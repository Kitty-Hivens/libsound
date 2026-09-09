package dev.hivens.libsound.dsp

import dev.hivens.libsound.AudioSink
import dev.hivens.libsound.Capability

/**
 * Scales the samples on their way through.
 *
 * Not the same thing as [AudioSink.setVolume], and the difference is the whole
 * reason [Capability.STREAM_VOLUME] exists. A volume goes to the system where
 * the backend can put it there: the desktop's mixer shows it, the user can move
 * it, and it survives this process. A gain is arithmetic on the buffer, invisible
 * to everything outside, and it is what a consumer wants for a fade, for
 * ducking one of its own streams against another, or for trimming a source that
 * was mastered too hot.
 *
 * Reach for the volume first. This is for the cases the volume cannot express.
 */
public class GainSink(
    inner: AudioSink,
    gain: Float = 1f,
) : ProcessingSink(inner) {

    /**
     * The factor every sample is multiplied by. 1 is unchanged, 0 is silence.
     *
     * Not clamped at the top: a consumer trimming a quiet recording upward has
     * a reason to go above 1, and what happens past full scale is decided by
     * the encoding, or by a [LimiterSink] above this one. Negative is refused,
     * because it inverts the phase rather than quietening anything, and nobody
     * asks for that by accident.
     */
    @Volatile
    public var gain: Float = gain
        set(value) {
            require(value >= 0f) { "gain must not be negative, was $value" }
            field = value
        }

    init {
        require(gain >= 0f) { "gain must not be negative, was $gain" }
    }

    override fun process(samples: FloatArray, frames: Int, channels: Int) {
        val factor = gain
        if (factor == 1f) return
        for (index in 0 until frames * channels) {
            samples[index] *= factor
        }
    }
}
