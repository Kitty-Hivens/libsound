package dev.hivens.libsound.dsp

import dev.hivens.libsound.AudioSink
import dev.hivens.libsound.testing.AudioSinkDecoratorContract

/**
 * Every decorator this module ships, against the fixture that says what one
 * owes.
 *
 * The point of the module is that processing hangs off a public interface, and
 * the point of the fixture is that hanging things off it is a trap without one.
 * So each of these is four lines and none of them is optional: a filter that
 * returns before the device took the audio, or reports frames nobody heard, is
 * caught here rather than in somebody's synchronisation three phases later.
 */
class GainSinkContractTest : AudioSinkDecoratorContract() {
    override fun decorate(inner: AudioSink): AudioSink = GainSink(inner, gain = 0.5f)
}

class BiquadSinkContractTest : AudioSinkDecoratorContract() {
    override fun decorate(inner: AudioSink): AudioSink =
        BiquadSink(inner) { Biquad.lowPass(it.sampleRate, 1_000.0) }
}

class LimiterSinkContractTest : AudioSinkDecoratorContract() {
    override fun decorate(inner: AudioSink): AudioSink = LimiterSink(inner)
}

class TapSinkContractTest : AudioSinkDecoratorContract() {
    override fun decorate(inner: AudioSink): AudioSink = TapSink(inner) { _, _, _ -> }
}

/**
 * And a stack of them, because a consumer's chain is never one deep and the
 * rules have to survive composition: each of these delegates to the next, so a
 * decorator that broke the blocking or the position would break it through
 * three layers rather than one.
 */
class StackedDecoratorContractTest : AudioSinkDecoratorContract() {
    override fun decorate(inner: AudioSink): AudioSink =
        GainSink(BiquadSink(LimiterSink(inner)) { Biquad.highPass(it.sampleRate, 80.0) }, gain = 0.8f)
}
