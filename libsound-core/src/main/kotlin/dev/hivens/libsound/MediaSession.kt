package dev.hivens.libsound

/** What a player is doing, in the three states every desktop protocol agrees on. */
public enum class PlaybackState {
    PLAYING,
    PAUSED,
    STOPPED,
}

/**
 * What a player does when it reaches the end of what it is playing.
 *
 * Three values because that is what the desktop protocols carry, and a queue
 * that repeats is a different thing from a track that repeats: a widget draws
 * the two as separate states of one button.
 */
public enum class LoopMode {
    /** Stop at the end. */
    NONE,

    /** Play the current track again. */
    TRACK,

    /** Start the queue over. */
    PLAYLIST,
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
 *
 * Published as a unit means every field, every time. A field left out of a
 * later state is not carried over from the last one, it is that field's
 * default, and for [loop], [shuffle] and [fullscreen] the default is absent.
 * Where the platform can say so, which today is MPRIS, the desktop is told the
 * player no longer has the property and the button drawn from it goes away.
 * Build each state from the one before it rather than from scratch.
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
    /**
     * What happens at the end of the track, or null for a player that has no
     * such notion.
     *
     * Null is not [LoopMode.NONE], and the difference is what a widget draws.
     * A player that publishes this gets a repeat button and is expected to
     * honour [SessionCommand.SetLoop]. A player that publishes null gets no
     * button at all, which is the right answer for a radio stream, where
     * answering NONE would offer a control that changes nothing.
     */
    public val loop: LoopMode? = null,
    /**
     * Whether the queue is played in a random order. Null carries the same
     * meaning it does for [loop]: there is no such notion here, so no control
     * for it should be drawn.
     */
    public val shuffle: Boolean? = null,
    /**
     * Whether the player currently occupies the whole screen, or null for one
     * with no window to do it with.
     *
     * Whether the desktop may change it is [SessionConfig.canSetFullscreen],
     * because that is a property of the application rather than of the moment.
     */
    public val fullscreen: Boolean? = null,
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

    /**
     * Show the application's window, because somebody clicked the player's name
     * in a widget.
     *
     * Delivered only where [SessionConfig.canRaise] said it would be honoured.
     * A desktop that offers the action and reaches a player which does nothing
     * with it is the dead button [MediaSession.onCommand] exists to avoid.
     */
    public data object Raise : SessionCommand

    /** Exit, at the desktop's request. Delivered only where [SessionConfig.canQuit] allows it. */
    public data object Quit : SessionCommand

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

    /**
     * Repeat what is playing, or the queue, or nothing.
     *
     * Arrives only for a player that published [SessionState.loop], and the
     * player answers by publishing the new mode: the desktop set a property and
     * waits to be told what it now holds.
     */
    public data class SetLoop(public val loop: LoopMode) : SessionCommand

    /** Play the queue in a random order. Arrives only where [SessionState.shuffle] was published. */
    public data class SetShuffle(public val shuffle: Boolean) : SessionCommand

    /**
     * Occupy the whole screen, or stop doing so.
     *
     * Needs both halves of the pair: [SessionConfig.canSetFullscreen] says the
     * desktop may change it, and [SessionState.fullscreen] has to carry a value
     * for there to be a property to change. A session that claims the first and
     * publishes neither has no fullscreen on its interface and never sees this.
     */
    public data class SetFullscreen(public val fullscreen: Boolean) : SessionCommand
}

/**
 * Who the session says it is.
 *
 * [identity] is the human name a widget shows. [desktopEntry] is the basename of
 * the `.desktop` file without its suffix, and it is what lets a desktop find the
 * application's icon -- without it a media widget shows a generic placeholder
 * beside an otherwise complete session.
 */
public data class SessionConfig(
    public val applicationName: String,
    public val identity: String = applicationName,
    public val desktopEntry: String? = null,
    /** Whether the desktop may ask the application to quit. */
    public val canQuit: Boolean = false,
    /** Whether the desktop may ask the application to show its window. */
    public val canRaise: Boolean = false,
    /**
     * Whether the desktop may put the application in and out of fullscreen.
     *
     * The current state is [SessionState.fullscreen], and this flag is only
     * about who may change it. Both are needed before a desktop can offer the
     * control: a player that never publishes a fullscreen state is one with no
     * screen to fill, whatever it says here.
     */
    public val canSetFullscreen: Boolean = false,
    /**
     * The application's window, on Windows only. Ignored everywhere else.
     *
     * Windows attaches its transport controls to a window and offers a desktop
     * process no other way in, so a session there belongs to a window whether
     * the application thinks in those terms or not.
     *
     * Null is supported and is the right answer for a consumer that has no
     * window -- a command line tool, a service. The backend then makes a window
     * of its own to hang the session on. That is the fallback rather than the
     * default because a window the application already owns is the one the user
     * is looking at, and it is the one Windows will associate the controls with
     * most reliably.
     *
     * A Compose or Swing application already has the value: Skiko exposes it as
     * `SkiaLayer.windowHandle`, and an AWT `Window` yields it through the same
     * native peer. Passing it costs a line and removes a guess.
     */
    public val windowHandle: Long? = null,
) {
    init {
        require(applicationName.isNotBlank()) { "applicationName must not be blank" }
    }
}

/**
 * Our own media session, published outward.
 *
 * Unlike [AudioSink], this one degrades quietly: a session is a convenience
 * surface, and a desktop without a session bus should cost the application
 * nothing more than the absence of media keys. So the factory answers null
 * where there is nothing to publish to, rather than throwing -- and rather than
 * handing back an object that accepts every update and shows nothing, which
 * would be indistinguishable from a session the desktop is ignoring.
 */
public interface MediaSession : AutoCloseable {

    public val capabilities: Capabilities

    /** True between construction and [close]. */
    public val isOpen: Boolean

    /**
     * Publish the current state. Idempotent, and cheap to call often: an
     * implementation sends only what changed since the last call, because a
     * property-change storm is what makes a desktop widget flicker.
     */
    public fun publish(state: SessionState)

    /**
     * Announce that the position moved discontinuously -- a seek, a track
     * change, a loop.
     *
     * Separate from [publish] because the difference cannot be inferred. A
     * position that jumped and a position that advanced look identical to
     * anything comparing two snapshots, and only the player knows which
     * happened. Readers rely on this: the protocols deliberately leave position
     * out of ordinary change notifications, since it moves continuously and
     * emitting it as one would flood the bus, so a reader that is not told
     * about a seek goes on extrapolating from the old anchor.
     */
    public fun seeked(positionMicros: Long)

    /**
     * Subscribe to commands. The handler runs on the session's own dispatch
     * thread; hop before touching UI state. The returned function unsubscribes
     * and is idempotent.
     *
     * Register before the first [publish] if the desktop is meant to offer
     * transport controls. MPRIS answers `CanControl` from whether anything is
     * listening at all, and the specification treats that property as one which
     * does not change, so no notification is emitted when it does: a reader
     * that asked before the first handler arrived can go on showing dead
     * buttons for the life of the session.
     */
    public fun onCommand(handler: (SessionCommand) -> Unit): () -> Unit

    override fun close()
}
