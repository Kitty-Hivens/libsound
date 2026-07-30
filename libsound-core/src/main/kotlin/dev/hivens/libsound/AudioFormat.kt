package dev.hivens.libsound

/**
 * Sample encoding of the PCM a sink accepts. Interleaved, little-endian.
 *
 * S16LE is what every current consumer pushes and what every backend must
 * accept. F32LE exists because PipeWire and CoreAudio are float-native and a
 * backend may pass it through without a conversion; a backend that cannot is
 * free to reject it, which the capability query reports rather than a failure
 * at the first write.
 */
public enum class PcmEncoding(public val bytesPerSample: Int) {
    S16LE(2),
    F32LE(4),
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
    public val sampleRate: Int,
    public val channels: Int = 2,
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

    public companion object {
        public const val NANOS_PER_SECOND: Long = 1_000_000_000L

        /** The shape skinema pushes and every backend must accept. */
        public val CD_STEREO: AudioFormat = AudioFormat(44_100, 2, PcmEncoding.S16LE)
    }
}
