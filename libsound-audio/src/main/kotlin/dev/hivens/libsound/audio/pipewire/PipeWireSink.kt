package dev.hivens.libsound.audio.pipewire

import dev.hivens.libsound.AudioException
import dev.hivens.libsound.AudioFormat
import dev.hivens.libsound.AudioSink
import dev.hivens.libsound.Capabilities
import dev.hivens.libsound.Capability
import dev.hivens.libsound.PcmEncoding
import dev.hivens.libsound.PcmRingBuffer
import dev.hivens.libsound.audio.realtime.RealtimeThreads
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
 * `pw_time.ticks` is the graph's own clock, described by the header as the time
 * the remote end is reading and monotonically increasing. Whether it keeps
 * advancing through an underrun on frames this stream never provided has not
 * been measured, and section 13.10 of the plan says so rather than this
 * pretending otherwise. What is reported is the count of frames the callback
 * actually took out of the ring, which is right either way and needs no such
 * measurement. `pa_stream_get_time` and `AudioTimeStamp.mSampleTime` each had
 * that trap and each needed a different correction, so counting is the answer
 * that does not depend on which kind this turns out to be.
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
    private val baseCapabilities: Capabilities,
) : AudioSink {

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

    /**
     * The stream, read and written only under the loop lock.
     *
     * Volatile is not enough on its own and the reason is worth stating: a
     * caller that reads the pointer into a local and calls through it
     * afterwards is safe when the local keeps a Java object alive, and this
     * local is an address. So the read, the null check and the call all happen
     * inside one `loop.locked`, which is the same lock [disconnectStream]
     * destroys under. Reading it outside and calling inside would leave the
     * destroy free to run in between.
     */
    @Volatile
    private var stream: MemorySegment = MemorySegment.NULL

    @Volatile
    private var openFormat: AudioFormat? = null

    @Volatile
    private var volumeValue = 1f

    /** Frames of real audio the callback has handed to the graph. */
    private val framesRendered = AtomicLong(0)

    /** Periods the callback could not fill from the ring. */
    private val underruns = AtomicLong(0)

    /**
     * Whether anybody has started feeding this sink since [open].
     *
     * The contract says open starts the device, so the graph pulls from the
     * moment it returns and every period before the first write comes up short.
     * Counting those would make the number a consumer watches to decide whether
     * it asked for too short a buffer report a guaranteed burst at every open
     * and every track change, which is noise in the one place the count is
     * supposed to be signal.
     */
    @Volatile
    private var fed = false

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
     * The channel count, the encoding, and whether the graph has a name for
     * every position the layout claims.
     *
     * Twenty-six of the thirty-six, against the compatibility layer's eighteen.
     * A layout naming one of the other ten is refused rather than placed as a
     * neighbour, and the message says to send the same audio with an
     * unspecified layout instead.
     *
     * Except at two channels and below, where nothing is refused: there is
     * nothing to place and every platform agrees, so `binaural` and `downmix`
     * open and travel as a bare count. That is the rule
     * [dev.hivens.libsound.Capability.CHANNEL_PLACEMENT] states, and it is what
     * lets those two through on every backend here.
     */
    override fun accepts(format: AudioFormat): Boolean =
        format.channels <= SpaAbi.MAX_CHANNELS &&
            (
                !format.layout.isSpecified ||
                    format.channels <= UNIVERSAL_CHANNELS ||
                    SpaPod.positionsOf(format.layout) != null
                )

    override fun open(format: AudioFormat) {
        if (closed.get()) throw AudioException("sink is closed")
        refuseUnacceptable(format)
        disconnectStream()
        // Cleared before anything is attempted rather than replaced after it
        // succeeded. An open that throws half way leaves nothing open, and a
        // consumer walking down a ladder of encodings reads isOpen and format
        // between the rungs.
        openFormat = null

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
        fed = false

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
        // Everything past here can fail, and a sink that failed to open is a
        // sink that is not open: the stream goes and the format stays null, so
        // isOpen answers false and write refuses rather than parking on a ring
        // nothing will ever drain.
        runCatching {
            awaitReady()
            openFormat = format
            // The contract's first rule: open starts the device. A consumer
            // that wants silence stops immediately after.
            applyVolume()
            start()
        }.onFailure { failure ->
            openFormat = null
            disconnectStream()
            ring?.close()
            throw failure as? AudioException ?: AudioException("open failed: ${failure.message}", failure)
        }
        // No "by count" case to report: refuseUnacceptable above has already
        // thrown for every layout this would not honour.
        log.info("stream open: {} ring={} frames, layout {}", format, depthFrames, format.layout)
    }

    override fun write(data: ByteArray, offset: Int, length: Int) {
        val format = openFormat ?: throw AudioException("write before open")
        require(offset >= 0 && length >= 0 && offset + length <= data.size) {
            "range $offset..${offset + length} outside array of ${data.size}"
        }
        require(length % format.bytesPerFrame == 0) {
            "length ($length) must be a whole number of frames (${format.bytesPerFrame})"
        }
        if (config.realtime) promoteThisThread()
        val current = ring ?: throw AudioException("write on a closed sink")
        // From here the device running dry is the consumer falling behind
        // rather than the consumer not having started.
        fed = true
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
        loop.locked {
            val current = stream
            if (current.address() == 0L) return@locked
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

    /**
     * What the ring has room for, which is exactly where a write parks when it
     * has none.
     */
    override fun writableFrames(): Long {
        // The flag and the format as well as the ring: close() closes the ring
        // to free a parked producer and leaves the reference standing, and a
        // closed ring still reports its whole capacity as free.
        if (closed.get() || openFormat == null) return 0L
        val current = ring ?: return 0L
        val bytes = frameBytes
        return if (bytes <= 0 || current.isClosed()) 0L else (current.free() / bytes).toLong()
    }

    /**
     * The graph's share of the path, in nanoseconds.
     *
     * Three segments, in two different units, and adding them as one number was
     * wrong. The header draws the path as `queued`, then `buffered`, then
     * `delay`, and says of the rate field that it is "the rate of ticks and
     * delay". So only `delay` is in the graph's units.
     *
     * `queued` is the sum of the `size` fields of the buffers this client has
     * handed over, and this client writes frames of its own format into that
     * field, so it is in the stream's frames. `buffered` is documented as
     * frames held in the resampler, which sits on the stream's side of the rate
     * change. Both are converted at the stream's rate and only `delay` at the
     * graph's.
     *
     * A stream at 48 kHz on a graph at 44.1 is where the difference shows, and
     * that is the ordinary case this library exists to stop mattering rather
     * than an exotic one.
     */
    private fun graphNanos(time: MemorySegment, format: AudioFormat): Long {
        val queued = time.get(ValueLayout.JAVA_LONG, SpaAbi.TIME_QUEUED)
        val buffered = time.get(ValueLayout.JAVA_LONG, SpaAbi.TIME_BUFFERED)
        val ours = format.nanosFor(maxOf(queued, 0L) + maxOf(buffered, 0L))
        return ours + delayNanos(time)
    }

    /**
     * `pw_time.delay`, in nanoseconds, at the rate the same struct reports.
     *
     * Negative is a documented value rather than an error, and it means the
     * stream is asked for late rather than early. Zero here is the honest
     * answer where the graph has not yet decided a rate, because a delay
     * converted at the stream's rate instead would be a number invented from
     * the wrong clock.
     */
    private fun delayNanos(time: MemorySegment): Long {
        val delay = time.get(ValueLayout.JAVA_LONG, SpaAbi.TIME_DELAY)
        if (delay <= 0) return 0L
        val num = time.get(ValueLayout.JAVA_INT, SpaAbi.TIME_RATE_NUM)
        val denom = time.get(ValueLayout.JAVA_INT, SpaAbi.TIME_RATE_DENOM)
        if (num <= 0 || denom <= 0) return 0L
        // Split so the multiplication cannot overflow, the same correction the
        // frame arithmetic in AudioFormat carries.
        val whole = delay / denom
        val remainder = delay % denom
        return (whole * num * AudioFormat.NANOS_PER_SECOND) +
            (remainder * num * AudioFormat.NANOS_PER_SECOND / denom)
    }

    /**
     * Cycles where the graph asked for audio and the ring had less than it
     * wanted, which is one of the two ways this stream leaves a gap.
     *
     * The other is a cycle this node did not finish in time, and nothing here
     * can count it: the count is incremented inside the process callback, so a
     * callback that ran late or did not run increments nothing. Measured
     * against a graph on a 2.67 ms quantum with a thread allocating hard
     * alongside: the daemon logged 112 missed cycles for this node in twenty
     * seconds while this number stayed at zero, because whenever the callback
     * did run the ring had audio for it.
     *
     * The graph does keep that count and publishes it through the profiler
     * object the daemon loads by default, which is where `pw-top` reads its
     * error column. Binding that and reading this node's entry is what would
     * make the number whole, and it is not built.
     */
    override fun underrunCount(): Long = underruns.get()

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

    /**
     * Ask for real-time priority for the thread that writes, once per thread.
     *
     * Off unless the config asked, because the limit is process wide and not
     * something a library takes on behalf of a caller that did not. Done at the
     * call rather than at open because the contract's pacing puts the deadline
     * on whichever thread writes, and that is not necessarily the one that
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
            log.info("the writer thread runs at real-time priority")
        } else if (realtimeRefusalLogged.compareAndSet(false, true)) {
            log.info(
                "no real-time priority for the writer thread ({}); the lowest latency profiles will " +
                    "underrun under load",
                refusal,
            )
        }
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
        // atomic increment, which is what this may cost on this thread. Not
        // counted before the first write, where a dry ring is the ordinary
        // state of a sink that has been opened and not yet fed.
        if (real < wanted && fed) underruns.incrementAndGet()
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
        loop.locked {
            val current = stream
            if (current.address() == 0L) return@locked
            runCatching { lib.handle("pw_stream_set_active").invokeExact(current, active) as Int }
                .onFailure { log.debug("set_active({}) threw: {}", active, it.message) }
            // Whoever is parked on the loop's own condition is woken to
            // re-read the state. A producer parked in write is not one of
            // them: it waits on the ring's condition, which this cannot reach
            // and does not try to. Stopping a full sink leaves that producer
            // parked until flush or close, which is what the contract promises
            // and no more.
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
        val deadline = System.nanoTime() + READY_TIMEOUT_NANOS
        val timedOut = loop.locked {
            while (System.nanoTime() < deadline) {
                // Re-read under the lock every time round. The wait below
                // releases it, and a close in that window destroys the stream:
                // a pointer taken before the loop would be read again after it
                // had been freed.
                val current = stream
                if (current.address() == 0L) return@locked false
                val state = Arena.ofConfined().use { call ->
                    lib.handle("pw_stream_get_state").invokeExact(current, call.allocate(ValueLayout.ADDRESS)) as Int
                }
                if (state == SpaAbi.STREAM_STATE_ERROR) throw AudioException("the stream went to error")
                if (state != SpaAbi.STREAM_STATE_CONNECTING && state != SpaAbi.STREAM_STATE_UNCONNECTED) {
                    return@locked false
                }
                // Bounded, because the unbounded sibling never returns on a
                // graph that stops changing the state: the deadline above is
                // only reached by waking up, and nothing is obliged to wake it.
                loop.awaitFor(READY_WAIT_SECONDS)
            }
            true
        }
        // Reported by failing, not by a line in a log. A consumer whose open
        // returned believes it has a stream, writes into it, and parks on a
        // ring that nothing drains, which is a worse way to learn the graph
        // never answered than an exception at the call it made.
        if (timedOut) {
            throw AudioException(
                "the stream did not leave connecting within ${READY_TIMEOUT_NANOS / 1_000_000} ms",
            )
        }
    }

    /**
     * Destroy the stream, with the claim and the destroy under one lock.
     *
     * The lock is what makes the pointer safe to use rather than merely safe to
     * swap. Everything that dereferences it reads the field inside the same
     * `loop.locked`, so a reader either finds it alive, in which case this waits
     * for the lock, or finds null. Claiming it under a monitor of our own and
     * destroying it under the loop's would leave a reader holding a pointer
     * across the window between the two, which is a read of freed memory inside
     * libpipewire and, through the volume control, a write.
     */
    private fun disconnectStream() {
        runCatching {
            loop.locked {
                val current = stream
                if (current.address() == 0L) return@locked
                stream = MemorySegment.NULL
                lib.handle("pw_stream_disconnect").invokeExact(current) as Int
                lib.handle("pw_stream_destroy").invokeExact(current) as Unit
            }
        }.onFailure { log.warn("stream teardown threw: {}", it.message) }
    }

    /** The refusal [accepts] promised, naming the position that could not be placed. */
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

        /** The bounded wait's own ceiling, past the deadline above so the deadline decides. */
        const val READY_WAIT_SECONDS = 3
    }
}
