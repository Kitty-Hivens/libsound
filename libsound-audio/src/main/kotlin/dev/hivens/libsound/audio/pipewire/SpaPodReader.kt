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
 * ## What it decodes, and what it walks past
 *
 * Booleans, ids, ints, longs, floats, arrays of ids, ints or floats, and
 * structs. An array of booleans or of longs is not decoded, because nothing the
 * graph sends here is one: a channel map is ids, a per-channel volume is
 * floats.
 *
 * Everything else comes back as null, and the position of what follows it is
 * unaffected: every pod declares its own size and the walk steps by that size
 * whatever the type is, so an unknown value is skipped correctly rather than
 * shifting the rest. That is what lets a follower block be read for its first
 * and ninth fields while the string and the fraction between them are passed
 * over.
 *
 * The gap worth naming is `Choice`, which wraps a value when the graph is
 * offering a range or a set rather than stating one. `PropInfo` and
 * `EnumFormat` are written that way. Neither is read here: the two objects this
 * decodes are `Props`, whose values the graph writes flat, and the profiler's,
 * whose blocks are structs of plain numbers. A reader that grew to take either
 * of the other two would meet this first.
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
     * Values come back as [Boolean], [Int], [Long], [Float], [IntArray],
     * [FloatArray], or a [List] of those for a struct, whose entries are null
     * where the field's own type is one this does not decode. A type this does
     * not decode is left out rather than guessed at, so a caller asking for a
     * key it understands is never handed something it does not.
     *
     * A key the object carries more than once keeps its last value here. The
     * profiler's object is written that way and [objectEntries] is what reads
     * it.
     */
    fun objectProperties(pod: MemorySegment): Map<Int, Any> {
        val found = LinkedHashMap<Int, Any>()
        objectEntries(pod).forEach { (key, value) -> found[key] = value }
        return found
    }

    /**
     * The same properties in the order the graph wrote them, with repeats kept.
     *
     * A map is the right shape for the objects this started with, where a key
     * appears once and a caller asks for the one it wants. The profiler's
     * object is not one of those: it carries a block under the same key for
     * every node in the cycle, so a map would keep the last node and drop the
     * rest of the graph.
     */
    fun objectEntries(pod: MemorySegment): List<Pair<Int, Any>> {
        if (pod.isNative && pod.address() == 0L) return emptyList()
        val header = view(pod, POD_HEADER) ?: return emptyList()
        val size = header.get(INT32, SpaAbi.POD_SIZE_OFFSET.toLong())
        val type = header.get(INT32, SpaAbi.POD_TYPE_OFFSET.toLong())
        if (type != SpaAbi.TYPE_OBJECT) return emptyList()
        if (size < SpaAbi.POD_OBJECT_BODY_SIZE || size > MAX_POD_BYTES) return emptyList()
        val declared = POD_HEADER + size
        // A pointer from the graph arrives with no length attached, so the size
        // it declares is the only one there is. A segment that carries its own
        // length is clamped to it instead, which is what lets a truncated
        // reference dump be put through this in a test.
        val end = if (pod.isNative) declared else minOf(declared, pod.byteSize().toInt())
        val whole = view(pod, end) ?: return emptyList()
        return entriesAt(whole, POD_HEADER, size, end)
    }

    /**
     * The same, for a pod that is a struct of objects rather than one object.
     *
     * The shape the profiler sends: a struct holding one object per driver,
     * each carrying a block for every node that followed it. Flattened,
     * because a caller looking for one key across the whole cycle has no use
     * for which driver it came from, and a node appears under one driver.
     *
     * An object at the top is taken as well, so a caller that does not know
     * which of the two it has can ask this.
     */
    fun structuredEntries(pod: MemorySegment): List<Pair<Int, Any>> {
        if (pod.isNative && pod.address() == 0L) return emptyList()
        val header = view(pod, POD_HEADER) ?: return emptyList()
        val size = header.get(INT32, SpaAbi.POD_SIZE_OFFSET.toLong())
        val type = header.get(INT32, SpaAbi.POD_TYPE_OFFSET.toLong())
        if (size < 0 || size > MAX_POD_BYTES) return emptyList()
        // An object needs room for the type and id its body opens with, which
        // is the floor the other entry point applies. Both answer nothing for a
        // pod too short to be what it says it is.
        if (type == SpaAbi.TYPE_OBJECT && size < SpaAbi.POD_OBJECT_BODY_SIZE) return emptyList()
        val declared = POD_HEADER + size
        val end = if (pod.isNative) declared else minOf(declared, pod.byteSize().toInt())
        val whole = view(pod, end) ?: return emptyList()
        if (type == SpaAbi.TYPE_OBJECT) return entriesAt(whole, POD_HEADER, size, end)
        if (type != SpaAbi.TYPE_STRUCT) return emptyList()
        val found = ArrayList<Pair<Int, Any>>()
        var at = POD_HEADER
        while (at + POD_HEADER <= end) {
            val childSize = whole.get(INT32, (at + SpaAbi.POD_SIZE_OFFSET).toLong())
            val childType = whole.get(INT32, (at + SpaAbi.POD_TYPE_OFFSET).toLong())
            val childBody = at + POD_HEADER
            if (childSize < 0 || childSize > end - childBody) break
            if (childType == SpaAbi.TYPE_OBJECT) found += entriesAt(whole, childBody, childSize, end)
            at = childBody + padded(childSize)
        }
        return found
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

    /** The same, over bytes already copied out of the graph. */
    fun objectEntries(bytes: ByteArray): List<Pair<Int, Any>> =
        objectEntries(MemorySegment.ofArray(bytes))

    /** What object type and parameter id a pod carries, or null if it is not an object. */
    fun objectHeader(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < POD_HEADER + SpaAbi.POD_OBJECT_BODY_SIZE) return null
        val whole = MemorySegment.ofArray(bytes)
        if (whole.get(INT32, SpaAbi.POD_TYPE_OFFSET.toLong()) != SpaAbi.TYPE_OBJECT) return null
        return whole.get(INT32, POD_HEADER.toLong()) to
            whole.get(INT32, (POD_HEADER + Int.SIZE_BYTES).toLong())
    }

    // -- the walk -------------------------------------------------------------

    /**
     * One object's properties, wherever that object sits.
     *
     * [body] is just past its pod header, so the properties start after the
     * object type and id that its body opens with, and [size] is what that
     * header declared. [limit] is how far the bytes actually reach, which is
     * the smaller of the two wherever a dump has been cut short.
     */
    private fun entriesAt(whole: MemorySegment, body: Int, size: Int, limit: Int): List<Pair<Int, Any>> {
        val found = ArrayList<Pair<Int, Any>>()
        val end = minOf(body + size, limit)
        var at = body + SpaAbi.POD_OBJECT_BODY_SIZE
        while (at + SpaAbi.POD_PROP_HEADER_SIZE + POD_HEADER <= end) {
            val key = whole.get(INT32, at.toLong())
            val value = at + SpaAbi.POD_PROP_HEADER_SIZE
            // Named apart from this function's own size and body, which describe
            // the object being walked rather than the property at the cursor.
            val valueSize = whole.get(INT32, (value + SpaAbi.POD_SIZE_OFFSET).toLong())
            val valueType = whole.get(INT32, (value + SpaAbi.POD_TYPE_OFFSET).toLong())
            val valueBody = value + POD_HEADER
            // A size the object it sits in cannot hold is where the walk stops.
            // Continuing would read whatever the graph allocated next, and the
            // bytes come from another process.
            // Subtracted rather than added, because a length near the top of
            // the range makes the sum wrap negative and pass a check written
            // the other way round. The bytes come from another process.
            if (valueSize < 0 || valueSize > end - valueBody) break
            valueOf(whole, valueBody, valueSize, valueType, 0)?.let { found += key to it }
            at = valueBody + padded(valueSize)
        }
        return found
    }

    /**
     * A struct's fields, by position, with a null where the type is one this
     * does not decode.
     *
     * The nulls are the point rather than a shortfall. A follower block is ten
     * fields and two of them are wanted, so what matters is that the eight in
     * between are walked past by their own declared size and leave the
     * positions of the other two where they belong.
     */
    private fun fields(whole: MemorySegment, body: Int, size: Int, depth: Int): List<Any?> {
        val found = ArrayList<Any?>()
        var at = body
        val limit = body + size
        while (at + POD_HEADER <= limit && found.size < MAX_STRUCT_FIELDS) {
            val childSize = whole.get(INT32, (at + SpaAbi.POD_SIZE_OFFSET).toLong())
            val childType = whole.get(INT32, (at + SpaAbi.POD_TYPE_OFFSET).toLong())
            val childBody = at + POD_HEADER
            if (childSize < 0 || childSize > limit - childBody) break
            found += valueOf(whole, childBody, childSize, childType, depth + 1)
            at = childBody + padded(childSize)
        }
        return found
    }

    private fun valueOf(whole: MemorySegment, body: Int, size: Int, type: Int, depth: Int): Any? = when (type) {
        SpaAbi.TYPE_BOOL -> size >= Int.SIZE_BYTES &&
            whole.get(INT32, body.toLong()) != 0
        SpaAbi.TYPE_ID, SpaAbi.TYPE_INT ->
            if (size >= Int.SIZE_BYTES) whole.get(INT32, body.toLong()) else null
        SpaAbi.TYPE_LONG ->
            if (size >= Long.SIZE_BYTES) whole.get(INT64, body.toLong()) else null
        SpaAbi.TYPE_FLOAT ->
            if (size >= Float.SIZE_BYTES) whole.get(FLOAT32, body.toLong()) else null
        SpaAbi.TYPE_ARRAY -> array(whole, body, size)
        // Bounded rather than trusted: the bytes come from another process, and
        // nothing the graph sends here nests further than a block inside an
        // object.
        SpaAbi.TYPE_STRUCT -> if (depth >= MAX_STRUCT_DEPTH) null else fields(whole, body, size, depth)
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

    /** The same, over bytes already copied out of the graph. */
    fun structuredEntries(bytes: ByteArray): List<Pair<Int, Any>> =
        structuredEntries(MemorySegment.ofArray(bytes))

    /** More channels than any layout names, by a wide margin. */
    private const val MAX_ARRAY_ELEMENTS = 4_096

    /** Longer than any block the graph writes, by a wide margin. */
    private const val MAX_STRUCT_FIELDS = 64

    /** A block inside an object is one level. Nothing here sends two. */
    private const val MAX_STRUCT_DEPTH = 1
}
