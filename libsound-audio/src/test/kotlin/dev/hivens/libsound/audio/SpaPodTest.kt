package dev.hivens.libsound.audio

import dev.hivens.libsound.AudioFormat
import dev.hivens.libsound.ChannelLayout
import dev.hivens.libsound.ChannelPosition
import dev.hivens.libsound.PcmEncoding
import dev.hivens.libsound.audio.pipewire.SpaAbi
import dev.hivens.libsound.audio.pipewire.SpaPod
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The POD encoder against the bytes PipeWire's own builder produces.
 *
 * Every other ABI table in this repository is transcribed from an oracle and
 * believed. This one is compared, because it can be: `tools/pipewire-oracle.c`
 * builds the same two objects with `spa_pod_builder` and dumps them, and an
 * encoder that emits the same bytes has got the encoding right.
 *
 * That matters more here than the usual argument for oracles. A wrong slot
 * index crashes; a wrong POD is a well-formed object with a field in the wrong
 * place, which the server answers by negotiating something else or by ignoring
 * a property, and neither is visible from the client at all. There is no
 * exception to catch and no log line to read, so a byte comparison is the only
 * place the mistake can surface.
 *
 * It also needs no graph. This runs on every CI row rather than only the Linux
 * ones, which is the second reason to encode against a dump rather than against
 * a live negotiation.
 */
class SpaPodTest {

    @Test
    fun `the format object matches what PipeWire's own builder emits`() {
        // 5.1, S16LE, 48 kHz, positions named. The shape a surround stream asks
        // for, and the one with every kind of property in it: two ids for the
        // media type, one for the sample format, two ints, and an array.
        val format = AudioFormat(48_000, 6, PcmEncoding.S16LE, ChannelLayout.SURROUND_5_1)
        SpaPod.audioFormat(format, SpaAbi.PARAM_ENUM_FORMAT).toHex() shouldBe ENUM_FORMAT_5_1
    }

    @Test
    fun `the latency object matches what PipeWire's own builder emits`() {
        SpaPod.latency(
            direction = SpaAbi.DIRECTION_OUTPUT,
            minQuantum = 1.0f,
            maxQuantum = 1.0f,
        ).toHex() shouldBe PARAM_LATENCY_ONE_QUANTUM
    }

    @Test
    fun `a size counts the body and not the padding it is followed by`() {
        // The mistake a hand-written encoder makes that nothing complains
        // about. A media type property is a four-byte id in an eight-byte slot,
        // so a length field that counted its own padding would be right by
        // inspection and wrong by four, and the server would read the next
        // property from the middle of this one.
        val pod = SpaPod.audioFormat(AudioFormat(48_000, 2), SpaAbi.PARAM_ENUM_FORMAT)
        // The outer object: size at 0, and the whole thing is that plus a header.
        val declared = pod.int32At(SpaAbi.POD_SIZE_OFFSET)
        declared + SpaAbi.POD_HEADER_SIZE shouldBe pod.size
        // The first property's value pod, which starts after the object header
        // and the object body and then the property's own key and flags.
        val firstValue = SpaAbi.POD_HEADER_SIZE + SpaAbi.POD_OBJECT_BODY_SIZE + SpaAbi.POD_PROP_HEADER_SIZE
        pod.int32At(firstValue) shouldBe Int.SIZE_BYTES
        pod.int32At(firstValue + SpaAbi.POD_TYPE_OFFSET) shouldBe SpaAbi.TYPE_ID
    }

    @Test
    fun `a layout that names nothing sends a count and no positions`() {
        // What a stream that declared no layout gets, here and everywhere: the
        // graph lays it out by its own convention, because there is nothing to
        // place. The property is absent rather than empty, since an empty array
        // would be a claim that the stream has no channels anywhere.
        val bare = AudioFormat(48_000, 6, PcmEncoding.S16LE, ChannelLayout.unspecified(6))
        SpaPod.positionsOf(bare.layout) shouldBe null
        val named = SpaPod.audioFormat(AudioFormat(48_000, 6, PcmEncoding.S16LE, ChannelLayout.SURROUND_5_1))
        val unnamed = SpaPod.audioFormat(bare)
        (unnamed.size < named.size) shouldBe true
    }

    @Test
    fun `a layout the graph cannot place sends a count rather than a wrong position`() {
        // The ten positions with no equivalent: the downmix pair, surround
        // direct, side surround, top surround and binaural. Placing one as its
        // nearest neighbour is the quiet mis-placement the whole mechanism
        // exists to stop, so the array is left out and the count stands.
        val binaural = ChannelLayout.STANDARD.getValue("binaural")
        SpaPod.positionsOf(binaural) shouldBe null
        SpaAbi.channelOf(ChannelPosition.BIL) shouldBe null
    }

    @Test
    fun `the graph names eight positions the compatibility layer has no word for`() {
        // The measurement section 13.1 is built on, kept here so it fails if
        // the table is edited rather than only if the plan is.
        val named = ChannelPosition.entries.count { SpaAbi.channelOf(it) != null }
        withClue("positions the graph can place") { named shouldBe 26 }
    }

    private fun ByteArray.int32At(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or
            ((this[offset + 1].toInt() and 0xFF) shl 8) or
            ((this[offset + 2].toInt() and 0xFF) shl 16) or
            ((this[offset + 3].toInt() and 0xFF) shl 24)

    private fun ByteArray.toHex(): String = joinToString(" ") { "%02x".format(it) }

    private companion object {
        /**
         * Printed by `tools/pipewire-oracle.c`, built by `spa_pod_builder` from
         * `spa_format_audio_raw_build`. 184 bytes.
         */
        const val ENUM_FORMAT_5_1 =
            "b0 00 00 00 0f 00 00 00 03 00 04 00 03 00 00 00 " +
                "01 00 00 00 00 00 00 00 04 00 00 00 03 00 00 00 " +
                "01 00 00 00 00 00 00 00 02 00 00 00 00 00 00 00 " +
                "04 00 00 00 03 00 00 00 01 00 00 00 00 00 00 00 " +
                "01 00 01 00 00 00 00 00 04 00 00 00 03 00 00 00 " +
                "03 01 00 00 00 00 00 00 03 00 01 00 00 00 00 00 " +
                "04 00 00 00 04 00 00 00 80 bb 00 00 00 00 00 00 " +
                "04 00 01 00 00 00 00 00 04 00 00 00 04 00 00 00 " +
                "06 00 00 00 00 00 00 00 05 00 01 00 00 00 00 00 " +
                "20 00 00 00 0d 00 00 00 04 00 00 00 03 00 00 00 " +
                "03 00 00 00 04 00 00 00 05 00 00 00 06 00 00 00 " +
                "0c 00 00 00 0d 00 00 00"

        /** The same, from `spa_latency_build` for one quantum out. 184 bytes. */
        const val PARAM_LATENCY_ONE_QUANTUM =
            "b0 00 00 00 0f 00 00 00 0b 00 04 00 0f 00 00 00 " +
                "01 00 00 00 00 00 00 00 04 00 00 00 03 00 00 00 " +
                "01 00 00 00 00 00 00 00 02 00 00 00 00 00 00 00 " +
                "04 00 00 00 06 00 00 00 00 00 80 3f 00 00 00 00 " +
                "03 00 00 00 00 00 00 00 04 00 00 00 06 00 00 00 " +
                "00 00 80 3f 00 00 00 00 04 00 00 00 00 00 00 00 " +
                "04 00 00 00 04 00 00 00 00 00 00 00 00 00 00 00 " +
                "05 00 00 00 00 00 00 00 04 00 00 00 04 00 00 00 " +
                "00 00 00 00 00 00 00 00 06 00 00 00 00 00 00 00 " +
                "08 00 00 00 05 00 00 00 00 00 00 00 00 00 00 00 " +
                "07 00 00 00 00 00 00 00 08 00 00 00 05 00 00 00 " +
                "00 00 00 00 00 00 00 00"
    }
}
