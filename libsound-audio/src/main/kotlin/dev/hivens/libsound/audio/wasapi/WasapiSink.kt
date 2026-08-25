package dev.hivens.libsound.audio.wasapi

import dev.hivens.libsound.AudioException
import dev.hivens.libsound.AudioFormat
import dev.hivens.libsound.AudioSink
import dev.hivens.libsound.Capabilities
import dev.hivens.libsound.Capability
import dev.hivens.libsound.PcmEncoding
import dev.hivens.libsound.SinkConfig
import org.slf4j.LoggerFactory
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.util.concurrent.atomic.AtomicBoolean

/**
 * An [AudioSink] over WASAPI in shared mode.
 *
 * Better than the fallback in every dimension that matters here: `IAudioClock`
 * is a sample-accurate playhead, `ISimpleAudioVolume` is the per-application
 * volume the Windows mixer shows and follows, and `IAudioSessionControl` gives
 * the session the name a user sees instead of the JVM's.
 *
 * ## Two traps, both pinned in [WasapiAbi]
 *
 * `REFERENCE_TIME` counts hundreds of nanoseconds, so every duration here is a
 * factor of ten away from the microseconds it resembles. And without
 * `AUTOCONVERTPCM` the engine refuses any format but its own mix format, which
 * would leave this library owing the resampling it says it does not do.
 *
 * ## The reset that rewinds
 *
 * `IAudioClient::Reset` empties the buffer *and* restarts the audio clock at
 * zero. Taken literally that makes a flush rewind the playhead to the start of
 * the track -- the exact move a mastered clock cannot absorb, and the mirror of
 * JavaSound's flush, which jumps the other way. So the position reported here
 * is the clock's reading plus everything accumulated before the last reset. The
 * two backends need opposite corrections for the same reason: the contract
 * counts frames played since `open`, and neither platform's own counter does.
 */
internal class WasapiSink(
    private val com: WasapiCom,
    private val enumerator: MemorySegment,
    private val config: SinkConfig,
    private val baseCapabilities: Capabilities,
) : AudioSink {

    private val log = LoggerFactory.getLogger("libsound.Wasapi")

    private val closed = AtomicBoolean(false)

    /**
     * Held whenever a pointer below is dereferenced, and not merely while one
     * is assigned.
     *
     * The contract requires [close] to break a [write] that is in flight, so
     * the two run concurrently by design. Reading a pointer into a local and
     * calling through it afterwards is safe in the JavaSound sink, where the
     * local keeps a Java object alive; here the local is an address and
     * `Release` is what decides whether the object behind it still exists.
     *
     * That is not a theoretical difference. Closing against four writers over
     * five hundred opens, on a Windows JVM under wine, took the process down
     * with `EXCEPTION_ACCESS_VIOLATION` inside `write` -- on the copy into the
     * buffer `IAudioRenderClient::GetBuffer` had handed back, from a render
     * client released underneath it. The same run against the code below
     * survives every round.
     */
    private val interfaceLock = Any()

    // Every one of these is a COM interface pointer, released in reverse order.
    private var device = MemorySegment.NULL
    private var client = MemorySegment.NULL
    private var render = MemorySegment.NULL
    private var clock = MemorySegment.NULL
    private var simpleVolume = MemorySegment.NULL
    private var sessionControl = MemorySegment.NULL

    /**
     * What this sink turned out to be able to do, not what the backend hoped.
     *
     * The volume and session interfaces are asked for when the stream opens and
     * an endpoint may refuse either. Reporting them from a constant meant a sink
     * that never got `ISimpleAudioVolume` still claimed STREAM_VOLUME, and
     * setVolume then returned quietly while volume() echoed the value back --
     * the discovered-by-failing case this enum exists to prevent.
     *
     * Recomputed on each open, because an endpoint that refused once need not
     * refuse the next one.
     */
    @Volatile
    private var openCapabilities: Capabilities = baseCapabilities

    override val capabilities: Capabilities get() = openCapabilities

    @Volatile
    private var openFormat: AudioFormat? = null

    @Volatile
    private var bufferFrames = 0

    /** Ticks per second of the device clock; frames are derived through it. */
    @Volatile
    private var clockFrequency = 0L

    /**
     * Frames played before the most recent [flush].
     *
     * `Reset` restarts the device clock, so without this every seek would hand
     * a consumer a playhead at zero and re-anchor its clock to the start of the
     * track.
     */
    @Volatile
    private var positionBase = 0L

    @Volatile
    private var volumeValue = 1f

    @Volatile
    private var running = false

    override val format: AudioFormat? get() = openFormat

    override val isOpen: Boolean get() = client.address() != 0L && !closed.get()

    override fun open(format: AudioFormat) {
        if (closed.get()) throw AudioException("sink is closed")
        require(format.encoding == PcmEncoding.S16LE) {
            "WASAPI backend takes S16LE only, was ${format.encoding}"
        }
        com.ensureComOnThisThread()
        releaseInterfaces()
        positionBase = 0

        Arena.ofConfined().use { call ->
            val fresh = activateDevice(call)
            val audioClient = activateClient(call, fresh)
            initialiseClient(call, audioClient, format)

            val services = ServicePointers(
                render = service(call, audioClient, WasapiAbi.IID_AUDIO_RENDER_CLIENT, "IAudioRenderClient"),
                clock = service(call, audioClient, WasapiAbi.IID_AUDIO_CLOCK, "IAudioClock"),
                // The last two are conveniences, not requirements: a session
                // without a volume interface still plays, it just cannot be
                // moved from the mixer. Their absence is reported through the
                // capability set, never by refusing to open.
                volume = serviceOrNull(call, audioClient, WasapiAbi.IID_SIMPLE_AUDIO_VOLUME),
                session = serviceOrNull(call, audioClient, WasapiAbi.IID_AUDIO_SESSION_CONTROL),
            )

            synchronized(interfaceLock) {
                device = fresh
                client = audioClient
                render = services.render
                clock = services.clock
                simpleVolume = services.volume ?: MemorySegment.NULL
                sessionControl = services.session ?: MemorySegment.NULL
                openCapabilities = Capabilities(
                    buildSet {
                        addAll(baseCapabilities.supported)
                        if (services.volume == null) remove(Capability.STREAM_VOLUME)
                        if (services.session == null) remove(Capability.STREAM_IDENTITY)
                    },
                )
                bufferFrames = readBufferSize(call, audioClient)
                clockFrequency = readClockFrequency(call, services.clock)
                openFormat = format

                // Inside the same section that published the pointers: naming
                // the session and starting the device dereference them, and a
                // close arriving between the publish and the start would have
                // released them out from under both.
                nameTheSession(call)
                applyVolumeLocked()
                // The contract's first rule: open starts the device.
                hr(callClient(WasapiAbi.CLIENT_START), "IAudioClient::Start")
                running = true
            }
        }
        log.debug("stream open: {} buffer={} frames, clock={} Hz", format, bufferFrames, clockFrequency)
    }

    override fun write(data: ByteArray, offset: Int, length: Int) {
        val format = openFormat ?: throw AudioException("write before open")
        require(offset >= 0 && length >= 0 && offset + length <= data.size) {
            "range $offset..${offset + length} outside array of ${data.size}"
        }
        require(length % format.bytesPerFrame == 0) {
            "length ($length) must be a whole number of frames (${format.bytesPerFrame})"
        }
        com.ensureComOnThisThread()

        var writtenFrames = 0L
        val totalFrames = format.framesIn(length.toLong())
        while (writtenFrames < totalFrames) {
            if (closed.get()) throw AudioException("sink closed while writing")
            var waitMillis = 0L
            // The COM work runs under the lock; the wait below does not. A
            // close is then delayed by one buffer round trip at most, and can
            // never land between reading a pointer and calling through it.
            synchronized(interfaceLock) {
                val renderClient = render
                val audioClient = client
                if (renderClient.address() == 0L || audioClient.address() == 0L) {
                    throw AudioException("write on a disconnected stream")
                }
                Arena.ofConfined().use { call ->
                    val padding = readPadding(call, audioClient)
                    val free = (bufferFrames - padding).coerceAtLeast(0)
                    if (free == 0) {
                        waitMillis = pollIntervalMillis(format)
                        return@use
                    }
                    val chunkFrames = minOf(free.toLong(), totalFrames - writtenFrames)
                    val buffer = getBuffer(call, renderClient, chunkFrames.toInt())
                    val bytes = (chunkFrames * format.bytesPerFrame).toInt()
                    MemorySegment.copy(
                        data, (offset + writtenFrames * format.bytesPerFrame).toInt(),
                        buffer.reinterpret(bytes.toLong()), ValueLayout.JAVA_BYTE, 0L, bytes,
                    )
                    releaseBuffer(renderClient, chunkFrames.toInt())
                    writtenFrames += chunkFrames
                }
            }
            // The pacing. WASAPI has no blocking write, so the wait is ours to
            // build: sleep a fraction of the buffer rather than spin, because a
            // busy loop here is a core burnt per stream. Outside the lock, so a
            // close does not queue behind it.
            if (waitMillis > 0) Thread.sleep(waitMillis)
        }
    }

    override fun start() {
        com.ensureComOnThisThread()
        synchronized(interfaceLock) {
            if (running || client.address() == 0L) return
            hr(callClient(WasapiAbi.CLIENT_START), "IAudioClient::Start")
            running = true
        }
    }

    override fun stop() {
        com.ensureComOnThisThread()
        synchronized(interfaceLock) {
            if (!running || client.address() == 0L) return
            hr(callClient(WasapiAbi.CLIENT_STOP), "IAudioClient::Stop")
            running = false
        }
    }

    override fun flush() {
        com.ensureComOnThisThread()
        synchronized(interfaceLock) { flushLocked() }
    }

    /** Caller holds [interfaceLock]. */
    private fun flushLocked() {
        val audioClient = client
        if (audioClient.address() == 0L) return
        val wasRunning = running
        // Reset refuses a running client, so the stop is part of the operation
        // rather than something the caller has to remember.
        if (wasRunning) {
            hr(callClient(WasapiAbi.CLIENT_STOP), "IAudioClient::Stop")
            running = false
        }
        // Carry the playhead across the reset that is about to zero it.
        positionBase = framePosition()
        hr(callClient(WasapiAbi.CLIENT_RESET), "IAudioClient::Reset")
        if (wasRunning) {
            hr(callClient(WasapiAbi.CLIENT_START), "IAudioClient::Start")
            running = true
        }
    }

    override fun framePosition(): Long = synchronized(interfaceLock) {
        val audioClock = clock
        val format = openFormat ?: return 0L
        if (audioClock.address() == 0L || clockFrequency == 0L) return positionBase
        return Arena.ofConfined().use { call ->
            val out = call.allocate(ValueLayout.JAVA_LONG)
            val qpc = call.allocate(ValueLayout.JAVA_LONG)
            val result = com.method(
                audioClock, WasapiAbi.CLOCK_GET_POSITION,
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                ),
            ).invokeExact(audioClock, out, qpc) as Int
            if (result != WasapiAbi.S_OK) return@use positionBase
            val ticks = out.get(ValueLayout.JAVA_LONG, 0)
            // Split so the multiplication cannot overflow: ticks are counted at
            // the device's own frequency and a long stream puts ticks * rate
            // past Long well before the position itself gets large.
            val whole = ticks / clockFrequency
            val remainder = ticks % clockFrequency
            positionBase + whole * format.sampleRate + remainder * format.sampleRate / clockFrequency
        }
    }

    override fun latencyNanos(): Long = synchronized(interfaceLock) {
        val audioClient = client
        val format = openFormat ?: return 0L
        if (audioClient.address() == 0L) return 0L
        return Arena.ofConfined().use { call ->
            // What is queued, not what the engine adds: the contract asks how
            // far ahead of the speaker the write head is, and the padding is
            // exactly that.
            val padding = readPadding(call, audioClient)
            format.nanosFor(padding.toLong())
        }
    }

    override fun setVolume(volume: Float) {
        volumeValue = volume.coerceIn(0f, 1f)
        applyVolume()
    }

    override fun volume(): Float = volumeValue

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching {
            com.ensureComOnThisThread()
            synchronized(interfaceLock) {
                if (running && client.address() != 0L) callClient(WasapiAbi.CLIENT_STOP)
            }
        }
        running = false
        releaseInterfaces()
        openFormat = null
    }

    // -- open, step by step --------------------------------------------------

    private fun activateDevice(call: Arena): MemorySegment {
        val requested = config.device
        val out = call.allocate(ValueLayout.ADDRESS)
        val result = if (requested == null) {
            com.method(
                enumerator, WasapiAbi.GET_DEFAULT_AUDIO_ENDPOINT,
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                ),
            ).invokeExact(enumerator, WasapiAbi.E_RENDER, WasapiAbi.E_CONSOLE, out) as Int
        } else {
            com.method(
                enumerator, WasapiAbi.GET_DEVICE,
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                ),
            ).invokeExact(enumerator, WasapiCom.wide(call, requested.value), out) as Int
        }
        hr(result, "IMMDeviceEnumerator::GetDevice")
        return out.get(ValueLayout.ADDRESS, 0)
    }

    private fun activateClient(call: Arena, target: MemorySegment): MemorySegment {
        val out = call.allocate(ValueLayout.ADDRESS)
        val result = com.method(
            target, WasapiAbi.DEVICE_ACTIVATE,
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
            ),
        ).invokeExact(
            target, WasapiCom.guid(call, WasapiAbi.IID_AUDIO_CLIENT),
            WasapiAbi.CLSCTX_ALL, MemorySegment.NULL, out,
        ) as Int
        hr(result, "IMMDevice::Activate(IAudioClient)")
        return out.get(ValueLayout.ADDRESS, 0)
    }

    private fun initialiseClient(call: Arena, audioClient: MemorySegment, format: AudioFormat) {
        val wfx = call.allocate(WasapiAbi.WFX_SIZE, 2)
        val blockAlign = format.bytesPerFrame
        wfx.set(ValueLayout.JAVA_SHORT, WasapiAbi.WFX_FORMAT_TAG, WasapiAbi.WAVE_FORMAT_PCM.toShort())
        wfx.set(ValueLayout.JAVA_SHORT, WasapiAbi.WFX_CHANNELS, format.channels.toShort())
        wfx.set(ValueLayout.JAVA_INT, WasapiAbi.WFX_SAMPLES_PER_SEC, format.sampleRate)
        wfx.set(ValueLayout.JAVA_INT, WasapiAbi.WFX_AVG_BYTES_PER_SEC, format.sampleRate * blockAlign)
        wfx.set(ValueLayout.JAVA_SHORT, WasapiAbi.WFX_BLOCK_ALIGN, blockAlign.toShort())
        wfx.set(
            ValueLayout.JAVA_SHORT, WasapiAbi.WFX_BITS_PER_SAMPLE,
            (format.encoding.bytesPerSample * 8).toShort(),
        )
        wfx.set(ValueLayout.JAVA_SHORT, WasapiAbi.WFX_CB_SIZE, 0)

        val bufferNanos = config.bufferNanos ?: DEFAULT_BUFFER_NANOS
        val duration = bufferNanos / WasapiAbi.NANOS_PER_REFTIME

        val flags = WasapiAbi.STREAMFLAGS_AUTOCONVERTPCM or
            WasapiAbi.STREAMFLAGS_SRC_DEFAULT_QUALITY or
            WasapiAbi.STREAMFLAGS_NOPERSIST
        val result = com.method(
            audioClient, WasapiAbi.CLIENT_INITIALIZE,
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
            ),
        ).invokeExact(
            audioClient, WasapiAbi.SHAREMODE_SHARED, flags,
            duration, 0L, wfx, MemorySegment.NULL,
        ) as Int
        if (result == WasapiAbi.AUDCLNT_E_UNSUPPORTED_FORMAT) {
            throw AudioException("the audio engine refused $format even with AUTOCONVERTPCM")
        }
        hr(result, "IAudioClient::Initialize")
    }

    private class ServicePointers(
        val render: MemorySegment,
        val clock: MemorySegment,
        val volume: MemorySegment?,
        val session: MemorySegment?,
    )

    private fun service(call: Arena, audioClient: MemorySegment, iid: String, what: String): MemorySegment =
        serviceOrNull(call, audioClient, iid) ?: throw AudioException("IAudioClient::GetService($what) failed")

    private fun serviceOrNull(call: Arena, audioClient: MemorySegment, iid: String): MemorySegment? {
        val out = call.allocate(ValueLayout.ADDRESS)
        val result = com.method(
            audioClient, WasapiAbi.CLIENT_GET_SERVICE,
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
            ),
        ).invokeExact(audioClient, WasapiCom.guid(call, iid), out) as Int
        if (result != WasapiAbi.S_OK) return null
        val pointer = out.get(ValueLayout.ADDRESS, 0)
        return if (pointer.address() == 0L) null else pointer
    }

    private fun nameTheSession(call: Arena) {
        val session = sessionControl
        if (session.address() == 0L) return
        runCatching {
            com.method(
                session, WasapiAbi.SESSION_CONTROL_SET_DISPLAY_NAME,
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
            ).invokeExact(session, WasapiCom.wide(call, config.applicationName), MemorySegment.NULL) as Int
        }.onFailure { log.debug("SetDisplayName failed: {}", it.message) }
        val icon = config.iconName ?: return
        runCatching {
            com.method(
                session, WasapiAbi.SESSION_CONTROL_SET_ICON_PATH,
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
            ).invokeExact(session, WasapiCom.wide(call, icon), MemorySegment.NULL) as Int
        }.onFailure { log.debug("SetIconPath failed: {}", it.message) }
    }

    // -- small reads ---------------------------------------------------------

    private fun readBufferSize(call: Arena, audioClient: MemorySegment): Int {
        val out = call.allocate(ValueLayout.JAVA_INT)
        hr(
            com.method(
                audioClient, WasapiAbi.CLIENT_GET_BUFFER_SIZE,
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
            ).invokeExact(audioClient, out) as Int,
            "IAudioClient::GetBufferSize",
        )
        return out.get(ValueLayout.JAVA_INT, 0)
    }

    private fun readPadding(call: Arena, audioClient: MemorySegment): Int {
        val out = call.allocate(ValueLayout.JAVA_INT)
        val result = com.method(
            audioClient, WasapiAbi.CLIENT_GET_CURRENT_PADDING,
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
        ).invokeExact(audioClient, out) as Int
        if (result == WasapiAbi.AUDCLNT_E_DEVICE_INVALIDATED) {
            throw AudioException("the audio device went away mid-stream")
        }
        hr(result, "IAudioClient::GetCurrentPadding")
        return out.get(ValueLayout.JAVA_INT, 0)
    }

    private fun readClockFrequency(call: Arena, audioClock: MemorySegment): Long {
        val out = call.allocate(ValueLayout.JAVA_LONG)
        hr(
            com.method(
                audioClock, WasapiAbi.CLOCK_GET_FREQUENCY,
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
            ).invokeExact(audioClock, out) as Int,
            "IAudioClock::GetFrequency",
        )
        return out.get(ValueLayout.JAVA_LONG, 0)
    }

    private fun getBuffer(call: Arena, renderClient: MemorySegment, frames: Int): MemorySegment {
        val out = call.allocate(ValueLayout.ADDRESS)
        val result = com.method(
            renderClient, WasapiAbi.RENDER_GET_BUFFER,
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
            ),
        ).invokeExact(renderClient, frames, out) as Int
        if (result == WasapiAbi.AUDCLNT_E_DEVICE_INVALIDATED) {
            throw AudioException("the audio device went away mid-stream")
        }
        hr(result, "IAudioRenderClient::GetBuffer")
        return out.get(ValueLayout.ADDRESS, 0)
    }

    private fun releaseBuffer(renderClient: MemorySegment, frames: Int) {
        // Every GetBuffer owes a ReleaseBuffer; skipping one on an error path
        // wedges the engine on this stream until the client is destroyed.
        hr(
            com.method(
                renderClient, WasapiAbi.RENDER_RELEASE_BUFFER,
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
            ).invokeExact(renderClient, frames, 0) as Int,
            "IAudioRenderClient::ReleaseBuffer",
        )
    }

    private fun callClient(slot: Int): Int {
        val audioClient = client
        if (audioClient.address() == 0L) return WasapiAbi.S_OK
        return com.method(
            audioClient, slot,
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
        ).invokeExact(audioClient) as Int
    }

    private fun applyVolume() {
        synchronized(interfaceLock) { applyVolumeLocked() }
    }

    /** Caller holds [interfaceLock]. */
    private fun applyVolumeLocked() {
        val volume = simpleVolume
        if (volume.address() == 0L) return
        runCatching {
            com.method(
                volume, WasapiAbi.VOLUME_SET_MASTER,
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_FLOAT, ValueLayout.ADDRESS),
            ).invokeExact(volume, volumeValue, MemorySegment.NULL) as Int
        }.onFailure { log.debug("SetMasterVolume failed: {}", it.message) }
    }

    private fun releaseInterfaces() {
        synchronized(interfaceLock) {
            val held = listOf(sessionControl, simpleVolume, clock, render, client, device)
            sessionControl = MemorySegment.NULL
            simpleVolume = MemorySegment.NULL
            clock = MemorySegment.NULL
            render = MemorySegment.NULL
            client = MemorySegment.NULL
            device = MemorySegment.NULL
            // Released inside the lock rather than after it. Nulling the fields
            // stops the next caller from finding a pointer; it does nothing for
            // the caller already holding one, and that is the caller a release
            // outside the lock would pull the object out from under.
            //
            // Reverse order of acquisition: the services come from the client,
            // so they go first.
            held.forEach { com.release(it) }
        }
    }

    private fun hr(result: Int, what: String) {
        if (result < 0) throw AudioException("$what failed: 0x${Integer.toHexString(result)}")
    }

    /** A quarter of the buffer, bounded: short enough to refill, long enough not to spin. */
    private fun pollIntervalMillis(format: AudioFormat): Long =
        (format.nanosFor(bufferFrames.toLong()) / 4_000_000L).coerceIn(1L, 20L)

    private companion object {
        const val DEFAULT_BUFFER_NANOS = 200_000_000L
    }
}
