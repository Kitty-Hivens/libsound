package dev.hivens.libsound

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The bridge between a consumer that pushes and a device that pulls.
 *
 * Native backends ask for data through a callback; skinema hands it over
 * through a blocking write. A ring sits between them either way, so this is not
 * an adapter added for symmetry -- it is the shape the problem already has.
 *
 * Single producer, single consumer, lock-based. Boring on purpose: a lock-free
 * ring would buy nothing here (the contended window is one memcpy per period)
 * and would cost the ability to park a writer, which is the entire pacing
 * mechanism.
 *
 * ## Which side may wait
 *
 * The rule is about the device, not about reading or writing: **whichever side
 * the device is on cannot wait, and whichever side the consumer is on can and
 * must.** Waiting is how a consumer learns time has passed, and a device
 * callback missing its deadline is audible.
 *
 * That makes four operations rather than two, because playback and capture put
 * the device on opposite sides.
 *
 * Playing, the device reads. [read] never blocks, fills the shortfall with
 * silence and counts an [underruns], because handing back a short buffer would
 * leave the tail as whatever the caller's array held, which is a repeated
 * fragment or noise. The consumer writes, so [writeFully] blocks until the ring
 * drains, and only the non-blocking [write] reports a partial accept and counts
 * an [overruns].
 *
 * Capturing, the two swap. The device writes, so it uses the non-blocking
 * [write] and a full ring is an [overruns] rather than a stall. The consumer
 * reads, so it uses [readFully], which blocks until the frames have been
 * captured. A recorder wants a filled buffer, and a count it has to loop around
 * is a different question with a smaller buffer as its answer.
 *
 * Every offset is in bytes and every operation is frame-aligned: a device
 * handed half a frame plays the channels swapped from that point on.
 */
public class PcmRingBuffer(
    /** How much it holds. A whole number of frames, checked on construction. */
    public val capacityBytes: Int,
    /** One frame's width, which every read and write is rounded to. */
    public val frameBytes: Int,
) {
    init {
        require(frameBytes > 0) { "frameBytes must be positive, was $frameBytes" }
        require(capacityBytes > 0) { "capacityBytes must be positive, was $capacityBytes" }
        require(capacityBytes % frameBytes == 0) {
            "capacityBytes ($capacityBytes) must be a whole number of frames ($frameBytes)"
        }
    }

    private val lock = ReentrantLock()

    /** Signalled whenever room appears: a read, a clear, or a close. */
    private val roomAvailable = lock.newCondition()

    /** The mirror, for the capture direction: signalled when audio arrives or the ring closes. */
    private val dataAvailable = lock.newCondition()

    private val buffer = ByteArray(capacityBytes)

    /** Index of the oldest unread byte. */
    private var head = 0

    /** Bytes currently readable. */
    private var count = 0

    private var closed = false

    private var overrunBytes = 0L
    private var underrunBytes = 0L

    /** Bytes a producer dropped because the ring was full. */
    public val overruns: Long get() = lock.withLock { overrunBytes }

    /** Bytes of silence a consumer was handed because the ring was empty. */
    public val underruns: Long get() = lock.withLock { underrunBytes }

    /** Bytes available to read right now. */
    public fun available(): Int = lock.withLock { count }

    /** Bytes that can be written right now without blocking. */
    public fun free(): Int = lock.withLock { capacityBytes - count }

    /** True once [close] has been called. A closed buffer accepts nothing more. */
    public fun isClosed(): Boolean = lock.withLock { closed }

    /**
     * Copy up to [length] bytes in, without blocking. Returns how many were
     * accepted, rounded down to a whole frame; a shortfall counts towards
     * [overruns]. Returns 0 on a closed ring.
     */
    public fun write(src: ByteArray, offset: Int, length: Int): Int {
        requireRange(src.size, offset, length)
        lock.withLock {
            if (closed) return 0
            val accepted = alignDown(minOf(length, capacityBytes - count))
            if (accepted < length) overrunBytes += (length - accepted).toLong()
            if (accepted == 0) return 0
            copyIn(src, offset, accepted)
            return accepted
        }
    }

    /**
     * Copy all [length] bytes in, parking while the ring is full.
     *
     * This is the pacing point. It returns when the consumer has taken the
     * bytes, not when they were queued, which is what lets a producer's write
     * loop double as its clock.
     *
     * Returns false when the ring was closed before everything fit, or when
     * [timeoutNanos] elapsed -- a non-positive timeout waits indefinitely.
     * Bytes written before the timeout stay written; a caller that cares which
     * ones is using the wrong method.
     */
    public fun writeFully(src: ByteArray, offset: Int, length: Int, timeoutNanos: Long = 0): Boolean {
        requireRange(src.size, offset, length)
        require(length % frameBytes == 0) { "length ($length) must be a whole number of frames ($frameBytes)" }
        var remainingNanos = timeoutNanos
        var written = 0
        lock.withLock {
            while (written < length) {
                if (closed) return false
                val room = alignDown(capacityBytes - count)
                if (room == 0) {
                    if (timeoutNanos <= 0) {
                        roomAvailable.await()
                    } else {
                        remainingNanos = roomAvailable.awaitNanos(remainingNanos)
                        if (remainingNanos <= 0) return false
                    }
                    continue
                }
                val chunk = minOf(room, length - written)
                copyIn(src, offset + written, chunk)
                written += chunk
            }
        }
        return true
    }

    /**
     * Fill [length] bytes out, never blocking. Returns how many were real
     * audio; the remainder of [length] is zeroed and counts towards
     * [underruns].
     */
    public fun read(dst: ByteArray, offset: Int, length: Int): Int {
        requireRange(dst.size, offset, length)
        require(length % frameBytes == 0) { "length ($length) must be a whole number of frames ($frameBytes)" }
        lock.withLock {
            val real = minOf(length, count)
            if (real > 0) copyOut(dst, offset, real)
            if (real < length) {
                dst.fill(0, offset + real, offset + length)
                underrunBytes += (length - real).toLong()
            }
            if (real > 0) roomAvailable.signalAll()
            return real
        }
    }

    /**
     * Fill all [length] bytes out, parking until they have been captured.
     *
     * The mirror of [writeFully], and the capture direction's pacing point: the
     * device writes and cannot wait, the consumer reads and must. It returns
     * when the frames exist rather than when the call was made, which is what
     * lets a recording loop double as its clock the way a playback loop does.
     *
     * Returns false when the ring was closed before everything arrived, or when
     * [timeoutNanos] elapsed. A non-positive timeout waits indefinitely. Bytes
     * copied before the timeout stay copied, and a caller that cares which ones
     * is using [read] instead.
     */
    public fun readFully(dst: ByteArray, offset: Int, length: Int, timeoutNanos: Long = 0): Boolean {
        requireRange(dst.size, offset, length)
        require(length % frameBytes == 0) { "length ($length) must be a whole number of frames ($frameBytes)" }
        var remainingNanos = timeoutNanos
        var filled = 0
        lock.withLock {
            while (filled < length) {
                if (closed) return false
                val ready = alignDown(minOf(count, length - filled))
                if (ready == 0) {
                    if (timeoutNanos <= 0) {
                        dataAvailable.await()
                    } else {
                        remainingNanos = dataAvailable.awaitNanos(remainingNanos)
                        if (remainingNanos <= 0) return false
                    }
                    continue
                }
                copyOut(dst, offset + filled, ready)
                filled += ready
                roomAvailable.signalAll()
            }
        }
        return true
    }

    /**
     * Drop everything buffered. The seek path: what is in the ring belongs to
     * the old position. Parked writers wake, because the room they were waiting
     * for now exists.
     */
    public fun clear() {
        lock.withLock {
            head = 0
            count = 0
            roomAvailable.signalAll()
            // A parked reader is woken rather than left holding a wait for
            // audio that has just been thrown away: what it was waiting for is
            // gone, and the next thing to arrive belongs to the new position.
            dataAvailable.signalAll()
        }
    }

    /**
     * Refuse further use and wake everyone parked.
     *
     * This is what makes the sink contract's "close unblocks a write in flight"
     * implementable: the thread that could rescue a blocked producer is never
     * the producer itself.
     */
    public fun close() {
        lock.withLock {
            closed = true
            // Both directions. Whoever is parked is freed here, because in
            // either direction they cannot free themselves.
            roomAvailable.signalAll()
            dataAvailable.signalAll()
        }
    }

    /** Zero the counters. For tests and for a fresh open, not for normal running. */
    public fun resetCounters() {
        lock.withLock {
            overrunBytes = 0
            underrunBytes = 0
        }
    }

    // -- internals, all called under the lock --------------------------------

    private fun copyIn(src: ByteArray, offset: Int, length: Int) {
        val tail = (head + count) % capacityBytes
        val firstRun = minOf(length, capacityBytes - tail)
        System.arraycopy(src, offset, buffer, tail, firstRun)
        if (firstRun < length) {
            System.arraycopy(src, offset + firstRun, buffer, 0, length - firstRun)
        }
        count += length
        // Every path that adds audio wakes a parked reader, which is the
        // capture direction's half of the pacing. Signalled here rather than at
        // each call site so a new writer cannot forget it.
        dataAvailable.signalAll()
    }

    private fun copyOut(dst: ByteArray, offset: Int, length: Int) {
        val firstRun = minOf(length, capacityBytes - head)
        System.arraycopy(buffer, head, dst, offset, firstRun)
        if (firstRun < length) {
            System.arraycopy(buffer, 0, dst, offset + firstRun, length - firstRun)
        }
        head = (head + length) % capacityBytes
        count -= length
    }

    private fun alignDown(bytes: Int): Int = bytes - bytes % frameBytes

    private fun requireRange(size: Int, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset + length <= size) {
            "range $offset..${offset + length} outside array of $size"
        }
    }
}
