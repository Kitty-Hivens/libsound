package dev.hivens.libsound.session.mpris

import dev.hivens.libsound.PlaybackState

/**
 * The names, paths and shapes the MPRIS specification fixes.
 *
 * Kept apart from the implementation because almost all of it is quotation:
 * a reader that does not find these exact strings does not find the player at
 * all, and a value that is nearly right -- `"playing"` for `"Playing"` -- is a
 * widget that renders nothing with no error anywhere.
 */
internal object Mpris {

    /** Every player owns a name under this prefix; the suffix is the app's. */
    const val BUS_NAME_PREFIX = "org.mpris.MediaPlayer2."

    /** The spec fixes the object path. There is exactly one, for every player. */
    const val OBJECT_PATH = "/org/mpris/MediaPlayer2"

    const val ROOT_INTERFACE = "org.mpris.MediaPlayer2"
    const val PLAYER_INTERFACE = "org.mpris.MediaPlayer2.Player"

    const val PROPERTIES_INTERFACE = "org.freedesktop.DBus.Properties"
    const val INTROSPECTABLE_INTERFACE = "org.freedesktop.DBus.Introspectable"
    const val PEER_INTERFACE = "org.freedesktop.DBus.Peer"

    /**
     * What a track without an identity is called.
     *
     * `mpris:trackid` is an object path, not a string, and the spec names this
     * one for "no track". Sending an empty string instead is a signature error;
     * sending nothing at all leaves readers unable to tell one track from the
     * next.
     */
    const val NO_TRACK = "/org/mpris/MediaPlayer2/TrackList/NoTrack"

    /** Where generated track ids live, since the spec requires a valid path. */
    const val TRACK_ID_PREFIX = "/dev/hivens/libsound/track/"

    // -- metadata keys ---------------------------------------------------------

    const val KEY_TRACK_ID = "mpris:trackid"
    const val KEY_LENGTH = "mpris:length"
    const val KEY_ART_URL = "mpris:artUrl"
    const val KEY_TITLE = "xesam:title"
    const val KEY_ARTIST = "xesam:artist"
    const val KEY_ALBUM = "xesam:album"
    const val KEY_ALBUM_ARTIST = "xesam:albumArtist"
    const val KEY_TRACK_NUMBER = "xesam:trackNumber"

    // -- properties ------------------------------------------------------------

    const val PROP_PLAYBACK_STATUS = "PlaybackStatus"
    const val PROP_METADATA = "Metadata"
    const val PROP_POSITION = "Position"
    const val PROP_VOLUME = "Volume"
    const val PROP_RATE = "Rate"
    const val PROP_MINIMUM_RATE = "MinimumRate"
    const val PROP_MAXIMUM_RATE = "MaximumRate"
    const val PROP_CAN_GO_NEXT = "CanGoNext"
    const val PROP_CAN_GO_PREVIOUS = "CanGoPrevious"
    const val PROP_CAN_PLAY = "CanPlay"
    const val PROP_CAN_PAUSE = "CanPause"
    const val PROP_CAN_SEEK = "CanSeek"
    const val PROP_CAN_CONTROL = "CanControl"

    const val PROP_IDENTITY = "Identity"
    const val PROP_DESKTOP_ENTRY = "DesktopEntry"
    const val PROP_CAN_QUIT = "CanQuit"
    const val PROP_CAN_RAISE = "CanRaise"
    const val PROP_HAS_TRACK_LIST = "HasTrackList"
    const val PROP_SUPPORTED_URI_SCHEMES = "SupportedUriSchemes"
    const val PROP_SUPPORTED_MIME_TYPES = "SupportedMimeTypes"

    /**
     * Properties that go into `PropertiesChanged`.
     *
     * [PROP_POSITION] is deliberately absent, and this is the one rule of the
     * protocol that is easy to break and hard to notice. Position changes
     * continuously, so emitting it as a property change floods the bus and makes
     * every widget on it redraw at the emission rate. The spec says readers poll
     * it or follow `Seeked`, which is what the signal exists for.
     */
    val CHANGING_PROPERTIES: List<String> = listOf(
        PROP_PLAYBACK_STATUS,
        PROP_METADATA,
        PROP_VOLUME,
        PROP_RATE,
        PROP_CAN_GO_NEXT,
        PROP_CAN_GO_PREVIOUS,
        PROP_CAN_PLAY,
        PROP_CAN_PAUSE,
        PROP_CAN_SEEK,
    )

    /** `PlaybackStatus` is one of exactly these three, capitalised exactly so. */
    fun statusOf(state: PlaybackState): String = when (state) {
        PlaybackState.PLAYING -> "Playing"
        PlaybackState.PAUSED -> "Paused"
        PlaybackState.STOPPED -> "Stopped"
    }

    fun stateOf(status: String?): PlaybackState = when (status) {
        "Playing" -> PlaybackState.PLAYING
        "Paused" -> PlaybackState.PAUSED
        else -> PlaybackState.STOPPED
    }

    /**
     * Turn an arbitrary track identity into a valid object path.
     *
     * Two reasons this is not optional, and the first is not what it looks like.
     *
     * **An invalid path aborts the process.** Not a rejected append, not a
     * malformed message -- `dbus_message_iter_append_basic` asserts
     * `_dbus_check_is_valid_path` and calls `_dbus_abort()`, which dumps core
     * and takes the host down with it. Measured, after the comment here first
     * claimed the milder version. A consumer's track identity is usually a file
     * path or a URL, so handing it over unchecked is a crash waiting for the
     * first track with a space in its name.
     *
     * **The mapping has to be injective.** Replacing every illegal character
     * with the same `_` collapses distinct titles onto one path: any two
     * Japanese titles of equal length become identical, and `SetPosition`
     * compares the id against the current track before accepting a seek. Two
     * tracks sharing a path means a seek aimed at one is accepted while the
     * other is playing. So illegal bytes are escaped rather than replaced --
     * `_` plus the hex of each UTF-8 byte, with `_` itself escaped so nothing
     * is ambiguous.
     */
    fun trackPath(trackId: String?): String {
        if (trackId.isNullOrBlank()) return NO_TRACK
        val escaped = buildString(trackId.length * 2) {
            for (byte in trackId.toByteArray(Charsets.UTF_8)) {
                val ch = byte.toInt().toChar()
                if (byte >= 0 && (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9')) {
                    append(ch)
                } else {
                    append('_').append("%02x".format(byte.toInt() and 0xFF))
                }
            }
        }
        return TRACK_ID_PREFIX + escaped.ifEmpty { "unknown" }
    }

    /** The grammar an object path element has to satisfy, for tests and guards. */
    val OBJECT_PATH_PATTERN: Regex = Regex("^/([A-Za-z0-9_]+)(/[A-Za-z0-9_]+)*$")

    /**
     * The bus name for [applicationName], with everything a bus name cannot
     * carry replaced.
     *
     * A name element must not start with a digit and admits only
     * `[A-Za-z0-9_-]`, so "My Player 2" has to become something legal before it
     * is requested rather than after the request fails.
     */
    fun busName(applicationName: String): String {
        val cleaned = buildString(applicationName.length) {
            for (ch in applicationName) {
                append(if (ch.isLetterOrDigit() && ch.code < 128 || ch == '_' || ch == '-') ch else '_')
            }
        }
        val safe = cleaned.trim('_').ifEmpty { "libsound" }
        return BUS_NAME_PREFIX + if (safe.first().isDigit()) "_$safe" else safe
    }

    /**
     * Hand-written, per the family convention: no generated bindings, and a
     * consumer that probes before subscribing needs an answer or it stalls to
     * its own timeout.
     */
    val INTROSPECTION_XML: String = """
        <!DOCTYPE node PUBLIC "-//freedesktop//DTD D-BUS Object Introspection 1.0//EN"
          "http://www.freedesktop.org/standards/dbus/1.0/introspect.dtd">
        <node>
          <interface name="org.freedesktop.DBus.Properties">
            <method name="Get">
              <arg name="interface" type="s" direction="in"/>
              <arg name="property" type="s" direction="in"/>
              <arg name="value" type="v" direction="out"/>
            </method>
            <method name="GetAll">
              <arg name="interface" type="s" direction="in"/>
              <arg name="properties" type="a{sv}" direction="out"/>
            </method>
            <method name="Set">
              <arg name="interface" type="s" direction="in"/>
              <arg name="property" type="s" direction="in"/>
              <arg name="value" type="v" direction="in"/>
            </method>
            <signal name="PropertiesChanged">
              <arg name="interface" type="s"/>
              <arg name="changed" type="a{sv}"/>
              <arg name="invalidated" type="as"/>
            </signal>
          </interface>
          <interface name="org.freedesktop.DBus.Introspectable">
            <method name="Introspect"><arg name="xml" type="s" direction="out"/></method>
          </interface>
          <interface name="org.freedesktop.DBus.Peer">
            <method name="Ping"/>
            <method name="GetMachineId"><arg name="machine_uuid" type="s" direction="out"/></method>
          </interface>
          <interface name="org.mpris.MediaPlayer2">
            <method name="Raise"/>
            <method name="Quit"/>
            <property name="CanQuit" type="b" access="read"/>
            <property name="CanRaise" type="b" access="read"/>
            <property name="HasTrackList" type="b" access="read"/>
            <property name="Identity" type="s" access="read"/>
            <property name="DesktopEntry" type="s" access="read"/>
            <property name="SupportedUriSchemes" type="as" access="read"/>
            <property name="SupportedMimeTypes" type="as" access="read"/>
          </interface>
          <interface name="org.mpris.MediaPlayer2.Player">
            <method name="Next"/>
            <method name="Previous"/>
            <method name="Pause"/>
            <method name="PlayPause"/>
            <method name="Stop"/>
            <method name="Play"/>
            <method name="Seek"><arg name="Offset" type="x" direction="in"/></method>
            <method name="SetPosition">
              <arg name="TrackId" type="o" direction="in"/>
              <arg name="Position" type="x" direction="in"/>
            </method>
            <method name="OpenUri"><arg name="Uri" type="s" direction="in"/></method>
            <signal name="Seeked"><arg name="Position" type="x"/></signal>
            <property name="PlaybackStatus" type="s" access="read"/>
            <property name="Rate" type="d" access="readwrite"/>
            <property name="Metadata" type="a{sv}" access="read"/>
            <property name="Volume" type="d" access="readwrite"/>
            <property name="Position" type="x" access="read"/>
            <property name="MinimumRate" type="d" access="read"/>
            <property name="MaximumRate" type="d" access="read"/>
            <property name="CanGoNext" type="b" access="read"/>
            <property name="CanGoPrevious" type="b" access="read"/>
            <property name="CanPlay" type="b" access="read"/>
            <property name="CanPause" type="b" access="read"/>
            <property name="CanSeek" type="b" access="read"/>
            <property name="CanControl" type="b" access="read"/>
          </interface>
        </node>
    """.trimIndent()
}
