package dev.hivens.libsound.audio

import dev.hivens.libsound.AudioBackend
import dev.hivens.libsound.AudioFormat
import dev.hivens.libsound.Capability
import dev.hivens.libsound.SinkConfig
import dev.hivens.libsound.StreamDirection
import dev.hivens.libsound.StreamEvent
import dev.hivens.libsound.StreamId
import dev.hivens.libsound.VolumeMixer
import dev.hivens.libsound.audio.pipewire.PipeWireBackend
import dev.hivens.libsound.audio.pipewire.PipeWireMixer
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * The mixer read off the graph, against the same graph the shim reads.
 *
 * Every case here plays a stream of its own and asks the mixer about it, which
 * is the only honest way to test a mixer: what is on somebody's machine is not
 * something a suite gets to assume, and a row this suite made is one it can name
 * and put back.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class PipeWireMixerTest {

    private val appName = "libsound pipewire mixer ${ProcessHandle.current().pid()}"

    private var mixer: VolumeMixer? = null
    private var backend: AudioBackend? = null
    private var player: Player? = null

    @BeforeEach
    fun open() {
        mixer = PipeWireMixer.openOrNull(appName)
        AudioTestGate.require("pipewire", mixer != null, "no PipeWire graph reachable")
        backend = PipeWireBackend.connectOrNull("$appName player")
        AudioTestGate.require("pipewire", backend != null, "no PipeWire graph reachable")
    }

    @AfterEach
    fun close() {
        player?.stop()
        player = null
        // The mixer first, so what it restores is applied while the stream it
        // was changed on is still there.
        mixer?.let { runCatching { it.close() } }
        backend?.let { runCatching { it.close() } }
        mixer = null
        backend = null
    }

    @Test
    fun `the mixer says what it covers and what it leaves to the other one`() {
        val mixer = checkNotNull(mixer)
        mixer.capabilities.allOf(
            Capability.STREAM_ENUMERATION,
            Capability.STREAM_CONTROL,
            Capability.STREAM_ROUTING,
            Capability.CAPTURE_ENUMERATION,
            Capability.DEVICE_VOLUME,
        ) shouldBe true
        // Absent rather than present and answering false, so a settings screen
        // asks before it draws a control. Absent because none of it is built,
        // not because the graph withholds it: each is one interface along from
        // what this already binds.
        mixer.capabilities.anyOf(
            Capability.DEVICE_PROFILES,
            Capability.VIRTUAL_DEVICES,
        ) shouldBe false
        // Watching what a row plays is here; watching what one records is not,
        // and they are separate capabilities because aiming at a recording row
        // would tap the device it records from rather than that row.
        (Capability.STREAM_METERING in mixer.capabilities) shouldBe true
        (Capability.CAPTURE_METERING in mixer.capabilities) shouldBe false
        mixer.cards() shouldBe emptyList()
        mixer.createVirtualSink("libsound_never") shouldBe null
    }

    @Test
    fun `a stream this process is playing appears as its own row`() {
        val mixer = checkNotNull(mixer)
        play()
        val row = eventually("our own row") { mixer.streams().firstOrNull { it.applicationName == appName } }
        row.isOurs shouldBe true
        row.direction shouldBe StreamDirection.PLAYBACK
        withClue("a row nobody can name is a row whose slider does nothing") {
            row.id.value.isNotBlank() shouldBe true
        }
        // Waited for separately, and the wait is the point rather than a
        // convenience. A node exists from the moment it is created and is
        // linked a moment later by the session manager, so a row's device is
        // null in between: that is a state a mixer will see and draw, not a gap
        // in what this reports.
        val placed = eventually("the stream to be linked to something") {
            mixer.streams().firstOrNull { it.id == row.id && it.device != null }
        }
        // And it is a device the graph actually has, rather than a name
        // followed out of a link and never checked against anything.
        withClue("the row names a device that is on the graph") {
            checkNotNull(backend).devices().any { it.id == placed.device } shouldBe true
        }
    }

    @Test
    fun `a volume set on a row is read back off the graph`() {
        val mixer = checkNotNull(mixer)
        play()
        val row = eventually("our own row") { mixer.streams().firstOrNull { it.applicationName == appName } }
        // Retried rather than asserted once. A node's global arrives before its
        // parameters do, and a volume cannot be written until the channel count
        // has, so a row is settable a moment after it appears rather than at
        // the instant it does.
        eventually("the row to become settable") { mixer.setVolume(row.id, 0.25f).takeIf { it } }
        val quiet = eventually("the volume to come back") {
            mixer.streams().firstOrNull { it.id == row.id }?.takeIf { abs(it.volume - 0.25f) < TOLERANCE }
        }
        (abs(quiet.volume - 0.25f) < TOLERANCE) shouldBe true

        eventually("the mute to be taken") { mixer.setMuted(row.id, true).takeIf { it } }
        eventually("the mute to come back") {
            mixer.streams().firstOrNull { it.id == row.id }?.takeIf { it.muted }
        }
    }

    @Test
    fun `what this process changed is put back`() {
        val mixer = checkNotNull(mixer)
        play()
        val row = eventually("our own row") { mixer.streams().firstOrNull { it.applicationName == appName } }
        val before = row.volume
        eventually("the row to become settable") { mixer.setVolume(row.id, 0.1f).takeIf { it } }
        eventually("the change to land") {
            mixer.streams().firstOrNull { it.id == row.id }?.takeIf { abs(it.volume - 0.1f) < TOLERANCE }
        }
        // The obligation this interface exists under: a process that lowered
        // something and went away leaves a user with quiet audio and nothing to
        // point at.
        mixer.restoreAll()
        val after = eventually("the restore to land") {
            mixer.streams().firstOrNull { it.id == row.id }?.takeIf { abs(it.volume - before) < TOLERANCE }
        }
        (abs(after.volume - before) < TOLERANCE) shouldBe true
    }

    @Test
    fun `a row appearing and going is reported as two events rather than one redraw`() {
        val mixer = checkNotNull(mixer)
        val seen = CopyOnWriteArrayList<StreamEvent>()
        val cancel = mixer.onStreamsChanged { seen.add(it) }
        try {
            play()
            eventually("an appearance") {
                seen.filterIsInstance<StreamEvent.Appeared>()
                    .firstOrNull { it.stream.applicationName == appName }
            }
            val id = checkNotNull(
                seen.filterIsInstance<StreamEvent.Appeared>()
                    .first { it.stream.applicationName == appName }.stream.id,
            )
            player?.stop()
            player = null
            eventually("a departure") {
                seen.filterIsInstance<StreamEvent.Gone>().firstOrNull { it.id == id }
            }
        } finally {
            cancel()
        }
    }

    @Test
    fun `a meter on a row reports the level of what is playing`() {
        // Loud audio through a stream of this suite's own, metered by id. What
        // is asserted is that the level moves, because a meter that never moves
        // is exactly what a consumer would see if the capability were claimed
        // and the mechanism were not there.
        //
        // Not that it followed the row rather than the device, which this could
        // not tell apart: one stream into a null sink means a capture of either
        // carries the same tone. That aim is the capture stream's, and
        // PipeWirePerStreamCaptureTest is where it is discriminated, by playing
        // a loud stream and a silent one into one sink and aiming at each.
        val mixer = checkNotNull(mixer)
        play(amplitude = LOUD)
        val row = eventually("our own row") { mixer.streams().firstOrNull { it.applicationName == appName } }
        val peaks = CopyOnWriteArrayList<Float>()
        val cancel = mixer.meter(row.id) { peaks.add(it) }
        try {
            val loud = eventually("a level to arrive") { peaks.firstOrNull { it > SILENCE } }
            (loud > SILENCE) shouldBe true
            withClue("a peak outside nought to one is not a linear level") {
                peaks.all { it in 0f..1f } shouldBe true
            }
        } finally {
            cancel()
        }
    }

    @Test
    fun `metering an id that names nothing hands back a cancel that is safe to call`() {
        // The contract asks for a cancel in every case, including the ones
        // where the handler will never run, so a consumer's teardown is the
        // same code either way.
        val mixer = checkNotNull(mixer)
        val cancel = mixer.meter(StreamId("sink-input:999999999")) { error("never") }
        cancel()
        cancel()
    }

    @Test
    fun `an id naming nothing is refused rather than answered for`() {
        val mixer = checkNotNull(mixer)
        // A stream that has gone is a row that springs back, which is the
        // correct rendering and can only be drawn by an implementation that
        // waited for the answer rather than for the request to go out.
        mixer.setVolume(StreamId("sink-input:999999999"), 0.5f) shouldBe false
        mixer.setMuted(StreamId("sink-input:999999999"), true) shouldBe false
        // And one that is not this mixer's shape at all.
        mixer.setVolume(StreamId("not-a-handle"), 0.5f) shouldBe false
    }

    // -- the machinery --------------------------------------------------------

    private fun play(amplitude: Double = 0.0) {
        val sink = checkNotNull(backend).createSink(SinkConfig(applicationName = appName))
        val running = AtomicBoolean(true)
        val thread = Thread(
            {
                runCatching {
                    sink.open(AudioFormat(48_000, 2))
                    val pcm = tone(checkNotNull(sink.format), amplitude)
                    while (running.get()) sink.write(pcm, 0, pcm.size)
                }
            },
            "libsound-mixer-suite",
        )
        thread.isDaemon = true
        thread.start()
        player = Player(running, thread) { runCatching { sink.close() } }
    }

    private class Player(
        private val running: AtomicBoolean,
        private val thread: Thread,
        private val shut: () -> Unit,
    ) {
        fun stop() {
            running.set(false)
            shut()
            thread.join(5_000)
        }
    }

    /** A tenth of a second of tone, or of silence where the case does not need one. */
    private fun tone(format: AudioFormat, amplitude: Double): ByteArray {
        val frames = format.sampleRate / 10
        val pcm = ByteArray(frames * format.bytesPerFrame)
        if (amplitude <= 0.0) return pcm
        for (frame in 0 until frames) {
            val value = (sin(2.0 * PI * 440.0 * frame / format.sampleRate) * amplitude * Short.MAX_VALUE).toInt()
            val at = frame * format.bytesPerFrame
            repeat(format.channels) { channel ->
                pcm[at + channel * 2] = (value and 0xFF).toByte()
                pcm[at + channel * 2 + 1] = ((value shr 8) and 0xFF).toByte()
            }
        }
        return pcm
    }

    private fun <T : Any> eventually(what: String, produce: () -> T?): T {
        val deadline = System.nanoTime() + APPEAR_TIMEOUT_NANOS
        while (System.nanoTime() < deadline) {
            produce()?.let { return it }
            Thread.sleep(50)
        }
        throw AssertionError("never appeared: $what")
    }

    private companion object {
        /** Well inside a step of either scale, and well outside the rounding on one. */
        const val TOLERANCE = 0.01f

        const val APPEAR_TIMEOUT_NANOS = 15_000_000_000L

        /** Loud enough that a level reaching the meter cannot be mistaken for noise. */
        const val LOUD = 0.5

        /** Above anything a null sink's own path carries, which is nothing. */
        const val SILENCE = 0.05f
    }
}
