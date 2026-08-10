package dev.hivens.libsound.session

import dev.hivens.libsound.MediaSession
import dev.hivens.libsound.PlaybackState
import dev.hivens.libsound.PlayerEvent
import dev.hivens.libsound.SessionCommand
import dev.hivens.libsound.SessionConfig
import dev.hivens.libsound.SessionReader
import dev.hivens.libsound.SessionState
import dev.hivens.libsound.TrackMetadata
import dev.hivens.libsound.session.mpris.MprisReader
import dev.hivens.libsound.session.mpris.MprisSession
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The two halves against each other, on a real bus.
 *
 * The publisher says something, the reader picks it up, and the reader tells the
 * publisher to do something. Both ends are ours, which would be circular if the
 * wire between them were ours too -- it is not. Everything crosses a real
 * session bus in the protocol's own encoding, and `playerctl` is standing beside
 * it to say the same thing independently.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class MprisReaderTest {

    private val name = "libsoundLoop${ProcessHandle.current().pid()}"
    private val busName = "org.mpris.MediaPlayer2.$name"

    private var session: MediaSession? = null
    private var reader: SessionReader? = null
    private val commands = CopyOnWriteArrayList<SessionCommand>()

    @BeforeEach
    fun open() {
        session = MprisSession.openOrNull(
            SessionConfig(applicationName = name, identity = "libsound loop"),
        )
        SessionTestGate.require("dbus", session != null, "no session bus reachable")
        session!!.onCommand { commands.add(it) }
        reader = MprisReader.openOrNull()
        SessionTestGate.require("dbus", reader != null, "reader could not open a connection")
    }

    @AfterEach
    fun close() {
        reader?.let { runCatching { it.close() } }
        session?.let { runCatching { it.close() } }
        reader = null
        session = null
    }

    @Test
    fun `the reader finds what the publisher published`() {
        session!!.publish(
            SessionState(
                playback = PlaybackState.PLAYING,
                metadata = TrackMetadata(
                    title = "夏凪ぎ",
                    artists = listOf("麻枝準"),
                    album = "Bus Stop",
                    durationMicros = 245_000_000L,
                    trackId = "natsunagi",
                ),
                canPlay = true, canPause = true, canGoNext = true, canSeek = true,
            ),
        )
        val player = awaitPlayer()
        player.identity shouldBe "libsound loop"
        player.playback shouldBe PlaybackState.PLAYING
        player.metadata.title shouldBe "夏凪ぎ"
        player.metadata.artists shouldBe listOf("麻枝準")
        player.metadata.album shouldBe "Bus Stop"
        player.metadata.durationMicros shouldBe 245_000_000L
        player.canGoNext shouldBe true
        // CanControl follows whether anything is listening, and something is.
        player.canControl shouldBe true
    }

    @Test
    fun `the reader can drive the player it found`() {
        session!!.publish(SessionState(playback = PlaybackState.PLAYING, canPlay = true, canPause = true))
        awaitPlayer()

        reader!!.control(busName, SessionCommand.PlayPause) shouldBe true
        awaitCommand { it == SessionCommand.PlayPause }

        reader!!.control(busName, SessionCommand.Seek(3_000_000L)) shouldBe true
        awaitCommand { it is SessionCommand.Seek && it.offsetMicros == 3_000_000L }

        reader!!.control(busName, SessionCommand.SetVolume(0.4)) shouldBe true
        awaitCommand { it is SessionCommand.SetVolume }
    }

    @Test
    fun `a change reaches a subscriber`() {
        session!!.publish(SessionState(playback = PlaybackState.PLAYING, canPlay = true))
        awaitPlayer()

        val changed = CountDownLatch(1)
        reader!!.onChange { if (it is PlayerEvent.Changed && it.player.id == busName) changed.countDown() }
        session!!.publish(
            SessionState(
                playback = PlaybackState.PAUSED,
                metadata = TrackMetadata(title = "Bus Stop"),
                canPlay = true,
            ),
        )
        changed.await(10, TimeUnit.SECONDS) shouldBe true
    }

    @Test
    fun `a command to a player that is not there fails rather than hangs`() {
        reader!!.control(
            "org.mpris.MediaPlayer2.NoSuchPlayer${ProcessHandle.current().pid()}",
            SessionCommand.Play,
        ) shouldBe false
    }

    private fun awaitPlayer(): dev.hivens.libsound.ForeignPlayer {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            reader!!.players().firstOrNull { it.id == busName }?.let { return it }
            Thread.sleep(100)
        }
        error("the reader never saw $busName")
    }

    private fun awaitCommand(predicate: (SessionCommand) -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            if (commands.any(predicate)) return
            Thread.sleep(50)
        }
        error("command never arrived; saw $commands")
    }
}
