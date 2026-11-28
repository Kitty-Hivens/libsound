package dev.hivens.libsound.audio

import dev.hivens.libsound.audio.pipewire.MissedCycles
import dev.hivens.libsound.audio.pipewire.SpaAbi
import dev.hivens.libsound.audio.pipewire.SpaPodReader
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.File
import java.lang.foreign.MemorySegment
import java.lang.management.ManagementFactory
import java.util.concurrent.ConcurrentHashMap
import com.sun.management.ThreadMXBean as AllocatingThreadMXBean

/**
 * What a profile event costs to take in, old walk against new, timed.
 *
 * Not a test and never a gate. A timing is a property of the machine it ran on,
 * so this runs only where `LIBSOUND_BENCH=1` is set, which is a CI job of its own
 * that does not fail the build. The gate is [ProfileDecodeAllocationTest], which
 * counts and therefore means the same thing everywhere. This is the number that
 * goes beside it in a commit.
 *
 * Both paths run on the same JVM on the same runner, interleaved round by round,
 * so whatever the host is doing to one it is doing to the other and the ratio
 * is the honest part of the answer. The old path is what `onProfile` did before
 * the follower walk existed, reproduced here line for line: the general walk,
 * then a boxed count put into a map per node. The new one is what it does now.
 *
 * Lives beside the tests rather than in `tools/` with the other stands, because
 * the reader is internal to this module and only its own sources can reach it.
 *
 * What it showed when it was written, on the CI row's JDK 22:
 *
 *     followers   old ns   new ns   old bytes   new bytes
 *     2              486       93         520           0
 *     16            1935      446        2832           0
 *     64            4423     1738       11192           0
 *
 * Its first run is the reason the last column is zero. The new walk first wrote
 * into a map keyed by a boxed id and measured 32, 256 and 1024 bytes, which the
 * JVM the change was made on had hidden by removing the box.
 *
 *   LIBSOUND_BENCH=1 ./gradlew :libsound-audio:test --tests '*ProfileDecodeBench' --rerun
 *
 * The report lands in `libsound-audio/build/bench/profile-decode.txt`.
 */
@EnabledIfEnvironmentVariable(named = "LIBSOUND_BENCH", matches = "1")
class ProfileDecodeBench {

    @Test
    fun `time the old walk against the new one`() {
        val threads = ManagementFactory.getThreadMXBean() as? AllocatingThreadMXBean
        threads?.isThreadAllocatedMemoryEnabled = true
        val report = StringBuilder()
        report.appendLine("profile event decode, ns and bytes per event, median of $ROUNDS interleaved rounds")
        report.appendLine("java ${System.getProperty("java.vm.version")} on ${System.getProperty("os.arch")}")
        report.appendLine(String.format("%-10s %14s %14s %14s %14s", "followers", "old ns", "new ns", "old bytes", "new bytes"))

        for (followers in FOLLOWER_COUNTS) {
            val event = MemorySegment.ofArray(ProfilerFixtures.eventWith(followers))
            val old = OldPath()
            val new = NewPath()
            repeat(WARMUP) {
                old.take(event)
                new.take(event)
            }
            val oldNanos = LongArray(ROUNDS)
            val newNanos = LongArray(ROUNDS)
            val oldBytes = LongArray(ROUNDS)
            val newBytes = LongArray(ROUNDS)
            for (round in 0 until ROUNDS) {
                // Alternating which goes first, so neither always meets a cold
                // cache or the tail of the other's garbage.
                if (round % 2 == 0) {
                    measure(threads, event, old::take).let { oldNanos[round] = it.first; oldBytes[round] = it.second }
                    measure(threads, event, new::take).let { newNanos[round] = it.first; newBytes[round] = it.second }
                } else {
                    measure(threads, event, new::take).let { newNanos[round] = it.first; newBytes[round] = it.second }
                    measure(threads, event, old::take).let { oldNanos[round] = it.first; oldBytes[round] = it.second }
                }
            }
            report.appendLine(
                String.format(
                    "%-10d %14d %14d %14d %14d",
                    followers, median(oldNanos), median(newNanos), median(oldBytes), median(newBytes),
                ),
            )
            // Read, so neither path's work can be dropped as unused.
            val last = ProfilerFixtures.FIRST_NODE + followers - 1
            check(old.missed[last] == new.missed.get(last)) { "the two paths disagree about $followers followers" }
        }

        print(report)
        File("build/bench").mkdirs()
        File("build/bench/profile-decode.txt").writeText(report.toString())
    }

    /** Nanoseconds and bytes per event over one batch. */
    private fun measure(threads: AllocatingThreadMXBean?, event: MemorySegment, take: (MemorySegment) -> Unit): Pair<Long, Long> {
        val bytesBefore = threads?.currentThreadAllocatedBytes ?: 0L
        val started = System.nanoTime()
        repeat(BATCH) { take(event) }
        val nanos = (System.nanoTime() - started) / BATCH
        val bytes = ((threads?.currentThreadAllocatedBytes ?: 0L) - bytesBefore) / BATCH
        return nanos to bytes
    }

    private fun median(values: LongArray): Long = values.sorted()[values.size / 2]

    /** What `onProfile` did before: the general walk, then a boxed count per node. */
    private class OldPath {
        val missed = ConcurrentHashMap<Int, Long>()

        fun take(pod: MemorySegment) {
            SpaPodReader.structuredEntries(pod).forEach { (key, value) ->
                if (key != SpaAbi.PROFILER_FOLLOWER_BLOCK) return@forEach
                val block = value as? List<*> ?: return@forEach
                val node = block.getOrNull(SpaAbi.PROFILER_BLOCK_ID) as? Int ?: return@forEach
                val count = block.getOrNull(SpaAbi.PROFILER_BLOCK_XRUNS) as? Int ?: return@forEach
                missed[node] = count.toLong() and UNSIGNED_INT
            }
        }
    }

    /** What it does now: the follower walk, into the registry's own counters. */
    private class NewPath {
        val missed = MissedCycles()

        private val sink = SpaPodReader.FollowerSink(missed::record)

        fun take(pod: MemorySegment) {
            SpaPodReader.followers(pod, sink)
        }
    }

    private companion object {
        val FOLLOWER_COUNTS = intArrayOf(2, 16, 64)
        const val WARMUP = 20_000
        const val ROUNDS = 9
        const val BATCH = 5_000
        const val UNSIGNED_INT = 0xFFFF_FFFFL
    }
}
