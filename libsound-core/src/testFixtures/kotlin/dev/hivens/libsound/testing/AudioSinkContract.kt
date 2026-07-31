package dev.hivens.libsound.testing

import dev.hivens.libsound.AudioFormat
import dev.hivens.libsound.AudioSink
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
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
 *
 * Writes are half a second at a time, which is not arbitrary. A real server
 * will not start playing until its prebuffer is met, and that defaults to the
 * whole target buffer; a suite that wrote a few milliseconds and then asked
 * whether the device had moved would be asking about a device that had
 * correctly not started yet.
 */
// SEPARATE_THREAD is the whole point. JUnit's default thread mode resolves to
// SAME_THREAD, where the timeout does not interrupt anything -- it lets the
// method run to completion and only then compares elapsed time. A write parked
// in pa_threaded_mainloop_wait would have hung the build to the runner's own
// limit while this annotation sat above it looking like protection.
@Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
public abstract class AudioSinkContract {

    /**
     * A fresh, unopened sink.
     *
     * Its buffer has to hold at least [writeHalfSecond]'s worth, or the very
     * first write parks against a device nothing is draining. That is not a
     * hypothetical: it is how this suite first met its own class timeout, which
     * is why the timeout is on the class at all. A blocking write with no
     * deadline turns a broken backend into a hung build rather than a red one,
     * and a hung build on CI is a wasted hour instead of a diagnosis.
     */
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

    /** Silence, [count] frames long, in the suite's format. */
    protected fun frames(count: Int): ByteArray = ByteArray(count * format.bytesPerFrame)

    /** Write half a second of silence -- comfortably past any plausible prebuffer. */
    protected fun writeHalfSecond(sink: AudioSink) {
        val half = frames(format.sampleRate / 2)
        sink.write(half, 0, half.size)
    }

    private fun halfSecondFrames(): Long = (format.sampleRate / 2).toLong()

    @Test
    public fun `frame position is zero on a freshly opened sink`() {
        newSink().use { sink ->
            sink.open(format)
            sink.framePosition() shouldBe 0L
        }
    }

    @Test
    public fun `open leaves the device running`() {
        // No start() anywhere in this test. If open only prepared the device,
        // the blocking write would never drain and the position would never
        // move -- a consumer would wait forever on a sink that looks healthy.
        newSink().use { sink ->
            sink.open(format)
            writeHalfSecond(sink)
            advance(sink, halfSecondFrames() / 2)
            sink.framePosition() shouldBeGreaterThan 0L
        }
    }

    @Test
    public fun `open resets the frame position`() {
        newSink().use { sink ->
            sink.open(format)
            writeHalfSecond(sink)
            advance(sink, halfSecondFrames() / 2)
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
            writeHalfSecond(sink)
            advance(sink, halfSecondFrames() / 2)

            // The playhead has to be MOVING before the freeze means anything.
            // The first cut froze a position that was still zero and then
            // asserted it stayed zero, which every backend passes, including one
            // that ignores stop() entirely.
            val moving = sink.framePosition()
            moving shouldBeGreaterThan 0L

            sink.stop()
            val frozen = sink.framePosition()
            advance(sink, halfSecondFrames() / 2)
            // The seek handshake freezes first and reads second; a position that
            // keeps moving here steps a mastered clock backward later.
            //
            // A tolerance, not equality: cork is asynchronous on a real server,
            // so a few milliseconds can still reach the speaker between stop()
            // returning and the read below.
            val drift = sink.framePosition() - frozen
            (drift in 0..(format.sampleRate / 20).toLong()) shouldBe true

            sink.start()
            // The resume is followed by a write, because that is what a
            // consumer does and because one backend's playhead only advances
            // while writes are flowing. Asserting that start() alone moves it
            // would be asserting something no consumer depends on and one
            // backend cannot provide.
            writeHalfSecond(sink)
            advance(sink, halfSecondFrames() / 2)
            sink.framePosition() shouldBeGreaterThan frozen
        }
    }

    @Test
    public fun `flush is valid while stopped and discards the tail`() {
        newSink().use { sink ->
            sink.open(format)
            writeHalfSecond(sink)
            sink.stop()
            // A baseline, so the zero below means "emptied" and not "was never
            // filled": a sink that buffers nothing would pass the latency
            // assertion without a flush ever doing anything.
            sink.latencyNanos() shouldBeGreaterThan 0L
            sink.flush()
            val afterFlush = sink.framePosition()

            // Latency is what actually proves the discard. A position delta
            // cannot: whatever a flush credits to the playhead is constant
            // afterwards, so it cancels out of any difference -- the same
            // reason skinema's clock is immune to the JavaSound flush jump.
            // What is buffered, on the other hand, is either gone or it is not.
            sink.latencyNanos() shouldBe 0L

            // And the device runs again afterwards, fed continuously, because
            // one backend's playhead only advances while writes are flowing.
            sink.start()
            val chunk = frames(format.sampleRate / 50)
            var fed = 0L
            repeat(25) {
                sink.write(chunk, 0, chunk.size)
                fed += format.sampleRate / 50
            }
            advance(sink, fed)

            val moved = sink.framePosition() - afterFlush
            (moved > fed * 0.4) shouldBe true
            (moved < fed * 1.8) shouldBe true
        }
    }

    @Test
    public fun `the playhead never runs past what was written`() {
        // The one assertion that separates a device-derived playhead from an
        // arbitrary extrapolation. Without it nothing in this suite validates
        // the DEVICE_POSITION claim: a sink reporting wall-clock time passes
        // every other test here.
        newSink().use { sink ->
            sink.open(format)
            val written = format.sampleRate / 2
            val data = frames(written)
            sink.write(data, 0, data.size)
            advance(sink, written.toLong())
            advance(sink, written.toLong())

            val position = sink.framePosition()
            (position <= written.toLong()) shouldBe true
            // And it did get most of the way there, so a sink that always
            // answers zero does not pass by being trivially under the bound.
            (position > written * 0.5) shouldBe true
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
                // Returning or throwing are both acceptable ways to come back;
                // staying parked is not.
                thrown.set(t)
            } finally {
                finished.countDown()
            }
        }, "contract-blocked-writer")
        writer.isDaemon = true
        writer.start()

        entered.await(2, TimeUnit.SECONDS) shouldBe true
        Thread.sleep(500)   // let it fill the buffer and reach the park
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
        const val REAL_TIME_SLACK_MILLIS = 150L
    }
}
