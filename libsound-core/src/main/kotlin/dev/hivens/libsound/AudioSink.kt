package dev.hivens.libsound

/**
 * A PCM output channel: open it, write to it, and read back where the device
 * actually is.
 *
 * ## Why the contract is this long
 *
 * The signatures are the easy half. Consumers that drive an audio/video clock
 * from [framePosition] depend on a set of behaviours that no signature states,
 * and a backend can satisfy every method here while breaking synchronisation
 * in ways that look like decoder bugs. skinema learned each of these the
 * expensive way against JavaSound; they are written down so a second backend
 * does not have to.
 *
 * Every rule below is asserted by the contract suite that ships in this
 * module's test fixtures. A backend that passes it is a backend a clock can
 * ride on.
 *
 * ### open() starts the device
 *
 * Not "prepares" -- starts. A consumer that wants silence calls [stop]
 * immediately afterwards, and that is the sequence the pipeline is written
 * around. A backend that opens paused strands a consumer waiting for a
 * position that will never move.
 *
 * ### open() resets the position to zero
 *
 * A clock re-anchors by reading the fresh line at a known point and assumes it
 * counts from zero. A backend that continues an older count makes every
 * position after a reopen wrong by the length of everything played before it.
 *
 * ### write() blocks until the device has consumed the samples
 *
 * This is the pacing. There is no separate frame-rate loop on the consumer's
 * audio thread -- the write returning *is* the signal that time has passed. A
 * backend that buffers the whole write and returns immediately turns the
 * consumer's decode loop into a busy loop and makes a stall watchdog fire
 * during healthy playback. Where a ring buffer sits in the path, the blocking
 * point is "the ring has drained enough", never "the ring accepted the bytes".
 *
 * ### stop() freezes the position, start() resumes it
 *
 * A seek freezes the device first and only then reads the playhead. Reading
 * first would sample a position that the still-draining buffer is about to
 * move past, and re-anchoring a mastered clock backward is the one transition
 * a video pacer cannot absorb.
 *
 * ### flush() discards what has not been played
 *
 * Safe to call while stopped, which is where a seek calls it.
 *
 * ### framePosition() need not be monotonic across a flush
 *
 * Some backends reconcile their counters around a flush or a restart. The
 * clock above carries the monotonic clamp; a sink must not invent numbers to
 * fake monotonicity, because a fabricated position is worse than a visibly
 * jumpy one. Report what the device says.
 *
 * ### close() unblocks a write that is in flight
 *
 * A dead device is the case that matters: a blocking write against a yanked
 * USB DAC can hang without raising anything, and the only thread that could
 * rescue it is the one already blocked. A watchdog closes the sink to free it,
 * so [close] must break a pending [write] rather than wait for it.
 *
 * ## Failure policy
 *
 * Unlike the tray and notification libraries, this one does not degrade
 * silently: an output channel that accepts writes and plays nothing is worse
 * than one that fails. [open] throws when the device cannot be opened, and the
 * backend selection above it falls back. [write] may throw when the device
 * dies mid-stream, which is the consumer's cue to run its own recovery.
 * [close] never throws, and [setVolume] is best-effort -- what it can actually
 * do is in [capabilities], not in a return value.
 */
public interface AudioSink : AutoCloseable {

    /** What this sink can do. Constant for its lifetime. */
    public val capabilities: Capabilities

    /** The format currently open, or null before the first [open] and after [close]. */
    public val format: AudioFormat?

    /** True between a successful [open] and [close]. */
    public val isOpen: Boolean

    /**
     * Open the device for [format] and start it. Reopening an already-open sink
     * replaces the stream -- a consumer switching to a track at another sample
     * rate calls this again, and the previous stream and its buffered tail are
     * dropped first. The frame position restarts at zero.
     *
     * @throws AudioException when the device cannot be opened.
     */
    public fun open(format: AudioFormat)

    /**
     * Write interleaved PCM, blocking until the device has consumed all
     * [length] bytes. [length] must be a whole number of frames.
     *
     * @throws AudioException when the device fails mid-write.
     */
    public fun write(data: ByteArray, offset: Int, length: Int)

    /** Resume after [stop]. No-op when already running. */
    public fun start()

    /** Freeze the device and the frame position with it. No-op when already stopped. */
    public fun stop()

    /** Discard buffered-but-unplayed audio. Valid while stopped. */
    public fun flush()

    /** Sample frames the device has played since [open]. Frozen while stopped. */
    public fun framePosition(): Long

    /**
     * How far ahead of the speaker the write head currently is, in nanoseconds.
     * Zero when the backend cannot tell -- which is itself information, so it is
     * not an error.
     */
    public fun latencyNanos(): Long

    /**
     * Linear volume in 0..1. Applied to the stream at the system level when
     * [Capability.STREAM_VOLUME] is present, to the samples otherwise. Values
     * outside the range are clamped rather than rejected.
     */
    public fun setVolume(volume: Float)

    /** The volume last set, clamped. Defaults to 1.0. */
    public fun volume(): Float

    /**
     * Release the device. Idempotent, never throws, and unblocks any [write]
     * currently in flight.
     */
    override fun close()
}

/** Raised when a device cannot be opened or fails mid-stream. */
public class AudioException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
