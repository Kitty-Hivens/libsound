package dev.hivens.libsound

import dev.hivens.libsound.testing.AudioSourceContract
import dev.hivens.libsound.testing.FakeAudioSource
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The capture contract against the fake.
 *
 * Two jobs, exactly as on the output side: it proves the fake is a legitimate
 * stand-in for a microphone, and it proves the suite is satisfiable at all. A
 * contract nothing passes is a contract that gets quietly relaxed the first
 * time a real backend fails it.
 */
class FakeAudioSourceContractTest : AudioSourceContract() {

    // A second of buffer. The suite lets the device produce a quarter of a
    // second at a time while a reader drains it, and a default-sized fake would
    // drop most of that as an overrun rather than handing it over.
    override fun newSource() = FakeAudioSource(bufferFrames = 48_000)

    // Production is under test control here, so the suite runs deterministically
    // rather than against a sleep.
    override fun advance(source: AudioSource, frames: Long) {
        (source as FakeAudioSource).produce(frames)
    }
}

/**
 * What the fake can assert that a real microphone cannot: exact counts, and a
 * flush whose effect is measured rather than inferred.
 */
class FakeAudioSourceTest {

    private val format = AudioFormat(48_000)

    @Test
    fun `the position counts exactly what was captured`() {
        FakeAudioSource().use { source ->
            source.open(format)
            source.produce(200)
            source.framePosition() shouldBe 200L
            source.produce(280)
            source.framePosition() shouldBe 480L
        }
    }

    @Test
    fun `a read hands back what the device produced, in order`() {
        FakeAudioSource().use { source ->
            source.open(format)
            val spoken = ByteArray(8 * format.bytesPerFrame) { it.toByte() }
            source.produce(spoken)

            val heard = ByteArray(spoken.size)
            source.read(heard, 0, heard.size)
            heard.toList() shouldBe spoken.toList()
        }
    }

    @Test
    fun `flush discards what was captured and not read`() {
        // The rule the shared suite cannot assert against hardware, because a
        // real source reports the device's own latency whether or not anything
        // is queued behind it. Here the buffer is the whole of the latency, so
        // the discard is visible.
        FakeAudioSource().use { source ->
            source.open(format)
            source.produce(400)
            source.bufferedFrames() shouldBe 400L
            source.stop()
            source.flush()
            source.bufferedFrames() shouldBe 0L
            source.latencyNanos() shouldBe 0L
            // The position is the take, not the buffer: flushing unread audio
            // does not un-capture it.
            source.framePosition() shouldBe 400L
        }
    }

    @Test
    fun `frames nobody collected are counted rather than dropped in silence`() {
        FakeAudioSource(bufferFrames = 100).use { source ->
            source.open(format)
            source.produce(250)
            source.bufferedFrames() shouldBe 100L
            source.overrunFrames() shouldBe 150L
        }
    }

    @Test
    fun `a stopped device captures nothing`() {
        FakeAudioSource().use { source ->
            source.open(format)
            source.produce(100)
            source.stop()
            source.produce(100)
            source.framePosition() shouldBe 100L
            source.start()
            source.produce(100)
            source.framePosition() shouldBe 200L
        }
    }

    @Test
    fun `a reopen drops the previous take`() {
        FakeAudioSource().use { source ->
            source.open(format)
            source.produce(400)
            source.bufferedFrames() shouldBe 400L

            source.open(AudioFormat(44_100))
            source.bufferedFrames() shouldBe 0L
            source.framePosition() shouldBe 0L
            source.overrunFrames() shouldBe 0L
            source.opens shouldBe 2
        }
    }

    @Test
    fun `a read against a stopped device is recorded rather than silently allowed`() {
        // Reading while stopped is how a recorder deadlocks itself: nothing is
        // produced, so a read larger than what is buffered never returns.
        FakeAudioSource().use { source ->
            source.open(format)
            source.produce(100)
            source.stop()
            source.read(ByteArray(50 * format.bytesPerFrame), 0, 50 * format.bytesPerFrame)
            source.readsWhileStopped shouldBe 1
        }
    }
}
