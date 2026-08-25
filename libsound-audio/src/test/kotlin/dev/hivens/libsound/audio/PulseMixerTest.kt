package dev.hivens.libsound.audio

import dev.hivens.libsound.AudioFormat
import dev.hivens.libsound.VolumeMixer
import dev.hivens.libsound.AudioSink
import dev.hivens.libsound.Capability
import dev.hivens.libsound.MediaRole
import dev.hivens.libsound.SinkConfig
import dev.hivens.libsound.StreamEvent
import dev.hivens.libsound.StreamId
import dev.hivens.libsound.audio.pulse.PulseBackend
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

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
    private var mixer: VolumeMixer? = null

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
        mixer = VolumeMixers.open("libsound mixer test")
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
        stream.active shouldBe true
        // The device it is playing to, resolved from the sink index to a name a
        // consumer could store and select again later.
        (stream.device != null) shouldBe true
    }

    @Test
    fun `a stream this process opened is marked as ours`() {
        // From the pid libpulse stamps on every stream, so it holds for streams
        // this process opened by other means too -- and needs no bookkeeping
        // between the sink and the mixer, which do not know each other.
        checkNotNull(ours()).isOurs shouldBe true
        // And is not simply true for everything: the mixer's own connection
        // publishes no stream, so anything else on the machine is somebody's.
        mixer!!.streams().count { it.isOurs } shouldBe
            mixer!!.streams().count { it.applicationName?.startsWith(appName) == true }
    }

    @Test
    fun `the first enumeration already knows the device names`() {
        // Priming is waited on rather than fired and forgotten. Fired, the first
        // call reports a null device for every row, which a consumer cannot tell
        // apart from a backend that does not know how to answer.
        val fresh = checkNotNull(VolumeMixers.open("libsound mixer prime test"))
        try {
            fresh.streams().none { it.device == null } shouldBe true
        } finally {
            fresh.close()
        }
    }

    @Test
    fun `volume set on a mono stream is what the server then reports`() {
        // A cvolume carries its own channel count and the server matches it
        // against the stream's. Half the streams on a desktop are mono, and a
        // fixed two is a request a server is entitled to reject.
        val monoName = "$appName mono"
        val mono = backend!!.createSink(SinkConfig(applicationName = monoName))
            .also { it.open(AudioFormat(48_000, 1)) }
        try {
            val silence = ByteArray(48_000 / 4)
            mono.write(silence, 0, silence.size)
            Thread.sleep(200)
            val stream = checkNotNull(mixer!!.streams().firstOrNull { it.applicationName == monoName })
            mixer!!.setVolume(stream.id, 0.4f) shouldBe true
            Thread.sleep(200)
            val after = checkNotNull(mixer!!.streams().firstOrNull { it.applicationName == monoName })
            (abs(after.volume - 0.4f) < 0.05f) shouldBe true
        } finally {
            runCatching { mono.close() }
        }
    }

    @Test
    fun `setting a stream that is not there answers false`() {
        // What the server said, not that the request was sent: libpulse hands
        // back a live operation for a stream that has already gone and reports
        // the refusal a moment later.
        mixer!!.setVolume(StreamId("999999"), 0.5f) shouldBe false
        mixer!!.setMuted(StreamId("999999"), true) shouldBe false
    }

    @Test
    fun `a mute this process set is put back`() {
        // The same obligation as volume, and separately recorded: restoring a
        // volume must not undo a mute the user set in the meantime.
        checkNotNull(ours()).muted shouldBe false
        mixer!!.setMuted(checkNotNull(ours()).id, true) shouldBe true
        Thread.sleep(200)
        checkNotNull(ours()).muted shouldBe true

        mixer!!.restoreAll()
        Thread.sleep(300)
        checkNotNull(ours()).muted shouldBe false
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
    fun `concurrent enumeration does not shred the answer`() {
        // The mixer contends with itself by construction: its own subscription
        // dispatcher enumerates on every stream event, so a consumer listing
        // streams is racing a thread the library started. Duplicate ids are the
        // signature -- two collections landing in one buffer -- and unlike a
        // changed stream set they cannot happen for an honest reason.
        val pool = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        try {
            val futures = (1..4).map {
                pool.submit<List<List<String>>> {
                    start.await()
                    (1..15).map { mixer!!.streams().map { stream -> stream.id.value } }
                }
            }
            start.countDown()
            val results = futures.flatMap { it.get(60, TimeUnit.SECONDS) }
            results.none { it.size != it.distinct().size } shouldBe true
            // Ours is alive for the whole run, so every single listing must have
            // it -- a truncated collection is what its absence would mean.
            results.all { ids -> ids.contains(checkNotNull(ours()).id.value) } shouldBe true
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `a meter follows a stream that is actually playing`() {
        // Silence would prove nothing: a meter that always answers zero passes
        // any assertion about a stopped stream. So this writes a tone and asks
        // for a peak above zero, which only real audio produces.
        val toneName = "$appName tone"
        val tone = backend!!.createSink(SinkConfig(applicationName = toneName))
            .also { it.open(format) }
        try {
            val seen = CopyOnWriteArrayList<Float>()
            val loud = CountDownLatch(1)
            val samples = ByteArray(format.sampleRate * format.bytesPerFrame)
            for (frame in 0 until format.sampleRate) {
                val value = (sin(2.0 * PI * 440.0 * frame / format.sampleRate) * 0.5 * Short.MAX_VALUE).toInt()
                val at = frame * format.bytesPerFrame
                repeat(format.channels) { channel ->
                    samples[at + channel * 2] = (value and 0xFF).toByte()
                    samples[at + channel * 2 + 1] = ((value shr 8) and 0xFF).toByte()
                }
            }
            tone.write(samples, 0, samples.size)
            Thread.sleep(200)
            val stream = checkNotNull(mixer!!.streams().firstOrNull { it.applicationName == toneName })

            val cancel = mixer!!.meter(stream.id) { peak ->
                seen += peak
                if (peak > 0.01f) loud.countDown()
            }
            try {
                tone.write(samples, 0, samples.size)
                loud.await(10, TimeUnit.SECONDS) shouldBe true
                seen.all { it in 0f..1f } shouldBe true
            } finally {
                cancel()
            }
        } finally {
            runCatching { tone.close() }
        }
    }

    @Test
    fun `cancelling a meter stops it`() {
        val stream = checkNotNull(ours())
        val after = CopyOnWriteArrayList<Float>()
        val cancel = mixer!!.meter(stream.id) { after += it }
        cancel()
        // Cancelling twice is a consumer mistake that must not be a crash: the
        // second call has nothing to tear down and has to notice that itself.
        cancel()
        val settled = after.size
        Thread.sleep(500)
        after.size shouldBe settled
    }

    @Test
    fun `the mixer claims what it can do`() {
        mixer!!.capabilities.allOf(
            Capability.STREAM_ENUMERATION,
            Capability.STREAM_CONTROL,
            Capability.STREAM_ROUTING,
            Capability.STREAM_METERING,
        ) shouldBe true
    }
}
