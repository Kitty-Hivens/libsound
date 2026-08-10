package dev.hivens.libsound.audio

import dev.hivens.libsound.AudioBackend
import dev.hivens.libsound.AudioFormat
import dev.hivens.libsound.AudioSink
import dev.hivens.libsound.Capability
import dev.hivens.libsound.MediaRole
import dev.hivens.libsound.SinkConfig
import dev.hivens.libsound.audio.pulse.PulseBackend
import dev.hivens.libsound.testing.AudioSinkContract
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val APP_NAME = "libsound contract suite"

private const val CONCURRENT_READERS = 8

private object PulseFixture {
    var backend: AudioBackend? = null

    fun connect() {
        backend = PulseBackend.connectOrNull(APP_NAME)
    }

    fun disconnect() {
        backend?.let { runCatching { it.close() } }
        backend = null
    }

    fun gate() {
        AudioTestGate.require("pulse", backend != null, "no PulseAudio or PipeWire server reachable")
    }

    fun config(): SinkConfig = SinkConfig(
        applicationName = APP_NAME,
        applicationId = "dev.hivens.libsound.test",
        iconName = "audio-x-generic",
        mediaRole = MediaRole.MUSIC,
    )
}

/**
 * The contract, against a real sound server.
 *
 * Runs only where one is reachable. On CI that is Linux with a null sink; the
 * gate turns a missing server into a loud failure when the row was supposed to
 * have one, and into a skip on a developer machine that has not started it.
 */
class PulseSinkContractTest : AudioSinkContract() {

    companion object {
        @JvmStatic
        @BeforeAll
        fun connect() = PulseFixture.connect()

        @JvmStatic
        @AfterAll
        fun disconnect() = PulseFixture.disconnect()
    }

    @BeforeEach
    fun gate() = PulseFixture.gate()

    override fun newSink(): AudioSink = checkNotNull(PulseFixture.backend).createSink(PulseFixture.config())
}

class PulseBackendTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun connect() = PulseFixture.connect()

        @JvmStatic
        @AfterAll
        fun disconnect() = PulseFixture.disconnect()
    }

    private val format = AudioFormat(48_000, 2)

    @BeforeEach
    fun gate() = PulseFixture.gate()

    @Test
    fun `the server lists devices with names worth showing`() {
        val backend = checkNotNull(PulseFixture.backend)
        val devices = backend.devices()
        // Also the cheapest possible check on the ABI table: the names and
        // descriptions are read at oracle-derived offsets, and a wrong offset
        // yields a null pointer or mojibake rather than a plausible label.
        devices.isNotEmpty() shouldBe true
        devices.all { it.id.value.isNotBlank() } shouldBe true
        devices.all { it.name.isNotBlank() } shouldBe true
    }

    @Test
    fun `the default device is one of the listed ones`() {
        val backend = checkNotNull(PulseFixture.backend)
        val default = backend.defaultDevice()
        checkNotNull(default) { "a running server always has a default sink" }
        // One round trip, and no comparison of a value with itself -- the first
        // cut asserted `devices().map { it.id } shouldBe devices().map { it.id }`,
        // which no implementation can fail.
        val devices = backend.devices()
        (default.id in devices.map { it.id }) shouldBe true
        devices.single { it.id == default.id }.isDefault shouldBe true
        devices.count { it.isDefault } shouldBe 1
    }

    @Test
    fun `concurrent enumeration does not shred the answer`() {
        // Holding the mainloop lock is not enough on its own, because the wait
        // releases it: two threads asking at once each cleared the other's
        // collection and then read a list holding both replies. A settings
        // screen refreshing while a device event lands is exactly that, and it
        // shows up as a doubled or truncated list rather than an exception.
        val backend = checkNotNull(PulseFixture.backend)
        val expected = backend.devices().map { it.id.value }.sorted()
        expected.isNotEmpty() shouldBe true

        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(CONCURRENT_READERS)
        try {
            val futures = (1..CONCURRENT_READERS).map {
                pool.submit<List<List<String>>> {
                    start.await()
                    (1..15).map { backend.devices().map { device -> device.id.value }.sorted() }
                }
            }
            start.countDown()
            val results = futures.flatMap { it.get(60, TimeUnit.SECONDS) }
            results.none { it.size != it.distinct().size } shouldBe true
            results.all { it == expected } shouldBe true
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `a write is paced by the device rather than buffered and returned`() {
        // The interface's central rule, and the one the shared contract cannot
        // check: a fake with a buffer large enough for the suite would return
        // instantly and be right to. Only a real device paces.
        val backend = checkNotNull(PulseFixture.backend)
        backend.createSink(PulseFixture.config()).use { sink ->
            sink.open(format)
            val oneSecond = ByteArray(format.sampleRate * format.bytesPerFrame)
            val startedAt = System.nanoTime()
            sink.write(oneSecond, 0, oneSecond.size)
            val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
            // The buffer is 200 ms, so a second of audio cannot be accepted in
            // much less than 800 ms. A sink that queued it all and returned
            // would turn a consumer's decode loop into a busy loop.
            (elapsedMillis > 500) shouldBe true
        }
    }

    @Test
    fun `the backend claims what it can actually do`() {
        val backend = checkNotNull(PulseFixture.backend)
        backend.capabilities.allOf(
            Capability.STREAM_VOLUME,
            Capability.STREAM_IDENTITY,
            Capability.DEVICE_ENUMERATION,
            Capability.DEVICE_SELECTION,
            Capability.DEVICE_EVENTS,
            Capability.DEVICE_POSITION,
        ) shouldBe true
    }

    @Test
    fun `the stream reaches the server under the name and role we chose`() {
        // The whole point of the library, asserted end to end: an addressable
        // stream rather than an anonymous client row. If this passes, an
        // EasyEffects rule can match it.
        val backend = checkNotNull(PulseFixture.backend)
        backend.createSink(PulseFixture.config()).use { sink ->
            sink.open(format)
            val half = ByteArray(format.sampleRate / 2 * format.bytesPerFrame)
            sink.write(half, 0, half.size)

            val listing = pactlSinkInputs()
            listing.contains(APP_NAME) shouldBe true
            listing.contains("media.role = \"music\"") shouldBe true
        }
    }

    @Test
    fun `the playhead answers immediately after open`() {
        // Without the timing flags and the explicit first update, pa_stream_get_time
        // answers NODATA for the life of the stream and this reads as a device
        // that never starts.
        val backend = checkNotNull(PulseFixture.backend)
        backend.createSink(PulseFixture.config()).use { sink ->
            sink.open(format)
            sink.framePosition() shouldBe 0L
            val half = ByteArray(format.sampleRate / 2 * format.bytesPerFrame)
            sink.write(half, 0, half.size)
            sink.framePosition() shouldBeGreaterThan 0L
        }
    }

    @Test
    fun `latency is reported and plausible`() {
        val backend = checkNotNull(PulseFixture.backend)
        backend.createSink(PulseFixture.config()).use { sink ->
            sink.open(format)
            val half = ByteArray(format.sampleRate / 2 * format.bytesPerFrame)
            sink.write(half, 0, half.size)
            val latency = sink.latencyNanos()
            // A zero would mean the timing info never arrived; anything past a
            // second would mean the buffer request was ignored.
            latency shouldBeGreaterThan 0L
            (latency < 1_000_000_000L) shouldBe true
        }
    }
}

private fun pactlSinkInputs(): String = runCatching {
    val process = ProcessBuilder("pactl", "list", "sink-inputs")
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.readAllBytes().decodeToString()
    process.waitFor(5, TimeUnit.SECONDS)
    output
}.getOrDefault("")
