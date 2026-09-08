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
import dev.hivens.libsound.session.mpris.Mpris
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
                canRaise = true,
                canQuit = true,
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
    fun `the root's own methods reach the player through the reader`() {
        session!!.publish(SessionState(playback = PlaybackState.PLAYING, canPlay = true))
        val player = awaitPlayer()
        // Read before asked, the way the transport buttons already are: these
        // say whether the target will act on the call rather than merely answer
        // it, and a widget that skipped the question would draw both actions
        // for every player on the bus.
        player.canRaise shouldBe true
        player.canQuit shouldBe true

        reader!!.control(busName, SessionCommand.Raise) shouldBe true
        awaitCommand { it == SessionCommand.Raise }
        reader!!.control(busName, SessionCommand.Quit) shouldBe true
        awaitCommand { it == SessionCommand.Quit }
    }

    @Test
    fun `the fullscreen pair arrives and leaves together, without a re-read`() {
        // Read first while there is no fullscreen at all, so the reader's
        // cached row says the property is absent and may not be set. Every
        // assertion below is then about what the signal carried: awaitPlayer
        // would take a fresh GetAll and hide the question.
        session!!.publish(SessionState(playback = PlaybackState.PLAYING))
        val before = awaitPlayer()
        before.fullscreen shouldBe null
        before.canSetFullscreen shouldBe false

        val seen = CopyOnWriteArrayList<ForeignPlayer>()
        reader!!.onChange { if (it is PlayerEvent.Changed && it.player.id == busName) seen.add(it.player) }
        // On the root's own interface, which is the half a reader watching only
        // the player interface would never see. CanSetFullscreen has to ride
        // along: its value never changes, but it appears with the property it
        // describes, and a widget told only about Fullscreen would draw a
        // read-only indicator on a player that accepts being resized.
        session!!.publish(SessionState(playback = PlaybackState.PLAYING, fullscreen = false))
        awaitIn(seen) { it.fullscreen == false && it.canSetFullscreen }

        session!!.publish(SessionState(playback = PlaybackState.PLAYING))
        awaitIn(seen) { it.fullscreen == null && !it.canSetFullscreen }
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
    fun `the reader survives a properties signal shaped like something else`() {
        // The reading half of the same abort. readVariantDict recurses into
        // what a peer wrote, and an `as` where an `a{sv}` was expected leaves
        // the cursor on a string: recursing into one asserts inside libdbus and
        // takes the process down. Anybody on the bus can send this by claiming
        // to be a player, and the parser runs before the sender is resolved, so
        // being unknown is no protection.
        session!!.publish(SessionState(playback = PlaybackState.PLAYING, canPlay = true))
        awaitPlayer()

        ProcessBuilder(
            "dbus-send", "--session", "--type=signal", Mpris.OBJECT_PATH,
            "org.freedesktop.DBus.Properties.PropertiesChanged",
            "string:${Mpris.PLAYER_INTERFACE}", "array:string:not,a,dictionary", "array:string:",
        ).redirectErrorStream(true).start().waitFor(10, TimeUnit.SECONDS)
        Thread.sleep(500)

        // Still reading, and still reading correctly.
        reader!!.isOpen shouldBe true
        awaitPlayer().playback shouldBe PlaybackState.PLAYING
    }

    @Test
    fun `the reader sets fullscreen on the interface that carries it`() {
        // The negative test below passes just as happily when the property is
        // set on the wrong interface, because both answer an error. This is the
        // half that only passes when it is sent to the root.
        session!!.publish(SessionState(playback = PlaybackState.PLAYING, fullscreen = false))
        awaitPlayer { it.fullscreen == false }

        reader!!.control(busName, SessionCommand.SetFullscreen(true)) shouldBe true
        awaitCommand { it is SessionCommand.SetFullscreen && it.fullscreen }
    }

    @Test
    fun `a track number survives the trip out and back`() {
        // The metadata specification says Integer, and this library reads its
        // own published session: written as an int64 the key is present on the
        // wire and invisible to every reader that follows the spec, this one
        // included.
        session!!.publish(
            SessionState(
                playback = PlaybackState.PLAYING,
                metadata = TrackMetadata(title = "Bus Stop", trackNumber = 7, trackId = "seven"),
            ),
        )
        awaitPlayer { it.metadata.trackNumber != null }.metadata.trackNumber shouldBe 7
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
