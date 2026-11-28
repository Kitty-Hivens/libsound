package dev.hivens.libsound.audio

import dev.hivens.libsound.audio.pipewire.MissedCycles
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MissedCyclesTest {

    @Test
    fun `a count is read back for the node it was recorded for`() {
        val missed = MissedCycles()
        missed.get(7) shouldBe null
        missed.record(7, 3)
        missed.get(7) shouldBe 3L
        missed.record(7, 4)
        missed.get(7) shouldBe 4L
    }

    @Test
    fun `the graph's unsigned count stays unsigned`() {
        val missed = MissedCycles()
        missed.record(1, -1)
        missed.get(1) shouldBe 0xFFFF_FFFFL
    }

    @Test
    fun `every node survives the table growing and its neighbours being removed`() {
        // Enough nodes to grow the table several times, and removals scattered
        // through it, which is what leaves holes inside a probe run. A delete
        // that left a hole would hide the keys that had probed past it.
        val missed = MissedCycles()
        val nodes = (0 until 2_000).map { it * 37 }
        nodes.forEachIndexed { index, node -> missed.record(node, index) }
        val removed = nodes.filterIndexed { index, _ -> index % 3 == 0 }.toSet()
        removed.forEach { missed.remove(it) }
        nodes.forEachIndexed { index, node ->
            missed.get(node) shouldBe if (node in removed) null else index.toLong()
        }
        // And a removed id taken by a new node starts from what that node says.
        missed.record(nodes[0], 99)
        missed.get(nodes[0]) shouldBe 99L
    }

    @Test
    fun `a write after a removal lands in the index the writer reads`() {
        // The index and the shared map hold the same counters. A record after a
        // remove has to reach both, or the next cycle would write into a counter
        // no reader can see.
        val missed = MissedCycles()
        missed.record(5, 1)
        missed.remove(5)
        missed.record(5, 2)
        missed.record(5, 3)
        missed.get(5) shouldBe 3L
    }

    @Test
    fun `clear forgets everything`() {
        val missed = MissedCycles()
        (0 until 100).forEach { missed.record(it, it) }
        missed.clear()
        (0 until 100).forEach { missed.get(it) shouldBe null }
        missed.record(3, 1)
        missed.get(3) shouldBe 1L
    }
}
