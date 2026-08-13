package dev.hivens.libsound.session

import dev.hivens.libsound.Capability
import dev.hivens.libsound.MediaSession
import dev.hivens.libsound.PlaybackState
import dev.hivens.libsound.SessionCommand
import dev.hivens.libsound.SessionConfig
import dev.hivens.libsound.SessionState
import dev.hivens.libsound.TrackMetadata
import dev.hivens.libsound.session.macos.MpNowPlayingSession
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * The macOS session against the real framework.
 *
 * Runs on a macOS runner, which is the only machine this project has that can
 * execute it at all. What it can check is that every message this backend sends
 * is one the runtime accepts: a wrong selector, a class that is not there, or a
 * block the framework refuses all surface here as a throw or a null rather than
 * as silence.
 *
 * What it cannot check is whether the Now Playing widget shows any of it, or
 * whether a media key reaches the handler. A runner has no user session, and
 * that half is in docs/TESTING.md for a person.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class MpNowPlayingSessionTest {

    private var session: MediaSession? = null

    private val config = SessionConfig(applicationName = "libsound session suite", identity = "libsound suite")

    @BeforeEach
    fun open() {
        session = MpNowPlayingSession.openOrNull(config)
        SessionTestGate.require("nowplaying", session != null, "MPNowPlayingInfoCenter did not open")
    }

    @AfterEach
    fun close() {
        session?.let { runCatching { it.close() } }
        session = null
    }

    private fun playing(position: Long = 0) = SessionState(
        playback = PlaybackState.PLAYING,
        metadata = TrackMetadata(
            title = "Bus Stop",
            artists = listOf("Example Artist"),
            album = "Example Album",
            durationMicros = 214_000_000,
        ),
        positionMicros = position,
        canPlay = true,
        canPause = true,
        canGoNext = true,
        canGoPrevious = true,
    )

    @Test
    fun `the session opens and claims only what macOS can do`() {
        val open = checkNotNull(session)
        open.isOpen shouldBe true
        open.capabilities.contains(Capability.SESSION_PUBLISH) shouldBe true
        // Reading other applications' sessions needs the private MediaRemote
        // framework, so this backend must not claim it.
        open.capabilities.contains(Capability.SESSION_READ) shouldBe false
    }

    @Test
    fun `a full state publishes without the runtime refusing anything`() {
        // Every selector, the dictionary construction, the NSNumber boxing and
        // the playback state all go through here. A wrong selector name throws
        // rather than being ignored, which is what makes this worth running.
        checkNotNull(session).publish(playing())
    }

    @Test
    fun `publishing repeatedly is cheap and does not throw`() {
        val open = checkNotNull(session)
        // What a player driving a position bar does. The backend skips the call
        // when nothing changed, and this is the path that would break if the
        // comparison were wrong.
        repeat(20) { open.publish(playing(position = it * 1_000_000L)) }
        open.publish(playing(position = 19_000_000L))
    }

    @Test
    fun `every playback state is accepted`() {
        val open = checkNotNull(session)
        PlaybackState.entries.forEach { state ->
            open.publish(playing().copy(playback = state))
        }
    }

    @Test
    fun `a command handler can be registered and removed`() {
        // The block is the part with an ABI rather than a name: it is installed
        // when the session opens, so reaching this at all means the runtime
        // accepted the struct. Registering a consumer handler is the half this
        // can check without a key press.
        val open = checkNotNull(session)
        val seen = mutableListOf<SessionCommand>()
        val unsubscribe = open.onCommand { seen += it }
        unsubscribe()
        seen.isEmpty() shouldBe true
    }

    @Test
    fun `close clears the session and is idempotent`() {
        val open = checkNotNull(session)
        open.publish(playing())
        open.close()
        open.close()
        open.isOpen shouldBe false
    }
}
