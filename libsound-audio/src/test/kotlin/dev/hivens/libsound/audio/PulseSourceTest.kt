package dev.hivens.libsound.audio

import dev.hivens.libsound.AudioBackend
import dev.hivens.libsound.AudioFormat
import dev.hivens.libsound.AudioSource
import dev.hivens.libsound.Capability
import dev.hivens.libsound.DeviceId
import dev.hivens.libsound.LatencyProfile
import dev.hivens.libsound.MediaRole
import dev.hivens.libsound.SourceConfig
import dev.hivens.libsound.StreamDirection
import dev.hivens.libsound.audio.pulse.PulseBackend
import dev.hivens.libsound.testing.AudioSourceContract
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

private const val CAPTURE_APP_NAME = "libsound capture suite"

private object CaptureFixture {
    var backend: AudioBackend? = null

    fun connect() {
        backend = PulseBackend.connectOrNull(CAPTURE_APP_NAME)
    }

    fun disconnect() {
        backend?.let { runCatching { it.close() } }
        backend = null
    }

    fun gate() {
        AudioTestGate.require("pulse", backend != null, "no PulseAudio or PipeWire server reachable")
    }

    /**
     * A sink's monitor where the machine has one, and the default input
     * otherwise.
     *
     * The code path is the same either way: one `pa_stream_connect_record`
     * against a named source. What differs is whose audio it is, and a suite
     * that opened a developer's microphone every time it ran would be recording
     * a room nobody agreed to have recorded. LIBSOUND_CAPTURE_DEVICE names a
     * source to use instead, for a run that means to exercise a real input.
     */
    fun device(): DeviceId? {
        System.getenv("LIBSOUND_CAPTURE_DEVICE")?.takeIf { it.isNotBlank() }?.let { return DeviceId(it) }
        return backend?.captureDevices()?.firstOrNull { it.isMonitor }?.id
    }

    fun config(latency: LatencyProfile = LatencyProfile.BALANCED): SourceConfig = SourceConfig(
        applicationName = CAPTURE_APP_NAME,
        applicationId = "dev.hivens.libsound.test",
        iconName = "audio-input-microphone",
        mediaRole = MediaRole.MUSIC,
        device = device(),
        latency = latency,
    )
}

/**
 * The capture contract, against a real sound server.
 *
 * The mirror of `PulseSinkContractTest`, and the point of writing the two
 * suites to the same shape: a backend that passes both can be read from and
 * written to by one consumer without it learning two sets of rules.
 */
class PulseSourceContractTest : AudioSourceContract() {

    companion object {
        @JvmStatic
        @BeforeAll
        fun connect() = CaptureFixture.connect()

        @JvmStatic
        @AfterAll
        fun disconnect() = CaptureFixture.disconnect()
    }

    @BeforeEach
    fun gate() = CaptureFixture.gate()

    override fun newSource(): AudioSource = checkNotNull(CaptureFixture.backend).createSource(CaptureFixture.config())
}

class PulseCaptureTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun connect() = CaptureFixture.connect()

        @JvmStatic
        @AfterAll
        fun disconnect() = CaptureFixture.disconnect()
    }

    private val format = AudioFormat(48_000, 2)

    @BeforeEach
    fun gate() = CaptureFixture.gate()

    @Test
    fun `the server lists capture devices with names worth showing`() {
        val backend = checkNotNull(CaptureFixture.backend)
        val devices = backend.captureDevices()
        // Also the cheapest check there is on the new half of the ABI table: the
        // names come from oracle-derived offsets, and a wrong one yields a null
        // pointer or mojibake rather than a plausible label.
        devices.isNotEmpty() shouldBe true
        devices.all { it.id.value.isNotBlank() } shouldBe true
        devices.all { it.name.isNotBlank() } shouldBe true
        devices.all { it.direction == StreamDirection.CAPTURE } shouldBe true
    }

    @Test
    fun `a monitor is listed and marked as one`() {
        // Any machine with an output has a monitor source for it. Offering it as
        // a microphone would confuse everyone who read the list, and hiding it
        // would take away recording what the speakers are playing, so it is
        // listed and flagged.
        val backend = checkNotNull(CaptureFixture.backend)
        val monitors = backend.captureDevices().filter { it.isMonitor }
        monitors.isNotEmpty() shouldBe true
        // The playback list and the capture list are different lists: a monitor
        // belongs to one and its sink to the other.
        backend.devices().none { it.id in monitors.map { monitor -> monitor.id } } shouldBe true
    }

    @Test
    fun `the default capture device is one of the listed ones`() {
        val backend = checkNotNull(CaptureFixture.backend)
        val default = checkNotNull(backend.defaultCaptureDevice()) { "a running server always has a default source" }
        val devices = backend.captureDevices()
        (default.id in devices.map { it.id }) shouldBe true
        devices.count { it.isDefault } shouldBe 1
    }

    @Test
    fun `the capture stream reaches the server under the name and role we chose`() {
        // The same end-to-end claim the output side makes, and the one that
        // matters more here: this is the row a desktop reads to tell a user
        // which application has their microphone open.
        val backend = checkNotNull(CaptureFixture.backend)
        backend.createSource(CaptureFixture.config()).use { source ->
            source.open(format)
            val chunk = ByteArray(format.sampleRate / 20 * format.bytesPerFrame)
            source.read(chunk, 0, chunk.size)

            val listing = pactl("list", "source-outputs")
            listing.contains(CAPTURE_APP_NAME) shouldBe true
            listing.contains("media.role = \"music\"") shouldBe true
        }
    }

    @Test
    fun `a read comes back with frames and a plausible latency`() {
        val backend = checkNotNull(CaptureFixture.backend)
        backend.createSource(CaptureFixture.config()).use { source ->
            source.open(format)
            val chunk = ByteArray(format.sampleRate / 10 * format.bytesPerFrame)
            val startedAt = System.nanoTime()
            source.read(chunk, 0, chunk.size)
            val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

            // A tenth of a second of audio cannot arrive much faster than a
            // tenth of a second. A source that answered instantly would be
            // handing back a buffer nobody filled.
            (elapsedMillis > 50) shouldBe true
            source.framePosition() shouldBeGreaterThan 0L
            val latency = source.latencyNanos()
            // Zero would mean the timing info never arrived; a second would mean
            // the fragment size was ignored.
            (latency in 0..1_000_000_000L) shouldBe true
            source.overrunFrames() shouldBe 0L
        }
    }

    @Test
    fun `a shorter profile is a shorter fragment`() {
        val backend = checkNotNull(CaptureFixture.backend)
        fun firstReadMillis(profile: LatencyProfile): Long =
            backend.createSource(CaptureFixture.config(profile)).use { source ->
                source.open(format)
                // One fragment's worth: the server hands data over in fragments,
                // so how long the first small read takes is how long a fragment
                // is.
                val chunk = ByteArray(format.bytesPerFrame * 128)
                val startedAt = System.nanoTime()
                source.read(chunk, 0, chunk.size)
                (System.nanoTime() - startedAt) / 1_000_000
            }

        // Warmed first: the very first record stream on a suspended device pays
        // for waking it, which has nothing to do with the fragment size.
        firstReadMillis(LatencyProfile.LOW)
        val relaxed = firstReadMillis(LatencyProfile.RELAXED)
        val low = firstReadMillis(LatencyProfile.LOW)
        (low < relaxed) shouldBe true
    }

    @Test
    fun `a source claims what it can actually do`() {
        val backend = checkNotNull(CaptureFixture.backend)
        (Capability.CAPTURE in backend.capabilities) shouldBe true
        backend.createSource(CaptureFixture.config()).use { source ->
            source.capabilities.allOf(
                Capability.CAPTURE,
                Capability.STREAM_VOLUME,
                Capability.STREAM_IDENTITY,
                Capability.DEVICE_POSITION,
            ) shouldBe true
            // A source cannot enumerate devices or subscribe to their events,
            // and claiming either would have a consumer offering a control that
            // reaches nothing.
            source.capabilities.anyOf(
                Capability.DEVICE_ENUMERATION,
                Capability.DEVICE_SELECTION,
                Capability.DEVICE_EVENTS,
            ) shouldBe false
        }
    }
}

private fun pactl(vararg args: String): String = runCatching {
    val process = ProcessBuilder(listOf("pactl") + args)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.readAllBytes().decodeToString()
    process.waitFor(5, TimeUnit.SECONDS)
    output
}.getOrDefault("")
