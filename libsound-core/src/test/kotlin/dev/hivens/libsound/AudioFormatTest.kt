package dev.hivens.libsound

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AudioFormatTest {

    @Test
    fun `bytes per frame follows the encoding and the channel count`() {
        AudioFormat(48_000, 2, PcmEncoding.S16LE).bytesPerFrame shouldBe 4
        AudioFormat(48_000, 1, PcmEncoding.S16LE).bytesPerFrame shouldBe 2
        AudioFormat(48_000, 2, PcmEncoding.F32LE).bytesPerFrame shouldBe 8
    }

    @Test
    fun `a second of frames is a second of nanoseconds`() {
        val format = AudioFormat(48_000)
        format.nanosFor(48_000) shouldBe 1_000_000_000L
        format.framesFor(1_000_000_000L) shouldBe 48_000L
    }

    @Test
    fun `a rate that does not divide evenly still round-trips within a frame`() {
        val format = AudioFormat(44_100)
        val frames = 44_100L * 37 + 1_234
        val back = format.framesFor(format.nanosFor(frames))
        // Integer nanoseconds cannot name every frame boundary at 44.1 kHz, so
        // the round trip loses up to one frame -- 23 us, which is buffer-sizing
        // and latency arithmetic, not a sample-precise anchor. A caller that
        // needs the exact sample seeks by frames and never goes through nanos.
        val lost = frames - back
        (lost in 0..1) shouldBe true
    }

    @Test
    fun `frame arithmetic survives a hundred hours`() {
        // 100 h at 48 kHz is 1.728e10 frames, and the naive
        // `frames * 1_000_000_000` overflows Long above about 9.2e9 frames --
        // roughly 53 h. A soak run reaches that; a unit test with a few seconds
        // of audio never would, which is why this case is written down.
        val format = AudioFormat(48_000)
        val frames = 48_000L * 3_600 * 100
        format.nanosFor(frames) shouldBe 360_000L * 1_000_000_000L
        format.framesFor(360_000L * 1_000_000_000L) shouldBe frames
    }

    @Test
    fun `a partial trailing frame is not counted`() {
        val format = AudioFormat(48_000)
        format.framesIn(4) shouldBe 1L
        format.framesIn(7) shouldBe 1L
        format.framesIn(8) shouldBe 2L
    }

    @Test
    fun `a nonsense format is refused at construction`() {
        assertThrows<IllegalArgumentException> { AudioFormat(0) }
        assertThrows<IllegalArgumentException> { AudioFormat(-48_000) }
        assertThrows<IllegalArgumentException> { AudioFormat(48_000, 0) }
    }
}
