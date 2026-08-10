package dev.hivens.libsound

/**
 * Something a backend either can or cannot do.
 *
 * Queried, never discovered by failing. macOS has no per-application volume at
 * all, so a settings screen must be able to ask before it draws a control that
 * could not work; a JavaSound fallback loses stream identity and device
 * selection and has to say so rather than silently ignoring both.
 */
public enum class Capability {
    /**
     * Volume is set on the stream at the system level, so the desktop's mixer
     * shows and controls it. Absent means volume is applied to the samples on
     * their way out, which is audible-equivalent but invisible to the system.
     */
    STREAM_VOLUME,

    /** Output devices can be listed. */
    DEVICE_ENUMERATION,

    /** A specific output device can be chosen rather than following the default. */
    DEVICE_SELECTION,

    /** Device add, remove and default-change arrive as events. */
    DEVICE_EVENTS,

    /**
     * The stream carries an application name, icon and media role that the
     * system can see -- what makes it addressable by an EasyEffects rule
     * instead of appearing as an anonymous client.
     */
    STREAM_IDENTITY,

    /**
     * [AudioSink.framePosition] comes from the device's own consumed-sample
     * count. Absent means it is extrapolated, and a consumer driving an A/V
     * clock from it should expect drift.
     */
    DEVICE_POSITION,

    /** Every playback stream on the machine can be listed. */
    STREAM_ENUMERATION,

    /** Volume and mute can be set on somebody else's stream. */
    STREAM_CONTROL,

    /** A stream can be moved to another device. */
    STREAM_ROUTING,

    /**
     * A media role actually changes what other streams do.
     *
     * Present only where the session manager is configured to act on roles, so
     * it says something about the desktop rather than about the backend. A
     * consumer that wants to quiet the music behind a video asks this before
     * choosing between the role, which the server enforces and which vanishes
     * with the stream, and direct volume, which does neither.
     */
    DUCKS_OTHERS,

    /** Our own media session can be published to the desktop. */
    SESSION_PUBLISH,

    /** Other applications' media sessions can be read. */
    SESSION_READ,
}

/**
 * The capability set a backend reports. Immutable for the backend's lifetime:
 * a consumer may read it once at startup and build its UI from the answer.
 */
public class Capabilities(supported: Set<Capability>) {

    public val supported: Set<Capability> = supported.toSet()

    public operator fun contains(capability: Capability): Boolean = capability in supported

    public fun anyOf(vararg capabilities: Capability): Boolean = capabilities.any { it in supported }

    public fun allOf(vararg capabilities: Capability): Boolean = capabilities.all { it in supported }

    override fun equals(other: Any?): Boolean = other is Capabilities && other.supported == supported

    override fun hashCode(): Int = supported.hashCode()

    override fun toString(): String =
        supported.sortedBy { it.name }.joinToString(prefix = "Capabilities[", postfix = "]") { it.name }

    public companion object {
        public val NONE: Capabilities = Capabilities(emptySet())

        public fun of(vararg capabilities: Capability): Capabilities = Capabilities(capabilities.toSet())
    }
}
