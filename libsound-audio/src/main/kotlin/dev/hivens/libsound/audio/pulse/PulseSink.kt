package dev.hivens.libsound.audio.pulse

import dev.hivens.libsound.AudioException
import dev.hivens.libsound.AudioFormat
import dev.hivens.libsound.AudioSink
import dev.hivens.libsound.Capabilities
import dev.hivens.libsound.PcmEncoding
import dev.hivens.libsound.SinkConfig
import org.slf4j.LoggerFactory
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.util.concurrent.atomic.AtomicBoolean

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
    override val capabilities: Capabilities,
) : AudioSink {

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

    /** Native scratch for the copy into `pa_stream_write`; reallocated per open. */
    private var scratchArena: Arena? = null
    private var scratch: MemorySegment = MemorySegment.NULL

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

        val targetNanos = config.bufferNanos ?: DEFAULT_BUFFER_NANOS
        val tlength = format.bytesFor(format.framesFor(targetNanos)).toInt()
            .coerceAtLeast(format.bytesPerFrame)

        Arena.ofConfined().use { setup ->
            val spec = setup.allocate(PulseAbi.SAMPLE_SPEC_SIZE, 4)
            spec.set(ValueLayout.JAVA_INT, PulseAbi.SAMPLE_SPEC_FORMAT, encodingOf(format))
            spec.set(ValueLayout.JAVA_INT, PulseAbi.SAMPLE_SPEC_RATE, format.sampleRate)
            spec.set(ValueLayout.JAVA_BYTE, PulseAbi.SAMPLE_SPEC_CHANNELS, format.channels.toByte())

            val attr = setup.allocate(PulseAbi.BUFFER_ATTR_SIZE, 4)
            attr.set(ValueLayout.JAVA_INT, PulseAbi.BUFFER_ATTR_MAXLENGTH, tlength * 2)
            attr.set(ValueLayout.JAVA_INT, PulseAbi.BUFFER_ATTR_TLENGTH, tlength)
            attr.set(ValueLayout.JAVA_INT, PulseAbi.BUFFER_ATTR_PREBUF, PulseAbi.ATTR_DEFAULT)
            attr.set(ValueLayout.JAVA_INT, PulseAbi.BUFFER_ATTR_MINREQ, PulseAbi.ATTR_DEFAULT)
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

                // START_CORKED, then uncork below. Connecting already running
                // would let the server pull from an empty buffer before the
                // first write, which is an underrun on the very first frame.
                val flags = PulseAbi.STREAM_START_CORKED or PulseAbi.STREAM_TIMING_FLAGS
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
        val arena = Arena.ofShared()
        scratchArena?.let { runCatching { it.close() } }
        scratchArena = arena
        scratch = arena.allocate(tlength.toLong(), 8)

        awaitTimingInfo()
        // The contract's first rule: open starts the device.
        cork(false)
        applyVolume()
        log.debug(
            "stream open: {} tlength={} bytes ({} ms)",
            format, tlength, targetNanos / 1_000_000,
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
        scratchArena?.let { runCatching { it.close() } }
        scratchArena = null
        scratch = MemorySegment.NULL
        openFormat = null
    }

    // -- internals -----------------------------------------------------------

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
                lib.handle("pa_stream_disconnect").invokeExact(current) as Int
                lib.handle("pa_stream_unref").invokeExact(current) as Unit
            }
        }.onFailure { log.warn("stream teardown threw: {}", it.message) }
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
         * 200 ms unless the caller says otherwise. libpulse can go lower than
         * JavaSound's measured floor, but the number that survives load has not
         * been measured yet, and guessing it in the wrong direction buys an
         * underrun -- which freezes a clock exactly like the stall it replaces.
         */
        const val DEFAULT_BUFFER_NANOS = 200_000_000L

        const val TIMING_TIMEOUT_NANOS = 1_000_000_000L
        const val TIMING_POLL_MILLIS = 5L
    }
}
