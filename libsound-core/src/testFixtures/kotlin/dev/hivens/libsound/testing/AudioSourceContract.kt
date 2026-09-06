package dev.hivens.libsound.testing

import dev.hivens.libsound.AudioFormat
import dev.hivens.libsound.AudioSource
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The behaviours every [AudioSource] has to have, as executable assertions.
 *
 * The mirror of [AudioSinkContract], and written to look like it on purpose: a
 * consumer that has learned the output side should recognise every rule here,
 * and a backend that implements both should be able to read the two suites
 * side by side.
 *
 * The assertions are qualitative for the same reason the sink's are. A real
 * microphone cannot be driven frame by frame, so what is asserted is froze,
 * advanced, reset, unblocked. Anything needing exact counts belongs in the
 * tests of a source that can be driven exactly, which is [FakeAudioSource].
 *
 * One rule is deliberately weaker here than its sink counterpart. The sink
 * suite proves a flush by watching buffered latency fall to zero; a source
 * cannot, because [AudioSource.latencyNanos] reports the whole path and a
 * device's own latency does not go away when its buffer is emptied. So this
 * suite asserts that a flush is legal where a seek makes it and that the
 * device keeps working afterwards, and the discard itself is asserted against
 * the fake, where it can be measured rather than inferred.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
public abstract class AudioSourceContract {

    /** A fresh, unopened source. */
    protected abstract fun newSource(): AudioSource

    /** The format the suite opens with. Override if a backend cannot take this one. */
    protected open val format: AudioFormat = AudioFormat(48_000, 2)

    /**
     * Let the device capture about [frames] frames.
     *
     * The default waits in real time, which is all a microphone can offer. A
     * source whose production is under test control overrides this and produces
     * exactly, which is what makes the suite deterministic where it can be.
     */
    protected open fun advance(source: AudioSource, frames: Long) {
        Thread.sleep(format.nanosFor(frames) / 1_000_000 + REAL_TIME_SLACK_MILLIS)
    }

    /** A buffer [count] frames long, in the suite's format. */
    protected fun frames(count: Int): ByteArray = ByteArray(count * format.bytesPerFrame)

    /**
     * Collect a twentieth of a second, which is what makes the capture position
     * move.
     *
     * Reading rather than only waiting, because a record stream's position is
     * where the reader has got to: the server holds what nobody has collected,
     * and a suite that waited and then asked would be asking a device that is
     * running whether it had run.
     */
    protected fun readSome(source: AudioSource) {
        val chunk = frames(format.sampleRate / 20)
        source.read(chunk, 0, chunk.size)
    }

    private fun quarterSecondFrames(): Long = (format.sampleRate / 4).toLong()

    /**
     * What counts as "the count starts here" on a device that is already
     * capturing.
     *
     * Twenty milliseconds. A real source is running from the instant it opens,
     * so the position is a fraction of a millisecond past zero by the time it
     * can be read; a count carried over from an earlier take is seconds, which
     * this catches and that does not.
     */
    private fun freshStartFrames(): Long = format.framesFor(20_000_000L)

    @Test
    public fun `the frame position starts from zero on a freshly opened source`() {
        newSource().use { source ->
            source.open(format)
            (source.framePosition() <= freshStartFrames()) shouldBe true
        }
    }

    @Test
    public fun `the source accepts CD stereo, whatever else it prefers`() {
        val cd = AudioFormat.CD_STEREO
        newSource().use { source ->
            source.open(cd)
            source.format shouldBe cd
        }
    }

    @Test
    public fun `open leaves the device running`() {
        // No start() anywhere in this test. A source that opened idle would
        // strand a recorder waiting for frames that never come, and it would
        // look exactly like a source waiting for a quiet room.
        newSource().use { source ->
            source.open(format)
            advance(source, quarterSecondFrames())
            readSome(source)
            source.framePosition() shouldBeGreaterThan 0L
        }
    }

    @Test
    public fun `open resets the frame position`() {
        newSource().use { source ->
            source.open(format)
            advance(source, quarterSecondFrames())
            readSome(source)
            source.framePosition() shouldBeGreaterThan 0L

            source.open(format)
            (source.framePosition() <= freshStartFrames()) shouldBe true
        }
    }

    @Test
    public fun `stop freezes the frame position and start resumes it`() {
        newSource().use { source ->
            source.open(format)
            advance(source, quarterSecondFrames())
            readSome(source)

            // The position has to be MOVING before a freeze means anything: a
            // suite that froze a zero and then asserted it stayed zero would
            // pass against a source that ignores stop() entirely.
            val moving = source.framePosition()
            moving shouldBeGreaterThan 0L

            source.stop()
            val frozen = source.framePosition()
            advance(source, quarterSecondFrames())
            // A tolerance rather than equality: cork is asynchronous on a real
            // server, so a few milliseconds can still be captured between stop()
            // returning and the read below.
            val drift = source.framePosition() - frozen
            (drift in 0..(format.sampleRate / 20).toLong()) shouldBe true

            source.start()
            advance(source, quarterSecondFrames())
            readSome(source)
            source.framePosition() shouldBeGreaterThan frozen
        }
    }

    @Test
    public fun `flush is valid while stopped and the device runs afterwards`() {
        newSource().use { source ->
            source.open(format)
            advance(source, quarterSecondFrames())
            source.stop()
            source.flush()

            source.start()
            advance(source, quarterSecondFrames())
            val chunk = frames(format.sampleRate / 50)
            source.read(chunk, 0, chunk.size)
        }
    }

    @Test
    public fun `read blocks until the device has produced the frames`() {
        // The pacing rule, and the mirror of the sink's blocking write: the call
        // returning is what tells a recorder that time has passed. A source that
        // answered immediately with whatever it had would turn a recording loop
        // into a busy loop.
        newSource().use { source ->
            source.open(format)
            val quarter = frames(format.sampleRate / 4)
            val done = CountDownLatch(1)
            val reader = Thread({
                runCatching { source.read(quarter, 0, quarter.size) }
                done.countDown()
            }, "contract-source-reader")
            reader.isDaemon = true
            reader.start()

            // Nothing has been produced yet, so a quarter second cannot be
            // filled however fast the implementation is.
            done.await(50, TimeUnit.MILLISECONDS) shouldBe false

            advance(source, quarterSecondFrames())
            done.await(30, TimeUnit.SECONDS) shouldBe true
        }
    }

    @Test
    public fun `close unblocks a read in flight`() {
        // The device-death case. A read parked against an unplugged interface
        // cannot free itself, and the watchdog's only lever is close().
        val source = newSource()
        source.open(format)
        source.stop()

        val entered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val thrown = AtomicReference<Throwable?>()
        val reader = Thread({
            try {
                entered.countDown()
                // Far more than any plausible device buffer, against a stopped
                // device: this cannot complete on its own.
                val huge = frames(format.sampleRate * 10)
                source.read(huge, 0, huge.size)
            } catch (t: Throwable) {
                // Returning or throwing are both acceptable ways to come back;
                // staying parked is not.
                thrown.set(t)
            } finally {
                finished.countDown()
            }
        }, "contract-blocked-reader")
        reader.isDaemon = true
        reader.start()

        entered.await(2, TimeUnit.SECONDS) shouldBe true
        Thread.sleep(500)
        source.close()

        finished.await(5, TimeUnit.SECONDS) shouldBe true
        reader.join(1_000)
        reader.isAlive shouldBe false
    }

    @Test
    public fun `close is idempotent and does not throw`() {
        val source = newSource()
        source.open(format)
        source.close()
        source.close()
        source.isOpen shouldBe false
    }

    @Test
    public fun `overruns are counted rather than thrown`() {
        // A slow consumer is an ordinary condition on a shared machine. What
        // would be a failure is losing frames without saying so, which is why
        // the count exists at all and why it is readable before anything has
        // gone wrong.
        newSource().use { source ->
            source.open(format)
            source.overrunFrames() shouldBe 0L
            advance(source, quarterSecondFrames())
            readSome(source)
            (source.overrunFrames() >= 0L) shouldBe true
        }
    }

    @Test
    public fun `volume is clamped rather than rejected`() {
        newSource().use { source ->
            source.open(format)
            source.setVolume(-1f)
            source.volume() shouldBe 0f
            source.setVolume(4f)
            source.volume() shouldBe 1f
            source.setVolume(0.5f)
            source.volume() shouldBe 0.5f
        }
    }

    @Test
    public fun `a partial frame is rejected`() {
        newSource().use { source ->
            source.open(format)
            // Half a frame would shift every channel after it, and a recording
            // with the channels swapped from one point on is worse than a loud
            // failure at the call site.
            assertThrows<IllegalArgumentException> {
                source.read(frames(4), 0, format.bytesPerFrame + 1)
            }
        }
    }

    private companion object {
        /** Slack over the nominal duration, so a loaded runner still captures. */
        const val REAL_TIME_SLACK_MILLIS = 150L
    }
}
