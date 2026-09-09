package dev.hivens.libsound.audio

import dev.hivens.libsound.ChannelLayout
import dev.hivens.libsound.ChannelPosition
import dev.hivens.libsound.audio.pulse.PulseAbi
import dev.hivens.libsound.audio.wasapi.WasapiAbi
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * What each channel is, on the two backends that can say.
 *
 * Table arithmetic, so it runs on every row rather than only where a device is:
 * the numbers came from the oracles, and what is asserted here is that the
 * mapping built on them says what the platforms say.
 *
 * The Windows half exists because of a coincidence worth distrusting. FFmpeg's
 * own layout bits were chosen to line up with `SPEAKER_*`, which makes the
 * interleaving order come out right for free. Free is not the same as checked,
 * and a coincidence that stops holding would be silent: the mask would still be
 * legal, the engine would still play, and one channel would be somewhere else.
 */
class ChannelPlacementTest {

    @Test
    fun `every position libpulse can name is one Windows can name too`() {
        // Not a requirement either platform imposes, and worth pinning: the
        // eighteen both can express are the ones a consumer can count on
        // carrying anywhere, and a divergence would mean a layout that opens on
        // one backend and is refused on the other.
        for (position in ChannelPosition.entries) {
            withClue(position.name) {
                (PulseAbi.channelPositionOf(position) != null) shouldBe
                    (WasapiAbi.speakerBitOf(position) != null)
            }
        }
    }

    @Test
    fun `the assembled mask is the one Windows names`() {
        // Each row is a mask built out of the per-position bits, checked
        // against the whole-layout constant the oracle printed beside them.
        mapOf(
            "mono" to WasapiAbi.KSAUDIO_SPEAKER_MONO,
            "stereo" to WasapiAbi.KSAUDIO_SPEAKER_STEREO,
            "quad" to WasapiAbi.KSAUDIO_SPEAKER_QUAD,
            "4.0" to WasapiAbi.KSAUDIO_SPEAKER_SURROUND,
            "5.1" to WasapiAbi.KSAUDIO_SPEAKER_5POINT1,
            "5.1(side)" to WasapiAbi.KSAUDIO_SPEAKER_5POINT1_SURROUND,
            // The pair worth reading twice. What Windows calls 7.1 is the
            // layout FFmpeg calls 7.1(wide), and what FFmpeg calls 7.1 is the
            // one Windows calls 7.1 surround. A consumer that matched them by
            // name would send eight channels with two of them in the wrong
            // places.
            "7.1(wide)" to WasapiAbi.KSAUDIO_SPEAKER_7POINT1,
            "7.1" to WasapiAbi.KSAUDIO_SPEAKER_7POINT1_SURROUND,
        ).forEach { (name, expected) ->
            withClue(name) {
                WasapiAbi.channelMaskOf(ChannelLayout.STANDARD.getValue(name)) shouldBe expected
            }
        }
    }

    @Test
    fun `the interleaving order FFmpeg uses is the order the mask implies`() {
        // The coincidence, asserted. A WAVEFORMATEXTENSIBLE carries no order of
        // its own: the engine reads channels in ascending bit order, always. So
        // a layout is only carried correctly when the order its positions are
        // interleaved in is already that order, and this is every standard
        // layout Windows can express saying so.
        val checked = ChannelLayout.STANDARD.values.filter { WasapiAbi.channelMaskOf(it) != null }
        checked.forEach { layout ->
            val bits = layout.positions.map { WasapiAbi.speakerBitOf(it)!! }
            withClue("$layout interleaves as $bits") {
                bits shouldBe bits.sorted()
            }
        }
        // And the filter did not quietly leave everything out.
        (checked.size > 20) shouldBe true
    }

    @Test
    fun `a layout naming a position neither platform carries is refused by both`() {
        // 22.2 has a second LFE and a bottom row, and hexadecagonal has the
        // wide pair. Neither system has anywhere to put them, so both answer
        // null and the sinks turn that into a refusal rather than into a
        // channel played somewhere nobody chose.
        listOf("22.2", "hexadecagonal", "7.2.3", "9.1.6").forEach { name ->
            val layout = ChannelLayout.STANDARD.getValue(name)
            withClue(name) {
                (PulseAbi.unplaceable(layout) != null) shouldBe true
                WasapiAbi.channelMaskOf(layout) shouldBe null
            }
        }
    }

    @Test
    fun `a single channel is mono to libpulse, not front centre`() {
        // FFmpeg calls one channel front-center and libpulse keeps the two
        // apart: a stream marked front-center is pinned to that speaker on a
        // surround setup, and one marked mono is spread the way a mono stream
        // is meant to be. The translation is the only place a position does not
        // map to its namesake.
        ChannelLayout.MONO.positions shouldBe listOf(ChannelPosition.FC)
        PulseAbi.channelPositionOf(ChannelPosition.FC) shouldBe PulseAbi.CHANNEL_POSITION_FRONT_CENTER
        PulseAbi.CHANNEL_POSITION_MONO shouldBe 0
    }

    @Test
    fun `what FFmpeg calls back libpulse calls rear`() {
        // The names differ where the concept does not, which is the one place
        // this mapping could be written plausibly and wrongly.
        PulseAbi.channelPositionOf(ChannelPosition.BL) shouldBe PulseAbi.CHANNEL_POSITION_REAR_LEFT
        PulseAbi.channelPositionOf(ChannelPosition.BR) shouldBe PulseAbi.CHANNEL_POSITION_REAR_RIGHT
        PulseAbi.channelPositionOf(ChannelPosition.BC) shouldBe PulseAbi.CHANNEL_POSITION_REAR_CENTER
        // And the sides are their own pair, not the rears under another name.
        PulseAbi.channelPositionOf(ChannelPosition.SL) shouldBe PulseAbi.CHANNEL_POSITION_SIDE_LEFT
        PulseAbi.channelPositionOf(ChannelPosition.SR) shouldBe PulseAbi.CHANNEL_POSITION_SIDE_RIGHT
    }
}
