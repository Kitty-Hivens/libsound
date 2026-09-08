package dev.hivens.libsound.session

import dev.hivens.libsound.ForeignPlayer
import dev.hivens.libsound.LoopMode
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
            SessionConfig(
                applicationName = name,
                identity = "libsound loop",
                canSetFullscreen = true,
            ),
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
    fun `the reader sees a repeat mode only where the player publishes one`() {
        session!!.publish(SessionState(playback = PlaybackState.PLAYING, canPlay = true))
        val plain = awaitPlayer()
        // Null on both sides means the same thing, which is the point of
        // carrying it rather than defaulting: this player has no repeat and no
        // shuffle, so a widget draws neither.
        plain.loop shouldBe null
        plain.shuffle shouldBe null
        plain.fullscreen shouldBe null

        session!!.publish(
            SessionState(
                playback = PlaybackState.PLAYING,
                canPlay = true,
                loop = LoopMode.PLAYLIST,
                shuffle = true,
                fullscreen = false,
            ),
        )
        val full = awaitPlayer { it.loop != null }
        full.loop shouldBe LoopMode.PLAYLIST
        full.shuffle shouldBe true
        full.fullscreen shouldBe false
        full.canSetFullscreen shouldBe true
    }

    @Test
    fun `a repeat mode that changes reaches a subscriber without a re-read`() {
        session!!.publish(
            SessionState(playback = PlaybackState.PLAYING, loop = LoopMode.NONE, shuffle = false),
        )
        awaitPlayer()

        val seen = CopyOnWriteArrayList<ForeignPlayer>()
        reader!!.onChange { if (it is PlayerEvent.Changed && it.player.id == busName) seen.add(it.player) }
        session!!.publish(
            SessionState(playback = PlaybackState.PLAYING, loop = LoopMode.TRACK, shuffle = true),
        )
        awaitIn(seen) { it.loop == LoopMode.TRACK && it.shuffle == true }

        // And a player that stops carrying the property says so through the
        // invalidated array, which is the only thing the protocol offers for a
        // property that went away. A reader that ignored it would keep drawing
        // a repeat button for a player that no longer has one.
        session!!.publish(SessionState(playback = PlaybackState.PLAYING))
        awaitIn(seen) { it.loop == null && it.shuffle == null }
    }

    @Test
    fun `the reader can set a repeat mode on the player it found`() {
        session!!.publish(
            SessionState(playback = PlaybackState.PLAYING, loop = LoopMode.NONE, shuffle = false),
        )
        awaitPlayer()

        reader!!.control(busName, SessionCommand.SetLoop(LoopMode.TRACK)) shouldBe true
        awaitCommand { it is SessionCommand.SetLoop && it.loop == LoopMode.TRACK }

        reader!!.control(busName, SessionCommand.SetShuffle(true)) shouldBe true
        awaitCommand { it is SessionCommand.SetShuffle && it.shuffle }
    }

    @Test
    fun `setting a property the player does not carry comes back as a refusal`() {
        // Nothing published a fullscreen state, so the property is not on the
        // object and the Set answers an error. False rather than a silent
        // success is what lets a consumer tell the two apart.
        session!!.publish(SessionState(playback = PlaybackState.PLAYING))
        awaitPlayer()
        reader!!.control(busName, SessionCommand.SetFullscreen(true)) shouldBe false
    }

    @Test
    fun `a command to a player that is not there fails rather than hangs`() {
        reader!!.control(
            "org.mpris.MediaPlayer2.NoSuchPlayer${ProcessHandle.current().pid()}",
            SessionCommand.Play,
        ) shouldBe false
    }

    /**
     * The player, once it is on the bus and [predicate] holds of it.
     *
     * A publish is queued rather than sent inline, so a read taken straight
     * after one can still answer with the state before it.
     */
    private fun awaitPlayer(predicate: (ForeignPlayer) -> Boolean = { true }): ForeignPlayer {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            reader!!.players().firstOrNull { it.id == busName && predicate(it) }?.let { return it }
            Thread.sleep(100)
        }
        error("the reader never saw $busName in the state the test was waiting for")
    }

    private fun awaitIn(seen: List<ForeignPlayer>, predicate: (ForeignPlayer) -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            if (seen.any(predicate)) return
            Thread.sleep(50)
        }
        error("no change matched; saw $seen")
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
