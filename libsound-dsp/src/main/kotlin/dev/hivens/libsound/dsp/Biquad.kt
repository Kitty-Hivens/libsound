package dev.hivens.libsound.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The five coefficients of a second order section, already normalised.
 *
 * A biquad is the smallest filter worth having: two poles and two zeros, which
 * is enough for a shelf, a bell, or one stage of a steeper filter built by
 * running several. The designers below are the standard cookbook formulas, and
 * they are here rather than in the sink because coefficients depend on the
 * sample rate, which is not known until a stream is open.
 *
 * Normalised means every coefficient is already divided by `a0`, so the sink
 * multiplies and adds and never divides.
 */
public data class Biquad(
    public val b0: Float,
    public val b1: Float,
    public val b2: Float,
    public val a1: Float,
    public val a2: Float,
) {
    public companion object {
        /** Passes what is below [cutoffHz] and rolls off above it, at 12 dB per octave. */
        public fun lowPass(sampleRate: Int, cutoffHz: Double, q: Double = SQRT_HALF): Biquad {
            val w = omega(sampleRate, cutoffHz)
            val alpha = sin(w) / (2 * q)
            val cosine = cos(w)
            val b1 = 1 - cosine
            return normalise(b1 / 2, b1, b1 / 2, 1 + alpha, -2 * cosine, 1 - alpha)
        }

        /** The mirror of [lowPass]: rumble, handling noise, anything under [cutoffHz]. */
        public fun highPass(sampleRate: Int, cutoffHz: Double, q: Double = SQRT_HALF): Biquad {
            val w = omega(sampleRate, cutoffHz)
            val alpha = sin(w) / (2 * q)
            val cosine = cos(w)
            val b1 = -(1 + cosine)
            return normalise(-b1 / 2, b1, -b1 / 2, 1 + alpha, -2 * cosine, 1 - alpha)
        }

        /**
         * A bell at [centreHz], lifted or cut by [gainDb].
         *
         * The one an equaliser is built out of, and the reason [q] matters more
         * here than in the other two: it sets how wide the bell is, so a narrow
         * one corrects a resonance and a wide one changes the character.
         */
        public fun peaking(sampleRate: Int, centreHz: Double, gainDb: Double, q: Double = 1.0): Biquad {
            val w = omega(sampleRate, centreHz)
            val amplitude = 10.0.pow(gainDb / 40)
            val alpha = sin(w) / (2 * q)
            val cosine = cos(w)
            return normalise(
                1 + alpha * amplitude, -2 * cosine, 1 - alpha * amplitude,
                1 + alpha / amplitude, -2 * cosine, 1 - alpha / amplitude,
            )
        }

        /** Butterworth, the flattest passband a single section has. */
        public val SQRT_HALF: Double = sqrt(0.5)

        private fun omega(sampleRate: Int, hz: Double): Double {
            require(sampleRate > 0) { "sampleRate must be positive, was $sampleRate" }
            // Above the Nyquist frequency the formulas fold back and design a
            // filter that does something other than what was asked, quietly.
            require(hz > 0 && hz < sampleRate / 2.0) {
                "frequency must be inside 0 and the Nyquist frequency of ${sampleRate / 2.0}, was $hz"
            }
            return 2 * PI * hz / sampleRate
        }

        private fun normalise(
            b0: Double,
            b1: Double,
            b2: Double,
            a0: Double,
            a1: Double,
            a2: Double,
        ): Biquad = Biquad(
            (b0 / a0).toFloat(),
            (b1 / a0).toFloat(),
            (b2 / a0).toFloat(),
            (a1 / a0).toFloat(),
            (a2 / a0).toFloat(),
        )
    }
}
