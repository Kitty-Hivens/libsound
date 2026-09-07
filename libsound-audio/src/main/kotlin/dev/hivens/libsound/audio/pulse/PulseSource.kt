package dev.hivens.libsound.audio.pulse

import dev.hivens.libsound.AudioException
import dev.hivens.libsound.AudioFormat
import dev.hivens.libsound.AudioSource
import dev.hivens.libsound.Capabilities
import dev.hivens.libsound.Capability
import dev.hivens.libsound.PcmEncoding
import dev.hivens.libsound.SourceConfig
import dev.hivens.libsound.audio.realtime.RealtimeThreads
import org.slf4j.LoggerFactory
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * An [AudioSource] over a record `pa_stream` on the shared [PulseContext].
 *
 * The mirror of [PulseSink], and it reuses the machinery that was already here:
 * `pa_stream_connect_record`, `pa_stream_peek` and `pa_stream_drop` were bound
 * and exercised for the level meter, which watches a sink's monitor. Pointing
 * the same three calls at a real source is the smaller half of the work.
 *
 * ## Peek, copy, drop
 *
 * The server hands over whole fragments and takes them back whole: a peek that
 * is dropped without being fully consumed loses the remainder, and one that is
 * not dropped at all stalls the stream for good. So a fragment larger than what
 * the caller asked for is copied as far as it fits and the rest is kept here
 * until the next read.
 *
 * A hole in the stream arrives as a null pointer with a non-zero length. It
 * means the server dropped audio nobody collected in time, which is [overrunFrames]
 * and not an error: a consumer falling behind on a shared machine is ordinary,
 * and losing the frames silently would be the failure.
 *
 * ## What the identity is for
 *
 * A capture stream shows in the desktop's privacy indicator. The application
 * name, icon and role in [SourceConfig] are what the row saying "Example is
 * using your microphone" reads, which is a reason to fill them in rather than a
 * reason to leave them out.
 */
internal class PulseSource(
    private val pulse: PulseContext,
    private val config: SourceConfig,
    private val baseCapabilities: Capabilities,
    private val monitor: MonitorTarget? = null,
) : AudioSource {

    /**
     * One application's output, rather than a device's input.
     *
     * A sink's monitor carries everything that sink plays, and
     * `pa_stream_set_monitor_stream` narrows it to a single sink input before
     * the stream connects. That is per-application recording with no virtual
     * device, no routing, and nothing the target application can see, which is
     * why it is behind [Capability.PER_STREAM_CAPTURE] and documented for what
     * it is.
     */
    internal class MonitorTarget(val sourceName: String, val sinkInputIndex: Int)

    private val log = LoggerFactory.getLogger("libsound.Pulse")

    private val lib = pulse.lib

    override val capabilities: Capabilities
        get() = if (realtimeGranted) {
            Capabilities(baseCapabilities.supported + Capability.REALTIME_THREAD)
        } else {
            baseCapabilities
        }

    @Volatile
    private var stream: MemorySegment = MemorySegment.NULL

    @Volatile
    private var openFormat: AudioFormat? = null

    /** Read by the read loop on every pass; a close has to break a parked reader. */
    @Volatile
    private var abort = false

    private val closed = AtomicBoolean(false)

    /** Guards the stream pointer swap, so a disconnect happens exactly once. */
    private val streamLock = Any()

    /** Last real reading of the capture position, held so a failed query cannot report zero. */
    @Volatile
    private var lastKnownFrames = 0L

    private val overruns = AtomicLong(0)

    @Volatile
    private var grantedFragment = 0L

    /**
     * The fragment the server granted, in nanoseconds, or zero before the first
     * open.
     *
     * Not on [AudioSource]: a consumer asks [latencyNanos], which is the whole
     * path and the number it can act on. This is the backend's own, and it is
     * here because it is the only evidence that a latency profile reached the
     * server at all. A suite that timed a read instead would be measuring when
     * the server chose to hand the first fragment over, which the two servers
     * do not answer the same way.
     */
    internal val grantedFragmentNanos: Long get() = grantedFragment

    @Volatile
    private var volumeValue = 1f

    /**
     * The tail of a fragment the last read could not fit.
     *
     * A fragment is the server's unit and a read's length is the caller's, and
     * they have no reason to match. Dropping the remainder would lose audio,
     * and holding the peek open until it is consumed would stall the stream.
     */
    private var leftover: ByteArray = ByteArray(0)
    private var leftoverOffset = 0

    private val promotionAttempted = ThreadLocal.withInitial { false }

    @Volatile
    private var realtimeGranted = false

    private val realtimeRefusalLogged = AtomicBoolean(false)

    override val format: AudioFormat? get() = openFormat

    override val isOpen: Boolean get() = stream.address() != 0L && !closed.get()

    override fun open(format: AudioFormat) {
        if (closed.get()) throw AudioException("source is closed")
        require(format.encoding == PcmEncoding.S16LE || format.encoding == PcmEncoding.F32LE) {
            "unsupported encoding ${format.encoding}"
        }
        disconnectStream()
        abort = false
        lastKnownFrames = 0
        overruns.set(0)
        leftover = ByteArray(0)
        leftoverOffset = 0

        val targetNanos = config.targetNanos
        // fragsize is the record side's tlength: how much the server collects
        // before it hands anything over, which is the latency a consumer feels.
        val fragsize = format.bytesFor(format.framesFor(targetNanos)).toInt()
            .coerceAtLeast(format.bytesPerFrame)

        Arena.ofConfined().use { setup ->
            val spec = setup.allocate(PulseAbi.SAMPLE_SPEC_SIZE, 4)
            spec.set(ValueLayout.JAVA_INT, PulseAbi.SAMPLE_SPEC_FORMAT, encodingOf(format))
            spec.set(ValueLayout.JAVA_INT, PulseAbi.SAMPLE_SPEC_RATE, format.sampleRate)
            spec.set(ValueLayout.JAVA_BYTE, PulseAbi.SAMPLE_SPEC_CHANNELS, format.channels.toByte())

            val attr = setup.allocate(PulseAbi.BUFFER_ATTR_SIZE, 4)
            // Left to the server, like the playback side: with ADJUST_LATENCY it
            // sizes its own buffer to meet the fragment size asked for.
            attr.set(ValueLayout.JAVA_INT, PulseAbi.BUFFER_ATTR_MAXLENGTH, PulseAbi.ATTR_DEFAULT)
            attr.set(ValueLayout.JAVA_INT, PulseAbi.BUFFER_ATTR_TLENGTH, PulseAbi.ATTR_DEFAULT)
            attr.set(ValueLayout.JAVA_INT, PulseAbi.BUFFER_ATTR_PREBUF, PulseAbi.ATTR_DEFAULT)
            attr.set(ValueLayout.JAVA_INT, PulseAbi.BUFFER_ATTR_MINREQ, PulseAbi.ATTR_DEFAULT)
            attr.set(ValueLayout.JAVA_INT, PulseAbi.BUFFER_ATTR_FRAGSIZE, fragsize)

            val proplist = lib.handle("pa_proplist_new").invokeExact() as MemorySegment
            propSet(setup, proplist, PulseAbi.PROP_APPLICATION_NAME, config.applicationName)
            config.applicationId?.let { propSet(setup, proplist, PulseAbi.PROP_APPLICATION_ID, it) }
            config.iconName?.let { propSet(setup, proplist, PulseAbi.PROP_APPLICATION_ICON_NAME, it) }
            propSet(setup, proplist, PulseAbi.PROP_MEDIA_ROLE, config.mediaRole.wireName)

            val streamName = setup.allocateUtf8(config.applicationName)
            // A monitor target decides the device: the audio wanted is on the
            // sink the target stream is playing to, so a device asked for
            // alongside it would be a contradiction rather than a preference.
            val target = monitor?.sourceName ?: config.device?.value
            val deviceName = target?.let { setup.allocateUtf8(it) } ?: MemorySegment.NULL

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
                // The same signal-only stub the playback side uses for write
                // space: a read callback and a write callback are one type in
                // libpulse, and all either has to do here is wake the reader.
                lib.handle("pa_stream_set_read_callback")
                    .invokeExact(fresh, pulse.requestStub, MemorySegment.NULL) as Unit

                if (monitor != null) {
                    // Before connecting, or the stream is already listening to
                    // the whole sink and the narrowing arrives too late.
                    val aimed = lib.handle("pa_stream_set_monitor_stream")
                        .invokeExact(fresh, monitor.sinkInputIndex) as Int
                    if (aimed < 0) {
                        lib.handle("pa_stream_unref").invokeExact(fresh) as Unit
                        throw AudioException("pa_stream_set_monitor_stream: ${pulse.lastError()}")
                    }
                }

                val flags = PulseAbi.STREAM_START_CORKED or
                    PulseAbi.STREAM_TIMING_FLAGS or
                    PulseAbi.STREAM_ADJUST_LATENCY or
                    // A monitor must stay on the sink it was aimed at: if the
                    // target application moves to another device, following it
                    // would quietly start recording something else.
                    (if (monitor != null) PulseAbi.STREAM_DONT_MOVE else 0)
                val rc = lib.handle("pa_stream_connect_record")
                    .invokeExact(fresh, deviceName, attr, flags) as Int
                if (rc < 0) {
                    lib.handle("pa_stream_unref").invokeExact(fresh) as Unit
                    throw AudioException("pa_stream_connect_record: ${pulse.lastError()}")
                }
                while (true) {
                    val state = lib.handle("pa_stream_get_state").invokeExact(fresh) as Int
                    if (state == PulseAbi.STREAM_READY) break
                    if (state == PulseAbi.STREAM_FAILED || state == PulseAbi.STREAM_TERMINATED) {
                        // Disconnect before unref, or the client and the server
                        // both keep a source output nothing owns.
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
        // What the server settled on, read back for the same reason the
        // playback side reads its own: a request is a request, and a consumer
        // that has to explain the latency it is seeing needs the number that
        // was granted rather than the one that was asked for.
        val granted = grantedFragsizeBytes() ?: fragsize
        grantedFragment = format.nanosFor(format.framesIn(granted.toLong()))
        awaitTimingInfo()
        if (config.realtime) promoteThisThread()
        // The contract's first rule: open starts the device. A consumer that
        // wants the microphone open and silent stops immediately after.
        cork(false)
        applyVolume()
        log.info(
            "capture open: {} asked for {} ms fragments, granted {} ms ({} bytes){}",
            format, targetNanos / 1_000_000, grantedFragment / 1_000_000, granted,
            monitor?.let { ", recording stream ${it.sinkInputIndex}" } ?: "",
        )
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
        var filled = 0
        pulse.lock()
        try {
            Arena.ofConfined().use { call ->
                val data = call.allocate(ValueLayout.ADDRESS)
                val size = call.allocate(ValueLayout.JAVA_LONG)
                while (filled < length) {
                    if (abort || closed.get()) throw AudioException("source closed while reading")
                    val current = stream
                    if (current.address() == 0L) throw AudioException("read on a disconnected stream")
                    val state = lib.handle("pa_stream_get_state").invokeExact(current) as Int
                    if (state != PulseAbi.STREAM_READY) throw AudioException("stream is not ready ($state)")

                    if (leftoverOffset < leftover.size) {
                        filled += takeLeftover(dst, offset + filled, length - filled)
                        continue
                    }

                    val rc = lib.handle("pa_stream_peek").invokeExact(current, data, size) as Int
                    if (rc != 0) throw AudioException("pa_stream_peek: ${pulse.lastError()}")
                    val available = size.get(ValueLayout.JAVA_LONG, 0)
                    if (available == 0L) {
                        // Nothing yet, and nothing to drop: a peek that came back
                        // empty was not a fragment. The wait is ours, so a close
                        // frees it with a signal rather than by destroying the
                        // stream.
                        pulse.await()
                        continue
                    }
                    val pointer = data.get(ValueLayout.ADDRESS, 0)
                    if (pointer.address() == 0L) {
                        // A hole: audio the server dropped because nobody
                        // collected it in time. Counted, and dropped like any
                        // other fragment, because an undropped hole stalls the
                        // stream permanently.
                        overruns.addAndGet(format.framesIn(available))
                        lib.handle("pa_stream_drop").invokeExact(current) as Int
                        continue
                    }
                    val fragment = pointer.reinterpret(available)
                    val wanted = minOf(available, (length - filled).toLong()).toInt()
                    MemorySegment.copy(fragment, ValueLayout.JAVA_BYTE, 0L, dst, offset + filled, wanted)
                    filled += wanted
                    if (wanted < available) {
                        // The remainder is ours to keep: the drop below takes the
                        // whole fragment whether or not it was all used.
                        leftover = ByteArray((available - wanted).toInt())
                        MemorySegment.copy(
                            fragment, ValueLayout.JAVA_BYTE, wanted.toLong(),
                            leftover, 0, leftover.size,
                        )
                        leftoverOffset = 0
                    }
                    lib.handle("pa_stream_drop").invokeExact(current) as Int
                }
            }
        } finally {
            pulse.unlock()
        }
    }

    override fun start(): Unit = cork(false)

    override fun stop(): Unit = cork(true)

    override fun flush() {
        val current = stream
        if (current.address() == 0L) return
        pulse.locked {
            // The tail this side is holding belongs to the same discarded audio
            // as whatever the server is holding.
            leftover = ByteArray(0)
            leftoverOffset = 0
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
                // Zero is the one answer indistinguishable from a fresh open, so
                // a failed query holds the last real reading instead: the
                // position stalls, which is what a stalled device looks like.
                if (rc != 0) return@locked lastKnownFrames
                val frames = format.framesFor(out.get(ValueLayout.JAVA_LONG, 0) * 1_000L)
                lastKnownFrames = frames
                frames
            }
        }
    }

    /**
     * The whole path, which on this side means how long ago the audio about to
     * be read was actually spoken.
     */
    override fun latencyNanos(): Long {
        val current = stream
        if (current.address() == 0L) return 0L
        return pulse.locked {
            Arena.ofConfined().use { call ->
                val usec = call.allocate(ValueLayout.JAVA_LONG)
                val negative = call.allocate(ValueLayout.JAVA_INT)
                val rc = lib.handle("pa_stream_get_latency").invokeExact(current, usec, negative) as Int
                if (rc != 0) return@locked 0L
                if (negative.get(ValueLayout.JAVA_INT, 0) != 0) 0L
                else usec.get(ValueLayout.JAVA_LONG, 0) * 1_000L
            }
        }
    }

    override fun overrunFrames(): Long = overruns.get()

    override fun setVolume(volume: Float) {
        volumeValue = volume.coerceIn(0f, 1f)
        applyVolume()
    }

    override fun volume(): Float = volumeValue

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        abort = true
        // Free a reader parked waiting for a fragment before tearing anything
        // down: the signal costs nothing, and the alternative is destroying a
        // stream a thread is still waiting on.
        runCatching { pulse.locked { pulse.signal() } }
        disconnectStream()
        leftover = ByteArray(0)
        leftoverOffset = 0
        openFormat = null
    }

    // -- internals ------------------------------------------------------------

    /**
     * The fragment size the server settled on, read once the stream is ready.
     * Null when the query fails, which leaves the request as the best answer
     * available.
     */
    private fun grantedFragsizeBytes(): Int? {
        val current = stream
        if (current.address() == 0L) return null
        return pulse.locked {
            val attr = lib.handle("pa_stream_get_buffer_attr").invokeExact(current) as MemorySegment
            if (attr.address() == 0L) return@locked null
            runCatching {
                attr.reinterpret(PulseAbi.BUFFER_ATTR_SIZE)
                    .get(ValueLayout.JAVA_INT, PulseAbi.BUFFER_ATTR_FRAGSIZE)
            }.getOrNull()?.takeIf { it > 0 }
        }
    }

    /** Caller holds the mainloop lock. */
    private fun takeLeftover(dst: ByteArray, at: Int, wanted: Int): Int {
        val chunk = minOf(wanted, leftover.size - leftoverOffset)
        System.arraycopy(leftover, leftoverOffset, dst, at, chunk)
        leftoverOffset += chunk
        if (leftoverOffset >= leftover.size) {
            leftover = ByteArray(0)
            leftoverOffset = 0
        }
        return chunk
    }

    private fun cork(on: Boolean) {
        val current = stream
        if (current.address() == 0L) return
        pulse.locked {
            val op = lib.handle("pa_stream_cork")
                .invokeExact(current, if (on) 1 else 0, MemorySegment.NULL, MemorySegment.NULL) as MemorySegment
            pulse.releaseOperation(op)
            // A corked stream delivers nothing, so a reader parked waiting for a
            // fragment has to be woken to re-check. It does not free it: the
            // loop parks again, and close is the escape.
            if (on) pulse.signal()
        }
    }

    /**
     * Ask for the first timing block and wait for it, for the reason the
     * playback side does: without it `pa_stream_get_time` answers NODATA and
     * the position looks frozen on a device that is running.
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
        log.warn("timing info did not arrive within {} ms; the capture position may lag", TIMING_TIMEOUT_NANOS / 1_000_000)
    }

    private fun promoteThisThread() {
        if (promotionAttempted.get()) return
        promotionAttempted.set(true)
        val refusal = RealtimeThreads.promoteCurrentThread()
        if (refusal == null) {
            realtimeGranted = true
            log.info("the reader thread runs at real-time priority")
        } else if (realtimeRefusalLogged.compareAndSet(false, true)) {
            log.info("no real-time priority for the reader thread ({})", refusal)
        }
    }

    private fun applyVolume() {
        val current = stream
        if (current.address() == 0L) return
        val format = openFormat ?: return
        pulse.locked {
            val index = lib.handle("pa_stream_get_index").invokeExact(current) as Int
            Arena.ofConfined().use { call ->
                val cvolume = call.allocate(PulseAbi.CVOLUME_SIZE, 4)
                val level = lib.handle("pa_sw_volume_from_linear")
                    .invokeExact(volumeValue.toDouble()) as Int
                lib.handle("pa_cvolume_set").invokeExact(cvolume, format.channels, level) as MemorySegment
                val op = lib.handle("pa_context_set_source_output_volume").invokeExact(
                    pulse.context, index, cvolume, MemorySegment.NULL, MemorySegment.NULL,
                ) as MemorySegment
                pulse.releaseOperation(op)
            }
        }
    }

    private fun disconnectStream() {
        // Claim the pointer before touching it, so two threads cannot walk away
        // with the same stream and unref it twice.
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
                lib.handle("pa_stream_set_read_callback")
                    .invokeExact(current, MemorySegment.NULL, MemorySegment.NULL) as Unit
                lib.handle("pa_stream_disconnect").invokeExact(current) as Int
                lib.handle("pa_stream_unref").invokeExact(current) as Unit
            }
        }.onFailure { log.warn("capture stream teardown threw: {}", it.message) }
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
        const val TIMING_TIMEOUT_NANOS = 1_000_000_000L
        const val TIMING_POLL_MILLIS = 5L
    }
}
