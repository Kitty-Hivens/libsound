package dev.hivens.libsound.dsp

import dev.hivens.libsound.AudioSink

/**
 * Frames on their way to the device, handed to somebody watching.
 *
 * The one call a level meter, a spectrum or a waveform needs, and the reason it
 * is a decorator rather than something on the sink: a consumer that plays audio
 * already holds the samples, and a visualiser is not a second consumer of the
 * device but a second reader of the same buffer.
 *
 * Called on whichever thread is writing, which is the consumer's own audio
 * thread, and it is holding up the write for as long as this takes. So the
 * body belongs on the far side of a queue: copy what is wanted, hand it over,
 * return. Anything drawn, allocated or locked here is paid for in the audio
 * path, and the deadline there is the buffer, which at the low latency profiles
 * is single-digit milliseconds.
 */
public fun interface FrameTap {

    /**
     * [frames] frames of [channels] interleaved samples, roughly in -1 to 1.
     *
     * [samples] belongs to the sink and is reused by the next write. It is
     * valid for the length of this call and not one instruction longer, so
     * anything kept has to be copied out.
     */
    public fun onFrames(samples: FloatArray, frames: Int, channels: Int)
}

/**
 * Passes every frame through unchanged and shows it to a [FrameTap] first.
 *
 * Changes nothing, holds nothing and adds no latency, so it is the cheapest
 * decorator there is and the one to reach for when the audio is not the point.
 */
public class TapSink(
    inner: AudioSink,
    private val tap: FrameTap,
) : ProcessingSink(inner) {

    override fun process(samples: FloatArray, frames: Int, channels: Int) {
        tap.onFrames(samples, frames, channels)
    }
}
