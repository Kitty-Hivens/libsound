package dev.hivens.libsound

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class LatencyProfileTest {

    @Test
    fun `the default is BALANCED, which is not the old default`() {
        // The behaviour change, asserted rather than only written down. 200 ms
        // was measured for the JavaSound fallback and then applied to every
        // backend; a library whose first purpose is latency cannot keep it as
        // the default, and a consumer that wants it asks for RELAXED and gets
        // exactly the old number.
        SinkConfig(applicationName = "Example").latency shouldBe LatencyProfile.BALANCED
        SinkConfig(applicationName = "Example").targetNanos shouldBe 40_000_000L
        LatencyProfile.RELAXED.targetNanos shouldBe 200_000_000L
    }

    @Test
    fun `bufferNanos overrides the profile, for a caller that knows its number`() {
        val config = SinkConfig(
            applicationName = "Example",
            latency = LatencyProfile.LOWEST,
            bufferNanos = 123_000_000L,
        )
        config.targetNanos shouldBe 123_000_000L
    }

    @Test
    fun `a source asks the same way a sink does`() {
        SourceConfig(applicationName = "Example").targetNanos shouldBe LatencyProfile.BALANCED.targetNanos
        SourceConfig(applicationName = "Example", latency = LatencyProfile.LOW).targetNanos shouldBe 10_000_000L
    }

    @Test
    fun `the profiles are ordered, so a settings screen can list them as a scale`() {
        val descending = LatencyProfile.entries.map { it.targetNanos }
        descending shouldBe descending.sortedDescending()
    }

    @Test
    fun `realtime is off unless it is asked for`() {
        // Taking a thread that will not be preempted is not something a library
        // does on behalf of a process that did not ask.
        SinkConfig(applicationName = "Example").realtime shouldBe false
        SourceConfig(applicationName = "Example").realtime shouldBe false
    }
}
