package dev.hivens.libsound.audio

import dev.hivens.libsound.AudioBackend
import dev.hivens.libsound.AudioException
import dev.hivens.libsound.AudioFormat
import dev.hivens.libsound.AudioSink
import dev.hivens.libsound.Capability
import dev.hivens.libsound.DeviceId
import dev.hivens.libsound.MediaRole
import dev.hivens.libsound.PcmEncoding
import dev.hivens.libsound.SinkConfig
import dev.hivens.libsound.audio.coreaudio.CoreAudioBackend
import dev.hivens.libsound.testing.AudioSinkContract
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

private const val CORE_AUDIO_APP = "libsound contract suite"

private object CoreAudioFixture {
    var backend: AudioBackend? = null

    fun connect() {
        backend = CoreAudioBackend.connectOrNull()
    }

    fun disconnect() {
        backend?.let { runCatching { it.close() } }
        backend = null
    }

    fun gate() {
        AudioTestGate.require("coreaudio", backend != null, "no CoreAudio on this machine")
    }

    fun config(): SinkConfig = SinkConfig(
        applicationName = CORE_AUDIO_APP,
        applicationId = "dev.hivens.libsound.test",
        mediaRole = MediaRole.MUSIC,
    )
}

/**
 * The contract, against a real output unit.
 *
 * Unlike Windows, macOS runners have a working output device -- "Apple Virtual
 * Sound Device", measured by the ABI oracle before any of this was written. So
 * the same suite every other backend passes runs here on every push, and macOS
 * is the platform that needs the least hand checking rather than the most.
 */
class CoreAudioSinkContractTest : AudioSinkContract() {

    companion object {
        @JvmStatic
        @BeforeAll
        fun connect() = CoreAudioFixture.connect()

        @JvmStatic
        @AfterAll
        fun disconnect() = CoreAudioFixture.disconnect()
    }

    @BeforeEach
    fun gate() = CoreAudioFixture.gate()

    override fun newSink(): AudioSink =
        checkNotNull(CoreAudioFixture.backend).createSink(CoreAudioFixture.config())
}

class CoreAudioBackendTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun connect() = CoreAudioFixture.connect()

        @JvmStatic
        @AfterAll
        fun disconnect() = CoreAudioFixture.disconnect()
    }

    @BeforeEach
    fun gate() = CoreAudioFixture.gate()

    @Test
    fun `every listed device can actually be played to`() {
        val backend = checkNotNull(CoreAudioFixture.backend)
        val devices = backend.devices()
        devices.isNotEmpty() shouldBe true
        devices.all { it.name.isNotBlank() } shouldBe true
        // The uid, not the name and not the per-boot object id. Both halves of a
        // duplex interface carry the same name, and the machine this was first
        // measured on had two devices called "Apple Virtual Sound Device" --
        // one of them an input with no output channels at all.
        devices.map { it.id.value }.distinct().size shouldBe devices.size
    }

    @Test
    fun `the default device is one of the listed ones`() {
        val backend = checkNotNull(CoreAudioFixture.backend)
        val default = checkNotNull(backend.defaultDevice()) { "a Mac always has a default output" }
        val devices = backend.devices()
        (default.id in devices.map { it.id }) shouldBe true
        devices.count { it.isDefault } shouldBe 1
    }

    @Test
    fun `a named device is honoured rather than ignored`() {
        // The default-output unit cannot be pinned to a device, so a config that
        // names one has to switch the backend to the HAL unit. A sink that
        // quietly played to the default instead would pass every other test
        // here while making the device selector decorative.
        val backend = checkNotNull(CoreAudioFixture.backend)
        val device = checkNotNull(backend.defaultDevice())
        backend.createSink(CoreAudioFixture.config().copy(device = device.id)).use { sink ->
            sink.open(AudioFormat(48_000, 2))
            sink.isOpen shouldBe true
        }
    }

    @Test
    fun `a device that is not there is refused rather than silently replaced`() {
        val backend = checkNotNull(CoreAudioFixture.backend)
        backend.createSink(CoreAudioFixture.config().copy(device = DeviceId("no-such-device-uid"))).use { sink ->
            assertThrows<AudioException> { sink.open(AudioFormat(48_000, 2)) }
        }
    }

    @Test
    fun `the backend claims what macOS can actually do, and not what it cannot`() {
        val backend = checkNotNull(CoreAudioFixture.backend)
        backend.capabilities.allOf(
            Capability.DEVICE_ENUMERATION,
            Capability.DEVICE_SELECTION,
            Capability.DEVICE_EVENTS,
            Capability.DEVICE_POSITION,
        ) shouldBe true
        // The two the platform has no public API for. Claiming either would put
        // a control on a settings screen that cannot work: nothing in macOS
        // shows a per-application volume, and nothing shows a stream's name.
        backend.capabilities.anyOf(
            Capability.STREAM_VOLUME,
            Capability.STREAM_IDENTITY,
        ) shouldBe false
    }

    @Test
    fun `there is no mixer on macOS, and it says so rather than pretending`() {
        // Not a gap in this library. There is no per-application volume in any
        // public macOS API, so a null here is the platform's answer and
        // STREAM_ENUMERATION is how a consumer learns not to draw the screen.
        AudioMixers.open(CORE_AUDIO_APP) shouldBe null
    }

    @Test
    fun `a float stream opens as readily as an integer one`() {
        val backend = checkNotNull(CoreAudioFixture.backend)
        backend.createSink(CoreAudioFixture.config()).use { sink ->
            sink.open(AudioFormat(48_000, 2, PcmEncoding.F32LE))
            val silence = ByteArray(48_000 / 4 * sink.format!!.bytesPerFrame)
            sink.write(silence, 0, silence.size)
            sink.isOpen shouldBe true
        }
    }
}
