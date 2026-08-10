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
        // An object path admits only [A-Za-z0-9_] between slashes. A consumer's
        // identity is usually a file path or a URL, and libdbus refuses to
        // append an invalid one -- which takes the whole metadata dictionary out
        // malformed rather than dropping one field.
        Mpris.trackPath("track-42") shouldBe "/dev/hivens/libsound/track/track_42"
        Mpris.trackPath("/home/haru/Bus Stop.mp3") shouldBe
            "/dev/hivens/libsound/track/_home_haru_Bus_Stop_mp3"
        Mpris.trackPath("https://example.invalid/a?b=c") shouldBe
            "/dev/hivens/libsound/track/https___example_invalid_a_b_c"
        // Cyrillic and CJK are not path-legal either, and must not vanish into
        // an empty element.
        Mpris.trackPath("дорожка") shouldBe "/dev/hivens/libsound/track/_______"
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
        Mpris.busName("Nexira") shouldBe "org.mpris.MediaPlayer2.Nexira"
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
