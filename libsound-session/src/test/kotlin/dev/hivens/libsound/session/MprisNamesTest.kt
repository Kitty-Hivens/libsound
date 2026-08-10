package dev.hivens.libsound.session

import dev.hivens.libsound.PlaybackState
import dev.hivens.libsound.session.mpris.Mpris
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The string-shaped half of the protocol, which fails silently when wrong.
 *
 * A reader that does not find these exact spellings does not find the player,
 * and nothing anywhere reports an error: the widget simply stays empty. None of
 * this needs a bus, so it stays checked everywhere.
 */
class MprisNamesTest {

    @Test
    fun `playback status is capitalised the way the spec spells it`() {
        Mpris.statusOf(PlaybackState.PLAYING) shouldBe "Playing"
        Mpris.statusOf(PlaybackState.PAUSED) shouldBe "Paused"
        Mpris.statusOf(PlaybackState.STOPPED) shouldBe "Stopped"
        // And back, because the reading half meets the same three strings.
        Mpris.stateOf("Playing") shouldBe PlaybackState.PLAYING
        Mpris.stateOf("Paused") shouldBe PlaybackState.PAUSED
        Mpris.stateOf("Stopped") shouldBe PlaybackState.STOPPED
        // Anything else is stopped rather than an exception: a player is free to
        // send something we have never heard of, and a media widget is not worth
        // crashing a launcher over.
        Mpris.stateOf("playing") shouldBe PlaybackState.STOPPED
        Mpris.stateOf(null) shouldBe PlaybackState.STOPPED
    }

    @Test
    fun `a track id becomes a valid object path`() {
        // An invalid path is not a rejected field. libdbus asserts and calls
        // _dbus_abort(), which dumps core and takes the host with it -- measured,
        // not assumed. So every output of this has to satisfy the grammar.
        listOf(
            "track-42",
            "/home/haru/Bus Stop.mp3",
            "https://example.invalid/a?b=c",
            "\u590f\u51ea\u304e",
            "\u3053\u306e\u60d1\u661f\u306eBirthday Song",
            "\u0434\u043e\u0440\u043e\u0436\u043a\u0430",
            "_leading_underscore",
            "emoji \ud83c\udfb5 here",
        ).forEach { id ->
            val path = Mpris.trackPath(id)
            Mpris.OBJECT_PATH_PATTERN.matches(path) shouldBe true
        }
        // ASCII letters and digits survive readably; everything else is escaped
        // rather than replaced.
        Mpris.trackPath("track42") shouldBe "/dev/hivens/libsound/track/track42"
    }

    @Test
    fun `distinct titles never collapse onto one path`() {
        // The failure this guards is not cosmetic. SetPosition compares the id
        // against the current track before accepting a seek, so two tracks
        // sharing a path means a seek aimed at one is accepted while the other
        // plays. Replacing every illegal character with the same underscore did
        // exactly that: any two Japanese titles of equal length collided.
        val titles = listOf(
            "\u590f\u51ea\u304e",
            "\u5915\u51ea\u304e",
            "\u3053\u306e\u60d1\u661f\u306eBirthday Song",
            "\u7d04\u675f\u306e\u5504",
            "Bus Stop",
            "Bus_Stop",
            "Bus-Stop",
        )
        val paths = titles.map { Mpris.trackPath(it) }
        paths.distinct().size shouldBe titles.size
    }

    @Test
    fun `no track is the path the spec names, not an empty string`() {
        Mpris.trackPath(null) shouldBe Mpris.NO_TRACK
        Mpris.trackPath("") shouldBe Mpris.NO_TRACK
        Mpris.trackPath("   ") shouldBe Mpris.NO_TRACK
        Mpris.NO_TRACK shouldBe "/org/mpris/MediaPlayer2/TrackList/NoTrack"
    }

    @Test
    fun `a bus name survives a human application name`() {
        // The suffix is the consumer's own name; the library fixes only the prefix.
        Mpris.busName("Aurora") shouldBe "org.mpris.MediaPlayer2.Aurora"
        Mpris.busName("My Player") shouldBe "org.mpris.MediaPlayer2.My_Player"
        // A name element may not start with a digit.
        Mpris.busName("2Player") shouldBe "org.mpris.MediaPlayer2._2Player"
        // Nothing legal left at all still has to produce a requestable name.
        Mpris.busName("...") shouldBe "org.mpris.MediaPlayer2.libsound"
        Mpris.busName("") shouldBe "org.mpris.MediaPlayer2.libsound"
    }

    @Test
    fun `position is not among the properties that get emitted`() {
        // The rule of the protocol that is easiest to break and hardest to
        // notice: Position changes continuously, so emitting it as a property
        // change floods the bus and makes every widget redraw at that rate.
        // Readers poll it or follow Seeked.
        (Mpris.PROP_POSITION in Mpris.CHANGING_PROPERTIES) shouldBe false
        (Mpris.PROP_PLAYBACK_STATUS in Mpris.CHANGING_PROPERTIES) shouldBe true
        (Mpris.PROP_METADATA in Mpris.CHANGING_PROPERTIES) shouldBe true
    }

    @Test
    fun `the introspection xml names every method the spec requires`() {
        val xml = Mpris.INTROSPECTION_XML
        listOf(
            "Next", "Previous", "Pause", "PlayPause", "Stop", "Play",
            "Seek", "SetPosition", "OpenUri", "Seeked",
            "PlaybackStatus", "Metadata", "Position", "Volume", "CanControl",
            "Raise", "Quit", "Identity", "DesktopEntry",
        ).forEach { (it in xml) shouldBe true }
        // The object path is fixed by the spec, and a player that answers on
        // another one is a player nobody finds.
        Mpris.OBJECT_PATH shouldBe "/org/mpris/MediaPlayer2"
    }
}
