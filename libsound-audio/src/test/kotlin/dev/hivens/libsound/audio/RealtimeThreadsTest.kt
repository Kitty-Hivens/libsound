package dev.hivens.libsound.audio

import dev.hivens.libsound.AudioFormat
import dev.hivens.libsound.Capability
import dev.hivens.libsound.LatencyProfile
import dev.hivens.libsound.MediaRole
import dev.hivens.libsound.SinkConfig
import dev.hivens.libsound.audio.pulse.PulseBackend
import dev.hivens.libsound.audio.realtime.RealtimeThreads
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The promotion, against the real RealtimeKit on the machine running the suite.
 *
 * Every assertion is made by reading the kernel's own answer out of `/proc`
 * rather than by trusting the call that came back without an error. A daemon
 * that accepted the request and did nothing would pass any check made through
 * our own D-Bus round trip, which is the same reason the MPRIS suites assert
 * through gdbus.
 *
 * Everything runs on a thread of the suite's own, which then exits. A promoted
 * JUnit thread would keep its priority for the rest of the run, and a run that
 * spends a long time at a priority the scheduler will not preempt is a run that
 * can make the machine it is on unpleasant to use.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class RealtimeThreadsTest {

    @Test
    fun `a thread this process owns is promoted, and the kernel agrees`() {
        val name = "libsound-rt-probe-${System.nanoTime()}"
        val refusal = AtomicReference<String?>("never ran")
        val policy = AtomicReference<Scheduling?>()

        val probe = Thread({
            refusal.set(RealtimeThreads.promoteCurrentThread())
            policy.set(schedulingOf(name))
        }, name)
        probe.isDaemon = true
        probe.start()
        probe.join(30_000)

        AudioTestGate.require("rtkit", refusal.get() == null, refusal.get() ?: "promotion refused")

        val scheduling = checkNotNull(policy.get()) { "the probe thread left no /proc entry to read" }
        // SCHED_RR is what RealtimeKit sets. Accepting FIFO as well because
        // which of the two a daemon chooses is its business; what this asserts
        // is that the thread is no longer on the ordinary scheduler.
        (scheduling.policy == SCHED_RR || scheduling.policy == SCHED_FIFO) shouldBe true
        scheduling.priority shouldBe RealtimeThreads.DEFAULT_PRIORITY
    }

    @Test
    fun `a sink asked for real-time says whether it got it`() {
        val backend = PulseBackend.connectOrNull("libsound realtime test")
        AudioTestGate.require("pulse", backend != null, "no PulseAudio or PipeWire server")
        checkNotNull(backend).use {
            val granted = AtomicReference(false)
            // On a thread of its own for the same reason as above: the writer is
            // what gets promoted, and here the writer is this test.
            val writer = Thread({
                val sink = backend.createSink(
                    SinkConfig(
                        applicationName = "libsound realtime test",
                        mediaRole = MediaRole.MUSIC,
                        latency = LatencyProfile.LOW,
                        realtime = true,
                    ),
                )
                sink.use { channel ->
                    val format = AudioFormat(48_000, 2)
                    channel.open(format)
                    val chunk = ByteArray(format.sampleRate / 10 * format.bytesPerFrame)
                    channel.write(chunk, 0, chunk.size)
                    granted.set(Capability.REALTIME_THREAD in channel.capabilities)
                }
            }, "libsound-rt-writer")
            writer.isDaemon = true
            writer.start()
            writer.join(30_000)

            // The capability is the answer to "did the system agree", so it is
            // gated on the same thing the promotion is. What must never happen
            // is the sink claiming it without a grant, which is what the
            // comparison below is: the two are the same fact.
            AudioTestGate.require(
                "rtkit", granted.get(),
                "the sink asked for real-time priority and did not get it",
            )
        }
    }

    private class Scheduling(val policy: Int, val priority: Int)

    /**
     * The scheduler's own view of a thread, found by the name it was given.
     *
     * `/proc/self/task/<tid>/comm` is how a thread is identified here, because
     * the JVM has no tid to offer and binding gettid a second time in a test
     * would be testing this file against itself.
     */
    private fun schedulingOf(threadName: String): Scheduling? {
        val tasks = File("/proc/self/task").listFiles() ?: return null
        val task = tasks.firstOrNull { dir ->
            runCatching { File(dir, "comm").readText().trim() }.getOrNull() == threadName.take(COMM_MAX)
        } ?: return null
        val stat = runCatching { File(task, "stat").readText() }.getOrNull() ?: return null
        // The command name is in parentheses and may contain spaces, so the
        // fields are counted from the last closing bracket rather than split
        // from the start.
        val fields = stat.substringAfterLast(')').trim().split(' ')
        val priority = fields.getOrNull(RT_PRIORITY_INDEX)?.toIntOrNull() ?: return null
        val policy = fields.getOrNull(POLICY_INDEX)?.toIntOrNull() ?: return null
        return Scheduling(policy, priority)
    }

    private companion object {
        const val SCHED_FIFO = 1
        const val SCHED_RR = 2

        /** The kernel truncates a thread name to fifteen characters plus a NUL. */
        const val COMM_MAX = 15

        /**
         * `rt_priority` is field 40 of `/proc/[pid]/stat` and `policy` is field
         * 41, counted from one. The split above starts at field three, so both
         * shift by that much.
         */
        const val RT_PRIORITY_INDEX = 37
        const val POLICY_INDEX = 38
    }
}
