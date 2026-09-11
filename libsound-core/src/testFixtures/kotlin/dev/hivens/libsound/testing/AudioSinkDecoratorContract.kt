package dev.hivens.libsound.testing

import dev.hivens.libsound.AudioFormat
import dev.hivens.libsound.AudioSink
import dev.hivens.libsound.Capability
import dev.hivens.libsound.PcmEncoding
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * What a sink that wraps another sink owes, as executable assertions.
 *
 * Processing is a decorator: a gain, a filter, a limiter, each taking frames
 * and handing them on. That works because [AudioSink] is an interface, and it
 * is a trap without this suite, because every rule a wrapper can break is one
 * a consumer only discovers as a stall, a busy loop or video drifting away
 * from audio.
 *
 * Extend it, wrap [device], and a decorator that returns early or reports
 * frames nobody heard fails here instead of in somebody's synchronisation
 * three phases later. The rules themselves, and the reason each exists, are in
 * [AudioSink]'s documentation.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
public abstract class AudioSinkDecoratorContract {

    /**
     * The sink under the decorator, and the device the suite drives.
     *
     * A fake rather than a mock: a write has to be able to park against a full
     * buffer, which is the whole of what rule one is about, and a mock that
     * accepted everything would make the rule unfalsifiable.
     */
    protected val device: FakeAudioSink = FakeAudioSink(bufferFrames = DEVICE_FRAMES)

    /** Wrap [inner]. Called once per test. */
    protected abstract fun decorate(inner: AudioSink): AudioSink

    protected open val format: AudioFormat = AudioFormat(48_000, 2)

    /**
     * What the decorator should report when the device has played
     * [innerFrames].
     *
     * The identity by default, which is rule two. A decorator that changes the
     * frame count and scales positions back into the consumer's frames
     * overrides this with its ratio. One that cannot scale withholds
     * [Capability.DEVICE_POSITION] instead, and this is never consulted.
     */
    protected open fun expectedPosition(innerFrames: Long): Long = innerFrames

    private fun frames(count: Int): ByteArray = ByteArray(count * format.bytesPerFrame)

    @Test
    public fun `open reaches the sink it wraps`() {
        decorate(device).use { sink ->
            sink.open(format)
            device.isOpen shouldBe true
            device.opens shouldBe 1
        }
    }

    @Test
    public fun `what it accepts is what the sink underneath accepts`() {
        // A decorator changes the samples, not the shapes a device will take.
        // One that answered for itself would either promise a format the device
        // refuses, or hide one it would have played.
        decorate(device).use { sink ->
            sink.acceptedEncodings shouldBe device.acceptedEncodings
            for (encoding in PcmEncoding.entries) {
                val shape = AudioFormat(format.sampleRate, format.channels, encoding)
                sink.accepts(shape) shouldBe device.accepts(shape)
            }
        }
    }

    @Test
    public fun `write blocks until the wrapped sink has taken the audio`() {
        // Rule one, and the one that costs a consumer the most when it is
        // broken: a decorator that buffers and returns turns a decode loop into
        // a busy loop and makes a stall watchdog fire during healthy playback.
        val sink = decorate(device)
        sink.use {
            sink.open(format)
            device.stop()

            val finished = CountDownLatch(1)
            val writer = Thread({
                runCatching { sink.write(frames(DEVICE_FRAMES * 4), 0, DEVICE_FRAMES * 4 * format.bytesPerFrame) }
                finished.countDown()
            }, "decorator-contract-writer")
            writer.isDaemon = true
            writer.start()

            // Four times what the device holds, against a device that is not
            // draining: nothing can legitimately have returned yet.
            finished.await(500, TimeUnit.MILLISECONDS) shouldBe false

            device.start()
            val drainer = Thread({
                repeat(200) {
                    device.consumeAll()
                    Thread.sleep(5)
                }
            }, "decorator-contract-drainer")
            drainer.isDaemon = true
            drainer.start()

            finished.await(20, TimeUnit.SECONDS) shouldBe true
        }
    }

    @Test
    public fun `the position is the wrapped sink's`() {
        // Rule two. A decorator has played nothing, so counting its own buffered
        // frames would report audio nobody has heard, which a clock reads as
        // audio running ahead of video.
        decorate(device).use { sink ->
            sink.open(format)
            // Comfortably inside what the device holds: nothing is draining it
            // here, and a write larger than the buffer would park rather than
            // reach the assertion.
            val written = DEVICE_FRAMES / 4
            sink.write(frames(written), 0, written * format.bytesPerFrame)
            device.consume(written.toLong() / 2)

            if (Capability.DEVICE_POSITION in sink.capabilities) {
                sink.framePosition() shouldBe expectedPosition(device.framePosition())
            }
        }
    }

    @Test
    public fun `flush drops the decorator's own buffer too`() {
        // Rule four. A seek that leaves stale samples in a filter plays the old
        // position for as long as the filter is deep.
        decorate(device).use { sink ->
            sink.open(format)
            val chunk = format.sampleRate / 50
            sink.write(frames(chunk), 0, chunk * format.bytesPerFrame)
            sink.stop()
            sink.flush()

            device.flushes shouldBe 1
            device.bufferedFrames() shouldBe 0L
            // Nothing is queued anywhere: the device was emptied, and anything
            // the decorator was holding went with it.
            sink.latencyNanos() shouldBe 0L
        }
    }

    @Test
    public fun `latency covers the decorator's own buffer as well`() {
        decorate(device).use { sink ->
            sink.open(format)
            val chunk = format.sampleRate / 50
            sink.write(frames(chunk), 0, chunk * format.bytesPerFrame)
            // A filter that hides its own depth makes every consumer's
            // synchronisation wrong by exactly that much.
            (sink.latencyNanos() >= device.latencyNanos()) shouldBe true
        }
    }

    @Test
    public fun `what it will take is never less than the wrapped sink will take`() {
        // The other end of the latency rule. A decorator that reported less than
        // the device would send a consumer polling it away while the device was
        // waiting for audio, which is a stall the consumer cannot see the cause
        // of. One that holds frames may report its own room on top; one that
        // holds none reports the device's unchanged.
        decorate(device).use { sink ->
            sink.open(format)
            (sink.writableFrames() >= device.writableFrames()) shouldBe true

            val chunk = format.sampleRate / 50
            sink.write(frames(chunk), 0, chunk * format.bytesPerFrame)
            (sink.writableFrames() >= device.writableFrames()) shouldBe true

            // And filling the device is reported as no room, so a consumer is
            // told to come back rather than told to write into a full one.
            device.stop()
            val remaining = device.writableFrames().toInt()
            if (remaining > 0) sink.write(frames(remaining), 0, remaining * format.bytesPerFrame)
            device.writableFrames() shouldBe 0L
        }
    }

    @Test
    public fun `close closes the sink it wraps`() {
        val sink = decorate(device)
        sink.open(format)
        sink.close()
        device.isOpen shouldBe false
        // And twice, because a consumer closing a decorator it already closed is
        // a mistake that must not become a crash.
        sink.close()
    }

    private companion object {
        /** Small enough that a write of four times it certainly parks. */
        const val DEVICE_FRAMES = 4_800
    }
}
