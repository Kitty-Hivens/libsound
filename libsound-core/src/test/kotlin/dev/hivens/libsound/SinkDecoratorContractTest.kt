package dev.hivens.libsound

import dev.hivens.libsound.testing.AudioSinkDecoratorContract

/**
 * The decorator rules against a decorator that follows them.
 *
 * The same two jobs the other contract tests do: it proves the suite is
 * satisfiable, and it proves the rules can be met by something with a buffer of
 * its own rather than only by a pass-through that forwards each call
 * unchanged. A processing module is the real audience, and it will hold frames.
 */
class BufferedPassThroughDecoratorTest : AudioSinkDecoratorContract() {

    override fun decorate(inner: AudioSink): AudioSink = BufferedPassThroughSink(inner, holdFrames = 240)
}

/**
 * The smallest decorator that is not trivial: it forwards every frame and
 * keeps five milliseconds of them on the way through.
 *
 * Not a processing step and not published. A filter needs a window before it
 * can produce anything, so a decorator with a buffer is the shape the rules
 * were written for, and a suite exercised only against a forwarder would not
 * notice a rule that talks about the buffer.
 */
private class BufferedPassThroughSink(
    private val inner: AudioSink,
    private val holdFrames: Int,
) : AudioSink {

    private var held = ByteArray(0)

    override val capabilities: Capabilities get() = inner.capabilities

    /** The device's, because a decorator changes samples and not what a device takes. */
    override val acceptedEncodings: Set<PcmEncoding> get() = inner.acceptedEncodings

    override fun accepts(format: AudioFormat): Boolean = inner.accepts(format)

    override val format: AudioFormat? get() = inner.format

    override val isOpen: Boolean get() = inner.isOpen

    override fun open(format: AudioFormat) {
        held = ByteArray(0)
        inner.open(format)
    }

    override fun write(data: ByteArray, offset: Int, length: Int) {
        val format = inner.format ?: throw AudioException("write before open")
        require(length % format.bytesPerFrame == 0) { "length must be whole frames" }
        held += data.copyOfRange(offset, offset + length)
        val holdBytes = holdFrames * format.bytesPerFrame
        if (held.size <= holdBytes) return
        val forward = held.size - holdBytes
        // The blocking half of rule one: what has been produced goes to the
        // device before this call returns, however much of it there is.
        inner.write(held, 0, forward)
        held = held.copyOfRange(forward, held.size)
    }

    override fun start(): Unit = inner.start()

    override fun stop(): Unit = inner.stop()

    override fun flush() {
        held = ByteArray(0)
        inner.flush()
    }

    override fun framePosition(): Long = inner.framePosition()

    override fun latencyNanos(): Long {
        val format = inner.format ?: return inner.latencyNanos()
        return inner.latencyNanos() + format.nanosFor(format.framesIn(held.size.toLong()))
    }

    override fun underrunCount(): Long = inner.underrunCount()

    override fun setVolume(volume: Float): Unit = inner.setVolume(volume)

    override fun volume(): Float = inner.volume()

    override fun close() {
        held = ByteArray(0)
        inner.close()
    }
}
