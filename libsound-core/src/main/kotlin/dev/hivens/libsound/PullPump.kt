package dev.hivens.libsound

import java.util.concurrent.atomic.AtomicBoolean

/**
 * A consumer that produces PCM on demand rather than pushing it.
 *
 * The mirror of [AudioSink.write]: instead of the producer deciding when to
 * hand bytes over, the sink asks. Native backends are shaped this way, so a
 * consumer written against a callback should not have to invent an adapter.
 */
public fun interface PcmSource {
    /**
     * Fill up to [length] bytes at [offset] in [dst], returning how many were
     * written, or -1 at end of stream. A short return is "nothing more right
     * now", not an error -- the caller covers the gap with silence.
     */
    public fun read(dst: ByteArray, offset: Int, length: Int): Int
}

/**
 * Drives a [PcmSource] into an [AudioSink] on a thread of its own.
 *
 * The sink's blocking write is what paces the loop, so this deliberately has no
 * timer: pulling faster than the device drains is impossible, and pulling
 * slower cannot happen while the source keeps up.
 *
 * A source that returns short does not stall the device -- the remainder of the
 * period goes out as silence and counts in [silenceFrames]. Feeding the device
 * late is the failure that produces a click; feeding it silence is the failure
 * that produces a gap, and a gap keeps the clock honest where a click does not.
 */
public class PullPump(
    private val sink: AudioSink,
    private val source: PcmSource,
    periodNanos: Long = DEFAULT_PERIOD_NANOS,
    threadName: String = "libsound-pull",
) : AutoCloseable {

    private val format: AudioFormat = requireNotNull(sink.format) {
        "the sink must be open before a pump is attached -- the period is sized from its format"
    }

    private val chunk: ByteArray = ByteArray(
        (format.bytesFor(format.framesFor(periodNanos)).toInt()).coerceAtLeast(format.bytesPerFrame),
    )

    private val running = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    @Volatile
    private var ended = false

    @Volatile
    private var silence = 0L

    /** Frames handed to the device as silence because the source came up short. */
    public val silenceFrames: Long get() = silence

    /** True once the source reported end of stream. */
    public val isEnded: Boolean get() = ended

    private val thread = Thread(::run, threadName).apply { isDaemon = true }

    /** Begin pulling. Idempotent; a pump that has ended or closed does not restart. */
    public fun start() {
        if (closed.get() || ended) return
        if (running.compareAndSet(false, true) && !thread.isAlive) thread.start()
    }

    /**
     * Stop pulling without ending the stream. The loop parks after the write in
     * flight returns, so the device keeps whatever is already queued -- call
     * [AudioSink.stop] if the point is to go quiet now.
     */
    public fun pause() {
        running.set(false)
    }

    /**
     * Stop the pump for good.
     *
     * The loop exits after the write in flight returns. A write that will never
     * return is a dead device, and freeing it means closing the sink -- which
     * belongs to whoever created it, not to the pump.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        running.set(false)
        thread.join(CLOSE_JOIN_MILLIS)
    }

    private fun run() {
        while (!closed.get()) {
            if (!running.get()) {
                Thread.sleep(IDLE_POLL_MILLIS)
                continue
            }
            val produced = try {
                source.read(chunk, 0, chunk.size)
            } catch (t: Throwable) {
                ended = true
                throw t
            }
            if (produced < 0) {
                ended = true
                running.set(false)
                continue
            }
            val aligned = produced - produced % format.bytesPerFrame
            if (aligned < chunk.size) {
                chunk.fill(0, aligned, chunk.size)
                silence += format.framesIn((chunk.size - aligned).toLong())
            }
            // The blocking write is the pacing; nothing here sleeps on a timer.
            sink.write(chunk, 0, chunk.size)
        }
    }

    public companion object {
        /**
         * 20 ms. Short enough that a pause reaches the device within a frame or
         * two of video, long enough that the per-write overhead stays noise.
         */
        public const val DEFAULT_PERIOD_NANOS: Long = 20_000_000L

        private const val IDLE_POLL_MILLIS = 10L
        private const val CLOSE_JOIN_MILLIS = 2_000L
    }
}
