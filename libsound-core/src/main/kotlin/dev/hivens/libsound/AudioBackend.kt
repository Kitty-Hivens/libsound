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
     * How long the path to the speaker may be. A request the server tries to
     * meet, not a promise: [LatencyProfile] says what limits it.
     */
    public val latency: LatencyProfile = LatencyProfile.BALANCED,
    /**
     * Target buffer depth, overriding [latency] for a caller that knows exactly
     * what it wants. Smaller means a shorter stall after a flush and a faster
     * response to a volume change; too small underruns under load, and an
     * underrun freezes a clock exactly like the stall it was meant to avoid.
     * Null takes the profile's number.
     */
    public val bufferNanos: Long? = null,
    /**
     * Ask for the writing thread to be promoted to real-time priority.
     *
     * Off by default, and deliberately: a thread that will not be preempted is
     * not something a library takes on behalf of a process that did not ask for
     * it. At [LatencyProfile.LOW] and below it is the difference between asking
     * for low latency and having it, because an ordinary JVM thread competing
     * with a compile or a game loop wakes late and every late wake is an
     * underrun.
     *
     * Best effort. A refusal is logged once with its reason and reported as
     * [Capability.REALTIME_THREAD] being absent from the sink, so a settings
     * screen can say why the lowest profile is not on offer.
     */
    public val realtime: Boolean = false,
) {
    init {
        require(applicationName.isNotBlank()) { "applicationName must not be blank" }
    }

    /** The buffer this config actually asks for. */
    public val targetNanos: Long get() = bufferNanos ?: latency.targetNanos
}

/**
 * The identity a capture stream carries and the device it prefers.
 *
 * The mirror of [SinkConfig], and the identity matters more here than it does
 * on the output side: a capture stream appears in the desktop's privacy
 * indicator, and the row that says "Example is using your microphone" is
 * reading exactly these fields. An anonymous one is a warning a user cannot
 * act on.
 */
public data class SourceConfig(
    public val applicationName: String,
    /** Reverse-DNS id, matched against a `.desktop` entry where the platform has one. */
    public val applicationId: String? = null,
    /** Freedesktop icon name. Backends that want raw bytes resolve it themselves. */
    public val iconName: String? = null,
    public val mediaRole: MediaRole = MediaRole.MUSIC,
    /** Null follows the system default input, including when the default moves. */
    public val device: DeviceId? = null,
    /**
     * Record one application's output instead of a device.
     *
     * The id comes from [VolumeMixer.streams], and what arrives is that
     * stream's audio and nothing else: not the desktop, not whatever else is
     * playing through the same speakers. A game recorded while a voice chat
     * plays over it, one browser tab, one application fed into another: all of
     * it is this field.
     *
     * Only where [Capability.PER_STREAM_CAPTURE] is present, and [device] is
     * ignored when it is set, because the device is then whichever one the
     * target stream is playing to. Worth stating plainly for what it is: this
     * reads another application's audio, and that application is not told.
     */
    public val captureStream: StreamId? = null,
    public val latency: LatencyProfile = LatencyProfile.BALANCED,
    /** Overrides [latency], for a caller that knows the number it wants. */
    public val bufferNanos: Long? = null,
    /** As [SinkConfig.realtime], for the thread that drains the microphone. */
    public val realtime: Boolean = false,
) {
    init {
        require(applicationName.isNotBlank()) { "applicationName must not be blank" }
    }

    /** The buffer this config actually asks for. */
    public val targetNanos: Long get() = bufferNanos ?: latency.targetNanos
}

/**
 * A short sound the server holds, triggered by name rather than played through
 * a stream.
 *
 * The backend's own handle, and not an index for the same reason [DeviceId] is
 * not one: a server recycles them.
 */
@JvmInline
public value class SampleId(public val value: String) {
    init {
        require(value.isNotBlank()) { "SampleId must not be blank" }
    }

    override fun toString(): String = value
}

/**
 * A connection to the system's audio service: enumerates devices, watches them
 * change, and hands out sinks.
 *
 * Backends are selected, not constructed -- the platform module tries the real
 * service first and falls back, then reports through [capabilities] what
 * survived the fallback. "No sound server" is not a failure -- the fallback
 * covers it, and reports through [capabilities] what it lost on the way. Only a
 * JVM that cannot play audio at all, headless or in a container with no device,
 * leaves a consumer with nothing, and the factory says so by answering null.
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
     * Create a source. The returned source is not open yet, exactly as
     * [createSink] hands back a sink that is not.
     *
     * @throws AudioException where [Capability.CAPTURE] is absent. A consumer
     *   asks the capability first, the same way it asks before drawing a device
     *   menu, and there is no useful object to hand back to one that did not.
     */
    public fun createSource(config: SourceConfig): AudioSource

    /**
     * Input devices, default first where the backend says which is default.
     * Empty when [Capability.DEVICE_ENUMERATION] is absent, which covers both
     * directions.
     *
     * A parallel list rather than a flag on [devices], because a consumer
     * almost always wants one or the other and never a mixed one: a device menu
     * offering a microphone as an output is a menu with a broken row in it.
     */
    public fun captureDevices(): List<AudioDevice>

    /** The current default input, or null when unknown. */
    public fun defaultCaptureDevice(): AudioDevice?

    /**
     * Upload a short sound once, to be triggered by name afterwards. Null where
     * the server refused or [Capability.SAMPLE_CACHE] is absent.
     *
     * The lowest-latency path there is for an interface click or a
     * notification: no stream to set up, no buffer to fill, no scheduling, so
     * the sound lands with the click rather than after it.
     *
     * Samples this process uploaded are removed by [close], the same obligation
     * as everything else here that outlives a process.
     */
    public fun cacheSample(name: String, format: AudioFormat, pcm: ByteArray): SampleId?

    /**
     * Trigger an uploaded sound, on [device] or on the default. False when the
     * sample is gone or the server refused.
     */
    public fun playSample(id: SampleId, device: DeviceId? = null, volume: Float = 1f): Boolean

    /**
     * Subscribe to device add, remove and default-change, in both directions.
     * The handler runs on a thread the backend owns; hop before touching UI
     * state. The returned function unsubscribes and is idempotent. A no-op
     * subscription when [Capability.DEVICE_EVENTS] is absent.
     *
     * Deliberately coarse, and covering capture as well as playback: it says
     * something moved rather than what, every consumer re-reads the list
     * anyway, and splitting it would be state to keep correct for no gain.
     */
    public fun onDevicesChanged(handler: () -> Unit): () -> Unit

    /** Release the connection and every sink made from it. Idempotent. */
    override fun close()
}
