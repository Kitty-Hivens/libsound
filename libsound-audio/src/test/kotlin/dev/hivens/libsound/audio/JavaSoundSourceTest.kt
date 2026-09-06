package dev.hivens.libsound.audio

import dev.hivens.libsound.AudioFormat
import dev.hivens.libsound.AudioSource
import dev.hivens.libsound.Capability
import dev.hivens.libsound.SourceConfig
import dev.hivens.libsound.audio.javasound.JavaSoundBackend
import dev.hivens.libsound.audio.javasound.JavaSoundSource
import dev.hivens.libsound.testing.AudioSourceContract
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine
import javax.sound.sampled.AudioFormat as JavaAudioFormat

private fun captureLineAvailable(): Boolean = runCatching {
    AudioSystem.isLineSupported(
        DataLine.Info(TargetDataLine::class.java, JavaAudioFormat(48_000f, 16, 2, true, false)),
    )
}.getOrDefault(false)

/**
 * The gate is its own name, and that is the point.
 *
 * JavaSound captures from whatever the JVM calls the default input, and on a
 * developer's machine that is a microphone in a room. A suite that opened it on
 * every run would be recording people who did not agree to it, so this one skips
 * unless a run names `javasound-capture` in LIBSOUND_REQUIRE. CI names it,
 * because a container's default input is a null sink's monitor and there is no
 * room to record.
 */
private fun gateCapture() {
    AudioTestGate.require(
        "javasound-capture",
        captureLineAvailable() && AudioTestGate.isRequired("javasound-capture"),
        "the JavaSound capture suite opens the default input, so it runs only when a run asks for it",
    )
}

/** The capture contract, against the backend that exists everywhere. */
class JavaSoundSourceContractTest : AudioSourceContract() {

    @BeforeEach
    fun gate() = gateCapture()

    override fun newSource(): AudioSource = JavaSoundSource()
}

class JavaSoundSourceTest {

    private val format = AudioFormat(48_000, 2)

    @BeforeEach
    fun gate() = gateCapture()

    @Test
    fun `a read is paced by the device rather than handed back at once`() {
        // The rule the shared contract cannot check, because a fake produces on
        // command. Only a real line makes a second of audio take a second.
        JavaSoundSource().use { source ->
            source.open(format)
            val half = ByteArray(format.sampleRate / 2 * format.bytesPerFrame)
            val startedAt = System.nanoTime()
            source.read(half, 0, half.size)
            val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
            (elapsedMillis > 250) shouldBe true
            source.framePosition() shouldBeGreaterThan 0L
        }
    }

    @Test
    fun `the fallback claims nothing it cannot do`() {
        JavaSoundSource().use { source ->
            // The same absences as the output side, and one more: a
            // TargetDataLine reports what is waiting and never what went past
            // unread, so there is no overrun count to offer.
            (Capability.CAPTURE in source.capabilities) shouldBe true
            (Capability.DEVICE_POSITION in source.capabilities) shouldBe true
            (Capability.STREAM_VOLUME in source.capabilities) shouldBe false
            (Capability.STREAM_IDENTITY in source.capabilities) shouldBe false
            (Capability.DEVICE_SELECTION in source.capabilities) shouldBe false
            source.open(format)
            source.overrunFrames() shouldBe 0L
        }
    }

    @Test
    fun `the backend hands out a source and closes it with everything else`() {
        val backend = checkNotNull(JavaSoundBackend.createOrNull())
        val source = backend.createSource(SourceConfig(applicationName = "libsound capture test"))
        source.open(format)
        source.isOpen shouldBe true
        // Closing the backend closes what it handed out, on both sides.
        backend.close()
        source.isOpen shouldBe false
    }
}
