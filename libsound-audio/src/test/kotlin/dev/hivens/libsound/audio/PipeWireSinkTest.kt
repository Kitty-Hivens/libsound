package dev.hivens.libsound.audio

import dev.hivens.libsound.AudioBackend
import dev.hivens.libsound.AudioFormat
import dev.hivens.libsound.AudioSink
import dev.hivens.libsound.Capability
import dev.hivens.libsound.ChannelLayout
import dev.hivens.libsound.ChannelPosition
import dev.hivens.libsound.MediaRole
import dev.hivens.libsound.PcmEncoding
import dev.hivens.libsound.SinkConfig
import dev.hivens.libsound.audio.pipewire.PipeWireBackend
import dev.hivens.libsound.audio.pipewire.SpaAbi
import dev.hivens.libsound.audio.pulse.PulseAbi
import dev.hivens.libsound.testing.AudioSinkContract
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

private const val APP_NAME = "libsound pipewire suite"

private object PipeWireFixture {
    var backend: AudioBackend? = null

    fun connect() {
        backend = PipeWireBackend.connectOrNull(APP_NAME)
    }

    fun disconnect() {
        backend?.let { runCatching { it.close() } }
        backend = null
    }

    fun gate() {
        AudioTestGate.require("pipewire", backend != null, "no PipeWire graph reachable")
    }

    fun config(): SinkConfig = SinkConfig(
        applicationName = APP_NAME,
        applicationId = "dev.hivens.libsound.test",
        iconName = "audio-x-generic",
        mediaRole = MediaRole.MUSIC,
    )
}

/**
 * The contract, against the graph rather than against the shim in front of it.
 *
 * The same suite every other backend passes, which is the point of having one:
 * a second path to the same speakers either obeys the rules a consumer's clock
 * rides on or it is not a second path, it is a second set of bugs.
 *
 * Runs where libpipewire is loadable and a graph answers. A machine on real
 * PulseAudio skips it, which is a legitimate answer rather than a gap, and
 * `LIBSOUND_REQUIRE=pipewire` turns the skip into a failure on a row that was
 * meant to have one.
 */
class PipeWireSinkContractTest : AudioSinkContract() {

    companion object {
        @JvmStatic
        @BeforeAll
        fun connect() = PipeWireFixture.connect()

        @JvmStatic
        @AfterAll
        fun disconnect() = PipeWireFixture.disconnect()
    }

    @BeforeEach
    fun gate() = PipeWireFixture.gate()

    override fun newSink(): AudioSink = checkNotNull(PipeWireFixture.backend).createSink(PipeWireFixture.config())
}

/**
 * What the native path can say that the compatibility layer cannot.
 *
 * Every case here is a refusal the libpulse backend makes and this one does
 * not, which is the whole argument of section 13 stated as assertions rather
 * than as a table in a document.
 */
class PipeWireBackendTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun connect() = PipeWireFixture.connect()

        @JvmStatic
        @AfterAll
        fun disconnect() = PipeWireFixture.disconnect()
    }

    @BeforeEach
    fun gate() = PipeWireFixture.gate()

    @Test
    fun `a 64-bit float opens, which the pulse protocol has no name for`() {
        // pa_sample_format_t has no 64-bit float at all, so the libpulse
        // backend refuses this and reports the refusal as the server's. The
        // graph has SPA_AUDIO_FORMAT_F64_LE and always did.
        val shape = AudioFormat(48_000, 2, PcmEncoding.F64LE)
        checkNotNull(PipeWireFixture.backend).createSink(PipeWireFixture.config()).use { sink ->
            sink.accepts(shape) shouldBe true
            sink.open(shape)
            val frame = ByteArray(480 * shape.bytesPerFrame)
            sink.write(frame, 0, frame.size)
        }
    }

    @Test
    fun `every encoding opens here`() {
        PcmEncoding.entries.forEach { encoding ->
            val shape = AudioFormat(48_000, 2, encoding)
            checkNotNull(PipeWireFixture.backend).createSink(PipeWireFixture.config()).use { sink ->
                withClue(encoding.name) { sink.accepts(shape) shouldBe true }
                sink.open(shape)
                val frame = ByteArray(480 * shape.bytesPerFrame)
                sink.write(frame, 0, frame.size)
            }
        }
    }

    @Test
    fun `every standard layout opens here, including the four the shim refuses`() {
        // The eight positions the compatibility layer costs are the wide pair,
        // the second low frequency channel, the top side pair and the bottom
        // row, and four standard layouts need one of them: 9.1.6, 7.2.3,
        // hexadecagonal and 22.2. The libpulse backend refuses all four and
        // says the server has no position for the channel, which a consumer
        // reads as the machine being unable to play the file.
        //
        // Every one of the forty opens here, which is the difference stated as
        // an assertion rather than as a table in a document.
        val refusedByTheShim = listOf("9.1.6", "7.2.3", "hexadecagonal", "22.2")
        ChannelLayout.STANDARD.forEach { (name, layout) ->
            val shape = AudioFormat(48_000, layout.channels, PcmEncoding.S16LE, layout)
            checkNotNull(PipeWireFixture.backend).createSink(PipeWireFixture.config()).use { sink ->
                withClue(name) { sink.accepts(shape) shouldBe true }
                sink.open(shape)
                val frame = ByteArray(240 * shape.bytesPerFrame)
                sink.write(frame, 0, frame.size)
            }
        }
        // And the four named above are genuinely the ones that need the eight,
        // rather than four names that happen to be in the list.
        refusedByTheShim.forEach { name ->
            val layout = ChannelLayout.STANDARD.getValue(name)
            withClue(name) { (PulseAbi.unplaceable(layout) != null) shouldBe true }
        }
    }

    @Test
    fun `wider is not unlimited, and the refusal is still there`() {
        // Ten of the thirty-six positions have no equivalent anywhere: the
        // downmix pair, surround direct, side surround, top surround and
        // binaural. A layout naming one of them at more than two channels is
        // refused rather than placed as a neighbour.
        //
        // No standard layout reaches that today. The two that name an
        // unplaceable position, binaural and downmix, are pairs, and below
        // three channels every platform agrees so there is nothing to place.
        // The guard is asserted at the table because that is where it can be:
        // ChannelLayout hands out the standard set and bare counts, so there is
        // no public way to build the case that would reach open.
        listOf(
            ChannelPosition.BIL,
            ChannelPosition.DL,
            ChannelPosition.SDL,
            ChannelPosition.SSL,
            ChannelPosition.TTL,
        ).forEach { position ->
            withClue(position.name) { SpaAbi.channelOf(position) shouldBe null }
        }
        // A pair of them still opens, because a pair needs no placing.
        val binaural = ChannelLayout.STANDARD.getValue("binaural")
        val shape = AudioFormat(48_000, 2, PcmEncoding.S16LE, binaural)
        checkNotNull(PipeWireFixture.backend).createSink(PipeWireFixture.config()).use { sink ->
            sink.accepts(shape) shouldBe true
        }
    }

    @Test
    fun `the backend says what it is and what it is not`() {
        val backend = checkNotNull(PipeWireFixture.backend)
        backend.name shouldBe "pipewire"
        // What it has over the shim.
        backend.capabilities.allOf(
            Capability.CHANNEL_PLACEMENT,
            Capability.TOTAL_LATENCY,
            Capability.LOW_LATENCY,
        ) shouldBe true
        // And what it does not have, which is everything that needs the
        // registry. A consumer that asks first draws no device menu here.
        backend.capabilities.anyOf(
            Capability.DEVICE_ENUMERATION,
            Capability.DEVICE_SELECTION,
            Capability.DEVICE_EVENTS,
            Capability.STREAM_VOLUME,
            Capability.CAPTURE,
        ) shouldBe false
        backend.devices().isEmpty() shouldBe true
    }
}
