package dev.hivens.libsound.audio.pulse

import dev.hivens.libsound.AudioFormat
import dev.hivens.libsound.ChannelLayout
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/**
 * Telling the server what each channel is, rather than only how many there are.
 *
 * A `pa_sample_spec` carries a count and nothing else, and a stream connected
 * with a null map gets `PA_CHANNEL_MAP_DEFAULT`, which is the ALSA ordering. It
 * is not FFmpeg's, and the two do not merely disagree about the last pair. The
 * oracle prints what the default actually is:
 *
 * ```
 *  3 channels -> front-left front-right front-center
 *  4 channels -> front-left front-center front-right rear-center
 *  6 channels -> front-left front-left-of-center front-center front-right
 *                front-right-of-center rear-center
 * ```
 *
 * A decoder handing over 5.1 as `FL FR FC LFE BL BR` against the third row
 * plays its right channel out of a front-left-of-center speaker and its LFE out
 * of the front right, at full level. Nothing reports it, because nothing on
 * either side was ever asked.
 *
 * So the map goes across explicitly wherever the layout names its channels and
 * this server has a name for every one of them, and
 * [dev.hivens.libsound.Capability.CHANNEL_PLACEMENT] is how a consumer knows
 * that happened.
 */
internal object PulseChannelMap {

    /**
     * Below this, every platform agrees and there is nothing to negotiate.
     *
     * It is also what lets `binaural` and `downmix` through: both are two
     * channels of positions libpulse has no name for, and both are correctly
     * rendered by any device that treats them as an ordinary pair.
     */
    private const val UNIVERSAL_CHANNELS = 2

    /**
     * Whether [format] can be opened without the server being told something
     * untrue.
     *
     * False only for a layout that names a position libpulse cannot: wide,
     * downmix, binaural past a pair, the second LFE, the bottom row. A consumer
     * that meets a false here sends the same audio with
     * [ChannelLayout.unspecified] and takes the server's own ordering, which is
     * an answer rather than a workaround, because it is what a stream that
     * declared no layout gets anyway.
     */
    fun placeable(format: AudioFormat): Boolean =
        !format.layout.isSpecified ||
            format.channels <= UNIVERSAL_CHANNELS ||
            PulseAbi.unplaceable(format.layout) == null

    /**
     * Allocate the map for [format], or null where the count speaks for itself.
     *
     * Null means the stream connects with no map and the server applies its
     * default, which is right in exactly two cases: a layout that names nothing,
     * and one whose positions libpulse cannot express but whose count it can
     * lay out conventionally.
     */
    fun writeOrNull(arena: Arena, format: AudioFormat): MemorySegment? {
        val layout = format.layout
        if (!layout.isSpecified) return null
        // Mono is the one position whose translation is not its name. FFmpeg
        // calls a single channel front-center and libpulse keeps the two apart
        // on purpose: a stream marked front-center is pinned to that speaker,
        // and one marked mono is spread the way a mono stream is meant to be.
        if (format.channels == 1) return write(arena, intArrayOf(PulseAbi.CHANNEL_POSITION_MONO))
        val positions = layout.positions.map { PulseAbi.channelPositionOf(it) ?: return null }
        return write(arena, positions.toIntArray())
    }

    private fun write(arena: Arena, positions: IntArray): MemorySegment {
        val map = arena.allocate(PulseAbi.CHANNEL_MAP_SIZE, 4)
        map.set(ValueLayout.JAVA_BYTE, PulseAbi.CHANNEL_MAP_CHANNELS, positions.size.toByte())
        positions.forEachIndexed { index, position ->
            map.set(ValueLayout.JAVA_INT, PulseAbi.CHANNEL_MAP_MAP + index * Int.SIZE_BYTES, position)
        }
        return map
    }
}
