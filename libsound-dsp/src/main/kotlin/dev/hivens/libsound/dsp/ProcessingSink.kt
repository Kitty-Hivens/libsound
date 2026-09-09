package dev.hivens.libsound.dsp

import dev.hivens.libsound.AudioFormat
import dev.hivens.libsound.AudioSink
import dev.hivens.libsound.Capabilities
import dev.hivens.libsound.PcmEncoding
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A sink that wraps a sink and changes the samples on the way through.
 *
 * The base every decorator in this module is built on, and the shape any
 * consumer's own should copy. `AudioSink`'s documentation states four rules a
 * wrapper owes and the fixture beside it asserts them, and three of the four
 * are satisfied here by construction rather than by care:
 *
 * **Nothing is held.** One write in, the same frames out, immediately. So the
 * write blocks exactly as long as the wrapped sink blocks, the frame count is
 * unchanged, and there is no buffer of ours for a flush to drop or for
 * [latencyNanos] to have to add. A decorator that does need to hold frames, a
 * resampler or anything with lookahead, has to override [latencyNanos] and say
 * how deep it is, and the rule it will meet first is that a filter hiding its
 * depth makes every consumer's synchronisation wrong by exactly that much.
 *
 * The fourth rule is the one that needs doing: filter state is not audio, but
 * it is the tail of audio that was, so [reset] is called on a flush. A seek
 * that leaves the previous position ringing in a filter plays it for as long as
 * the filter is deep.
 *
 * Samples arrive at [process] as floats in roughly -1 to 1, interleaved, one
 * per channel per frame. Roughly, rather than exactly, because a decorator
 * earlier in the chain may have produced values outside it: that is what
 * [LimiterSink] is for, and clamping here instead would take the decision away
 * from the consumer.
 */
public abstract class ProcessingSink(
    /** The sink underneath. Closed when this one is. */
    protected val inner: AudioSink,
) : AudioSink {

    private var scratch: FloatArray = FloatArray(0)
    private var encoded: ByteArray = ByteArray(0)
    private var closed = false

    /**
     * Change [samples] in place. [frames] frames of [channels] samples each,
     * interleaved, and the array may be longer than the two of them multiplied.
     */
    protected abstract fun process(samples: FloatArray, frames: Int, channels: Int)

    /**
     * Forget everything carried over from the audio that came before.
     *
     * Called on a flush and on an open. The default does nothing, which is
     * right for anything stateless.
     */
    protected open fun reset() {}

    /** The wrapped sink's, since a decorator changes what it can do rather than what it is. */
    override val capabilities: Capabilities get() = inner.capabilities

    /** The wrapped sink's. */
    override val format: AudioFormat? get() = inner.format

    /** The wrapped sink's. */
    override val isOpen: Boolean get() = inner.isOpen

    /** Drops whatever was carried over, then opens the sink underneath. */
    override fun open(format: AudioFormat) {
        reset()
        inner.open(format)
    }

    /**
     * Converts, calls [process], converts back, and hands the result on.
     *
     * Blocks for exactly as long as the wrapped sink blocks, because there is
     * nothing between the two.
     */
    override fun write(data: ByteArray, offset: Int, length: Int) {
        val open = inner.format
        if (open == null || length <= 0) {
            inner.write(data, offset, length)
            return
        }
        val frames = length / open.bytesPerFrame
        val samples = frames * open.channels
        if (frames == 0) {
            inner.write(data, offset, length)
            return
        }
        if (scratch.size < samples) scratch = FloatArray(samples)
        if (encoded.size < length) encoded = ByteArray(length)

        decode(data, offset, length, open, samples)
        process(scratch, frames, open.channels)
        encode(open, samples)
        // The wrapped sink is what blocks, and it blocks for the same frames it
        // would have taken unprocessed, which is rule one satisfied by there
        // being nothing between the two calls.
        inner.write(encoded, 0, length)
    }

    private fun decode(data: ByteArray, offset: Int, length: Int, format: AudioFormat, samples: Int) {
        val buffer = ByteBuffer.wrap(data, offset, length).order(ByteOrder.LITTLE_ENDIAN)
        when (format.encoding) {
            // 32768 rather than 32767, so that the most negative sample maps to
            // exactly -1 and the scale stays a power of two. It costs the
            // positive full scale a fraction of a bit and buys arithmetic that
            // is exact in both directions.
            PcmEncoding.S16LE -> for (index in 0 until samples) {
                scratch[index] = buffer.short / 32768f
            }
            PcmEncoding.F32LE -> for (index in 0 until samples) {
                scratch[index] = buffer.float
            }
        }
    }

    private fun encode(format: AudioFormat, samples: Int) {
        val buffer = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN)
        when (format.encoding) {
            // Clamped, because an integer format has no room above full scale
            // and wrapping a sample that went over turns a loud passage into a
            // click. A consumer that wants the peaks kept puts a limiter above.
            PcmEncoding.S16LE -> for (index in 0 until samples) {
                val value = (scratch[index] * 32768f).toInt().coerceIn(-32768, 32767)
                buffer.putShort(value.toShort())
            }
            PcmEncoding.F32LE -> for (index in 0 until samples) {
                buffer.putFloat(scratch[index])
            }
        }
    }

    /** Delegated. */
    override fun start() {
        inner.start()
    }

    /** Delegated. */
    override fun stop() {
        inner.stop()
    }

    /** Drops this decorator's own state, then flushes the sink underneath. */
    override fun flush() {
        // Ours first: the tail of what was playing is in the filter state, and
        // it is exactly what a seek must not carry across.
        reset()
        inner.flush()
    }

    /** The device's own count. A decorator has played nothing. */
    override fun framePosition(): Long = inner.framePosition()

    /** The wrapped sink's, because nothing here is queued on top of it. */
    override fun latencyNanos(): Long = inner.latencyNanos()

    /** The device's own count. */
    override fun underrunCount(): Long = inner.underrunCount()

    /**
     * Delegated, and not the same thing as a gain: a volume goes to the system
     * where the backend can put it there, and [GainSink] is arithmetic here.
     */
    override fun setVolume(volume: Float) {
        inner.setVolume(volume)
    }

    /** The wrapped sink's. */
    override fun volume(): Float = inner.volume()

    /** Closes the sink underneath. Idempotent, never throws. */
    override fun close() {
        if (closed) return
        closed = true
        inner.close()
    }
}
