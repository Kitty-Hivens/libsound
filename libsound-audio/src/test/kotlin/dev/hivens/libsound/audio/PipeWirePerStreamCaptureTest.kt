package dev.hivens.libsound.audio

import dev.hivens.libsound.AudioBackend
import dev.hivens.libsound.AudioFormat
import dev.hivens.libsound.AudioSink
import dev.hivens.libsound.Capability
import dev.hivens.libsound.DeviceId
import dev.hivens.libsound.SinkConfig
import dev.hivens.libsound.SourceConfig
import dev.hivens.libsound.StreamId
import dev.hivens.libsound.VolumeMixer
import dev.hivens.libsound.audio.pipewire.PipeWireBackend
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Recording one application on the graph, and proving it is that one.
 *
 * The libpulse side has a call for this and this side has a property: a capture
 * stream naming another node in `target.object` is linked to that node's output
 * rather than to a device. Which means the mistake available here is different
 * and worse. A call that fails, fails; a target the graph does not recognise is
 * a stream that connects to whatever was going anyway, and a caller that asked
 * to record one application is handed a microphone in a room instead.
 *
 * So the test is built to tell those two apart rather than to show that audio
 * arrived. Two applications play into a null sink of this suite's own, one loud
 * and one silent, and the recording is aimed at each in turn. It is the pair
 * that discriminates, and each half catches a different way of being wrong.
 *
 * Aimed at the loud one it has to carry the tone. Measured: with the target
 * property removed and everything else left alone, the capture connects to
 * whatever the graph calls the default input, hears nothing at all, and this
 * half fails.
 *
 * Aimed at the silent one it has to be silent. That is the half that catches a
 * capture which reached the right sink but recorded all of it, because the sink
 * is mixing the loud one too.
 *
 * Named in `LIBSOUND_REQUIRE` separately for the reason every capture suite
 * here is: on a machine that is not the isolated server, a fallback is a
 * microphone.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class PipeWirePerStreamCaptureTest {

    private val appName = "libsound pipewire capture ${ProcessHandle.current().pid()}"
    private val format = AudioFormat(48_000, 2)

    private var backend: AudioBackend? = null
    private var mixer: VolumeMixer? = null

    @BeforeEach
    fun open() {
        backend = PipeWireBackend.connectOrNull(appName)
        AudioTestGate.require("pipewire", backend != null, "no PipeWire graph reachable")
        Assumptions.assumeTrue(
            AudioTestGate.isRequired("pipewire-capture"),
            "a fallback here would record the default input; name pipewire-capture to run it",
        )
        // The mixer is the pulse protocol's on every Linux machine, which is
        // also where the id this feature takes comes from. That is the point
        // rather than an inconvenience: a consumer holds one id and this has to
        // resolve it whichever backend is playing.
        mixer = VolumeMixers.open(appName)
        AudioTestGate.require("pipewire", mixer != null, "no mixer available")
    }

    @AfterEach
    fun close() {
        mixer?.let { runCatching { it.close() } }
        backend?.let { runCatching { it.close() } }
        mixer = null
        backend = null
    }

    @Test
    fun `the recording follows the stream it named and not the device behind it`() {
        val backend = checkNotNull(backend)
        val mixer = checkNotNull(mixer)
        (Capability.PER_STREAM_CAPTURE in backend.capabilities) shouldBe true

        val quiet = checkNotNull(
            mixer.createVirtualSink("libsound_pw_capture_${ProcessHandle.current().pid()}"),
        ) { "the server refused a null sink to play into" }

        val loudName = "$appName loud"
        val silentName = "$appName silent"
        val players = listOf(
            play(backend, quiet, loudName, tone(LOUD_AMPLITUDE)),
            play(backend, quiet, silentName, tone(0.0)),
        )
        try {
            val loudId = idOf(mixer, loudName)
            val silentId = idOf(mixer, silentName)

            // Both streams are on the same sink, so a capture that reached
            // the sink rather than the stream would carry the tone.
            withClue("aimed at a silent application and heard the loud one beside it") {
                (loudestFrom(silentId) < SILENCE_THRESHOLD) shouldBe true
            }
            // And the other way, which is what a capture that ignored the
            // target fails: it lands on the default input and hears nothing.
            withClue("aimed at the loud application and heard nothing") {
                (loudestFrom(loudId) > SILENCE_THRESHOLD) shouldBe true
            }
        } finally {
            players.forEach { it.stop() }
        }
    }

    @Test
    fun `a stream that is not on the graph is refused rather than quietly replaced`() {
        // The failure this whole design is arranged around. A source that
        // connected to something else and handed back audio would be
        // indistinguishable from one that worked, and what it handed back would
        // be whatever the machine was listening to.
        val backend = checkNotNull(backend)
        val source = backend.createSource(
            SourceConfig(applicationName = appName, captureStream = StreamId("sink-input:999999999")),
        )
        runCatching { source.use { it.open(format) } }.isFailure shouldBe true

        // An id from the capture half of the mixer names something with no
        // output to record, which is a different mistake with the same answer.
        val wrongWay = backend.createSource(
            SourceConfig(applicationName = appName, captureStream = StreamId("source-output:1")),
        )
        runCatching { wrongWay.use { it.open(format) } }.isFailure shouldBe true
    }

    // -- the machinery --------------------------------------------------------

    private fun idOf(mixer: VolumeMixer, name: String): StreamId = eventually(name) {
        mixer.streams().firstOrNull { it.applicationName == name }?.id
    }

    private fun loudestFrom(id: StreamId): Int =
        checkNotNull(backend).createSource(
            SourceConfig(applicationName = "$appName reader", captureStream = id),
        ).use { source ->
            source.open(format)
            val heard = ByteArray(format.sampleRate / 2 * format.bytesPerFrame)
            source.read(heard, 0, heard.size)
            (heard.indices step 2).maxOf { at ->
                abs(((heard[at + 1].toInt() shl 8) or (heard[at].toInt() and 0xFF)).toShort().toInt())
            }
        }

    /** An application on the graph, playing [pcm] round and round until stopped. */
    private fun play(backend: AudioBackend, device: DeviceId, name: String, pcm: ByteArray): Player {
        val sink = backend.createSink(SinkConfig(applicationName = name, device = device))
        val running = AtomicBoolean(true)
        val thread = Thread(
            {
                runCatching {
                    sink.open(format)
                    while (running.get()) sink.write(pcm, 0, pcm.size)
                }
            },
            "libsound-pw-capture-$name",
        )
        thread.isDaemon = true
        thread.start()
        return Player(sink, running, thread)
    }

    private class Player(
        private val sink: AudioSink,
        private val running: AtomicBoolean,
        private val thread: Thread,
    ) {
        fun stop() {
            running.set(false)
            runCatching { sink.close() }
            thread.join(5_000)
        }
    }

    private fun tone(amplitude: Double): ByteArray {
        val frames = format.sampleRate / 4
        val pcm = ByteArray(frames * format.bytesPerFrame)
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
        /** Loud enough that a device-wide capture could not be mistaken for silence. */
        const val LOUD_AMPLITUDE = 0.5

        /** Well above what a null sink's monitor carries on its own, which is nothing. */
        const val SILENCE_THRESHOLD = 1_000

        const val APPEAR_TIMEOUT_NANOS = 10_000_000_000L
    }
}
