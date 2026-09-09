package dev.hivens.libsound.dsp

import dev.hivens.libsound.AudioFormat
import dev.hivens.libsound.AudioSink

/**
 * Runs a second order filter over everything written through it.
 *
 * The coefficients come from a function of the format rather than from a
 * constructor argument, because every one of them depends on the sample rate
 * and the rate is not known until [open]. A consumer that reopens at another
 * rate gets a filter designed for that rate rather than one that quietly moved
 * its own cutoff.
 *
 * ```
 * BiquadSink(sink) { Biquad.highPass(it.sampleRate, 80.0) }
 * ```
 *
 * State is per channel and is dropped on a flush. That is the fourth decorator
 * rule and the one a filter can actually break: two poles hold roughly two
 * samples of the audio that came before, and a seek that leaves them in place
 * plays the old position into the new one.
 */
public class BiquadSink(
    inner: AudioSink,
    private val design: (AudioFormat) -> Biquad,
) : ProcessingSink(inner) {

    private var coefficients: Biquad? = null
    private var x1: FloatArray = FloatArray(0)
    private var x2: FloatArray = FloatArray(0)
    private var y1: FloatArray = FloatArray(0)
    private var y2: FloatArray = FloatArray(0)

    /** The filter currently running, or null before the first [open]. */
    public val filter: Biquad? get() = coefficients

    /** Designs the filter for this format, then opens the sink underneath. */
    override fun open(format: AudioFormat) {
        coefficients = design(format)
        x1 = FloatArray(format.channels)
        x2 = FloatArray(format.channels)
        y1 = FloatArray(format.channels)
        y2 = FloatArray(format.channels)
        super.open(format)
    }

    override fun reset() {
        x1.fill(0f)
        x2.fill(0f)
        y1.fill(0f)
        y2.fill(0f)
    }

    override fun process(samples: FloatArray, frames: Int, channels: Int) {
        val filter = coefficients ?: return
        if (x1.size < channels) return
        // Direct form I, which keeps the input and output histories apart and
        // is the shape the coefficients above are written for.
        for (frame in 0 until frames) {
            val base = frame * channels
            for (channel in 0 until channels) {
                val x0 = samples[base + channel]
                val y0 = filter.b0 * x0 + filter.b1 * x1[channel] + filter.b2 * x2[channel] -
                    filter.a1 * y1[channel] - filter.a2 * y2[channel]
                x2[channel] = x1[channel]
                x1[channel] = x0
                y2[channel] = y1[channel]
                y1[channel] = y0
                samples[base + channel] = y0
            }
        }
    }
}
