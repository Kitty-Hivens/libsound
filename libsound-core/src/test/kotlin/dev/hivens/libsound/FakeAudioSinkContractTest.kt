package dev.hivens.libsound

import dev.hivens.libsound.testing.AudioSinkContract
import dev.hivens.libsound.testing.FakeAudioSink
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The contract suite against the fake.
 *
 * Two jobs at once: it proves the fake is a legitimate stand-in for a device,
 * and it proves the suite is satisfiable at all. A contract nothing passes is a
 * contract that will be quietly relaxed the first time a real backend fails it.
 */
class FakeAudioSinkContractTest : AudioSinkContract() {

    override fun newSink() = FakeAudioSink()

    // The playhead is under test control here, so the suite runs deterministically
    // rather than against a sleep.
    override fun advance(sink: dev.hivens.libsound.AudioSink, frames: Long) {
        (sink as FakeAudioSink).consume(frames)
    }
}

/**
 * What the fake can assert that a hardware backend cannot: exact counts.
 */
class FakeAudioSinkTest {

    private val format = AudioFormat(48_000)

    @Test
    fun `the position counts exactly what was played`() {
        FakeAudioSink().use { sink ->
            sink.open(format)
            sink.write(ByteArray(480 * format.bytesPerFrame), 0, 480 * format.bytesPerFrame)
            sink.consume(200)
            sink.framePosition() shouldBe 200L
            sink.consume(280)
            sink.framePosition() shouldBe 480L
        }
    }

    @Test
    fun `latency is what is still buffered`() {
        FakeAudioSink().use { sink ->
            sink.open(format)
            sink.write(ByteArray(480 * format.bytesPerFrame), 0, 480 * format.bytesPerFrame)
            sink.latencyNanos() shouldBe format.nanosFor(480)
            sink.consume(480)
            sink.latencyNanos() shouldBe 0L
        }
    }

    @Test
    fun `a write against a stopped device is recorded rather than silently allowed`() {
        // Writing while stopped is how a pipeline deadlocks itself: nothing
        // drains, so a write larger than the buffer never returns. The probe
        // makes a consumer's test able to assert it never happens.
        FakeAudioSink(bufferFrames = 1_000).use { sink ->
            sink.open(format)
            sink.stop()
            sink.write(ByteArray(100 * format.bytesPerFrame), 0, 100 * format.bytesPerFrame)
            sink.writesWhileStopped shouldBe 1
        }
    }

    @Test
    fun `a reopen drops the previous stream`() {
        FakeAudioSink().use { sink ->
            sink.open(format)
            sink.write(ByteArray(400 * format.bytesPerFrame), 0, 400 * format.bytesPerFrame)
            sink.bufferedFrames() shouldBe 400L

            sink.open(AudioFormat(44_100))
            sink.bufferedFrames() shouldBe 0L
            sink.framePosition() shouldBe 0L
            sink.capturedBytes().size shouldBe 0
            sink.opens shouldBe 2
        }
    }

    @Test
    fun `flush discards the tail without moving the position`() {
        FakeAudioSink().use { sink ->
            sink.open(format)
            sink.write(ByteArray(400 * format.bytesPerFrame), 0, 400 * format.bytesPerFrame)
            sink.consume(100)
            sink.flush()
            sink.bufferedFrames() shouldBe 0L
            sink.framePosition() shouldBe 100L
            sink.consume(300)
            sink.framePosition() shouldBe 100L
        }
    }
}
