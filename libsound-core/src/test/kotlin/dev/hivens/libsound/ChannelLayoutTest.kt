package dev.hivens.libsound

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 * The channel dictionary, against the tool it was read from.
 *
 * These names and orders are quotation, and quotation drifts. A position
 * understood as the wrong one is not an error anybody sees: it puts a film's
 * rear channels into its sides, and the only symptom is that the room sounds
 * wrong to somebody who is not looking at a screen full of assertions.
 *
 * So where FFmpeg is present the table is checked against it. Where it is not,
 * the shape rules below still run, because they need nothing.
 */
class ChannelLayoutTest {

    private fun ffmpegLayouts(): Map<String, List<String>>? = runCatching {
        val process = ProcessBuilder("ffmpeg", "-hide_banner", "-layouts")
            .redirectErrorStream(true).start()
        val out = process.inputStream.readAllBytes().decodeToString()
        process.waitFor(10, TimeUnit.SECONDS)
        val standard = out.substringAfter("Standard channel layouts:", "").lines()
            .drop(1)
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"), limit = 2)
                if (parts.size != 2) null else parts[0] to parts[1].split("+")
            }
        standard.takeIf { it.isNotEmpty() }?.toMap()
    }.getOrNull()

    @Test
    fun `every layout decomposes the way ffmpeg decomposes it`() {
        val theirs = ffmpegLayouts()
        Assumptions.assumeTrue(theirs != null, "ffmpeg not installed, nothing to check against")
        // Only what this library names. A version of FFmpeg that grew a layout
        // we do not know is not a defect here, and one that reordered a layout
        // we do know is the defect this exists to catch.
        ChannelLayout.STANDARD.forEach { (name, layout) ->
            val decomposition = theirs!![name]
            if (decomposition != null) {
                layout.positions.map { it.ffmpegName } shouldBe decomposition
            }
        }
    }

    @Test
    fun `the six-channel layouts differ in the pair that is not the front`() {
        // The case the whole type exists for. Both are six channels and laying
        // one out as the other moves the rear of a film into its sides.
        val back = ChannelLayout.STANDARD.getValue("5.1")
        val side = ChannelLayout.STANDARD.getValue("5.1(side)")
        back.channels shouldBe side.channels
        (back == side) shouldBe false
        back.positions.takeLast(2) shouldBe listOf(ChannelPosition.BL, ChannelPosition.BR)
        side.positions.takeLast(2) shouldBe listOf(ChannelPosition.SL, ChannelPosition.SR)
    }

    @Test
    fun `a bare channel count resolves the way ffmpeg resolves it`() {
        // Read from libavutil rather than guessed, and it is not what guessing
        // produces: three channels is 2.1 rather than 3.0, and four is 4.0
        // rather than quad.
        ChannelLayout.defaultFor(1).name shouldBe "mono"
        ChannelLayout.defaultFor(2).name shouldBe "stereo"
        ChannelLayout.defaultFor(3).name shouldBe "2.1"
        ChannelLayout.defaultFor(4).name shouldBe "4.0"
        ChannelLayout.defaultFor(6).name shouldBe "5.1"
        ChannelLayout.defaultFor(8).name shouldBe "7.1"
    }

    @Test
    fun `a count with no layout stays a count`() {
        // Nine channels is nine channels. FFmpeg answers with every position
        // unset, and inventing one here would be a lie the caller cannot see.
        val nine = ChannelLayout.defaultFor(9)
        nine.isSpecified shouldBe false
        nine.channels shouldBe 9
        nine.name shouldBe "9 channels"
        ChannelLayout.STEREO.isSpecified shouldBe true
    }

    @Test
    fun `what arrives across the seam parses`() {
        ChannelLayout.of("5.1(side)") shouldBe ChannelLayout.STANDARD.getValue("5.1(side)")
        // The shape a stream that declared nothing arrives as.
        ChannelLayout.of("6 channels") shouldBe ChannelLayout.defaultFor(6)
        ChannelLayout.of("1 channel") shouldBe ChannelLayout.MONO
        ChannelLayout.of("9 channels")?.isSpecified shouldBe false
        ChannelLayout.of("not a layout") shouldBe null
        ChannelLayout.of("0 channels") shouldBe null
    }

    @Test
    fun `a format cannot disagree with its own layout`() {
        // The invariant worth a check rather than a comment: a six-channel
        // layout on a two-channel format describes nothing that exists.
        runCatching { AudioFormat(48_000, 2, layout = ChannelLayout.SURROUND_5_1) }
            .isFailure shouldBe true
        AudioFormat(48_000, 6).layout shouldBe ChannelLayout.SURROUND_5_1
        AudioFormat(48_000, 2).layout shouldBe ChannelLayout.STEREO
    }

    @Test
    fun `significant bits are the whole sample until somebody says otherwise`() {
        AudioFormat(48_000, 2, PcmEncoding.S32LE).significantBits shouldBe 32
        // What a 24-bit source actually arrives as, because FFmpeg has no
        // 24-bit sample format to send it in.
        AudioFormat(48_000, 2, PcmEncoding.S32LE, significantBits = 24).significantBits shouldBe 24
        runCatching { AudioFormat(48_000, 2, PcmEncoding.S16LE, significantBits = 24) }
            .isFailure shouldBe true
    }
}
