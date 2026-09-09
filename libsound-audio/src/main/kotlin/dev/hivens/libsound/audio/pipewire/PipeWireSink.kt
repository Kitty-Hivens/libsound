package dev.hivens.libsound.audio.pipewire

import dev.hivens.libsound.AudioException
import dev.hivens.libsound.AudioFormat
import dev.hivens.libsound.AudioSink
import dev.hivens.libsound.Capabilities
import dev.hivens.libsound.Capability
import dev.hivens.libsound.PcmEncoding
import dev.hivens.libsound.PcmRingBuffer
import dev.hivens.libsound.SinkConfig
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
 * An [AudioSink] over a `pw_stream`, with nothing between it and the graph.
 *
 * What this backend is for is in section 13 of the plan, and it is not speed.
 * It is that the compatibility layer decides what the library can say about a
 * stream, and it says less than the graph can hear: eighteen of the thirty-six
 * channel positions a decoder sends against the graph's twenty-six, no 64-bit
 * float at all, and a `node.latency` the shim recomputes. Those refusals were
 * reported as the platform's.
 *
 * ## The graph pulls, the contract pushes
 *
 * `PW_STREAM_FLAG_MAP_BUFFERS` hands the process callback a mapped buffer to
 * fill, which is CoreAudio's shape rather than libpulse's. So a
 * [PcmRingBuffer] sits between the consumer's blocking write and the callback,
 * exactly as `CoreAudioSink` does, and the pacing rule holds for the same
 * reason: the write returns when the callback has taken the frames, not when
 * they were queued.
 *
 * ## The playhead is counted, not read off the clock
 *
 * `pw_time.ticks` is the graph's own clock and keeps advancing through an
 * underrun on frames this stream never provided, which is the trap
 * `pa_stream_get_time` and `AudioTimeStamp.mSampleTime` both set. So what is
 * reported is what the callback actually took out of the ring, and a starved
 * stream stalls a consumer's clock instead of running it away.
 *
 * ## The process callback runs on the graph's thread
 *
 * It takes the ring's lock and copies, and does nothing else. No allocation
 * beyond reinterpreting a mapped pointer, no logging, no native call back into
 * the library. A garbage collection can still land on it and be heard, which is
 * true of every JVM audio path; the honest mitigation is the ring depth, not a
 * claim that it cannot happen.
 */
internal class PipeWireSink(
    private val loop: PipeWireLoop,
    private val config: SinkConfig,
    override val capabilities: Capabilities,
) : AudioSink {

    private val log = LoggerFactory.getLogger("libsound.PipeWire")

    private val lib = loop.lib

    private val closed = AtomicBoolean(false)

    /**
     * Guards the stream pointer for its whole use, not only across the swap.
     *
     * The same rule the WASAPI sink needed and for the same reason: a caller
     * that reads the pointer into a local and calls through it afterwards is
     * safe when the local keeps a Java object alive, and this local is an
     * address. Everything that dereferences it takes the loop lock, which is
     * also what libpipewire requires of anything belonging to the loop.
     */
    @Volatile
    private var stream: MemorySegment = MemorySegment.NULL

    @Volatile
    private var openFormat: AudioFormat? = null

    @Volatile
    private var running = false

    @Volatile
    private var volumeValue = 1f

    /** Frames of real audio the callback has handed to the graph. */
    private val framesRendered = AtomicLong(0)

    /** Periods the callback could not fill from the ring. */
    private val underruns = AtomicLong(0)

    /** Read by the process callback; replaced wholesale on each open. */
    @Volatile
    private var ring: PcmRingBuffer? = null

    /** Pre-allocated so the callback never allocates. Sized at open. */
    @Volatile
    private var scratch: ByteArray = ByteArray(0)

    @Volatile
    private var frameBytes = 0

    /**
     * Why the callback last went quiet, reported from [close] rather than from
     * the callback.
     *
     * Logging on the graph's thread allocates and may take a lock the logging
     * backend holds, which turns one bad period into several. A volatile write
     * costs nothing and loses nothing.
     */
    @Volatile
    private var renderFailure: String? = null

    /**
     * Holds the events struct and its upcall stubs, and outlives every callback
     * by construction: it is closed only after the stream has been destroyed
     * under the loop lock, which is the point past which none can be in flight.
     */
    private val stubArena: Arena = Arena.ofShared()

    override val format: AudioFormat? get() = openFormat

    override val isOpen: Boolean get() = stream.address() != 0L && !closed.get()

    /**
     * All five, and this is the difference the backend exists for: the graph
     * has a 64-bit float and the PulseAudio protocol has none.
     */
    override val acceptedEncodings: Set<PcmEncoding> get() = PcmEncoding.entries.toSet()

    /**
     * The encoding, and whether the graph has a name for every position the
     * layout claims.
     *
     * Twenty-six of the thirty-six, against the compatibility layer's eighteen.
     * The ten with none are refused rather than placed as a neighbour, and the
     * message says to send the same audio with an unspecified layout.
     */
    override fun accepts(format: AudioFormat): Boolean =
        !format.layout.isSpecified ||
            format.channels <= UNIVERSAL_CHANNELS ||
            SpaPod.positionsOf(format.layout) != null

    override fun open(format: AudioFormat) {
        if (closed.get()) throw AudioException("sink is closed")
        refuseUnacceptable(format)
        disconnectStream()

        frameBytes = format.bytesPerFrame
        val depthFrames = format.framesFor(config.targetNanos)
            .coerceAtLeast(MIN_RING_FRAMES.toLong())
        // The old ring goes with the old stream: nothing drains it any more, so
        // a producer parked on it would stay parked through a reopen that
        // looked to everyone else like a fresh start.
        ring?.close()
        ring = PcmRingBuffer((depthFrames * frameBytes).toInt(), frameBytes)
        scratch = ByteArray(MAX_FRAMES_PER_PERIOD * frameBytes)
        framesRendered.set(0)
        underruns.set(0)

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
                // pw_stream_new_simple takes the properties whether or not it
                // succeeds, so nothing frees them here.
                if (created.address() == 0L) throw AudioException("pw_stream_new_simple failed")

                val flags = SpaAbi.STREAM_FLAG_AUTOCONNECT or
                    SpaAbi.STREAM_FLAG_MAP_BUFFERS or
                    // Started inactive and activated below, so the graph cannot
                    // pull from an empty ring before the first write. The same
                    // reason the libpulse sink connects corked.
                    SpaAbi.STREAM_FLAG_INACTIVE
                val rc = lib.handle("pw_stream_connect").invokeExact(
                    created, SpaAbi.DIRECTION_OUTPUT, SpaAbi.ID_ANY, flags, params, 1,
                ) as Int
                if (rc < 0) {
                    lib.handle("pw_stream_destroy").invokeExact(created) as Unit
                    throw AudioException("pw_stream_connect = $rc")
                }
                created
            }
        }

        stream = fresh
        openFormat = format
        awaitReady()
        // The contract's first rule: open starts the device. A consumer that
        // wants silence stops immediately after.
        start()
        log.info(
            "stream open: {} ring={} frames, layout {}",
            format, depthFrames, if (accepts(format)) format.layout else "by count",
        )
    }

    override fun write(data: ByteArray, offset: Int, length: Int) {
        val format = openFormat ?: throw AudioException("write before open")
        require(offset >= 0 && length >= 0 && offset + length <= data.size) {
            "range $offset..${offset + length} outside array of ${data.size}"
        }
        require(length % format.bytesPerFrame == 0) {
            "length ($length) must be a whole number of frames (${format.bytesPerFrame})"
        }
        val current = ring ?: throw AudioException("write on a closed sink")
        // The pacing point, and it holds no lock of ours: the ring parks on its
        // own condition, so reading the playhead while this is parked costs
        // nothing, which is what the contract asks for.
        if (!current.writeFully(data, offset, length)) {
            throw AudioException("sink closed while writing")
        }
    }

    override fun start() {
        setActive(true)
    }

    override fun stop() {
        setActive(false)
    }

    override fun flush() {
        // Ours first, then the graph's. What is in the ring belongs to the old
        // position as much as what the graph is holding.
        ring?.clear()
        val current = stream
        if (current.address() == 0L) return
        loop.locked {
            runCatching { lib.handle("pw_stream_flush").invokeExact(current, false) as Int }
                .onFailure { log.debug("stream flush threw: {}", it.message) }
        }
    }

    override fun framePosition(): Long = framesRendered.get()

    /**
     * What is queued here plus the graph's own share, which is the whole path.
     *
     * `pw_time.delay` is what the graph adds behind this node, in the rate its
     * own fraction gives rather than in nanoseconds, and `queued` is what this
     * client handed over and has not had played. The ring is added because it
     * is ours and neither field knows about it.
     */
    override fun latencyNanos(): Long {
        val format = openFormat ?: return 0L
        val buffered = ring?.available() ?: 0
        val ours = format.nanosFor((buffered / format.bytesPerFrame).toLong())
        val current = stream
        if (current.address() == 0L) return ours
        return ours + loop.locked {
            Arena.ofConfined().use { call ->
                val time = call.allocate(SpaAbi.TIME_SIZE, 8)
                val rc = lib.handle("pw_stream_get_time_n")
                    .invokeExact(current, time, SpaAbi.TIME_SIZE) as Int
                if (rc < 0) return@use 0L
                graphNanos(time, format)
            }
        }
    }

    /**
     * The graph's share of the path, in nanoseconds.
     *
     * `delay` and `queued` are both counted in the rate `pw_time` reports as a
     * fraction, which is the graph's rather than the stream's: reading them
     * against the stream's sample rate is right only while the two happen to
     * agree, and a graph running at 44.1 while a stream asks for 48 is the
     * ordinary case this library exists to stop mattering.
     */
    private fun graphNanos(time: MemorySegment, format: AudioFormat): Long {
        val num = time.get(ValueLayout.JAVA_INT, SpaAbi.TIME_RATE_NUM)
        val denom = time.get(ValueLayout.JAVA_INT, SpaAbi.TIME_RATE_DENOM)
        val delay = time.get(ValueLayout.JAVA_LONG, SpaAbi.TIME_DELAY)
        val queued = time.get(ValueLayout.JAVA_LONG, SpaAbi.TIME_QUEUED)
        val ticks = delay + queued
        if (ticks <= 0) return 0L
        // A fraction of zero either way is a stream the graph has not yet
        // decided a rate for, and a division by it is worse than a zero.
        if (num <= 0 || denom <= 0) return format.nanosFor(ticks)
        // Split so the multiplication cannot overflow, the same correction the
        // frame arithmetic in AudioFormat carries.
        val whole = ticks / denom
        val remainder = ticks % denom
        return (whole * num * AudioFormat.NANOS_PER_SECOND) +
            (remainder * num * AudioFormat.NANOS_PER_SECOND / denom)
    }

    override fun underrunCount(): Long = underruns.get()

    /**
     * Remembered and not applied.
     *
     * A stream's volume in the graph is a control on the node, reached through
     * the registry rather than through `pw_stream`, and section 13.8 leaves the
     * registry out of the first cut deliberately. So this backend does not
     * claim [Capability.STREAM_VOLUME], and a consumer that asks first draws no
     * slider here. Scaling the samples instead would be audible and invisible
     * to the desktop, which is the thing that capability exists to tell apart.
     */
    override fun setVolume(volume: Float) {
        volumeValue = volume.coerceIn(0f, 1f)
    }

    override fun volume(): Float = volumeValue

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Free a producer parked in write() first: the thread that could rescue
        // it is never the producer itself.
        ring?.close()
        disconnectStream()
        openFormat = null
        renderFailure?.let { log.warn("the process callback failed at least once: {}", it) }
        // Only here. The stream is destroyed, so no callback can be in flight
        // and nothing native still holds a pointer into this arena.
        runCatching { stubArena.close() }
    }

    // -- the callbacks, on the graph's own thread -----------------------------

    // Public rather than internal although nothing outside calls them: Kotlin
    // mangles an internal function's name and findVirtual looks up what is
    // written, so the mangled lookup fails and the sink reports no graph on a
    // machine that has one.

    /**
     * Fill one period. On the graph's thread, and it does the least it can.
     */
    fun onProcess(unusedData: MemorySegment) {
        try {
            val current = stream
            if (current.address() == 0L) return
            val buffer = lib.handle("pw_stream_dequeue_buffer").invokeExact(current) as MemorySegment
            if (buffer.address() == 0L) return
            try {
                fill(buffer)
            } finally {
                // Every dequeue owes a queue. Skipping one on an error path
                // costs the graph a buffer for the life of the stream.
                lib.handle("pw_stream_queue_buffer").invokeExact(current, buffer) as Int
            }
        } catch (e: Throwable) {
            // A throw crossing an upcall boundary is undefined, and nothing
            // here is worth risking that for.
            renderFailure = e.message
        }
    }

    private fun fill(pwBuffer: MemorySegment) {
        val head = pwBuffer.reinterpret(SpaAbi.PW_BUFFER_HEAD)
        val spaBuffer = head.get(ValueLayout.ADDRESS, SpaAbi.PW_BUFFER_BUFFER)
        if (spaBuffer.address() == 0L) return
        val buffers = spaBuffer.reinterpret(SpaAbi.SPA_BUFFER_HEAD)
        if (buffers.get(ValueLayout.JAVA_INT, SpaAbi.SPA_BUFFER_N_DATAS) < 1) return
        val datas = buffers.get(ValueLayout.ADDRESS, SpaAbi.SPA_BUFFER_DATAS)
        if (datas.address() == 0L) return

        // The format asked for is interleaved, so one data is what comes back.
        val data = datas.reinterpret(SpaAbi.SPA_DATA_SIZE)
        val target = data.get(ValueLayout.ADDRESS, SpaAbi.SPA_DATA_DATA)
        val capacity = data.get(ValueLayout.JAVA_INT, SpaAbi.SPA_DATA_MAXSIZE)
        val chunk = data.get(ValueLayout.ADDRESS, SpaAbi.SPA_DATA_CHUNK)
        if (target.address() == 0L || chunk.address() == 0L || capacity <= 0) return

        val bytesPerFrame = frameBytes
        val source = ring
        if (source == null || bytesPerFrame <= 0) return
        // What the graph asked for where it said, and the whole buffer where it
        // did not. Both are clamped to the scratch, which is sized at open.
        val requested = head.get(ValueLayout.JAVA_LONG, SpaAbi.PW_BUFFER_REQUESTED)
        val wantedBytes = if (requested > 0) requested * bytesPerFrame else capacity.toLong()
        var wanted = minOf(wantedBytes, capacity.toLong(), scratch.size.toLong()).toInt()
        wanted -= wanted % bytesPerFrame
        if (wanted <= 0) return

        val real = source.read(scratch, 0, wanted)
        MemorySegment.copy(scratch, 0, target.reinterpret(wanted.toLong()), ValueLayout.JAVA_BYTE, 0L, wanted)

        val chunkHead = chunk.reinterpret(SpaAbi.SPA_CHUNK_SIZE)
        chunkHead.set(ValueLayout.JAVA_INT, SpaAbi.SPA_CHUNK_OFFSET, 0)
        chunkHead.set(ValueLayout.JAVA_INT, SpaAbi.SPA_CHUNK_LENGTH, wanted)
        chunkHead.set(ValueLayout.JAVA_INT, SpaAbi.SPA_CHUNK_STRIDE, bytesPerFrame)
        head.set(ValueLayout.JAVA_LONG, SpaAbi.PW_BUFFER_SIZE, (wanted / bytesPerFrame).toLong())

        if (real > 0) framesRendered.addAndGet((real / bytesPerFrame).toLong())
        // A short read is the graph asking for audio nobody had ready. One
        // atomic increment, which is what this may cost on this thread.
        if (real < wanted) underruns.incrementAndGet()
    }

    /** Wakes whoever is waiting for the stream to reach a state. */
    fun onStateChanged(
        unusedData: MemorySegment,
        unusedOld: Int,
        unusedState: Int,
        unusedError: MemorySegment,
    ) {
        runCatching { loop.signal() }
    }

    /** Bound and deliberately empty: the format this stream asked for is the one it takes. */
    fun onParamChanged(unusedData: MemorySegment, unusedId: Int, unusedParam: MemorySegment) = Unit

    // -- internals ------------------------------------------------------------

    /**
     * The events struct, which the library calls through and therefore has to
     * be exactly the right size with every slot accounted for.
     *
     * Three are ours and eight are null. A short struct would leave the loop
     * calling through whatever follows the allocation, which is why the size
     * comes from the oracle rather than from counting the fields that matter.
     */
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
                    PipeWireSink::class.java, "onProcess",
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
                    PipeWireSink::class.java, "onStateChanged",
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
                    PipeWireSink::class.java, "onParamChanged",
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

    /**
     * The node's identity and what it asks the graph for, as a `spa_dict`.
     *
     * A dict rather than `pw_properties_new`, which is variadic: a Panama
     * downcall to a variadic function needs a descriptor per call shape, and
     * this needs none.
     */
    private fun properties(setup: Arena, format: AudioFormat): MemorySegment {
        val entries = buildList {
            add(SpaAbi.KEY_MEDIA_TYPE to "Audio")
            add(SpaAbi.KEY_MEDIA_CATEGORY to "Playback")
            add(SpaAbi.KEY_MEDIA_ROLE to config.mediaRole.wireName.replaceFirstChar { it.uppercase() })
            add(SpaAbi.KEY_APP_NAME to config.applicationName)
            add(SpaAbi.KEY_NODE_NAME to config.applicationName)
            add(SpaAbi.KEY_NODE_DESCRIPTION to config.applicationName)
            config.applicationId?.let { add(SpaAbi.KEY_APP_ID to it) }
            config.iconName?.let { add(SpaAbi.KEY_APP_ICON_NAME to it) }
            // The lever the compatibility layer overwrites. A quantum in the
            // stream's own rate, which is what a node asks the graph for.
            add(SpaAbi.KEY_NODE_LATENCY to "${format.framesFor(config.targetNanos)}/${format.sampleRate}")
            add(SpaAbi.KEY_NODE_RATE to "1/${format.sampleRate}")
            // Where a consumer that already chose wants to be placed. One
            // property rather than a device name in a connect call.
            config.device?.let { add(SpaAbi.KEY_TARGET_OBJECT to it.value) }
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

    private fun setActive(active: Boolean) {
        val current = stream
        if (current.address() == 0L) return
        loop.locked {
            runCatching { lib.handle("pw_stream_set_active").invokeExact(current, active) as Int }
                .onFailure { log.debug("set_active({}) threw: {}", active, it.message) }
            running = active
            // A stream that has stopped will never take from the ring again, so
            // a producer parked in write has to be woken to re-check rather
            // than left to discover it at a timeout it does not have. It does
            // not free it: the ring is still full, so the loop parks again, and
            // close is the escape.
            loop.signal()
        }
    }

    /**
     * Wait for the stream to leave CONNECTING, so a consumer that reads the
     * playhead straight after open is not asking a stream that has not been
     * placed yet.
     *
     * Bounded, because a graph that never answers is a graph this should report
     * rather than hang on.
     */
    private fun awaitReady() {
        val current = stream
        if (current.address() == 0L) return
        val deadline = System.nanoTime() + READY_TIMEOUT_NANOS
        loop.locked {
            while (System.nanoTime() < deadline) {
                val state = Arena.ofConfined().use { call ->
                    lib.handle("pw_stream_get_state").invokeExact(current, call.allocate(ValueLayout.ADDRESS)) as Int
                }
                if (state == SpaAbi.STREAM_STATE_ERROR) throw AudioException("the stream went to error")
                if (state != SpaAbi.STREAM_STATE_CONNECTING && state != SpaAbi.STREAM_STATE_UNCONNECTED) return
                loop.await()
            }
        }
        log.warn("the stream did not leave connecting within {} ms", READY_TIMEOUT_NANOS / 1_000_000)
    }

    private fun disconnectStream() {
        // Claim the pointer before touching it, so two threads cannot walk away
        // with the same stream and destroy it twice.
        val current = synchronized(this) {
            val held = stream
            stream = MemorySegment.NULL
            held
        }
        running = false
        if (current.address() == 0L) return
        runCatching {
            loop.locked {
                lib.handle("pw_stream_disconnect").invokeExact(current) as Int
                lib.handle("pw_stream_destroy").invokeExact(current) as Unit
            }
        }.onFailure { log.warn("stream teardown threw: {}", it.message) }
    }

    /** The refusal [accepts] promised, naming the position that could not be placed. */
    private fun refuseUnacceptable(format: AudioFormat) {
        if (accepts(format)) return
        val position = format.layout.positions.firstOrNull { SpaAbi.channelOf(it) == null }
        throw AudioException(
            "the graph has no channel position for $position in ${format.layout}; " +
                "send the same audio with an unspecified layout to take the graph's own ordering",
        )
    }

    private companion object {
        /** Below this every platform agrees, so there is nothing to place and nothing to refuse. */
        const val UNIVERSAL_CHANNELS = 2

        /**
         * A floor under the ring, so a short latency profile does not size it
         * below one period and turn every callback into an underrun.
         */
        const val MIN_RING_FRAMES = 4_096

        /**
         * The scratch the callback copies through. Generous: a period this long
         * is past anything a graph asks for, and the headroom costs one array
         * per open.
         */
        const val MAX_FRAMES_PER_PERIOD = 8_192

        const val READY_TIMEOUT_NANOS = 2_000_000_000L
    }
}
