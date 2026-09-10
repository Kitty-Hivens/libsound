package dev.hivens.libsound.audio.pipewire

import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/**
 * Reading the serialised objects PipeWire answers with, which is [SpaPod]
 * backwards.
 *
 * Everything this library sent up to now it also built, so the encoder was the
 * whole of the POD story. A device's own volume is the other direction: it is a
 * parameter of a node, and asking for it means decoding an object the graph
 * wrote.
 *
 * ## The shape, from the reading end
 *
 * A pod is a size, a type, and a body padded to eight, and the size counts the
 * body before the padding. So the next pod after one starts at its body plus
 * the padding, and a reader that steps by the size alone drifts by up to seven
 * bytes per property and ends up decoding a key as a value.
 *
 * An object's body is an object type and a parameter id, then properties. A
 * property is not a pod: it is a key, a flags word, and one value pod. An
 * array's body is the size and type of one element, then the elements.
 *
 * ## Nothing here trusts the numbers
 *
 * The bytes come from another process. Every length is checked against the one
 * the pod above it declared before it is used to advance, and anything that
 * does not fit ends the walk rather than reading past the end. What comes back
 * is what was decoded up to that point, because a truncated answer a caller can
 * see is better than an exception on the graph's own thread.
 *
 * ## How it is known to be right
 *
 * The same reference bytes the encoder is checked against, read back. The
 * oracle dumps a real 5.1 EnumFormat built by the library's own builder, and a
 * reader that pulls the rate, the channel count and the six positions out of
 * those bytes has agreed with `spa_pod_builder` in both directions.
 */
internal object SpaPodReader {

    /**
     * Every property of an object pod, by key, or empty for anything that is
     * not one.
     *
     * Values come back as [Boolean], [Int], [Long], [Float], [IntArray] or
     * [FloatArray]. A type this does not decode is left out rather than
     * guessed at, so a caller asking for a key it understands is never handed
     * something it does not.
     */
    fun objectProperties(pod: MemorySegment): Map<Int, Any> {
        if (pod.isNative && pod.address() == 0L) return emptyMap()
        val header = view(pod, POD_HEADER) ?: return emptyMap()
        val size = header.get(INT32, SpaAbi.POD_SIZE_OFFSET.toLong())
        val type = header.get(INT32, SpaAbi.POD_TYPE_OFFSET.toLong())
        if (type != SpaAbi.TYPE_OBJECT) return emptyMap()
        if (size < SpaAbi.POD_OBJECT_BODY_SIZE || size > MAX_POD_BYTES) return emptyMap()
        val declared = POD_HEADER + size
        // A pointer from the graph arrives with no length attached, so the size
        // it declares is the only one there is. A segment that carries its own
        // length is clamped to it instead, which is what lets a truncated
        // reference dump be put through this in a test.
        val end = if (pod.isNative) declared else minOf(declared, pod.byteSize().toInt())
        val whole = view(pod, end) ?: return emptyMap()
        return properties(whole, end)
    }

    /** A view of at least [bytes] bytes, or null where there are not that many. */
    private fun view(pod: MemorySegment, bytes: Int): MemorySegment? = when {
        pod.isNative -> pod.reinterpret(bytes.toLong())
        pod.byteSize() >= bytes -> pod
        else -> null
    }

    /**
     * The same, over bytes already copied out of the graph.
     *
     * What the tests use, and the reason the walk below takes a segment rather
     * than a pointer: a byte array wrapped in a segment reads identically, so
     * the reference dumps from the oracle can be put through the same code the
     * callback puts the graph's own answer through.
     */
    fun objectProperties(bytes: ByteArray): Map<Int, Any> =
        objectProperties(MemorySegment.ofArray(bytes))

    /** What object type and parameter id a pod carries, or null if it is not an object. */
    fun objectHeader(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < POD_HEADER + SpaAbi.POD_OBJECT_BODY_SIZE) return null
        val whole = MemorySegment.ofArray(bytes)
        if (whole.get(INT32, SpaAbi.POD_TYPE_OFFSET.toLong()) != SpaAbi.TYPE_OBJECT) return null
        return whole.get(INT32, POD_HEADER.toLong()) to
            whole.get(INT32, (POD_HEADER + Int.SIZE_BYTES).toLong())
    }

    // -- the walk -------------------------------------------------------------

    private fun properties(whole: MemorySegment, end: Int): Map<Int, Any> {
        val found = LinkedHashMap<Int, Any>()
        var at = POD_HEADER + SpaAbi.POD_OBJECT_BODY_SIZE
        while (at + SpaAbi.POD_PROP_HEADER_SIZE + POD_HEADER <= end) {
            val key = whole.get(INT32, at.toLong())
            val value = at + SpaAbi.POD_PROP_HEADER_SIZE
            val size = whole.get(INT32, (value + SpaAbi.POD_SIZE_OFFSET).toLong())
            val type = whole.get(INT32, (value + SpaAbi.POD_TYPE_OFFSET).toLong())
            val body = value + POD_HEADER
            // A size the object it sits in cannot hold is where the walk stops.
            // Continuing would read whatever the graph allocated next, and the
            // bytes come from another process.
            if (size < 0 || body + size > end) break
            valueOf(whole, body, size, type)?.let { found[key] = it }
            at = body + padded(size)
        }
        return found
    }

    private fun valueOf(whole: MemorySegment, body: Int, size: Int, type: Int): Any? = when (type) {
        SpaAbi.TYPE_BOOL -> size >= Int.SIZE_BYTES &&
            whole.get(INT32, body.toLong()) != 0
        SpaAbi.TYPE_ID, SpaAbi.TYPE_INT ->
            if (size >= Int.SIZE_BYTES) whole.get(INT32, body.toLong()) else null
        SpaAbi.TYPE_LONG ->
            if (size >= Long.SIZE_BYTES) whole.get(INT64, body.toLong()) else null
        SpaAbi.TYPE_FLOAT ->
            if (size >= Float.SIZE_BYTES) whole.get(FLOAT32, body.toLong()) else null
        SpaAbi.TYPE_ARRAY -> array(whole, body, size)
        else -> null
    }

    private fun array(whole: MemorySegment, body: Int, size: Int): Any? {
        if (size < SpaAbi.POD_ARRAY_BODY_SIZE) return null
        val childSize = whole.get(INT32, body.toLong())
        val childType = whole.get(INT32, (body + Int.SIZE_BYTES).toLong())
        if (childSize <= 0) return null
        val first = body + SpaAbi.POD_ARRAY_BODY_SIZE
        val count = (size - SpaAbi.POD_ARRAY_BODY_SIZE) / childSize
        if (count <= 0 || count > MAX_ARRAY_ELEMENTS) return null
        return when {
            childType == SpaAbi.TYPE_FLOAT && childSize == Float.SIZE_BYTES ->
                FloatArray(count) { whole.get(FLOAT32, (first + it * childSize).toLong()) }
            (childType == SpaAbi.TYPE_ID || childType == SpaAbi.TYPE_INT) && childSize == Int.SIZE_BYTES ->
                IntArray(count) { whole.get(INT32, (first + it * childSize).toLong()) }
            else -> null
        }
    }

    private fun padded(size: Int): Int = (size + SpaAbi.POD_ALIGN - 1) / SpaAbi.POD_ALIGN * SpaAbi.POD_ALIGN

    /**
     * Unaligned layouts, so the same walk reads native memory and a byte array.
     *
     * A pod in the graph's memory is eight aligned and would take the ordinary
     * ones. A reference dump wrapped in a heap segment is a byte array, whose
     * alignment is one, and the aligned layouts refuse it outright. Using these
     * everywhere is what lets the decoder be checked against the oracle's bytes
     * by the same code path that reads the graph's, rather than by a second
     * implementation that is the thing under test.
     */
    private val INT32 = ValueLayout.JAVA_INT_UNALIGNED
    private val INT64 = ValueLayout.JAVA_LONG_UNALIGNED
    private val FLOAT32 = ValueLayout.JAVA_FLOAT_UNALIGNED

    private const val POD_HEADER = SpaAbi.POD_HEADER_SIZE

    /** A parameter object larger than this is one something is wrong with. */
    private const val MAX_POD_BYTES = 1 shl 20

    /** More channels than any layout names, by a wide margin. */
    private const val MAX_ARRAY_ELEMENTS = 4_096
}
