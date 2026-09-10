package dev.hivens.libsound.audio.pulse

import dev.hivens.libsound.ChannelLayout
import dev.hivens.libsound.ChannelPosition
import dev.hivens.libsound.PcmEncoding

/**
 * Constants and struct layouts for the libpulse subset this backend binds.
 *
 * Every number here was printed by `tools/pa-oracle.c` against the pinned
 * headers, not read off a reference and not inferred. The sibling libraries
 * inferred one struct size instead -- 64 bytes for a 72-byte `DBusMessageIter`
 * -- and wrote past an arena on every call through two shipped releases. The
 * oracle is the whole answer to that class of defect, and rerunning it is the
 * first step of any libpulse version bump.
 *
 * Taken from libpulse 17.0.0, x86_64. The layouts that matter are small and
 * have been stable for the life of the 1.x ABI; the ones that are not stable
 * (`pa_sink_info` and friends) are read field by field at the offsets below
 * rather than mapped wholesale, so a field appended upstream costs nothing.
 */
internal object PulseAbi {

    // -- pa_sample_spec ------------------------------------------------------

    const val SAMPLE_SPEC_FORMAT = 0L
    const val SAMPLE_SPEC_RATE = 4L
    const val SAMPLE_SPEC_CHANNELS = 8L
    const val SAMPLE_SPEC_SIZE = 12L

    // -- pa_buffer_attr ------------------------------------------------------

    const val BUFFER_ATTR_MAXLENGTH = 0L
    const val BUFFER_ATTR_TLENGTH = 4L
    const val BUFFER_ATTR_PREBUF = 8L
    const val BUFFER_ATTR_MINREQ = 12L
    const val BUFFER_ATTR_FRAGSIZE = 16L
    const val BUFFER_ATTR_SIZE = 20L

    /** Every pa_buffer_attr field takes this to mean "server default". */
    const val ATTR_DEFAULT: Int = -1

    // -- pa_cvolume ----------------------------------------------------------

    const val CVOLUME_SIZE = 132L
    const val CVOLUME_CHANNELS = 0L
    const val VOLUME_NORM = 65_536

    /** `PA_CHANNELS_MAX`. A cvolume claiming more channels than this is invalid. */
    const val CHANNELS_MAX = 32

    // -- pa_sink_info, read field by field -----------------------------------

    const val SINK_INFO_NAME = 0L
    const val SINK_INFO_INDEX = 8L
    const val SINK_INFO_DESCRIPTION = 16L
    const val SINK_INFO_VOLUME = 172L
    const val SINK_INFO_MUTE = 304L
    const val SINK_INFO_STATE = 364L
    const val SINK_INFO_CARD = 372L
    const val SINK_INFO_N_PORTS = 376L
    const val SINK_INFO_PORTS = 384L
    const val SINK_INFO_ACTIVE_PORT = 392L

    /**
     * Enough of pa_sink_info to reach the active port. The struct is 416 bytes
     * and the tail, which is the format list, is not this library's business.
     */
    const val SINK_INFO_HEAD = 400L

    // -- peak metering --------------------------------------------------------

    /**
     * With this flag the server sends one sample per fragment holding the
     * loudest value in it, rather than the audio itself. A meter therefore costs
     * a few bytes a second instead of a copy of the stream.
     */
    const val STREAM_PEAK_DETECT = 2_048

    /**
     * Turns `tlength` from a buffer size into a latency request: the server
     * shortens everything it controls to meet it, rather than treating the
     * number as a hint about how much to hold. Without it a client asking for
     * five milliseconds gets a five millisecond buffer at the end of a path the
     * server sized for itself.
     */
    const val STREAM_ADJUST_LATENCY = 8_192

    /**
     * Bound, printed by the oracle, and deliberately never set.
     *
     * libpulse documents it as mutually exclusive with [STREAM_ADJUST_LATENCY]
     * and as a compatibility mode for clients that sleep on a timer instead of
     * on the device. This backend does the opposite: its write parks in
     * `pa_threaded_mainloop_wait` until the server asks for data, so early
     * requests would buy nothing and would cost the flag that actually shortens
     * the path.
     */
    const val STREAM_EARLY_REQUESTS = 16_384

    /** The monitor must stay on the sink it was aimed at, or the meter follows the wrong audio. */
    const val STREAM_DONT_MOVE = 512

    const val SINK_INFO_MONITOR_SOURCE = 308L
    const val SINK_INFO_MONITOR_SOURCE_NAME = 312L

    /** Enough of pa_sink_info to reach the monitor source name. */
    const val SINK_INFO_MONITOR_HEAD = 320L

    /** 25 windows a second: live to the eye, and nothing to the machine. */
    const val METER_RATE = 25

    const val INVALID_INDEX = -1

    // -- pa_sink_input_info: somebody else's stream ---------------------------

    const val SINK_INPUT_INDEX = 0L
    const val SINK_INPUT_NAME = 8L
    const val SINK_INPUT_SINK = 24L
    const val SINK_INPUT_VOLUME = 172L
    const val SINK_INPUT_MUTE = 336L
    const val SINK_INPUT_PROPLIST = 344L
    const val SINK_INPUT_CORKED = 352L

    /** Only the head is read; the struct is 376 bytes and most of it is not ours. */
    const val SINK_INPUT_HEAD = 360L

    /** Stream properties a mixer shows, read out of the sink input's proplist. */
    const val PROP_MEDIA_NAME = "media.name"
    const val PROP_APPLICATION_PROCESS_BINARY = "application.process.binary"

    /**
     * The pid libpulse stamps on every stream its client opens, without being
     * asked. Comparing it to our own is how a mixer knows which rows are its own
     * process's -- exact, and needing no bookkeeping between the sink that
     * opened a stream and the mixer that lists it.
     */
    const val PROP_APPLICATION_PROCESS_ID = "application.process.id"

    // -- pa_source_info: the capture half of the device list ------------------

    const val SOURCE_INFO_NAME = 0L
    const val SOURCE_INFO_INDEX = 8L
    const val SOURCE_INFO_DESCRIPTION = 16L
    const val SOURCE_INFO_VOLUME = 172L
    const val SOURCE_INFO_MUTE = 304L

    /**
     * `PA_INVALID_INDEX` for a real input, and the owning sink's index for a
     * monitor. A source that is a sink's monitor is not a microphone, and a
     * device list offering "Monitor of Built-in Audio" as an input confuses
     * everyone who reads it. Both are legitimate things to want, so the field
     * is carried rather than used to filter.
     */
    const val SOURCE_INFO_MONITOR_OF_SINK = 308L
    const val SOURCE_INFO_STATE = 364L
    const val SOURCE_INFO_CARD = 372L
    const val SOURCE_INFO_N_PORTS = 376L
    const val SOURCE_INFO_PORTS = 384L
    const val SOURCE_INFO_ACTIVE_PORT = 392L

    /** The same shape and the same size as pa_sink_info, one facility along. */
    const val SOURCE_INFO_HEAD = 400L

    // -- ports, one struct per direction with the same layout -----------------

    const val PORT_INFO_NAME = 0L
    const val PORT_INFO_DESCRIPTION = 8L
    const val PORT_INFO_PRIORITY = 16L
    const val PORT_INFO_AVAILABLE = 20L
    const val PORT_INFO_SIZE = 40L

    /**
     * `PA_PORT_AVAILABLE_NO`. Zero is "this port does not support jack
     * detection", which is not the same as unavailable and is why the check is
     * against this rather than against the truthiness of the field.
     */
    const val PORT_AVAILABLE_NO = 1

    // -- pa_card_info and the profiles a card can be put into -----------------

    const val CARD_INFO_INDEX = 0L
    const val CARD_INFO_NAME = 8L
    const val CARD_INFO_N_PROFILES = 32L
    const val CARD_INFO_PROFILES2 = 80L
    const val CARD_INFO_ACTIVE_PROFILE2 = 88L
    const val CARD_INFO_HEAD = 96L

    /**
     * `profiles2` rather than the deprecated `profiles`: the newer array is
     * pointers rather than inline structs and carries the availability flag,
     * which is what tells a profile that exists apart from one worth offering.
     */
    const val CARD_PROFILE_NAME = 0L
    const val CARD_PROFILE_DESCRIPTION = 8L
    const val CARD_PROFILE_PRIORITY = 24L
    const val CARD_PROFILE_AVAILABLE = 28L
    const val CARD_PROFILE_SIZE = 32L

    // -- pa_source_output_info: somebody else's capture stream ----------------

    const val SOURCE_OUTPUT_INDEX = 0L
    const val SOURCE_OUTPUT_NAME = 8L
    const val SOURCE_OUTPUT_SOURCE = 24L
    const val SOURCE_OUTPUT_PROPLIST = 208L
    const val SOURCE_OUTPUT_CORKED = 216L
    const val SOURCE_OUTPUT_VOLUME = 220L
    const val SOURCE_OUTPUT_MUTE = 352L

    /**
     * Zero means the volume field above holds nothing meaningful. Reading it
     * anyway would draw a slider at a position the server never chose.
     */
    const val SOURCE_OUTPUT_HAS_VOLUME = 356L

    /** Only the head is read; the struct is 376 bytes and the tail is not ours. */
    const val SOURCE_OUTPUT_HEAD = 360L

    // -- device state ---------------------------------------------------------

    /**
     * The server has closed the hardware because nothing is using it. A resting
     * state rather than a fault: a stream opened against a suspended device
     * wakes it.
     */
    const val DEVICE_STATE_SUSPENDED = 2

    // -- pa_module_info, read to answer one question --------------------------

    const val MODULE_INFO_NAME = 8L

    /** Only the head is read; the argument and proplist are not our business. */
    const val MODULE_INFO_HEAD = 16L

    /**
     * The modules that make a media role actually quiet other streams.
     *
     * Whether ducking happens is a fact about how the desktop is configured, not
     * about this library, and the only evidence reachable over the protocol is
     * which modules the server loaded. PipeWire's pulse server reports its own
     * modules rather than these, so the answer there is a truthful no: its
     * policy lives in WirePlumber, which the protocol cannot see.
     */
    val ROLE_POLICY_MODULES = setOf("module-role-ducking", "module-role-cork")

    // -- pa_server_info, for which devices are currently default -------------

    const val SERVER_INFO_DEFAULT_SINK_NAME = 48L
    const val SERVER_INFO_DEFAULT_SOURCE_NAME = 56L

    // -- sample formats ------------------------------------------------------

    const val SAMPLE_U8 = 0
    const val SAMPLE_S16LE = 3
    const val SAMPLE_FLOAT32LE = 5
    const val SAMPLE_S32LE = 7

    /**
     * The packed 24-bit formats, for a device that asks for one.
     *
     * Not reachable from [PcmEncoding] and deliberately: FFmpeg has no 24-bit
     * sample format, so 24-bit content arrives as S32LE with the value in the
     * top bits, and packing it belongs next to a device that wants it packed
     * rather than in the shape a consumer hands over.
     */
    const val SAMPLE_S24LE = 9
    const val SAMPLE_S24_32LE = 11

    const val SAMPLE_INVALID = -1

    /**
     * What libpulse calls the shape a consumer handed us, or null where the
     * server has no name for it.
     *
     * Null is an answer rather than a gap: `pa_sample_format_t` has no 64-bit
     * float at all, so a consumer that sends one has to be refused instead of
     * quietly given something narrower. A sink that accepted a format and
     * played another would be indistinguishable from one that worked.
     */
    fun sampleFormatOf(encoding: PcmEncoding): Int? = when (encoding) {
        PcmEncoding.U8 -> SAMPLE_U8
        PcmEncoding.S16LE -> SAMPLE_S16LE
        PcmEncoding.S32LE -> SAMPLE_S32LE
        PcmEncoding.F32LE -> SAMPLE_FLOAT32LE
        PcmEncoding.F64LE -> null
    }

    /** The same table read the other way, for a consumer that asks before it opens. */
    val ACCEPTED_ENCODINGS: Set<PcmEncoding> =
        PcmEncoding.entries.filterTo(LinkedHashSet()) { sampleFormatOf(it) != null }

    // -- pa_channel_map ------------------------------------------------------

    /** `channels` is a byte at 0, then three of padding, then 32 ints. */
    const val CHANNEL_MAP_CHANNELS = 0L
    const val CHANNEL_MAP_MAP = 4L
    const val CHANNEL_MAP_SIZE = 132L

    /**
     * `pa_channel_position_t`, printed by the oracle and not contiguous: the
     * eleven ordinary positions run from 1 to 11 and the height ones resume at
     * 44, with the auxiliary channels in between.
     */
    /** Not the same as front-centre: a mono stream is spread, a centre one is pinned. */
    const val CHANNEL_POSITION_MONO = 0
    const val CHANNEL_POSITION_FRONT_LEFT = 1
    const val CHANNEL_POSITION_FRONT_RIGHT = 2
    const val CHANNEL_POSITION_FRONT_CENTER = 3
    const val CHANNEL_POSITION_REAR_CENTER = 4
    const val CHANNEL_POSITION_REAR_LEFT = 5
    const val CHANNEL_POSITION_REAR_RIGHT = 6
    const val CHANNEL_POSITION_LFE = 7
    const val CHANNEL_POSITION_FRONT_LEFT_OF_CENTER = 8
    const val CHANNEL_POSITION_FRONT_RIGHT_OF_CENTER = 9
    const val CHANNEL_POSITION_SIDE_LEFT = 10
    const val CHANNEL_POSITION_SIDE_RIGHT = 11
    const val CHANNEL_POSITION_TOP_CENTER = 44
    const val CHANNEL_POSITION_TOP_FRONT_LEFT = 45
    const val CHANNEL_POSITION_TOP_FRONT_RIGHT = 46
    const val CHANNEL_POSITION_TOP_FRONT_CENTER = 47
    const val CHANNEL_POSITION_TOP_REAR_LEFT = 48
    const val CHANNEL_POSITION_TOP_REAR_RIGHT = 49
    const val CHANNEL_POSITION_TOP_REAR_CENTER = 50

    /**
     * What libpulse calls a position, or null where it has no name for it.
     *
     * Eighteen of the thirty-six positions FFmpeg names have an equivalent here.
     * The rest are wide, downmix, binaural, a second LFE and the bottom row, and
     * a null is what makes them a refusal rather than a channel placed
     * somewhere nobody chose.
     *
     * The names differ where the concepts agree: what FFmpeg calls back is what
     * libpulse calls rear, and the two mean the same speaker.
     */
    fun channelPositionOf(position: ChannelPosition): Int? = when (position) {
        ChannelPosition.FL -> CHANNEL_POSITION_FRONT_LEFT
        ChannelPosition.FR -> CHANNEL_POSITION_FRONT_RIGHT
        ChannelPosition.FC -> CHANNEL_POSITION_FRONT_CENTER
        ChannelPosition.LFE -> CHANNEL_POSITION_LFE
        ChannelPosition.BL -> CHANNEL_POSITION_REAR_LEFT
        ChannelPosition.BR -> CHANNEL_POSITION_REAR_RIGHT
        ChannelPosition.BC -> CHANNEL_POSITION_REAR_CENTER
        ChannelPosition.FLC -> CHANNEL_POSITION_FRONT_LEFT_OF_CENTER
        ChannelPosition.FRC -> CHANNEL_POSITION_FRONT_RIGHT_OF_CENTER
        ChannelPosition.SL -> CHANNEL_POSITION_SIDE_LEFT
        ChannelPosition.SR -> CHANNEL_POSITION_SIDE_RIGHT
        ChannelPosition.TC -> CHANNEL_POSITION_TOP_CENTER
        ChannelPosition.TFL -> CHANNEL_POSITION_TOP_FRONT_LEFT
        ChannelPosition.TFC -> CHANNEL_POSITION_TOP_FRONT_CENTER
        ChannelPosition.TFR -> CHANNEL_POSITION_TOP_FRONT_RIGHT
        ChannelPosition.TBL -> CHANNEL_POSITION_TOP_REAR_LEFT
        ChannelPosition.TBC -> CHANNEL_POSITION_TOP_REAR_CENTER
        ChannelPosition.TBR -> CHANNEL_POSITION_TOP_REAR_RIGHT
        ChannelPosition.DL, ChannelPosition.DR,
        ChannelPosition.WL, ChannelPosition.WR,
        ChannelPosition.SDL, ChannelPosition.SDR,
        ChannelPosition.LFE2,
        ChannelPosition.TSL, ChannelPosition.TSR,
        ChannelPosition.BFC, ChannelPosition.BFL, ChannelPosition.BFR,
        ChannelPosition.SSL, ChannelPosition.SSR,
        ChannelPosition.TTL, ChannelPosition.TTR,
        ChannelPosition.BIL, ChannelPosition.BIR,
        -> null
    }

    /**
     * What libpulse spells a position, printed by `pa_channel_position_to_string`.
     *
     * Needed as text as well as as a number, because a module argument carries
     * a channel map as a comma-separated list of these. A name written from
     * memory is refused at load time with a message nobody reads, and the
     * device then simply does not exist.
     */
    fun channelNameOf(position: ChannelPosition): String? = when (channelPositionOf(position)) {
        CHANNEL_POSITION_FRONT_LEFT -> "front-left"
        CHANNEL_POSITION_FRONT_RIGHT -> "front-right"
        CHANNEL_POSITION_FRONT_CENTER -> "front-center"
        CHANNEL_POSITION_REAR_CENTER -> "rear-center"
        CHANNEL_POSITION_REAR_LEFT -> "rear-left"
        CHANNEL_POSITION_REAR_RIGHT -> "rear-right"
        CHANNEL_POSITION_LFE -> "lfe"
        CHANNEL_POSITION_FRONT_LEFT_OF_CENTER -> "front-left-of-center"
        CHANNEL_POSITION_FRONT_RIGHT_OF_CENTER -> "front-right-of-center"
        CHANNEL_POSITION_SIDE_LEFT -> "side-left"
        CHANNEL_POSITION_SIDE_RIGHT -> "side-right"
        CHANNEL_POSITION_TOP_CENTER -> "top-center"
        CHANNEL_POSITION_TOP_FRONT_LEFT -> "top-front-left"
        CHANNEL_POSITION_TOP_FRONT_RIGHT -> "top-front-right"
        CHANNEL_POSITION_TOP_FRONT_CENTER -> "top-front-center"
        CHANNEL_POSITION_TOP_REAR_LEFT -> "top-rear-left"
        CHANNEL_POSITION_TOP_REAR_RIGHT -> "top-rear-right"
        CHANNEL_POSITION_TOP_REAR_CENTER -> "top-rear-center"
        else -> null
    }

    /**
     * A whole layout as the text a module argument takes, or null where a
     * position has no name here.
     *
     * One channel is `mono` rather than `front-center`, the same translation
     * [PulseChannelMap] makes for the same reason: libpulse keeps the two apart
     * so a mono stream is spread and a centre one is pinned.
     */
    fun channelMapTextOf(layout: ChannelLayout): String? {
        if (!layout.isSpecified) return null
        if (layout.channels == 1) return "mono"
        val names = layout.positions.map { channelNameOf(it) ?: return null }
        return names.joinToString(",")
    }

    /**
     * The first position this server cannot name, or null when it can name them
     * all.
     *
     * What a refusal is built from: a consumer told which channel could not be
     * placed can send the layout as a bare count and take the server's own
     * ordering, or drop to something the server does understand.
     */
    fun unplaceable(layout: ChannelLayout): ChannelPosition? =
        layout.positions.firstOrNull { channelPositionOf(it) == null }

    // -- context state -------------------------------------------------------

    const val CONTEXT_READY = 4
    const val CONTEXT_FAILED = 5
    const val CONTEXT_TERMINATED = 6
    const val CONTEXT_NOFLAGS = 0

    // -- stream state --------------------------------------------------------

    const val STREAM_READY = 2
    const val STREAM_FAILED = 3
    const val STREAM_TERMINATED = 4

    // -- stream flags --------------------------------------------------------

    const val STREAM_START_CORKED = 1
    const val STREAM_INTERPOLATE_TIMING = 2
    const val STREAM_AUTO_TIMING_UPDATE = 8

    /**
     * The pair without which `pa_stream_get_time` answers `-PA_ERR_NODATA` for
     * the life of the stream, however often it is asked. AUTO_TIMING_UPDATE has
     * the server push timing blocks unprompted; INTERPOLATE_TIMING fills the
     * gaps between them locally, which is what makes reading the playhead cheap
     * enough to do per frame.
     */
    const val STREAM_TIMING_FLAGS = STREAM_INTERPOLATE_TIMING or STREAM_AUTO_TIMING_UPDATE

    // -- misc ----------------------------------------------------------------

    const val SEEK_RELATIVE = 0

    /** `pa_stream_writable_size` returns this on failure. */
    const val SIZE_ERROR = -1L

    const val ERR_NODATA = 16

    // -- subscription --------------------------------------------------------

    const val SUBSCRIPTION_MASK_SINK = 0x0001

    /**
     * 0x0080, not 0x0100. The first cut used 0x0100, which is the deprecated
     * `PA_SUBSCRIPTION_MASK_AUTOLOAD` -- so the server-change event was never
     * subscribed and a default-sink switch reached no listener, while
     * `Capability.DEVICE_EVENTS` still claimed it did.
     *
     * This and every other subscription constant below are printed by the
     * oracle. They were not, for a while, and this comment claimed they were
     * checked against the header -- true of how they were written, and not of
     * anything a build could catch.
     */
    const val SUBSCRIPTION_MASK_SERVER = 0x0080
    const val SUBSCRIPTION_MASK_SINK_INPUT = 0x0004
    const val SUBSCRIPTION_MASK_SOURCE = 0x0002
    const val SUBSCRIPTION_MASK_SOURCE_OUTPUT = 0x0008

    /**
     * A subscription event packs the facility and the kind into one int. The
     * low nibble says what changed, bits 4 and 5 say how, and reading either
     * without masking gives a number that matches nothing.
     */
    const val SUBSCRIPTION_EVENT_FACILITY_MASK = 0x000F
    const val SUBSCRIPTION_EVENT_TYPE_MASK = 0x0030
    const val SUBSCRIPTION_EVENT_SINK_INPUT = 0x0002
    const val SUBSCRIPTION_EVENT_SOURCE_OUTPUT = 0x0003
    const val SUBSCRIPTION_EVENT_NEW = 0x0000
    const val SUBSCRIPTION_EVENT_CHANGE = 0x0010
    const val SUBSCRIPTION_EVENT_REMOVE = 0x0020

    /** Properties the stream carries so the desktop can name and route it. */
    const val PROP_APPLICATION_NAME = "application.name"
    const val PROP_APPLICATION_ID = "application.id"
    const val PROP_APPLICATION_ICON_NAME = "application.icon_name"
    const val PROP_MEDIA_ROLE = "media.role"
}
