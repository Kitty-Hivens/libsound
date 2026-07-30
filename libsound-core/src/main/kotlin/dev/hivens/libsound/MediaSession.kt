package dev.hivens.libsound

/** What a player is doing, in the three states every desktop protocol agrees on. */
public enum class PlaybackState {
    PLAYING,
    PAUSED,
    STOPPED,
}

/**
 * What is playing.
 *
 * Shaped after MPRIS's metadata map because that is the richest of the three
 * protocols and the other two are subsets -- mapping down loses nothing, while
 * mapping up would mean inventing fields. Every field is optional: a stream
 * that knows only its title is a legitimate stream, and a session that refuses
 * to publish until it knows the album art is a session nobody sees.
 */
public data class TrackMetadata(
    public val title: String? = null,
    public val artists: List<String> = emptyList(),
    public val album: String? = null,
    public val albumArtists: List<String> = emptyList(),
    public val durationMicros: Long? = null,
    public val trackNumber: Int? = null,
    /**
     * Where the cover lives. `file://` is used as given; `http(s)://` is fetched
     * and cached by the reader, never by the publisher.
     */
    public val artUrl: String? = null,
    /**
     * Stable identity of this track within the player, so a position update can
     * be rejected when it names a track that is no longer current.
     */
    public val trackId: String? = null,
) {
    public companion object {
        public val EMPTY: TrackMetadata = TrackMetadata()
    }
}

/**
 * The complete outward state of our own session. Published as a unit: the
 * protocols emit property changes in batches, and a partial update is what
 * produces a widget showing the previous track's art beside the new title.
 */
public data class SessionState(
    public val playback: PlaybackState = PlaybackState.STOPPED,
    public val metadata: TrackMetadata = TrackMetadata.EMPTY,
    public val positionMicros: Long = 0,
    public val canPlay: Boolean = false,
    public val canPause: Boolean = false,
    public val canGoNext: Boolean = false,
    public val canGoPrevious: Boolean = false,
    public val canSeek: Boolean = false,
    /** 1.0 is normal speed. Reported, never requested -- rate control is not in scope. */
    public val rate: Double = 1.0,
    public val volume: Double = 1.0,
)

/**
 * Something the desktop asked our player to do -- a media key, a click in a
 * widget, a `playerctl` invocation.
 */
public sealed interface SessionCommand {
    public data object Play : SessionCommand
    public data object Pause : SessionCommand
    public data object PlayPause : SessionCommand
    public data object Stop : SessionCommand
    public data object Next : SessionCommand
    public data object Previous : SessionCommand

    /** Move by [offsetMicros] from the current position; negative seeks back. */
    public data class Seek(public val offsetMicros: Long) : SessionCommand

    /**
     * Jump to an absolute position. [trackId] names the track the sender
     * believed was playing; a mismatch against the current track means the
     * command is stale and must be dropped, which is why it is carried at all.
     */
    public data class SetPosition(
        public val trackId: String?,
        public val positionMicros: Long,
    ) : SessionCommand

    public data class SetVolume(public val volume: Double) : SessionCommand
}

/**
 * Our own media session, published outward.
 *
 * Unlike [AudioSink], this one degrades quietly: a session is a convenience
 * surface, and a desktop without a session bus should cost the application
 * nothing more than the absence of media keys. Creation returns a no-op
 * implementation rather than failing, and [capabilities] says which it was.
 */
public interface MediaSession : AutoCloseable {

    public val capabilities: Capabilities

    /** True between construction and [close]. */
    public val isOpen: Boolean

    /**
     * Publish the current state. Idempotent and cheap to call often -- the
     * implementation diffs against what it last sent and emits only what
     * changed, because a property-change storm is what makes a desktop widget
     * flicker.
     */
    public fun publish(state: SessionState)

    /**
     * Subscribe to commands. The handler runs on the session's own dispatch
     * thread; hop before touching UI state. The returned function unsubscribes
     * and is idempotent.
     */
    public fun onCommand(handler: (SessionCommand) -> Unit): () -> Unit

    override fun close()
}
