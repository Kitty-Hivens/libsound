package dev.hivens.libsound.dsp

import dev.hivens.libsound.AudioFormat
import dev.hivens.libsound.AudioSink
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow

/**
 * Holds the peaks under a ceiling, and adds no latency doing it.
 *
 * A limiter usually works by looking ahead: it sees a transient coming, starts
 * turning down before it arrives, and pays for that with delay equal to the
 * lookahead. This one does not look ahead, which is a deliberate trade and the
 * one that suits a decorator. It costs a little shape on the sharpest
 * transients and it means [latencyNanos] stays the wrapped sink's, so a
 * consumer's audio and video do not drift by however deep the limiter is.
 *
 * What it does instead: for every frame, the loudest channel decides the gain,
 * that gain is applied to all of them so the image does not move, and the
 * recovery back to unity is smoothed over [releaseMs] so a single loud frame
 * does not audibly duck everything after it.
 *
 * It cannot overshoot, because the reduction for a sample is computed from that
 * same sample before it is written.
 */
public class LimiterSink(
    inner: AudioSink,
    /** The ceiling, in decibels below full scale. 0 is full scale itself. */
    public val thresholdDb: Double = -1.0,
    /** How long the gain takes to come most of the way back. */
    public val releaseMs: Double = 50.0,
) : ProcessingSink(inner) {

    private val threshold: Float = 10.0.pow(thresholdDb / 20).toFloat()

    /** Worked out at [open] and read by whichever thread writes audio. */
    @Volatile
    private var releaseCoefficient: Float = 0f

    /** The working value, touched only by the thread inside [process]. */
    private var gain: Float = 1f

    /**
     * What [reduction] answers, published once per buffer.
     *
     * [gain] is read and written for every frame, so making that field volatile
     * would put a barrier in the inner loop of a filter to serve a number
     * nobody reads at that rate. A meter drawing this wants the value as of the
     * last buffer, which is what it gets.
     */
    @Volatile
    private var published: Float = 1f

    /** The gain reduction currently applied, 1 while nothing is being held down. */
    public val reduction: Float get() = published

    init {
        require(thresholdDb <= 0.0) { "thresholdDb is below full scale, so it must not be positive" }
        require(releaseMs > 0.0) { "releaseMs must be positive, was $releaseMs" }
    }

    /** Works the release out for this rate, then opens the sink underneath. */
    override fun open(format: AudioFormat) {
        // One time constant per sample, so the release means the same length of
        // time whatever rate the stream turns out to be.
        releaseCoefficient = exp(-1.0 / (releaseMs / 1000.0 * format.sampleRate)).toFloat()
        super.open(format)
    }

    override fun reset() {
        gain = 1f
        published = 1f
    }

    override fun process(samples: FloatArray, frames: Int, channels: Int) {
        // Both fields into locals before the loop. The release is volatile and
        // the gain is not, and neither wants reading from memory once a frame.
        var current = gain
        val release = releaseCoefficient
        for (frame in 0 until frames) {
            val base = frame * channels
            var peak = 0f
            for (channel in 0 until channels) {
                val magnitude = abs(samples[base + channel])
                if (magnitude > peak) peak = magnitude
            }
            // Instantaneous when it has to come down, smoothed on the way back:
            // the first is what makes an overshoot impossible, the second is
            // what keeps one loud frame from ducking the next second of audio.
            val wanted = if (peak * current > threshold) threshold / peak else 1f
            current = if (wanted < current) wanted else wanted + (current - wanted) * release
            if (current != 1f) {
                for (channel in 0 until channels) {
                    samples[base + channel] *= current
                }
            }
        }
        gain = current
        published = current
    }
}
