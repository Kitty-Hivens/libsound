package dev.hivens.libsound.audio.javasound

import dev.hivens.libsound.AudioBackend
import dev.hivens.libsound.AudioDevice
import dev.hivens.libsound.AudioSink
import dev.hivens.libsound.Capabilities
import dev.hivens.libsound.SinkConfig
import org.slf4j.LoggerFactory
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.AudioFormat as JavaAudioFormat

/**
 * The backend of last resort: no natives of our own, present on every JVM.
 *
 * It reports almost nothing in [capabilities], and that is the point. Every
 * facet it cannot provide is a facet a consumer must be able to hide rather
 * than discover by watching a control do nothing -- a device selector that
 * lists entries which would seize the hardware, or a volume slider the system
 * mixer never follows.
 *
 * From a memory-safety standpoint this is also the safest backend in the
 * library: it owns no native code, so there is no arena, no upcall stub and no
 * struct layout to get wrong.
 */
internal class JavaSoundBackend private constructor(
    private val bufferNanos: Long?,
) : AudioBackend {

    override val name: String = "javasound"

    /**
     * The same set its sinks report, not an empty one.
     *
     * A consumer reads the *backend* to decide what to offer -- that is the
     * line the selection logs and the line a settings screen keys off. A
     * backend claiming nothing while its sinks provide a device-derived
     * playhead would have consumers disabling A/V sync on a backend that
     * supports it.
     */
    override val capabilities: Capabilities = JavaSoundSink.CAPABILITIES

    private val sinks = mutableListOf<JavaSoundSink>()

    override fun createSink(config: SinkConfig): AudioSink {
        // config carries an application name, an icon and a role. None of them
        // can be attached to a JavaSound line; they are dropped here rather
        // than approximated, and STREAM_IDENTITY says so.
        val sink = JavaSoundSink(
            bufferNanos = config.bufferNanos ?: bufferNanos ?: JavaSoundSink.DEFAULT_BUFFER_NANOS,
        )
        synchronized(sinks) { sinks.add(sink) }
        return sink
    }

    override fun devices(): List<AudioDevice> = emptyList()

    override fun defaultDevice(): AudioDevice? = null

    override fun onDevicesChanged(handler: () -> Unit): () -> Unit = {}

    override fun close() {
        val open = synchronized(sinks) { sinks.toList().also { sinks.clear() } }
        open.forEach { runCatching { it.close() } }
    }

    internal companion object {
        private val log = LoggerFactory.getLogger("libsound.JavaSound")

        /**
         * Build the fallback, or null when the JVM cannot play at all.
         *
         * Null is not "degrade further" -- there is nothing below this. It means
         * a headless or device-less machine, which the selection above reports
         * once and the consumer treats as silence.
         */
        fun createOrNull(bufferNanos: Long? = null): AudioBackend? {
            val probe = JavaAudioFormat(48_000f, 16, 2, true, false)
            val supported = runCatching {
                AudioSystem.isLineSupported(DataLine.Info(SourceDataLine::class.java, probe))
            }.getOrDefault(false)
            if (!supported) {
                log.info("no JavaSound output line available -- this JVM cannot play audio")
                return null
            }
            return JavaSoundBackend(bufferNanos)
        }
    }
}
