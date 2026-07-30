package dev.hivens.libsound.testing

import dev.hivens.libsound.AudioFormat
import dev.hivens.libsound.AudioSink
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The behaviours every [AudioSink] has to have, as executable assertions.
 *
 * The interface's KDoc explains why each rule exists; this is where a backend
 * finds out whether it actually follows them. Extend it, hand back a sink, and
 * a backend that breaks the seek handshake fails here instead of in a
 * consumer's video sync three phases later.
 *
 * The assertions are deliberately qualitative -- froze, advanced, reset,
 * unblocked -- because a real device cannot be driven frame by frame. Anything
 * that needs exact counts belongs in the tests of a sink that can be driven
 * exactly, not in a suite a hardware backend also has to pass.
 */
public abstract class AudioSinkContract {

    /** A fresh, unopened sink. */
    protected abstract fun newSink(): AudioSink

    /** The format the suite opens with. Override if a backend cannot take this one. */
    protected open val format: AudioFormat = AudioFormat(48_000, 2)

    /**
     * Let the device play about [frames] frames.
     *
     * The default waits in real time, which is all a hardware backend can offer.
     * A sink whose playhead is under test control overrides this and moves it
     * exactly, which is what makes the suite deterministic where it can be.
     */
    protected open fun advance(sink: AudioSink, frames: Long) {
        Thread.sleep(format.nanosFor(frames) / 1_000_000 + REAL_TIME_SLACK_MILLIS)
    }

    /** Silence, [frames] long, in the suite's format. */
    protected fun frames(count: Int): ByteArray = ByteArray(count * format.bytesPerFrame)

    @Test
    public fun `frame position is zero on a freshly opened sink`() {
        newSink().use { sink ->
            sink.open(format)
            sink.framePosition() shouldBe 0L
        }
    }

    @Test
    public fun `open leaves the device running`() {
        // No start() anywhere in this test: if open only prepared the device,
        // the position would never move and a consumer would wait forever.
        newSink().use { sink ->
            sink.open(format)
            sink.write(frames(480), 0, 480 * format.bytesPerFrame)
            advance(sink, 480)
            sink.framePosition() shouldBeGreaterThan 0L
        }
    }

    @Test
    public fun `open resets the frame position`() {
        newSink().use { sink ->
            sink.open(format)
            sink.write(frames(960), 0, 960 * format.bytesPerFrame)
            advance(sink, 960)
            sink.framePosition() shouldBeGreaterThan 0L

            // A track switch reopens, and a clock re-anchors against the fresh
            // stream assuming it counts from zero.
            sink.open(format)
            sink.framePosition() shouldBe 0L
        }
    }

    @Test
    public fun `stop freezes the frame position and start resumes it`() {
        newSink().use { sink ->
            sink.open(format)
            sink.write(frames(960), 0, 960 * format.bytesPerFrame)
            advance(sink, 480)

            sink.stop()
            val frozen = sink.framePosition()
            advance(sink, 480)
            // The seek handshake freezes first and reads second; a position that
            // keeps moving here steps a mastered clock backward later.
            sink.framePosition() shouldBe frozen

            sink.start()
            advance(sink, 480)
            sink.framePosition() shouldBeGreaterThan frozen
        }
    }

    @Test
    public fun `flush is valid while stopped and discards the tail`() {
        newSink().use { sink ->
            sink.open(format)
            sink.write(frames(960), 0, 960 * format.bytesPerFrame)
            sink.stop()
            sink.flush()
            val afterFlush = sink.framePosition()

            sink.start()
            advance(sink, 960)
            // The discarded frames must not turn up in the position: a seek
            // reads the playhead right after this, and counting the dropped
            // tail lands the anchor past where sound will actually resume.
            sink.framePosition() shouldBe afterFlush
        }
    }

    @Test
    public fun `close is idempotent and does not throw`() {
        val sink = newSink()
        sink.open(format)
        sink.close()
        sink.close()
        sink.isOpen shouldBe false
    }

    @Test
    public fun `close unblocks a write in flight`() {
        // The device-death case. A write parked on a device that will never
        // drain cannot free itself, and the watchdog's only lever is close().
        val sink = newSink()
        sink.open(format)
        sink.stop()

        val entered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val thrown = AtomicReference<Throwable?>()
        val writer = Thread({
            try {
                entered.countDown()
                // Far more than any plausible device buffer, against a stopped
                // device: this cannot complete on its own.
                val huge = frames(format.sampleRate * 10)
                sink.write(huge, 0, huge.size)
            } catch (t: Throwable) {
                thrown.set(t)
            } finally {
                finished.countDown()
            }
        }, "contract-blocked-writer")
        writer.isDaemon = true
        writer.start()

        entered.await(2, TimeUnit.SECONDS) shouldBe true
        Thread.sleep(200)   // let it reach the park
        sink.close()

        finished.await(5, TimeUnit.SECONDS) shouldBe true
        writer.join(1_000)
        writer.isAlive shouldBe false
    }

    @Test
    public fun `volume is clamped rather than rejected`() {
        newSink().use { sink ->
            sink.open(format)
            sink.setVolume(-1f)
            sink.volume() shouldBe 0f
            sink.setVolume(4f)
            sink.volume() shouldBe 1f
            sink.setVolume(0.5f)
            sink.volume() shouldBe 0.5f
        }
    }

    @Test
    public fun `a partial frame is rejected`() {
        newSink().use { sink ->
            sink.open(format)
            // Half a frame would shift every channel after it; better a loud
            // failure at the call site than swapped stereo for the rest of the
            // stream.
            assertThrows<IllegalArgumentException> {
                sink.write(frames(4), 0, format.bytesPerFrame + 1)
            }
        }
    }

    private companion object {
        /** Slack over the nominal duration, so a loaded runner still drains. */
        const val REAL_TIME_SLACK_MILLIS = 50L
    }
}
