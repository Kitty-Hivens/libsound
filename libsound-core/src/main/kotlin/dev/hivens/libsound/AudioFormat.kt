package dev.hivens.libsound

/**
 * Sample encoding of the PCM a sink accepts. Interleaved, little-endian.
 *
 * The set a decoder actually produces rather than the set that is convenient
 * here. S16LE is the floor every backend must accept and what most consumers
 * push. The others exist because refusing them costs a conversion somebody
 * else then has to do, and one of them costs more than a conversion: FFmpeg
 * has no 24-bit sample format at all, so 24-bit content arrives as S32LE with
 * the value in the top bits, and a library without it sends every FLAC, ALAC,
 * DTS-HD and TrueHD source down to F32LE or S16LE.
 *
 * A backend that cannot take one refuses it at [AudioSink.open], which is an
 * answer rather than a failure: a consumer walks its own ladder down from what
 * the media is towards the floor. A backend that accepted a format and played
 * something else would be indistinguishable from one that worked.
 *
 * How many of the bits carry signal is a separate question from how wide the
 * sample is, and [AudioFormat.significantBits] is where it is answered.
 */
public enum class PcmEncoding(
    /** Width of one sample of one channel. */
    public val bytesPerSample: Int,
) {
    /** Unsigned, offset by 128. Old WAV and what derives from it. */
    U8(1),

    /** Signed, and the one format every backend has to accept. */
    S16LE(2),

    /**
     * Signed. Also where 24-bit content arrives, in the top 24 bits, because
     * FFmpeg has no 24-bit sample format to send it in.
     */
    S32LE(4),

    /** Float, and native on PipeWire and CoreAudio, which may pass it through untouched. */
    F32LE(4),

    /** Double. Rare sources and some filter outputs. */
    F64LE(8),
}

/**
 * The shape of the stream a sink is opened with.
 *
 * Frame arithmetic lives here rather than at every call site because the
 * conversions are where overflow hides: `frames * 1_000_000_000` passes Long's
 * range after about 53 hours at 48 kHz, which a soak run reaches and a unit
 * test does not. Splitting into whole seconds plus a remainder moves the limit
 * to a few centuries, the same correction skinema's pts math already carries.
 */
public data class AudioFormat(
    /** Frames per second. 48000 is what most graphs run at. */
    public val sampleRate: Int,
    /** How many samples one frame carries, interleaved. */
    public val channels: Int = 2,
    /** How each sample is written. */
    public val encoding: PcmEncoding = PcmEncoding.S16LE,
) {
    init {
        require(sampleRate > 0) { "sampleRate must be positive, was $sampleRate" }
        require(channels > 0) { "channels must be positive, was $channels" }
    }

    /** Bytes in one sample frame -- one sample per channel. */
    public val bytesPerFrame: Int get() = channels * encoding.bytesPerSample

    /** Whole frames in [bytes]; a partial trailing frame is not counted. */
    public fun framesIn(bytes: Long): Long = bytes / bytesPerFrame

    /** Bytes occupied by [frames] frames. */
    public fun bytesFor(frames: Long): Long = frames * bytesPerFrame

    /** Duration of [frames] frames. Split so the multiplication cannot overflow. */
    public fun nanosFor(frames: Long): Long {
        val whole = frames / sampleRate
        val remainder = frames % sampleRate
        return whole * NANOS_PER_SECOND + remainder * NANOS_PER_SECOND / sampleRate
    }

    /** Frames covering [nanos]. Split for the same reason as [nanosFor]. */
    public fun framesFor(nanos: Long): Long {
        val whole = nanos / NANOS_PER_SECOND
        val remainder = nanos % NANOS_PER_SECOND
        return whole * sampleRate + remainder * sampleRate / NANOS_PER_SECOND
    }

    /** The constants the frame arithmetic above is written against. */
    public companion object {
        /** The unit every duration in this library is measured in. */
        public const val NANOS_PER_SECOND: Long = 1_000_000_000L

        /** The shape skinema pushes and every backend must accept. */
        public val CD_STEREO: AudioFormat = AudioFormat(44_100, 2, PcmEncoding.S16LE)
    }
}
