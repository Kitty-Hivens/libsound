package dev.hivens.libsound.dsp

import dev.hivens.libsound.AudioFormat
import dev.hivens.libsound.PcmEncoding
import dev.hivens.libsound.testing.FakeAudioSink
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * What the samples actually are by the time they reach the device.
 *
 * The contract fixture asserts the rules a decorator owes a consumer's clock.
 * These assert the other half: that the arithmetic is the arithmetic that was
 * asked for, read out of the bytes the fake device received rather than out of
 * the code that produced them.
 */
class ProcessingSinkTest {

    private val format = AudioFormat(48_000, 2, PcmEncoding.S16LE)
    private val device = FakeAudioSink(bufferFrames = 48_000)

    private fun pcm(vararg samples: Short): ByteArray {
        val buffer = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { buffer.putShort(it) }
        return buffer.array()
    }

    private fun int32(vararg samples: Int): ByteArray {
        val buffer = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { buffer.putInt(it) }
        return buffer.array()
    }

    private fun float32(vararg samples: Float): ByteArray {
        val buffer = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { buffer.putFloat(it) }
        return buffer.array()
    }

    private fun float64(vararg samples: Double): ByteArray {
        val buffer = ByteBuffer.allocate(samples.size * 8).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { buffer.putDouble(it) }
        return buffer.array()
    }

    private fun withClue(clue: Any?, body: () -> Unit) {
        runCatching(body).onFailure { throw AssertionError("$clue", it) }
    }

    private fun captured(): ShortArray {
        val bytes = device.capturedBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return ShortArray(bytes.size / 2) { buffer.short }
    }

    @Test
    fun `a gain scales what reaches the device`() {
        GainSink(device, gain = 0.5f).use { sink ->
            sink.open(format)
            sink.write(pcm(10_000, -10_000, 4_000, -4_000), 0, 8)
        }
        captured() shouldBe shortArrayOf(5_000, -5_000, 2_000, -2_000)
    }

    @Test
    fun `a gain of one leaves the bytes exactly as they were`() {
        // The conversion out and back has to be the identity, or every consumer
        // that inserts a decorator it is not using pays a rounding error for it.
        val original = pcm(0, 1, -1, 32_767, -32_768, 12_345, -12_345, 7)
        GainSink(device, gain = 1f).use { sink ->
            sink.open(format)
            sink.write(original, 0, original.size)
        }
        device.capturedBytes() shouldBe original
    }

    @Test
    fun `going past full scale clamps rather than wraps`() {
        // The classic integer failure: a sample that overflows a short comes
        // back with the opposite sign, and a loud passage becomes a click.
        GainSink(device, gain = 4f).use { sink ->
            sink.open(format)
            sink.write(pcm(20_000, -20_000), 0, 4)
        }
        captured() shouldBe shortArrayOf(32_767, -32_768)
    }

    @Test
    fun `a flush drops the filter's memory of what came before`() {
        // The fourth rule, and the one a filter can actually break. Two poles
        // hold the tail of what was playing, and a seek that leaves them in
        // place plays the old position into the new one.
        val sink = BiquadSink(device) { Biquad.lowPass(it.sampleRate, 500.0) }
        sink.use {
            sink.open(format)
            val loud = ShortArray(2_000) { 20_000 }
            sink.write(pcm(*loud), 0, loud.size * 2)
            sink.stop()
            sink.flush()
            device.start()
            // The device keeps everything written since it was opened, and a
            // flush empties its buffer rather than that record, so the question
            // is only about what arrives from here on.
            val before = device.capturedBytes().size

            // Silence in. Anything that comes out is the previous audio ringing.
            val silence = ByteArray(2_000 * 2)
            sink.write(silence, 0, silence.size)
            captured().drop(before / 2).all { it.toInt() == 0 } shouldBe true
        }
    }

    @Test
    fun `a filter with its memory intact does ring, which is what makes the flush matter`() {
        // The same signal without the flush. Without this, the assertion above
        // would pass just as happily on a filter that does nothing at all.
        val sink = BiquadSink(device) { Biquad.lowPass(it.sampleRate, 500.0) }
        sink.use {
            sink.open(format)
            val loud = ShortArray(2_000) { 20_000 }
            sink.write(pcm(*loud), 0, loud.size * 2)
            device.consumeAll()
            val before = device.capturedBytes().size
            val silence = ByteArray(2_000 * 2)
            sink.write(silence, 0, silence.size)
            val tail = captured().drop(before / 2)
            tail.any { it.toInt() != 0 } shouldBe true
        }
    }

    @Test
    fun `a limiter holds the ceiling it was given`() {
        val threshold = -6.0
        val ceiling = Math.pow(10.0, threshold / 20).toFloat()
        LimiterSink(device, thresholdDb = threshold).use { sink ->
            sink.open(format)
            // Full scale on both channels, which is well over the ceiling.
            val loud = ShortArray(4_000) { if (it % 2 == 0) 32_767 else -32_768 }
            sink.write(pcm(*loud), 0, loud.size * 2)
        }
        // A frame of slack for the rounding the integer encoding costs.
        val peak = captured().maxOf { abs(it / 32_768f) }
        (peak <= ceiling + 0.001f) shouldBe true
    }

    @Test
    fun `a limiter leaves quiet audio alone`() {
        LimiterSink(device, thresholdDb = -6.0).use { sink ->
            sink.open(format)
            sink.write(pcm(1_000, -1_000, 2_000, -2_000), 0, 8)
            sink.reduction shouldBe 1f
        }
        captured() shouldBe shortArrayOf(1_000, -1_000, 2_000, -2_000)
    }

    @Test
    fun `a tap sees every frame and changes none of them`() {
        var frames = 0
        var channels = 0
        var loudest = 0f
        val original = pcm(10_000, -10_000, 4_000, -4_000, 0, 0)
        TapSink(device) { samples, count, lanes ->
            frames += count
            channels = lanes
            for (index in 0 until count * lanes) {
                val magnitude = abs(samples[index])
                if (magnitude > loudest) loudest = magnitude
            }
        }.use { sink ->
            sink.open(format)
            sink.write(original, 0, original.size)
        }
        frames shouldBe 3
        channels shouldBe 2
        // What the visualiser would draw, and what the device got, unchanged.
        (abs(loudest - 10_000f / 32_768f) < 0.001f) shouldBe true
        device.capturedBytes() shouldBe original
    }

    @Test
    fun `every encoding survives a gain of one, as far as a float can carry it`() {
        // Decode and encode have to be each other's inverse, or a consumer that
        // inserts a decorator it is not using pays for it in the samples. The
        // limit is real and documented rather than idealised: the carrier is a
        // float, whose mantissa holds 24 bits.
        val exact = mapOf(
            PcmEncoding.U8 to byteArrayOf(0, 1, 127, -128, -1, 64),
            PcmEncoding.S16LE to pcm(0, 1, -1, 32_767, -32_768, 12_345, -12_345, 7),
            // 24-bit content in the top bits, which is what FFmpeg actually
            // sends and the case a float carries exactly.
            PcmEncoding.S32LE to int32(0, 256, -256, 0x7FFFFF00, -0x7FFFFF00, 0x123456_00),
            PcmEncoding.F32LE to float32(0.25f, -0.5f, 1f, -1f, 0f),
            PcmEncoding.F64LE to float64(0.25, -0.5, 1.0, -1.0, 0.0),
        )
        exact.forEach { (encoding, bytes) ->
            val device = FakeAudioSink(bufferFrames = 48_000)
            GainSink(device, gain = 1f).use { sink ->
                sink.open(AudioFormat(48_000, 1, encoding))
                sink.write(bytes, 0, bytes.size)
            }
            withClue(encoding) { device.capturedBytes() shouldBe bytes }
        }
    }

    @Test
    fun `a sample using all thirty-two bits does not survive, which is why significantBits exists`() {
        // The other side of the same limit, asserted rather than hoped for. A
        // value with bits below the top 24 comes back changed, and a consumer
        // that needs them kept has to keep the decorator out of that path.
        val device = FakeAudioSink(bufferFrames = 48_000)
        val bytes = int32(0x12345678, 0x7FFFFFFF)
        GainSink(device, gain = 1f).use { sink ->
            sink.open(AudioFormat(48_000, 1, PcmEncoding.S32LE))
            sink.write(bytes, 0, bytes.size)
        }
        (device.capturedBytes() contentEquals bytes) shouldBe false
    }

    @Test
    fun `float samples survive the trip untouched`() {
        val floats = AudioFormat(48_000, 2, PcmEncoding.F32LE)
        val buffer = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        listOf(0.25f, -0.5f, 1f, -1f).forEach { buffer.putFloat(it) }
        GainSink(device, gain = 1f).use { sink ->
            sink.open(floats)
            sink.write(buffer.array(), 0, 16)
        }
        device.capturedBytes() shouldBe buffer.array()
    }
}
