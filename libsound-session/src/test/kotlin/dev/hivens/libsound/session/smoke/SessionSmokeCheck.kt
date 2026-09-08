package dev.hivens.libsound.session.smoke

import dev.hivens.libsound.Capability
import dev.hivens.libsound.LoopMode
import dev.hivens.libsound.MediaSession
import dev.hivens.libsound.PlaybackState
import dev.hivens.libsound.SessionCommand
import dev.hivens.libsound.SessionConfig
import dev.hivens.libsound.SessionState
import dev.hivens.libsound.TrackMetadata
import dev.hivens.libsound.session.MediaSessions
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.system.exitProcess

/**
 * A published session, held up for a person to look at.
 *
 * The audio module has a smoke check because a suite cannot hear a tone. This
 * is the same argument one layer up: a suite can prove the message is well
 * formed and cannot prove that Windows drew it on the lock screen, that the
 * artwork arrived, or that pressing the media key on a keyboard reached this
 * process. Those are the questions the SMTC row in the README is still waiting
 * on, and no runner anywhere can answer them.
 *
 * It publishes, changes what it is playing every few seconds so a widget has
 * something to follow, prints every command that arrives, and closes cleanly on
 * Enter. Nothing outside this process is touched: no other player is driven, no
 * volume is changed, nothing is written that outlives the run.
 */
private const val SECONDS = 90L

private val commands = CopyOnWriteArrayList<SessionCommand>()

fun main() {
    println("libsound session smoke check")
    println("os.name       ${System.getProperty("os.name")} ${System.getProperty("os.version")}")
    println("java          ${System.getProperty("java.version")} (${System.getProperty("java.vendor")})")
    println()

    val session = MediaSessions.open(
        SessionConfig(
            applicationName = "libsound smoke check",
            identity = "libsound smoke check",
            desktopEntry = "libsound",
            canRaise = true,
            canQuit = true,
            canSetFullscreen = true,
        ),
    )
    if (session == null) {
        println("FAIL  no session backend here at all. Nothing below can run.")
        println("      On Linux that means no session bus. On Windows it means the")
        println("      transport controls refused, and the reason is worth sending.")
        exitProcess(1)
    }

    println("capabilities  ${session.capabilities}")
    if (Capability.SESSION_LOOP_SHUFFLE !in session.capabilities) {
        println("              repeat and shuffle are not carried here, so questions 4")
        println("              and 5 below do not apply on this platform.")
    }
    println()

    session.use { play(it) }

    println()
    println("Commands that arrived: ${commands.size}")
    commands.forEach { println("  $it") }
    println()
    questions()
    exitProcess(0)
}

private fun play(session: MediaSession) {
    session.onCommand {
        commands.add(it)
        println("  <- $it")
    }

    println("Publishing. Now open the place this platform shows a player:")
    println("  Windows  the lock screen (Win+L), and the volume flyout beside the clock")
    println("  Linux    whatever media widget the shell has")
    println("  macOS    the Now Playing widget in Control Centre")
    println()
    println("Then press the media keys on the keyboard: play, pause, next, previous.")
    println("Every one that reaches this process prints below as it arrives.")
    println()
    println("Running for $SECONDS seconds, or press Enter to stop early.")
    println()

    val started = System.nanoTime()
    var track = 0
    var position = 0L
    val stop = Thread {
        runCatching { readlnOrNull() }
    }.apply { isDaemon = true; start() }

    while (stop.isAlive && (System.nanoTime() - started) < SECONDS * 1_000_000_000L) {
        session.publish(state(track, position))
        Thread.sleep(1_000)
        position += 1_000_000
        // A track change every twelve seconds, so a widget that only redraws on
        // one is seen to redraw, and the artwork is seen to follow.
        if (position >= 12_000_000L) {
            position = 0
            track += 1
            session.seeked(0)
        }
    }
}

private fun state(track: Int, positionMicros: Long): SessionState = SessionState(
    playback = PlaybackState.PLAYING,
    metadata = TrackMetadata(
        title = "Smoke check track ${track + 1}",
        artists = listOf("libsound"),
        album = "Held up for a person to look at",
        durationMicros = 12_000_000L,
        trackNumber = track + 1,
        trackId = "smoke-${track + 1}",
    ),
    positionMicros = positionMicros,
    canPlay = true,
    canPause = true,
    canGoNext = true,
    canGoPrevious = true,
    canSeek = true,
    loop = LoopMode.PLAYLIST,
    shuffle = false,
)

private fun questions() {
    println("The questions no automatic check can answer. Answer each yes or no.")
    println()
    println("1. Did a player appear at all, and under the name 'libsound smoke check'")
    println("   rather than something like 'java' or 'javaw.exe'?")
    println("2. Did the title and the track number change as it ran, and did the")
    println("   position bar move between changes?")
    println("3. Did the media keys reach this process? Every one that did is listed")
    println("   above. A key that did nothing at all is the interesting answer.")
    println("4. Was there a repeat control, and did pressing it print a SetLoop above?")
    println("5. Was there a shuffle control, and did pressing it print a SetShuffle?")
    println("6. On Windows only: did the lock screen show it, with the album and the")
    println("   artist, and did the volume flyout show it too?")
    println()
    println("Send the whole output either way. A run where nothing appeared is more")
    println("useful than one where everything did, and the output is what fixes it.")
}
