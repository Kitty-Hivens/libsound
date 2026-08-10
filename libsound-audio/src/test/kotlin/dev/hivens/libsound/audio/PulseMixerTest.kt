package dev.hivens.libsound.audio

import dev.hivens.libsound.AudioFormat
import dev.hivens.libsound.AudioMixer
import dev.hivens.libsound.AudioSink
import dev.hivens.libsound.Capability
import dev.hivens.libsound.MediaRole
import dev.hivens.libsound.SinkConfig
import dev.hivens.libsound.StreamEvent
import dev.hivens.libsound.audio.pulse.PulseBackend
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * The mixer against a live server, and only ever against our own stream.
 *
 * Somebody else's audio is exactly what this can reach, which is the point of
 * it and also the reason the tests do not touch any: a suite that lowered the
 * volume of whatever the developer had playing would be correct and
 * unforgivable.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class PulseMixerTest {

    private val appName = "libsound mixer test ${ProcessHandle.current().pid()}"
    private val format = AudioFormat(48_000, 2)

    private var backend: dev.hivens.libsound.AudioBackend? = null
    private var sink: AudioSink? = null
    private var mixer: AudioMixer? = null

    @BeforeEach
    fun open() {
        backend = PulseBackend.connectOrNull(appName)
        AudioTestGate.require("pulse", backend != null, "no PulseAudio or PipeWire server")
        sink = backend!!.createSink(
            SinkConfig(applicationName = appName, mediaRole = MediaRole.MUSIC),
        ).also { it.open(format) }
        // A stream only exists on the server once something has been written.
        val silence = ByteArray(format.sampleRate / 4 * format.bytesPerFrame)
        sink!!.write(silence, 0, silence.size)
        mixer = AudioMixers.open("libsound mixer test")
        AudioTestGate.require("pulse", mixer != null, "no mixer available")
    }

    @AfterEach
    fun close() {
        mixer?.let { runCatching { it.close() } }
        sink?.let { runCatching { it.close() } }
        backend?.let { runCatching { it.close() } }
        mixer = null; sink = null; backend = null
    }

    private fun ours() = mixer!!.streams().firstOrNull { it.applicationName == appName }

    @Test
    fun `the mixer sees a stream with the identity its owner gave it`() {
        val stream = checkNotNull(ours()) { "our own stream should be listed" }
        stream.mediaRole shouldBe MediaRole.MUSIC
        stream.volume shouldBeGreaterThan 0f
        // The device it is playing to, resolved from the sink index to a name a
        // consumer could store and select again later.
        (stream.device != null) shouldBe true
    }

    @Test
    fun `volume set on a stream is what the server then reports`() {
        val before = checkNotNull(ours())
        mixer!!.setVolume(before.id, 0.4f) shouldBe true
        Thread.sleep(200)
        val after = checkNotNull(ours())
        (abs(after.volume - 0.4f) < 0.05f) shouldBe true
    }

    @Test
    fun `mute is set and reported`() {
        val stream = checkNotNull(ours())
        mixer!!.setMuted(stream.id, true) shouldBe true
        Thread.sleep(200)
        checkNotNull(ours()).muted shouldBe true
        mixer!!.setMuted(stream.id, false) shouldBe true
        Thread.sleep(200)
        checkNotNull(ours()).muted shouldBe false
    }

    @Test
    fun `what the mixer changed it puts back`() {
        // The obligation that comes with writing state a sound server remembers:
        // a process that lowers something and walks away leaves a user with
        // quiet audio and nothing to point at.
        val original = checkNotNull(ours()).volume
        mixer!!.setVolume(checkNotNull(ours()).id, 0.2f) shouldBe true
        Thread.sleep(200)
        (abs(checkNotNull(ours()).volume - original) > 0.1f) shouldBe true

        mixer!!.restoreAll()
        Thread.sleep(300)
        (abs(checkNotNull(ours()).volume - original) < 0.05f) shouldBe true
    }

    @Test
    fun `a new stream reaches a subscriber`() {
        val appeared = CountDownLatch(1)
        mixer!!.onStreamsChanged { if (it is StreamEvent.Appeared || it is StreamEvent.Changed) appeared.countDown() }
        val second = backend!!.createSink(SinkConfig(applicationName = "$appName second")).also { it.open(format) }
        try {
            val silence = ByteArray(format.sampleRate / 4 * format.bytesPerFrame)
            second.write(silence, 0, silence.size)
            appeared.await(10, TimeUnit.SECONDS) shouldBe true
        } finally {
            runCatching { second.close() }
        }
    }

    @Test
    fun `the mixer claims what it can do`() {
        mixer!!.capabilities.allOf(
            Capability.STREAM_ENUMERATION,
            Capability.STREAM_CONTROL,
            Capability.STREAM_ROUTING,
        ) shouldBe true
    }
}
