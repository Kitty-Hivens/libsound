package dev.hivens.libsound.audio

import dev.hivens.libsound.AudioBackend
import dev.hivens.libsound.AudioFormat
import dev.hivens.libsound.AudioSink
import dev.hivens.libsound.AudioSource
import dev.hivens.libsound.Capability
import dev.hivens.libsound.ChannelLayout
import dev.hivens.libsound.ChannelPosition
import dev.hivens.libsound.MediaRole
import dev.hivens.libsound.PcmEncoding
import dev.hivens.libsound.SinkConfig
import dev.hivens.libsound.SourceConfig
import dev.hivens.libsound.StreamDirection
import dev.hivens.libsound.audio.pipewire.PipeWireBackend
import dev.hivens.libsound.audio.pipewire.SpaAbi
import dev.hivens.libsound.audio.pulse.PulseAbi
import dev.hivens.libsound.audio.pulse.PulseBackend
import dev.hivens.libsound.testing.AudioSinkContract
import dev.hivens.libsound.testing.AudioSourceContract
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.math.abs

private const val APP_NAME = "libsound pipewire suite"

/** One step of the pulse protocol's own volume scale is far finer than this. */
private const val VOLUME_TOLERANCE = 0.01f

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
 * The capture contract, against the graph.
 *
 * Named separately in `LIBSOUND_REQUIRE` for the reason the JavaSound capture
 * suite is: it records whatever the graph calls the default input, which on a
 * developer's machine is a microphone in a room. On the isolated server and on
 * CI that is a null sink's monitor and there is nothing to overhear.
 */
class PipeWireSourceContractTest : AudioSourceContract() {

    companion object {
        @JvmStatic
        @BeforeAll
        fun connect() = PipeWireFixture.connect()

        @JvmStatic
        @AfterAll
        fun disconnect() = PipeWireFixture.disconnect()
    }

    @BeforeEach
    fun gate() {
        PipeWireFixture.gate()
        // Named rather than run by default. The gate's own mechanism, so the
        // parsing of LIBSOUND_REQUIRE lives in one place.
        Assumptions.assumeTrue(
            AudioTestGate.isRequired("pipewire-capture"),
            "capture records the graph's default input; name pipewire-capture to run it",
        )
    }

    override fun newSource(): AudioSource = checkNotNull(PipeWireFixture.backend).createSource(
        SourceConfig(
            applicationName = APP_NAME,
            applicationId = "dev.hivens.libsound.test",
            mediaRole = MediaRole.MUSIC,
        ),
    )
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
        // What it has over the shim, plus what the registry brought.
        backend.capabilities.allOf(
            Capability.CHANNEL_PLACEMENT,
            Capability.TOTAL_LATENCY,
            Capability.LOW_LATENCY,
            Capability.CAPTURE,
            Capability.STREAM_VOLUME,
            Capability.DEVICE_ENUMERATION,
            Capability.DEVICE_SELECTION,
            Capability.DEVICE_EVENTS,
        ) shouldBe true
    }

    @Test
    fun `the device list arrives without anybody asking for it`() {
        // The shape that differs from every other backend here. A registry
        // global carries the object's whole property dict, so the list is what
        // has been heard rather than a round trip: by the time a consumer asks,
        // the answer is already in hand.
        //
        // Any graph that can play at all has a sink on it, which is what makes
        // this assertable rather than a count that depends on the machine.
        val backend = checkNotNull(PipeWireFixture.backend)
        val sinks = backend.devices()
        withClue("the graph reported no audio sink at all") { sinks.isNotEmpty() shouldBe true }
        sinks.all { it.id.value.isNotBlank() && it.name.isNotBlank() } shouldBe true
        sinks.all { it.direction == StreamDirection.PLAYBACK } shouldBe true
        backend.captureDevices().all { it.direction == StreamDirection.CAPTURE } shouldBe true
    }

    @Test
    fun `a device row carries the device's own volume, on the same scale as the shim`() {
        // The one thing on a device row that is not on the global's property
        // dict. It is a parameter of the node, so it costs a bind and a
        // subscription, and it is the last of the device fields the libpulse
        // backend reported and this one could not.
        //
        // The scale is asserted rather than assumed, and against the other
        // backend rather than against a constant. Both report linear amplitude:
        // half through the pulse protocol is a quarter of the way up a slider
        // and reads here as an eighth, because the protocol's own scale is the
        // cube root. A backend that reported the slider position instead would
        // pass every test that only checked the range.
        val backend = checkNotNull(PipeWireFixture.backend)
        (Capability.DEVICE_VOLUME in backend.capabilities) shouldBe true
        val devices = backend.devices()
        withClue("no device row carried a volume") {
            devices.any { it.volume != null } shouldBe true
        }
        devices.forEach { device ->
            withClue(device.name) {
                device.volume?.let { (it in 0f..1f) shouldBe true }
                device.muted shouldNotBe null
            }
        }
    }

    @Test
    fun `the two backends report the same device at the same volume`() {
        // The scale, checked against the other path to the same graph rather
        // than against a number written down here.
        //
        // Neither reports what a slider shows. The pulse protocol's own scale
        // is the cube root of amplitude, and pa_sw_volume_to_linear undoes it,
        // so a sink at half on a slider is 0.125 on both sides. A backend that
        // passed the graph's float through where the other cubed it, or the
        // reverse, would still be inside nought to one and would still look
        // right in a settings screen, and this is the only place the two can
        // disagree loudly.
        val native = checkNotNull(PipeWireFixture.backend)
        val shim = PulseBackend.connectOrNull("$APP_NAME shim") ?: return
        try {
            val theirs = shim.devices().associate { it.id to it.volume }
            val shared = native.devices().filter { it.id in theirs && it.volume != null }
            withClue("the two backends named no device in common") { shared.isNotEmpty() shouldBe true }
            shared.forEach { device ->
                val other = theirs.getValue(device.id)
                withClue("${device.id}: native ${device.volume} against shim $other") {
                    (abs(checkNotNull(device.volume) - checkNotNull(other)) < VOLUME_TOLERANCE) shouldBe true
                }
            }
        } finally {
            shim.close()
        }
    }

    @Test
    fun `a connection that has just returned already knows the graph`() {
        // The connect-time contract, on a backend of its own: every other case
        // here runs long after the fixture connected, so it would hold whether
        // anything waited for the graph or not.
        //
        // A registry global is an event rather than a reply, so no call's
        // returning means the list is complete. What connect waits out instead
        // is two syncs, which the graph answers after everything it had already
        // queued: one for the globals and one for what binding the metadata
        // object among them asked for.
        //
        // A guard rather than a proof, and worth saying so. On a machine where
        // the graph answers faster than the harness gets to the assertion this
        // passes with no waiting at all, which was measured, so what it catches
        // is a connection that enumerates nothing rather than one that
        // enumerates late.
        val fresh = checkNotNull(PipeWireBackend.connectOrNull("$APP_NAME fresh"))
        try {
            withClue("connect returned before the graph had been enumerated") {
                fresh.devices().isNotEmpty() shouldBe true
            }
            withClue("connect returned before the default was known") {
                fresh.defaultDevice() shouldNotBe null
            }
        } finally {
            fresh.close()
        }
    }

    @Test
    fun `a device names something a stream can actually be pointed at`() {
        // The list is only worth having if its ids work, and here an id is a
        // node.name that goes across as target.object. A device menu whose rows
        // cannot be selected is a menu with dead rows.
        val backend = checkNotNull(PipeWireFixture.backend)
        val device = backend.devices().firstOrNull() ?: return
        backend.createSink(PipeWireFixture.config().copy(device = device.id)).use { sink ->
            sink.open(AudioFormat(48_000, 2))
            sink.isOpen shouldBe true
            val frame = ByteArray(480 * sink.format!!.bytesPerFrame)
            sink.write(frame, 0, frame.size)
        }
    }

    @Test
    fun `which device is default is read rather than guessed`() {
        // Not a property of the graph. The session manager writes it into a
        // metadata object, so the answer comes from binding that object and
        // listening to it, which is the one proxy this backend holds.
        //
        // Asserted against the list rather than against a name, because the
        // name is the machine's: what has to be true is that the default is one
        // of the devices offered, that it is marked as the default there, and
        // that exactly one row is.
        val backend = checkNotNull(PipeWireFixture.backend)
        val default = backend.defaultDevice()
        withClue("the graph named no default sink") { default shouldNotBe null }
        val devices = backend.devices()
        devices.count { it.isDefault } shouldBe 1
        devices.first().isDefault shouldBe true
        devices.map { it.id } shouldContain checkNotNull(default).id
        // The capture side goes through the same object and is allowed to be
        // unknown: a graph whose only input is a sink's monitor has a default
        // naming a node that is not in the capture list, because a monitor is
        // not a node of its own here.
        backend.defaultCaptureDevice()?.let { input ->
            backend.captureDevices().map { it.id } shouldContain input.id
            input.isDefault shouldBe true
        }
    }

    @Test
    fun `a volume set on the stream is the stream's, at the system level`() {
        // A control on the node rather than arithmetic on the samples, which is
        // the whole of what STREAM_VOLUME distinguishes: the desktop's mixer
        // shows this one and follows it.
        checkNotNull(PipeWireFixture.backend).createSink(PipeWireFixture.config()).use { sink ->
            sink.open(AudioFormat(48_000, 2))
            (Capability.STREAM_VOLUME in sink.capabilities) shouldBe true
            sink.setVolume(0.4f)
            sink.volume() shouldBe 0.4f
            // Clamped rather than refused, which the contract suite asserts for
            // every backend and is restated here because this one applies it
            // natively rather than remembering it.
            sink.setVolume(4f)
            sink.volume() shouldBe 1f
        }
    }
}
