package dev.hivens.libsound

/**
 * Where a channel sits, by the names FFmpeg uses.
 *
 * Read from `ffmpeg -layouts` rather than written from memory, for the reason
 * every ABI number here comes from an oracle: a decoder sends these names, and
 * a position understood as the wrong one puts the rear of a film in the sides.
 * `tools/ffmpeg-layout-oracle.c` prints the same table plus the part `-layouts`
 * does not, which is what a bare channel count means.
 */
public enum class ChannelPosition(
    /** The name FFmpeg prints for this position. */
    public val ffmpegName: String,
) {
    /** Front left. */
    FL("FL"),
    /** Front right. */
    FR("FR"),
    /** Front center. */
    FC("FC"),
    /** Low frequency. */
    LFE("LFE"),
    /** Back left. */
    BL("BL"),
    /** Back right. */
    BR("BR"),
    /** Front left-of-center. */
    FLC("FLC"),
    /** Front right-of-center. */
    FRC("FRC"),
    /** Back center. */
    BC("BC"),
    /** Side left. */
    SL("SL"),
    /** Side right. */
    SR("SR"),
    /** Top center. */
    TC("TC"),
    /** Top front left. */
    TFL("TFL"),
    /** Top front center. */
    TFC("TFC"),
    /** Top front right. */
    TFR("TFR"),
    /** Top back left. */
    TBL("TBL"),
    /** Top back center. */
    TBC("TBC"),
    /** Top back right. */
    TBR("TBR"),
    /** Downmix left. */
    DL("DL"),
    /** Downmix right. */
    DR("DR"),
    /** Wide left. */
    WL("WL"),
    /** Wide right. */
    WR("WR"),
    /** Surround direct left. */
    SDL("SDL"),
    /** Surround direct right. */
    SDR("SDR"),
    /** Low frequency 2. */
    LFE2("LFE2"),
    /** Top side left. */
    TSL("TSL"),
    /** Top side right. */
    TSR("TSR"),
    /** Bottom front center. */
    BFC("BFC"),
    /** Bottom front left. */
    BFL("BFL"),
    /** Bottom front right. */
    BFR("BFR"),
    /** Side surround left. */
    SSL("SSL"),
    /** Side surround right. */
    SSR("SSR"),
    /** Top surround left. */
    TTL("TTL"),
    /** Top surround right. */
    TTR("TTR"),
    /** Binaural left. */
    BIL("BIL"),
    /** Binaural right. */
    BIR("BIR"),
}

/**
 * How many channels a stream carries and, where it says, what each one is.
 *
 * Counting is not enough. Six channels is `5.1` or `5.1(side)` and they differ
 * in whether the last pair is the rear or the sides, so a player that lays them
 * out by count alone turns a film's rear channels into its side ones. The
 * decomposition is the interleaving order.
 *
 * A stream may also carry no layout at all, and then it is a count and nothing
 * more: FFmpeg answers `"9 channels"` with every position unset, and so does
 * this. [isSpecified] is the question to ask before trusting [positions], and
 * an unspecified layout is not a failure, it is what the media said.
 */
public class ChannelLayout private constructor(
    /** What FFmpeg calls it, which is what arrives across the seam. */
    public val name: String,
    /** How many samples one frame carries. */
    public val channels: Int,
    /** What each channel is, in interleaving order. Empty where the stream did not say. */
    public val positions: List<ChannelPosition>,
) {
    /** Whether this layout names its channels or only counts them. */
    public val isSpecified: Boolean get() = positions.isNotEmpty()

    /**
     * Two layouts are equal when they carry the same name and the same
     * positions.
     *
     * The count is not compared because it cannot differ once those two agree:
     * a named layout's count is the length of its positions, and an unspecified
     * one carries the count in its name.
     */
    override fun equals(other: Any?): Boolean =
        other is ChannelLayout && other.name == name && other.positions == positions

    /** The name's and the positions', so equal layouts hash alike. */
    override fun hashCode(): Int = 31 * name.hashCode() + positions.hashCode()

    /** The FFmpeg name, so a log line reads as the layout rather than as a wrapper. */
    override fun toString(): String = name

    /** The layouts a decoder can name, and the two ways of getting one. */
    public companion object {
        private fun layout(name: String, vararg positions: ChannelPosition): ChannelLayout =
            ChannelLayout(name, positions.size, positions.toList())

        /** A count with no positions, which is what a stream that declared none carries. */
        public fun unspecified(channels: Int): ChannelLayout {
            require(channels > 0) { "channels must be positive, was $channels" }
            return ChannelLayout(if (channels == 1) "1 channel" else "$channels channels", channels, emptyList())
        }

        /** Every layout FFmpeg names, keyed the way it names them. */
        public val STANDARD: Map<String, ChannelLayout> = listOf(
            layout("mono", ChannelPosition.FC),
            layout("stereo", ChannelPosition.FL, ChannelPosition.FR),
            layout("2.1", ChannelPosition.FL, ChannelPosition.FR, ChannelPosition.LFE),
            layout("3.0", ChannelPosition.FL, ChannelPosition.FR, ChannelPosition.FC),
            layout("3.0(back)", ChannelPosition.FL, ChannelPosition.FR, ChannelPosition.BC),
            layout("4.0", ChannelPosition.FL, ChannelPosition.FR, ChannelPosition.FC, ChannelPosition.BC),
            layout("quad", ChannelPosition.FL, ChannelPosition.FR, ChannelPosition.BL, ChannelPosition.BR),
            layout("quad(side)", ChannelPosition.FL, ChannelPosition.FR, ChannelPosition.SL, ChannelPosition.SR),
            layout("3.1", ChannelPosition.FL, ChannelPosition.FR, ChannelPosition.FC, ChannelPosition.LFE),
            layout("5.0", ChannelPosition.FL, ChannelPosition.FR, ChannelPosition.FC, ChannelPosition.BL, ChannelPosition.BR),
            layout("5.0(side)", ChannelPosition.FL, ChannelPosition.FR, ChannelPosition.FC, ChannelPosition.SL, ChannelPosition.SR),
            layout("4.1", ChannelPosition.FL, ChannelPosition.FR, ChannelPosition.FC, ChannelPosition.LFE, ChannelPosition.BC),
            layout("5.1", ChannelPosition.FL, ChannelPosition.FR, ChannelPosition.FC, ChannelPosition.LFE, ChannelPosition.BL, ChannelPosition.BR),
            layout("5.1(side)", ChannelPosition.FL, ChannelPosition.FR, ChannelPosition.FC, ChannelPosition.LFE, ChannelPosition.SL, ChannelPosition.SR),
            layout("6.0", ChannelPosition.FL, ChannelPosition.FR, ChannelPosition.FC, ChannelPosition.BC, ChannelPosition.SL, ChannelPosition.SR),
            layout("6.0(front)", ChannelPosition.FL, ChannelPosition.FR, ChannelPosition.FLC, ChannelPosition.FRC, ChannelPosition.SL, ChannelPosition.SR),
            layout("3.1.2", ChannelPosition.FL, ChannelPosition.FR, ChannelPosition.FC, ChannelPosition.LFE, ChannelPosition.TFL, ChannelPosition.TFR),
            layout("hexagonal", ChannelPosition.FL, ChannelPosition.FR, ChannelPosition.FC, ChannelPosition.BL, ChannelPosition.BR, ChannelPosition.BC),
            layout("6.1", ChannelPosition.FL, ChannelPosition.FR, ChannelPosition.FC, ChannelPosition.LFE, ChannelPosition.BC, ChannelPosition.SL, ChannelPosition.SR),
            layout("6.1(back)", ChannelPosition.FL, ChannelPosition.FR, ChannelPosition.FC, ChannelPosition.LFE, ChannelPosition.BL, ChannelPosition.BR, ChannelPosition.BC),
            layout("6.1(front)", ChannelPosition.FL, ChannelPosition.FR, ChannelPosition.LFE, ChannelPosition.FLC, ChannelPosition.FRC, ChannelPosition.SL, ChannelPosition.SR),
            layout("7.0", ChannelPosition.FL, ChannelPosition.FR, ChannelPosition.FC, ChannelPosition.BL, ChannelPosition.BR, ChannelPosition.SL, ChannelPosition.SR),
            layout("7.0(front)", ChannelPosition.FL, ChannelPosition.FR, ChannelPosition.FC, ChannelPosition.FLC, ChannelPosition.FRC, ChannelPosition.SL, ChannelPosition.SR),
            layout("7.1", ChannelPosition.FL, ChannelPosition.FR, ChannelPosition.FC, ChannelPosition.LFE, ChannelPosition.BL, ChannelPosition.BR, ChannelPosition.SL, ChannelPosition.SR),
            layout("7.1(wide)", ChannelPosition.FL, ChannelPosition.FR, ChannelPosition.FC, ChannelPosition.LFE, ChannelPosition.BL, ChannelPosition.BR, ChannelPosition.FLC, ChannelPosition.FRC),
            layout("7.1(wide-side)", ChannelPosition.FL, ChannelPosition.FR, ChannelPosition.FC, ChannelPosition.LFE, ChannelPosition.FLC, ChannelPosition.FRC, ChannelPosition.SL, ChannelPosition.SR),
            layout("5.1.2", ChannelPosition.FL, ChannelPosition.FR, ChannelPosition.FC, ChannelPosition.LFE, ChannelPosition.SL, ChannelPosition.SR, ChannelPosition.TFL, ChannelPosition.TFR),
            layout("5.1.2(back)", ChannelPosition.FL, ChannelPosition.FR, ChannelPosition.FC, ChannelPosition.LFE, ChannelPosition.BL, ChannelPosition.BR, ChannelPosition.TFL, ChannelPosition.TFR),
            layout("octagonal", ChannelPosition.FL, ChannelPosition.FR, ChannelPosition.FC, ChannelPosition.BL, ChannelPosition.BR, ChannelPosition.BC, ChannelPosition.SL, ChannelPosition.SR),
            layout("cube", ChannelPosition.FL, ChannelPosition.FR, ChannelPosition.BL, ChannelPosition.BR, ChannelPosition.TFL, ChannelPosition.TFR, ChannelPosition.TBL, ChannelPosition.TBR),
            layout("5.1.4", ChannelPosition.FL, ChannelPosition.FR, ChannelPosition.FC, ChannelPosition.LFE, ChannelPosition.SL, ChannelPosition.SR, ChannelPosition.TFL, ChannelPosition.TFR, ChannelPosition.TBL, ChannelPosition.TBR),
            layout("7.1.2", ChannelPosition.FL, ChannelPosition.FR, ChannelPosition.FC, ChannelPosition.LFE, ChannelPosition.BL, ChannelPosition.BR, ChannelPosition.SL, ChannelPosition.SR, ChannelPosition.TFL, ChannelPosition.TFR),
            layout("7.1.4", ChannelPosition.FL, ChannelPosition.FR, ChannelPosition.FC, ChannelPosition.LFE, ChannelPosition.BL, ChannelPosition.BR, ChannelPosition.SL, ChannelPosition.SR, ChannelPosition.TFL, ChannelPosition.TFR, ChannelPosition.TBL, ChannelPosition.TBR),
            layout("7.2.3", ChannelPosition.FL, ChannelPosition.FR, ChannelPosition.FC, ChannelPosition.LFE, ChannelPosition.BL, ChannelPosition.BR, ChannelPosition.SL, ChannelPosition.SR, ChannelPosition.TFL, ChannelPosition.TFR, ChannelPosition.TBC, ChannelPosition.LFE2),
            layout("9.1.4", ChannelPosition.FL, ChannelPosition.FR, ChannelPosition.FC, ChannelPosition.LFE, ChannelPosition.BL, ChannelPosition.BR, ChannelPosition.FLC, ChannelPosition.FRC, ChannelPosition.SL, ChannelPosition.SR, ChannelPosition.TFL, ChannelPosition.TFR, ChannelPosition.TBL, ChannelPosition.TBR),
            layout("9.1.6", ChannelPosition.FL, ChannelPosition.FR, ChannelPosition.FC, ChannelPosition.LFE, ChannelPosition.BL, ChannelPosition.BR, ChannelPosition.FLC, ChannelPosition.FRC, ChannelPosition.SL, ChannelPosition.SR, ChannelPosition.TFL, ChannelPosition.TFR, ChannelPosition.TBL, ChannelPosition.TBR, ChannelPosition.TSL, ChannelPosition.TSR),
            layout("hexadecagonal", ChannelPosition.FL, ChannelPosition.FR, ChannelPosition.FC, ChannelPosition.BL, ChannelPosition.BR, ChannelPosition.BC, ChannelPosition.SL, ChannelPosition.SR, ChannelPosition.TFL, ChannelPosition.TFC, ChannelPosition.TFR, ChannelPosition.TBL, ChannelPosition.TBC, ChannelPosition.TBR, ChannelPosition.WL, ChannelPosition.WR),
            layout("binaural", ChannelPosition.BIL, ChannelPosition.BIR),
            layout("downmix", ChannelPosition.DL, ChannelPosition.DR),
            layout("22.2", ChannelPosition.FL, ChannelPosition.FR, ChannelPosition.FC, ChannelPosition.LFE, ChannelPosition.BL, ChannelPosition.BR, ChannelPosition.FLC, ChannelPosition.FRC, ChannelPosition.BC, ChannelPosition.SL, ChannelPosition.SR, ChannelPosition.TC, ChannelPosition.TFL, ChannelPosition.TFC, ChannelPosition.TFR, ChannelPosition.TBL, ChannelPosition.TBC, ChannelPosition.TBR, ChannelPosition.LFE2, ChannelPosition.TSL, ChannelPosition.TSR, ChannelPosition.BFC, ChannelPosition.BFL, ChannelPosition.BFR),
        ).associateBy { it.name }

        /**
         * What FFmpeg means by a bare channel count.
         *
         * Not every count has an answer: nine channels is nine channels and
         * nothing more, which is why this returns an unspecified layout rather
         * than inventing one.
         */
        public fun defaultFor(channels: Int): ChannelLayout =
            DEFAULTS[channels] ?: unspecified(channels)

        /**
         * The layout a decoder named, or the count it sent instead.
         *
         * Takes what arrives across the seam: a standard name, or the
         * `"6 channels"` shape FFmpeg prints for a stream that declared
         * nothing, which resolves to the default for that count.
         */
        public fun of(name: String): ChannelLayout? {
            STANDARD[name]?.let { return it }
            val bare = Regex("""^(\d+) channels?$""").find(name.trim()) ?: return null
            val channels = bare.groupValues[1].toIntOrNull() ?: return null
            if (channels <= 0) return null
            return defaultFor(channels)
        }

        private val DEFAULTS: Map<Int, ChannelLayout> = mapOf(
            1 to STANDARD.getValue("mono"),
            2 to STANDARD.getValue("stereo"),
            3 to STANDARD.getValue("2.1"),
            4 to STANDARD.getValue("4.0"),
            5 to STANDARD.getValue("5.0"),
            6 to STANDARD.getValue("5.1"),
            7 to STANDARD.getValue("6.1"),
            8 to STANDARD.getValue("7.1"),
            10 to STANDARD.getValue("5.1.4"),
            12 to STANDARD.getValue("7.1.4"),
            14 to STANDARD.getValue("9.1.4"),
            16 to STANDARD.getValue("9.1.6"),
            24 to STANDARD.getValue("22.2"),
        )

        /** One channel, at the centre. */
        public val MONO: ChannelLayout = STANDARD.getValue("mono")

        /** The front pair, and what most media carries. */
        public val STEREO: ChannelLayout = STANDARD.getValue("stereo")

        /**
         * Front pair, centre, low frequency, rear pair, in that order.
         *
         * The rear pair rather than the sides, which is the whole reason a
         * count is not a layout: `5.1(side)` carries the same six channels and
         * puts the last two somewhere else.
         */
        public val SURROUND_5_1: ChannelLayout = STANDARD.getValue("5.1")

        /** `5.1` with the side pair added after the rears. */
        public val SURROUND_7_1: ChannelLayout = STANDARD.getValue("7.1")
    }
}
