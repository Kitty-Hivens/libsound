package dev.hivens.libsound.session.samples

import dev.hivens.libsound.MediaSession
import dev.hivens.libsound.PlaybackState
import dev.hivens.libsound.SessionCommand
import dev.hivens.libsound.SessionConfig
import dev.hivens.libsound.SessionReader
import dev.hivens.libsound.SessionState
import dev.hivens.libsound.TrackMetadata
import dev.hivens.libsound.session.MediaSessions
import dev.hivens.libsound.session.SessionReaders

/**
 * Every session example in `docs/GUIDE.md`, as code that compiles.
 *
 * See [dev.hivens.libsound.audio.samples.AudioSamples] for why these exist as
 * code rather than as prose in the guide.
 */
@Suppress("unused", "UNUSED_PARAMETER")
internal object SessionSamples {

    // -- being a player the desktop knows about ------------------------------

    fun publish(): MediaSession? {
        val session = MediaSessions.open(
            SessionConfig(
                applicationName = "Example",
                identity = "Example Player",
                desktopEntry = "com.example.player",
                canRaise = true,
            ),
        )
        // Null is ordinary: no session bus, no backend on this platform yet, or
        // another process already owns the name. Audio still plays without it.
        return session
    }

    fun nowPlaying(session: MediaSession) {
        // The whole state at once, not field by field. What the desktop shows is
        // one consistent picture, and publishing a title without the playback
        // state that goes with it is how a widget ends up showing a new track
        // as still paused.
        session.publish(
            SessionState(
                playback = PlaybackState.PLAYING,
                metadata = TrackMetadata(
                    title = "Bus Stop",
                    artists = listOf("Example Artist"),
                    album = "Example Album",
                    durationMicros = 214_000_000,
                    artUrl = "file:///home/example/cover.jpg",
                    trackId = "example-track-1",
                ),
                positionMicros = 0,
                canPlay = true,
                canPause = true,
                canGoNext = true,
                canSeek = true,
            ),
        )
    }

    fun userDraggedTheScrubber(session: MediaSession, positionMicros: Long) {
        // A seek the desktop did not ask for has to be announced, or its widget
        // keeps extrapolating from the position it last knew about.
        session.seeked(positionMicros)
    }

    fun obeyTheDesktop(session: MediaSession, pause: () -> Unit, resume: () -> Unit) {
        // The desktop's media keys and panel widgets arrive here. The handler
        // runs on a thread the library owns, so hop before touching UI state.
        session.onCommand { command ->
            when (command) {
                SessionCommand.Pause -> pause()
                SessionCommand.Play -> resume()
                else -> Unit
            }
        }
    }

    // -- driving everybody else ----------------------------------------------

    fun pauseEverythingElse(): Int {
        val reader = SessionReaders.open() ?: return 0
        return reader.use {
            it.players()
                // A player publishes whether it will accept being driven.
                // Calling a method it says it does not support is not a bug it
                // has to tolerate.
                .filter { player -> player.canControl && player.playback == PlaybackState.PLAYING }
                .count { player -> it.control(player.id, SessionCommand.Pause) }
        }
    }

    fun watchPlayers(reader: SessionReader, redraw: () -> Unit): () -> Unit =
        reader.onChange { redraw() }
}
