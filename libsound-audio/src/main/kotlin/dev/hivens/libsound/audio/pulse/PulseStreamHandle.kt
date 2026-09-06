package dev.hivens.libsound.audio.pulse

import dev.hivens.libsound.StreamDirection
import dev.hivens.libsound.StreamId

/**
 * A stream, and which facility it came from.
 *
 * The pair is the identity: a sink input and a source output can carry the
 * same index at the same time, so an index alone would have a microphone
 * row and a playback row answering to one id.
 */
internal data class PulseStreamHandle(val direction: StreamDirection, val index: Int) {
    fun id(): StreamId = StreamId("${prefix(direction)}:$index")

    override fun toString(): String = id().value

    companion object {
        fun prefix(direction: StreamDirection): String = when (direction) {
            StreamDirection.PLAYBACK -> "sink-input"
            StreamDirection.CAPTURE -> "source-output"
        }

        /** Null for anything this mixer did not hand out. */
        fun parse(id: StreamId): PulseStreamHandle? {
            val facility = id.value.substringBefore(':', missingDelimiterValue = "")
            val index = id.value.substringAfter(':', missingDelimiterValue = "").toIntOrNull() ?: return null
            val direction = StreamDirection.entries.firstOrNull { prefix(it) == facility } ?: return null
            return PulseStreamHandle(direction, index)
        }
    }
}
