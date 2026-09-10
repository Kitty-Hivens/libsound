package dev.hivens.libsound.audio

import dev.hivens.libsound.AudioFormat
import dev.hivens.libsound.ChannelLayout
import dev.hivens.libsound.ChannelPosition
import dev.hivens.libsound.PcmEncoding
import dev.hivens.libsound.audio.pipewire.SpaAbi
import dev.hivens.libsound.audio.pipewire.SpaPod
import dev.hivens.libsound.audio.pipewire.SpaPodReader
import dev.hivens.libsound.audio.pulse.PulseAbi
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
    fun `the reader pulls back out of those bytes what the builder put in`() {
        // The other direction, against the same reference dump, which is what
        // makes this a check rather than the encoder and the decoder agreeing
        // with each other about something they both have wrong.
        //
        // The bytes are spa_pod_builder's. Reading a rate of 48000, six
        // channels and the 5.1 positions out of them means the walk steps by
        // the padded size rather than the declared one, and knows that an
        // array's body starts with the size and type of one element.
        val bytes = ENUM_FORMAT_5_1.fromHex()
        SpaPodReader.objectHeader(bytes) shouldBe (SpaAbi.OBJECT_FORMAT to SpaAbi.PARAM_ENUM_FORMAT)
        val read = SpaPodReader.objectProperties(bytes)
        read[SpaAbi.FORMAT_MEDIA_TYPE] shouldBe SpaAbi.MEDIA_TYPE_AUDIO
        read[SpaAbi.FORMAT_MEDIA_SUBTYPE] shouldBe SpaAbi.MEDIA_SUBTYPE_RAW
        read[SpaAbi.FORMAT_AUDIO_FORMAT] shouldBe SpaAbi.AUDIO_FORMAT_S16_LE
        read[SpaAbi.FORMAT_AUDIO_RATE] shouldBe 48_000
        read[SpaAbi.FORMAT_AUDIO_CHANNELS] shouldBe 6
        (read[SpaAbi.FORMAT_AUDIO_POSITION] as IntArray).toList() shouldBe listOf(
            SpaAbi.CHANNEL_FL, SpaAbi.CHANNEL_FR, SpaAbi.CHANNEL_FC,
            SpaAbi.CHANNEL_LFE, SpaAbi.CHANNEL_RL, SpaAbi.CHANNEL_RR,
        )
    }

    @Test
    fun `the reader takes a float, a boolean and a float array, which is what a volume is`() {
        // A device's own volume is a Props object the graph wrote, carrying a
        // float per channel and a mute beside it. This dump is spa_pod_builder's
        // too, so the three types the format object does not exercise are read
        // against the same authority as the rest.
        val bytes = PROPS_VOLUME.fromHex()
        SpaPodReader.objectHeader(bytes) shouldBe (SpaAbi.OBJECT_PROPS to SpaAbi.PARAM_PROPS)
        val read = SpaPodReader.objectProperties(bytes)
        read[SpaAbi.PROP_VOLUME] shouldBe 0.25f
        read[SpaAbi.PROP_MUTE] shouldBe true
        (read[SpaAbi.PROP_CHANNEL_VOLUMES] as FloatArray).toList() shouldBe listOf(0.25f, 0.5f)
    }

    @Test
    fun `a length the object cannot hold ends the walk instead of reading past it`() {
        // The bytes come from another process, so a size is a claim rather than
        // a fact. Truncating the reference dump leaves a property whose value
        // runs past the end, and what comes back is what was decoded before it
        // rather than an exception on the graph's own thread.
        val whole = ENUM_FORMAT_5_1.fromHex()
        val cut = whole.copyOf(whole.size - SpaAbi.POD_ALIGN * 3)
        val read = SpaPodReader.objectProperties(cut)
        read[SpaAbi.FORMAT_AUDIO_RATE] shouldBe 48_000
        withClue("the array ran past the end and was taken anyway") {
            read.containsKey(SpaAbi.FORMAT_AUDIO_POSITION) shouldBe false
        }
    }

    @Test
    fun `the graph names eight positions the compatibility layer has no word for`() {
        // The measurement section 13.1 is built on, kept here so it fails if
        // either table is edited rather than only if the plan is.
        //
        // Both counts and the difference between them, because the number in
        // the name is the difference. Asserting only the graph's twenty-six
        // left the eight unguarded: widening the shim's table would have made
        // this test's own name wrong while it stayed green.
        val graph = ChannelPosition.entries.filter { SpaAbi.channelOf(it) != null }
        val shim = ChannelPosition.entries.filter { PulseAbi.channelPositionOf(it) != null }
        withClue("positions the graph can place") { graph.size shouldBe 26 }
        withClue("positions the compatibility layer can place") { shim.size shouldBe 18 }
        withClue("what the layer costs") { (graph - shim.toSet()).size shouldBe 8 }
        // And they are these eight, by name, so a table edited to keep the
        // count and change the members is caught too.
        (graph - shim.toSet()).map { it.name }.sorted() shouldBe
            listOf("BFC", "BFL", "BFR", "LFE2", "TSL", "TSR", "WL", "WR")
        // Nothing the shim places is missing from the graph, which is the
        // direction that would make "wider" the wrong word entirely.
        withClue("positions only the shim can place") { (shim - graph.toSet()) shouldBe emptyList() }
    }

    private fun ByteArray.int32At(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or
            ((this[offset + 1].toInt() and 0xFF) shl 8) or
            ((this[offset + 2].toInt() and 0xFF) shl 16) or
            ((this[offset + 3].toInt() and 0xFF) shl 24)

    private fun ByteArray.toHex(): String = joinToString(" ") { "%02x".format(it) }

    /** The reference dumps read back, so a decoder can be put through them. */
    private fun String.fromHex(): ByteArray =
        split(" ").filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()

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

        /**
         * `spa_pod_builder_add_object` for a Props carrying a volume, a mute
         * and two channel volumes. 96 bytes, and the reference the decoder is
         * checked against: it holds the three types the format object has none
         * of, a float, a boolean and an array of floats.
         */
        const val PROPS_VOLUME =
            "58 00 00 00 0f 00 00 00 02 00 04 00 02 00 00 00 " +
                "03 00 01 00 00 00 00 00 04 00 00 00 06 00 00 00 " +
                "00 00 80 3e 00 00 00 00 04 00 01 00 00 00 00 00 " +
                "04 00 00 00 02 00 00 00 01 00 00 00 00 00 00 00 " +
                "08 00 01 00 00 00 00 00 10 00 00 00 0d 00 00 00 " +
                "04 00 00 00 06 00 00 00 00 00 80 3e 00 00 00 3f"

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
