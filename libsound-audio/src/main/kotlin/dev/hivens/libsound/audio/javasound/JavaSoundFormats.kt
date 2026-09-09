package dev.hivens.libsound.audio.javasound

import dev.hivens.libsound.AudioFormat
import dev.hivens.libsound.PcmEncoding
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine
import javax.sound.sampled.AudioFormat as JavaAudioFormat

/**
 * Turning a shape into the one JavaSound describes, and asking the JVM whether
 * it takes it.
 *
 * This backend is the one place in the library where the question has an
 * answer already: `AudioSystem.isLineSupported` walks every mixer the runtime
 * offers and says yes or no, on the same walk `getSourceDataLine` makes when it
 * opens. So the accepted set is read out of the JVM rather than declared here,
 * and the two cannot drift, because they are the same call.
 *
 * It replaces a hardcoded S16LE, which was true of the shape this backend
 * happened to be written against rather than of what a JVM will play. Every
 * runtime that has an output line at all takes the eight-bit and the wider
 * signed forms too, and refusing them cost a conversion nobody needed to do.
 */
internal object JavaSoundFormats {

    /**
     * What JavaSound calls this shape, or null where it has no name for it.
     *
     * `PCM_FLOAT` exists in the API from Java 7 and is separate from the signed
     * and unsigned integer encodings, which is why it cannot be expressed by
     * the width alone. Whether any mixer implements it is a different question
     * and the one [supported] asks.
     */
    fun javaFormatOf(format: AudioFormat): JavaAudioFormat? = when (format.encoding) {
        // Unsigned, which is the whole of what makes U8 different: the sample
        // width says eight bits either way and the sign flag says which.
        PcmEncoding.U8 -> JavaAudioFormat(format.sampleRate.toFloat(), 8, format.channels, false, false)
        PcmEncoding.S16LE -> JavaAudioFormat(format.sampleRate.toFloat(), 16, format.channels, true, false)
        PcmEncoding.S32LE -> JavaAudioFormat(format.sampleRate.toFloat(), 32, format.channels, true, false)
        PcmEncoding.F32LE -> floatFormat(format, bits = 32)
        PcmEncoding.F64LE -> floatFormat(format, bits = 64)
    }

    private fun floatFormat(format: AudioFormat, bits: Int): JavaAudioFormat = JavaAudioFormat(
        JavaAudioFormat.Encoding.PCM_FLOAT,
        format.sampleRate.toFloat(),
        bits,
        format.channels,
        format.channels * (bits / 8),
        format.sampleRate.toFloat(),
        false,
    )

    /** Whether a playback line exists for [format] anywhere the runtime can see. */
    fun supportedForPlayback(format: AudioFormat): Boolean =
        supported(SourceDataLine::class.java, format)

    /** The same question, one direction along. */
    fun supportedForCapture(format: AudioFormat): Boolean =
        supported(TargetDataLine::class.java, format)

    private fun supported(line: Class<*>, format: AudioFormat): Boolean {
        val java = javaFormatOf(format) ?: return false
        // Never throws in practice and caught anyway: this is asked before an
        // open, and a probe that failed loudly would turn a question into the
        // failure it was there to avoid.
        return runCatching { AudioSystem.isLineSupported(DataLine.Info(line, java)) }.getOrDefault(false)
    }

    /**
     * The encodings a line of this kind takes, probed at [format]'s rate and
     * channel count.
     *
     * A ladder rather than a promise about every rate: what a mixer accepts can
     * depend on the whole shape, which is why [supportedForPlayback] exists
     * beside this and answers the whole question.
     */
    fun acceptedFor(line: Class<*>, format: AudioFormat): Set<PcmEncoding> =
        PcmEncoding.entries.filterTo(LinkedHashSet()) { encoding ->
            supported(line, AudioFormat(format.sampleRate, format.channels, encoding))
        }

    /** The shape the accepted set is probed at, and what the fallback is sized around. */
    val PROBE: AudioFormat = AudioFormat(48_000, 2, PcmEncoding.S16LE)
}
