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
 * ## What a wrapper owes
 *
 * A sink can wrap another sink, and that is how processing is meant to be
 * added: a gain, a filter, a limiter, published separately and depending on
 * this contract alone. It works today because this is an interface, and it is
 * a trap today because the contract says nothing about what a wrapper owes.
 * Four rules, asserted by the decorator fixture beside the contract suite.
 *
 * 1. **[write] still blocks until the device took the audio.** A decorator may
 *    buffer internally, but it may not return before the frames it produced
 *    have been consumed by the sink it wraps. Returning early turns the
 *    consumer's decode loop into a busy loop and makes a stall watchdog fire
 *    during healthy playback.
 *
 * 2. **[framePosition] delegates unchanged.** It counts frames the device has
 *    played, and a decorator has played nothing. Adding its own buffered
 *    frames would report audio nobody has heard.
 *
 * 3. **A decorator that changes the frame count has to say so.** Resampling
 *    and time-stretching produce a different number of frames than they
 *    consume, so the wrapped sink's position stops counting the consumer's
 *    frames. Such a decorator either scales the position back into the
 *    consumer's frames or declares itself unusable as a clock source by
 *    withholding [Capability.DEVICE_POSITION]. There is no third option that
 *    keeps audio and video in sync.
 *
 * 4. **[flush] drops the decorator's own buffer too, and [close] closes what it
 *    wraps.** A seek that leaves stale samples in a filter plays the old
 *    position for as long as the filter is deep.
 *
 * And [latencyNanos] includes the decorator's own buffer on top of what it
 * wraps. A filter that hides its depth makes every consumer's synchronisation
 * wrong by exactly that much.
 *
 * ## What shapes it takes, asked rather than caught
 *
 * Backends do not accept the same set. libpulse has no 64-bit float at all, the
 * JavaSound fallback takes whatever the JVM's default line takes and no more,
 * and the two of them differ from what a CoreAudio unit will convert. The rest
 * of this library answers that class of question through [Capabilities] rather
 * than by failing, and this one is no different: [acceptedEncodings] is the
 * ladder a consumer walks down, and [accepts] is the whole question including
 * the channel count and the layout.
 *
 * The two are bound to [open] by contract. `accepts(format)` is true exactly
 * when `open(format)` would not throw for want of the shape, and the contract
 * suite asserts the pair against every encoding. A sink whose answer and whose
 * behaviour disagree is worse than one that only fails, because a consumer that
 * asked first has no second question to ask.
 *
 * ### Beyond stereo, the layout is a separate promise
 *
 * [Capability.CHANNEL_PLACEMENT] says whether a sink tells the device what each
 * channel is. Where it is present, [AudioFormat.layout] is honoured and a
 * layout the backend cannot express is refused by [accepts]. Where it is
 * absent, the sink hands over a channel count and nothing else, the device
 * applies its own convention, and a 5.1 stream can come out with the rears and
 * the sides exchanged. Mono and stereo are the same everywhere and need no
 * question asked; past them, this is the question.
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

    /**
     * Every encoding this sink can be opened with. Always contains
     * [PcmEncoding.S16LE], which is the floor every backend owes.
     *
     * The ladder a consumer walks down when the shape it has is not on offer,
     * and the reason it is a set rather than a probe: a decoder choosing what
     * to produce wants to choose once, before it has anything to hand over.
     */
    public val acceptedEncodings: Set<PcmEncoding>

    /**
     * Whether [open] would take this shape.
     *
     * The whole question, where [acceptedEncodings] is one part of it: a
     * backend may take an encoding and refuse the channel count beside it, or
     * take both and be unable to place the channels the layout names. False
     * here means [open] throws, and the two are asserted against each other by
     * the contract suite.
     *
     * The default answers on the encoding alone, which is the whole of it for a
     * backend that places no channels and limits no counts. A backend that
     * knows more overrides.
     */
    public fun accepts(format: AudioFormat): Boolean = format.encoding in acceptedEncodings

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
     * @throws AudioException when the device cannot be opened, which includes
     *   every shape [accepts] answers false for. An exception rather than an
     *   argument check, because a consumer walking down from what the media is
     *   catches what the contract promises, and an `IllegalArgumentException`
     *   goes straight past that and out of the player.
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
     *
     * The whole path, and this is the number a consumer needs rather than the
     * one that is easiest to produce: what is queued here, plus what the server
     * holds, plus the device's own. A pacer that had to add its own estimate of
     * the server's share would get it wrong differently on every machine. It is
     * also how a consumer finds out what its [SinkConfig.latency] request was
     * actually granted, since a profile is a request and the graph's quantum is
     * a floor under it.
     */
    public fun latencyNanos(): Long

    /**
     * Times the device ran dry since [open]. Monotonic within one open.
     *
     * A low-latency target nobody can validate is a setting rather than a
     * guarantee: this is the number a consumer watches to find out that it
     * asked for too much, and backs its profile off when it climbs. The
     * vocabulary is [PcmRingBuffer]'s on purpose, which counts the same event
     * one layer up.
     *
     * Zero where the backend does not count them, which is not the same as
     * never having run dry. [Capability.UNDERRUN_COUNT] is how the two are told
     * apart.
     */
    public fun underrunCount(): Long

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
