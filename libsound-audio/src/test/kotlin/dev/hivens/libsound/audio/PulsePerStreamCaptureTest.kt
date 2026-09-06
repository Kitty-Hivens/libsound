package dev.hivens.libsound.audio

import dev.hivens.libsound.AudioBackend
import dev.hivens.libsound.AudioFormat
import dev.hivens.libsound.Capability
import dev.hivens.libsound.SinkConfig
import dev.hivens.libsound.SourceConfig
import dev.hivens.libsound.StreamId
import dev.hivens.libsound.VolumeMixer
import dev.hivens.libsound.audio.pulse.PulseBackend
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Recording one application's output, and nothing else on the machine.
 *
 * The application recorded here is this suite: it opens a sink of its own,
 * pinned to a null sink it also creates, so the tone is inaudible and the only
 * audio in the recording is the audio this test made. That is also the honest
 * demonstration of what the feature is, which is reading another application's
 * output without that application being told.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class PulsePerStreamCaptureTest {

    private val appName = "libsound per-stream capture ${ProcessHandle.current().pid()}"
    private val format = AudioFormat(48_000, 2)

    private var backend: AudioBackend? = null
    private var mixer: VolumeMixer? = null

    @BeforeEach
    fun open() {
        backend = PulseBackend.connectOrNull(appName)
        AudioTestGate.require("pulse", backend != null, "no PulseAudio or PipeWire server")
        mixer = VolumeMixers.open(appName)
        AudioTestGate.require("pulse", mixer != null, "no mixer available")
    }

    @AfterEach
    fun close() {
        mixer?.let { runCatching { it.close() } }
        backend?.let { runCatching { it.close() } }
        mixer = null; backend = null
    }

    private fun tone(): ByteArray {
        val pcm = ByteArray(format.sampleRate / 4 * format.bytesPerFrame)
        for (frame in 0 until format.sampleRate / 4) {
            val value = (sin(2.0 * PI * 440.0 * frame / format.sampleRate) * 0.5 * Short.MAX_VALUE).toInt()
            val at = frame * format.bytesPerFrame
            repeat(format.channels) { channel ->
                pcm[at + channel * 2] = (value and 0xFF).toByte()
                pcm[at + channel * 2 + 1] = ((value shr 8) and 0xFF).toByte()
            }
        }
        return pcm
    }

    @Test
    fun `one application's output is recorded, and nothing else`() {
        val backend = checkNotNull(backend)
        val mixer = checkNotNull(mixer)
        (Capability.PER_STREAM_CAPTURE in backend.capabilities) shouldBe true

        val quiet = checkNotNull(mixer.createVirtualSink("libsound_capture_target_${ProcessHandle.current().pid()}")) {
            "the server refused a null sink to play into"
        }
        val sink = backend.createSink(SinkConfig(applicationName = appName, device = quiet))
        val playing = AtomicBoolean(true)
        val player = Thread({
            runCatching {
                sink.open(format)
                val pcm = tone()
                while (playing.get()) sink.write(pcm, 0, pcm.size)
            }
        }, "libsound-capture-tone")
        player.isDaemon = true
        player.start()

        try {
            val id = eventually("our own stream in the mixer") {
                mixer.streams().firstOrNull { it.applicationName == appName && it.isOurs }?.id
            }
            recordAndExpectSound(id)
        } finally {
            playing.set(false)
            player.join(5_000)
            runCatching { sink.close() }
        }
    }

    private fun recordAndExpectSound(id: StreamId) {
        val backend = checkNotNull(backend)
        backend.createSource(
            SourceConfig(applicationName = "$appName reader", captureStream = id),
        ).use { source ->
            source.open(format)
            // Half a second of the target's own output. Silence would prove
            // nothing, so what is asserted is that real audio arrived: only the
            // stream being recorded is producing any.
            val heard = ByteArray(format.sampleRate / 2 * format.bytesPerFrame)
            source.read(heard, 0, heard.size)
            val loudest = (heard.indices step 2).maxOf { at ->
                abs(((heard[at + 1].toInt() shl 8) or (heard[at].toInt() and 0xFF)).toShort().toInt())
            }
            (loudest > SILENCE_THRESHOLD) shouldBe true
        }
    }

    @Test
    fun `recording a stream that is not there is refused rather than silently empty`() {
        // A source that opened against nothing and handed back silence would be
        // indistinguishable from a quiet application, which is the worst answer
        // available.
        val backend = checkNotNull(backend)
        runCatching {
            backend.createSource(
                SourceConfig(applicationName = appName, captureStream = StreamId("sink-input:999999")),
            )
        }.isFailure shouldBe true
        // And an id from the capture half of the mixer names something that has
        // no output to record.
        runCatching {
            backend.createSource(
                SourceConfig(applicationName = appName, captureStream = StreamId("source-output:1")),
            )
        }.isFailure shouldBe true
    }

    private fun <T : Any> eventually(what: String, produce: () -> T?): T {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            produce()?.let { return it }
            Thread.sleep(50)
        }
        throw AssertionError("never appeared: $what")
    }

    private companion object {
        /** Well above the noise a null sink's monitor carries, which is none. */
        const val SILENCE_THRESHOLD = 1_000
    }
}
