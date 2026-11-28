package dev.hivens.libsound.audio

/**
 * Profiler objects as `tools/pipewire-oracle.c` dumps them, and events of any
 * size built from the same bytes.
 *
 * Shared because three places need them: the decoder's own tests, the count of
 * what decoding one event allocates, and the stand that times it.
 */
internal object ProfilerFixtures {

    /**
     * A Profiler object built by the library's own builder. Two follower blocks
     * under the one key, which is how the object carries a graph: a block per
     * node, ten fields each, of which the reader takes the first and the ninth.
     * 376 bytes.
     */
    const val PROFILER_FOLLOWER =
        "70 01 00 00 0f 00 00 00 0a 00 04 00 00 00 00 00 " +
            "01 00 02 00 00 00 00 00 a8 00 00 00 0e 00 00 00 " +
            "04 00 00 00 04 00 00 00 31 00 00 00 00 00 00 00 " +
            "0e 00 00 00 08 00 00 00 6c 69 62 73 6f 75 6e 64 " +
            "5f 74 65 73 74 00 00 00 08 00 00 00 05 00 00 00 " +
            "01 00 00 00 00 00 00 00 08 00 00 00 05 00 00 00 " +
            "02 00 00 00 00 00 00 00 08 00 00 00 05 00 00 00 " +
            "03 00 00 00 00 00 00 00 08 00 00 00 05 00 00 00 " +
            "04 00 00 00 00 00 00 00 04 00 00 00 04 00 00 00 " +
            "03 00 00 00 00 00 00 00 08 00 00 00 0b 00 00 00 " +
            "00 04 00 00 80 bb 00 00 04 00 00 00 04 00 00 00 " +
            "07 00 00 00 00 00 00 00 04 00 00 00 02 00 00 00 " +
            "00 00 00 00 00 00 00 00 01 00 02 00 00 00 00 00 " +
            "a0 00 00 00 0e 00 00 00 04 00 00 00 04 00 00 00 " +
            "32 00 00 00 00 00 00 00 08 00 00 00 08 00 00 00 " +
            "61 6e 6f 74 68 65 72 00 08 00 00 00 05 00 00 00 " +
            "05 00 00 00 00 00 00 00 08 00 00 00 05 00 00 00 " +
            "06 00 00 00 00 00 00 00 08 00 00 00 05 00 00 00 " +
            "07 00 00 00 00 00 00 00 08 00 00 00 05 00 00 00 " +
            "08 00 00 00 00 00 00 00 04 00 00 00 04 00 00 00 " +
            "03 00 00 00 00 00 00 00 08 00 00 00 0b 00 00 00 " +
            "00 04 00 00 80 bb 00 00 04 00 00 00 04 00 00 00 " +
            "00 00 00 00 00 00 00 00 04 00 00 00 02 00 00 00 " +
            "00 00 00 00 00 00 00 00"

    /**
     * The same again in the shape a profile event actually arrives in: a struct
     * whose children are objects, one per driver, carrying a block per node.
     * Two blocks here, so a reader that kept only the last would be caught.
     */
    const val PROFILE_EVENT =
        "78 01 00 00 0e 00 00 00 70 01 00 00 0f 00 00 00 " +
            "0a 00 04 00 00 00 00 00 01 00 02 00 00 00 00 00 " +
            "a8 00 00 00 0e 00 00 00 04 00 00 00 04 00 00 00 " +
            "31 00 00 00 00 00 00 00 0e 00 00 00 08 00 00 00 " +
            "6c 69 62 73 6f 75 6e 64 5f 74 65 73 74 00 00 00 " +
            "08 00 00 00 05 00 00 00 01 00 00 00 00 00 00 00 " +
            "08 00 00 00 05 00 00 00 02 00 00 00 00 00 00 00 " +
            "08 00 00 00 05 00 00 00 03 00 00 00 00 00 00 00 " +
            "08 00 00 00 05 00 00 00 04 00 00 00 00 00 00 00 " +
            "04 00 00 00 04 00 00 00 03 00 00 00 00 00 00 00 " +
            "08 00 00 00 0b 00 00 00 00 04 00 00 80 bb 00 00 " +
            "04 00 00 00 04 00 00 00 07 00 00 00 00 00 00 00 " +
            "04 00 00 00 02 00 00 00 00 00 00 00 00 00 00 00 " +
            "01 00 02 00 00 00 00 00 a0 00 00 00 0e 00 00 00 " +
            "04 00 00 00 04 00 00 00 32 00 00 00 00 00 00 00 " +
            "08 00 00 00 08 00 00 00 61 6e 6f 74 68 65 72 00 " +
            "08 00 00 00 05 00 00 00 01 00 00 00 00 00 00 00 " +
            "08 00 00 00 05 00 00 00 02 00 00 00 00 00 00 00 " +
            "08 00 00 00 05 00 00 00 03 00 00 00 00 00 00 00 " +
            "08 00 00 00 05 00 00 00 04 00 00 00 00 00 00 00 " +
            "04 00 00 00 04 00 00 00 03 00 00 00 00 00 00 00 " +
            "08 00 00 00 0b 00 00 00 00 04 00 00 80 bb 00 00 " +
            "04 00 00 00 04 00 00 00 00 00 00 00 00 00 00 00 " +
            "04 00 00 00 02 00 00 00 00 00 00 00 00 00 00 00"

    /** The reference dumps read back, so a decoder can be put through them. */
    fun bytesOf(hex: String): ByteArray =
        hex.split(" ").filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()

    /**
     * A profile event carrying [followers] blocks, in the shape the graph sends.
     *
     * Each block is the first one of [PROFILER_FOLLOWER] byte for byte, with the
     * node id and the missed count rewritten: node `FIRST_NODE + index`, having
     * missed `index` cycles. One driver object inside the struct, which is a
     * graph with one driver and that many nodes following it.
     */
    fun eventWith(followers: Int): ByteArray {
        val block = bytesOf(PROFILER_FOLLOWER).copyOfRange(FIRST_BLOCK_AT, FIRST_BLOCK_AT + BLOCK_BYTES)
        val objectSize = OBJECT_BODY_BYTES + followers * BLOCK_BYTES
        val event = ByteArray(HEADER_BYTES + HEADER_BYTES + objectSize)
        putInt(event, 0, HEADER_BYTES + objectSize)
        putInt(event, 4, TYPE_STRUCT)
        putInt(event, 8, objectSize)
        putInt(event, 12, TYPE_OBJECT)
        // The object type and id the dump opens with, unchanged.
        bytesOf(PROFILER_FOLLOWER).copyInto(event, 16, 8, 16)
        repeat(followers) { index ->
            val at = HEADER_BYTES * 2 + OBJECT_BODY_BYTES + index * BLOCK_BYTES
            block.copyInto(event, at)
            putInt(event, at + NODE_AT, FIRST_NODE + index)
            putInt(event, at + MISSED_AT, index)
        }
        return event
    }

    /** The node id the first block of [eventWith] describes. */
    const val FIRST_NODE = 1_000

    private fun putInt(bytes: ByteArray, at: Int, value: Int) {
        bytes[at] = value.toByte()
        bytes[at + 1] = (value ushr 8).toByte()
        bytes[at + 2] = (value ushr 16).toByte()
        bytes[at + 3] = (value ushr 24).toByte()
    }

    private const val HEADER_BYTES = 8
    private const val OBJECT_BODY_BYTES = 8
    private const val TYPE_STRUCT = 0x0e
    private const val TYPE_OBJECT = 0x0f

    /** The first block in [PROFILER_FOLLOWER]: past the object's header and body. */
    private const val FIRST_BLOCK_AT = 16

    /** A key, a flags word, a struct header and its 168 byte body. */
    private const val BLOCK_BYTES = 184

    /** Inside one block: the value of its first field, which is the node. */
    private const val NODE_AT = 24

    /** And of its ninth, which is the missed count. */
    private const val MISSED_AT = 160
}
