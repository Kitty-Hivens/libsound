package dev.hivens.libsound.dsp.samples

import dev.hivens.libsound.AudioSink
import dev.hivens.libsound.dsp.Biquad
import dev.hivens.libsound.dsp.BiquadSink
import dev.hivens.libsound.dsp.GainSink
import dev.hivens.libsound.dsp.LimiterSink
import dev.hivens.libsound.dsp.TapSink

/**
 * Every processing example in `docs/GUIDE.md`, as code that compiles.
 *
 * See [dev.hivens.libsound.audio.samples.AudioSamples] for why these exist as
 * code rather than as prose in the guide.
 */
@Suppress("unused", "UNUSED_PARAMETER")
internal object DspSamples {

    fun watchTheLevel(sink: AudioSink, redraw: (Float) -> Unit): AudioSink {
        // Runs on whichever thread is writing, and holds that write up for as
        // long as it takes. So it measures and hands the number over, and the
        // drawing happens somewhere else.
        return TapSink(sink) { samples, frames, channels ->
            var peak = 0f
            for (index in 0 until frames * channels) {
                val magnitude = kotlin.math.abs(samples[index])
                if (magnitude > peak) peak = magnitude
            }
            redraw(peak)
        }
    }

    fun cleanUpAVoiceRecording(sink: AudioSink): AudioSink {
        // Read outward: the limiter is nearest the device, so it sees what
        // everything above it produced and is the last thing that can stop a
        // peak reaching the speaker.
        return GainSink(
            BiquadSink(
                LimiterSink(sink, thresholdDb = -1.0),
            ) { format -> Biquad.highPass(format.sampleRate, 80.0) },
            gain = 1.5f,
        )
    }
}
