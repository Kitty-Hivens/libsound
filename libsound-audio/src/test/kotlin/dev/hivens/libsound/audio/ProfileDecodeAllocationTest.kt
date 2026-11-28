package dev.hivens.libsound.audio

import dev.hivens.libsound.audio.pipewire.MissedCycles
import dev.hivens.libsound.audio.pipewire.SpaPodReader
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import java.lang.foreign.MemorySegment
import java.lang.management.ManagementFactory
import com.sun.management.ThreadMXBean as AllocatingThreadMXBean

/**
 * What decoding one profile event allocates, counted rather than timed.
 *
 * The graph sends this event once per cycle, hundreds of times a second at a
 * short quantum, and a sink's process callback shares the heap it lands on: a
 * collection there is audible. The general walk built a list of boxed fields
 * for every node in every cycle, so the garbage grew with the size of the graph.
 *
 * A count is the right instrument where a timing is not. It is the same number
 * on any machine, so it can fail a build, and what it fails on is the property
 * that matters: whether the cost of a cycle grows with the nodes in it.
 */
class ProfileDecodeAllocationTest {

    @Test
    fun `decoding a profile event allocates nothing per node`() {
        val threads = ManagementFactory.getThreadMXBean() as? AllocatingThreadMXBean
        Assumptions.assumeTrue(
            threads != null && threads.isThreadAllocatedMemorySupported,
            "this JVM does not count what a thread allocates",
        )
        val counting = checkNotNull(threads).also { it.isThreadAllocatedMemoryEnabled = true }

        val event = MemorySegment.ofArray(ProfilerFixtures.eventWith(FOLLOWERS))
        // The whole of what a cycle does: the walk, and each count written into
        // the counters the registry keeps. Counting the walk alone missed the
        // half that allocated, because a boxed id per lookup is free on a JVM
        // that removes the box and sixteen bytes a node on one that does not.
        val missed = MissedCycles()
        val sink = SpaPodReader.FollowerSink(missed::record)

        // Warmed first, so what is counted is the compiled walk a running graph
        // would be served by rather than the interpreter's first pass over it.
        repeat(WARMUP) { SpaPodReader.followers(event, sink) }
        val before = counting.currentThreadAllocatedBytes
        repeat(MEASURED) { SpaPodReader.followers(event, sink) }
        val perEvent = (counting.currentThreadAllocatedBytes - before) / MEASURED

        missed.get(ProfilerFixtures.FIRST_NODE + FOLLOWERS - 1) shouldBe (FOLLOWERS - 1).toLong()
        withClue("$perEvent bytes per event of $FOLLOWERS nodes") {
            (perEvent < PER_EVENT_CEILING) shouldBe true
        }
    }

    private companion object {
        const val FOLLOWERS = 64
        const val WARMUP = 20_000
        const val MEASURED = 20_000

        /**
         * Less than a byte per node. The general walk spends hundreds per node,
         * so anything that allocates per block at all lands far above this.
         */
        const val PER_EVENT_CEILING = 64L
    }
}
