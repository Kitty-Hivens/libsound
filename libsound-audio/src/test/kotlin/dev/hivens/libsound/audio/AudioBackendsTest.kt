package dev.hivens.libsound.audio

import dev.hivens.libsound.AudioFormat
import dev.hivens.libsound.Capability
import dev.hivens.libsound.MediaRole
import dev.hivens.libsound.SinkConfig
import dev.hivens.libsound.audio.wasapi.WasapiBackend
import dev.hivens.libsound.testing.AudioSinkContract
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The selection chain, which the review found had no test at all -- despite
 * being the thing that decides what a settings screen may offer.
 */
class AudioBackendsTest {

    @Test
    fun `a machine that can play audio gets a backend`() {
        val backend = AudioBackends.open("libsound selection test")
        checkNotNull(backend) { "this machine has a sound server and a JVM that can play" }
        backend.use {
            it.name.isNotBlank() shouldBe true
            // Whatever won, the sink it hands out has to be usable: the point of
            // the fallback chain is that a consumer never has to ask which one
            // it got before it can play.
            val sink = it.createSink(
                SinkConfig(applicationName = "libsound selection test", mediaRole = MediaRole.MUSIC),
            )
            sink.use { _ -> sink.isOpen shouldBe false }
        }
    }

    @Test
    fun `a capability the caller needs decides which rung it lands on`() {
        // Two rungs on Linux differ by one thing, and the whole reason this
        // parameter exists is that a consumer which uses that thing should get
        // the rung that has it rather than a null return at the moment it
        // wanted a sound.
        //
        // Asserted against whatever answered rather than by naming a backend:
        // what has to hold is that the backend handed back offers what was
        // asked for, on every machine and whichever rung answered.
        val needed = setOf(Capability.SAMPLE_CACHE)
        val backend = AudioBackends.open("libsound selection test", needed)
        if (backend == null) {
            // A legitimate answer: no backend on this machine has it. What
            // would not be legitimate is one that does not, handed back anyway.
            return
        }
        backend.use {
            (Capability.SAMPLE_CACHE in it.capabilities) shouldBe true
        }
    }

    @Test
    fun `asking for something nothing offers answers null rather than something else`() {
        // The refusal is the point. Falling back to a backend that was
        // explicitly told it would not do is worse than saying so, because the
        // caller named the capability precisely because it cannot adapt.
        //
        // Every capability at once is a set nothing can satisfy: no backend has
        // both the mixer's and the session module's, and macOS has almost none
        // of it at all.
        //
        // Skipped under a pin, because the pin is documented to win: a run
        // comparing two backends must get the one it named, and asserting the
        // refusal here would be asserting against that rule rather than for it.
        Assumptions.assumeTrue(
            System.getProperty("libsound.backend").isNullOrBlank(),
            "a pinned backend is documented to win over a capability request",
        )
        AudioBackends.open("libsound selection test", Capability.entries.toSet()) shouldBe null
    }

    @Test
    fun `an empty request is the plain open`() {
        val plain = AudioBackends.open("libsound selection test")
        val empty = AudioBackends.open("libsound selection test", emptySet())
        checkNotNull(plain).use { first ->
            checkNotNull(empty).use { second ->
                first.name shouldBe second.name
                first.capabilities shouldBe second.capabilities
            }
        }
    }

    @Test
    fun `the backend and its sinks never disagree about the playhead`() {
        // The review found the fallback claiming an empty set while its sinks
        // claimed DEVICE_POSITION. A consumer reads the backend, so the two
        // disagreeing means a feature hidden or offered wrongly.
        val backend = AudioBackends.open("libsound selection test")
        checkNotNull(backend)
        backend.use {
            val sink = it.createSink(SinkConfig(applicationName = "libsound selection test"))
            sink.use { _ ->
                val backendHas = Capability.DEVICE_POSITION in it.capabilities
                val sinkHas = Capability.DEVICE_POSITION in sink.capabilities
                backendHas shouldBe sinkHas
            }
        }
    }

    @Test
    fun `a sink never claims what only a backend can do`() {
        val backend = AudioBackends.open("libsound selection test")
        checkNotNull(backend)
        backend.use {
            val sink = it.createSink(SinkConfig(applicationName = "libsound selection test"))
            sink.use { _ ->
                // A sink has no device list and no way to change its device
                // after creation; claiming either would have a consumer offering
                // a control that reaches nothing.
                (Capability.DEVICE_ENUMERATION in sink.capabilities) shouldBe false
                (Capability.DEVICE_SELECTION in sink.capabilities) shouldBe false
                (Capability.DEVICE_EVENTS in sink.capabilities) shouldBe false
            }
        }
    }
}

/**
 * The contract against WASAPI.
 *
 * Runs only on Windows with a device present, which is neither this machine nor
 * a GitHub runner -- both answer null and the gate turns that into a skip. Named
 * in LIBSOUND_REQUIRE it becomes a failure instead, which is how a real Windows
 * box or a tester's run is told to actually exercise it.
 */
class WasapiSinkContractTest : AudioSinkContract() {

    companion object {
        private var backend: dev.hivens.libsound.AudioBackend? = null

        @JvmStatic
        @BeforeAll
        fun connect() {
            backend = WasapiBackend.connectOrNull()
        }

        @JvmStatic
        @AfterAll
        fun disconnect() {
            backend?.let { runCatching { it.close() } }
            backend = null
        }
    }

    @BeforeEach
    fun gate() {
        AudioTestGate.require("wasapi", backend != null, "no WASAPI endpoint reachable")
    }

    override val format: AudioFormat = AudioFormat(48_000, 2)

    override fun newSink() = checkNotNull(backend).createSink(
        SinkConfig(
            applicationName = "libsound contract suite",
            applicationId = "dev.hivens.libsound.test",
            mediaRole = MediaRole.MUSIC,
        ),
    )
}
