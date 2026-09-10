package dev.hivens.libsound.audio.pulse

import dev.hivens.libsound.AudioBackend
import dev.hivens.libsound.AudioDevice
import dev.hivens.libsound.AudioException
import dev.hivens.libsound.AudioFormat
import dev.hivens.libsound.AudioSink
import dev.hivens.libsound.AudioSource
import dev.hivens.libsound.Capabilities
import dev.hivens.libsound.Capability
import dev.hivens.libsound.DeviceId
import dev.hivens.libsound.PcmEncoding
import dev.hivens.libsound.SampleId
import dev.hivens.libsound.SinkConfig
import dev.hivens.libsound.SourceConfig
import dev.hivens.libsound.StreamDirection
import dev.hivens.libsound.StreamId
import org.slf4j.LoggerFactory
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The PulseAudio backend, which is also the PipeWire backend: `pipewire-pulse`
 * speaks the same protocol, so one binding covers both servers and the choice
 * between them stops being this library's problem.
 *
 * Everything the fallback cannot do lives here -- a named stream, volume the
 * desktop's mixer shows and follows, a device list worth offering, and events
 * when the default moves.
 */
internal class PulseBackend private constructor(
    private val pulse: PulseContext,
) : AudioBackend {

    private val log = LoggerFactory.getLogger("libsound.Pulse")

    private val lib = pulse.lib

    /** One transcription of the device structs, shared with the mixer. */
    private val reader = PulseDeviceReader(lib)

    override val name: String = "pulse"

    /**
     * Fixed for this backend's lifetime, and [Capability.DUCKS_OTHERS] is the
     * one entry decided by asking the server rather than by what this code can
     * do.
     *
     * Computed on first read rather than at construction, because the answer
     * comes from a round trip that cannot happen until the stubs are installed.
     * Eager, it would be built before the question was asked and would report
     * absent on every machine, which is precisely the lie this capability was
     * added to avoid.
     */
    override val capabilities: Capabilities by lazy {
        Capabilities(
            buildSet {
                add(Capability.STREAM_VOLUME)
                add(Capability.STREAM_IDENTITY)
                add(Capability.DEVICE_ENUMERATION)
                add(Capability.DEVICE_SELECTION)
                add(Capability.DEVICE_EVENTS)
                add(Capability.DEVICE_POSITION)
                add(Capability.CAPTURE)
                add(Capability.PER_STREAM_CAPTURE)
                add(Capability.DEVICE_VOLUME)
                add(Capability.LOW_LATENCY)
                add(Capability.TOTAL_LATENCY)
                add(Capability.CHANNEL_PLACEMENT)
                if (sampleCacheWorks) add(Capability.SAMPLE_CACHE)
                add(Capability.UNDERRUN_COUNT)
                if (rolePolicyLoaded) add(Capability.DUCKS_OTHERS)
            },
        )
    }

    /**
     * Device-change handlers run here, never on the mainloop thread.
     *
     * libpulse delivers the subscription callback on its own thread with the
     * mainloop lock held, and the natural response to the event -- re-reading
     * the device list -- calls pa_threaded_mainloop_wait. On the mainloop
     * thread that parks the loop waiting for a signal only that loop could
     * deliver, and audio, introspection and teardown all stop for good.
     */
    private val eventDispatch = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "libsound-pulse-events").apply { isDaemon = true }
    }

    private val sinks = CopyOnWriteArrayList<PulseSink>()
    private val sources = CopyOnWriteArrayList<PulseSource>()

    /**
     * Samples this process uploaded, removed on close.
     *
     * The same obligation as everything else here that outlives a process: a
     * sound left in the server's cache is state a user did not ask for and
     * cannot see.
     */
    private val ownedSamples = CopyOnWriteArrayList<String>()
    private val deviceListeners = CopyOnWriteArrayList<() -> Unit>()

    /**
     * One introspection round trip at a time.
     *
     * Holding the mainloop lock is not enough on its own:
     * `pa_threaded_mainloop_wait` releases it while it waits, so two threads
     * asking for the device list would each collect into the other's buffer.
     * A settings screen refreshing while the subscription dispatcher answers a
     * default-sink change is exactly that, and is the ordinary case rather than
     * a contrived one.
     *
     * Always taken before the mainloop lock, never while holding it.
     */
    private val roundTrip = ReentrantLock()

    private val closed = AtomicBoolean(false)

    // Written on the mainloop thread by the upcalls, read by the thread holding
    // roundTrip that issued the call. The completion flags are volatile and are
    // written last, so a reader that sees one set also sees everything the
    // callback wrote before it. The mainloop's own mutex cannot supply that
    // edge: it is native, and the Java memory model cannot see it.
    private val collected = mutableListOf<AudioDevice>()

    @Volatile
    private var collectComplete = false

    @Volatile
    private var defaultSinkName: String? = null

    @Volatile
    private var defaultSourceName: String? = null

    @Volatile
    private var serverInfoComplete = false

    @Volatile
    private var moduleListComplete = false

    /** True once a module that acts on media roles has been seen in the list. */
    @Volatile
    private var rolePolicyLoaded = false

    /** True once an upload has been proven to survive being uploaded. */
    @Volatile
    private var sampleCacheWorks = false

    private lateinit var sinkInfoStub: MemorySegment
    private lateinit var sourceInfoStub: MemorySegment
    private lateinit var successStub: MemorySegment

    @Volatile
    private var controlPending = false

    private var controlSuccess = false

    @Volatile
    private var sampleFound = false

    @Volatile
    private var sampleLookupComplete = false

    private lateinit var sampleInfoStub: MemorySegment

    /** Which sink a single sink input is playing to, and that sink's monitor. */
    @Volatile
    private var sinkInputSinkIndex = PulseAbi.INVALID_INDEX

    @Volatile
    private var sinkInputLookupComplete = false

    @Volatile
    private var monitorSourceName: String? = null

    @Volatile
    private var monitorLookupComplete = false

    private lateinit var sinkInputStub: MemorySegment
    private lateinit var monitorSinkStub: MemorySegment
    private lateinit var serverInfoStub: MemorySegment
    private lateinit var subscribeStub: MemorySegment
    private lateinit var moduleInfoStub: MemorySegment

    override fun createSink(config: SinkConfig): AudioSink {
        val sink = PulseSink(pulse, config, SINK_CAPABILITIES)
        sinks.add(sink)
        return sink
    }

    override fun devices(): List<AudioDevice> {
        if (closed.get()) return emptyList()
        return roundTrip.withLock {
            val default = queryDefaultSinkName()
            pulse.locked {
                collected.clear()
                collectComplete = false
                val op = lib.handle("pa_context_get_sink_info_list")
                    .invokeExact(pulse.context, sinkInfoStub, MemorySegment.NULL) as MemorySegment
                if (op.address() == 0L) return@locked emptyList()
                pulse.releaseOperation(op)
                if (!awaitFlag { collectComplete }) return@locked emptyList()
                collected.map { it.copy(isDefault = it.id.value == default) }
            }
        }
    }

    /** The list already carries the answer, so asking the server twice buys nothing. */
    override fun defaultDevice(): AudioDevice? = devices().firstOrNull { it.isDefault }

    override fun createSource(config: SourceConfig): AudioSource {
        if (closed.get()) throw AudioException("backend is closed")
        val monitor = config.captureStream?.let { stream ->
            monitorTargetFor(stream)
                ?: throw AudioException("no playback stream $stream to record")
        }
        val source = PulseSource(pulse, config, SOURCE_CAPABILITIES, monitor)
        sources.add(source)
        return source
    }

    /**
     * Where one application's audio can be listened to: the monitor of the sink
     * it is playing to, narrowed to that stream.
     *
     * Two round trips, because the server answers two questions and neither
     * alone is enough. Null for a stream that is not playing, is not there, or
     * is on a device with no monitor.
     */
    private fun monitorTargetFor(id: StreamId): PulseSource.MonitorTarget? {
        val handle = PulseStreamHandle.parse(id) ?: return null
        if (handle.direction != StreamDirection.PLAYBACK) return null
        return roundTrip.withLock {
            val sinkIndex = pulse.locked {
                sinkInputSinkIndex = PulseAbi.INVALID_INDEX
                sinkInputLookupComplete = false
                val op = lib.handle("pa_context_get_sink_input_info")
                    .invokeExact(pulse.context, handle.index, sinkInputStub, MemorySegment.NULL) as MemorySegment
                if (op.address() == 0L) return@locked PulseAbi.INVALID_INDEX
                pulse.releaseOperation(op)
                if (!awaitFlag { sinkInputLookupComplete }) PulseAbi.INVALID_INDEX else sinkInputSinkIndex
            }
            if (sinkIndex == PulseAbi.INVALID_INDEX) return@withLock null
            val monitor = pulse.locked {
                monitorSourceName = null
                monitorLookupComplete = false
                val op = lib.handle("pa_context_get_sink_info_by_index")
                    .invokeExact(pulse.context, sinkIndex, monitorSinkStub, MemorySegment.NULL) as MemorySegment
                if (op.address() == 0L) return@locked null
                pulse.releaseOperation(op)
                if (!awaitFlag { monitorLookupComplete }) null else monitorSourceName
            } ?: return@withLock null
            PulseSource.MonitorTarget(monitor, handle.index)
        }
    }

    /**
     * Sources, monitors included and marked as such.
     *
     * A monitor is a sink's output offered back as something to record, which is
     * a legitimate thing to want and not a microphone. Both are listed, with
     * [AudioDevice.isMonitor] telling them apart, because filtering here would
     * take away the per-application recording this library can actually do.
     */
    override fun captureDevices(): List<AudioDevice> {
        if (closed.get()) return emptyList()
        return roundTrip.withLock {
            val default = queryDefaultSourceName()
            pulse.locked {
                collected.clear()
                collectComplete = false
                val op = lib.handle("pa_context_get_source_info_list")
                    .invokeExact(pulse.context, sourceInfoStub, MemorySegment.NULL) as MemorySegment
                if (op.address() == 0L) return@locked emptyList()
                pulse.releaseOperation(op)
                if (!awaitFlag { collectComplete }) return@locked emptyList()
                collected.map { it.copy(isDefault = it.id.value == default) }
            }
        }
    }

    override fun defaultCaptureDevice(): AudioDevice? = captureDevices().firstOrNull { it.isDefault }

    /**
     * Upload the sound once, on the connection this backend already has.
     *
     * The upload is a stream like any other and lives only long enough to carry
     * the bytes: connect, write all of them, finish, let go. What is left
     * behind is a name the server answers to, which is the whole point of the
     * cache: triggering it costs no stream setup, no buffer to fill and no
     * scheduling, so a click is heard when it is clicked.
     */
    override fun cacheSample(name: String, format: AudioFormat, pcm: ByteArray): SampleId? {
        if (closed.get()) return null
        if (name.isBlank() || pcm.isEmpty()) return null
        if (pcm.size % format.bytesPerFrame != 0) return null
        val uploaded = roundTrip.withLock { upload(name, format, pcm) }
        if (!uploaded) return null
        ownedSamples.addIfAbsent(name)
        return SampleId(name)
    }

    override fun playSample(id: SampleId, device: DeviceId?, volume: Float): Boolean {
        if (closed.get()) return false
        return awaitSuccess { call ->
            val level = lib.handle("pa_sw_volume_from_linear")
                .invokeExact(volume.coerceIn(0f, 1f).toDouble()) as Int
            lib.handle("pa_context_play_sample").invokeExact(
                pulse.context, call.allocateUtf8(id.value),
                device?.let { call.allocateUtf8(it.value) } ?: MemorySegment.NULL,
                level, successStub, MemorySegment.NULL,
            ) as MemorySegment
        }
    }

    override fun onDevicesChanged(handler: () -> Unit): () -> Unit {
        deviceListeners.add(handler)
        return { deviceListeners.remove(handler) }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        removeOwnedSamples()
        sinks.forEach { runCatching { it.close() } }
        sinks.clear()
        sources.forEach { runCatching { it.close() } }
        sources.clear()
        deviceListeners.clear()
        // Drained, not killed. A handler runs on this thread and its natural
        // first move is to re-read the device list, which parks on the mainloop
        // -- a wait that shutdownNow cannot interrupt because it is native.
        // Tearing the context down underneath it would free what it is reading.
        // Its work ends quickly regardless: devices() answers empty once closed.
        eventDispatch.shutdown()
        runCatching { eventDispatch.awaitTermination(2, TimeUnit.SECONDS) }
        // Under the round-trip lock: closing frees the mainloop and the arena
        // holding the upcall stubs, and a caller that passed the closed check a
        // moment ago is inside a round trip now.
        roundTrip.withLock { pulse.close() }
    }

    // -- upcalls, all on the mainloop thread with its lock held --------------

    // Public rather than internal for the same reason as PulseContext's
    // callbacks: Kotlin mangles an internal name and findVirtual would miss it.
    fun onSinkInfo(unusedContext: MemorySegment, info: MemorySegment, eol: Int, unusedUserData: MemorySegment) {
        runCatching {
            if (eol != 0) {
                // Positive is end of list, negative is an error; either way
                // nothing more is coming and the caller must stop waiting.
                if (eol < 0) log.debug("sink info list ended with {}", eol)
                collectComplete = true
                pulse.signal()
                return@runCatching
            }
            // Volume, mute, suspended state and ports as well as the name: the
            // device itself rather than only its label, which is the half of a
            // mixer this backend could not reach before.
            reader.sink(info)?.let(collected::add)
        }.onFailure { log.warn("sink info callback threw: {}", it.message) }
    }

    fun onSourceInfo(unusedContext: MemorySegment, info: MemorySegment, eol: Int, unusedUserData: MemorySegment) {
        runCatching {
            if (eol != 0) {
                if (eol < 0) log.debug("source info list ended with {}", eol)
                collectComplete = true
                pulse.signal()
                return@runCatching
            }
            reader.source(info)?.let(collected::add)
        }.onFailure { log.warn("source info callback threw: {}", it.message) }
    }

    /** One sink input, read for the single field that says where it is playing. */
    fun onSinkInput(unusedContext: MemorySegment, info: MemorySegment, eol: Int, unusedUserData: MemorySegment) {
        runCatching {
            if (eol != 0) {
                sinkInputLookupComplete = true
                pulse.signal()
                return@runCatching
            }
            if (info.address() == 0L) return@runCatching
            sinkInputSinkIndex = info.reinterpret(PulseAbi.SINK_INPUT_HEAD)
                .get(ValueLayout.JAVA_INT, PulseAbi.SINK_INPUT_SINK)
        }.onFailure { log.warn("sink input callback threw: {}", it.message) }
    }

    /** One sink, read for the name of the source that carries what it plays. */
    fun onMonitorSink(unusedContext: MemorySegment, info: MemorySegment, eol: Int, unusedUserData: MemorySegment) {
        runCatching {
            if (eol != 0) {
                monitorLookupComplete = true
                pulse.signal()
                return@runCatching
            }
            if (info.address() == 0L) return@runCatching
            monitorSourceName = info.reinterpret(PulseAbi.SINK_INFO_MONITOR_HEAD)
                .get(ValueLayout.ADDRESS, PulseAbi.SINK_INFO_MONITOR_SOURCE_NAME).readCString()
        }.onFailure { log.warn("monitor sink callback threw: {}", it.message) }
    }

    fun onControlSuccess(unusedContext: MemorySegment, success: Int, unusedUserData: MemorySegment) {
        runCatching {
            controlSuccess = success != 0
            controlPending = false
            pulse.signal()
        }
    }

    /** One reply per existing sample, then an end of list; absence is the answer. */
    fun onSampleInfo(unusedContext: MemorySegment, info: MemorySegment, eol: Int, unusedUserData: MemorySegment) {
        runCatching {
            if (eol != 0) {
                sampleLookupComplete = true
                pulse.signal()
                return@runCatching
            }
            if (info.address() != 0L) sampleFound = true
        }.onFailure { log.warn("sample info callback threw: {}", it.message) }
    }

    fun onServerInfo(unusedContext: MemorySegment, info: MemorySegment, unusedUserData: MemorySegment) {
        runCatching {
            val head = if (info.address() == 0L) null else info.reinterpret(SERVER_INFO_HEAD)
            defaultSinkName = head?.get(ValueLayout.ADDRESS, PulseAbi.SERVER_INFO_DEFAULT_SINK_NAME)?.readCString()
            defaultSourceName = head?.get(ValueLayout.ADDRESS, PulseAbi.SERVER_INFO_DEFAULT_SOURCE_NAME)?.readCString()
            serverInfoComplete = true
            pulse.signal()
        }.onFailure { log.warn("server info callback threw: {}", it.message) }
    }

    /**
     * One module per call, ending with eol. Only the name is read: whether the
     * module is there at all is the entire question.
     */
    fun onModuleInfo(unusedContext: MemorySegment, info: MemorySegment, eol: Int, unusedUserData: MemorySegment) {
        runCatching {
            if (eol != 0) {
                moduleListComplete = true
                pulse.signal()
                return@runCatching
            }
            if (info.address() == 0L) return@runCatching
            val name = info.reinterpret(PulseAbi.MODULE_INFO_HEAD)
                .get(ValueLayout.ADDRESS, PulseAbi.MODULE_INFO_NAME).readCString()
            if (name in PulseAbi.ROLE_POLICY_MODULES) rolePolicyLoaded = true
        }.onFailure { log.warn("module info callback threw: {}", it.message) }
    }

    fun onSubscribe(
        unusedContext: MemorySegment,
        unusedEvent: Int,
        unusedIndex: Int,
        unusedUserData: MemorySegment,
    ) {
        // Deliberately coarse: the event says which sink changed, but every
        // consumer of this signal re-reads the whole list anyway, and a
        // per-device diff would be state to keep correct for no gain.
        val handlers = deviceListeners.toList()
        if (handlers.isEmpty()) return
        runCatching {
            eventDispatch.execute {
                handlers.forEach { handler ->
                    runCatching { handler() }.onFailure { log.warn("device listener threw: {}", it.message) }
                }
            }
        }.onFailure { log.debug("device event dropped, dispatcher is shut down") }
    }

    // -- internals -----------------------------------------------------------

    /**
     * The upload stream, start to finish. Caller holds [roundTrip].
     *
     * False for every refusal, and the refusals are ordinary: a server that
     * keeps no sample cache is one this returns false on rather than one this
     * throws at.
     */
    private fun upload(name: String, format: AudioFormat, pcm: ByteArray): Boolean = pulse.locked {
        Arena.ofConfined().use { call ->
            val spec = call.allocate(PulseAbi.SAMPLE_SPEC_SIZE, 4)
            spec.set(ValueLayout.JAVA_INT, PulseAbi.SAMPLE_SPEC_FORMAT, encodingOf(format) ?: return@locked false)
            spec.set(ValueLayout.JAVA_INT, PulseAbi.SAMPLE_SPEC_RATE, format.sampleRate)
            spec.set(ValueLayout.JAVA_BYTE, PulseAbi.SAMPLE_SPEC_CHANNELS, format.channels.toByte())

            val stream = lib.handle("pa_stream_new_with_proplist").invokeExact(
                pulse.context, call.allocateUtf8(name), spec, MemorySegment.NULL, MemorySegment.NULL,
            ) as MemorySegment
            if (stream.address() == 0L) return@locked false
            var finished = false
            try {
                lib.handle("pa_stream_set_state_callback")
                    .invokeExact(stream, pulse.notifyStub, MemorySegment.NULL) as Unit
                val rc = lib.handle("pa_stream_connect_upload").invokeExact(stream, pcm.size.toLong()) as Int
                if (rc < 0) return@locked false
                if (!awaitStreamReady(stream)) return@locked false

                val buffer = call.allocate(pcm.size.toLong(), 8)
                MemorySegment.copy(pcm, 0, buffer, ValueLayout.JAVA_BYTE, 0L, pcm.size)
                val written = lib.handle("pa_stream_write").invokeExact(
                    stream, buffer, pcm.size.toLong(), MemorySegment.NULL, 0L, PulseAbi.SEEK_RELATIVE,
                ) as Int
                if (written < 0) return@locked false
                // finish_upload is what makes the bytes a sample. It also
                // disconnects the stream, which is why nothing below
                // disconnects it again.
                finished = (lib.handle("pa_stream_finish_upload").invokeExact(stream) as Int) == 0
                finished
            } finally {
                lib.handle("pa_stream_set_state_callback")
                    .invokeExact(stream, MemorySegment.NULL, MemorySegment.NULL) as Unit
                if (!finished) runCatching { lib.handle("pa_stream_disconnect").invokeExact(stream) as Int }
                lib.handle("pa_stream_unref").invokeExact(stream) as Unit
            }
        }
    }

    /** Caller holds the mainloop lock. */
    private fun awaitStreamReady(stream: MemorySegment): Boolean {
        val deadline = System.nanoTime() + INTROSPECT_TIMEOUT_NANOS
        while (true) {
            val state = lib.handle("pa_stream_get_state").invokeExact(stream) as Int
            if (state == PulseAbi.STREAM_READY) return true
            if (state == PulseAbi.STREAM_FAILED || state == PulseAbi.STREAM_TERMINATED) return false
            if (System.nanoTime() > deadline) return false
            pulse.await()
        }
    }

    private fun removeOwnedSamples() {
        val owned = ownedSamples.toList()
        ownedSamples.clear()
        if (owned.isEmpty()) return
        log.info("removing {} cached sample(s) this process uploaded", owned.size)
        owned.forEach { name ->
            runCatching {
                awaitSuccess { call ->
                    lib.handle("pa_context_remove_sample").invokeExact(
                        pulse.context, call.allocateUtf8(name), successStub, MemorySegment.NULL,
                    ) as MemorySegment
                }
            }.onFailure { log.debug("could not remove the sample {}: {}", name, it.message) }
        }
    }

    /**
     * Issue an operation and answer what the server said about it, rather than
     * that the request went out. The same shape the mixer uses, and for the
     * same reason: libpulse hands back a live operation for a name that does
     * not exist and reports the refusal a moment later.
     */
    private fun awaitSuccess(issue: (Arena) -> MemorySegment): Boolean = roundTrip.withLock {
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

    private fun encodingOf(format: AudioFormat): Int? = PulseAbi.sampleFormatOf(format.encoding)

    /**
     * Whether this server keeps a sample cache at all, asked rather than
     * assumed.
     *
     * A silent frame is uploaded and looked up by name, then removed. Nothing
     * is played, so the probe is inaudible, and the answer is the one thing
     * that cannot be inferred from the protocol version: pipewire-pulse accepts
     * an upload and a client cannot tell from the reply whether anything was
     * kept.
     */
    private fun probeSampleCache() {
        val format = AudioFormat(48_000, 1)
        val silence = ByteArray(format.bytesPerFrame * SAMPLE_PROBE_FRAMES)
        val uploaded = roundTrip.withLock { upload(SAMPLE_PROBE_NAME, format, silence) }
        if (!uploaded) {
            log.debug("this server refused a sample upload; the cache is not offered")
            return
        }
        sampleCacheWorks = lookUpSample(SAMPLE_PROBE_NAME)
        runCatching {
            awaitSuccess { call ->
                lib.handle("pa_context_remove_sample").invokeExact(
                    pulse.context, call.allocateUtf8(SAMPLE_PROBE_NAME), successStub, MemorySegment.NULL,
                ) as MemorySegment
            }
        }
        log.debug("the sample cache keeps what it is given here: {}", sampleCacheWorks)
    }

    private fun lookUpSample(name: String): Boolean = roundTrip.withLock {
        pulse.locked {
            sampleFound = false
            sampleLookupComplete = false
            Arena.ofConfined().use { call ->
                val op = lib.handle("pa_context_get_sample_info_by_name").invokeExact(
                    pulse.context, call.allocateUtf8(name), sampleInfoStub, MemorySegment.NULL,
                ) as MemorySegment
                if (op.address() == 0L) return@locked false
                pulse.releaseOperation(op)
                if (!awaitFlag { sampleLookupComplete }) return@locked false
                sampleFound
            }
        }
    }

    /** Caller holds [roundTrip]. */
    private fun queryDefaultSinkName(): String? = queryServerInfo()?.let { defaultSinkName }

    /** Caller holds [roundTrip]. */
    private fun queryDefaultSourceName(): String? = queryServerInfo()?.let { defaultSourceName }

    /**
     * One round trip that answers both defaults, because the server sends both
     * in one struct and asking twice would be two round trips for one fact.
     * Caller holds [roundTrip].
     */
    private fun queryServerInfo(): Unit? = pulse.locked {
        defaultSinkName = null
        defaultSourceName = null
        serverInfoComplete = false
        val op = lib.handle("pa_context_get_server_info")
            .invokeExact(pulse.context, serverInfoStub, MemorySegment.NULL) as MemorySegment
        if (op.address() == 0L) return@locked null
        pulse.releaseOperation(op)
        if (!awaitFlag { serverInfoComplete }) null else Unit
    }

    /**
     * Park until [done], the connection drops, or the deadline passes.
     *
     * `pa_threaded_mainloop_wait` has no timeout of its own, so a server that
     * dies mid-operation would leave this parked for the life of the process.
     * The context state is checked on every wakeup for exactly that: an
     * introspection call is not worth hanging a consumer's settings screen.
     */
    private inline fun awaitFlag(done: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + INTROSPECT_TIMEOUT_NANOS
        while (!done()) {
            val state = lib.handle("pa_context_get_state").invokeExact(pulse.context) as Int
            if (state != PulseAbi.CONTEXT_READY) {
                log.debug("introspection abandoned: context state {}", state)
                return false
            }
            if (System.nanoTime() > deadline) {
                log.warn("introspection timed out after {} ms", INTROSPECT_TIMEOUT_NANOS / 1_000_000)
                return false
            }
            pulse.await()
        }
        return true
    }

    /**
     * Ask the server whether a media role does anything here.
     *
     * A role is enforced by the session manager, not by us, so the honest answer
     * comes from what the server loaded. Absent is the safe direction to be
     * wrong in: a consumer that believes ducking will not happen falls back to
     * setting volume directly, which works everywhere and merely has to put it
     * back afterwards.
     *
     * PipeWire reports its own modules over this protocol rather than
     * PulseAudio's, so the answer there is no. Its role policy lives in
     * WirePlumber, which the protocol cannot see, and claiming a capability on
     * the strength of not being able to check it is the failure this whole
     * mechanism exists to prevent.
     */
    private fun probeRolePolicy() {
        roundTrip.withLock {
            pulse.locked {
                moduleListComplete = false
                val op = lib.handle("pa_context_get_module_info_list")
                    .invokeExact(pulse.context, moduleInfoStub, MemorySegment.NULL) as MemorySegment
                if (op.address() == 0L) return@locked
                pulse.releaseOperation(op)
                awaitFlag { moduleListComplete }
            }
        }
        log.debug("media roles are acted on here: {}", rolePolicyLoaded)
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
        sinkInfoStub = linker.upcallStub(
            lookup.findVirtual(PulseBackend::class.java, "onSinkInfo", infoType).bindTo(this),
            FunctionDescriptor.ofVoid(addr, addr, i32, addr),
            lib.arena,
        )
        sourceInfoStub = linker.upcallStub(
            lookup.findVirtual(PulseBackend::class.java, "onSourceInfo", infoType).bindTo(this),
            FunctionDescriptor.ofVoid(addr, addr, i32, addr),
            lib.arena,
        )
        serverInfoStub = linker.upcallStub(
            lookup.findVirtual(
                PulseBackend::class.java, "onServerInfo",
                MethodType.methodType(
                    Void.TYPE, MemorySegment::class.java, MemorySegment::class.java, MemorySegment::class.java,
                ),
            ).bindTo(this),
            FunctionDescriptor.ofVoid(addr, addr, addr),
            lib.arena,
        )
        moduleInfoStub = linker.upcallStub(
            lookup.findVirtual(
                PulseBackend::class.java, "onModuleInfo",
                MethodType.methodType(
                    Void.TYPE, MemorySegment::class.java, MemorySegment::class.java,
                    Int::class.javaPrimitiveType, MemorySegment::class.java,
                ),
            ).bindTo(this),
            FunctionDescriptor.ofVoid(addr, addr, i32, addr),
            lib.arena,
        )
        successStub = linker.upcallStub(
            lookup.findVirtual(
                PulseBackend::class.java, "onControlSuccess",
                MethodType.methodType(
                    Void.TYPE, MemorySegment::class.java, Int::class.javaPrimitiveType,
                    MemorySegment::class.java,
                ),
            ).bindTo(this),
            FunctionDescriptor.ofVoid(addr, i32, addr),
            lib.arena,
        )
        sinkInputStub = linker.upcallStub(
            lookup.findVirtual(PulseBackend::class.java, "onSinkInput", infoType).bindTo(this),
            FunctionDescriptor.ofVoid(addr, addr, i32, addr),
            lib.arena,
        )
        monitorSinkStub = linker.upcallStub(
            lookup.findVirtual(PulseBackend::class.java, "onMonitorSink", infoType).bindTo(this),
            FunctionDescriptor.ofVoid(addr, addr, i32, addr),
            lib.arena,
        )
        sampleInfoStub = linker.upcallStub(
            lookup.findVirtual(PulseBackend::class.java, "onSampleInfo", infoType).bindTo(this),
            FunctionDescriptor.ofVoid(addr, addr, i32, addr),
            lib.arena,
        )
        subscribeStub = linker.upcallStub(
            lookup.findVirtual(
                PulseBackend::class.java, "onSubscribe",
                MethodType.methodType(
                    Void.TYPE, MemorySegment::class.java, Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType, MemorySegment::class.java,
                ),
            ).bindTo(this),
            FunctionDescriptor.ofVoid(addr, i32, i32, addr),
            lib.arena,
        )
    }

    private fun subscribe() {
        pulse.locked {
            lib.handle("pa_context_set_subscribe_callback")
                .invokeExact(pulse.context, subscribeStub, MemorySegment.NULL) as Unit
            // Sources as well as sinks: onDevicesChanged covers both
            // directions, and a microphone appearing is exactly the event a
            // consumer that draws an input menu is waiting for.
            val mask = PulseAbi.SUBSCRIPTION_MASK_SINK or
                PulseAbi.SUBSCRIPTION_MASK_SOURCE or
                PulseAbi.SUBSCRIPTION_MASK_SERVER
            val op = lib.handle("pa_context_subscribe")
                .invokeExact(pulse.context, mask, MemorySegment.NULL, MemorySegment.NULL) as MemorySegment
            pulse.releaseOperation(op)
        }
    }

    internal companion object {
        private val log = LoggerFactory.getLogger("libsound.Pulse")

        private const val SERVER_INFO_HEAD = 64L

        private const val INTROSPECT_TIMEOUT_NANOS = 2_000_000_000L

        /** Silent, inaudible, and long enough that no server rounds it away. */
        private const val SAMPLE_PROBE_FRAMES = 480

        /**
         * Per process, because two of them starting at once would otherwise
         * collide on the name and one would read the collision as a server
         * that keeps no cache.
         */
        private val SAMPLE_PROBE_NAME = "libsound-cache-probe-${ProcessHandle.current().pid()}"

        /**
         * What a *sink* can do, which is not what the backend can do. A sink
         * cannot enumerate devices, cannot subscribe to device events, and has
         * no way to change the device it was created against -- handing it the
         * backend's set claimed all three.
         */
        /**
         * What a source can do. The same shape as a sink's set and for the same
         * reason: it can name itself and set its own volume, and it can neither
         * enumerate devices nor subscribe to their events.
         */
        private val SOURCE_CAPABILITIES = Capabilities.of(
            Capability.CAPTURE,
            Capability.STREAM_VOLUME,
            Capability.STREAM_IDENTITY,
            Capability.DEVICE_POSITION,
            Capability.LOW_LATENCY,
            // pa_stream_get_latency answers for the whole path, client buffer
            // plus server plus device, which is what the contract asks for.
            Capability.TOTAL_LATENCY,
            Capability.CHANNEL_PLACEMENT,
        )

        private val SINK_CAPABILITIES = Capabilities.of(
            Capability.STREAM_VOLUME,
            Capability.STREAM_IDENTITY,
            Capability.DEVICE_POSITION,
            // The two that make a latency profile mean something: the server
            // shortens its own path to meet the request, and it says when it
            // ran dry trying.
            Capability.LOW_LATENCY,
            Capability.UNDERRUN_COUNT,
            Capability.TOTAL_LATENCY,
            // A pa_channel_map goes across with the sample spec, so the layout
            // a decoder interleaved to is the layout the server lays out.
            Capability.CHANNEL_PLACEMENT,
        )

        /** Connect and return the backend, or null when there is no sound server. */
        fun connectOrNull(applicationName: String): AudioBackend? {
            val context = PulseContext.connectOrNull(applicationName) ?: return null
            return runCatching {
                PulseBackend(context).apply {
                    installStubs()
                    // Before anything reads capabilities: the set is fixed at
                    // construction and DUCKS_OTHERS is the server's answer.
                    probeRolePolicy()
                    probeSampleCache()
                    subscribe()
                }
            }.getOrElse {
                log.debug("PulseAudio backend setup failed: {}", it.message)
                context.close()
                null
            }
        }
    }
}

/**
 * Read a `const char *` out of a pointer-valued field.
 *
 * Bounded rather than reinterpreted to `Long.MAX_VALUE`, which is what this did
 * and what the WASAPI reader already refuses to do for the reason written
 * there: a size is needed before the first read, and an unbounded one turns a
 * stray pointer into a scan of the whole address space. Everything read here is
 * a device name, a description or a proplist value, so the ceiling is a ceiling
 * and not an expectation.
 */
internal fun MemorySegment.readCString(): String? {
    if (address() == 0L) return null
    return runCatching { reinterpret(MAX_C_STRING_BYTES).getString(0) }.getOrNull()
}

private const val MAX_C_STRING_BYTES = 64L * 1024
