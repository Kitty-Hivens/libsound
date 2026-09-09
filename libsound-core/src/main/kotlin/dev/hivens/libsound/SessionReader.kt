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
    /** What [SessionReader.control] takes to name this player. */
    public val id: String,
    /** What the player calls itself, for display and nothing else. */
    public val identity: String,
    /** Playing, paused or stopped, as the player last said. */
    public val playback: PlaybackState = PlaybackState.STOPPED,
    /** What it is playing, as far as it has told anybody. */
    public val metadata: TrackMetadata = TrackMetadata.EMPTY,
    /**
     * Where it was when this was read, which is not where it is now.
     *
     * The protocols leave position out of ordinary change notifications
     * because it moves continuously, so a consumer that draws a scrubber
     * extrapolates from here and re-anchors when a seek is announced.
     */
    public val positionMicros: Long = 0,
    /**
     * Whether this player accepts being driven from outside.
     *
     * Its own answer, not our guess. A widget that draws transport buttons for a
     * player which refuses them is a widget with dead buttons, and the player is
     * the only thing that knows.
     */
    public val canControl: Boolean = false,
    /** Whether it says it has a next track. */
    public val canGoNext: Boolean = false,
    /** Whether it says it has a previous one. */
    public val canGoPrevious: Boolean = false,
    /**
     * What the player does at the end of the track, or null where it publishes
     * no such property.
     *
     * The reading half of [SessionState.loop], and null means the same thing on
     * both sides: this player has no repeat to draw. The property is optional,
     * so a widget that read its absence as a value would draw a button for a
     * player that never offered one.
     */
    public val loop: LoopMode? = null,
    /** Whether the player is shuffling, or null where it publishes no such property. */
    public val shuffle: Boolean? = null,
    /** Whether the player fills the screen, or null where it publishes no such property. */
    public val fullscreen: Boolean? = null,
    /** Whether the player accepts being asked to show its window. */
    public val canRaise: Boolean = false,
    /** Whether the player accepts being asked to exit. */
    public val canQuit: Boolean = false,
    /** Whether the player accepts being put in and out of fullscreen. */
    public val canSetFullscreen: Boolean = false,
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

    /** What this reader can do. Constant for its lifetime. */
    public val capabilities: Capabilities

    /** True between a successful open and [close]. */
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
     * Returns false when the player is gone or answered with an error, which is
     * what setting a property it does not carry produces.
     *
     * A method is a weaker signal than that, and the difference matters because
     * the two kinds of command are mixed here. The protocol has a player answer
     * `Raise`, `Quit` and the transport calls whether or not it acts on them,
     * so true for one of those means the call was delivered rather than
     * honoured. [ForeignPlayer.canControl], [ForeignPlayer.canRaise] and
     * [ForeignPlayer.canQuit] are the player's own answer about what it will
     * act on, and they are meant to be read before asking.
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

    /** Stop reading and release the connection. Idempotent, never throws. */
    override fun close()
}

/** A change in the set of foreign players, or in one of them. */
public sealed interface PlayerEvent {
    /** A player that was not on the desktop before. */
    public data class Appeared(
        /** Everything known about it, so a consumer draws it without asking again. */
        public val player: ForeignPlayer,
    ) : PlayerEvent

    /** A player whose state moved. */
    public data class Changed(
        /** The player as it now is, not the difference. */
        public val player: ForeignPlayer,
    ) : PlayerEvent

    /**
     * A player that has gone.
     *
     * Only the id, because by the time this arrives there is nothing left to
     * read.
     */
    public data class Gone(
        /** Which player to forget. */
        public val id: String,
    ) : PlayerEvent
}
