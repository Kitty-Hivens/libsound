package dev.hivens.libsound.audio.pulse

import dev.hivens.libsound.AudioCard
import dev.hivens.libsound.AudioStream
import dev.hivens.libsound.Capabilities
import dev.hivens.libsound.Capability
import dev.hivens.libsound.AudioDevice
import dev.hivens.libsound.CardId
import dev.hivens.libsound.DeviceId
import dev.hivens.libsound.MediaRole
import dev.hivens.libsound.StreamDirection
import dev.hivens.libsound.StreamEvent
import dev.hivens.libsound.StreamId
import dev.hivens.libsound.VolumeMixer
import org.slf4j.LoggerFactory
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Everyone else's streams, over the same libpulse the sink uses.
 *
 * `pa_context_get_sink_input_info_list` beside the `..._sink_info_list` the
 * output backend already binds: same callback shape, same introspection wait,
 * same subscription. Which is why this is not a separate artifact -- it shares
 * the native dependency, and a module boundary drawn by subject rather than by
 * dependency would buy a consumer nothing.
 *
 * ## What it puts back
 *
 * A sound server remembers per-application volume, so this is the one surface in
 * the library that writes state outliving its process. Volume and mute are each
 * recorded against the value they replaced, separately, so that restoring a
 * volume this process lowered does not also undo a mute the user set meanwhile.
 * [close] restores whatever is still outstanding. That covers an orderly exit
 * and not a crash, which is why a consumer whose ducking is temporary should
 * prefer the media role where the desktop honours it: a role vanishes with the
 * stream that asked for it, and a volume does not.
 */
internal class PulseMixer private constructor(
    private val pulse: PulseContext,
) : VolumeMixer {

    private val log = LoggerFactory.getLogger("libsound.Mixer")

    private val lib = pulse.lib

    /** One transcription of the device structs, shared with the backend. */
    private val reader = PulseDeviceReader(lib)

    private val closed = AtomicBoolean(false)

    private val listeners = CopyOnWriteArrayList<(StreamEvent) -> Unit>()

    /**
     * Volume and mute as we first found them, kept apart on purpose.
     *
     * One snapshot covering both would restore a field this process never
     * touched -- lower somebody's volume, and an unrelated mute the user set
     * afterwards would be undone along with it.
     */
    private val originalVolumes = ConcurrentHashMap<PulseStreamHandle, Float>()
    private val originalMutes = ConcurrentHashMap<PulseStreamHandle, Boolean>()

    /**
     * Channel count per stream. A cvolume carries its own channel count and the
     * server matches it against the stream's: sending a fixed two at a mono
     * stream is a request a strict server is entitled to reject, and half the
     * streams on a desktop are mono.
     */
    private val channelCounts = ConcurrentHashMap<PulseStreamHandle, Int>()

    private val sinkNames = ConcurrentHashMap<Int, String>()

    /** The same, one facility along: a capture row names the source it reads. */
    private val sourceNames = ConcurrentHashMap<Int, String>()

    /**
     * Devices by name, as the last walk saw them.
     *
     * A DeviceId is a name and the server's setters take names, but the call to
     * make differs by facility and a cvolume has to carry the device's own
     * channel count. Both come from here rather than from a round trip per
     * control, which is also what makes a restore possible during close, when
     * enumeration answers empty by design.
     */
    private val deviceRows = ConcurrentHashMap<String, DeviceRow>()

    /** Device volume and mute as we first found them, kept apart for the same reason streams' are. */
    private val originalDeviceVolumes = ConcurrentHashMap<String, Float>()
    private val originalDeviceMutes = ConcurrentHashMap<String, Boolean>()

    /** Which device each stream was last seen on, so a meter knows where to listen. */
    private val lastDeviceIndexes = ConcurrentHashMap<PulseStreamHandle, Int>()

    /** Live meters, closed with the mixer so none outlives the connection. */
    private val meters = CopyOnWriteArrayList<PulseMeter>()

    @Volatile
    private var monitorName: String? = null

    /**
     * One round trip at a time.
     *
     * `pa_threaded_mainloop_wait` releases the mainloop lock while it waits, so
     * two threads issuing introspection at once would each collect into the
     * other's buffer and read the other's answer. That is a contended path
     * rather than a theoretical one: the subscription dispatcher enumerates on
     * every event, while a consumer may be enumerating for its own reasons.
     *
     * Always taken before the mainloop lock, never while holding it.
     */
    private val roundTrip = ReentrantLock()

    /** Handlers never run on the mainloop thread; see PulseBackend for why. */
    private val dispatch = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "libsound-mixer-events").apply { isDaemon = true }
    }

    // Written on the mainloop thread by the upcalls, read by the thread holding
    // roundTrip that issued the call. The completion flags are volatile and are
    // written last, so the reader that sees a flag set also sees everything the
    // callback wrote before it -- the mainloop's own mutex is invisible to the
    // Java memory model and cannot be relied on for that edge.
    private val rows = mutableListOf<Row>()

    @Volatile
    private var rowsComplete = false

    /**
     * Which round trip the callbacks are answering.
     *
     * `awaitFlag` gives up on a deadline, but giving up does not cancel the
     * operation -- `pa_operation_unref` drops our reference and the server
     * finishes it anyway. Without this, the abandoned request's end-of-list
     * satisfied the *next* request's wait, and the caller got a half-collected
     * list that looked complete. The generation goes out as the callback's
     * userdata and comes back with every reply.
     */
    private val generation = AtomicLong(0)

    @Volatile
    private var currentGeneration = 0L

    @Volatile
    private var sinkLookupComplete = false

    /**
     * Devices this process created, by the name they were given.
     *
     * The obligation that comes with them is stronger than the one that comes
     * with a volume: a virtual sink left behind after a crash is not quiet
     * audio a user can fix in their mixer, it is a device in their settings
     * that nothing owns and nothing will remove.
     */
    private val ownedModules = ConcurrentHashMap<String, Int>()

    @Volatile
    private var loadedModuleIndex = PulseAbi.INVALID_INDEX

    @Volatile
    private var loadPending = false

    private val collectedCards = mutableListOf<AudioCard>()

    @Volatile
    private var cardsComplete = false

    @Volatile
    private var controlPending = false

    private var controlSuccess = false

    private lateinit var sinkInputStub: MemorySegment
    private lateinit var sourceOutputStub: MemorySegment
    private lateinit var sinkStub: MemorySegment
    private lateinit var sourceStub: MemorySegment
    private lateinit var cardStub: MemorySegment
    private lateinit var moduleIndexStub: MemorySegment
    private lateinit var subscribeStub: MemorySegment
    private lateinit var successStub: MemorySegment
    private lateinit var monitorStub: MemorySegment

    override val capabilities: Capabilities = Capabilities.of(
        Capability.STREAM_ENUMERATION,
        Capability.STREAM_CONTROL,
        Capability.STREAM_ROUTING,
        Capability.STREAM_METERING,
        // The capture half, which is the same three questions asked of the
        // other facility. Metering is deliberately not among them: see [meter].
        Capability.CAPTURE_ENUMERATION,
        Capability.CAPTURE_CONTROL,
        Capability.CAPTURE_ROUTING,
        // The devices themselves, which is the other half of a mixer: one
        // application quieted, and the speaker everything plays through.
        Capability.DEVICE_VOLUME,
        Capability.DEVICE_PROFILES,
        Capability.VIRTUAL_DEVICES,
    )

    override val isOpen: Boolean get() = !closed.get()

    /**
     * Both facilities, in one list.
     *
     * Two round trips rather than one, because the server has two lists and no
     * call that answers both. They are taken under the same lock so that a
     * consumer cannot see half of a change, and the rows carry
     * [AudioStream.direction] so that a panel drawing one half can filter.
     */
    override fun streams(): List<AudioStream> {
        if (closed.get()) return emptyList()
        val collected = roundTrip.withLock {
            val playback = walk("pa_context_get_sink_input_info_list", sinkInputStub) ?: return emptyList()
            val capture = walk("pa_context_get_source_output_info_list", sourceOutputStub) ?: emptyList()
            // Name any device we have not seen yet. Priming at open catches the
            // devices that existed then; this catches one plugged in since,
            // which is otherwise a row whose device column stays blank for the
            // life of the mixer.
            playback.asSequence().map { it.deviceIndex }.distinct()
                .filter { it != INVALID_INDEX && !sinkNames.containsKey(it) }
                .forEach { resolveDeviceName(StreamDirection.PLAYBACK, it) }
            capture.asSequence().map { it.deviceIndex }.distinct()
                .filter { it != INVALID_INDEX && !sourceNames.containsKey(it) }
                .forEach { resolveDeviceName(StreamDirection.CAPTURE, it) }
            playback + capture
        }
        collected.forEach {
            channelCounts[it.handle] = it.channels
            if (it.deviceIndex != INVALID_INDEX) lastDeviceIndexes[it.handle] = it.deviceIndex
        }
        return collected.map { it.toStream() }
    }

    /** One introspection walk into [rows]. Caller holds [roundTrip]. */
    private fun walk(symbol: String, stub: MemorySegment): List<Row>? = pulse.locked {
        rows.clear()
        rowsComplete = false
        currentGeneration = generation.incrementAndGet()
        val op = lib.handle(symbol)
            .invokeExact(pulse.context, stub, MemorySegment.ofAddress(currentGeneration)) as MemorySegment
        if (op.address() == 0L) return@locked null
        pulse.releaseOperation(op)
        if (!awaitFlag { rowsComplete }) return@locked null
        rows.toList()
    }

    override fun setVolume(id: StreamId, volume: Float): Boolean {
        val handle = PulseStreamHandle.parse(id) ?: return false
        if (closed.get()) return false
        return roundTrip.withLock {
            rememberVolume(handle)
            applyVolume(handle, volume)
        }
    }

    override fun setMuted(id: StreamId, muted: Boolean): Boolean {
        val handle = PulseStreamHandle.parse(id) ?: return false
        if (closed.get()) return false
        return roundTrip.withLock {
            rememberMute(handle)
            applyMute(handle, muted)
        }
    }

    /** A capture stream moves to another input; the call differs, the shape does not. */
    override fun moveTo(id: StreamId, device: DeviceId): Boolean {
        val handle = PulseStreamHandle.parse(id) ?: return false
        if (closed.get()) return false
        val symbol = when (handle.direction) {
            StreamDirection.PLAYBACK -> "pa_context_move_sink_input_by_name"
            StreamDirection.CAPTURE -> "pa_context_move_source_output_by_name"
        }
        return awaitControl { call ->
            lib.handle(symbol).invokeExact(
                pulse.context, handle.index, call.allocateUtf8(device.value),
                successStub, MemorySegment.NULL,
            ) as MemorySegment
        }
    }

    override fun setDeviceVolume(device: DeviceId, volume: Float): Boolean {
        if (closed.get()) return false
        val row = deviceRow(device) ?: return false
        return roundTrip.withLock {
            rememberDeviceVolume(device, row)
            applyDeviceVolume(device, row, volume)
        }
    }

    override fun setDeviceMuted(device: DeviceId, muted: Boolean): Boolean {
        if (closed.get()) return false
        val row = deviceRow(device) ?: return false
        return roundTrip.withLock {
            rememberDeviceMute(device, row)
            applyDeviceMute(device, row, muted)
        }
    }

    /**
     * Not undone by [restoreAll], unlike everything else here.
     *
     * A default a user picked in a settings screen is a decision rather than a
     * change made on their behalf, and putting it back at the end of the
     * process would undo the thing they asked for.
     */
    override fun setDefaultDevice(device: DeviceId): Boolean {
        if (closed.get()) return false
        val row = deviceRow(device) ?: return false
        val symbol = when (row.device.direction) {
            StreamDirection.PLAYBACK -> "pa_context_set_default_sink"
            StreamDirection.CAPTURE -> "pa_context_set_default_source"
        }
        return awaitControl { call ->
            lib.handle(symbol).invokeExact(
                pulse.context, call.allocateUtf8(device.value), successStub, MemorySegment.NULL,
            ) as MemorySegment
        }
    }

    override fun cards(): List<AudioCard> {
        if (closed.get()) return emptyList()
        return roundTrip.withLock {
            pulse.locked {
                collectedCards.clear()
                cardsComplete = false
                val op = lib.handle("pa_context_get_card_info_list")
                    .invokeExact(pulse.context, cardStub, MemorySegment.NULL) as MemorySegment
                if (op.address() == 0L) return@locked emptyList()
                pulse.releaseOperation(op)
                if (!awaitFlag { cardsComplete }) return@locked emptyList()
                collectedCards.toList()
            }
        }
    }

    override fun setCardProfile(card: CardId, profile: String): Boolean {
        if (closed.get()) return false
        return awaitControl { call ->
            lib.handle("pa_context_set_card_profile_by_name").invokeExact(
                pulse.context, call.allocateUtf8(card.value), call.allocateUtf8(profile),
                successStub, MemorySegment.NULL,
            ) as MemorySegment
        }
    }

    override fun setDevicePort(device: DeviceId, port: String): Boolean {
        if (closed.get()) return false
        val row = deviceRow(device) ?: return false
        val symbol = when (row.device.direction) {
            StreamDirection.PLAYBACK -> "pa_context_set_sink_port_by_name"
            StreamDirection.CAPTURE -> "pa_context_set_source_port_by_name"
        }
        return awaitControl { call ->
            lib.handle(symbol).invokeExact(
                pulse.context, call.allocateUtf8(device.value), call.allocateUtf8(port),
                successStub, MemorySegment.NULL,
            ) as MemorySegment
        }
    }

    override fun createVirtualSink(name: String, channels: Int): DeviceId? {
        if (closed.get()) return null
        val safe = sanitise(name) ?: return null
        val map = CHANNEL_MAPS[channels.coerceIn(1, CHANNEL_MAPS.size)] ?: return null
        return loadModule(
            safe,
            "module-null-sink",
            "sink_name=$safe channel_map=$map sink_properties=device.description=$safe",
        )
    }

    override fun removeVirtualSink(id: DeviceId): Boolean {
        // Only what this process created. Unloading a module somebody else
        // loaded is exactly the kind of reach this library does not take, and a
        // caller that could ask for it by name would be one call away from
        // removing a user's own configuration.
        val index = ownedModules.remove(id.value) ?: return false
        return unloadModule(index)
    }

    override fun combineSinks(name: String, devices: List<DeviceId>): DeviceId? {
        if (closed.get()) return null
        val safe = sanitise(name) ?: return null
        if (devices.isEmpty()) return null
        val slaves = devices.map { sanitise(it.value) ?: return null }
        return loadModule(
            safe,
            "module-combine-sink",
            "sink_name=$safe slaves=${slaves.joinToString(",")}",
        )
    }

    override fun restoreAll() {
        // Modules first, and the order is load-bearing: a stream restored onto
        // a device that is about to vanish ends up somewhere nobody chose.
        removeOwnedModules()
        restoreDevices()
        val volumes = originalVolumes.entries.map { it.key to it.value }
        volumes.forEach { originalVolumes.remove(it.first) }
        val mutes = originalMutes.entries.map { it.key to it.value }
        mutes.forEach { originalMutes.remove(it.first) }
        volumes.forEach { (handle, volume) ->
            runCatching { applyVolume(handle, volume) }
                .onFailure { log.debug("could not restore volume of stream {}: {}", handle, it.message) }
        }
        mutes.forEach { (handle, muted) ->
            runCatching { applyMute(handle, muted) }
                .onFailure { log.debug("could not restore mute of stream {}: {}", handle, it.message) }
        }
    }

    override fun onStreamsChanged(handler: (StreamEvent) -> Unit): () -> Unit {
        listeners.add(handler)
        return { listeners.remove(handler) }
    }

    /**
     * A monitor stream per watch, torn down by the cancel this returns.
     *
     * The peak arrives on the mainloop thread, so it is handed to the dispatcher
     * before the consumer sees it -- the same rule as every other callback here,
     * and the more important for running at [PulseAbi.METER_RATE] a second.
     */
    override fun meter(id: StreamId, handler: (Float) -> Unit): () -> Unit {
        val handle = PulseStreamHandle.parse(id) ?: return {}
        if (closed.get()) return {}
        // Playback only, and Capability.CAPTURE_METERING is absent to say so.
        // The narrowing this is built on, pa_stream_set_monitor_stream, takes a
        // sink input index and exists because a sink's monitor carries
        // everything the sink plays. A real source has no such call: the only
        // level available for a capture row is the device's own, shared by
        // every application reading it, and showing one application's row
        // moving because another is talking would be worse than showing no
        // meter at all.
        if (handle.direction != StreamDirection.PLAYBACK) return {}
        val index = handle.index
        val monitor = monitorSourceFor(handle) ?: run {
            log.debug("no monitor source for stream {}; nothing to meter", index)
            return {}
        }
        val meter = PulseMeter.openOrNull(
            pulse, monitor, index,
            onPeak = { peak ->
                runCatching { dispatch.execute { runCatching { handler(peak) } } }
            },
            onFailure = { log.debug("meter for {} failed: {}", index, it) },
        ) ?: return {}
        meters.add(meter)
        return {
            if (meters.remove(meter)) meter.close()
        }
    }

    /**
     * The monitor source of the sink the stream is playing to.
     *
     * A monitor belongs to a sink rather than to a stream, so this is two
     * questions: which sink, then that sink's monitor. A stream that is not
     * routed anywhere has neither.
     */
    private fun monitorSourceFor(handle: PulseStreamHandle): String? {
        val sinkIndex = rowSinkIndex(handle) ?: return null
        return roundTrip.withLock {
            monitorName = null
            pulse.locked {
                sinkLookupComplete = false
                val op = lib.handle("pa_context_get_sink_info_by_index")
                    .invokeExact(pulse.context, sinkIndex, monitorStub, MemorySegment.NULL) as MemorySegment
                if (op.address() == 0L) return@locked
                pulse.releaseOperation(op)
                awaitFlag { sinkLookupComplete }
            }
            monitorName
        }
    }

    /** Which device a stream is on, from the last walk rather than a fresh one. */
    private fun rowSinkIndex(handle: PulseStreamHandle): Int? {
        streams()
        return lastDeviceIndexes[handle]
    }

    /** Reads only the monitor source name; the device list has its own callback. */
    fun onMonitorSink(unusedContext: MemorySegment, info: MemorySegment, eol: Int, unusedUserData: MemorySegment) {
        runCatching {
            if (eol != 0) {
                sinkLookupComplete = true
                pulse.signal()
                return@runCatching
            }
            if (info.address() == 0L) return@runCatching
            monitorName = info.reinterpret(PulseAbi.SINK_INFO_MONITOR_HEAD)
                .get(ValueLayout.ADDRESS, PulseAbi.SINK_INFO_MONITOR_SOURCE_NAME).readCString()
        }.onFailure { log.warn("monitor sink callback threw: {}", it.message) }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        listeners.clear()
        // Drain the event thread before restoring: it enumerates, and an
        // enumeration in flight holds the round-trip lock the restore needs.
        // Its work stops quickly because streams() answers empty once closed.
        dispatch.shutdown()
        runCatching { dispatch.awaitTermination(2, TimeUnit.SECONDS) }
        meters.forEach { runCatching { it.close() } }
        meters.clear()
        runCatching { restoreOnClose() }
        // Under the round-trip lock, because closing frees the mainloop and the
        // arena holding every upcall stub. A caller that passed the closed check
        // a moment earlier is inside a round trip right now, and the flag alone
        // does not wait for it.
        roundTrip.withLock { pulse.close() }
    }

    private fun restoreDevices() {
        val volumes = originalDeviceVolumes.entries.map { it.key to it.value }
        volumes.forEach { originalDeviceVolumes.remove(it.first) }
        val mutes = originalDeviceMutes.entries.map { it.key to it.value }
        mutes.forEach { originalDeviceMutes.remove(it.first) }
        volumes.forEach { (name, volume) ->
            val row = deviceRows[name] ?: return@forEach
            runCatching { applyDeviceVolume(DeviceId(name), row, volume) }
                .onFailure { log.debug("could not restore volume of device {}: {}", name, it.message) }
        }
        mutes.forEach { (name, muted) ->
            val row = deviceRows[name] ?: return@forEach
            runCatching { applyDeviceMute(DeviceId(name), row, muted) }
                .onFailure { log.debug("could not restore mute of device {}: {}", name, it.message) }
        }
    }

    private fun removeOwnedModules() {
        val owned = ownedModules.entries.map { it.key to it.value }
        owned.forEach { ownedModules.remove(it.first) }
        owned.forEach { (name, index) ->
            runCatching { unloadModule(index) }
                .onFailure { log.warn("could not remove the virtual device {}: {}", name, it.message) }
        }
    }

    /**
     * Load a module and remember what it made, or null when the server refused.
     *
     * The index comes back through a callback rather than a return value, which
     * is why this waits: a caller that got a device id without knowing the load
     * succeeded would have a name for something that does not exist.
     */
    private fun loadModule(name: String, module: String, argument: String): DeviceId? = roundTrip.withLock {
        val index = pulse.locked {
            Arena.ofConfined().use { call ->
                loadedModuleIndex = PulseAbi.INVALID_INDEX
                loadPending = true
                val op = lib.handle("pa_context_load_module").invokeExact(
                    pulse.context, call.allocateUtf8(module), call.allocateUtf8(argument),
                    moduleIndexStub, MemorySegment.NULL,
                ) as MemorySegment
                if (op.address() == 0L) {
                    loadPending = false
                    return@locked PulseAbi.INVALID_INDEX
                }
                pulse.releaseOperation(op)
                if (!awaitFlag { !loadPending }) return@locked PulseAbi.INVALID_INDEX
                loadedModuleIndex
            }
        }
        if (index == PulseAbi.INVALID_INDEX) {
            log.info("the server refused {} ({})", module, pulse.lastError())
            return@withLock null
        }
        ownedModules[name] = index
        // The device list changed, so the cached rows are one device short
        // until somebody walks it again.
        primeDeviceNames()
        DeviceId(name)
    }

    private fun unloadModule(index: Int): Boolean = awaitControl {
        lib.handle("pa_context_unload_module").invokeExact(
            pulse.context, index, successStub, MemorySegment.NULL,
        ) as MemorySegment
    }

    /**
     * A module argument is a flat string of `key=value` pairs, so a name with a
     * space or a quote in it would be read as more arguments than were meant.
     * Refused rather than escaped: the set of names a device may have is not
     * this library's to widen, and a caller that gets null has been told
     * exactly what happened.
     */
    private fun sanitise(name: String): String? =
        name.takeIf { it.isNotBlank() && it.length <= MAX_DEVICE_NAME && it.all(::isNameCharacter) }

    private fun isNameCharacter(character: Char): Boolean =
        character.isLetterOrDigit() || character == '_' || character == '.' || character == '-'

    private fun restoreOnClose() {
        val outstanding = originalVolumes.size + originalMutes.size +
            originalDeviceVolumes.size + originalDeviceMutes.size + ownedModules.size
        if (outstanding == 0) return
        log.info("restoring {} setting(s) this process changed", outstanding)
        restoreAll()
    }

    // -- devices ----------------------------------------------------------------

    /** One device as the last walk saw it, with the channel count a cvolume needs. */
    private class DeviceRow(val device: AudioDevice, val channels: Int)

    /**
     * The row for a device, refreshing the cache once if the name is new.
     *
     * A name that is in neither facility after a refresh is a device that is
     * gone, and every control on it answers false rather than guessing which
     * call to make.
     */
    private fun deviceRow(device: DeviceId): DeviceRow? {
        deviceRows[device.value]?.let { return it }
        primeDeviceNames()
        return deviceRows[device.value]
    }

    private fun applyDeviceVolume(device: DeviceId, row: DeviceRow, volume: Float): Boolean {
        val channels = row.channels.coerceIn(1, PulseAbi.CHANNELS_MAX)
        val symbol = when (row.device.direction) {
            StreamDirection.PLAYBACK -> "pa_context_set_sink_volume_by_name"
            StreamDirection.CAPTURE -> "pa_context_set_source_volume_by_name"
        }
        return awaitControl { call ->
            val cvolume = call.allocate(PulseAbi.CVOLUME_SIZE, 4)
            val level = lib.handle("pa_sw_volume_from_linear")
                .invokeExact(volume.coerceIn(0f, 1f).toDouble()) as Int
            lib.handle("pa_cvolume_set").invokeExact(cvolume, channels, level) as MemorySegment
            lib.handle(symbol).invokeExact(
                pulse.context, call.allocateUtf8(device.value), cvolume, successStub, MemorySegment.NULL,
            ) as MemorySegment
        }
    }

    private fun applyDeviceMute(device: DeviceId, row: DeviceRow, muted: Boolean): Boolean {
        val symbol = when (row.device.direction) {
            StreamDirection.PLAYBACK -> "pa_context_set_sink_mute_by_name"
            StreamDirection.CAPTURE -> "pa_context_set_source_mute_by_name"
        }
        return awaitControl { call ->
            lib.handle(symbol).invokeExact(
                pulse.context, call.allocateUtf8(device.value), if (muted) 1 else 0,
                successStub, MemorySegment.NULL,
            ) as MemorySegment
        }
    }

    private fun rememberDeviceVolume(device: DeviceId, row: DeviceRow) {
        if (originalDeviceVolumes.containsKey(device.value)) return
        originalDeviceVolumes.putIfAbsent(device.value, row.device.volume ?: return)
    }

    private fun rememberDeviceMute(device: DeviceId, row: DeviceRow) {
        if (originalDeviceMutes.containsKey(device.value)) return
        originalDeviceMutes.putIfAbsent(device.value, row.device.muted ?: return)
    }

    // -- control, each waiting for the server's own answer ----------------------

    private fun applyVolume(handle: PulseStreamHandle, volume: Float): Boolean {
        // The channel count comes from the cache rather than a fresh
        // enumeration: this runs during close(), when streams() answers empty
        // by design. Anything we are restoring was enumerated when we recorded
        // it, so the cache has it.
        val channels = (channelCounts[handle] ?: FALLBACK_CHANNELS).coerceIn(1, PulseAbi.CHANNELS_MAX)
        val symbol = when (handle.direction) {
            StreamDirection.PLAYBACK -> "pa_context_set_sink_input_volume"
            StreamDirection.CAPTURE -> "pa_context_set_source_output_volume"
        }
        return awaitControl { call ->
            val cvolume = call.allocate(PulseAbi.CVOLUME_SIZE, 4)
            val level = lib.handle("pa_sw_volume_from_linear")
                .invokeExact(volume.coerceIn(0f, 1f).toDouble()) as Int
            lib.handle("pa_cvolume_set").invokeExact(cvolume, channels, level) as MemorySegment
            lib.handle(symbol).invokeExact(
                pulse.context, handle.index, cvolume, successStub, MemorySegment.NULL,
            ) as MemorySegment
        }
    }

    private fun applyMute(handle: PulseStreamHandle, muted: Boolean): Boolean {
        val symbol = when (handle.direction) {
            StreamDirection.PLAYBACK -> "pa_context_set_sink_input_mute"
            StreamDirection.CAPTURE -> "pa_context_set_source_output_mute"
        }
        return awaitControl {
            lib.handle(symbol).invokeExact(
                pulse.context, handle.index, if (muted) 1 else 0, successStub, MemorySegment.NULL,
            ) as MemorySegment
        }
    }

    /**
     * Issue an operation and return what the server said about it.
     *
     * Not whether the request was accepted: `pa_context_*` hands back a live
     * operation for a stream that has already gone, and reports the failure
     * through the callback a moment later. A mixer slider that springs back when
     * the application closes mid-drag can only be drawn on top of the real
     * answer.
     */
    private fun awaitControl(issue: (Arena) -> MemorySegment): Boolean = roundTrip.withLock {
        pulse.locked {
            Arena.ofConfined().use { call ->
                controlSuccess = false
                controlPending = true
                val op = runCatching { issue(call) }.getOrElse {
                    controlPending = false
                    throw it
                }
                if (op.address() == 0L) {
                    controlPending = false
                    return@locked false
                }
                pulse.releaseOperation(op)
                if (!awaitFlag { !controlPending }) return@locked false
                controlSuccess
            }
        }
    }

    private fun rememberVolume(handle: PulseStreamHandle) {
        if (originalVolumes.containsKey(handle)) return
        val current = find(handle.id()) ?: return
        originalVolumes.putIfAbsent(handle, current.volume)
    }

    private fun rememberMute(handle: PulseStreamHandle) {
        if (originalMutes.containsKey(handle)) return
        val current = find(handle.id()) ?: return
        originalMutes.putIfAbsent(handle, current.muted)
    }

    /** Enumerates, so it must not be called while the mainloop lock is held. */
    private fun find(id: StreamId): AudioStream? = streams().firstOrNull { it.id == id }

    // -- upcalls, on the mainloop thread with its lock held ---------------------

    // Public rather than internal: Kotlin mangles an internal name and
    // findVirtual looks up what is written.

    fun onSinkInput(unusedContext: MemorySegment, info: MemorySegment, eol: Int, userData: MemorySegment) {
        runCatching {
            // A reply to a request nobody is waiting for any more. Answering it
            // would end the round trip that is waiting now.
            if (userData.address() != currentGeneration) return@runCatching
            if (eol != 0) {
                rowsComplete = true
                pulse.signal()
                return@runCatching
            }
            if (info.address() == 0L) return@runCatching
            val head = info.reinterpret(PulseAbi.SINK_INPUT_HEAD)
            val proplist = head.get(ValueLayout.ADDRESS, PulseAbi.SINK_INPUT_PROPLIST)
            val cvolume = head.asSlice(PulseAbi.SINK_INPUT_VOLUME, PulseAbi.CVOLUME_SIZE)
            rows.add(
                Row(
                    direction = StreamDirection.PLAYBACK,
                    index = head.get(ValueLayout.JAVA_INT, PulseAbi.SINK_INPUT_INDEX),
                    deviceIndex = head.get(ValueLayout.JAVA_INT, PulseAbi.SINK_INPUT_SINK),
                    applicationName = prop(proplist, PulseAbi.PROP_APPLICATION_NAME)
                        ?: prop(proplist, PulseAbi.PROP_APPLICATION_PROCESS_BINARY),
                    applicationId = prop(proplist, PulseAbi.PROP_APPLICATION_ID),
                    iconName = prop(proplist, PulseAbi.PROP_APPLICATION_ICON_NAME),
                    mediaName = prop(proplist, PulseAbi.PROP_MEDIA_NAME),
                    role = roleOf(prop(proplist, PulseAbi.PROP_MEDIA_ROLE)),
                    volume = readVolume(cvolume),
                    channels = cvolume.get(ValueLayout.JAVA_BYTE, PulseAbi.CVOLUME_CHANNELS).toInt() and 0xFF,
                    muted = head.get(ValueLayout.JAVA_INT, PulseAbi.SINK_INPUT_MUTE) != 0,
                    active = head.get(ValueLayout.JAVA_INT, PulseAbi.SINK_INPUT_CORKED) == 0,
                    ours = prop(proplist, PulseAbi.PROP_APPLICATION_PROCESS_ID)?.toLongOrNull() == OUR_PID,
                ),
            )
        }.onFailure { log.warn("sink input callback threw: {}", it.message) }
    }

    /**
     * The capture half, and deliberately the same shape as [onSinkInput]: one
     * facility along, one struct along, the same generation guard.
     */
    fun onSourceOutput(unusedContext: MemorySegment, info: MemorySegment, eol: Int, userData: MemorySegment) {
        runCatching {
            if (userData.address() != currentGeneration) return@runCatching
            if (eol != 0) {
                rowsComplete = true
                pulse.signal()
                return@runCatching
            }
            if (info.address() == 0L) return@runCatching
            val head = info.reinterpret(PulseAbi.SOURCE_OUTPUT_HEAD)
            val proplist = head.get(ValueLayout.ADDRESS, PulseAbi.SOURCE_OUTPUT_PROPLIST)
            val cvolume = head.asSlice(PulseAbi.SOURCE_OUTPUT_VOLUME, PulseAbi.CVOLUME_SIZE)
            // A source output need not have a volume of its own, and where it
            // has none the field above holds nothing the server chose.
            val hasVolume = head.get(ValueLayout.JAVA_INT, PulseAbi.SOURCE_OUTPUT_HAS_VOLUME) != 0
            rows.add(
                Row(
                    direction = StreamDirection.CAPTURE,
                    index = head.get(ValueLayout.JAVA_INT, PulseAbi.SOURCE_OUTPUT_INDEX),
                    deviceIndex = head.get(ValueLayout.JAVA_INT, PulseAbi.SOURCE_OUTPUT_SOURCE),
                    applicationName = prop(proplist, PulseAbi.PROP_APPLICATION_NAME)
                        ?: prop(proplist, PulseAbi.PROP_APPLICATION_PROCESS_BINARY),
                    applicationId = prop(proplist, PulseAbi.PROP_APPLICATION_ID),
                    iconName = prop(proplist, PulseAbi.PROP_APPLICATION_ICON_NAME),
                    mediaName = prop(proplist, PulseAbi.PROP_MEDIA_NAME),
                    role = roleOf(prop(proplist, PulseAbi.PROP_MEDIA_ROLE)),
                    volume = if (hasVolume) readVolume(cvolume) else 1f,
                    channels = cvolume.get(ValueLayout.JAVA_BYTE, PulseAbi.CVOLUME_CHANNELS).toInt() and 0xFF,
                    muted = head.get(ValueLayout.JAVA_INT, PulseAbi.SOURCE_OUTPUT_MUTE) != 0,
                    active = head.get(ValueLayout.JAVA_INT, PulseAbi.SOURCE_OUTPUT_CORKED) == 0,
                    ours = prop(proplist, PulseAbi.PROP_APPLICATION_PROCESS_ID)?.toLongOrNull() == OUR_PID,
                ),
            )
        }.onFailure { log.warn("source output callback threw: {}", it.message) }
    }

    fun onSink(unusedContext: MemorySegment, info: MemorySegment, eol: Int, unusedUserData: MemorySegment) {
        runCatching {
            if (eol != 0) {
                sinkLookupComplete = true
                pulse.signal()
                return@runCatching
            }
            val device = reader.sink(info) ?: return@runCatching
            val index = info.reinterpret(PulseAbi.SINK_INFO_HEAD)
                .get(ValueLayout.JAVA_INT, PulseAbi.SINK_INFO_INDEX)
            sinkNames[index] = device.id.value
            // The whole row, not only the name: a control needs the facility to
            // call into and the channel count to send, and a restore needs the
            // value that was there first.
            deviceRows[device.id.value] = DeviceRow(
                device,
                reader.channels(info.reinterpret(PulseAbi.SINK_INFO_HEAD), PulseAbi.SINK_INFO_VOLUME),
            )
        }.onFailure { log.warn("sink callback threw: {}", it.message) }
    }

    /** The capture device list, read only for the names a row shows. */
    fun onSource(unusedContext: MemorySegment, info: MemorySegment, eol: Int, unusedUserData: MemorySegment) {
        runCatching {
            if (eol != 0) {
                sinkLookupComplete = true
                pulse.signal()
                return@runCatching
            }
            val device = reader.source(info) ?: return@runCatching
            val index = info.reinterpret(PulseAbi.SOURCE_INFO_HEAD)
                .get(ValueLayout.JAVA_INT, PulseAbi.SOURCE_INFO_INDEX)
            sourceNames[index] = device.id.value
            deviceRows[device.id.value] = DeviceRow(
                device,
                reader.channels(info.reinterpret(PulseAbi.SOURCE_INFO_HEAD), PulseAbi.SOURCE_INFO_VOLUME),
            )
        }.onFailure { log.warn("source callback threw: {}", it.message) }
    }

    fun onCard(unusedContext: MemorySegment, info: MemorySegment, eol: Int, unusedUserData: MemorySegment) {
        runCatching {
            if (eol != 0) {
                cardsComplete = true
                pulse.signal()
                return@runCatching
            }
            reader.card(info)?.let(collectedCards::add)
        }.onFailure { log.warn("card callback threw: {}", it.message) }
    }

    /** The module index a load produced, or PA_INVALID_INDEX when it failed. */
    fun onModuleIndex(unusedContext: MemorySegment, index: Int, unusedUserData: MemorySegment) {
        runCatching {
            loadedModuleIndex = index
            loadPending = false
            pulse.signal()
        }
    }

    fun onControlSuccess(unusedContext: MemorySegment, success: Int, unusedUserData: MemorySegment) {
        runCatching {
            controlSuccess = success != 0
            controlPending = false
            pulse.signal()
        }
    }

    fun onSubscribe(unusedContext: MemorySegment, event: Int, index: Int, unusedUserData: MemorySegment) {
        // The event packs facility and kind into one int; reading either without
        // masking gives a number matching nothing.
        val direction = when (event and PulseAbi.SUBSCRIPTION_EVENT_FACILITY_MASK) {
            PulseAbi.SUBSCRIPTION_EVENT_SINK_INPUT -> StreamDirection.PLAYBACK
            PulseAbi.SUBSCRIPTION_EVENT_SOURCE_OUTPUT -> StreamDirection.CAPTURE
            else -> return
        }
        val handle = PulseStreamHandle(direction, index)
        val kind = event and PulseAbi.SUBSCRIPTION_EVENT_TYPE_MASK
        val snapshot = listeners.toList()
        if (snapshot.isEmpty()) return
        runCatching {
            dispatch.execute {
                val streamEvent = when (kind) {
                    PulseAbi.SUBSCRIPTION_EVENT_REMOVE -> {
                        channelCounts.remove(handle)
                        lastDeviceIndexes.remove(handle)
                        StreamEvent.Gone(handle.id())
                    }
                    else -> {
                        val stream = find(handle.id()) ?: return@execute
                        if (kind == PulseAbi.SUBSCRIPTION_EVENT_NEW) StreamEvent.Appeared(stream)
                        else StreamEvent.Changed(stream)
                    }
                }
                snapshot.forEach { listener ->
                    runCatching { listener(streamEvent) }
                        .onFailure { log.warn("stream listener threw: {}", it.message) }
                }
            }
        }
    }

    // -- internals --------------------------------------------------------------

    /** One row as the callback read it, before the device index has a name. */
    private class Row(
        val direction: StreamDirection,
        val index: Int,
        val deviceIndex: Int,
        val applicationName: String?,
        val applicationId: String?,
        val iconName: String?,
        val mediaName: String?,
        val role: MediaRole?,
        val volume: Float,
        val channels: Int,
        val muted: Boolean,
        val active: Boolean,
        val ours: Boolean,
    ) {
        val handle: PulseStreamHandle get() = PulseStreamHandle(direction, index)
    }

    private fun Row.toStream() = AudioStream(
        id = handle.id(),
        applicationName = applicationName,
        applicationId = applicationId,
        iconName = iconName,
        mediaName = mediaName,
        mediaRole = role,
        device = deviceNames(direction)[deviceIndex]?.let { DeviceId(it) },
        volume = volume,
        muted = muted,
        active = active,
        isOurs = ours,
        direction = direction,
    )

    private fun deviceNames(direction: StreamDirection): Map<Int, String> = when (direction) {
        StreamDirection.PLAYBACK -> sinkNames
        StreamDirection.CAPTURE -> sourceNames
    }

    /** The loudest channel, which is what a mixer slider shows. */
    private fun readVolume(cvolume: MemorySegment): Float {
        val raw = lib.handle("pa_cvolume_max").invokeExact(cvolume) as Int
        return (lib.handle("pa_sw_volume_to_linear").invokeExact(raw) as Double)
            .toFloat().coerceIn(0f, 1f)
    }

    private fun prop(proplist: MemorySegment, key: String): String? {
        if (proplist.address() == 0L) return null
        return Arena.ofConfined().use { call ->
            (lib.handle("pa_proplist_gets").invokeExact(proplist, call.allocateUtf8(key)) as MemorySegment)
                .readCString()
        }
    }

    private fun roleOf(wireName: String?): MediaRole? =
        wireName?.lowercase()?.let { name -> MediaRole.entries.firstOrNull { it.wireName == name } }

    private inline fun awaitFlag(done: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + INTROSPECT_TIMEOUT_NANOS
        while (!done()) {
            val state = lib.handle("pa_context_get_state").invokeExact(pulse.context) as Int
            if (state != PulseAbi.CONTEXT_READY) return false
            if (System.nanoTime() > deadline) {
                log.warn("introspection timed out")
                return false
            }
            pulse.await()
        }
        return true
    }

    private fun resolveDeviceName(direction: StreamDirection, index: Int) {
        val symbol = when (direction) {
            StreamDirection.PLAYBACK -> "pa_context_get_sink_info_by_index"
            StreamDirection.CAPTURE -> "pa_context_get_source_info_by_index"
        }
        val stub = when (direction) {
            StreamDirection.PLAYBACK -> sinkStub
            StreamDirection.CAPTURE -> sourceStub
        }
        pulse.locked {
            sinkLookupComplete = false
            val op = lib.handle(symbol)
                .invokeExact(pulse.context, index, stub, MemorySegment.NULL) as MemorySegment
            if (op.address() == 0L) return@locked
            pulse.releaseOperation(op)
            awaitFlag { sinkLookupComplete }
        }
    }

    /**
     * Name every sink that exists now, before the first enumeration.
     *
     * Waited on rather than fired and forgotten: the first `streams()` call
     * otherwise reports a null device for every row, which is indistinguishable
     * from a backend that cannot tell.
     */
    private fun primeDeviceNames() {
        roundTrip.withLock {
            listOf(
                "pa_context_get_sink_info_list" to sinkStub,
                "pa_context_get_source_info_list" to sourceStub,
            ).forEach { (symbol, stub) ->
                pulse.locked {
                    sinkLookupComplete = false
                    val op = lib.handle(symbol)
                        .invokeExact(pulse.context, stub, MemorySegment.NULL) as MemorySegment
                    if (op.address() == 0L) return@locked
                    pulse.releaseOperation(op)
                    awaitFlag { sinkLookupComplete }
                }
            }
        }
    }

    private fun installStubs() {
        val linker = Linker.nativeLinker()
        val lookup = MethodHandles.lookup()
        val addr = ValueLayout.ADDRESS
        val i32 = ValueLayout.JAVA_INT
        val infoType = MethodType.methodType(
            Void.TYPE, MemorySegment::class.java, MemorySegment::class.java,
            Int::class.javaPrimitiveType, MemorySegment::class.java,
        )
        sinkInputStub = linker.upcallStub(
            lookup.findVirtual(PulseMixer::class.java, "onSinkInput", infoType).bindTo(this),
            FunctionDescriptor.ofVoid(addr, addr, i32, addr), lib.arena,
        )
        sourceOutputStub = linker.upcallStub(
            lookup.findVirtual(PulseMixer::class.java, "onSourceOutput", infoType).bindTo(this),
            FunctionDescriptor.ofVoid(addr, addr, i32, addr), lib.arena,
        )
        sinkStub = linker.upcallStub(
            lookup.findVirtual(PulseMixer::class.java, "onSink", infoType).bindTo(this),
            FunctionDescriptor.ofVoid(addr, addr, i32, addr), lib.arena,
        )
        sourceStub = linker.upcallStub(
            lookup.findVirtual(PulseMixer::class.java, "onSource", infoType).bindTo(this),
            FunctionDescriptor.ofVoid(addr, addr, i32, addr), lib.arena,
        )
        cardStub = linker.upcallStub(
            lookup.findVirtual(PulseMixer::class.java, "onCard", infoType).bindTo(this),
            FunctionDescriptor.ofVoid(addr, addr, i32, addr), lib.arena,
        )
        val intPairType = MethodType.methodType(
            Void.TYPE, MemorySegment::class.java, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, MemorySegment::class.java,
        )
        subscribeStub = linker.upcallStub(
            lookup.findVirtual(PulseMixer::class.java, "onSubscribe", intPairType).bindTo(this),
            FunctionDescriptor.ofVoid(addr, i32, i32, addr), lib.arena,
        )
        monitorStub = linker.upcallStub(
            lookup.findVirtual(PulseMixer::class.java, "onMonitorSink", infoType).bindTo(this),
            FunctionDescriptor.ofVoid(addr, addr, i32, addr), lib.arena,
        )
        moduleIndexStub = linker.upcallStub(
            lookup.findVirtual(
                PulseMixer::class.java, "onModuleIndex",
                MethodType.methodType(
                    Void.TYPE, MemorySegment::class.java, Int::class.javaPrimitiveType,
                    MemorySegment::class.java,
                ),
            ).bindTo(this),
            FunctionDescriptor.ofVoid(addr, i32, addr), lib.arena,
        )
        successStub = linker.upcallStub(
            lookup.findVirtual(
                PulseMixer::class.java, "onControlSuccess",
                MethodType.methodType(
                    Void.TYPE, MemorySegment::class.java, Int::class.javaPrimitiveType,
                    MemorySegment::class.java,
                ),
            ).bindTo(this),
            FunctionDescriptor.ofVoid(addr, i32, addr), lib.arena,
        )
    }

    private fun subscribe() {
        pulse.locked {
            lib.handle("pa_context_set_subscribe_callback")
                .invokeExact(pulse.context, subscribeStub, MemorySegment.NULL) as Unit
            val mask = PulseAbi.SUBSCRIPTION_MASK_SINK_INPUT or PulseAbi.SUBSCRIPTION_MASK_SOURCE_OUTPUT
            val op = lib.handle("pa_context_subscribe").invokeExact(
                pulse.context, mask, MemorySegment.NULL, MemorySegment.NULL,
            ) as MemorySegment
            pulse.releaseOperation(op)
        }
    }

    internal companion object {
        private val log = LoggerFactory.getLogger("libsound.Mixer")

        private val OUR_PID = ProcessHandle.current().pid()

        /**
         * Only reached when a restore runs for a stream whose channel count was
         * never seen, which the recording path makes unreachable. Stereo is the
         * least surprising thing to send if it ever is.
         */
        private const val FALLBACK_CHANNELS = 2

        private const val INTROSPECT_TIMEOUT_NANOS = 2_000_000_000L

        /** Long enough for a description, short enough not to be an argument list. */
        private const val MAX_DEVICE_NAME = 64

        /**
         * The channel maps a virtual sink can be asked for. Named rather than
         * generated: a map is a list of channel positions the server knows, and
         * an invented one is refused at load time with a message nobody reads.
         */
        private val CHANNEL_MAPS = mapOf(
            1 to "mono",
            2 to "front-left,front-right",
        )

        /** `PA_INVALID_INDEX`, which a sink input carries when it is not routed. */
        private const val INVALID_INDEX = -1

        /**
         * Open a mixer, or null where there is no sound server.
         *
         * Its own connection, not one shared with an output backend: they would
         * work on one, but a mixer subscribing to every stream event on the
         * connection a sink is writing through puts introspection traffic on the
         * path that carries audio timing.
         */
        fun openOrNull(applicationName: String): VolumeMixer? {
            val context = PulseContext.connectOrNull(applicationName) ?: return null
            return runCatching {
                PulseMixer(context).apply {
                    installStubs()
                    primeDeviceNames()
                    subscribe()
                }
            }.getOrElse {
                log.debug("mixer unavailable: {}", it.message)
                context.close()
                null
            }
        }
    }
}
