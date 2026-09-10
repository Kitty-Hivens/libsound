package dev.hivens.libsound.audio.pipewire

import dev.hivens.libsound.AudioFormat
import dev.hivens.libsound.ChannelLayout

/**
 * Building the serialised objects PipeWire negotiates with.
 *
 * The one place in this library that emits a native structure byte by byte
 * rather than calling something that does. PipeWire's own builder is inline C
 * macros over a `spa_pod_builder`, Panama can call a function and not a macro,
 * and there is no symbol to bind that would do it instead.
 *
 * ## The shape
 *
 * A pod is a size, a type, and a body padded to eight. The size counts the body
 * before the padding, which is the detail that makes a hand-written encoder
 * wrong in a way nothing complains about: a length field that includes its own
 * padding produces an object the server walks off the end of.
 *
 * An object is a pod whose body starts with an object type and a parameter id
 * and continues with properties. A property is not itself a pod: it is a key, a
 * flags word, and then one value pod. An array is a pod whose body starts with
 * the size and type of its elements and continues with the elements, which is
 * why an array of six ids is thirty-two bytes rather than twenty-four.
 *
 * ## How it is known to be right
 *
 * `tools/pipewire-oracle.c` builds the same objects with the library's own
 * builder and dumps them, and `SpaPodTest` holds those bytes and compares. That is
 * the whole verification, it needs no server, and it turns a class of defect
 * that would otherwise surface as a stream quietly negotiating something else
 * into a byte comparison with an offset in it.
 */
internal class SpaPod(capacity: Int = DEFAULT_CAPACITY) {

    private var bytes = ByteArray(capacity)
    private var length = 0

    /** Everything written so far, which is the object once the outermost is closed. */
    fun toByteArray(): ByteArray = bytes.copyOf(length)

    // -- primitives ----------------------------------------------------------

    private fun int32(value: Int) {
        ensure(Int.SIZE_BYTES)
        // Little-endian by hand rather than through a ByteBuffer: every machine
        // this runs on is little-endian, and writing it out is what makes that
        // an assumption on the page instead of one in a default.
        bytes[length] = value.toByte()
        bytes[length + 1] = (value ushr 8).toByte()
        bytes[length + 2] = (value ushr 16).toByte()
        bytes[length + 3] = (value ushr 24).toByte()
        length += Int.SIZE_BYTES
    }

    private fun int64(value: Long) {
        int32(value.toInt())
        int32((value ushr 32).toInt())
    }

    /** Pad to [SpaAbi.POD_ALIGN], which every pod body owes and no size counts. */
    private fun pad() {
        while (length % SpaAbi.POD_ALIGN != 0) {
            ensure(1)
            bytes[length] = 0
            length += 1
        }
    }

    private fun ensure(more: Int) {
        if (length + more <= bytes.size) return
        var grown = bytes.size * 2
        while (grown < length + more) grown *= 2
        bytes = bytes.copyOf(grown)
    }

    // -- pods ----------------------------------------------------------------

    /**
     * Write a header with a placeholder size and return where the size sits, so
     * [close] can patch it once the body is known.
     */
    private fun open(type: Int): Int {
        val sizeOffset = length
        int32(0)
        int32(type)
        return sizeOffset
    }

    /**
     * Patch the size a matching [open] left, then pad.
     *
     * The size is the body before the padding. Counting the padding is the
     * mistake this comment exists for: the server reads the next pod at the
     * padded offset and the length it was given, and the two disagreeing walks
     * it into the middle of something.
     */
    private fun close(sizeOffset: Int) {
        val body = length - (sizeOffset + SpaAbi.POD_HEADER_SIZE)
        val saved = length
        length = sizeOffset
        int32(body)
        length = saved
        pad()
    }

    private fun pod(type: Int, body: () -> Unit) {
        val sizeOffset = open(type)
        body()
        close(sizeOffset)
    }

    private fun id(value: Int) = pod(SpaAbi.TYPE_ID) { int32(value) }

    private fun int(value: Int) = pod(SpaAbi.TYPE_INT) { int32(value) }

    private fun long(value: Long) = pod(SpaAbi.TYPE_LONG) { int64(value) }

    private fun float(value: Float) = pod(SpaAbi.TYPE_FLOAT) { int32(value.toRawBits()) }

    /**
     * An array of ids, which is how a channel layout crosses.
     *
     * The body carries the size and type of one element before the elements
     * themselves, so an empty array is still eight bytes and a full one is
     * eight plus the count.
     */
    private fun idArray(values: IntArray) = pod(SpaAbi.TYPE_ARRAY) {
        int32(Int.SIZE_BYTES)
        int32(SpaAbi.TYPE_ID)
        values.forEach { int32(it) }
    }

    /** A key, a flags word, and one value. Not a pod itself. */
    private fun prop(key: Int, value: () -> Unit) {
        int32(key)
        int32(0)
        value()
    }

    private fun obj(objectType: Int, paramId: Int, body: () -> Unit) = pod(SpaAbi.TYPE_OBJECT) {
        int32(objectType)
        int32(paramId)
        body()
    }

    companion object {
        private const val DEFAULT_CAPACITY = 512

        /**
         * The format a stream asks the graph for.
         *
         * The positions go across whenever the layout names them and the graph
         * has a name for every one, which is what
         * [dev.hivens.libsound.Capability.CHANNEL_PLACEMENT] promises. A layout
         * that names nothing sends a count and no position array, and the graph
         * lays it out by its own convention, which is what a stream that
         * declared nothing gets anywhere.
         */
        fun audioFormat(format: AudioFormat, paramId: Int = SpaAbi.PARAM_ENUM_FORMAT): ByteArray {
            val positions = positionsOf(format.layout)
            return SpaPod().apply {
                obj(SpaAbi.OBJECT_FORMAT, paramId) {
                    prop(SpaAbi.FORMAT_MEDIA_TYPE) { id(SpaAbi.MEDIA_TYPE_AUDIO) }
                    prop(SpaAbi.FORMAT_MEDIA_SUBTYPE) { id(SpaAbi.MEDIA_SUBTYPE_RAW) }
                    prop(SpaAbi.FORMAT_AUDIO_FORMAT) { id(SpaAbi.audioFormatOf(format.encoding)) }
                    prop(SpaAbi.FORMAT_AUDIO_RATE) { int(format.sampleRate) }
                    prop(SpaAbi.FORMAT_AUDIO_CHANNELS) { int(format.channels) }
                    if (positions != null) {
                        prop(SpaAbi.FORMAT_AUDIO_POSITION) { idArray(positions) }
                    }
                }
            }.toByteArray()
        }

        /**
         * What the layout is as graph positions, or null where it names nothing
         * or names something the graph cannot place.
         */
        fun positionsOf(layout: ChannelLayout): IntArray? {
            if (!layout.isSpecified) return null
            val positions = IntArray(layout.positions.size)
            layout.positions.forEachIndexed { index, position ->
                positions[index] = SpaAbi.channelOf(position) ?: return null
            }
            return positions
        }

        /**
         * A latency request, in quanta.
         *
         * The lever section 4.4 measured `pipewire-pulse` overwriting. A node
         * asks for a quantum and keeps it, where a pulse client asks for a
         * buffer and has the shim translate.
         */
        fun latency(
            direction: Int,
            minQuantum: Float,
            maxQuantum: Float,
            paramId: Int = SpaAbi.PARAM_LATENCY,
        ): ByteArray = SpaPod().apply {
            obj(SpaAbi.OBJECT_PARAM_LATENCY, paramId) {
                prop(SpaAbi.LATENCY_DIRECTION) { id(direction) }
                prop(SpaAbi.LATENCY_MIN_QUANTUM) { float(minQuantum) }
                prop(SpaAbi.LATENCY_MAX_QUANTUM) { float(maxQuantum) }
                // The remaining four are the rate and the wall-clock bounds,
                // and zero is what the library's own builder writes for a
                // request that names neither. They are sent rather than left
                // out because the object the server matches against has them.
                prop(SpaAbi.LATENCY_MIN_RATE) { int(0) }
                prop(SpaAbi.LATENCY_MAX_RATE) { int(0) }
                prop(SpaAbi.LATENCY_MIN_NS) { long(0) }
                prop(SpaAbi.LATENCY_MAX_NS) { long(0) }
            }
        }.toByteArray()
    }
}
