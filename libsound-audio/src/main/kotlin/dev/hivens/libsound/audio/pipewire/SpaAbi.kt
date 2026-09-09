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
