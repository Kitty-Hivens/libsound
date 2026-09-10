package dev.hivens.libsound.audio.javasound

import dev.hivens.libsound.AudioException
import dev.hivens.libsound.AudioFormat
import dev.hivens.libsound.AudioSource
import dev.hivens.libsound.Capabilities
import dev.hivens.libsound.Capability
import dev.hivens.libsound.PcmEncoding
import org.slf4j.LoggerFactory
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.FloatControl
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.TargetDataLine
import kotlin.math.log10
import kotlin.math.max

/**
 * The capture counterpart of [JavaSoundSink]: a `TargetDataLine`, present on
 * every JVM.
 *
 * It exists for the reason the fallback exists at all, which is that something
 * has to work everywhere. What it loses is the same list the output side loses,
 * and for the same reason: JavaSound exposes no stream identity, no system
 * volume and no device selection worth offering, so a desktop cannot say which
 * application has the microphone open and a settings screen must not draw a
 * control that reaches nothing.
 *
 * It also cannot count what it dropped. A `TargetDataLine` reports how much is
 * waiting and never how much went past while nobody was reading, so
 * [overrunFrames] answers zero here, which is not the same as nothing having
 * been lost.
 */
internal class JavaSoundSource(
    private val bufferNanos: Long = DEFAULT_BUFFER_NANOS,
) : AudioSource {

    private val log = LoggerFactory.getLogger("libsound.JavaSound")

    // Volatile and never held under a lock across a blocking call: close() has
    // to reach the line while read() is parked inside it, and a lock shared
    // between the two would make the rescue impossible.
    @Volatile
    private var line: TargetDataLine? = null

    @Volatile
    private var openFormat: AudioFormat? = null

    @Volatile
    private var closed = false

    private var volumeValue = 1f

    override val capabilities: Capabilities = CAPABILITIES

    override val format: AudioFormat? get() = openFormat

    override val isOpen: Boolean get() = line != null && !closed

    /** Asked of the JVM, for the reason [JavaSoundSink.acceptedEncodings] is. */
    override val acceptedEncodings: Set<PcmEncoding> by lazy {
        JavaSoundFormats.acceptedFor(TargetDataLine::class.java, JavaSoundFormats.PROBE)
    }

    /** The same walk [open] makes, so the answer and the behaviour cannot drift. */
    override fun accepts(format: AudioFormat): Boolean = JavaSoundFormats.supportedForCapture(format)

    override fun open(format: AudioFormat) {
        if (closed) throw AudioException("source is closed")
        // AudioException rather than the argument check this used to be. A
        // consumer walks a ladder down from what the media is and catches what
        // the contract promises; an IllegalArgumentException goes straight past
        // it and out of the player.
        val javaFormat = JavaSoundFormats.javaFormatOf(format)
            ?: throw AudioException("JavaSound has no encoding for ${format.encoding}")
        if (!accepts(format)) throw AudioException("no JavaSound capture line takes $format")
        line?.let { old ->
            runCatching { old.stop() }
            runCatching { old.flush() }
            runCatching { old.close() }
        }
        // Cleared before the new line is built, not after: a failure below must
        // not leave isOpen answering true against a closed line.
        line = null
        openFormat = null
        val bufferBytes = (format.bytesFor(format.framesFor(bufferNanos))).toInt()
            .coerceAtLeast(format.bytesPerFrame)
        val fresh = try {
            AudioSystem.getTargetDataLine(javaFormat).apply {
                open(javaFormat, bufferBytes)
                // The contract's first rule: open starts the device. A caller
                // that wants the line open and idle stops immediately after.
                start()
            }
        } catch (e: LineUnavailableException) {
            throw AudioException("no capture line for $format", e)
        } catch (e: IllegalArgumentException) {
            throw AudioException("capture format not supported: $format", e)
        }
        line = fresh
        openFormat = format
        applyVolume()
        log.debug("capture line open: {} with a {} ms buffer", format, bufferNanos / 1_000_000)
    }

    override fun read(dst: ByteArray, offset: Int, length: Int) {
        val format = openFormat ?: throw AudioException("read before open")
        require(offset >= 0 && length >= 0 && offset + length <= dst.size) {
            "range $offset..${offset + length} outside array of ${dst.size}"
        }
        require(length % format.bytesPerFrame == 0) {
            "length ($length) must be a whole number of frames (${format.bytesPerFrame})"
        }
        // Read the field once, for the reason the sink does: close() can null it
        // while this is parked inside the line, and the local reference is what
        // keeps the object alive until the JDK returns from a closed line.
        val current = line ?: throw AudioException("read on a closed source")
        var filled = 0
        while (filled < length) {
            val got = current.read(dst, offset + filled, length - filled)
            if (got <= 0) {
                if (closed || line == null) throw AudioException("source closed while reading")
                // A stopped or flushed line hands back nothing until it runs
                // again. Waiting costs something on purpose: spinning here burns
                // a core for as long as the line stays stopped.
                Thread.sleep(STALLED_POLL_MILLIS)
                continue
            }
            filled += got
            if (filled < length && (closed || line == null)) {
                throw AudioException("source closed while reading")
            }
        }
    }

    override fun start() {
        line?.start()
    }

    override fun stop() {
        line?.stop()
    }

    override fun flush() {
        line?.flush()
    }

    override fun framePosition(): Long = line?.longFramePosition ?: 0L

    /**
     * What is waiting to be collected. The device's own share of the path is
     * not in it, because JavaSound does not report one.
     */
    override fun latencyNanos(): Long {
        val current = line ?: return 0L
        val format = openFormat ?: return 0L
        return format.nanosFor(format.framesIn(current.available().toLong()))
    }

    /** Always zero: nothing in a `TargetDataLine` counts what went past unread. */
    override fun overrunFrames(): Long = 0L

    override fun setVolume(volume: Float) {
        volumeValue = volume.coerceIn(0f, 1f)
        applyVolume()
    }

    override fun volume(): Float = volumeValue

    override fun close() {
        closed = true
        val current = line
        line = null
        openFormat = null
        if (current != null) {
            // stop before close so a read parked inside the line comes back;
            // this is the only lever JavaSound offers, because the wait belongs
            // to the JDK rather than to us.
            runCatching { current.stop() }
            runCatching { current.flush() }
            runCatching { current.close() }
        }
    }

    /**
     * Best effort, and usually nothing.
     *
     * Capture lines rarely carry a gain control, and where one exists the JDK
     * applies it to the samples rather than at the system level. So the value is
     * remembered either way and [Capability.STREAM_VOLUME] is not claimed.
     */
    private fun applyVolume() {
        val current = line ?: return
        if (!current.isControlSupported(FloatControl.Type.MASTER_GAIN)) return
        val gain = current.getControl(FloatControl.Type.MASTER_GAIN) as FloatControl
        val db = (20f * log10(max(volumeValue, 1e-4f))).coerceIn(gain.minimum, gain.maximum)
        gain.value = db
    }

    internal companion object {
        /**
         * The same reduced set the fallback sink reports, plus the one thing
         * this can do. No device selection, no stream identity, no system
         * volume, and no overrun count.
         */
        val CAPABILITIES: Capabilities = Capabilities.of(
            Capability.CAPTURE,
            Capability.DEVICE_POSITION,
        )

        /** 200 ms, matching the fallback sink and measured on the same reasoning. */
        const val DEFAULT_BUFFER_NANOS: Long = 200_000_000L

        private const val STALLED_POLL_MILLIS = 2L
    }
}
