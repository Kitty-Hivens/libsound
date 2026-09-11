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
 * ## What a float costs the wide formats
 *
 * Samples are carried through [process] as floats, whose mantissa holds 24
 * bits. That is exact for U8, S16LE and F32LE, and exact for the case S32LE
 * actually carries, which is 24-bit content in the top bits of a 32-bit sample,
 * because FFmpeg has no 24-bit format to send it in. It is lossy for a stream
 * that genuinely uses all 32, and for F64LE. [AudioFormat.significantBits] is
 * what says which of the two a stream is, and a consumer that needs the bottom
 * bits kept should not put a decorator in that path.
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

    /**
     * The wrapped sink's. A decorator changes the samples, not the shapes the
     * device will take, and [decode] covers every encoding there is.
     */
    override val acceptedEncodings: Set<PcmEncoding> get() = inner.acceptedEncodings

    /** The wrapped sink's, for the reason [acceptedEncodings] is. */
    override fun accepts(format: AudioFormat): Boolean = inner.accepts(format)

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
            // Unsigned and offset by half its range, which is the whole of what
            // makes U8 different from the signed formats below it.
            PcmEncoding.U8 -> for (index in 0 until samples) {
                scratch[index] = ((buffer.get().toInt() and 0xFF) - 128) / 128f
            }
            // A power of two rather than the largest value, so the most
            // negative sample maps to exactly -1 and the scale is exact in both
            // directions. It costs the positive full scale a fraction of a bit.
            PcmEncoding.S16LE -> for (index in 0 until samples) {
                scratch[index] = buffer.short / 32768f
            }
            PcmEncoding.S32LE -> for (index in 0 until samples) {
                scratch[index] = (buffer.int / 2147483648.0).toFloat()
            }
            PcmEncoding.F32LE -> for (index in 0 until samples) {
                scratch[index] = buffer.float
            }
            PcmEncoding.F64LE -> for (index in 0 until samples) {
                scratch[index] = buffer.double.toFloat()
            }
        }
    }

    private fun encode(format: AudioFormat, samples: Int) {
        val buffer = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN)
        // Clamped throughout, because an integer format has no room above full
        // scale and a sample that wrapped turns a loud passage into a click. A
        // consumer that wants the peaks kept puts a limiter above.
        when (format.encoding) {
            PcmEncoding.U8 -> for (index in 0 until samples) {
                val value = (scratch[index] * 128f).toInt() + 128
                buffer.put(value.coerceIn(0, 255).toByte())
            }
            PcmEncoding.S16LE -> for (index in 0 until samples) {
                val value = (scratch[index] * 32768f).toInt().coerceIn(-32768, 32767)
                buffer.putShort(value.toShort())
            }
            PcmEncoding.S32LE -> for (index in 0 until samples) {
                val value = (scratch[index].toDouble() * 2147483648.0).toLong()
                buffer.putInt(value.coerceIn(-2147483648L, 2147483647L).toInt())
            }
            PcmEncoding.F32LE -> for (index in 0 until samples) {
                buffer.putFloat(scratch[index])
            }
            PcmEncoding.F64LE -> for (index in 0 until samples) {
                buffer.putDouble(scratch[index].toDouble())
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

    /**
     * The wrapped sink's. Nothing is held here, so there is no room of this
     * decorator's own to add, and a consumer asking through a chain gets the
     * answer of the device at the bottom of it.
     */
    override fun writableFrames(): Long = inner.writableFrames()

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
