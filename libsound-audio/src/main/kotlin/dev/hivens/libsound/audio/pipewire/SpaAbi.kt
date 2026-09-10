package dev.hivens.libsound.audio.pipewire

import dev.hivens.libsound.ChannelPosition
import dev.hivens.libsound.PcmEncoding

/**
 * Constants and layouts for the PipeWire subset a native backend binds.
 *
 * Every number here was printed by `tools/pipewire-oracle.c` against the
 * installed headers, none of it is remembered, and one part of it can be
 * checked rather than merely transcribed: the oracle also builds two real PODs
 * with the library's own builder and dumps the bytes, so [SpaPod] is right when
 * it emits the same ones. That is the only oracle in this repository whose
 * output is executable, and it exists because the alternative is worse than
 * usual here.
 *
 * ## Why a POD has to be written by hand at all
 *
 * PipeWire negotiates formats with serialised objects, and the API for building
 * them is inline C macros over a `spa_pod_builder`. Panama can call a function
 * and cannot call a macro, so a binding emits the bytes itself. There is no
 * symbol to bind that would do it, which is what makes this different from
 * every other table here.
 *
 * Taken from pipewire 1.6.8, x86_64.
 */
internal object SpaAbi {

    // -- POD layout ----------------------------------------------------------

    /** `struct spa_pod` is a size and a type, and every body is padded to [POD_ALIGN]. */
    const val POD_HEADER_SIZE = 8
    const val POD_SIZE_OFFSET = 0
    const val POD_TYPE_OFFSET = 4

    /** An object's body starts with its own type and an id, then the properties. */
    const val POD_OBJECT_BODY_SIZE = 8

    /** A property is a key, flags, and one value pod. Not itself a pod. */
    const val POD_PROP_HEADER_SIZE = 8

    const val POD_ALIGN = 8

    // -- spa_dict, which is how properties cross without varargs -------------

    /**
     * `pw_properties_new` is variadic, and a Panama downcall to a variadic
     * function needs a descriptor per call shape. A dict needs none, so the
     * properties are built as one and handed to `pw_properties_new_dict`.
     */
    const val DICT_SIZE = 16L
    const val DICT_FLAGS = 0L
    const val DICT_N_ITEMS = 4L
    const val DICT_ITEMS = 8L

    const val DICT_ITEM_SIZE = 16L
    const val DICT_ITEM_KEY = 0L
    const val DICT_ITEM_VALUE = 8L

    // -- pw_stream_events, a vtable by another name --------------------------

    /**
     * Handed over whole, and the library calls whatever is in it. So the size
     * is as load bearing as the offsets: a struct shorter than this leaves the
     * loop calling through whatever memory follows the allocation, and the
     * offsets are deliberately not eight apart everywhere, which is what a
     * hand-counted version of this table gets wrong.
     */
    const val STREAM_EVENTS_SIZE = 96L
    const val STREAM_EVENTS_VERSION = 0L
    const val STREAM_EVENTS_DESTROY = 8L
    const val STREAM_EVENTS_STATE_CHANGED = 16L
    const val STREAM_EVENTS_CONTROL_INFO = 24L
    const val STREAM_EVENTS_IO_CHANGED = 32L
    const val STREAM_EVENTS_PARAM_CHANGED = 40L
    const val STREAM_EVENTS_ADD_BUFFER = 48L
    const val STREAM_EVENTS_REMOVE_BUFFER = 56L
    const val STREAM_EVENTS_PROCESS = 64L
    const val STREAM_EVENTS_DRAINED = 72L
    const val STREAM_EVENTS_COMMAND = 80L
    const val STREAM_EVENTS_TRIGGER_DONE = 88L

    /** The version the struct declares, which the library checks before calling anything. */
    const val VERSION_STREAM_EVENTS = 2

    // -- stream states and flags ---------------------------------------------

    const val STREAM_STATE_ERROR = -1
    const val STREAM_STATE_UNCONNECTED = 0
    const val STREAM_STATE_CONNECTING = 1
    const val STREAM_STATE_PAUSED = 2
    const val STREAM_STATE_STREAMING = 3

    const val STREAM_FLAG_AUTOCONNECT = 0x0000_0001
    const val STREAM_FLAG_INACTIVE = 0x0000_0002
    const val STREAM_FLAG_MAP_BUFFERS = 0x0000_0004
    const val STREAM_FLAG_RT_PROCESS = 0x0000_0010

    /** `PW_ID_ANY`, which is how a stream says it does not name a target. */
    const val ID_ANY = -1

    // -- buffers, which is where the audio actually is -----------------------

    const val PW_BUFFER_BUFFER = 0L
    const val PW_BUFFER_SIZE = 16L
    const val PW_BUFFER_REQUESTED = 24L
    const val PW_BUFFER_HEAD = 32L

    const val SPA_BUFFER_N_DATAS = 4L
    const val SPA_BUFFER_DATAS = 16L
    const val SPA_BUFFER_HEAD = 24L

    const val SPA_DATA_SIZE = 40L
    const val SPA_DATA_MAXSIZE = 20L
    const val SPA_DATA_DATA = 24L
    const val SPA_DATA_CHUNK = 32L

    const val SPA_CHUNK_SIZE = 16L
    const val SPA_CHUNK_OFFSET = 0L
    const val SPA_CHUNK_LENGTH = 4L
    const val SPA_CHUNK_STRIDE = 8L

    // -- pw_time, the playhead and the latency -------------------------------

    const val TIME_SIZE = 64L
    const val TIME_NOW = 0L
    const val TIME_RATE_NUM = 8L
    const val TIME_RATE_DENOM = 12L
    const val TIME_TICKS = 16L

    /** The graph's own share, in the units [TIME_RATE_NUM] and its denominator give. */
    const val TIME_DELAY = 24L

    /** Frames handed over and not yet played, which is this client's share. */
    const val TIME_QUEUED = 32L
    const val TIME_BUFFERED = 40L

    // -- calling a proxy method by hand --------------------------------------

    /**
     * A proxy pointer is a `spa_interface`, whose callbacks point at the
     * interface's method table, and the headers read a function out of it
     * through a macro. Panama cannot call a macro, so a binding walks the
     * layout: the same discipline the WASAPI vtable indices are held to, and
     * the same failure when it is wrong, which is a call through a function
     * that is not the one meant.
     */
    const val INTERFACE_CB_FUNCS = 16L

    /** `pw_core_methods`, where `get_registry` lives. */
    const val CORE_METHOD_GET_REGISTRY = 48L

    /** `pw_registry_methods`, where `bind` lives. */
    const val REGISTRY_METHOD_BIND = 16L
    const val REGISTRY_METHOD_DESTROY = 24L

    const val VERSION_CORE = 4
    const val VERSION_REGISTRY = 3

    // -- registry events -----------------------------------------------------

    /**
     * Three slots. A global arrives with its whole property dict attached,
     * which is what makes a device list need no method call at all: the answer
     * is in the event.
     */
    const val REGISTRY_EVENTS_SIZE = 24L
    const val REGISTRY_EVENTS_VERSION = 0L
    const val REGISTRY_EVENTS_GLOBAL = 8L
    const val REGISTRY_EVENTS_GLOBAL_REMOVE = 16L
    const val VERSION_REGISTRY_EVENTS = 0

    /** What a global says it is. A node is what a device list is made of. */
    const val INTERFACE_CORE = "PipeWire:Interface:Core"
    const val INTERFACE_NODE = "PipeWire:Interface:Node"
    const val INTERFACE_DEVICE = "PipeWire:Interface:Device"
    const val INTERFACE_METADATA = "PipeWire:Interface:Metadata"

    /** `struct spa_hook`, which a listener is registered through and which the caller owns. */
    const val HOOK_SIZE = 48L

    // -- properties a volume is set through ----------------------------------

    const val OBJECT_PROPS = 262_146
    const val PARAM_PROPS = 2
    const val PROP_VOLUME = 65_539
    const val PROP_MUTE = 65_540
    const val PROP_CHANNEL_VOLUMES = 65_544

    // -- property keys -------------------------------------------------------

    /**
     * What a node says it is, and the whole of what a device list filters on.
     * `Audio/Sink` and `Audio/Source` are devices; `Stream/Output/Audio` and
     * its sibling are somebody playing or recording.
     */
    const val KEY_MEDIA_CLASS = "media.class"
    const val MEDIA_CLASS_SINK = "Audio/Sink"
    const val MEDIA_CLASS_SOURCE = "Audio/Source"

    const val KEY_OBJECT_SERIAL = "object.serial"
    const val KEY_NODE_NICK = "node.nick"
    const val KEY_DEVICE_DESCRIPTION = "device.description"

    const val KEY_MEDIA_TYPE = "media.type"
    const val KEY_MEDIA_CATEGORY = "media.category"
    const val KEY_MEDIA_ROLE = "media.role"
    const val KEY_APP_NAME = "application.name"
    const val KEY_APP_ID = "application.id"
    const val KEY_APP_ICON_NAME = "application.icon-name"
    const val KEY_NODE_NAME = "node.name"
    const val KEY_NODE_DESCRIPTION = "node.description"

    /**
     * The lever section 4.4 measured `pipewire-pulse` overwriting. A node sets
     * it and keeps it; a pulse client sets it and has the shim recompute it
     * from the buffer size that client asked for.
     */
    const val KEY_NODE_LATENCY = "node.latency"
    const val KEY_NODE_RATE = "node.rate"
    const val KEY_TARGET_OBJECT = "target.object"

    // -- POD types -----------------------------------------------------------

    const val TYPE_NONE = 1
    const val TYPE_BOOL = 2
    const val TYPE_ID = 3
    const val TYPE_INT = 4
    const val TYPE_LONG = 5
    const val TYPE_FLOAT = 6
    const val TYPE_STRING = 8
    const val TYPE_BYTES = 9
    const val TYPE_ARRAY = 13
    const val TYPE_OBJECT = 15

    /**
     * Object types, which are not small integers and are the ones worth
     * distrusting: a wrong one is an object the server does not recognise,
     * which it answers by ignoring rather than by complaining.
     */
    const val OBJECT_FORMAT = 262_147
    const val OBJECT_PARAM_LATENCY = 262_155

    // -- parameter ids -------------------------------------------------------

    const val PARAM_ENUM_FORMAT = 3
    const val PARAM_FORMAT = 4
    const val PARAM_LATENCY = 15

    // -- format keys ---------------------------------------------------------

    const val FORMAT_MEDIA_TYPE = 1
    const val FORMAT_MEDIA_SUBTYPE = 2
    const val FORMAT_AUDIO_FORMAT = 65_537
    const val FORMAT_AUDIO_RATE = 65_539
    const val FORMAT_AUDIO_CHANNELS = 65_540
    const val FORMAT_AUDIO_POSITION = 65_541

    const val MEDIA_TYPE_AUDIO = 1
    const val MEDIA_SUBTYPE_RAW = 1

    // -- latency keys --------------------------------------------------------

    const val LATENCY_DIRECTION = 1
    const val LATENCY_MIN_QUANTUM = 2
    const val LATENCY_MAX_QUANTUM = 3
    const val LATENCY_MIN_RATE = 4
    const val LATENCY_MAX_RATE = 5
    const val LATENCY_MIN_NS = 6
    const val LATENCY_MAX_NS = 7

    const val DIRECTION_INPUT = 0
    const val DIRECTION_OUTPUT = 1

    // -- sample formats ------------------------------------------------------

    const val AUDIO_FORMAT_UNKNOWN = 0
    const val AUDIO_FORMAT_U8 = 258
    const val AUDIO_FORMAT_S16_LE = 259
    const val AUDIO_FORMAT_S24_32_LE = 263
    const val AUDIO_FORMAT_S32_LE = 267
    const val AUDIO_FORMAT_S24_LE = 271
    const val AUDIO_FORMAT_F32_LE = 283
    const val AUDIO_FORMAT_F64_LE = 285

    /**
     * What the graph calls the shape a consumer handed us.
     *
     * Total, with no null in it, which is the difference this backend exists
     * for. The libpulse path has to refuse [PcmEncoding.F64LE] because
     * `pa_sample_format_t` has no 64-bit float at all, and that refusal is the
     * compatibility layer's rather than the platform's.
     *
     * The packed 24-bit formats are named above and unreachable from here for
     * the reason they are unreachable from the libpulse table: FFmpeg has no
     * 24-bit sample format, so nothing produces one to send.
     */
    fun audioFormatOf(encoding: PcmEncoding): Int = when (encoding) {
        PcmEncoding.U8 -> AUDIO_FORMAT_U8
        PcmEncoding.S16LE -> AUDIO_FORMAT_S16_LE
        PcmEncoding.S32LE -> AUDIO_FORMAT_S32_LE
        PcmEncoding.F32LE -> AUDIO_FORMAT_F32_LE
        PcmEncoding.F64LE -> AUDIO_FORMAT_F64_LE
    }

    // -- channel positions ---------------------------------------------------

    const val CHANNEL_UNKNOWN = 0
    const val CHANNEL_FL = 3
    const val CHANNEL_FR = 4
    const val CHANNEL_FC = 5
    const val CHANNEL_LFE = 6
    const val CHANNEL_SL = 7
    const val CHANNEL_SR = 8
    const val CHANNEL_FLC = 9
    const val CHANNEL_FRC = 10
    const val CHANNEL_RC = 11
    const val CHANNEL_RL = 12
    const val CHANNEL_RR = 13
    const val CHANNEL_TC = 14
    const val CHANNEL_TFL = 15
    const val CHANNEL_TFC = 16
    const val CHANNEL_TFR = 17
    const val CHANNEL_TRL = 18
    const val CHANNEL_TRC = 19
    const val CHANNEL_TRR = 20
    const val CHANNEL_FLW = 23
    const val CHANNEL_FRW = 24
    const val CHANNEL_LFE2 = 25
    const val CHANNEL_TSL = 31
    const val CHANNEL_TSR = 32
    const val CHANNEL_BC = 35
    const val CHANNEL_BLC = 36
    const val CHANNEL_BRC = 37

    /**
     * What the graph calls a position, or null where it has no name for it.
     *
     * Twenty-six of the thirty-six FFmpeg names, against eighteen through
     * `pipewire-pulse`. The eight the compatibility layer costs are the wide
     * pair, the second low frequency channel, the top side pair and the bottom
     * row, and between them they are the difference between carrying `9.1.6`,
     * `7.2.3` and `hexadecagonal` and refusing them.
     *
     * The names differ where the concepts agree, as they do everywhere else
     * here: what FFmpeg calls back the graph calls rear, and what FFmpeg calls
     * bottom-front the graph calls bottom.
     *
     * The ten with no equivalent are the downmix pair, surround direct, side
     * surround, top surround and binaural. Nothing in the graph's list stands
     * for any of them, and a position placed as its nearest neighbour would be
     * the quiet mis-placement this whole mechanism exists to stop.
     */
    fun channelOf(position: ChannelPosition): Int? = when (position) {
        ChannelPosition.FL -> CHANNEL_FL
        ChannelPosition.FR -> CHANNEL_FR
        ChannelPosition.FC -> CHANNEL_FC
        ChannelPosition.LFE -> CHANNEL_LFE
        ChannelPosition.SL -> CHANNEL_SL
        ChannelPosition.SR -> CHANNEL_SR
        ChannelPosition.FLC -> CHANNEL_FLC
        ChannelPosition.FRC -> CHANNEL_FRC
        ChannelPosition.BC -> CHANNEL_RC
        ChannelPosition.BL -> CHANNEL_RL
        ChannelPosition.BR -> CHANNEL_RR
        ChannelPosition.TC -> CHANNEL_TC
        ChannelPosition.TFL -> CHANNEL_TFL
        ChannelPosition.TFC -> CHANNEL_TFC
        ChannelPosition.TFR -> CHANNEL_TFR
        ChannelPosition.TBL -> CHANNEL_TRL
        ChannelPosition.TBC -> CHANNEL_TRC
        ChannelPosition.TBR -> CHANNEL_TRR
        ChannelPosition.WL -> CHANNEL_FLW
        ChannelPosition.WR -> CHANNEL_FRW
        ChannelPosition.LFE2 -> CHANNEL_LFE2
        ChannelPosition.TSL -> CHANNEL_TSL
        ChannelPosition.TSR -> CHANNEL_TSR
        ChannelPosition.BFC -> CHANNEL_BC
        ChannelPosition.BFL -> CHANNEL_BLC
        ChannelPosition.BFR -> CHANNEL_BRC
        ChannelPosition.DL, ChannelPosition.DR,
        ChannelPosition.SDL, ChannelPosition.SDR,
        ChannelPosition.SSL, ChannelPosition.SSR,
        ChannelPosition.TTL, ChannelPosition.TTR,
        ChannelPosition.BIL, ChannelPosition.BIR,
        -> null
    }
}
