package dev.hivens.libsound

/**
 * A PCM input channel: the mirror of [AudioSink]. Same lifecycle, same rules,
 * opposite direction.
 *
 * ## The rules, and why they are the sink's rules reversed
 *
 * Every behaviour below is asserted by the contract suite that ships in this
 * module's test fixtures, the same way the output side is. A backend that
 * passes both suites can be read from and written to by the same consumer
 * without it having to learn two shapes.
 *
 * ### open() starts the device
 *
 * A consumer that wants the microphone open but not running calls [stop]
 * immediately afterwards. A source that opened idle would strand a recorder
 * waiting for frames that never come.
 *
 * ### open() starts the frame position from zero
 *
 * A recorder writing a file counts from the start of the take, and a reopen is
 * a new take.
 *
 * Not exactly zero at the instant it is read, and the difference is the device
 * rather than the bookkeeping: a source is capturing from the moment it opens,
 * so by the time the question is asked a fraction of a millisecond has already
 * gone by. What the rule forbids is a count carried over from an earlier take,
 * which is seconds rather than frames.
 *
 * ### read() blocks until the device has produced the frames
 *
 * It mirrors [AudioSink.write], which blocks until the device took every byte,
 * and for the same reason: the call returning is what tells a consumer that
 * time has passed. A recorder wants a filled buffer, not a count it has to
 * loop around. A consumer that wants the newest frames rather than all of them
 * is asking a different question, and the answer to that one is a smaller
 * buffer, not a short read.
 *
 * ### stop() freezes the position, start() resumes it
 *
 * ### flush() discards captured-but-unread audio, and is valid while stopped
 *
 * ### framePosition() need not be monotonic across a flush
 *
 * As on the output side: report what the device says rather than inventing a
 * number that looks tidier.
 *
 * ### close() unblocks a read in flight
 *
 * A read parked against a device that has gone, an unplugged interface, cannot
 * free itself, and the only thread that could is already blocked in it.
 *
 * ## Overruns are counted, never thrown
 *
 * A consumer that falls behind on a shared machine is an ordinary condition,
 * not an error. What would be a failure is losing frames silently, so
 * [overrunFrames] is always available and never zero by omission.
 *
 * ## What a capture stream is
 *
 * Opening one shows up in the desktop's privacy indicator: the row that says
 * an application is using the microphone is reading the identity in
 * [SourceConfig]. That is a reason to fill those fields in rather than a
 * reason to avoid them, and it is why they are not optional decoration here
 * any more than they are on a sink.
 */
public interface AudioSource : AutoCloseable {

    /** What this source can do. Constant for its lifetime. */
    public val capabilities: Capabilities

    /** The format currently open, or null before the first [open] and after [close]. */
    public val format: AudioFormat?

    /** True between a successful [open] and [close]. */
    public val isOpen: Boolean

    /**
     * Open the device for [format] and start it. Reopening replaces the stream
     * and restarts the frame position at zero.
     *
     * @throws AudioException when the device cannot be opened.
     */
    public fun open(format: AudioFormat)

    /**
     * Fill [length] bytes at [offset], blocking until the device has produced
     * all of them. [length] must be a whole number of frames.
     *
     * @throws AudioException when the device fails mid-read.
     */
    public fun read(dst: ByteArray, offset: Int, length: Int)

    /** Resume after [stop]. No-op when already running. */
    public fun start()

    /** Freeze the device and the frame position with it. No-op when already stopped. */
    public fun stop()

    /** Discard captured-but-unread audio. Valid while stopped. */
    public fun flush()

    /** Sample frames the device has captured since [open]. Frozen while stopped. */
    public fun framePosition(): Long

    /**
     * How far behind the microphone the read head is, in nanoseconds. The whole
     * path, device included, not just what is queued here. Zero when the
     * backend cannot tell, which is itself information rather than an error.
     */
    public fun latencyNanos(): Long

    /**
     * Frames the device produced and nobody read, because a consumer fell
     * behind. Monotonic within one open, and zero where the backend cannot
     * count them.
     */
    public fun overrunFrames(): Long

    /**
     * Linear volume in 0..1, applied to the capture stream at the system level
     * where [Capability.STREAM_VOLUME] is present. Clamped rather than
     * rejected.
     */
    public fun setVolume(volume: Float)

    /** The volume last set, clamped. Defaults to 1.0. */
    public fun volume(): Float

    /**
     * Release the device. Idempotent, never throws, and unblocks any [read]
     * currently in flight.
     */
    override fun close()
}
