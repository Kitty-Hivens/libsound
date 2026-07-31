package dev.hivens.libsound.audio

import dev.hivens.libsound.AudioFormat
import dev.hivens.libsound.AudioSink
import dev.hivens.libsound.Capability
import dev.hivens.libsound.audio.javasound.JavaSoundBackend
import dev.hivens.libsound.audio.javasound.JavaSoundSink
import dev.hivens.libsound.testing.AudioSinkContract
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.AudioFormat as JavaAudioFormat

private fun javaSoundAvailable(): Boolean = runCatching {
    AudioSystem.isLineSupported(
        DataLine.Info(SourceDataLine::class.java, JavaAudioFormat(48_000f, 16, 2, true, false)),
    )
}.getOrDefault(false)

/**
 * The contract, against the backend that exists everywhere.
 *
 * This is the row that matters most on Windows and macOS, where JavaSound is the
 * whole implementation until the native backends land. The behaviours it asserts
 * were measured on Linux; running the same assertions on the other two runners
 * is how the measurement stops being a Linux measurement.
 */
class JavaSoundSinkContractTest : AudioSinkContract() {

    @BeforeEach
    fun gate() {
        AudioTestGate.require("javasound", javaSoundAvailable(), "no output line on this JVM")
    }

    override fun newSink(): AudioSink = JavaSoundSink()
}

class JavaSoundSinkTest {

    private val format = AudioFormat(48_000, 2)

    @BeforeEach
    fun gate() {
        AudioTestGate.require("javasound", javaSoundAvailable(), "no output line on this JVM")
    }

    @Test
    fun `flush does not credit the discarded tail to the playhead`() {
        // The measured defect this backend compensates for: JavaSound's flush
        // moves the reported position forward to the total ever written, so a
        // seek that reads the playhead straight afterwards would anchor a whole
        // buffer ahead of the sound.
        JavaSoundSink().use { sink ->
            sink.open(format)
            val half = ByteArray(format.sampleRate / 2 * format.bytesPerFrame)
            sink.write(half, 0, half.size)
            sink.stop()

            val before = sink.framePosition()
            sink.flush()
            val after = sink.framePosition()

            // A tolerance rather than equality: the device keeps rendering
            // between the two reads on backends whose flush is honest, and a
            // few milliseconds of real progress is not the defect under test.
            (after - before) shouldBeLessThan format.sampleRate / 20L
        }
    }

    @Test
    fun `a write is paced by the device rather than buffered and returned`() {
        // The rule the shared contract cannot check, because a fake sized for
        // the suite would legitimately return at once. Only a real line paces.
        JavaSoundSink().use { sink ->
            sink.open(format)
            val oneSecond = ByteArray(format.sampleRate * format.bytesPerFrame)
            val startedAt = System.nanoTime()
            sink.write(oneSecond, 0, oneSecond.size)
            val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
            (elapsedMillis > 500) shouldBe true
        }
    }

    @Test
    fun `the backend and its sinks claim the same thing`() {
        // They disagreed: the backend reported an empty set while its sinks
        // reported DEVICE_POSITION. A consumer reads the backend to decide what
        // to offer, so it would have disabled sync on a backend that supports it.
        val backend = checkNotNull(JavaSoundBackend.createOrNull())
        backend.use {
            JavaSoundSink().use { sink ->
                it.capabilities shouldBe sink.capabilities
            }
        }
    }

    @Test
    fun `the fallback claims nothing it cannot do`() {
        JavaSoundSink().use { sink ->
            // Every absence here is deliberate and documented. A device list
            // whose entries seize the hardware is worse than no list, and a
            // volume the desktop's mixer never follows is worse than a slider
            // the consumer knows not to draw.
            (Capability.STREAM_VOLUME in sink.capabilities) shouldBe false
            (Capability.STREAM_IDENTITY in sink.capabilities) shouldBe false
            (Capability.DEVICE_SELECTION in sink.capabilities) shouldBe false
            (Capability.DEVICE_ENUMERATION in sink.capabilities) shouldBe false
            // What it does claim: a playhead that comes from the device and
            // tracks real time while writes are flowing.
            (Capability.DEVICE_POSITION in sink.capabilities) shouldBe true
        }
    }

    @Test
    fun `the backend offers no devices rather than unusable ones`() {
        val backend = JavaSoundBackend.createOrNull()
        checkNotNull(backend)
        backend.use {
            it.devices() shouldBe emptyList()
            it.defaultDevice() shouldBe null
            // Subscribing is a no-op, not a failure: a consumer should be able
            // to wire the same code on every backend and let the capability
            // decide whether anything arrives.
            val unsubscribe = it.onDevicesChanged { }
            unsubscribe()
        }
    }
}
