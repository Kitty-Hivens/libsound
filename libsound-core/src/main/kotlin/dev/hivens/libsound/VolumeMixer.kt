package dev.hivens.libsound

/**
 * Identity of one playback stream on the machine.
 *
 * The backend's own handle. Deliberately not an index: a sound server recycles
 * them, so a stored index selects somebody else's audio after a restart -- the
 * same reason [DeviceId] is a name.
 */
@JvmInline
public value class StreamId(public val value: String) {
    init {
        require(value.isNotBlank()) { "StreamId must not be blank" }
    }

    override fun toString(): String = value
}

/**
 * Somebody's audio, as the sound server sees it.
 *
 * Everything here comes from the stream's own properties, which is why so much
 * is nullable: an application that told the server nothing about itself appears
 * as an anonymous row, and a mixer has to draw that row anyway.
 */
public data class AudioStream(
    public val id: StreamId,
    /** What the application called itself, or null when it never said. */
    public val applicationName: String?,
    /** Reverse-DNS id, where one was given -- what matches a `.desktop` entry. */
    public val applicationId: String? = null,
    public val iconName: String? = null,
    /**
     * What the stream is playing, where it says: a track title, or a generic
     * label. The second line of a mixer row, and never a substitute for
     * [applicationName] -- "Playback Stream" names no application.
     */
    public val mediaName: String? = null,
    /** What the stream says it is for; the same roles our own output can request. */
    public val mediaRole: MediaRole? = null,
    /** The device it is currently playing to. */
    public val device: DeviceId? = null,
    /** Linear 0..1, as the desktop's mixer would show it. */
    public val volume: Float = 1f,
    public val muted: Boolean = false,
    /**
     * False when the stream is attached but not rendering -- a paused player
     * holding its channel open. A mixer draws the row either way and may grey
     * it, which is why this is not simply omitted from the list.
     */
    public val active: Boolean = true,
    /** True when this stream is one of ours, so a UI can mark or skip it. */
    public val isOurs: Boolean = false,
)

/** A change in the set of streams, or in one of them. */
public sealed interface StreamEvent {
    public data class Appeared(public val stream: AudioStream) : StreamEvent
    public data class Changed(public val stream: AudioStream) : StreamEvent
    public data class Gone(public val id: StreamId) : StreamEvent
}

/**
 * Everyone else's audio.
 *
 * The half of the library a shell needs rather than a player: what is playing,
 * how loud, on which device, and the means to change all three.
 *
 * Volume, not samples. Everywhere else in audio a mixer sums streams into one,
 * and that is the single thing this does not do: it moves the sliders the
 * desktop's own volume mixer moves, which is what Windows calls that panel and
 * why the name says so.
 *
 * ## What it leaves behind
 *
 * This is the one surface here that writes state outliving its process. A sound
 * server remembers per-application volume, so a mixer that lowers something and
 * then dies leaves a user with quiet audio and nothing to point at. Every change
 * made through [setVolume] and [setMuted] is therefore recorded, and [close]
 * restores whatever this process changed and did not change back.
 *
 * That is enough for an orderly exit and not enough for a crash. A consumer
 * whose whole feature is temporary -- quiet the music while a video plays --
 * should prefer the media role, which the server enforces and which disappears
 * with the stream that requested it. Direct volume is the mechanism for when the
 * role is not honoured, and [Capability.DUCKS_OTHERS] is how a consumer finds
 * out which situation it is in.
 */
public interface VolumeMixer : AutoCloseable {

    public val capabilities: Capabilities

    public val isOpen: Boolean

    /** Every playback stream the server currently has, ours included. */
    public fun streams(): List<AudioStream>

    /**
     * Linear 0..1, clamped.
     *
     * Returns what the server answered, not that the request was sent: false
     * means the stream went away or refused. A mixer row that springs back is
     * the correct rendering of a stream that closed mid-drag, and it can only be
     * drawn by an implementation that waited for the answer.
     */
    public fun setVolume(id: StreamId, volume: Float): Boolean

    /** As [setVolume], and answering for the same reasons. */
    public fun setMuted(id: StreamId, muted: Boolean): Boolean

    /**
     * Move a stream to another device. Returns false when either is gone.
     *
     * Absent [Capability.STREAM_ROUTING], this always returns false rather than
     * pretending -- a device menu on a mixer row is a control a consumer should
     * not draw where it cannot work.
     */
    public fun moveTo(id: StreamId, device: DeviceId): Boolean

    /**
     * Undo every change this process made and has not already undone.
     *
     * Called by [close]. Public because a consumer that ducks and un-ducks
     * around a video wants it at the end of the video, not at the end of the
     * process.
     */
    public fun restoreAll()

    /**
     * Subscribe to streams appearing, leaving and changing. The handler runs on
     * a thread the backend owns; hop before touching UI state.
     */
    public fun onStreamsChanged(handler: (StreamEvent) -> Unit): () -> Unit

    /**
     * Watch one stream's level, for the meter beside its slider.
     *
     * The handler receives the loudest sample of each short window, linear 0..1,
     * at a rate the backend picks -- fast enough to look live and slow enough
     * not to cost anything. It runs on a thread the backend owns.
     *
     * Watching costs something: the backend attaches to the audio itself rather
     * than asking about it. So this is a subscription with a cancel rather than
     * a property, and a mixer that draws twenty rows should watch the ones on
     * screen instead of all of them.
     *
     * Returns a cancel function in every case. Where
     * [Capability.STREAM_METERING] is absent the handler is never called, which
     * is why a consumer asks the capability rather than inferring the answer
     * from a meter that does not move.
     */
    public fun meter(id: StreamId, handler: (Float) -> Unit): () -> Unit

    override fun close()
}
