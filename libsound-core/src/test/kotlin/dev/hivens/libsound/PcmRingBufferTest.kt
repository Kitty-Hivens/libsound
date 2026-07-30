package dev.hivens.libsound

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class PcmRingBufferTest {

    private val frame = 4

    private fun ring(frames: Int) = PcmRingBuffer(frames * frame, frame)

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `what goes in comes out in order`() {
        val ring = ring(4)
        val src = ByteArray(8) { it.toByte() }
        ring.write(src, 0, 8) shouldBe 8
        val dst = ByteArray(8)
        ring.read(dst, 0, 8) shouldBe 8
        dst.toList() shouldBe src.toList()
    }

    @Test
    fun `a write that spans the end of the buffer wraps`() {
        val ring = ring(4)                       // 16 bytes
        ring.write(ByteArray(12) { 1 }, 0, 12)
        ring.read(ByteArray(12), 0, 12) shouldBe 12   // head is now at 12

        // 8 bytes from offset 12 has to split 4 + 4 around the end.
        val src = ByteArray(8) { (it + 100).toByte() }
        ring.write(src, 0, 8) shouldBe 8
        val dst = ByteArray(8)
        ring.read(dst, 0, 8) shouldBe 8
        dst.toList() shouldBe src.toList()
    }

    @Test
    fun `a full ring accepts what fits and counts the rest as overrun`() {
        val ring = ring(2)                       // 8 bytes
        ring.write(ByteArray(12), 0, 12) shouldBe 8
        ring.overruns shouldBe 4L
        ring.free() shouldBe 0
    }

    @Test
    fun `an empty ring hands back silence and counts the underrun`() {
        val ring = ring(4)
        ring.write(bytes(1, 2, 3, 4), 0, 4) shouldBe 4

        val dst = ByteArray(12) { 0x7F }
        // Real audio first, then zeros -- not a short return. A device callback
        // plays whatever it is handed, and leaving the tail as the caller's old
        // contents would replay a fragment.
        ring.read(dst, 0, 12) shouldBe 4
        dst.take(4) shouldBe listOf<Byte>(1, 2, 3, 4)
        dst.drop(4).all { it == 0.toByte() } shouldBe true
        ring.underruns shouldBe 8L
    }

    @Test
    fun `a partial accept is rounded down to a whole frame`() {
        val ring = ring(3)                       // 12 bytes
        ring.write(ByteArray(10), 0, 10) shouldBe 8   // 10 bytes is 2 frames plus 2
        ring.overruns shouldBe 2L
    }

    @Test
    fun `clear drops the contents`() {
        val ring = ring(4)
        ring.write(ByteArray(8), 0, 8)
        ring.available() shouldBe 8
        ring.clear()
        ring.available() shouldBe 0
    }

    @Test
    fun `writeFully parks until the consumer drains`() {
        val ring = ring(2)                       // 8 bytes
        val started = CountDownLatch(1)
        val done = AtomicBoolean(false)

        val writer = Thread({
            started.countDown()
            ring.writeFully(ByteArray(16), 0, 16)
            done.set(true)
        }, "ring-writer")
        writer.isDaemon = true
        writer.start()

        started.await(2, TimeUnit.SECONDS) shouldBe true
        Thread.sleep(150)
        // This is the pacing: the producer is held by the consumer's rate, not
        // by a timer, so it cannot run ahead of the device.
        done.get() shouldBe false

        ring.read(ByteArray(8), 0, 8)
        ring.read(ByteArray(8), 0, 8)
        writer.join(2_000)
        done.get() shouldBe true
    }

    @Test
    fun `writeFully gives up at its deadline`() {
        val ring = ring(2)
        val start = System.nanoTime()
        ring.writeFully(ByteArray(16), 0, 16, timeoutNanos = 150_000_000L) shouldBe false
        val elapsedMillis = (System.nanoTime() - start) / 1_000_000
        (elapsedMillis >= 100) shouldBe true
    }

    @Test
    fun `close releases a parked writer`() {
        // The rule that makes AudioSink.close able to unblock a write: the
        // parked thread cannot free itself, so something else has to.
        val ring = ring(2)
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val result = AtomicBoolean(true)

        val writer = Thread({
            started.countDown()
            result.set(ring.writeFully(ByteArray(64), 0, 64))
            finished.countDown()
        }, "ring-writer")
        writer.isDaemon = true
        writer.start()

        started.await(2, TimeUnit.SECONDS) shouldBe true
        Thread.sleep(150)
        ring.close()

        finished.await(2, TimeUnit.SECONDS) shouldBe true
        result.get() shouldBe false
    }

    @Test
    fun `a closed ring accepts nothing further`() {
        val ring = ring(4)
        ring.close()
        ring.write(ByteArray(4), 0, 4) shouldBe 0
        ring.isClosed() shouldBe true
    }

    @Test
    fun `a capacity that is not whole frames is refused`() {
        assertThrows<IllegalArgumentException> { PcmRingBuffer(10, 4) }
        assertThrows<IllegalArgumentException> { PcmRingBuffer(0, 4) }
        assertThrows<IllegalArgumentException> { PcmRingBuffer(16, 0) }
    }

    @Test
    fun `a read of a partial frame is refused`() {
        val ring = ring(4)
        assertThrows<IllegalArgumentException> { ring.read(ByteArray(8), 0, 5) }
    }

    @Test
    fun `a range outside the array is refused`() {
        val ring = ring(4)
        assertThrows<IllegalArgumentException> { ring.write(ByteArray(4), 0, 8) }
        assertThrows<IllegalArgumentException> { ring.write(ByteArray(4), -1, 4) }
    }
}
