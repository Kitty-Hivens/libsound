package dev.hivens.libsound.audio

import dev.hivens.libsound.AudioBackend
import dev.hivens.libsound.AudioFormat
import dev.hivens.libsound.Capability
import dev.hivens.libsound.SampleId
import dev.hivens.libsound.audio.pulse.PulseBackend
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin

/**
 * The sample cache, which is the lowest-latency path there is for a short
 * sound: no stream to set up, no buffer to fill, no scheduling.
 *
 * Whether a server keeps one at all is a fact about the server, so the backend
 * probes it at connect and this suite believes the answer rather than assuming
 * a PulseAudio. Nothing here is ever played to a device the user can hear: the
 * trigger goes to a null sink this suite creates.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class PulseSampleCacheTest {

    private val format = AudioFormat(48_000, 1)

    private var backend: AudioBackend? = null

    @BeforeEach
    fun open() {
        backend = PulseBackend.connectOrNull("libsound sample test")
        AudioTestGate.require("pulse", backend != null, "no PulseAudio or PipeWire server")
        Assumptions.assumeTrue(
            Capability.SAMPLE_CACHE in checkNotNull(backend).capabilities,
            "this server keeps no sample cache, which the backend probed at connect",
        )
    }

    @AfterEach
    fun close() {
        backend?.let { runCatching { it.close() } }
        backend = null
    }

    private fun tone(millis: Int): ByteArray {
        val frames = format.sampleRate / 1_000 * millis
        val pcm = ByteArray(frames * format.bytesPerFrame)
        for (frame in 0 until frames) {
            val value = (sin(2.0 * PI * 440.0 * frame / format.sampleRate) * 0.4 * Short.MAX_VALUE).toInt()
            pcm[frame * 2] = (value and 0xFF).toByte()
            pcm[frame * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }
        return pcm
    }

    @Test
    fun `a sound uploaded once can be triggered by name`() {
        val backend = checkNotNull(backend)
        val id = checkNotNull(backend.cacheSample("libsound_test_click", format, tone(100))) {
            "the server said it keeps a cache and then refused the upload"
        }
        // Played to a device of this suite's own, so nothing reaches whatever
        // the developer is listening to.
        val mixer = checkNotNull(VolumeMixers.open("libsound sample test"))
        mixer.use {
            val quiet = checkNotNull(mixer.createVirtualSink("libsound_test_sample_sink"))
            backend.playSample(id, quiet) shouldBe true
        }
    }

    @Test
    fun `triggering a sound that was never uploaded answers false`() {
        // What the server said, not that the request was sent.
        checkNotNull(backend).playSample(SampleId("libsound_no_such_sample")) shouldBe false
    }

    @Test
    fun `nonsense is refused before it reaches the server`() {
        val backend = checkNotNull(backend)
        backend.cacheSample("libsound_test_empty", format, ByteArray(0)) shouldBe null
        // Not a whole number of frames, which would upload a sound with half a
        // sample on the end of it.
        backend.cacheSample("libsound_test_partial", AudioFormat(48_000, 2), ByteArray(6)) shouldBe null
    }

    @Test
    fun `what this process uploaded is gone when it closes`() {
        val backend = checkNotNull(backend)
        val id = checkNotNull(backend.cacheSample("libsound_test_leftover", format, tone(50)))
        backend.close()
        this.backend = null

        // Asked through a second connection, because the first one is the thing
        // under test: a sound left in the server's cache is state a user did not
        // ask for and cannot see.
        val second = checkNotNull(PulseBackend.connectOrNull("libsound sample check"))
        second.use { it.playSample(id) shouldBe false }
    }
}
