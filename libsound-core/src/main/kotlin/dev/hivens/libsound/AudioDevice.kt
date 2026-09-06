package dev.hivens.libsound

/**
 * Opaque handle for a device, stable enough to persist in settings.
 *
 * The string is the backend's own identifier -- a PulseAudio sink name, a
 * WASAPI endpoint id, a CoreAudio UID. It is deliberately not an index: sink
 * indices are recycled across a server restart and a stored index would select
 * somebody else's device after a reboot.
 */
@JvmInline
public value class DeviceId(public val value: String) {
    init {
        require(value.isNotBlank()) { "DeviceId must not be blank" }
    }

    override fun toString(): String = value
}

/**
 * Which way audio flows through a device or a stream.
 *
 * One enum for both, because a person looking at their machine sees one
 * picture of it: the mixer lists what is playing and what is listening, and a
 * shell that wants half of that filters.
 */
public enum class StreamDirection {
    /** Out of the machine. A speaker, a sink, a stream somebody is playing. */
    PLAYBACK,

    /** Into it. A microphone, a source, a stream somebody is recording. */
    CAPTURE,
}

/**
 * One of a device's physical connectors, as the machine knows it.
 *
 * The headphone socket against the speakers on the same card, an HDMI output
 * that is wired but idle, the two ends of a 3.5 mm jack. Switching between
 * them is what a settings screen offers under the device rather than beside
 * it, which is why they hang off [AudioDevice] instead of forming a list of
 * their own.
 */
public data class DevicePort(
    /** The backend's name for it, and what [VolumeMixer.setDevicePort] takes. */
    public val name: String,
    /** Human-readable label, already localised where the system localises. */
    public val description: String,
    /**
     * True when something is plugged into it, false when the machine says
     * nothing is, and true when it cannot tell.
     *
     * A port with no jack detection is reported as available because refusing
     * to offer it would hide a connector that works. The two are not
     * distinguished further: a consumer that greys out an unavailable port
     * wants exactly this boolean, and one that wants more is asking the
     * hardware a question this library does not carry an answer to.
     */
    public val available: Boolean = true,
)

/**
 * A device the backend can play to or capture from.
 *
 * [isDefault] is a snapshot, not a subscription -- the default moves when the
 * user plugs in headphones, and a consumer that cares subscribes through
 * [AudioBackend.onDevicesChanged] rather than re-reading this field.
 *
 * The three fields after it describe the device itself rather than a stream on
 * it, and they are the half of a mixer that this library could not reach until
 * [Capability.DEVICE_VOLUME] existed. A backend that does not report them says
 * so with a null rather than with a plausible default, because a slider drawn
 * on an invented value moves nothing.
 */
public data class AudioDevice(
    public val id: DeviceId,
    /** Human-readable label, already localised by the system where it localises. */
    public val name: String,
    public val isDefault: Boolean = false,
    /** Which list this device came from. */
    public val direction: StreamDirection = StreamDirection.PLAYBACK,
    /** The device's own volume, linear 0..1, or null where the backend cannot read it. */
    public val volume: Float? = null,
    /** The device's own mute, or null where the backend cannot read it. */
    public val muted: Boolean? = null,
    /**
     * True when the server has closed the hardware because nothing is using
     * it. An ordinary resting state rather than a fault: a stream opened
     * against a suspended device wakes it. Worth showing because a user
     * looking at a silent device wants to know which kind of silence it is.
     */
    public val isSuspended: Boolean = false,
    /** The connectors this device offers, empty where the backend cannot list them. */
    public val ports: List<DevicePort> = emptyList(),
    /** Which of [ports] is in use, by name, or null when the backend cannot tell. */
    public val activePort: String? = null,
)
