package dev.hivens.libsound

/**
 * Identity of one playback stream on the machine.
 *
 * The backend's own handle. Deliberately not an index: a sound server recycles
 * them, so a stored index selects somebody else's audio after a restart -- the
 * same reason [DeviceId] is a name.
 */
@JvmInline
public value class StreamId(
    /** The server's own handle, opaque and not to be parsed. */
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "StreamId must not be blank" }
    }

    /** The name itself, so a log line reads as the stream rather than as a wrapper. */
    override fun toString(): String = value
}

/**
 * Somebody's audio, as the sound server sees it.
 *
 * Everything here comes from the stream's own properties, which is why so much
 * is nullable: an application that told the server nothing about itself appears
 * as an anonymous row, and a mixer has to draw that row anyway.
 */
public data class AudioStream(
    /** What every other call on [VolumeMixer] takes to name this stream. */
    public val id: StreamId,
    /** What the application called itself, or null when it never said. */
    public val applicationName: String?,
    /** Reverse-DNS id, where one was given -- what matches a `.desktop` entry. */
    public val applicationId: String? = null,
    /** Icon name from the desktop's theme, where the stream named one. */
    public val iconName: String? = null,
    /**
     * What the stream is playing, where it says: a track title, or a generic
     * label. The second line of a mixer row, and never a substitute for
     * [applicationName] -- "Playback Stream" names no application.
     */
    public val mediaName: String? = null,
    /** What the stream says it is for; the same roles our own output can request. */
    public val mediaRole: MediaRole? = null,
    /** The device it is currently playing to. */
    public val device: DeviceId? = null,
    /** Linear 0..1, as the desktop's mixer would show it. */
    public val volume: Float = 1f,
    /** Muted separately from the volume, and restored separately for the same reason. */
    public val muted: Boolean = false,
    /**
     * False when the stream is attached but not rendering -- a paused player
     * holding its channel open. A mixer draws the row either way and may grey
     * it, which is why this is not simply omitted from the list.
     */
    public val active: Boolean = true,
    /** True when this stream is one of ours, so a UI can mark or skip it. */
    public val isOurs: Boolean = false,
    /**
     * Whether this row is somebody playing or somebody listening.
     *
     * [VolumeMixer.streams] returns both, because a person looking at their
     * machine sees one picture of it. A panel that draws only playback filters
     * on this, and one that draws a microphone section filters the other way.
     */
    public val direction: StreamDirection = StreamDirection.PLAYBACK,
)

/** A change in the set of streams, or in one of them. */
public sealed interface StreamEvent {
    /** A stream that was not there before. */
    public data class Appeared(
        /** The whole row, so a consumer draws it without asking again. */
        public val stream: AudioStream,
    ) : StreamEvent

    /** A stream whose volume, mute, device or activity moved. */
    public data class Changed(
        /** The row as it now is, not the difference. */
        public val stream: AudioStream,
    ) : StreamEvent

    /**
     * A stream that has closed.
     *
     * Only the id, because by the time this arrives there is nothing left on
     * the server to read.
     */
    public data class Gone(
        /** Which row to remove. */
        public val id: StreamId,
    ) : StreamEvent
}

/**
 * Everyone else's audio.
 *
 * The half of the library a shell needs rather than a player: what is playing,
 * how loud, on which device, and the means to change all three.
 *
 * Volume, not samples. Everywhere else in audio a mixer sums streams into one,
 * and that is the single thing this does not do: it moves the sliders the
 * desktop's own volume mixer moves, which is what Windows calls that panel and
 * why the name says so.
 *
 * ## What it leaves behind
 *
 * This is the one surface here that writes state outliving its process. A sound
 * server remembers per-application volume, so a mixer that lowers something and
 * then dies leaves a user with quiet audio and nothing to point at. Every change
 * made through [setVolume] and [setMuted] is therefore recorded, and [close]
 * restores whatever this process changed and did not change back.
 *
 * That is enough for an orderly exit and not enough for a crash. A consumer
 * whose whole feature is temporary -- quiet the music while a video plays --
 * should prefer the media role, which the server enforces and which disappears
 * with the stream that requested it. Direct volume is the mechanism for when the
 * role is not honoured, and [Capability.DUCKS_OTHERS] is how a consumer finds
 * out which situation it is in.
 */
public interface VolumeMixer : AutoCloseable {

    /**
     * What this mixer can do. Constant for its lifetime, so a settings screen
     * may read it once at startup and build itself from the answer.
     */
    public val capabilities: Capabilities

    /** True between a successful open and [close]. */
    public val isOpen: Boolean

    /**
     * Every stream the server currently has, ours included, in both
     * directions.
     *
     * Capture rows appear only where [Capability.CAPTURE_ENUMERATION] is
     * present, so a consumer that wants to know whether a microphone section
     * can be drawn at all asks that rather than inferring it from an empty
     * half of the list. Everything else filters on
     * [AudioStream.direction].
     */
    public fun streams(): List<AudioStream>

    /**
     * Linear 0..1, clamped.
     *
     * Returns what the server answered, not that the request was sent: false
     * means the stream went away or refused. A mixer row that springs back is
     * the correct rendering of a stream that closed mid-drag, and it can only be
     * drawn by an implementation that waited for the answer.
     */
    public fun setVolume(id: StreamId, volume: Float): Boolean

    /** As [setVolume], and answering for the same reasons. */
    public fun setMuted(id: StreamId, muted: Boolean): Boolean

    /**
     * Move a stream to another device. Returns false when either is gone.
     *
     * Absent [Capability.STREAM_ROUTING], this always returns false rather than
     * pretending -- a device menu on a mixer row is a control a consumer should
     * not draw where it cannot work.
     */
    public fun moveTo(id: StreamId, device: DeviceId): Boolean

    /**
     * The device's own volume, linear 0..1, clamped. False where
     * [Capability.DEVICE_VOLUME] is absent or the device is gone.
     *
     * The other half of a mixer: [setVolume] quiets one application, this
     * quiets the speaker everything is playing through. It carries the same
     * restore obligation, and for a stronger reason, since a device volume
     * left low is the one a user is most likely to blame on their hardware.
     */
    public fun setDeviceVolume(device: DeviceId, volume: Float): Boolean

    /** As [setDeviceVolume], and answering for the same reasons. */
    public fun setDeviceMuted(device: DeviceId, muted: Boolean): Boolean

    /**
     * Make [device] the one applications get when they ask for no device in
     * particular.
     *
     * The single operation here that changes what happens to programs having
     * nothing to do with the caller, which is why it is behind
     * [Capability.DEVICE_VOLUME] and documented as a user-facing action rather
     * than housekeeping. It is not restored by [restoreAll]: a default the user
     * chose through a settings screen is a decision, not a change to undo.
     */
    public fun setDefaultDevice(device: DeviceId): Boolean

    /**
     * The machine's sound cards and the configurations they can be put into.
     * Empty where [Capability.DEVICE_PROFILES] is absent.
     */
    public fun cards(): List<AudioCard>

    /**
     * Put [card] into [profile], by the names [AudioCard] reports. False where
     * either is gone or the server refused.
     *
     * The devices a card offers change with its profile, so a consumer redraws
     * its device list afterwards rather than assuming the old one survived.
     *
     * Recorded and put back by [restoreAll], and it carries the strongest
     * version of that obligation in this interface. A volume left low is
     * something a user can find and fix; a card left on a profile nobody chose
     * is a machine whose speakers have stopped working with nothing on screen
     * to explain it.
     */
    public fun setCardProfile(card: CardId, profile: String): Boolean

    /**
     * Play a device out of, or record it through, one of its own connectors.
     * False where the port is gone or the server refused.
     *
     * The headphone socket against the speakers, an HDMI output that is wired
     * and idle. [AudioDevice.ports] is where the names come from.
     *
     * Recorded and put back by [restoreAll], for the reason
     * [setCardProfile] is: a device left playing out of the wrong socket is
     * silence a user has no way to attribute.
     */
    public fun setDevicePort(device: DeviceId, port: String): Boolean

    /**
     * Create a device that does not exist in hardware. Null where
     * [Capability.VIRTUAL_DEVICES] is absent or the server refused.
     *
     * A soundboard, a separate voice bus, game audio split away from music:
     * with the routing this interface already has, an application can be moved
     * into one without touching that application's own settings.
     *
     * The returned device is this process's to remove, and [close] removes
     * every one it created and did not remove already. That obligation matters
     * more here than anywhere else in the library: a virtual sink left behind
     * after a crash is not quiet audio a user can fix in their mixer, it is a
     * device in their settings that nothing owns and nothing will remove.
     *
     * A device that has just been created is adopted by the desktop's session
     * manager a moment after it appears, and the adoption carries whatever
     * volume and mute that manager decided on. So a [setDeviceVolume] made
     * inside that window can be replaced by a value nobody here chose, which
     * the server reports as a successful request all the same. A consumer that
     * needs the setting to hold asks again once the device has settled.
     *
     * [channels] is honoured or refused, never narrowed. A backend that cannot
     * lay out that many channels answers null rather than handing back a
     * device with fewer, because a caller that asked for a six-channel bus and
     * received a stereo one finds out by hearing four of its channels vanish.
     */
    public fun createVirtualSink(name: String, channels: Int = 2): DeviceId?

    /** Remove a device this process created. False for one it did not. */
    public fun removeVirtualSink(id: DeviceId): Boolean

    /**
     * Play the same audio to two devices at once, until removed. Null where
     * [Capability.VIRTUAL_DEVICES] is absent or the server refused.
     *
     * The result is a device like any other, removed through
     * [removeVirtualSink] and by [close].
     */
    public fun combineSinks(name: String, devices: List<DeviceId>): DeviceId?

    /**
     * Undo every change this process made and has not already undone.
     *
     * Called by [close]. Public because a consumer that ducks and un-ducks
     * around a video wants it at the end of the video, not at the end of the
     * process.
     *
     * The order is load-bearing and runs from what makes devices exist towards
     * what is set on them: devices this process created are removed, then card
     * profiles go back, then device ports, then every volume and mute. A stream
     * restored onto a device that is about to vanish ends up somewhere nobody
     * chose, and a volume applied to a sink a profile is about to destroy is
     * applied to nothing.
     *
     * Everything except [setDefaultDevice], which is a decision rather than a
     * change made on somebody's behalf.
     */
    public fun restoreAll()

    /**
     * Subscribe to streams appearing, leaving and changing. The handler runs on
     * a thread the backend owns; hop before touching UI state.
     */
    public fun onStreamsChanged(handler: (StreamEvent) -> Unit): () -> Unit

    /**
     * Watch one stream's level, for the meter beside its slider.
     *
     * The handler receives the loudest sample of each short window, linear 0..1,
     * at a rate the backend picks -- fast enough to look live and slow enough
     * not to cost anything. It runs on a thread the backend owns.
     *
     * Watching costs something: the backend attaches to the audio itself rather
     * than asking about it. So this is a subscription with a cancel rather than
     * a property, and a mixer that draws twenty rows should watch the ones on
     * screen instead of all of them.
     *
     * Returns a cancel function in every case. Where
     * [Capability.STREAM_METERING] is absent the handler is never called, which
     * is why a consumer asks the capability rather than inferring the answer
     * from a meter that does not move. A capture row is metered where
     * [Capability.CAPTURE_METERING] is present, which is a separate question:
     * watching what a stream plays and watching what it records are different
     * mechanisms, and a backend can have one without the other.
     */
    public fun meter(id: StreamId, handler: (Float) -> Unit): () -> Unit

    /**
     * Put back what this process changed, then release the connection.
     * Idempotent, never throws, and calls [restoreAll] on the way.
     */
    override fun close()
}
