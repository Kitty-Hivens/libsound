package dev.hivens.libsound

import dev.hivens.libsound.testing.FakeAudioSink
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PullPumpTest {

    private val format = AudioFormat(48_000)

    /** A sink with room for a whole test, so nothing parks on the buffer. */
    // `also`, not `apply`: inside `apply` the receiver's own nullable `format`
    // shadows this class's, and the sink would be opened with itself.
    private fun openSink() = FakeAudioSink(bufferFrames = 480_000).also { it.open(format) }

    @Test
    fun `the pump drives a source into the sink`() {
        val sink = openSink()
        val reads = CountDownLatch(4)
        val source = PcmSource { dst, offset, length ->
            dst.fill(0x11, offset, offset + length)
            reads.countDown()
            length
        }

        PullPump(sink, source).use { pump ->
            pump.start()
            reads.await(5, TimeUnit.SECONDS) shouldBe true
        }

        val captured = sink.capturedBytes()
        captured.isNotEmpty() shouldBe true
        captured.all { it == 0x11.toByte() } shouldBe true
        sink.close()
    }

    @Test
    fun `a short read is padded with silence rather than shortening the period`() {
        // Handing the device less than a period is what produces a click; the
        // gap is audible either way, and silence keeps the frame count -- and
        // therefore the clock -- honest.
        val sink = openSink()
        val reads = CountDownLatch(3)
        val source = PcmSource { dst, offset, length ->
            val half = (length / 2) - (length / 2) % format.bytesPerFrame
            dst.fill(0x22, offset, offset + half)
            reads.countDown()
            half
        }

        PullPump(sink, source).use { pump ->
            pump.start()
            reads.await(5, TimeUnit.SECONDS) shouldBe true
            pump.silenceFrames shouldBeGreaterThan 0L
        }
        sink.close()
    }

    @Test
    fun `end of stream stops the pump`() {
        val sink = openSink()
        val calls = AtomicInteger(0)
        val source = PcmSource { _, _, _ ->
            if (calls.incrementAndGet() >= 3) -1 else 0
        }

        PullPump(sink, source).use { pump ->
            pump.start()
            val deadline = System.nanoTime() + 5_000_000_000L
            while (!pump.isEnded && System.nanoTime() < deadline) Thread.sleep(10)
            pump.isEnded shouldBe true
        }
        sink.close()
    }

    @Test
    fun `a pump needs an open sink to size its period`() {
        val sink = FakeAudioSink()
        val failure = runCatching { PullPump(sink, PcmSource { _, _, _ -> 0 }) }.exceptionOrNull()
        (failure is IllegalArgumentException) shouldBe true
        sink.close()
    }
}
