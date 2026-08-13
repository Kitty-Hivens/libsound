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
    /**
     * Whether this player accepts being driven from outside.
     *
     * Its own answer, not our guess. A widget that draws transport buttons for a
     * player which refuses them is a widget with dead buttons, and the player is
     * the only thing that knows.
     */
    public val canControl: Boolean = false,
    public val canGoNext: Boolean = false,
    public val canGoPrevious: Boolean = false,
)

/**
 * Reads the media sessions other applications publish.
 *
 * Present on Linux through MPRIS. Windows exposes the same surface through its
 * session manager and this library does not bind it yet. Absent on macOS for
 * good, where the only route is the private MediaRemote framework -- so a
 * consumer asks [Capability.SESSION_READ] first and hides the feature rather
 * than showing an empty list that will never fill.
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
     * Ask another player to do something.
     *
     * The same [SessionCommand] set we accept ourselves, because the protocol is
     * symmetric: what a desktop can ask of us, we can ask of anyone publishing.
     *
     * This is control of another application, and it is deliberately in scope
     * while reaching into the sound server's stream list to change a volume is a
     * different matter. The distinction is not whose audio it is but what the
     * target agreed to: a player owning an MPRIS name has published a control
     * surface, advertises through [ForeignPlayer.canControl] whether it honours
     * it, and takes the whole arrangement away by closing its session. Nothing
     * is left behind for it to be broken by.
     *
     * Returns false when the player is gone or refused. Commands are
     * fire-and-forget past that: a player is free to ignore one, and no protocol
     * here reports back that it did.
     */
    public fun control(playerId: String, command: SessionCommand): Boolean

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
