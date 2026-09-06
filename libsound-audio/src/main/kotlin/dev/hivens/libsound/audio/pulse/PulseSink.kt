package dev.hivens.libsound.audio.pulse

import dev.hivens.libsound.AudioException
import dev.hivens.libsound.AudioFormat
import dev.hivens.libsound.AudioSink
import dev.hivens.libsound.Capabilities
import dev.hivens.libsound.Capability
import dev.hivens.libsound.PcmEncoding
import dev.hivens.libsound.SinkConfig
import dev.hivens.libsound.audio.realtime.RealtimeThreads
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
 * An [AudioSink] over a `pa_stream` on the shared [PulseContext].
 *
 * The identity this carries -- application name, icon, media role -- is half of
 * why the library exists: it is what turns an anonymous client row in the
 * desktop's mixer into a stream an EasyEffects rule can address.
 *
 * Two behaviours were measured in the Phase 0 spike rather than assumed, and
 * both shaped this class. `pa_stream_get_time` answers `-PA_ERR_NODATA` for the
 * life of the stream unless the timing flags are set at connect time and a
 * first update is requested explicitly, so [open] does both before it returns.
 * And a flush moves the playhead by exactly zero here, unlike JavaSound, so
 * there is no credit to compensate.
 */
internal class PulseSink(
    private val pulse: PulseContext,
    private val config: SinkConfig,
    private val baseCapabilities: Capabilities,
) : AudioSink {

    /**
     * The base set, plus the one entry that is decided per thread at runtime.
     *
     * [Capability.REALTIME_THREAD] cannot be a constant: whether the writer
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

    private val log = LoggerFactory.getLogger("libsound.Pulse")

    private val lib = pulse.lib

    @Volatile
    private var stream: MemorySegment = MemorySegment.NULL

    @Volatile
    private var openFormat: AudioFormat? = null

    /**
     * Read by the write loop on every pass. A close, or a stream that died
     * under us, has to break a producer parked waiting for buffer space -- and
     * because the wait is ours, breaking it costs a signal rather than the
     * stream.
     */
    @Volatile
    private var abort = false

    /**
     * A plain flag here was a check-then-set: two threads closing the same sink
     * -- a consumer and `PulseBackend.close()` walking its list -- both read
     * false, both proceeded, and both reached `pa_stream_unref` on one pointer.
     * A double unref is a double free, and `runCatching` cannot catch it because
     * the crash is native.
     */
    private val closed = AtomicBoolean(false)

    /** Guards the stream pointer swap so a disconnect can happen exactly once. */
    private val streamLock = Any()

    /** Last real playhead reading, held so a failed query cannot report zero. */
    @Volatile
    private var lastKnownFrames = 0L

    /**
     * Frames handed to the server since [open], and the ceiling on the playhead.
     *
     * `pa_stream_get_time` is a media *clock*, not a count of frames rendered:
     * once the stream underruns it keeps advancing on time the device never
     * played. Measured -- a stream fed half a second and then left alone
     * reported well past half a second. Unclamped, a consumer's audio clock
     * runs away during exactly the starvation where video most needs it to
     * stall, which is the opposite of what an underrun is supposed to do to
     * synchronisation.
     */
    @Volatile
    private var framesWritten = 0L

    @Volatile
    private var volumeValue = 1f

    /**
     * Times the server ran out of audio to play since the last [open].
     *
     * Written from the mainloop thread by the underflow callback and read by
     * whoever is watching, which is what makes it an atomic rather than a
     * volatile increment.
     */
    private val underruns = AtomicLong(0)

    /**
     * The other direction: audio written faster than the server could take it,
     * which means a producer ignoring what the write returned. Counted for the
     * one debug line at close rather than exposed, because the interface has no
     * question it answers.
     */
    private val overflows = AtomicLong(0)

    /** What the server granted, so the log line at open can say it. */
    @Volatile
    private var grantedNanos = 0L

    /**
     * One promotion attempt per thread, successful or not.
     *
     * The thread that matters is the one that writes, which is the consumer's
     * own and need not be the one that opened the sink. A thread-local is what
     * makes "promote whoever turns up, once" the whole of the bookkeeping.
     */
    private val promotionAttempted = ThreadLocal.withInitial { false }

    @Volatile
    private var realtimeGranted = false

    /** So a machine without RealtimeKit says so once rather than per write. */
    private val realtimeRefusalLogged = AtomicBoolean(false)

    /** Native scratch for the copy into `pa_stream_write`; reallocated per open. */
    private var scratchArena: Arena? = null
    private var scratch: MemorySegment = MemorySegment.NULL

    /**
     * Built once per sink and reused across reopens.
     *
     * The arena is the library's, which outlives the mainloop thread, and a
     * stub allocated per open would leave one behind on every track change.
     */
    private val underflowStub: MemorySegment by lazy { notifyStub("onUnderflow") }

    private val overflowStub: MemorySegment by lazy { notifyStub("onOverflow") }

    private fun notifyStub(method: String): MemorySegment = Linker.nativeLinker().upcallStub(
        MethodHandles.lookup().findVirtual(
            PulseSink::class.java, method,
            MethodType.methodType(Void.TYPE, MemorySegment::class.java, MemorySegment::class.java),
        ).bindTo(this),
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS),
        lib.arena,
    )

    // Public rather than internal although nothing outside calls them: Kotlin
    // mangles an internal name and findVirtual looks up what is written.

    /** On the mainloop thread. Counts, and does nothing else. */
    fun onUnderflow(unusedStream: MemorySegment, unusedUserData: MemorySegment) {
        underruns.incrementAndGet()
    }

    fun onOverflow(unusedStream: MemorySegment, unusedUserData: MemorySegment) {
        overflows.incrementAndGet()
    }

    override val format: AudioFormat? get() = openFormat

    override val isOpen: Boolean get() = stream.address() != 0L && !closed.get()

    override fun open(format: AudioFormat) {
        if (closed.get()) throw AudioException("sink is closed")
        require(format.encoding == PcmEncoding.S16LE || format.encoding == PcmEncoding.F32LE) {
            "unsupported encoding ${format.encoding}"
        }
        disconnectStream()
        abort = false
        lastKnownFrames = 0
        framesWritten = 0
        underruns.set(0)
        overflows.set(0)

        val targetNanos = config.targetNanos
        val tlength = format.bytesFor(format.framesFor(targetNanos)).toInt()
            .coerceAtLeast(format.bytesPerFrame)
        // A quarter of the target, which is the usual relationship, and never
        // below a frame. minreq is how much the server asks for at a time, so
        // leaving it at the default leaves half the latency to the server.
        val minreq = (tlength / MINREQ_DIVISOR)
            .let { it - it % format.bytesPerFrame }
            .coerceAtLeast(format.bytesPerFrame)

        Arena.ofConfined().use { setup ->
            val spec = setup.allocate(PulseAbi.SAMPLE_SPEC_SIZE, 4)
            spec.set(ValueLayout.JAVA_INT, PulseAbi.SAMPLE_SPEC_FORMAT, encodingOf(format))
            spec.set(ValueLayout.JAVA_INT, PulseAbi.SAMPLE_SPEC_RATE, format.sampleRate)
            spec.set(ValueLayout.JAVA_BYTE, PulseAbi.SAMPLE_SPEC_CHANNELS, format.channels.toByte())

            val attr = setup.allocate(PulseAbi.BUFFER_ATTR_SIZE, 4)
            // Left to the server, not tlength * 2: with ADJUST_LATENCY the
            // server sizes the shared buffer itself to meet the target, and a
            // client number fights it.
            attr.set(ValueLayout.JAVA_INT, PulseAbi.BUFFER_ATTR_MAXLENGTH, PulseAbi.ATTR_DEFAULT)
            attr.set(ValueLayout.JAVA_INT, PulseAbi.BUFFER_ATTR_TLENGTH, tlength)
            attr.set(ValueLayout.JAVA_INT, PulseAbi.BUFFER_ATTR_PREBUF, PulseAbi.ATTR_DEFAULT)
            attr.set(ValueLayout.JAVA_INT, PulseAbi.BUFFER_ATTR_MINREQ, minreq)
            attr.set(ValueLayout.JAVA_INT, PulseAbi.BUFFER_ATTR_FRAGSIZE, PulseAbi.ATTR_DEFAULT)

            val proplist = lib.handle("pa_proplist_new").invokeExact() as MemorySegment
            propSet(setup, proplist, PulseAbi.PROP_APPLICATION_NAME, config.applicationName)
            config.applicationId?.let { propSet(setup, proplist, PulseAbi.PROP_APPLICATION_ID, it) }
            config.iconName?.let { propSet(setup, proplist, PulseAbi.PROP_APPLICATION_ICON_NAME, it) }
            propSet(setup, proplist, PulseAbi.PROP_MEDIA_ROLE, config.mediaRole.wireName)

            val streamName = setup.allocateUtf8(config.applicationName)
            val deviceName = config.device?.let { setup.allocateUtf8(it.value) } ?: MemorySegment.NULL

            pulse.lock()
            try {
                val fresh = lib.handle("pa_stream_new_with_proplist")
                    .invokeExact(pulse.context, streamName, spec, MemorySegment.NULL, proplist) as MemorySegment
                lib.handle("pa_proplist_free").invokeExact(proplist) as Unit
                if (fresh.address() == 0L) {
                    throw AudioException("pa_stream_new_with_proplist: ${pulse.lastError()}")
                }
                lib.handle("pa_stream_set_state_callback")
                    .invokeExact(fresh, pulse.notifyStub, MemorySegment.NULL) as Unit
                lib.handle("pa_stream_set_write_callback")
                    .invokeExact(fresh, pulse.writeRequestStub, MemorySegment.NULL) as Unit
                lib.handle("pa_stream_set_underflow_callback")
                    .invokeExact(fresh, underflowStub, MemorySegment.NULL) as Unit
                lib.handle("pa_stream_set_overflow_callback")
                    .invokeExact(fresh, overflowStub, MemorySegment.NULL) as Unit

                // START_CORKED, then uncork below. Connecting already running
                // would let the server pull from an empty buffer before the
                // first write, which is an underrun on the very first frame.
                //
                // ADJUST_LATENCY is the line this section is about: it is what
                // makes tlength a latency the server shortens its own path to
                // meet, and it is what pipewire-pulse translates into the
                // graph node's quantum. Without it the number above is a
                // buffer size and the path in front of it stays whatever the
                // server chose.
                val flags = PulseAbi.STREAM_START_CORKED or
                    PulseAbi.STREAM_TIMING_FLAGS or
                    PulseAbi.STREAM_ADJUST_LATENCY
                val rc = lib.handle("pa_stream_connect_playback")
                    .invokeExact(fresh, deviceName, attr, flags, MemorySegment.NULL, MemorySegment.NULL) as Int
                if (rc < 0) {
                    lib.handle("pa_stream_unref").invokeExact(fresh) as Unit
                    throw AudioException("pa_stream_connect_playback: ${pulse.lastError()}")
                }
                while (true) {
                    val state = lib.handle("pa_stream_get_state").invokeExact(fresh) as Int
                    if (state == PulseAbi.STREAM_READY) break
                    if (state == PulseAbi.STREAM_FAILED || state == PulseAbi.STREAM_TERMINATED) {
                        // Disconnect before unref. A connected stream that is
                        // only unreffed lives on in the client and in the
                        // server's list -- one leaked sink input per failed open.
                        lib.handle("pa_stream_disconnect").invokeExact(fresh) as Int
                        lib.handle("pa_stream_unref").invokeExact(fresh) as Unit
                        throw AudioException("stream state $state: ${pulse.lastError()}")
                    }
                    pulse.await()
                }
                stream = fresh
            } finally {
                pulse.unlock()
            }
        }

        openFormat = format
        val granted = grantedTlengthBytes() ?: tlength
        grantedNanos = format.nanosFor(format.framesIn(granted.toLong()))
        val arena = Arena.ofShared()
        scratchArena?.let { runCatching { it.close() } }
        scratchArena = arena
        // Sized from what the server granted rather than what was asked for,
        // and floored: at five milliseconds the request is under a kilobyte,
        // and a scratch that small turns one write into a hundred copies.
        scratch = arena.allocate(maxOf(granted, MIN_SCRATCH_BYTES).toLong(), 8)

        awaitTimingInfo()
        // Attempted here as well as on the first write, so a consumer reading
        // capabilities straight after open gets a truthful answer rather than
        // one that only becomes true once audio is flowing.
        if (config.realtime) promoteThisThread()
        // The contract's first rule: open starts the device.
        cork(false)
        applyVolume()
        // A profile is a request and the graph's quantum is a floor under it,
        // so what was granted is worth one line: a consumer that asked for five
        // milliseconds on a desktop nobody configured for audio work gets
        // twenty, and this is where that stops being invisible.
        log.info(
            "stream open: {} asked for {} ms, granted {} ms ({} bytes)",
            format, targetNanos / 1_000_000, grantedNanos / 1_000_000, granted,
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
        if (config.realtime) promoteThisThread()
        var written = 0L
        pulse.lock()
        try {
            while (written < length) {
                if (abort || closed.get()) throw AudioException("sink closed while writing")
                val current = stream
                if (current.address() == 0L) throw AudioException("write on a disconnected stream")
                val state = lib.handle("pa_stream_get_state").invokeExact(current) as Int
                if (state != PulseAbi.STREAM_READY) throw AudioException("stream is not ready ($state)")

                val writable = lib.handle("pa_stream_writable_size").invokeExact(current) as Long
                if (writable == PulseAbi.SIZE_ERROR) {
                    throw AudioException("pa_stream_writable_size: ${pulse.lastError()}")
                }
                if (writable == 0L) {
                    // The pacing point, and the wait we own: a signal from any
                    // other thread frees this without touching the stream.
                    pulse.await()
                    continue
                }
                var chunk = minOf(writable, (length - written), scratch.byteSize())
                chunk -= chunk % format.bytesPerFrame
                if (chunk == 0L) {
                    pulse.await()
                    continue
                }
                MemorySegment.copy(
                    data, (offset + written).toInt(),
                    scratch, ValueLayout.JAVA_BYTE, 0L, chunk.toInt(),
                )
                val rc = lib.handle("pa_stream_write").invokeExact(
                    current, scratch.asSlice(0, chunk), chunk, MemorySegment.NULL, 0L, PulseAbi.SEEK_RELATIVE,
                ) as Int
                if (rc < 0) throw AudioException("pa_stream_write: ${pulse.lastError()}")
                written += chunk
                framesWritten += format.framesIn(chunk)
            }
        } finally {
            pulse.unlock()
        }
    }

    override fun start() = cork(false)

    override fun stop() = cork(true)

    override fun flush() {
        val current = stream
        if (current.address() == 0L) return
        pulse.locked {
            val op = lib.handle("pa_stream_flush")
                .invokeExact(current, MemorySegment.NULL, MemorySegment.NULL) as MemorySegment
            pulse.releaseOperation(op)
        }
    }

    override fun framePosition(): Long {
        val current = stream
        val format = openFormat ?: return 0L
        if (current.address() == 0L) return lastKnownFrames
        return pulse.locked {
            Arena.ofConfined().use { call ->
                val out = call.allocate(ValueLayout.JAVA_LONG)
                val rc = lib.handle("pa_stream_get_time").invokeExact(current, out) as Int
                // Zero would be the one answer indistinguishable from a fresh
                // open, and a clock re-anchoring on it jumps to the start of the
                // track. Holding the last real reading is the honest failure:
                // the playhead stalls, which is what a stalled device looks
                // like anyway.
                if (rc != 0) return@locked lastKnownFrames
                val frames = format.framesFor(out.get(ValueLayout.JAVA_LONG, 0) * 1_000L)
                    .coerceAtMost(framesWritten)
                lastKnownFrames = frames
                frames
            }
        }
    }

    override fun latencyNanos(): Long {
        val current = stream
        if (current.address() == 0L) return 0L
        return pulse.locked {
            Arena.ofConfined().use { call ->
                val usec = call.allocate(ValueLayout.JAVA_LONG)
                val negative = call.allocate(ValueLayout.JAVA_INT)
                val rc = lib.handle("pa_stream_get_latency").invokeExact(current, usec, negative) as Int
                if (rc != 0) return@locked 0L
                // A negative latency means the server is behind the write head;
                // reporting it as zero is honest enough for a buffer estimate.
                if (negative.get(ValueLayout.JAVA_INT, 0) != 0) 0L
                else usec.get(ValueLayout.JAVA_LONG, 0) * 1_000L
            }
        }
    }

    override fun underrunCount(): Long = underruns.get()

    override fun setVolume(volume: Float) {
        volumeValue = volume.coerceIn(0f, 1f)
        applyVolume()
    }

    override fun volume(): Float = volumeValue

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        abort = true
        // Free a producer parked in write() before tearing anything down: the
        // signal costs nothing and the alternative is joining a thread that is
        // waiting on a stream we are about to destroy.
        runCatching { pulse.locked { pulse.signal() } }
        disconnectStream()
        if (overflows.get() > 0) {
            log.debug("the server refused audio {} time(s): a producer wrote past what write returned", overflows.get())
        }
        scratchArena?.let { runCatching { it.close() } }
        scratchArena = null
        scratch = MemorySegment.NULL
        openFormat = null
    }

    // -- internals -----------------------------------------------------------

    /**
     * Ask for real-time priority for whichever thread is here, once.
     *
     * Failure is not fatal and not silent: the reason goes out once, and
     * [Capability.REALTIME_THREAD] stays absent so a settings screen can say
     * why the lowest profile is not on offer instead of letting a user pick one
     * that crackles.
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
                "no real-time priority for the writer thread ({}); the lowest latency profiles will underrun " +
                    "under load",
                refusal,
            )
        }
    }

    private fun cork(on: Boolean) {
        val current = stream
        if (current.address() == 0L) return
        pulse.locked {
            val op = lib.handle("pa_stream_cork")
                .invokeExact(current, if (on) 1 else 0, MemorySegment.NULL, MemorySegment.NULL) as MemorySegment
            pulse.releaseOperation(op)
            // A corked stream will never report writable space again, so a
            // producer parked in write() has to be told rather than left to
            // discover it at a timeout it does not have.
            // Wakes a parked producer so it re-checks abort and closed. It does
            // NOT free it: a corked stream reports no writable space, so the
            // loop parks again, exactly as the contract's deadlock semantics
            // say it should. close() is the escape, and the only one.
            if (on) pulse.signal()
        }
    }

    /**
     * Ask for the first timing block and wait for it.
     *
     * `PA_STREAM_AUTO_TIMING_UPDATE` requests one asynchronously, so a read
     * straight after READY still answers `NODATA`. Returning from [open] before
     * the playhead can answer would hand the clock a stream that looks stopped.
     */
    private fun awaitTimingInfo() {
        val current = stream
        if (current.address() == 0L) return
        pulse.locked {
            val op = lib.handle("pa_stream_update_timing_info")
                .invokeExact(current, MemorySegment.NULL, MemorySegment.NULL) as MemorySegment
            pulse.releaseOperation(op)
        }
        val deadline = System.nanoTime() + TIMING_TIMEOUT_NANOS
        while (System.nanoTime() < deadline) {
            val ready = pulse.locked {
                Arena.ofConfined().use { call ->
                    val out = call.allocate(ValueLayout.JAVA_LONG)
                    (lib.handle("pa_stream_get_time").invokeExact(current, out) as Int) == 0
                }
            }
            if (ready) return
            Thread.sleep(TIMING_POLL_MILLIS)
        }
        log.warn("timing info did not arrive within {} ms; the playhead may lag", TIMING_TIMEOUT_NANOS / 1_000_000)
    }

    private fun applyVolume() {
        val current = stream
        if (current.address() == 0L) return
        val format = openFormat ?: return
        pulse.locked {
            val index = lib.handle("pa_stream_get_index").invokeExact(current) as Int
            Arena.ofConfined().use { call ->
                val cvolume = call.allocate(PulseAbi.CVOLUME_SIZE, 4)
                // The library's own converter and the library's own setter: a
                // hand-filled pa_cvolume cannot disagree with a layout it did
                // not write, but it can disagree with a scale it did not choose.
                val level = lib.handle("pa_sw_volume_from_linear")
                    .invokeExact(volumeValue.toDouble()) as Int
                lib.handle("pa_cvolume_set").invokeExact(cvolume, format.channels, level) as MemorySegment
                val op = lib.handle("pa_context_set_sink_input_volume").invokeExact(
                    pulse.context, index, cvolume, MemorySegment.NULL, MemorySegment.NULL,
                ) as MemorySegment
                pulse.releaseOperation(op)
            }
        }
    }

    private fun disconnectStream() {
        // Claim the pointer before touching it. Read-then-null let two threads
        // walk away with the same pa_stream and unref it twice.
        val current = synchronized(streamLock) {
            val held = stream
            stream = MemorySegment.NULL
            held
        }
        if (current.address() == 0L) return
        runCatching {
            pulse.locked {
                lib.handle("pa_stream_set_state_callback")
                    .invokeExact(current, MemorySegment.NULL, MemorySegment.NULL) as Unit
                lib.handle("pa_stream_set_write_callback")
                    .invokeExact(current, MemorySegment.NULL, MemorySegment.NULL) as Unit
                lib.handle("pa_stream_set_underflow_callback")
                    .invokeExact(current, MemorySegment.NULL, MemorySegment.NULL) as Unit
                lib.handle("pa_stream_set_overflow_callback")
                    .invokeExact(current, MemorySegment.NULL, MemorySegment.NULL) as Unit
                lib.handle("pa_stream_disconnect").invokeExact(current) as Int
                lib.handle("pa_stream_unref").invokeExact(current) as Unit
            }
        }.onFailure { log.warn("stream teardown threw: {}", it.message) }
    }

    /**
     * The tlength the server actually settled on, read once the stream is
     * ready. Null when the query fails, which leaves the request as the best
     * available answer.
     */
    private fun grantedTlengthBytes(): Int? {
        val current = stream
        if (current.address() == 0L) return null
        return pulse.locked {
            val attr = lib.handle("pa_stream_get_buffer_attr").invokeExact(current) as MemorySegment
            if (attr.address() == 0L) return@locked null
            runCatching {
                attr.reinterpret(PulseAbi.BUFFER_ATTR_SIZE)
                    .get(ValueLayout.JAVA_INT, PulseAbi.BUFFER_ATTR_TLENGTH)
            }.getOrNull()?.takeIf { it > 0 }
        }
    }

    private fun propSet(arena: Arena, proplist: MemorySegment, key: String, value: String) {
        lib.handle("pa_proplist_sets")
            .invokeExact(proplist, arena.allocateUtf8(key), arena.allocateUtf8(value)) as Int
    }

    private fun encodingOf(format: AudioFormat): Int = when (format.encoding) {
        PcmEncoding.S16LE -> PulseAbi.SAMPLE_S16LE
        PcmEncoding.F32LE -> PulseAbi.SAMPLE_FLOAT32LE
    }

    private companion object {
        /**
         * How much of the target the server may ask for at a time. A quarter is
         * the usual relationship: large enough that the request rate stays
         * sane, small enough that the server is not holding a third of the
         * latency budget as one lump.
         */
        const val MINREQ_DIVISOR = 4

        /** Eight kilobytes, which is 21 ms of 48 kHz stereo. A floor, not a target. */
        const val MIN_SCRATCH_BYTES = 8_192

        const val TIMING_TIMEOUT_NANOS = 1_000_000_000L
        const val TIMING_POLL_MILLIS = 5L
    }
}
