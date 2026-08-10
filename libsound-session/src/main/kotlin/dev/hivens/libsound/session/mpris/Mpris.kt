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
     * Object paths admit only `[A-Za-z0-9_]` between slashes, so a consumer's
     * identity -- a file path, a URL, a database key -- cannot be used as one
     * directly. An invalid path is not a soft failure: libdbus refuses to append
     * it and the whole metadata dictionary goes out malformed.
     */
    fun trackPath(trackId: String?): String {
        if (trackId.isNullOrBlank()) return NO_TRACK
        val cleaned = buildString(trackId.length) {
            for (ch in trackId) {
                append(if (ch.isLetterOrDigit() && ch.code < 128 || ch == '_') ch else '_')
            }
        }
        return TRACK_ID_PREFIX + cleaned.ifEmpty { "unknown" }
    }

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
