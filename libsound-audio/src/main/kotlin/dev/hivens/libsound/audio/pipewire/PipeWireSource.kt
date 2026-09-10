package dev.hivens.libsound.audio.pipewire

import dev.hivens.libsound.AudioException
import dev.hivens.libsound.AudioFormat
import dev.hivens.libsound.AudioSource
import dev.hivens.libsound.Capabilities
import dev.hivens.libsound.Capability
import dev.hivens.libsound.PcmEncoding
import dev.hivens.libsound.PcmRingBuffer
import dev.hivens.libsound.audio.realtime.RealtimeThreads
import dev.hivens.libsound.SourceConfig
import dev.hivens.libsound.StreamDirection
import dev.hivens.libsound.StreamId
import dev.hivens.libsound.audio.pulse.PulseStreamHandle
import org.slf4j.LoggerFactory
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * An [AudioSource] over a `pw_stream` with the direction reversed.
 *
 * The mirror of [PipeWireSink], and deliberately the same file read backwards:
 * a graph that pulls for playback pushes for capture, so the ring between the
 * callback and the consumer stays and the two sides of it swap.
 *
 * ## Which side may wait
 *
 * Playing, the callback reads the ring and the consumer's write parks. Here the
 * callback writes and the consumer's read parks, which is
 * [PcmRingBuffer.readFully]. The rule underneath is the same one and it is
 * about the device rather than about the direction: whichever side the graph is
 * on cannot wait, and whichever side the consumer is on can and must, because
 * waiting is how a recorder learns time has passed.
 *
 * A full ring is what an overrun is here. The callback cannot park to make
 * room, so it writes what fits, and what did not fit is counted rather than
 * lost silently, which is the whole of why [overrunFrames] exists.
 */
internal class PipeWireSource(
    private val loop: PipeWireLoop,
    private val config: SourceConfig,
    private val baseCapabilities: Capabilities,
    private val registry: PipeWireRegistry? = null,
) : AudioSource {

    /**
     * The base set, plus the one entry decided per thread at runtime.
     *
     * [Capability.REALTIME_THREAD] cannot be a constant: whether the worker
     * runs at a priority that will not be preempted depends on a caller asking
     * for it and a system service agreeing, and a consumer offering the lowest
     * latency profile needs to know which of those happened.
     */
    override val capabilities: Capabilities
        get() = if (realtimeGranted) {
            Capabilities(baseCapabilities.supported + Capability.REALTIME_THREAD)
        } else {
            baseCapabilities
        }

    private val log = LoggerFactory.getLogger("libsound.PipeWire")

    /** Per thread, because the promotion is per thread and so is the refusal. */
    private val promotionAttempted = ThreadLocal.withInitial { false }

    @Volatile
    private var realtimeGranted = false

    private val realtimeRefusalLogged = AtomicBoolean(false)


    private val lib = loop.lib

    private val closed = AtomicBoolean(false)

    @Volatile
    private var stream: MemorySegment = MemorySegment.NULL

    @Volatile
    private var openFormat: AudioFormat? = null

    @Volatile
    private var volumeValue = 1f

    /** Frames the callback has taken off the graph since [open]. */
    private val framesCaptured = AtomicLong(0)

    /** Frames the graph produced and the ring had no room for. */
    private val overruns = AtomicLong(0)

    @Volatile
    private var ring: PcmRingBuffer? = null

    /** Pre-allocated so the callback never allocates. Sized at open. */
    @Volatile
    private var scratch: ByteArray = ByteArray(0)

    @Volatile
    private var frameBytes = 0

    @Volatile
    private var captureFailure: String? = null

    /** Holds the events struct and its stubs; closed only after the stream is destroyed. */
    private val stubArena: Arena = Arena.ofShared()

    override val format: AudioFormat? get() = openFormat

    override val isOpen: Boolean get() = stream.address() != 0L && !closed.get()

    override val acceptedEncodings: Set<PcmEncoding> get() = PcmEncoding.entries.toSet()

    override fun accepts(format: AudioFormat): Boolean =
        format.channels <= SpaAbi.MAX_CHANNELS &&
            (
                !format.layout.isSpecified ||
                    format.channels <= UNIVERSAL_CHANNELS ||
                    SpaPod.positionsOf(format.layout) != null
                )

    override fun open(format: AudioFormat) {
        if (closed.get()) throw AudioException("source is closed")
        refuseUnacceptable(format)
        disconnectStream()
        // Cleared before anything is attempted, for the reason the sink gives:
        // an open that throws half way must leave nothing open.
        openFormat = null

        frameBytes = format.bytesPerFrame
        val depthFrames = format.framesFor(config.targetNanos)
            .coerceAtLeast(MIN_RING_FRAMES.toLong())
        ring?.close()
        ring = PcmRingBuffer((depthFrames * frameBytes).toInt(), frameBytes)
        scratch = ByteArray(MAX_FRAMES_PER_PERIOD * frameBytes)
        framesCaptured.set(0)
        overruns.set(0)

        val fresh = Arena.ofConfined().use { setup ->
            val props = properties(setup, format)
            val params = setup.allocate(ValueLayout.ADDRESS, 1)
            val pod = SpaPod.audioFormat(format, SpaAbi.PARAM_ENUM_FORMAT)
            val podSegment = setup.allocate(pod.size.toLong(), SpaAbi.POD_ALIGN.toLong())
            MemorySegment.copy(pod, 0, podSegment, ValueLayout.JAVA_BYTE, 0L, pod.size)
            params.setAtIndex(ValueLayout.ADDRESS, 0, podSegment)

            loop.locked {
                val created = lib.handle("pw_stream_new_simple").invokeExact(
                    loop.loop, setup.allocateFrom(config.applicationName), props,
                    events, MemorySegment.NULL,
                ) as MemorySegment
                if (created.address() == 0L) throw AudioException("pw_stream_new_simple failed")

                val flags = SpaAbi.STREAM_FLAG_AUTOCONNECT or
                    SpaAbi.STREAM_FLAG_MAP_BUFFERS or
                    SpaAbi.STREAM_FLAG_INACTIVE or
                    // A stream aimed at one application stays aimed at it. Let
                    // it reconnect and the graph would give it whatever was
                    // available when that application left, which here means
                    // handing a caller a microphone it did not ask for.
                    (if (config.captureStream != null) SpaAbi.STREAM_FLAG_DONT_RECONNECT else 0)
                val rc = lib.handle("pw_stream_connect").invokeExact(
                    created, SpaAbi.DIRECTION_INPUT, SpaAbi.ID_ANY, flags, params, 1,
                ) as Int
                if (rc < 0) {
                    lib.handle("pw_stream_destroy").invokeExact(created) as Unit
                    throw AudioException("pw_stream_connect = $rc")
                }
                created
            }
        }

        stream = fresh
        runCatching {
            awaitReady()
            openFormat = format
            // The contract's first rule: open starts the device. A consumer
            // that wants the microphone open and idle stops immediately after.
            applyVolume()
            start()
        }.onFailure { failure ->
            openFormat = null
            disconnectStream()
            ring?.close()
            throw failure as? AudioException ?: AudioException("open failed: ${failure.message}", failure)
        }
        log.info("capture open: {} ring={} frames", format, depthFrames)
    }

    override fun read(dst: ByteArray, offset: Int, length: Int) {
        val format = openFormat ?: throw AudioException("read before open")
        require(offset >= 0 && length >= 0 && offset + length <= dst.size) {
            "range $offset..${offset + length} outside array of ${dst.size}"
        }
        require(length % format.bytesPerFrame == 0) {
            "length ($length) must be a whole number of frames (${format.bytesPerFrame})"
        }
        if (config.realtime) promoteThisThread()
        val current = ring ?: throw AudioException("read on a closed source")
        // The pacing point, holding no lock of ours: the ring parks on its own
        // condition, so the position stays answerable while this is parked.
        if (!current.readFully(dst, offset, length)) {
            throw AudioException("source closed while reading")
        }
    }

    override fun start() {
        setActive(true)
    }

    override fun stop() {
        setActive(false)
    }

    override fun flush() {
        ring?.clear()
        loop.locked {
            val current = stream
            if (current.address() == 0L) return@locked
            runCatching { lib.handle("pw_stream_flush").invokeExact(current, false) as Int }
                .onFailure { log.debug("stream flush threw: {}", it.message) }
        }
    }

    override fun framePosition(): Long = framesCaptured.get()

    /**
     * How long ago the audio about to be read was spoken: what is waiting in
     * the ring, plus the graph's own share behind it.
     */
    override fun latencyNanos(): Long {
        val format = openFormat ?: return 0L
        val waiting = ring?.available() ?: 0
        val ours = format.nanosFor((waiting / format.bytesPerFrame).toLong())
        return ours + loop.locked {
            val current = stream
            if (current.address() == 0L) return@locked 0L
            Arena.ofConfined().use { call ->
                val time = call.allocate(SpaAbi.TIME_SIZE, 8)
                val rc = lib.handle("pw_stream_get_time_n")
                    .invokeExact(current, time, SpaAbi.TIME_SIZE) as Int
                if (rc < 0) return@use 0L
                graphNanos(time, format)
            }
        }
    }

    override fun overrunFrames(): Long = overruns.get()

    /**
     * The stream's own volume, at the system level, which is what
     * [Capability.STREAM_VOLUME] means.
     *
     * A control on this node rather than arithmetic on the samples, so the
     * desktop's mixer shows it and follows it. `pw_stream_set_control` is the
     * one variadic call in this binding, and it takes a control and then the
     * zero that ends the list.
     */
    override fun setVolume(volume: Float) {
        volumeValue = volume.coerceIn(0f, 1f)
        applyVolume()
    }

    private fun applyVolume() {
        loop.locked {
            val current = stream
            if (current.address() == 0L) return@locked
            runCatching {
                Arena.ofConfined().use { call ->
                    val values = call.allocate(ValueLayout.JAVA_FLOAT)
                    values.set(ValueLayout.JAVA_FLOAT, 0L, volumeValue)
                    lib.setControl.invokeExact(current, SpaAbi.PROP_VOLUME, 1, values, 0) as Int
                }
            }.onFailure { log.debug("set_control(volume) threw: {}", it.message) }
        }
    }

    override fun volume(): Float = volumeValue

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Free a reader parked in read() first: the thread that could rescue it
        // is never the reader itself.
        ring?.close()
        disconnectStream()
        openFormat = null
        captureFailure?.let { log.warn("the capture callback failed at least once: {}", it) }
        runCatching { stubArena.close() }
    }

    /**
     * Ask for real-time priority for the thread that reads, once per thread.
     *
     * Off unless the config asked, because the limit is process wide and not
     * something a library takes on behalf of a caller that did not. Done at the
     * call rather than at open because the contract's pacing puts the deadline
     * on whichever thread reads, and that is not necessarily the one that
     * opened.
     *
     * Failure is not fatal and not silent: the reason goes out once, and
     * [Capability.REALTIME_THREAD] stays absent, so a settings screen can say
     * why the lowest profile is not on offer instead of letting somebody pick
     * one that crackles.
     */
    private fun promoteThisThread() {
        if (promotionAttempted.get()) return
        promotionAttempted.set(true)
        val refusal = RealtimeThreads.promoteCurrentThread()
        if (refusal == null) {
            realtimeGranted = true
            log.info("the reader thread runs at real-time priority")
        } else if (realtimeRefusalLogged.compareAndSet(false, true)) {
            log.info(
                "no real-time priority for the reader thread ({}); the lowest latency profiles will " +
                    "underrun under load",
                refusal,
            )
        }
    }

    // -- the callbacks, on the graph's own thread -----------------------------

    // Public rather than internal for the reason every other upcall here is:
    // Kotlin mangles an internal name and findVirtual looks up what is written.

    fun onProcess(unusedData: MemorySegment) {
        try {
            val current = stream
            if (current.address() == 0L) return
            val buffer = lib.handle("pw_stream_dequeue_buffer").invokeExact(current) as MemorySegment
            if (buffer.address() == 0L) return
            try {
                drain(buffer)
            } finally {
                lib.handle("pw_stream_queue_buffer").invokeExact(current, buffer) as Int
            }
        } catch (e: Throwable) {
            captureFailure = e.message
        }
    }

    private fun drain(pwBuffer: MemorySegment) {
        val head = pwBuffer.reinterpret(SpaAbi.PW_BUFFER_HEAD)
        val spaBuffer = head.get(ValueLayout.ADDRESS, SpaAbi.PW_BUFFER_BUFFER)
        if (spaBuffer.address() == 0L) return
        val buffers = spaBuffer.reinterpret(SpaAbi.SPA_BUFFER_HEAD)
        if (buffers.get(ValueLayout.JAVA_INT, SpaAbi.SPA_BUFFER_N_DATAS) < 1) return
        val datas = buffers.get(ValueLayout.ADDRESS, SpaAbi.SPA_BUFFER_DATAS)
        if (datas.address() == 0L) return

        val data = datas.reinterpret(SpaAbi.SPA_DATA_SIZE)
        val source = data.get(ValueLayout.ADDRESS, SpaAbi.SPA_DATA_DATA)
        val chunk = data.get(ValueLayout.ADDRESS, SpaAbi.SPA_DATA_CHUNK)
        val capacity = data.get(ValueLayout.JAVA_INT, SpaAbi.SPA_DATA_MAXSIZE)
        if (source.address() == 0L || chunk.address() == 0L || capacity <= 0) return

        // The chunk says how much of the mapped buffer the graph filled and
        // where it starts. Reading maxsize instead would read whatever the
        // buffer held before, which is the previous period's audio.
        val chunkHead = chunk.reinterpret(SpaAbi.SPA_CHUNK_SIZE)
        val chunkOffset = chunkHead.get(ValueLayout.JAVA_INT, SpaAbi.SPA_CHUNK_OFFSET)
        val filled = chunkHead.get(ValueLayout.JAVA_INT, SpaAbi.SPA_CHUNK_LENGTH)
        // Both are the graph's numbers and the mapping is only maxsize long, so
        // both are held to it. The header says as much of the two fields, and
        // the playback half already clamps to the same number: an offset the
        // buffer cannot hold is a read off the end of the mapping rather than a
        // period of noise.
        if (chunkOffset < 0 || filled <= 0 || chunkOffset >= capacity) return
        val available = minOf(filled, capacity - chunkOffset)
        if (available <= 0) return

        val bytesPerFrame = frameBytes
        val ring = this.ring
        if (ring == null || bytesPerFrame <= 0) return
        var wanted = minOf(available, scratch.size)
        wanted -= wanted % bytesPerFrame
        if (wanted <= 0) return

        MemorySegment.copy(
            source.reinterpret((chunkOffset + wanted).toLong()), ValueLayout.JAVA_BYTE, chunkOffset.toLong(),
            scratch, 0, wanted,
        )
        // The non-blocking write, because this side is the graph's and cannot
        // park. What does not fit is a consumer that fell behind, which is an
        // ordinary condition on a shared machine and a counted one rather than
        // a silent loss.
        val accepted = ring.write(scratch, 0, wanted)
        framesCaptured.addAndGet((accepted / bytesPerFrame).toLong())
        // Two ways to lose frames and both are counted. The ring being full is
        // the ordinary one. The period being longer than the scratch is not,
        // and it went uncounted until now: a graph running a quantum past
        // MAX_FRAMES_PER_PERIOD produced a recording with holes in it and an
        // overrun count of zero, which is the one thing the contract says this
        // must never do.
        val lost = (available - accepted).coerceAtLeast(0)
        if (lost > 0) overruns.addAndGet((lost / bytesPerFrame).toLong())
    }

    fun onStateChanged(
        unusedData: MemorySegment,
        unusedOld: Int,
        unusedState: Int,
        unusedError: MemorySegment,
    ) {
        runCatching { loop.signal() }
    }

    fun onParamChanged(unusedData: MemorySegment, unusedId: Int, unusedParam: MemorySegment) = Unit

    // -- internals ------------------------------------------------------------

    private val events: MemorySegment by lazy {
        val linker = Linker.nativeLinker()
        val lookup = MethodHandles.lookup()
        val addr = ValueLayout.ADDRESS
        val i32 = ValueLayout.JAVA_INT

        val struct = stubArena.allocate(SpaAbi.STREAM_EVENTS_SIZE, 8)
        struct.fill(0)
        struct.set(ValueLayout.JAVA_INT, SpaAbi.STREAM_EVENTS_VERSION, SpaAbi.VERSION_STREAM_EVENTS)
        struct.set(
            addr, SpaAbi.STREAM_EVENTS_PROCESS,
            linker.upcallStub(
                lookup.findVirtual(
                    PipeWireSource::class.java, "onProcess",
                    MethodType.methodType(Void.TYPE, MemorySegment::class.java),
                ).bindTo(this),
                FunctionDescriptor.ofVoid(addr),
                stubArena,
            ),
        )
        struct.set(
            addr, SpaAbi.STREAM_EVENTS_STATE_CHANGED,
            linker.upcallStub(
                lookup.findVirtual(
                    PipeWireSource::class.java, "onStateChanged",
                    MethodType.methodType(
                        Void.TYPE, MemorySegment::class.java,
                        Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                        MemorySegment::class.java,
                    ),
                ).bindTo(this),
                FunctionDescriptor.ofVoid(addr, i32, i32, addr),
                stubArena,
            ),
        )
        struct.set(
            addr, SpaAbi.STREAM_EVENTS_PARAM_CHANGED,
            linker.upcallStub(
                lookup.findVirtual(
                    PipeWireSource::class.java, "onParamChanged",
                    MethodType.methodType(
                        Void.TYPE, MemorySegment::class.java,
                        Int::class.javaPrimitiveType, MemorySegment::class.java,
                    ),
                ).bindTo(this),
                FunctionDescriptor.ofVoid(addr, i32, addr),
                stubArena,
            ),
        )
        struct
    }

    private fun properties(setup: Arena, format: AudioFormat): MemorySegment {
        val entries = buildList {
            add(SpaAbi.KEY_MEDIA_TYPE to "Audio")
            // Capture rather than Playback, which is what puts the node on the
            // input side of the graph and what a desktop's privacy indicator
            // reads to say an application has the microphone open.
            add(SpaAbi.KEY_MEDIA_CATEGORY to "Capture")
            add(SpaAbi.KEY_MEDIA_ROLE to config.mediaRole.wireName.replaceFirstChar { it.uppercase() })
            add(SpaAbi.KEY_APP_NAME to config.applicationName)
            add(SpaAbi.KEY_NODE_NAME to config.applicationName)
            add(SpaAbi.KEY_NODE_DESCRIPTION to config.applicationName)
            config.applicationId?.let { add(SpaAbi.KEY_APP_ID to it) }
            config.iconName?.let { add(SpaAbi.KEY_APP_ICON_NAME to it) }
            // Which process this belongs to, which is how a mixer marks its
            // own rows.
            //
            // Set here because the graph does not set it for a stream: it
            // fills in the process binary, host and user by itself and leaves
            // the id out, measured by removing this line and watching our own
            // stream stop being recognised as ours. A node made through the
            // pulse compatibility layer carries it, because the shim copies the
            // client's whole property set onto the node.
            //
            // It goes to the local sound daemon, which already knows which
            // process connected to it, and no further.
            add(SpaAbi.KEY_APP_PROCESS_ID to ProcessHandle.current().pid().toString())
            add(SpaAbi.KEY_NODE_LATENCY to "${format.framesFor(config.targetNanos)}/${format.sampleRate}")
            add(SpaAbi.KEY_NODE_RATE to "1/${format.sampleRate}")
            val stream = config.captureStream
            if (stream != null) {
                // The device is ignored beside it, which the contract already
                // says: the device is then whichever one the target is playing
                // to, so naming another would be a contradiction rather than a
                // preference.
                // pw_stream_connect turns STREAM_FLAG_DONT_RECONNECT into the
                // matching property itself, so this names the target and
                // nothing else.
                add(SpaAbi.KEY_TARGET_OBJECT to serialOf(stream).toString())
            } else {
                config.device?.let { add(SpaAbi.KEY_TARGET_OBJECT to it.value) }
            }
        }
        val items = setup.allocate(SpaAbi.DICT_ITEM_SIZE * entries.size, 8)
        entries.forEachIndexed { index, (key, value) ->
            val at = SpaAbi.DICT_ITEM_SIZE * index
            items.set(ValueLayout.ADDRESS, at + SpaAbi.DICT_ITEM_KEY, setup.allocateFrom(key))
            items.set(ValueLayout.ADDRESS, at + SpaAbi.DICT_ITEM_VALUE, setup.allocateFrom(value))
        }
        val dict = setup.allocate(SpaAbi.DICT_SIZE, 8)
        dict.set(ValueLayout.JAVA_INT, SpaAbi.DICT_FLAGS, 0)
        dict.set(ValueLayout.JAVA_INT, SpaAbi.DICT_N_ITEMS, entries.size)
        dict.set(ValueLayout.ADDRESS, SpaAbi.DICT_ITEMS, items)
        return lib.handle("pw_properties_new_dict").invokeExact(dict) as MemorySegment
    }

    /**
     * The graph's share of the path behind the ring, in nanoseconds.
     *
     * `delay` is what the header calls the time a sample travelled from the
     * capture device, in the rate the same struct reports, and `buffered` is
     * frames held in the resampler, in the stream's own.
     *
     * `queued` is deliberately not here, where the playback half does count it.
     * It is the sum of the `size` fields of the buffers this client has queued,
     * and a capture client queues buffers back empty: the audio waiting for us
     * is in buffers nobody has dequeued yet, which `delay` already covers.
     * Adding it would be counting a field this side never writes.
     */
    private fun graphNanos(time: MemorySegment, format: AudioFormat): Long {
        val buffered = time.get(ValueLayout.JAVA_LONG, SpaAbi.TIME_BUFFERED)
        val ours = format.nanosFor(maxOf(buffered, 0L))
        val delay = time.get(ValueLayout.JAVA_LONG, SpaAbi.TIME_DELAY)
        if (delay <= 0) return ours
        val num = time.get(ValueLayout.JAVA_INT, SpaAbi.TIME_RATE_NUM)
        val denom = time.get(ValueLayout.JAVA_INT, SpaAbi.TIME_RATE_DENOM)
        // Zero is the honest answer where the graph has not named a rate:
        // converting at the stream's instead is a number from the wrong clock.
        if (num <= 0 || denom <= 0) return ours
        val whole = delay / denom
        val remainder = delay % denom
        return ours + (whole * num * AudioFormat.NANOS_PER_SECOND) +
            (remainder * num * AudioFormat.NANOS_PER_SECOND / denom)
    }

    private fun setActive(active: Boolean) {
        loop.locked {
            val current = stream
            if (current.address() == 0L) return@locked
            runCatching { lib.handle("pw_stream_set_active").invokeExact(current, active) as Int }
                .onFailure { log.debug("set_active({}) threw: {}", active, it.message) }
            // Wakes whoever waits on the loop's own condition. A consumer
            // parked in read is not one of them: it waits on the ring's, which
            // this cannot reach and does not try to.
            loop.signal()
        }
    }

    private fun awaitReady() {
        val deadline = System.nanoTime() + READY_TIMEOUT_NANOS
        val timedOut = loop.locked {
            while (System.nanoTime() < deadline) {
                // Re-read under the lock each time round: the wait below
                // releases it, and a close in that window destroys the stream.
                val current = stream
                if (current.address() == 0L) return@locked false
                val state = Arena.ofConfined().use { call ->
                    lib.handle("pw_stream_get_state").invokeExact(current, call.allocate(ValueLayout.ADDRESS)) as Int
                }
                if (state == SpaAbi.STREAM_STATE_ERROR) throw AudioException("the stream went to error")
                if (state != SpaAbi.STREAM_STATE_CONNECTING && state != SpaAbi.STREAM_STATE_UNCONNECTED) {
                    return@locked false
                }
                // Bounded. The unbounded sibling never returns on a graph that
                // stops changing the state, and the deadline above is only
                // reached by waking up.
                loop.awaitFor(READY_WAIT_SECONDS)
            }
            true
        }
        if (timedOut) {
            throw AudioException(
                "the capture stream did not leave connecting within ${READY_TIMEOUT_NANOS / 1_000_000} ms",
            )
        }
    }

    /** The claim and the destroy under one lock, for the reason [PipeWireSink] gives. */
    private fun disconnectStream() {
        runCatching {
            loop.locked {
                val current = stream
                if (current.address() == 0L) return@locked
                stream = MemorySegment.NULL
                lib.handle("pw_stream_disconnect").invokeExact(current) as Int
                lib.handle("pw_stream_destroy").invokeExact(current) as Unit
            }
        }.onFailure { log.warn("capture stream teardown threw: {}", it.message) }
    }

    /**
     * The serial behind a [StreamId], checked against the graph.
     *
     * The id comes from `VolumeMixer`, which speaks the pulse protocol even on
     * a machine whose backend does not, so what arrives is that mixer's own
     * shape. Its number is the object serial, measured on `pipewire-pulse`
     * 1.6.8 against `pactl list short sink-inputs`, and a serial is monotonic
     * and never reused, so it names the application meant or names nothing.
     *
     * Checked rather than passed through, and this is the whole reason the
     * registry is a parameter here. An unknown serial left to the graph would
     * autoconnect, and a caller that asked to record one application would be
     * handed the default input instead, which is a microphone in a room.
     */
    private fun serialOf(stream: StreamId): Long {
        val handle = PulseStreamHandle.parse(stream)
        if (handle == null || handle.direction != StreamDirection.PLAYBACK) {
            throw AudioException(
                "$stream does not name a playback stream; captureStream takes an id from VolumeMixer.streams()",
            )
        }
        val watching = registry ?: throw AudioException(
            "this backend has no registry connection, so it cannot tell which stream $stream is",
        )
        if (!watching.isPlaying(handle.index.toLong())) {
            throw AudioException("$stream is not playing on this graph any more")
        }
        return handle.index.toLong()
    }

    private fun refuseUnacceptable(format: AudioFormat) {
        if (accepts(format)) return
        if (format.channels > SpaAbi.MAX_CHANNELS) {
            throw AudioException(
                "the graph carries at most ${SpaAbi.MAX_CHANNELS} channels and this asks for ${format.channels}",
            )
        }
        val position = format.layout.positions.firstOrNull { SpaAbi.channelOf(it) == null }
        throw AudioException(
            "the graph has no channel position for $position in ${format.layout}; " +
                "send the same audio with an unspecified layout to take the graph's own ordering",
        )
    }

    private companion object {
        const val UNIVERSAL_CHANNELS = 2
        const val MIN_RING_FRAMES = 4_096
        const val MAX_FRAMES_PER_PERIOD = 8_192
        const val READY_TIMEOUT_NANOS = 2_000_000_000L
        const val READY_WAIT_SECONDS = 3
    }
}
