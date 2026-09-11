package dev.hivens.libsound.audio

import dev.hivens.libsound.AudioBackend
import dev.hivens.libsound.AudioFormat
import dev.hivens.libsound.Capability
import dev.hivens.libsound.SinkConfig
import dev.hivens.libsound.SourceConfig
import dev.hivens.libsound.StreamDirection
import dev.hivens.libsound.StreamEvent
import dev.hivens.libsound.StreamId
import dev.hivens.libsound.VolumeMixer
import dev.hivens.libsound.audio.pipewire.PipeWireBackend
import dev.hivens.libsound.audio.pipewire.PipeWireMixer
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
        // asks before it draws a control. Cards and profiles are a Device
        // global with its own parameters, one interface along from what this
        // binds, so the graph does not withhold them. What is missing is
        // anywhere to try them: this graph has no card in it by construction,
        // and the suite does not run against a machine that has one.
        (Capability.DEVICE_PROFILES in mixer.capabilities) shouldBe false
        mixer.cards() shouldBe emptyList()
        // Watching what a row plays is here and watching what one records is
        // not. They are separate capabilities because aiming at a recording row
        // would tap the device it records from rather than that row.
        (Capability.STREAM_METERING in mixer.capabilities) shouldBe true
        (Capability.CAPTURE_METERING in mixer.capabilities) shouldBe false
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
    fun `a stream this process is recording appears as a capture row`() {
        // The other direction, which the capability set claims separately
        // because a backend can list what is playing without listing what is
        // listening, and because that second list is the one a person reads to
        // find out what has their microphone open.
        val mixer = checkNotNull(mixer)
        val source = checkNotNull(backend).createSource(SourceConfig(applicationName = "$appName recorder"))
        source.use {
            it.open(AudioFormat(48_000, 1))
            val row = eventually("our own capture row") {
                mixer.streams().firstOrNull {
                    row -> row.applicationName == "$appName recorder" && row.direction == StreamDirection.CAPTURE
                }
            }
            row.isOurs shouldBe true
            withClue("a capture row nobody can name is a row whose slider does nothing") {
                row.id.value.isNotBlank() shouldBe true
            }
        }
    }

    @Test
    fun `the default device moves, and restore leaves it where the caller put it`() {
        val mixer = checkNotNull(mixer)
        val was = checkNotNull(checkNotNull(backend).defaultDevice()) { "this graph has no default" }
        val name = "libsound_pw_default_${ProcessHandle.current().pid()}"
        val made = checkNotNull(mixer.createVirtualSink(name))
        try {
            mixer.setDefaultDevice(made) shouldBe true
            eventually("the default to move") {
                checkNotNull(backend).defaultDevice()?.takeIf { it.id == made }
            }
            // The one thing this interface changes and does not put back. A
            // default somebody picked through a settings screen is a decision,
            // not a change made on their behalf, so restoring it would undo the
            // thing they asked for.
            //
            // What the restore does take away is the device itself, since this
            // process created it, so the graph is left naming one that is not
            // there and the default reads as unknown. A mixer that recorded
            // defaults would have put the old one back instead, and that is the
            // difference this can see.
            mixer.restoreAll()
            withClue("restore put back a default the caller had replaced") {
                checkNotNull(backend).defaultDevice()?.id shouldNotBe was.id
            }
        } finally {
            // Put back by hand, because the mixer is right not to. The device
            // this pointed at is about to go, and a graph left naming one that
            // does not exist is a graph the cases after this one play into.
            mixer.setDefaultDevice(was.id)
            mixer.removeVirtualSink(made)
        }
    }

    @Test
    fun `a volume set on a row is readable the moment the setter answers`() {
        val mixer = checkNotNull(mixer)
        play()
        val row = eventually("our own row") { mixer.streams().firstOrNull { it.applicationName == appName } }
        // Retried rather than asserted once. This binding asks a node about its
        // parameters when the node's global arrives, so there is a moment after
        // a row appears in which the channel count a volume is written with has
        // not come back yet. That is this code's own sequencing rather than
        // anything the graph does, and it ends on its own.
        eventually("the row to become settable") { mixer.setVolume(row.id, 0.25f).takeIf { it } }
        // Read at once, and that is the assertion. The interface asks a setter
        // for the server's answer rather than for the fact that a request went
        // out, and the parameter carrying the new value back is queued ahead of
        // that answer, so a true that is not yet readable is a setter reporting
        // the socket.
        val quiet = checkNotNull(mixer.streams().firstOrNull { it.id == row.id }) { "the row went away" }
        withClue("the setter answered before the graph did") {
            (abs(quiet.volume - 0.25f) < TOLERANCE) shouldBe true
        }

        eventually("the mute to be taken") { mixer.setMuted(row.id, true).takeIf { it } }
        val muted = checkNotNull(mixer.streams().firstOrNull { it.id == row.id }) { "the row went away" }
        withClue("the mute answered before the graph did") { muted.muted shouldBe true }
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
    fun `a row appearing, changing and going is reported as three kinds of event`() {
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
            // The middle kind, which is the whole reason the difference is
            // worked out here rather than passed on as one coarse signal: a
            // list redrawn wholesale loses the scroll position and the drag in
            // progress, and a row whose volume moved is the common case.
            eventually("the row to become settable") { mixer.setVolume(id, 0.4f).takeIf { it } }
            eventually("a change") {
                seen.filterIsInstance<StreamEvent.Changed>()
                    .firstOrNull { it.stream.id == id && abs(it.stream.volume - 0.4f) < TOLERANCE }
            }
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
    fun `a cancelled meter releases the thread that was reading it`() {
        val mixer = checkNotNull(mixer)
        play(amplitude = LOUD)
        val row = eventually("our own row") { mixer.streams().firstOrNull { it.applicationName == appName } }
        val idle = meterThreads()
        val peaks = AtomicInteger()
        val cancel = mixer.meter(row.id) { peaks.incrementAndGet() }
        eventually("a level to arrive") { peaks.get().takeIf { seen -> seen > 0 } }
        cancel()
        // Cancelled twice, because a consumer that lost track of whether it
        // already had is not a reason to tear the same stream down twice.
        cancel()
        // The reader is the thing to assert on. A handler that stops being
        // called says nothing either way: a reader parked forever on a stream
        // nobody closed stops calling it too, which is the leak rather than the
        // absence of one.
        eventually("the reader to finish") { meterThreads().takeIf { live -> live == idle } }
    }

    @Test
    fun `closing the mixer takes down a meter nobody cancelled`() {
        // The case a consumer reaches by closing without cancelling first. Each
        // meter is a capture stream and a thread reading it, and the only
        // reference to either is the cancel handed to whoever asked, so a mixer
        // that does not keep its own list leaves both behind for the life of
        // the process.
        val mixer = checkNotNull(mixer)
        play(amplitude = LOUD)
        val row = eventually("our own row") { mixer.streams().firstOrNull { it.applicationName == appName } }
        val idle = meterThreads()
        val peaks = AtomicInteger()
        mixer.meter(row.id) { peaks.incrementAndGet() }
        eventually("a level to arrive") { peaks.get().takeIf { seen -> seen > 0 } }
        // A second connection, so the row is read out of the graph's own
        // account of what is on it rather than out of this mixer's memory.
        val watcher = checkNotNull(PipeWireMixer.openOrNull("$appName watcher"))
        watcher.use { watching ->
            eventually("the meter's own row to appear") {
                watching.streams().firstOrNull { it.applicationName == "$appName meter" }
            }
            mixer.close()
            eventually("the meter's row to go with the mixer") {
                watching.streams().none { it.applicationName == "$appName meter" }.takeIf { gone -> gone }
            }
        }
        eventually("the reader to finish") { meterThreads().takeIf { live -> live == idle } }
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
    fun `a device this process made appears, takes a stream, and goes when it is removed`() {
        val mixer = checkNotNull(mixer)
        (Capability.VIRTUAL_DEVICES in mixer.capabilities) shouldBe true
        val name = "libsound_pw_virtual_${ProcessHandle.current().pid()}"
        val made = checkNotNull(mixer.createVirtualSink(name)) { "the graph refused a device of its own" }
        made.value shouldBe name
        // On the graph, in the backend's own list, which is a second connection
        // reading it rather than this one remembering what it asked for.
        withClue("the device the mixer made is not on the graph") {
            checkNotNull(backend).devices().any { it.id == made } shouldBe true
        }

        // And a stream can be pointed at it, which is what a separate bus is
        // for and the only thing that makes one worth having.
        play()
        val row = eventually("our own row") { mixer.streams().firstOrNull { it.applicationName == appName } }
        eventually("the move to be taken") { mixer.moveTo(row.id, made).takeIf { it } }
        eventually("the stream to land on it") {
            mixer.streams().firstOrNull { it.id == row.id && it.device == made }
        }

        mixer.removeVirtualSink(made) shouldBe true
        eventually("the device to go") {
            checkNotNull(backend).devices().none { it.id == made }.takeIf { it }
        }
        // Removing one twice is a caller that lost track, not a reason to throw.
        mixer.removeVirtualSink(made) shouldBe false
    }

    @Test
    fun `a device is laid out with the channels it was asked for`() {
        val mixer = checkNotNull(mixer)
        val name = "libsound_pw_six_${ProcessHandle.current().pid()}"
        // Honoured or refused, never narrowed. Null here would be the graph
        // declining, and an id is a promise that six channels are there.
        val made = checkNotNull(mixer.createVirtualSink(name, channels = 6)) {
            "the graph refused a six channel device"
        }
        try {
            // A volume is written one entry per channel, off the count the
            // device itself reports, so this passing on a device that came back
            // as stereo would mean six entries went to a two channel node.
            eventually("the six channel device to become settable") {
                mixer.setDeviceVolume(made, 0.5f).takeIf { it }
            }
        } finally {
            mixer.removeVirtualSink(made)
        }
    }

    @Test
    fun `a device's own volume and mute go through, and restore puts them back`() {
        // The other half of a mixer, on a device rather than a row. Done on
        // whatever this graph calls the default, so the case names no device of
        // the test server's own.
        val mixer = checkNotNull(mixer)
        val device = checkNotNull(checkNotNull(backend).defaultDevice()) {
            "the graph reported no default playback device"
        }
        val before = checkNotNull(device.volume) { "the graph did not report the device's volume" }
        eventually("the device to become settable") { mixer.setDeviceVolume(device.id, 0.2f).takeIf { it } }
        val quiet = checkNotNull(checkNotNull(backend).devices().firstOrNull { it.id == device.id })
        withClue("the device setter answered before the graph did") {
            (abs(checkNotNull(quiet.volume) - 0.2f) < TOLERANCE) shouldBe true
        }
        mixer.setDeviceMuted(device.id, true) shouldBe true

        // The obligation is stronger here than on a row: a device volume left
        // low is the one a person is most likely to blame on their hardware.
        mixer.restoreAll()
        val after = eventually("the device volume to come back") {
            checkNotNull(backend).devices().firstOrNull {
                it.id == device.id && it.volume != null && abs(it.volume!! - before) < TOLERANCE
            }
        }
        withClue("the device mute was not put back") { after.muted shouldBe device.muted }
    }

    @Test
    fun `a device this process made goes when the mixer closes`() {
        // The other half of the obligation the interface states. restoreAll is
        // the half a consumer calls and is tested below. This is the half that
        // runs when a consumer calls nothing at all.
        val mixer = checkNotNull(mixer)
        val name = "libsound_pw_closing_${ProcessHandle.current().pid()}"
        checkNotNull(mixer.createVirtualSink(name))
        eventually("the device to appear") {
            checkNotNull(backend).devices().any { it.id.value == name }.takeIf { there -> there }
        }
        mixer.close()
        eventually("the device to go with the mixer") {
            checkNotNull(backend).devices().none { it.id.value == name }.takeIf { gone -> gone }
        }
    }

    @Test
    fun `a device this process made and forgot is taken back by restore`() {
        val mixer = checkNotNull(mixer)
        val name = "libsound_pw_orphan_${ProcessHandle.current().pid()}"
        checkNotNull(mixer.createVirtualSink(name))
        // The obligation this call is documented under. A device left behind is
        // one somebody finds in their settings and cannot account for.
        mixer.restoreAll()
        eventually("the forgotten device to be taken back") {
            checkNotNull(backend).devices().none { it.id.value == name }.takeIf { it }
        }
    }

    @Test
    fun `an id naming nothing is refused rather than answered for`() {
        val mixer = checkNotNull(mixer)
        // An id this mixer can parse that no node answers to, and one it
        // cannot parse at all. Both are refused before anything goes out, which
        // is a different mechanism from a write the graph turns down: there is
        // nothing here to ask about.
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

    /**
     * How many meter readers are running.
     *
     * The observable half of a meter that was not taken down. Its stream goes
     * when the loop it was created on is destroyed, whether or not anything
     * closed it, so the graph cannot tell a released meter from an abandoned
     * one. The thread can: it is parked in a read that nothing will answer.
     */
    private fun meterThreads(): Int =
        Thread.getAllStackTraces().keys.count { it.name == "libsound-pipewire-meter" && it.isAlive }

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
