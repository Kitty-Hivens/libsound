package dev.hivens.libsound

/**
 * Somebody else's player, as the desktop reports it.
 *
 * [id] is the bus name or its platform equivalent -- stable while the player
 * lives, reused by nobody, and the key to correlate two snapshots of the same
 * player. [identity] is what the player calls itself and is fit for display and
 * nothing else; two Firefox windows share it.
 */
public data class ForeignPlayer(
    public val id: String,
    public val identity: String,
    public val playback: PlaybackState = PlaybackState.STOPPED,
    public val metadata: TrackMetadata = TrackMetadata.EMPTY,
    public val positionMicros: Long = 0,
)

/**
 * Reads the media sessions other applications publish.
 *
 * Present on Linux through MPRIS and on Windows through the SMTC session
 * manager. Absent on macOS, where the only route is the private MediaRemote
 * framework -- so a consumer asks [Capability.SESSION_READ] first and hides the
 * feature rather than showing an empty list that will never fill.
 *
 * Degrades quietly, like [MediaSession] and unlike [AudioSink]: no session bus
 * means no players, which is a legitimate answer.
 */
public interface SessionReader : AutoCloseable {

    public val capabilities: Capabilities

    public val isOpen: Boolean

    /**
     * Every player currently publishing. Ordering is the platform's, which is
     * to say arbitrary; a consumer that wants "the one the user means" picks by
     * [ForeignPlayer.playback] and its own policy.
     */
    public fun players(): List<ForeignPlayer>

    /**
     * Subscribe to appearances, disappearances and state changes.
     *
     * [PlayerEvent.Gone] carries only the id, because by the time it fires
     * there is nothing left on the bus to read. The handler runs on the
     * reader's dispatch thread.
     */
    public fun onChange(handler: (PlayerEvent) -> Unit): () -> Unit

    override fun close()
}

/** A change in the set of foreign players, or in one of them. */
public sealed interface PlayerEvent {
    public data class Appeared(public val player: ForeignPlayer) : PlayerEvent
    public data class Changed(public val player: ForeignPlayer) : PlayerEvent
    public data class Gone(public val id: String) : PlayerEvent
}
