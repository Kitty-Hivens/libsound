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

    // Every one of these is written by [open] and read by whichever thread
    // writes audio, which is not required to be the same one. A volatile write
    // of the reference is also what publishes the array it points at.
    @Volatile
    private var coefficients: Biquad? = null

    @Volatile
    private var x1: FloatArray = FloatArray(0)

    @Volatile
    private var x2: FloatArray = FloatArray(0)

    @Volatile
    private var y1: FloatArray = FloatArray(0)

    @Volatile
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
        // Read once rather than per sample. Each is a volatile field and the
        // inner loop touches all four for every channel of every frame.
        val inputOne = x1
        val inputTwo = x2
        val outputOne = y1
        val outputTwo = y2
        if (inputOne.size < channels) return
        // Direct form I, which keeps the input and output histories apart and
        // is the shape the coefficients above are written for.
        for (frame in 0 until frames) {
            val base = frame * channels
            for (channel in 0 until channels) {
                val x0 = samples[base + channel]
                val y0 = filter.b0 * x0 + filter.b1 * inputOne[channel] + filter.b2 * inputTwo[channel] -
                    filter.a1 * outputOne[channel] - filter.a2 * outputTwo[channel]
                inputTwo[channel] = inputOne[channel]
                inputOne[channel] = x0
                outputTwo[channel] = outputOne[channel]
                outputOne[channel] = y0
                samples[base + channel] = y0
            }
        }
    }
}
