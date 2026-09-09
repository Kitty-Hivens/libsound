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

    /**
     * The sink tells the device what each channel is, so [AudioFormat.layout]
     * is honoured rather than assumed.
     *
     * Absent means only a count goes across and the device applies whatever
     * order it conventionally uses for that many channels. The two conventions
     * are not the same: ALSA lays six channels out as front pair, rear pair,
     * centre, LFE, and FFmpeg hands them over as front pair, centre, LFE, rear
     * pair. A player that sends one to a device expecting the other puts a
     * film's dialogue in the rears.
     *
     * Nothing to ask below three channels, where every platform agrees. Past
     * that this is the question, and a consumer whose backend answers no either
     * accepts the platform's ordering or downmixes to stereo, which is an
     * addon's work rather than this library's.
     */
    CHANNEL_PLACEMENT,

    /** Every playback stream on the machine can be listed. */
    STREAM_ENUMERATION,

    /** Volume and mute can be set on somebody else's stream. */
    STREAM_CONTROL,

    /** A stream can be moved to another device. */
    STREAM_ROUTING,

    /**
     * A stream's level can be watched, so a mixer can draw a meter beside its
     * slider.
     *
     * Separate from [STREAM_ENUMERATION] because listing streams and watching
     * what they are doing are different mechanisms: the level comes from
     * attaching to the audio itself, not from asking about it. Absent means a
     * mixer should leave the meter out rather than draw one that never moves.
     */
    STREAM_METERING,

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

    /** The backend can open an [AudioSource] at all. */
    CAPTURE,

    /**
     * Capture streams belonging to other applications can be listed.
     *
     * Separate from [STREAM_ENUMERATION] because a backend can list what is
     * playing without being able to list what is listening, and because the
     * second is the more pointed question: this is the list a user reads to
     * find out what has their microphone open.
     *
     * Whether capture *devices* can be listed is [DEVICE_ENUMERATION], which
     * covers both directions: a backend that can enumerate one list can
     * enumerate the other.
     */
    CAPTURE_ENUMERATION,

    /** Volume and mute can be set on somebody else's capture stream. */
    CAPTURE_CONTROL,

    /** A capture stream can be moved to another input device. */
    CAPTURE_ROUTING,

    /** A capture stream's level can be watched, so a meter can be drawn beside it. */
    CAPTURE_METERING,

    /**
     * The backend can honour a latency request rather than accepting it
     * politely.
     *
     * Absent means [SinkConfig.latency] is taken as a hint and the buffer that
     * results is whatever the platform would have chosen anyway. A settings
     * screen offering a latency control where this is absent is offering a
     * control that changes nothing.
     */
    LOW_LATENCY,

    /**
     * The writing thread was promoted to real-time priority, so the lowest
     * profiles are usable rather than merely requestable.
     *
     * Reported by the sink rather than by the backend, because the promotion is
     * per thread and per request: it is present only where a caller asked for
     * it through [SinkConfig.realtime] and the system agreed.
     */
    REALTIME_THREAD,

    /**
     * [AudioSink.underrunCount] counts what the device actually did, rather
     * than answering zero because nothing is counting.
     *
     * A latency target nobody can validate is a setting rather than a
     * guarantee, and this is how a consumer finds out whether the number it is
     * watching means anything before it decides to back a profile off.
     */
    UNDERRUN_COUNT,

    /**
     * The device's own volume, not just a stream's, can be read and set.
     *
     * A mixer without it is half a mixer: it can quiet one application and
     * cannot touch the speaker everything is playing through.
     */
    DEVICE_VOLUME,

    /** Card profiles and device ports can be listed and switched. */
    DEVICE_PROFILES,

    /** Devices that do not exist in hardware can be created and removed. */
    VIRTUAL_DEVICES,

    /**
     * One application's output can be recorded without recording the desktop.
     *
     * Behind a capability because it is worth a consumer knowing it cannot be
     * offered here, and worth stating plainly for what it is: this reads
     * another application's audio, without that application being told.
     */
    PER_STREAM_CAPTURE,

    /** Short sounds can be uploaded once and triggered by name. */
    SAMPLE_CACHE,

    /** Our own media session can be published to the desktop. */
    SESSION_PUBLISH,

    /** Other applications' media sessions can be read. */
    SESSION_READ,

    /**
     * Repeat and shuffle can be published, and come back as
     * [SessionCommand.SetLoop] and [SessionCommand.SetShuffle].
     *
     * Absent means [SessionState.loop] and [SessionState.shuffle] go nowhere.
     * The fields are on the type because the type is shared by every platform,
     * and a platform whose session protocol has no property to put them in
     * drops them without saying so, which is what this is for.
     *
     * On the reading side the same question is asked of each player rather than
     * of the backend, through [ForeignPlayer.loop], because there it is the
     * player that answers it.
     */
    SESSION_LOOP_SHUFFLE,

    /**
     * The player's fullscreen state can be published, and the desktop can put
     * it in and out of fullscreen where [SessionConfig.canSetFullscreen] says
     * it may.
     */
    SESSION_FULLSCREEN,

    /**
     * The desktop can ask the application to show its window or to exit, and
     * the request arrives as [SessionCommand.Raise] or [SessionCommand.Quit].
     *
     * [SessionConfig.canRaise] and [SessionConfig.canQuit] say whether this
     * session offers either. This says whether the platform can carry the offer
     * at all, and a consumer that sets those two where it is absent has claimed
     * something nothing will ask it for.
     */
    SESSION_RAISE_QUIT,
}

/**
 * The capability set a backend reports. Immutable for the backend's lifetime:
 * a consumer may read it once at startup and build its UI from the answer.
 */
public class Capabilities(supported: Set<Capability>) {

    /** Everything this backend can do, copied so the set cannot change underneath. */
    public val supported: Set<Capability> = supported.toSet()

    /** Whether [capability] is one of them. The question a settings screen asks. */
    public operator fun contains(capability: Capability): Boolean = capability in supported

    /** Whether at least one of [capabilities] is present. */
    public fun anyOf(vararg capabilities: Capability): Boolean = capabilities.any { it in supported }

    /** Whether every one of [capabilities] is present. */
    public fun allOf(vararg capabilities: Capability): Boolean = capabilities.all { it in supported }

    /** Two sets are equal when they hold the same capabilities. */
    override fun equals(other: Any?): Boolean = other is Capabilities && other.supported == supported

    /** The set's, so equal capability sets hash alike. */
    override fun hashCode(): Int = supported.hashCode()

    /** Sorted and named, so a log line says what a backend can do rather than how many things. */
    override fun toString(): String =
        supported.sortedBy { it.name }.joinToString(prefix = "Capabilities[", postfix = "]") { it.name }

    /** Ways to build a set, for a backend declaring what it is. */
    public companion object {
        /** A backend that can do none of it, which is a legitimate answer. */
        public val NONE: Capabilities = Capabilities(emptySet())

        /** The set holding exactly [capabilities]. */
        public fun of(vararg capabilities: Capability): Capabilities = Capabilities(capabilities.toSet())
    }
}
