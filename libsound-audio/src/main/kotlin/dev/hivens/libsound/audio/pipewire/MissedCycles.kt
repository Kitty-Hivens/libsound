package dev.hivens.libsound.audio.pipewire

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * How many cycles each node has missed, written once per graph cycle and read
 * whenever a sink is asked.
 *
 * Two structures over one set of counters, because the two sides want different
 * things. The writer is the loop's own thread, hundreds of times a second, and
 * finds a node's counter through an index keyed by the primitive id, so a cycle
 * that reports nodes already known allocates nothing at all. A map keyed by a
 * boxed id was the obvious shape and was measured costing sixteen bytes per node
 * per cycle on JDK 22, which boxes an id past 127 for every lookup.
 *
 * The readers are any thread, rarely, and they go through a concurrent map of
 * the same counters, where a box per call costs nothing that matters.
 *
 * [record], [remove] and [clear] run on the loop thread or under the loop lock,
 * which is what lets the index be an ordinary array. [get] may run anywhere.
 */
internal class MissedCycles {

    /** The same counters, for readers off the loop thread. */
    private val shared = ConcurrentHashMap<Int, AtomicLong>()

    // An open addressing table, linear probing, kept at most half full.
    private var keys = IntArray(INITIAL_CAPACITY)
    private var counters = arrayOfNulls<AtomicLong>(INITIAL_CAPACITY)
    private var used = BooleanArray(INITIAL_CAPACITY)
    private var size = 0

    /**
     * What one cycle said about one node. [count] is the graph's 32-bit
     * unsigned count as the bits it was written in, and a count that has
     * wrapped past two billion cycles is still a count.
     */
    fun record(node: Int, count: Int) {
        val value = count.toLong() and UNSIGNED_INT
        val held = find(node)
        if (held != null) {
            held.set(value)
            return
        }
        // A node seen for the first time costs one counter, once.
        val fresh = AtomicLong(value)
        insert(node, fresh)
        shared[node] = fresh
    }

    /** The last count recorded for [node], or null where none has been. */
    fun get(node: Int): Long? = shared[node]?.get()

    /** Forget [node], whose id the graph may hand to a different node next. */
    fun remove(node: Int) {
        shared.remove(node)
        delete(node)
    }

    fun clear() {
        shared.clear()
        keys.fill(0)
        counters.fill(null)
        used.fill(false)
        size = 0
    }

    private fun slot(node: Int, mask: Int): Int = (node * GOLDEN) ushr (Int.SIZE_BITS - Integer.bitCount(mask)) and mask

    private fun find(node: Int): AtomicLong? {
        val mask = keys.size - 1
        var at = slot(node, mask)
        while (used[at]) {
            if (keys[at] == node) return counters[at]
            at = (at + 1) and mask
        }
        return null
    }

    private fun insert(node: Int, counter: AtomicLong) {
        if ((size + 1) * 2 > keys.size) grow()
        place(node, counter)
    }

    private fun place(node: Int, counter: AtomicLong) {
        val mask = keys.size - 1
        var at = slot(node, mask)
        while (used[at]) {
            if (keys[at] == node) {
                counters[at] = counter
                return
            }
            at = (at + 1) and mask
        }
        used[at] = true
        keys[at] = node
        counters[at] = counter
        size++
    }

    /**
     * Take [node] out and put back everything after it in its run, which is
     * what keeps a later lookup from stopping at the hole and missing a key
     * that had probed past it.
     */
    private fun delete(node: Int) {
        val mask = keys.size - 1
        var at = slot(node, mask)
        while (used[at] && keys[at] != node) at = (at + 1) and mask
        if (!used[at]) return
        used[at] = false
        counters[at] = null
        size--
        var next = (at + 1) and mask
        while (used[next]) {
            val key = keys[next]
            val counter = checkNotNull(counters[next])
            used[next] = false
            counters[next] = null
            size--
            place(key, counter)
            next = (next + 1) and mask
        }
    }

    private fun grow() {
        val oldKeys = keys
        val oldCounters = counters
        val oldUsed = used
        keys = IntArray(oldKeys.size * 2)
        counters = arrayOfNulls(oldKeys.size * 2)
        used = BooleanArray(oldKeys.size * 2)
        size = 0
        for (index in oldKeys.indices) {
            if (oldUsed[index]) place(oldKeys[index], checkNotNull(oldCounters[index]))
        }
    }

    private companion object {
        /** A power of two, and room for a small graph before the first resize. */
        const val INITIAL_CAPACITY = 64

        /** Fibonacci hashing, so ids handed out in sequence do not land in one run. */
        const val GOLDEN = -0x61c88647

        const val UNSIGNED_INT = 0xFFFF_FFFFL
    }
}
