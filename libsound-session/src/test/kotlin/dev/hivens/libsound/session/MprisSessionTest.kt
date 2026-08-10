package dev.hivens.libsound.session

import dev.hivens.libsound.MediaSession
import dev.hivens.libsound.PlaybackState
import dev.hivens.libsound.SessionCommand
import dev.hivens.libsound.SessionConfig
import dev.hivens.libsound.SessionState
import dev.hivens.libsound.TrackMetadata
import dev.hivens.libsound.session.mpris.Mpris
import dev.hivens.libsound.session.mpris.MprisSession
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * The session, on a real bus, answered by a real client.
 *
 * Everything here goes through `gdbus`, which is the point: the assertions are
 * made by something that knows nothing about this implementation, the way a
 * desktop does. A test that read the properties back through our own marshalling
 * would pass on a message no reader could parse.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class MprisSessionTest {

    private var session: MediaSession? = null
    private val received = CopyOnWriteArrayList<SessionCommand>()
    private val name = "libsoundTest${ProcessHandle.current().pid()}"
    private val busName = "org.mpris.MediaPlayer2.$name"

    @BeforeEach
    fun publish() {
        SessionTestGate.require("dbus", gdbusAvailable(), "gdbus not installed")
        session = MprisSession.openOrNull(
            SessionConfig(applicationName = name, identity = "libsound test", desktopEntry = "libsound"),
        )
        SessionTestGate.require("dbus", session != null, "no session bus reachable")
        session?.onCommand { received.add(it) }
    }

    @AfterEach
    fun unpublish() {
        session?.let { runCatching { it.close() } }
        session = null
    }

    @Test
    fun `the player is on the bus and introspects`() {
        val xml = gdbus("introspect", "--dest", busName, "--object-path", Mpris.OBJECT_PATH, "--xml")
        ("org.mpris.MediaPlayer2.Player" in xml) shouldBe true
        ("PlaybackStatus" in xml) shouldBe true
        ("Seeked" in xml) shouldBe true
    }

    @Test
    fun `metadata reaches a reader that knows nothing about us`() {
        // Non-ASCII on purpose: the title goes out as a D-Bus string and must
        // survive intact, while the track id is a path and must not.
        session!!.publish(
            SessionState(
                playback = PlaybackState.PLAYING,
                metadata = TrackMetadata(
                    title = "夏凪ぎ",
                    artists = listOf("麻枝準"),
                    album = "Bus Stop",
                    durationMicros = 245_000_000L,
                    trackId = "/home/haru/夏凪ぎ.mp4",
                ),
                canPlay = true, canPause = true, canSeek = true,
            ),
        )
        val out = getAll(Mpris.PLAYER_INTERFACE)
        ("'PlaybackStatus': <'Playing'>" in out) shouldBe true
        ("夏凪ぎ" in out) shouldBe true
        ("麻枝準" in out) shouldBe true
        ("'mpris:length': <int64 245000000>" in out) shouldBe true
        // The id survived as a legal path rather than aborting the process.
        ("objectpath '/dev/hivens/libsound/track/" in out) shouldBe true
    }

    @Test
    fun `the root interface identifies the player`() {
        val out = getAll(Mpris.ROOT_INTERFACE)
        ("'Identity': <'libsound test'>" in out) shouldBe true
        ("'DesktopEntry': <'libsound'>" in out) shouldBe true
    }

    @Test
    fun `a command from outside reaches the handler`() {
        val latch = CountDownLatch(1)
        session!!.onCommand { latch.countDown() }
        gdbus("call", "--dest", busName, "--object-path", Mpris.OBJECT_PATH,
            "--method", "${Mpris.PLAYER_INTERFACE}.PlayPause")
        latch.await(5, TimeUnit.SECONDS) shouldBe true
        (SessionCommand.PlayPause in received) shouldBe true
    }

    @Test
    fun `a seek carries its offset`() {
        gdbus("call", "--dest", busName, "--object-path", Mpris.OBJECT_PATH,
            "--method", "${Mpris.PLAYER_INTERFACE}.Seek", "5000000")
        val deadline = System.nanoTime() + 5_000_000_000L
        while (received.none { it is SessionCommand.Seek } && System.nanoTime() < deadline) Thread.sleep(20)
        received.filterIsInstance<SessionCommand.Seek>().firstOrNull()?.offsetMicros shouldBe 5_000_000L
    }

    @Test
    fun `an unknown method is answered rather than left hanging`() {
        // Silence would block the caller to its own timeout, which is how a
        // desktop probing players hangs on startup.
        val out = gdbus("call", "--dest", busName, "--object-path", Mpris.OBJECT_PATH,
            "--method", "${Mpris.PLAYER_INTERFACE}.NoSuchMethod")
        ("UnknownMethod" in out) shouldBe true
    }

    @Test
    fun `volume set from outside becomes a command`() {
        gdbus("call", "--dest", busName, "--object-path", Mpris.OBJECT_PATH,
            "--method", "org.freedesktop.DBus.Properties.Set",
            Mpris.PLAYER_INTERFACE, "Volume", "<0.25>")
        val deadline = System.nanoTime() + 5_000_000_000L
        while (received.none { it is SessionCommand.SetVolume } && System.nanoTime() < deadline) Thread.sleep(20)
        received.filterIsInstance<SessionCommand.SetVolume>().firstOrNull()?.volume shouldBe 0.25
    }

    @Test
    fun `playerctl finds the player and reads its metadata`() {
        // The end of the chain, and the only assertion here made by a tool
        // people actually use. gdbus proves the message is well formed;
        // playerctl proves it is the message MPRIS readers expect, which is a
        // different claim -- a player can be perfectly marshalled and still be
        // invisible for putting a field in the wrong place.
        SessionTestGate.require("dbus", commandExists("playerctl"), "playerctl not installed")
        session!!.publish(
            SessionState(
                playback = PlaybackState.PLAYING,
                metadata = TrackMetadata(
                    title = "Bus Stop",
                    artists = listOf("麻枝準", "熊木杏里"),
                    album = "Bus Stop",
                    durationMicros = 245_000_000L,
                    trackId = "bus-stop",
                ),
                canPlay = true, canPause = true, canSeek = true,
            ),
        )
        Thread.sleep(300)

        run(listOf("playerctl", "--list-all")).contains(name) shouldBe true
        run(listOf("playerctl", "-p", name, "status")).trim() shouldBe "Playing"
        run(listOf("playerctl", "-p", name, "metadata", "xesam:title")).trim() shouldBe "Bus Stop"
        // playerctl joins an `as` with commas, which is itself the proof that
        // both entries went out as an array rather than one concatenated string.
        run(listOf("playerctl", "-p", name, "metadata", "xesam:artist")).trim() shouldBe "麻枝準, 熊木杏里"
        run(listOf("playerctl", "-p", name, "metadata", "mpris:length")).trim() shouldBe "245000000"
    }

    @Test
    fun `playerctl can drive the player`() {
        SessionTestGate.require("dbus", commandExists("playerctl"), "playerctl not installed")
        session!!.publish(SessionState(playback = PlaybackState.PLAYING, canPlay = true, canPause = true))
        run(listOf("playerctl", "-p", name, "play-pause"))
        val deadline = System.nanoTime() + 5_000_000_000L
        while (received.isEmpty() && System.nanoTime() < deadline) Thread.sleep(20)
        (SessionCommand.PlayPause in received) shouldBe true
    }

    private fun run(command: List<String>): String = runCatching {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val out = process.inputStream.readAllBytes().decodeToString()
        process.waitFor(10, TimeUnit.SECONDS)
        out
    }.getOrElse { "" }

    private fun commandExists(name: String): Boolean = runCatching {
        ProcessBuilder(name, "--version").redirectErrorStream(true).start().waitFor(5, TimeUnit.SECONDS)
    }.getOrDefault(false)

    private fun getAll(iface: String): String = gdbus(
        "call", "--dest", busName, "--object-path", Mpris.OBJECT_PATH,
        "--method", "org.freedesktop.DBus.Properties.GetAll", iface,
    )

    private fun gdbus(vararg args: String): String = runCatching {
        val process = ProcessBuilder(listOf("gdbus") + args + listOf("--session").let { emptyList() })
            .command(mutableListOf("gdbus", args[0], "--session") + args.drop(1))
            .redirectErrorStream(true)
            .start()
        val out = process.inputStream.readAllBytes().decodeToString()
        process.waitFor(10, TimeUnit.SECONDS)
        out
    }.getOrElse { "" }

    private fun gdbusAvailable(): Boolean = runCatching {
        ProcessBuilder("gdbus", "--version").redirectErrorStream(true).start().waitFor(5, TimeUnit.SECONDS)
    }.getOrDefault(false)
}
