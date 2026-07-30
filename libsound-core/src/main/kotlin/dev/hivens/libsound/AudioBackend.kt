package dev.hivens.libsound

/**
 * What a stream tells the system it is for. Maps onto PulseAudio's
 * `media.role`, and onto the nearest equivalent elsewhere; a backend that has
 * no equivalent drops it rather than approximating.
 */
public enum class MediaRole(public val wireName: String) {
    MUSIC("music"),
    VIDEO("video"),
    GAME("game"),
    EVENT("event"),
    NOTIFICATION("notification"),
}

/**
 * The identity a stream carries and the device it prefers.
 *
 * The identity fields are not decoration -- they are half of why this library
 * exists. A stream with a name, an icon and a role is addressable by an
 * EasyEffects rule and legible in the desktop's mixer; an anonymous one is a
 * row labelled with the JVM's process name.
 */
public data class SinkConfig(
    public val applicationName: String,
    /** Reverse-DNS id, matched against a `.desktop` entry where the platform has one. */
    public val applicationId: String? = null,
    /** Freedesktop icon name. Backends that want raw bytes resolve it themselves. */
    public val iconName: String? = null,
    public val mediaRole: MediaRole = MediaRole.MUSIC,
    /** Null follows the system default, including when the default moves. */
    public val device: DeviceId? = null,
    /**
     * Target buffer depth. Smaller means a shorter stall after a flush and a
     * faster response to a volume change; too small underruns under load, and
     * an underrun freezes a clock exactly like the stall it was meant to avoid.
     * Null lets the backend choose.
     */
    public val bufferNanos: Long? = null,
) {
    init {
        require(applicationName.isNotBlank()) { "applicationName must not be blank" }
    }
}

/**
 * A connection to the system's audio service: enumerates devices, watches them
 * change, and hands out sinks.
 *
 * Backends are selected, not constructed -- the platform module tries the real
 * service first and falls back, then reports through [capabilities] what
 * survived the fallback. A backend is always returned; "no audio at all" is not
 * a state this library models, because a consumer can do nothing useful with it
 * that silence does not already do.
 */
public interface AudioBackend : AutoCloseable {

    /** Which backend won selection, for the one line of log that says so. */
    public val name: String

    /** What this backend can do. Constant for its lifetime. */
    public val capabilities: Capabilities

    /**
     * Create a sink. The returned sink is not open yet -- [AudioSink.open]
     * chooses the format, and may be called again later at another rate.
     */
    public fun createSink(config: SinkConfig): AudioSink

    /**
     * Output devices, default first where the backend says which is default.
     * Empty when [Capability.DEVICE_ENUMERATION] is absent, never an exception:
     * a settings screen asks the capability before it asks for the list.
     */
    public fun devices(): List<AudioDevice>

    /** The current default output, or null when unknown. */
    public fun defaultDevice(): AudioDevice?

    /**
     * Subscribe to device add, remove and default-change. The handler runs on a
     * thread the backend owns; hop before touching UI state. The returned
     * function unsubscribes and is idempotent. A no-op subscription when
     * [Capability.DEVICE_EVENTS] is absent.
     */
    public fun onDevicesChanged(handler: () -> Unit): () -> Unit

    /** Release the connection and every sink made from it. Idempotent. */
    override fun close()
}
