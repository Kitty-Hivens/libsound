package dev.hivens.libsound.audio.coreaudio

import dev.hivens.libsound.AudioException
import dev.hivens.libsound.AudioFormat
import dev.hivens.libsound.AudioSink
import dev.hivens.libsound.Capabilities
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
 * An [AudioSink] over a CoreAudio output unit.
 *
 * CoreAudio pulls and the sink contract pushes, so a [PcmRingBuffer] sits
 * between them -- the shape the problem already has rather than an adapter added
 * for symmetry. [write] parks until the render callback has taken the bytes,
 * which is the same pacing the other backends give and is what lets a producer's
 * write loop double as its clock.
 *
 * ## What macOS does not have
 *
 * No per-application volume in any public API. [setVolume] drives the output
 * unit's own gain, which is applied to our samples inside the unit: audible, and
 * invisible to everything in the OS. So this backend does not claim
 * `STREAM_VOLUME`, and a settings screen that asks first will not draw a slider
 * promising the system mixer will follow.
 *
 * ## The render callback runs on a real-time thread
 *
 * It takes the ring's lock and copies, and does nothing else -- no allocation
 * beyond what reinterpreting a native pointer costs, no logging, no native call.
 * A garbage collection pause can still land on it and be heard, which is true of
 * every JVM audio path including JavaSound's; the honest mitigation is the
 * buffer depth below, not a claim that it cannot happen.
 */
internal class CoreAudioSink(
    private val lib: CoreAudioLibrary,
    private val config: SinkConfig,
    override val capabilities: Capabilities,
    private val deviceIdOf: (String) -> Int?,
) : AudioSink {

    private val log = LoggerFactory.getLogger("libsound.CoreAudio")

    private val closed = AtomicBoolean(false)

    /** Guards the unit pointer swap, so a dispose can happen exactly once. */
    private val unitLock = Any()

    @Volatile
    private var unit: MemorySegment = MemorySegment.NULL

    @Volatile
    private var openFormat: AudioFormat? = null

    @Volatile
    private var running = false

    @Volatile
    private var volumeValue = 1f

    /**
     * Frames of real audio the callback has handed to the device.
     *
     * Counted rather than read off a clock: `AudioTimeStamp.mSampleTime` is the
     * device's continuous sample time, which keeps advancing through an underrun
     * on samples this stream never provided. The same trap libpulse's
     * `pa_stream_get_time` sets, and the same answer -- count what was actually
     * consumed, so a starved stream stalls a consumer's clock instead of running
     * it away.
     */
    private val framesRendered = AtomicLong(0)

    /** Read by the render callback; replaced wholesale on each open. */
    @Volatile
    private var ring: PcmRingBuffer? = null

    /** Pre-allocated so the callback never allocates one. Sized at open. */
    @Volatile
    private var scratch: ByteArray = ByteArray(0)

    @Volatile
    private var frameBytes = 0

    /**
     * Holds the upcall stub, and outlives every callback by construction: it is
     * closed only after the unit has been disposed, which is the point past
     * which no callback can still be in flight. Getting that order wrong is the
     * one mistake here that corrupts memory rather than merely failing.
     */
    private val stubArena: Arena = Arena.ofShared()

    private val renderStub: MemorySegment by lazy {
        Linker.nativeLinker().upcallStub(
            MethodHandles.lookup().findVirtual(
                CoreAudioSink::class.java, "onRender",
                MethodType.methodType(
                    Int::class.javaPrimitiveType,
                    MemorySegment::class.java, MemorySegment::class.java, MemorySegment::class.java,
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, MemorySegment::class.java,
                ),
            ).bindTo(this),
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
            ),
            stubArena,
        )
    }

    override val format: AudioFormat? get() = openFormat

    override val isOpen: Boolean get() = unit.address() != 0L && !closed.get()

    override fun open(format: AudioFormat) {
        if (closed.get()) throw AudioException("sink is closed")
        require(format.encoding == PcmEncoding.S16LE || format.encoding == PcmEncoding.F32LE) {
            "unsupported encoding ${format.encoding}"
        }
        disposeUnit()
        // The old ring goes with the old unit. Nothing drains it any more, so a
        // producer parked on it would stay parked through a reopen that looked
        // to everyone else like a fresh start.
        ring?.close()

        frameBytes = format.bytesPerFrame
        val depthFrames = format.framesFor(config.bufferNanos ?: DEFAULT_BUFFER_NANOS)
            .coerceAtLeast(MAX_FRAMES_PER_SLICE.toLong())
        ring = PcmRingBuffer((depthFrames * frameBytes).toInt(), frameBytes)
        scratch = ByteArray(MAX_FRAMES_PER_SLICE * frameBytes)
        framesRendered.set(0)

        // 'ahal' only where a device was named: 'def ' follows the system
        // default and keeps following it when the default moves, which is what
        // a null device in the config asks for.
        val pinned = config.device?.let { deviceIdOf(it.value) }
        if (config.device != null && pinned == null) {
            throw AudioException("no such output device: ${config.device?.value}")
        }

        val fresh = Arena.ofConfined().use { setup ->
            val description = setup.allocate(CoreAudioAbi.COMPONENT_SIZE, 4)
            description.set(ValueLayout.JAVA_INT, CoreAudioAbi.COMPONENT_TYPE, CoreAudioAbi.UNIT_TYPE_OUTPUT)
            description.set(
                ValueLayout.JAVA_INT, CoreAudioAbi.COMPONENT_SUBTYPE,
                if (pinned != null) CoreAudioAbi.UNIT_SUBTYPE_HAL_OUTPUT else CoreAudioAbi.UNIT_SUBTYPE_DEFAULT_OUTPUT,
            )
            description.set(
                ValueLayout.JAVA_INT, CoreAudioAbi.COMPONENT_MANUFACTURER,
                CoreAudioAbi.UNIT_MANUFACTURER_APPLE,
            )
            description.set(ValueLayout.JAVA_INT, CoreAudioAbi.COMPONENT_FLAGS, 0)
            description.set(ValueLayout.JAVA_INT, CoreAudioAbi.COMPONENT_FLAGS_MASK, 0)

            val component = lib.handle("AudioComponentFindNext")
                .invokeExact(MemorySegment.NULL, description) as MemorySegment
            if (component.address() == 0L) throw AudioException("no output audio unit on this system")

            val out = setup.allocate(ValueLayout.ADDRESS)
            checkStatus("AudioComponentInstanceNew", lib.handle("AudioComponentInstanceNew").invokeExact(component, out) as Int)
            out.get(ValueLayout.ADDRESS, 0)
        }
        if (fresh.address() == 0L) throw AudioException("AudioComponentInstanceNew returned null")

        try {
            Arena.ofConfined().use { setup ->
                if (pinned != null) {
                    val id = setup.allocate(ValueLayout.JAVA_INT)
                    id.set(ValueLayout.JAVA_INT, 0, pinned)
                    checkStatus(
                        "set CurrentDevice",
                        lib.handle("AudioUnitSetProperty").invokeExact(
                            fresh, CoreAudioAbi.OUTPUT_UNIT_PROPERTY_CURRENT_DEVICE,
                            CoreAudioAbi.SCOPE_GLOBAL, CoreAudioAbi.ELEMENT_OUTPUT, id, 4,
                        ) as Int,
                    )
                }

                val asbd = setup.allocate(CoreAudioAbi.ASBD_SIZE, 8)
                writeStreamFormat(asbd, format)
                // The *input* scope of an output unit is what we feed; its
                // output scope is the device. Setting the format on the wrong
                // one is accepted and then plays nothing.
                checkStatus(
                    "set StreamFormat",
                    lib.handle("AudioUnitSetProperty").invokeExact(
                        fresh, CoreAudioAbi.UNIT_PROPERTY_STREAM_FORMAT,
                        CoreAudioAbi.SCOPE_INPUT, CoreAudioAbi.ELEMENT_OUTPUT,
                        asbd, CoreAudioAbi.ASBD_SIZE.toInt(),
                    ) as Int,
                )

                // Pinned rather than left to the unit, because the scratch the
                // callback copies through is sized against it and a slice larger
                // than the scratch would be a truncated period.
                val slice = setup.allocate(ValueLayout.JAVA_INT)
                slice.set(ValueLayout.JAVA_INT, 0, MAX_FRAMES_PER_SLICE)
                checkStatus(
                    "set MaximumFramesPerSlice",
                    lib.handle("AudioUnitSetProperty").invokeExact(
                        fresh, CoreAudioAbi.UNIT_PROPERTY_MAXIMUM_FRAMES_PER_SLICE,
                        CoreAudioAbi.SCOPE_GLOBAL, CoreAudioAbi.ELEMENT_OUTPUT, slice, 4,
                    ) as Int,
                )

                val callback = setup.allocate(CoreAudioAbi.RENDER_CALLBACK_SIZE, 8)
                callback.set(ValueLayout.ADDRESS, CoreAudioAbi.RENDER_CALLBACK_PROC, renderStub)
                callback.set(ValueLayout.ADDRESS, CoreAudioAbi.RENDER_CALLBACK_REFCON, MemorySegment.NULL)
                checkStatus(
                    "set RenderCallback",
                    lib.handle("AudioUnitSetProperty").invokeExact(
                        fresh, CoreAudioAbi.UNIT_PROPERTY_SET_RENDER_CALLBACK,
                        CoreAudioAbi.SCOPE_INPUT, CoreAudioAbi.ELEMENT_OUTPUT,
                        callback, CoreAudioAbi.RENDER_CALLBACK_SIZE.toInt(),
                    ) as Int,
                )
            }
            checkStatus("AudioUnitInitialize", lib.handle("AudioUnitInitialize").invokeExact(fresh) as Int)
        } catch (e: Throwable) {
            runCatching { lib.handle("AudioComponentInstanceDispose").invokeExact(fresh) as Int }
            throw e
        }

        synchronized(unitLock) { unit = fresh }
        openFormat = format
        applyVolume()
        // The contract's first rule: open starts the device.
        start()
        log.debug("output unit open: {} ring={} frames", format, depthFrames)
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
        if (!current.writeFully(data, offset, length)) {
            throw AudioException("sink closed while writing")
        }
    }

    override fun start() {
        val current = unit
        if (current.address() == 0L || running) return
        checkStatus("AudioOutputUnitStart", lib.handle("AudioOutputUnitStart").invokeExact(current) as Int)
        running = true
    }

    override fun stop() {
        val current = unit
        if (current.address() == 0L || !running) return
        checkStatus("AudioOutputUnitStop", lib.handle("AudioOutputUnitStop").invokeExact(current) as Int)
        running = false
    }

    override fun flush() {
        // Valid while stopped, and never credits what it drops: only bytes the
        // callback actually read are counted, and these were never read.
        ring?.clear()
    }

    override fun framePosition(): Long = framesRendered.get()

    /**
     * What is still queued ahead of the speaker, and deliberately not the
     * device's own propagation delay.
     *
     * `kAudioUnitProperty_Latency` would add a few more milliseconds and would
     * be the wrong answer to the question the interface asks: how far ahead the
     * write head is, which is a fill level. A fixed device delay does not move
     * when a flush empties the queue, so including it would report a backlog
     * that no longer exists.
     */
    override fun latencyNanos(): Long {
        val format = openFormat ?: return 0L
        val buffered = ring?.available() ?: return 0L
        return format.nanosFor((buffered / format.bytesPerFrame).toLong())
    }

    override fun setVolume(volume: Float) {
        volumeValue = volume.coerceIn(0f, 1f)
        applyVolume()
    }

    override fun volume(): Float = volumeValue

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Free a producer parked in write() first: the thread that could rescue
        // it is never the producer itself.
        ring?.close()
        disposeUnit()
        openFormat = null
        renderFailure?.let { log.warn("the render callback failed at least once: {}", it) }
        // Only here. The unit is disposed, so no render callback can be in
        // flight and nothing native still holds a pointer into this arena.
        runCatching { stubArena.close() }
    }

    // -- the render callback, on a real-time thread ---------------------------

    // Public rather than internal although nothing outside calls it: Kotlin
    // mangles an internal function's name and findVirtual looks up what is
    // written, so the mangled lookup fails and the sink reports no audio unit on
    // a machine that has one.

    fun onRender(
        unusedRefCon: MemorySegment,
        actionFlags: MemorySegment,
        unusedTimeStamp: MemorySegment,
        unusedBus: Int,
        frames: Int,
        ioData: MemorySegment,
    ): Int {
        try {
            val buffers = ioData.reinterpret(CoreAudioAbi.BUFFER_LIST_BUFFERS)
                .get(ValueLayout.JAVA_INT, CoreAudioAbi.BUFFER_LIST_NUMBER_BUFFERS)
            if (buffers < 1) return CoreAudioAbi.NO_ERROR
            val list = ioData.reinterpret(
                CoreAudioAbi.BUFFER_LIST_BUFFERS + CoreAudioAbi.BUFFER_SIZE * buffers,
            )

            // The format asked for is interleaved, so one buffer is what comes
            // back. Anything else is a format we did not request; silencing the
            // extras is the only response that is not noise.
            for (index in 1 until buffers) {
                zero(list, index)
            }

            val buffer = list.asSlice(CoreAudioAbi.BUFFER_LIST_BUFFERS, CoreAudioAbi.BUFFER_SIZE)
            val capacity = buffer.get(ValueLayout.JAVA_INT, CoreAudioAbi.BUFFER_DATA_BYTE_SIZE)
            val data = buffer.get(ValueLayout.ADDRESS, CoreAudioAbi.BUFFER_DATA)
            if (data.address() == 0L || capacity <= 0) return CoreAudioAbi.NO_ERROR

            val bytesPerFrame = frameBytes
            val source = ring
            val wanted = minOf(capacity, frames * bytesPerFrame, scratch.size)
                .let { it - it % bytesPerFrame.coerceAtLeast(1) }
            if (source == null || bytesPerFrame <= 0 || wanted <= 0) {
                zero(list, 0)
                markSilence(actionFlags)
                return CoreAudioAbi.NO_ERROR
            }

            val real = source.read(scratch, 0, wanted)
            MemorySegment.copy(
                scratch, 0, data.reinterpret(capacity.toLong()), ValueLayout.JAVA_BYTE, 0L, wanted,
            )
            if (wanted < capacity) {
                data.reinterpret(capacity.toLong()).asSlice(wanted.toLong()).fill(0)
            }
            if (real > 0) framesRendered.addAndGet((real / bytesPerFrame).toLong()) else markSilence(actionFlags)
        } catch (e: Throwable) {
            // A throw crossing an upcall boundary is undefined; nothing here is
            // worth risking that for, and a period of silence is survivable.
            runCatching { zero(ioData.reinterpret(CoreAudioAbi.BUFFER_LIST_BUFFERS + CoreAudioAbi.BUFFER_SIZE), 0) }
            renderFailure = e.message
        }
        return CoreAudioAbi.NO_ERROR
    }

    /**
     * Why the callback last went quiet, reported from [close] rather than from
     * the callback itself.
     *
     * Logging on a real-time thread allocates and may take a lock held by
     * whatever the logging backend is doing, which turns one bad period into
     * several. Recording a string and printing it where blocking is allowed
     * costs a volatile write and loses nothing.
     */
    @Volatile
    private var renderFailure: String? = null

    private fun zero(list: MemorySegment, index: Int) {
        val buffer = list.asSlice(
            CoreAudioAbi.BUFFER_LIST_BUFFERS + CoreAudioAbi.BUFFER_SIZE * index,
            CoreAudioAbi.BUFFER_SIZE,
        )
        val size = buffer.get(ValueLayout.JAVA_INT, CoreAudioAbi.BUFFER_DATA_BYTE_SIZE)
        val data = buffer.get(ValueLayout.ADDRESS, CoreAudioAbi.BUFFER_DATA)
        if (data.address() != 0L && size > 0) data.reinterpret(size.toLong()).fill(0)
    }

    private fun markSilence(actionFlags: MemorySegment) {
        if (actionFlags.address() == 0L) return
        val flags = actionFlags.reinterpret(4)
        flags.set(
            ValueLayout.JAVA_INT, 0,
            flags.get(ValueLayout.JAVA_INT, 0) or CoreAudioAbi.RENDER_ACTION_OUTPUT_IS_SILENCE,
        )
    }

    // -- internals ------------------------------------------------------------

    private fun writeStreamFormat(asbd: MemorySegment, format: AudioFormat) {
        val bitsPerChannel = format.encoding.bytesPerSample * 8
        val flags = CoreAudioAbi.FORMAT_FLAGS_NATIVE_ENDIAN or CoreAudioAbi.FORMAT_FLAG_IS_PACKED or
            when (format.encoding) {
                PcmEncoding.F32LE -> CoreAudioAbi.FORMAT_FLAG_IS_FLOAT
                PcmEncoding.S16LE -> CoreAudioAbi.FORMAT_FLAG_IS_SIGNED_INTEGER
            }
        asbd.set(ValueLayout.JAVA_DOUBLE, CoreAudioAbi.ASBD_SAMPLE_RATE, format.sampleRate.toDouble())
        asbd.set(ValueLayout.JAVA_INT, CoreAudioAbi.ASBD_FORMAT_ID, CoreAudioAbi.FORMAT_LINEAR_PCM)
        asbd.set(ValueLayout.JAVA_INT, CoreAudioAbi.ASBD_FORMAT_FLAGS, flags)
        asbd.set(ValueLayout.JAVA_INT, CoreAudioAbi.ASBD_BYTES_PER_PACKET, format.bytesPerFrame)
        asbd.set(ValueLayout.JAVA_INT, CoreAudioAbi.ASBD_FRAMES_PER_PACKET, 1)
        asbd.set(ValueLayout.JAVA_INT, CoreAudioAbi.ASBD_BYTES_PER_FRAME, format.bytesPerFrame)
        asbd.set(ValueLayout.JAVA_INT, CoreAudioAbi.ASBD_CHANNELS_PER_FRAME, format.channels)
        asbd.set(ValueLayout.JAVA_INT, CoreAudioAbi.ASBD_BITS_PER_CHANNEL, bitsPerChannel)
    }

    private fun applyVolume() {
        val current = unit
        if (current.address() == 0L) return
        val rc = lib.handle("AudioUnitSetParameter").invokeExact(
            current, CoreAudioAbi.HAL_PARAM_VOLUME,
            CoreAudioAbi.SCOPE_GLOBAL, CoreAudioAbi.ELEMENT_OUTPUT, volumeValue, 0,
        ) as Int
        // Best effort by contract: a unit that refuses its own gain parameter is
        // not a reason to fail a call the caller cannot do anything about.
        if (rc != CoreAudioAbi.NO_ERROR) log.debug("output unit refused volume: {}", rc)
    }

    private fun disposeUnit() {
        val current = synchronized(unitLock) {
            val held = unit
            unit = MemorySegment.NULL
            held
        }
        running = false
        if (current.address() == 0L) return
        runCatching {
            lib.handle("AudioOutputUnitStop").invokeExact(current) as Int
            lib.handle("AudioUnitUninitialize").invokeExact(current) as Int
            lib.handle("AudioComponentInstanceDispose").invokeExact(current) as Int
        }.onFailure { log.warn("output unit teardown threw: {}", it.message) }
    }

    private fun checkStatus(what: String, status: Int) {
        if (status != CoreAudioAbi.NO_ERROR) throw AudioException("$what failed: ${statusText(status)}")
    }

    private companion object {
        /**
         * 200 ms unless the caller says otherwise, matching the libpulse
         * backend. Lower is reachable here, but the depth that survives a
         * garbage collection has not been measured, and guessing it downwards
         * buys an underrun -- which stalls a clock exactly like the latency it
         * was meant to save.
         */
        const val DEFAULT_BUFFER_NANOS = 200_000_000L

        /**
         * Pinned on the unit, and the size of the scratch the callback copies
         * through. Generous: a period this long is far past anything a device
         * asks for, and the cost of the headroom is one array per open.
         */
        const val MAX_FRAMES_PER_SLICE = 4_096

        /**
         * OSStatus is often a four-character code, so the number alone says
         * nothing. Printing both is the difference between a log line that
         * identifies the failure and one that has to be looked up.
         */
        fun statusText(status: Int): String {
            val chars = CharArray(4) { index -> ((status shr (24 - index * 8)) and 0xFF).toChar() }
            val printable = chars.all { it.code in 32..126 }
            return if (printable) "$status ('${String(chars)}')" else status.toString()
        }
    }
}
