package dev.hivens.libsound.audio.javasound

import dev.hivens.libsound.AudioException
import dev.hivens.libsound.AudioFormat
import dev.hivens.libsound.AudioSink
import dev.hivens.libsound.Capabilities
import dev.hivens.libsound.Capability
import dev.hivens.libsound.PcmEncoding
import org.slf4j.LoggerFactory
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.FloatControl
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.SourceDataLine
import kotlin.math.log10
import kotlin.math.max
import javax.sound.sampled.AudioFormat as JavaAudioFormat

/**
 * The fallback that exists everywhere.
 *
 * On a PipeWire desktop this is not a separate audio path: JavaSound's default
 * mixer is the ALSA default, which `pipewire-alsa` routes into the same graph
 * `libpulse` reaches. The two backends differ by API, not by device -- which is
 * why the fallback loses stream identity, system volume and device selection.
 * ALSA could carry all three; JavaSound does not expose them.
 *
 * Three measured behaviours shape this class. They were probed rather than
 * assumed, because JavaSound's specification says almost nothing about the
 * playhead.
 *
 * 1. `SourceDataLine.open` does not start the line. The contract says [open]
 *    must, so [open] starts it.
 * 2. `flush()` credits every discarded frame as played -- the reported position
 *    jumps *forward* to the total ever written. See [flushCredit].
 * 3. The position only advances while writes are flowing, and tracks real time
 *    to within a percent when they are. Steady state is trustworthy; the
 *    transient after an open or a flush is not, which the seek handshake
 *    already avoids by freezing the device first.
 */
internal class JavaSoundSink(
    private val bufferNanos: Long = DEFAULT_BUFFER_NANOS,
) : AudioSink {

    private val log = LoggerFactory.getLogger("libsound.JavaSound")

    // Volatile and never held under a lock across a blocking call: close() has
    // to reach the line while write() is parked inside it, and a lock shared
    // between the two would make the rescue impossible.
    @Volatile
    private var line: SourceDataLine? = null

    @Volatile
    private var openFormat: AudioFormat? = null

    @Volatile
    private var closed = false

    private var volumeValue = 1f

    /**
     * Frames the device was credited with but never played.
     *
     * `flush()` moves the reported position forward by whatever was still
     * buffered. Rather than model that as "position equals frames written" --
     * which is this implementation's behaviour and not a documented one -- the
     * jump is measured each time: read the raw position on both sides of the
     * flush and accumulate the difference. A backend whose flush is honest
     * accumulates zero and the arithmetic disappears.
     */
    @Volatile
    private var flushCredit = 0L

    override val capabilities: Capabilities = CAPABILITIES

    override val format: AudioFormat? get() = openFormat

    override val isOpen: Boolean get() = line != null && !closed

    override fun open(format: AudioFormat) {
        if (closed) throw AudioException("sink is closed")
        require(format.encoding == PcmEncoding.S16LE) {
            "JavaSound backend takes S16LE only, was ${format.encoding}"
        }
        // A reopen drops the old line first; without this the previous line
        // keeps the device and its buffered tail.
        line?.let { old ->
            runCatching { old.stop() }
            runCatching { old.flush() }
            runCatching { old.close() }
        }
        // Cleared before the new line is built, not after: a failure below must
        // not leave isOpen answering true and format handing back the previous
        // track's shape while the field points at a closed line.
        line = null
        openFormat = null
        flushCredit = 0
        val javaFormat = JavaAudioFormat(
            format.sampleRate.toFloat(),
            format.encoding.bytesPerSample * 8,
            format.channels,
            true,
            false,
        )
        val bufferBytes = (format.bytesFor(format.framesFor(bufferNanos))).toInt()
            .coerceAtLeast(format.bytesPerFrame)
        val fresh = try {
            AudioSystem.getSourceDataLine(javaFormat).apply {
                open(javaFormat, bufferBytes)
                // The contract's first rule. JavaSound splits open from start;
                // a caller wanting silence stops immediately after.
                start()
            }
        } catch (e: LineUnavailableException) {
            throw AudioException("no audio line for $format", e)
        } catch (e: IllegalArgumentException) {
            throw AudioException("audio format not supported: $format", e)
        }
        line = fresh
        openFormat = format
        applyVolume()
        log.debug("opened {} with a {} ms buffer", format, bufferNanos / 1_000_000)
    }

    override fun write(data: ByteArray, offset: Int, length: Int) {
        val format = openFormat ?: throw AudioException("write before open")
        require(offset >= 0 && length >= 0 && offset + length <= data.size) {
            "range $offset..${offset + length} outside array of ${data.size}"
        }
        require(length % format.bytesPerFrame == 0) {
            "length ($length) must be a whole number of frames (${format.bytesPerFrame})"
        }
        // Read the field once: close() can null it while this is parked inside
        // the write, and the rescue works precisely because the local reference
        // keeps the object alive while the JDK returns from a closed line.
        val current = line ?: throw AudioException("write on a closed sink")
        // SourceDataLine.write returns early with a PARTIAL count when the line
        // is stopped, flushed or closed under it. Ignoring that count broke the
        // contract's central rule in the quietest possible way: the caller
        // believed every byte had gone out, advanced its stream position, and
        // the audio was dropped. It also made "close unblocks a write" pass for
        // the wrong reason -- the write returned normally instead of coming
        // back at all.
        var written = 0
        while (written < length) {
            val accepted = current.write(data, offset + written, length - written)
            if (accepted <= 0) {
                if (closed || line == null) throw AudioException("sink closed while writing")
                // A stopped or flushed line accepts nothing until it runs
                // again; keep waiting, because close is what breaks this. The
                // wait has to cost something, though: spinning here burns a
                // core for as long as the line stays stopped, which is the
                // failure the WASAPI sink already sleeps to avoid.
                Thread.sleep(STALLED_POLL_MILLIS)
                continue
            }
            written += accepted
            if (written < length && (closed || line == null)) {
                throw AudioException("sink closed while writing")
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
        val current = line ?: return
        // Measure the jump instead of predicting it. Both readings come from
        // the same thread that owns the seek path, so nothing can slip between.
        val before = current.longFramePosition
        current.flush()
        val after = current.longFramePosition
        if (after > before) flushCredit += after - before
    }

    override fun framePosition(): Long {
        val raw = line?.longFramePosition ?: return 0L
        // Never negative: a backend whose flush behaves differently from the
        // measured one could leave the credit ahead of the raw reading, and a
        // negative playhead is worse than a stalled one.
        return max(0L, raw - flushCredit)
    }

    override fun latencyNanos(): Long {
        val current = line ?: return 0L
        val format = openFormat ?: return 0L
        val buffered = (current.bufferSize - current.available()).coerceAtLeast(0)
        return format.nanosFor(format.framesIn(buffered.toLong()))
    }

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
            // stop before close so a write parked inside the line returns; this
            // is the only lever JavaSound offers, because the wait belongs to
            // the JDK rather than to us.
            runCatching { current.stop() }
            runCatching { current.flush() }
            runCatching { current.close() }
        }
    }

    private fun applyVolume() {
        val current = line ?: return
        if (!current.isControlSupported(FloatControl.Type.MASTER_GAIN)) return
        val gain = current.getControl(FloatControl.Type.MASTER_GAIN) as FloatControl
        // Linear to decibels, floored so that zero is silence rather than
        // negative infinity.
        val db = (20f * log10(max(volumeValue, 1e-4f))).coerceIn(gain.minimum, gain.maximum)
        gain.value = db
    }

    internal companion object {
        /**
         * Deliberately not DEVICE_ENUMERATION or DEVICE_SELECTION: the mixers
         * JavaSound can actually select are `plughw:*`, which take the hardware
         * exclusively and fight the sound server for it, and a device list whose
         * entries break playback is worse than no list.
         *
         * Not STREAM_VOLUME: MASTER_GAIN is applied to the samples by the JDK,
         * so the desktop's mixer neither shows it nor follows it. Not
         * STREAM_IDENTITY: the stream appears under whatever name the ALSA
         * client is given and nothing here can change it.
         *
         * Shared with the backend so the two can never disagree -- a backend
         * claiming less than its own sinks is what makes a consumer hide a
         * feature that works.
         */
        val CAPABILITIES: Capabilities = Capabilities.of(
            // Honest in steady state, measured to within a percent of real time.
            Capability.DEVICE_POSITION,
        )

        /**
         * 200 ms. Not a preference -- the measured floor. skinema found 100 ms
         * cleaner on an idle machine and intermittently glitchy with a build
         * running, and an underrun freezes a clock exactly like the stall it was
         * meant to avoid. A deterministic 200 ms beats a load-dependent freeze.
         */
        const val DEFAULT_BUFFER_NANOS: Long = 200_000_000L

        /**
         * How long to wait when the line accepts nothing.
         *
         * Short enough that a restarted line is fed within a frame of video,
         * long enough that a line left stopped costs nothing measurable.
         */
        private const val STALLED_POLL_MILLIS = 2L
    }
}
