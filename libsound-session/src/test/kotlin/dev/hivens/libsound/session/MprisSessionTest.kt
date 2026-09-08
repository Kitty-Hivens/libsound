package dev.hivens.libsound.session

import dev.hivens.libsound.LoopMode
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
    private val others = CopyOnWriteArrayList<MediaSession>()
    private val received = CopyOnWriteArrayList<SessionCommand>()
    private val name = "libsoundTest${ProcessHandle.current().pid()}"
    private val PEER = Mpris.PEER_INTERFACE
    private val busName = "org.mpris.MediaPlayer2.$name"

    @BeforeEach
    fun publish() {
        SessionTestGate.require("dbus", gdbusAvailable(), "gdbus not installed")
        session = MprisSession.openOrNull(
            SessionConfig(
                applicationName = name,
                identity = "libsound test",
                desktopEntry = "libsound",
                // Raise is offered and Quit is not, on purpose: the two are
                // handled by the same code and the pair is what proves the
                // gate works in both directions.
                canRaise = true,
                canSetFullscreen = true,
            ),
        )
        SessionTestGate.require("dbus", session != null, "no session bus reachable")
        session?.onCommand { received.add(it) }
    }

    @AfterEach
    fun unpublish() {
        others.forEach { runCatching { it.close() } }
        others.clear()
        session?.let { runCatching { it.close() } }
        session = null
    }

    @Test
    fun `the player is on the bus and introspects`() {
        val xml = gdbus("introspect", "--dest", busName, "--object-path", Mpris.OBJECT_PATH, "--xml")
        ("org.mpris.MediaPlayer2.Player" in xml) shouldBe true
        ("PlaybackStatus" in xml) shouldBe true
        ("Seeked" in xml) shouldBe true
        // Nothing has been published, so the optional four are not on the
        // object and the document must not claim otherwise. Introspection is
        // where a widget decides which controls to draw.
        ("LoopStatus" in xml) shouldBe false
        ("Shuffle" in xml) shouldBe false
        ("Fullscreen" in xml) shouldBe false
    }

    @Test
    fun `publishing a repeat mode puts it on the interface`() {
        // Before: not a false, not a "None". The property is optional and a
        // player without a queue has none, so a desktop widget draws no repeat
        // button rather than one that answers an error when pressed.
        ("LoopStatus" in getAll(Mpris.PLAYER_INTERFACE)) shouldBe false
        ("UnknownProperty" in get(Mpris.PLAYER_INTERFACE, Mpris.PROP_LOOP_STATUS)) shouldBe true

        session!!.publish(
            SessionState(playback = PlaybackState.PLAYING, loop = LoopMode.TRACK, shuffle = true),
        )

        val out = getAll(Mpris.PLAYER_INTERFACE)
        ("'LoopStatus': <'Track'>" in out) shouldBe true
        ("'Shuffle': <true>" in out) shouldBe true
        val xml = gdbus("introspect", "--dest", busName, "--object-path", Mpris.OBJECT_PATH, "--xml")
        ("LoopStatus" in xml) shouldBe true
        ("Shuffle" in xml) shouldBe true
    }

    @Test
    fun `a repeat mode set from outside becomes a command`() {
        session!!.publish(SessionState(playback = PlaybackState.PLAYING, loop = LoopMode.NONE))
        set(Mpris.PLAYER_INTERFACE, Mpris.PROP_LOOP_STATUS, "<'Playlist'>")
        await { received.any { it is SessionCommand.SetLoop } }
        received.filterIsInstance<SessionCommand.SetLoop>().firstOrNull()?.loop shouldBe LoopMode.PLAYLIST
    }

    @Test
    fun `shuffle set from outside becomes a command`() {
        session!!.publish(SessionState(playback = PlaybackState.PLAYING, shuffle = false))
        set(Mpris.PLAYER_INTERFACE, Mpris.PROP_SHUFFLE, "<true>")
        await { received.any { it is SessionCommand.SetShuffle } }
        received.filterIsInstance<SessionCommand.SetShuffle>().firstOrNull()?.shuffle shouldBe true
    }

    @Test
    fun `a property this player does not carry is refused rather than accepted`() {
        // Nothing published a loop status, so setting one has to fail. An empty
        // reply would tell a widget the mode it asked for is now in force, and
        // the button would sit there showing a state nothing is in.
        session!!.publish(SessionState(playback = PlaybackState.PLAYING))
        val out = set(Mpris.PLAYER_INTERFACE, Mpris.PROP_LOOP_STATUS, "<'Track'>")
        ("UnknownProperty" in out) shouldBe true
        Thread.sleep(300)
        received.none { it is SessionCommand.SetLoop } shouldBe true
    }

    @Test
    fun `fullscreen is a root property, and the desktop may set it`() {
        session!!.publish(SessionState(playback = PlaybackState.PLAYING, fullscreen = false))
        val out = getAll(Mpris.ROOT_INTERFACE)
        ("'Fullscreen': <false>" in out) shouldBe true
        // The pair travels together: whether the desktop may change it is worth
        // nothing beside a state that is not published.
        ("'CanSetFullscreen': <true>" in out) shouldBe true

        set(Mpris.ROOT_INTERFACE, Mpris.PROP_FULLSCREEN, "<true>")
        await { received.any { it is SessionCommand.SetFullscreen } }
        received.filterIsInstance<SessionCommand.SetFullscreen>().firstOrNull()?.fullscreen shouldBe true
    }

    @Test
    fun `a value of the wrong type is refused rather than read as something else`() {
        session!!.publish(
            SessionState(playback = PlaybackState.PLAYING, loop = LoopMode.NONE, shuffle = false),
        )
        // A desktop that sends the wrong shape gets told so. The reads return
        // null for a type they did not expect, and the alternative to answering
        // here is firing a command built out of whatever the variant held.
        ("InvalidArgs" in set(Mpris.PLAYER_INTERFACE, Mpris.PROP_LOOP_STATUS, "<42>")) shouldBe true
        ("InvalidArgs" in set(Mpris.PLAYER_INTERFACE, Mpris.PROP_SHUFFLE, "<'yes'>")) shouldBe true
        ("InvalidArgs" in set(Mpris.PLAYER_INTERFACE, Mpris.PROP_LOOP_STATUS, "<'Album'>")) shouldBe true
        Thread.sleep(300)
        received.none { it is SessionCommand.SetLoop || it is SessionCommand.SetShuffle } shouldBe true
    }

    @Test
    fun `a property set on the wrong interface is not the one this player carries`() {
        // Volume belongs to the player and Fullscreen to the root. The two
        // interfaces are separate objects as far as the protocol is concerned,
        // and a Set that names the wrong one is asking for a property that is
        // not there.
        session!!.publish(SessionState(playback = PlaybackState.PLAYING, fullscreen = false))
        ("UnknownProperty" in set(Mpris.ROOT_INTERFACE, Mpris.PROP_VOLUME, "<0.5>")) shouldBe true
        ("UnknownProperty" in set(Mpris.PLAYER_INTERFACE, Mpris.PROP_FULLSCREEN, "<true>")) shouldBe true
        Thread.sleep(300)
        received.none { it is SessionCommand.SetVolume || it is SessionCommand.SetFullscreen } shouldBe true
    }

    @Test
    fun `raise reaches the handler and quit does not, because the config said so`() {
        // Both are answered either way, since silence blocks the caller to its
        // own timeout. What differs is whether a command is delivered: a
        // consumer that never claimed CanQuit has no reason to expect one.
        gdbus("call", "--dest", busName, "--object-path", Mpris.OBJECT_PATH,
            "--method", "${Mpris.ROOT_INTERFACE}.Quit")
        gdbus("call", "--dest", busName, "--object-path", Mpris.OBJECT_PATH,
            "--method", "${Mpris.ROOT_INTERFACE}.Raise")
        await { SessionCommand.Raise in received }
        // Each gdbus run is its own connection, so the bus guarantees nothing
        // about the order of the two. What does: the first call blocks until
        // the session has answered it, and the answer goes out before a
        // command would have been fired.
        (SessionCommand.Quit in received) shouldBe false
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
    fun `quit reaches a player that claimed it, and fullscreen does not without permission`() {
        val (player, quitter) = openWith("Quit", listen = true) { app ->
            SessionConfig(applicationName = app, canQuit = true, canSetFullscreen = false)
        }
        call(quitter, Mpris.ROOT_INTERFACE, "Quit")
        await { SessionCommand.Quit in received }

        // The other half of the same gate. The state carries a fullscreen, so
        // the property is on the interface and readable. What the desktop may
        // not do is change it, and saying so is better than accepting a value
        // nothing acts on.
        player.publish(SessionState(playback = PlaybackState.PLAYING, fullscreen = false))
        val out = gdbus(
            "call", "--dest", quitter, "--object-path", Mpris.OBJECT_PATH,
            "--method", "org.freedesktop.DBus.Properties.Set",
            Mpris.ROOT_INTERFACE, Mpris.PROP_FULLSCREEN, "<true>",
        )
        ("NotSupported" in out) shouldBe true
        Thread.sleep(300)
        received.none { it is SessionCommand.SetFullscreen } shouldBe true
    }

    @Test
    fun `a set with the value left off is answered rather than aborting the process`() {
        // dbus_message_iter_init proves there is a first argument and nothing
        // more, and dbus_message_iter_recurse on an iterator that ran out
        // asserts inside libdbus, which calls _dbus_abort and takes the whole
        // host process with it. Measured: this killed the test JVM with
        // SIGABRT before the shape was checked, and it reproduces against the
        // commit this branch started from. Anybody on the session bus can send
        // it.
        val out = dbusSend(
            busName, "org.freedesktop.DBus.Properties.Set",
            "string:${Mpris.PLAYER_INTERFACE}", "string:${Mpris.PROP_VOLUME}",
        )
        ("InvalidArgs" in out) shouldBe true
        // The player answered, and is still there to answer the next one.
        session!!.isOpen shouldBe true
        ("'Volume'" in getAll(Mpris.PLAYER_INTERFACE)) shouldBe true
    }

    @Test
    fun `a player nobody is driving refuses a set rather than pretending`() {
        // CanControl is false where no handler is registered, and the spec says
        // a set made then has no effect and raises an error. An empty reply
        // would leave a widget showing the mode it asked for over a player that
        // never heard the request.
        val (deaf, address) = openWith("Deaf", listen = false) { app ->
            SessionConfig(applicationName = app)
        }
        deaf.publish(SessionState(playback = PlaybackState.PLAYING, loop = LoopMode.NONE))
        val out = gdbus(
            "call", "--dest", address, "--object-path", Mpris.OBJECT_PATH,
            "--method", "org.freedesktop.DBus.Properties.Set",
            Mpris.PLAYER_INTERFACE, Mpris.PROP_LOOP_STATUS, "<'Track'>",
        )
        ("NotSupported" in out) shouldBe true
        // And the property is still readable, which is the half that does not
        // depend on anybody listening.
        val read = gdbus(
            "call", "--dest", address, "--object-path", Mpris.OBJECT_PATH,
            "--method", "org.freedesktop.DBus.Properties.Get",
            Mpris.PLAYER_INTERFACE, Mpris.PROP_LOOP_STATUS,
        )
        ("'None'" in read) shouldBe true
    }

    @Test
    fun `a desktop entry nobody set is absent rather than blank`() {
        // The main session names one, so both polarities are covered in one
        // file. A blank entry sends GNOME and KDE looking for ".desktop", and
        // the miss is indistinguishable from a player that meant it.
        ("'DesktopEntry': <'libsound'>" in getAll(Mpris.ROOT_INTERFACE)) shouldBe true

        val (_, anonymous) = openWith("NoEntry") { app -> SessionConfig(applicationName = app) }
        val out = gdbus(
            "call", "--dest", anonymous, "--object-path", Mpris.OBJECT_PATH,
            "--method", "org.freedesktop.DBus.Properties.GetAll", Mpris.ROOT_INTERFACE,
        )
        out.isNotEmpty() shouldBe true
        ("DesktopEntry" in out) shouldBe false
        val xml = gdbus("introspect", "--dest", anonymous, "--object-path", Mpris.OBJECT_PATH, "--xml")
        ("DesktopEntry" in xml) shouldBe false
    }

    @Test
    fun `a call this object cannot route is answered rather than left hanging`() {
        // Nothing here runs libdbus's own dispatch, so nothing answers for us.
        // Walking the object tree from the root is what busctl and gdbus do,
        // and it used to sit for twenty-five seconds.
        val started = System.nanoTime()
        val elsewhere = gdbus("call", "--dest", busName, "--object-path", "/", "--method", "$PEER.Ping")
        ("UnknownObject" in elsewhere) shouldBe true

        val stranger = gdbus("call", "--dest", busName, "--object-path", Mpris.OBJECT_PATH,
            "--method", "com.example.NotOurs.DoSomething")
        ("UnknownInterface" in stranger) shouldBe true

        // Both answered well inside the caller's own timeout, which is the
        // whole point of answering at all.
        ((System.nanoTime() - started) < 10_000_000_000L) shouldBe true
    }

    @Test
    fun `a player nobody is driving says so in every can property`() {
        // The spec: with CanControl false a client must assume no method is
        // implemented and every other Can property is false. Answering
        // CanPlay true beside CanControl false describes an object that does
        // not exist, and a client that believes the flags draws dead buttons.
        val (deaf, address) = openWith("Mute", listen = false) { app ->
            SessionConfig(applicationName = app, canSetFullscreen = true)
        }
        deaf.publish(
            SessionState(
                playback = PlaybackState.PLAYING,
                canPlay = true, canPause = true, canGoNext = true, canSeek = true,
                fullscreen = false,
            ),
        )
        val player = gdbus(
            "call", "--dest", address, "--object-path", Mpris.OBJECT_PATH,
            "--method", "org.freedesktop.DBus.Properties.GetAll", Mpris.PLAYER_INTERFACE,
        )
        ("'CanControl': <false>" in player) shouldBe true
        ("'CanPlay': <false>" in player) shouldBe true
        ("'CanPause': <false>" in player) shouldBe true
        ("'CanGoNext': <false>" in player) shouldBe true
        ("'CanSeek': <false>" in player) shouldBe true
        val root = gdbus(
            "call", "--dest", address, "--object-path", Mpris.OBJECT_PATH,
            "--method", "org.freedesktop.DBus.Properties.GetAll", Mpris.ROOT_INTERFACE,
        )
        // The screen state is still readable. What is false is the claim that
        // this player would resize on request.
        ("'Fullscreen': <false>" in root) shouldBe true
        ("'CanSetFullscreen': <false>" in root) shouldBe true
    }

    @Test
    fun `a property that exists and cannot be written says which of the two it is`() {
        // UnknownProperty tells a client the property is not there. A client
        // that branches on the code then stops reading it as well.
        val out = set(Mpris.PLAYER_INTERFACE, Mpris.PROP_PLAYBACK_STATUS, "<'Playing'>")
        ("PropertyReadOnly" in out) shouldBe true
        // Rate is the one the spec declares writable and this player refuses.
        ("NotSupported" in set(Mpris.PLAYER_INTERFACE, Mpris.PROP_RATE, "<2.0>")) shouldBe true
        // And a name nothing carries is still an unknown property.
        ("UnknownProperty" in set(Mpris.PLAYER_INTERFACE, "NoSuchThing", "<1>")) shouldBe true
    }

    @Test
    fun `a set whose value is not a variant is refused rather than read through`() {
        // Set is `ssv`. Recursing into whatever container happens to be there
        // reads an array's first element and acts on it, so a caller that sent
        // `ssad` would set the volume with a message no spec allows.
        session!!.publish(SessionState(playback = PlaybackState.PLAYING))
        val out = dbusSend(
            busName, "org.freedesktop.DBus.Properties.Set",
            "string:${Mpris.PLAYER_INTERFACE}", "string:${Mpris.PROP_VOLUME}", "array:double:0.5",
        )
        ("InvalidArgs" in out) shouldBe true
        Thread.sleep(300)
        received.none { it is SessionCommand.SetVolume } shouldBe true
    }

    @Test
    fun `the value a desktop sent is the value the command carries`() {
        // Both polarities, because a handler that ignores its argument and
        // always fires true passes every assertion a true-only test can make.
        session!!.publish(
            SessionState(playback = PlaybackState.PLAYING, shuffle = true, fullscreen = true),
        )
        ("'Shuffle': <true>" in getAll(Mpris.PLAYER_INTERFACE)) shouldBe true

        set(Mpris.PLAYER_INTERFACE, Mpris.PROP_SHUFFLE, "<false>")
        await { received.any { it is SessionCommand.SetShuffle } }
        received.filterIsInstance<SessionCommand.SetShuffle>().first().shuffle shouldBe false

        set(Mpris.ROOT_INTERFACE, Mpris.PROP_FULLSCREEN, "<false>")
        await { received.any { it is SessionCommand.SetFullscreen } }
        received.filterIsInstance<SessionCommand.SetFullscreen>().first().fullscreen shouldBe false

        session!!.publish(
            SessionState(playback = PlaybackState.PLAYING, shuffle = false, fullscreen = false),
        )
        ("'Shuffle': <false>" in getAll(Mpris.PLAYER_INTERFACE)) shouldBe true
        ("'Fullscreen': <false>" in getAll(Mpris.ROOT_INTERFACE)) shouldBe true
    }

    @Test
    fun `opening a uri is refused rather than answered politely`() {
        // Advertised because the spec puts it on the interface. An empty reply
        // tells a desktop the track it asked for is starting, and it never does.
        val out = gdbus("call", "--dest", busName, "--object-path", Mpris.OBJECT_PATH,
            "--method", "${Mpris.PLAYER_INTERFACE}.OpenUri", "file:///tmp/nothing.flac")
        ("NotSupported" in out) shouldBe true
    }

    @Test
    fun `playerctl reads the repeat mode and sets it back`() {
        // The properties section 8 was written for, checked by the tool a
        // desktop widget behaves like. gdbus proves the message is well
        // formed, and this proves it is the message an MPRIS client goes
        // looking for.
        SessionTestGate.require("dbus", commandExists("playerctl"), "playerctl not installed")
        session!!.publish(
            SessionState(playback = PlaybackState.PLAYING, loop = LoopMode.TRACK, shuffle = true),
        )
        Thread.sleep(300)

        run(listOf("playerctl", "-p", name, "loop")).trim() shouldBe "Track"
        run(listOf("playerctl", "-p", name, "shuffle")).trim() shouldBe "On"

        run(listOf("playerctl", "-p", name, "loop", "Playlist"))
        await { received.any { it is SessionCommand.SetLoop } }
        received.filterIsInstance<SessionCommand.SetLoop>().firstOrNull()?.loop shouldBe LoopMode.PLAYLIST
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

    /**
     * A second player with a configuration of its own, on its own bus name.
     *
     * The gates in this file are read from the configuration, and a
     * configuration is fixed when the session opens, so the negative half of
     * each one needs a session that claimed something different.
     */
    private fun openWith(
        suffix: String,
        listen: Boolean = true,
        build: (String) -> SessionConfig,
    ): Pair<MediaSession, String> {
        val other = name + suffix
        val session = MprisSession.openOrNull(build(other))
        SessionTestGate.require("dbus", session != null, "no session bus reachable")
        others.add(session!!)
        // CanControl follows whether anything is listening, so a test about a
        // player nobody is driving has to be able to leave this out.
        if (listen) session.onCommand { received.add(it) }
        return session to "org.mpris.MediaPlayer2.$other"
    }

    /** dbus-send rather than gdbus, because gdbus builds the arguments the interface says it should. */
    private fun dbusSend(dest: String, method: String, vararg args: String): String =
        run(listOf("dbus-send", "--session", "--print-reply", "--dest=$dest", Mpris.OBJECT_PATH, method) + args)

    private fun call(dest: String, iface: String, member: String): String = gdbus(
        "call", "--dest", dest, "--object-path", Mpris.OBJECT_PATH, "--method", "$iface.$member",
    )

    /**
     * Run a client and capture what it printed, with the charset pinned.
     *
     * glib converts a string to the locale's charset on its way to a pipe, so
     * under a non-UTF-8 locale gdbus prints the title as `'???'` while the
     * message on the wire was correct all along -- visible in the same reply,
     * where the escaped track id still carries the right bytes. These
     * assertions are about what MPRIS delivers, so the reader is given a
     * charset that can render it instead of being asked what the machine
     * happens to be set to.
     */
    private fun run(command: List<String>): String = runCatching {
        val builder = ProcessBuilder(command).redirectErrorStream(true)
        builder.environment()["LC_ALL"] = "C.UTF-8"
        val process = builder.start()
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

    private fun get(iface: String, property: String): String = gdbus(
        "call", "--dest", busName, "--object-path", Mpris.OBJECT_PATH,
        "--method", "org.freedesktop.DBus.Properties.Get", iface, property,
    )

    /** The reply, so a caller can assert on the refusal as well as on the effect. */
    private fun set(iface: String, property: String, value: String): String = gdbus(
        "call", "--dest", busName, "--object-path", Mpris.OBJECT_PATH,
        "--method", "org.freedesktop.DBus.Properties.Set", iface, property, value,
    )

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(20)
        condition() shouldBe true
    }

    /**
     * `--session` goes after the subcommand, which is where gdbus wants it.
     *
     * The output is asserted on, including for absence, and [run] answers an
     * empty string for a client that could not start. Empty would satisfy every
     * "not in" check in this file at once, so it fails here instead.
     */
    private fun gdbus(vararg args: String): String {
        val out = run(listOf("gdbus", args[0], "--session") + args.drop(1))
        withClue("gdbus printed nothing for ${args.toList()}") { out.isNotEmpty() shouldBe true }
        return out
    }

    private fun withClue(clue: String, body: () -> Unit) {
        runCatching(body).onFailure { throw AssertionError(clue, it) }
    }

    private fun gdbusAvailable(): Boolean = runCatching {
        ProcessBuilder("gdbus", "--version").redirectErrorStream(true).start().waitFor(5, TimeUnit.SECONDS)
    }.getOrDefault(false)
}
