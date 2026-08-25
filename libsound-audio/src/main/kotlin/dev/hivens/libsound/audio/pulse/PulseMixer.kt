package dev.hivens.libsound.audio.pulse

import dev.hivens.libsound.AudioStream
import dev.hivens.libsound.Capabilities
import dev.hivens.libsound.Capability
import dev.hivens.libsound.DeviceId
import dev.hivens.libsound.MediaRole
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
    private val originalVolumes = ConcurrentHashMap<Int, Float>()
    private val originalMutes = ConcurrentHashMap<Int, Boolean>()

    /**
     * Channel count per stream. A cvolume carries its own channel count and the
     * server matches it against the stream's: sending a fixed two at a mono
     * stream is a request a strict server is entitled to reject, and half the
     * streams on a desktop are mono.
     */
    private val channelCounts = ConcurrentHashMap<Int, Int>()

    private val sinkNames = ConcurrentHashMap<Int, String>()

    /** Which sink each stream was last seen on, so a meter knows where to listen. */
    private val lastSinkIndexes = ConcurrentHashMap<Int, Int>()

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
    private lateinit var sinkStub: MemorySegment
    private lateinit var subscribeStub: MemorySegment
    private lateinit var successStub: MemorySegment
    private lateinit var monitorStub: MemorySegment

    override val capabilities: Capabilities = Capabilities.of(
        Capability.STREAM_ENUMERATION,
        Capability.STREAM_CONTROL,
        Capability.STREAM_ROUTING,
        Capability.STREAM_METERING,
    )

    override val isOpen: Boolean get() = !closed.get()

    override fun streams(): List<AudioStream> {
        if (closed.get()) return emptyList()
        val collected = roundTrip.withLock {
            val listed = pulse.locked {
                rows.clear()
                rowsComplete = false
                currentGeneration = generation.incrementAndGet()
                val op = lib.handle("pa_context_get_sink_input_info_list")
                    .invokeExact(pulse.context, sinkInputStub, MemorySegment.ofAddress(currentGeneration)) as MemorySegment
                if (op.address() == 0L) return@locked null
                pulse.releaseOperation(op)
                if (!awaitFlag { rowsComplete }) return@locked null
                rows.toList()
            } ?: return emptyList()
            // Name any device we have not seen yet. Priming at open catches the
            // sinks that existed then; this catches one plugged in since, which
            // is otherwise a row whose device column stays blank for the life of
            // the mixer.
            listed.asSequence().map { it.sinkIndex }.distinct()
                .filter { it != INVALID_INDEX && !sinkNames.containsKey(it) }
                .forEach { resolveSinkName(it) }
            listed
        }
        collected.forEach {
            channelCounts[it.index] = it.channels
            if (it.sinkIndex != INVALID_INDEX) lastSinkIndexes[it.index] = it.sinkIndex
        }
        return collected.map { it.toStream() }
    }

    override fun setVolume(id: StreamId, volume: Float): Boolean {
        val index = id.value.toIntOrNull() ?: return false
        if (closed.get()) return false
        return roundTrip.withLock {
            rememberVolume(index)
            applyVolume(index, volume)
        }
    }

    override fun setMuted(id: StreamId, muted: Boolean): Boolean {
        val index = id.value.toIntOrNull() ?: return false
        if (closed.get()) return false
        return roundTrip.withLock {
            rememberMute(index)
            applyMute(index, muted)
        }
    }

    override fun moveTo(id: StreamId, device: DeviceId): Boolean {
        val index = id.value.toIntOrNull() ?: return false
        if (closed.get()) return false
        return awaitControl { call ->
            lib.handle("pa_context_move_sink_input_by_name").invokeExact(
                pulse.context, index, call.allocateUtf8(device.value),
                successStub, MemorySegment.NULL,
            ) as MemorySegment
        }
    }

    override fun restoreAll() {
        val volumes = originalVolumes.entries.map { it.key to it.value }
        volumes.forEach { originalVolumes.remove(it.first) }
        val mutes = originalMutes.entries.map { it.key to it.value }
        mutes.forEach { originalMutes.remove(it.first) }
        volumes.forEach { (index, volume) ->
            runCatching { applyVolume(index, volume) }
                .onFailure { log.debug("could not restore volume of stream {}: {}", index, it.message) }
        }
        mutes.forEach { (index, muted) ->
            runCatching { applyMute(index, muted) }
                .onFailure { log.debug("could not restore mute of stream {}: {}", index, it.message) }
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
        val index = id.value.toIntOrNull() ?: return {}
        if (closed.get()) return {}
        val monitor = monitorSourceFor(index) ?: run {
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
    private fun monitorSourceFor(index: Int): String? {
        val sinkIndex = rowSinkIndex(index) ?: return null
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

    /** Which sink a stream is on, from the last walk rather than a fresh one. */
    private fun rowSinkIndex(index: Int): Int? {
        streams()
        return lastSinkIndexes[index]
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

    private fun applyVolume(index: Int, volume: Float): Boolean {
        // The channel count comes from the cache rather than a fresh
        // enumeration: this runs during close(), when streams() answers empty
        // by design. Anything we are restoring was enumerated when we recorded
        // it, so the cache has it.
        val channels = (channelCounts[index] ?: FALLBACK_CHANNELS).coerceIn(1, PulseAbi.CHANNELS_MAX)
        return awaitControl { call ->
            val cvolume = call.allocate(PulseAbi.CVOLUME_SIZE, 4)
            val level = lib.handle("pa_sw_volume_from_linear")
                .invokeExact(volume.coerceIn(0f, 1f).toDouble()) as Int
            lib.handle("pa_cvolume_set").invokeExact(cvolume, channels, level) as MemorySegment
            lib.handle("pa_context_set_sink_input_volume").invokeExact(
                pulse.context, index, cvolume, successStub, MemorySegment.NULL,
            ) as MemorySegment
        }
    }

    private fun applyMute(index: Int, muted: Boolean): Boolean = awaitControl {
        lib.handle("pa_context_set_sink_input_mute").invokeExact(
            pulse.context, index, if (muted) 1 else 0, successStub, MemorySegment.NULL,
        ) as MemorySegment
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

    private fun rememberVolume(index: Int) {
        if (originalVolumes.containsKey(index)) return
        val current = find(index) ?: return
        originalVolumes.putIfAbsent(index, current.volume)
    }

    private fun rememberMute(index: Int) {
        if (originalMutes.containsKey(index)) return
        val current = find(index) ?: return
        originalMutes.putIfAbsent(index, current.muted)
    }

    /** Enumerates, so it must not be called while the mainloop lock is held. */
    private fun find(index: Int): AudioStream? =
        streams().firstOrNull { it.id.value == index.toString() }

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
                    index = head.get(ValueLayout.JAVA_INT, PulseAbi.SINK_INPUT_INDEX),
                    sinkIndex = head.get(ValueLayout.JAVA_INT, PulseAbi.SINK_INPUT_SINK),
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
        if ((event and PulseAbi.SUBSCRIPTION_EVENT_FACILITY_MASK) != PulseAbi.SUBSCRIPTION_EVENT_SINK_INPUT) return
        val kind = event and PulseAbi.SUBSCRIPTION_EVENT_TYPE_MASK
        val snapshot = listeners.toList()
        if (snapshot.isEmpty()) return
        runCatching {
            dispatch.execute {
                val streamEvent = when (kind) {
                    PulseAbi.SUBSCRIPTION_EVENT_REMOVE -> {
                        channelCounts.remove(index)
                        StreamEvent.Gone(StreamId(index.toString()))
                    }
                    else -> {
                        val stream = find(index) ?: return@execute
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

    /** One row as the callback read it, before the sink index has a name. */
    private class Row(
        val index: Int,
        val sinkIndex: Int,
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
    )

    private fun Row.toStream() = AudioStream(
        id = StreamId(index.toString()),
        applicationName = applicationName,
        applicationId = applicationId,
        iconName = iconName,
        mediaName = mediaName,
        mediaRole = role,
        device = sinkNames[sinkIndex]?.let { DeviceId(it) },
        volume = volume,
        muted = muted,
        active = active,
        isOurs = ours,
    )

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

    private fun resolveSinkName(index: Int) {
        pulse.locked {
            sinkLookupComplete = false
            val op = lib.handle("pa_context_get_sink_info_by_index")
                .invokeExact(pulse.context, index, sinkStub, MemorySegment.NULL) as MemorySegment
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
    private fun primeSinkNames() {
        roundTrip.withLock {
            pulse.locked {
                sinkLookupComplete = false
                val op = lib.handle("pa_context_get_sink_info_list")
                    .invokeExact(pulse.context, sinkStub, MemorySegment.NULL) as MemorySegment
                if (op.address() == 0L) return@locked
                pulse.releaseOperation(op)
                awaitFlag { sinkLookupComplete }
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
        sinkStub = linker.upcallStub(
            lookup.findVirtual(PulseMixer::class.java, "onSink", infoType).bindTo(this),
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
            val op = lib.handle("pa_context_subscribe").invokeExact(
                pulse.context, PulseAbi.SUBSCRIPTION_MASK_SINK_INPUT, MemorySegment.NULL, MemorySegment.NULL,
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
                    primeSinkNames()
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
