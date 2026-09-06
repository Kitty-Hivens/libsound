package dev.hivens.libsound.audio.pulse

import dev.hivens.libsound.AudioCard
import dev.hivens.libsound.AudioStream
import dev.hivens.libsound.Capabilities
import dev.hivens.libsound.Capability
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

    private val closed = AtomicBoolean(false)

    private val listeners = CopyOnWriteArrayList<(StreamEvent) -> Unit>()

    /**
     * Volume and mute as we first found them, kept apart on purpose.
     *
     * One snapshot covering both would restore a field this process never
     * touched -- lower somebody's volume, and an unrelated mute the user set
     * afterwards would be undone along with it.
     */
    private val originalVolumes = ConcurrentHashMap<Handle, Float>()
    private val originalMutes = ConcurrentHashMap<Handle, Boolean>()

    /**
     * Channel count per stream. A cvolume carries its own channel count and the
     * server matches it against the stream's: sending a fixed two at a mono
     * stream is a request a strict server is entitled to reject, and half the
     * streams on a desktop are mono.
     */
    private val channelCounts = ConcurrentHashMap<Handle, Int>()

    private val sinkNames = ConcurrentHashMap<Int, String>()

    /** The same, one facility along: a capture row names the source it reads. */
    private val sourceNames = ConcurrentHashMap<Int, String>()

    /** Which device each stream was last seen on, so a meter knows where to listen. */
    private val lastDeviceIndexes = ConcurrentHashMap<Handle, Int>()

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

    @Volatile
    private var controlPending = false

    private var controlSuccess = false

    private lateinit var sinkInputStub: MemorySegment
    private lateinit var sourceOutputStub: MemorySegment
    private lateinit var sinkStub: MemorySegment
    private lateinit var sourceStub: MemorySegment
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
        val handle = Handle.parse(id) ?: return false
        if (closed.get()) return false
        return roundTrip.withLock {
            rememberVolume(handle)
            applyVolume(handle, volume)
        }
    }

    override fun setMuted(id: StreamId, muted: Boolean): Boolean {
        val handle = Handle.parse(id) ?: return false
        if (closed.get()) return false
        return roundTrip.withLock {
            rememberMute(handle)
            applyMute(handle, muted)
        }
    }

    /** A capture stream moves to another input; the call differs, the shape does not. */
    override fun moveTo(id: StreamId, device: DeviceId): Boolean {
        val handle = Handle.parse(id) ?: return false
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

    override fun setDeviceVolume(device: DeviceId, volume: Float): Boolean = false

    override fun setDeviceMuted(device: DeviceId, muted: Boolean): Boolean = false

    override fun setDefaultDevice(device: DeviceId): Boolean = false

    override fun cards(): List<AudioCard> = emptyList()

    override fun setCardProfile(card: CardId, profile: String): Boolean = false

    override fun setDevicePort(device: DeviceId, port: String): Boolean = false

    override fun createVirtualSink(name: String, channels: Int): DeviceId? = null

    override fun removeVirtualSink(id: DeviceId): Boolean = false

    override fun combineSinks(name: String, devices: List<DeviceId>): DeviceId? = null

    override fun restoreAll() {
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
        val handle = Handle.parse(id) ?: return {}
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
    private fun monitorSourceFor(handle: Handle): String? {
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
    private fun rowSinkIndex(handle: Handle): Int? {
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

    private fun restoreOnClose() {
        val outstanding = originalVolumes.size + originalMutes.size
        if (outstanding == 0) return
        log.info("restoring {} stream setting(s) this process changed", outstanding)
        restoreAll()
    }

    // -- control, each waiting for the server's own answer ----------------------

    private fun applyVolume(handle: Handle, volume: Float): Boolean {
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

    private fun applyMute(handle: Handle, muted: Boolean): Boolean {
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

    private fun rememberVolume(handle: Handle) {
        if (originalVolumes.containsKey(handle)) return
        val current = find(handle.id()) ?: return
        originalVolumes.putIfAbsent(handle, current.volume)
    }

    private fun rememberMute(handle: Handle) {
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
            if (info.address() == 0L) return@runCatching
            val head = info.reinterpret(SINK_HEAD)
            val index = head.get(ValueLayout.JAVA_INT, PulseAbi.SINK_INFO_INDEX)
            val name = head.get(ValueLayout.ADDRESS, PulseAbi.SINK_INFO_NAME).readCString()
            if (name != null) sinkNames[index] = name
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
            if (info.address() == 0L) return@runCatching
            val head = info.reinterpret(SOURCE_HEAD)
            val index = head.get(ValueLayout.JAVA_INT, PulseAbi.SOURCE_INFO_INDEX)
            val name = head.get(ValueLayout.ADDRESS, PulseAbi.SOURCE_INFO_NAME).readCString()
            if (name != null) sourceNames[index] = name
        }.onFailure { log.warn("source callback threw: {}", it.message) }
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
        val handle = Handle(direction, index)
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

    /**
     * A stream, and which facility it came from.
     *
     * The pair is the identity: a sink input and a source output can carry the
     * same index at the same time, so an index alone would have a microphone
     * row and a playback row answering to one id.
     */
    private data class Handle(val direction: StreamDirection, val index: Int) {
        fun id(): StreamId = StreamId("${prefix(direction)}:$index")

        override fun toString(): String = id().value

        companion object {
            fun prefix(direction: StreamDirection): String = when (direction) {
                StreamDirection.PLAYBACK -> "sink-input"
                StreamDirection.CAPTURE -> "source-output"
            }

            /** Null for anything this mixer did not hand out. */
            fun parse(id: StreamId): Handle? {
                val facility = id.value.substringBefore(':', missingDelimiterValue = "")
                val index = id.value.substringAfter(':', missingDelimiterValue = "").toIntOrNull() ?: return null
                val direction = StreamDirection.entries.firstOrNull { prefix(it) == facility } ?: return null
                return Handle(direction, index)
            }
        }
    }

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
        val handle: Handle get() = Handle(direction, index)
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

        private const val SINK_HEAD = 32L

        /** Enough of pa_source_info to reach the name and the index. */
        private const val SOURCE_HEAD = 32L
        private const val INTROSPECT_TIMEOUT_NANOS = 2_000_000_000L

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
