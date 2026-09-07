package dev.hivens.libsound.audio.pulse

import dev.hivens.libsound.AudioCard
import dev.hivens.libsound.AudioDevice
import dev.hivens.libsound.CardId
import dev.hivens.libsound.CardProfile
import dev.hivens.libsound.DeviceId
import dev.hivens.libsound.DevicePort
import dev.hivens.libsound.StreamDirection
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/**
 * Turns the server's device structs into the library's types.
 *
 * One reader rather than one per caller. The backend enumerates devices for a
 * consumer and the mixer enumerates them to control them, and both read the
 * same fields at the same offsets: transcribing them twice would be two places
 * for one ABI table to go wrong, which is the class of mistake the oracle
 * exists to prevent.
 *
 * A sink and a source have the same layout for everything read here, so the two
 * differ by which offsets are named rather than by any logic.
 */
internal class PulseDeviceReader(private val lib: PulseLibrary) {

    /** Null when the struct carries no name, which is the one field nothing can substitute. */
    fun sink(info: MemorySegment): AudioDevice? {
        if (info.address() == 0L) return null
        val head = info.reinterpret(PulseAbi.SINK_INFO_HEAD)
        val name = head.get(ValueLayout.ADDRESS, PulseAbi.SINK_INFO_NAME).readCString() ?: return null
        val ports = ports(head, PulseAbi.SINK_INFO_N_PORTS, PulseAbi.SINK_INFO_PORTS)
        return AudioDevice(
            id = DeviceId(name),
            name = head.get(ValueLayout.ADDRESS, PulseAbi.SINK_INFO_DESCRIPTION).readCString() ?: name,
            direction = StreamDirection.PLAYBACK,
            volume = volume(head.asSlice(PulseAbi.SINK_INFO_VOLUME, PulseAbi.CVOLUME_SIZE)),
            muted = head.get(ValueLayout.JAVA_INT, PulseAbi.SINK_INFO_MUTE) != 0,
            isSuspended = head.get(ValueLayout.JAVA_INT, PulseAbi.SINK_INFO_STATE) == PulseAbi.DEVICE_STATE_SUSPENDED,
            ports = ports,
            activePort = activePort(head, PulseAbi.SINK_INFO_ACTIVE_PORT),
        )
    }

    fun source(info: MemorySegment): AudioDevice? {
        if (info.address() == 0L) return null
        val head = info.reinterpret(PulseAbi.SOURCE_INFO_HEAD)
        val name = head.get(ValueLayout.ADDRESS, PulseAbi.SOURCE_INFO_NAME).readCString() ?: return null
        return AudioDevice(
            id = DeviceId(name),
            name = head.get(ValueLayout.ADDRESS, PulseAbi.SOURCE_INFO_DESCRIPTION).readCString() ?: name,
            direction = StreamDirection.CAPTURE,
            volume = volume(head.asSlice(PulseAbi.SOURCE_INFO_VOLUME, PulseAbi.CVOLUME_SIZE)),
            muted = head.get(ValueLayout.JAVA_INT, PulseAbi.SOURCE_INFO_MUTE) != 0,
            isSuspended = head.get(ValueLayout.JAVA_INT, PulseAbi.SOURCE_INFO_STATE) == PulseAbi.DEVICE_STATE_SUSPENDED,
            isMonitor = head.get(ValueLayout.JAVA_INT, PulseAbi.SOURCE_INFO_MONITOR_OF_SINK) != PulseAbi.INVALID_INDEX,
            ports = ports(head, PulseAbi.SOURCE_INFO_N_PORTS, PulseAbi.SOURCE_INFO_PORTS),
            activePort = activePort(head, PulseAbi.SOURCE_INFO_ACTIVE_PORT),
        )
    }

    /** How many channels a device's volume carries, for a cvolume sent back at it. */
    fun channels(info: MemorySegment, volumeOffset: Long): Int {
        val cvolume = info.asSlice(volumeOffset, PulseAbi.CVOLUME_SIZE)
        return cvolume.get(ValueLayout.JAVA_BYTE, PulseAbi.CVOLUME_CHANNELS).toInt() and 0xFF
    }

    fun card(info: MemorySegment): AudioCard? {
        if (info.address() == 0L) return null
        val head = info.reinterpret(PulseAbi.CARD_INFO_HEAD)
        val name = head.get(ValueLayout.ADDRESS, PulseAbi.CARD_INFO_NAME).readCString() ?: return null
        val count = head.get(ValueLayout.JAVA_INT, PulseAbi.CARD_INFO_N_PROFILES)
        val array = head.get(ValueLayout.ADDRESS, PulseAbi.CARD_INFO_PROFILES2)
        val profiles = if (count <= 0 || array.address() == 0L) {
            emptyList()
        } else {
            // An array of pointers, terminated by a null entry as well as
            // counted. Both are checked: the count is what the header
            // documents, and the terminator is what the server actually
            // writes.
            val pointers = array.reinterpret(count.toLong() * ValueLayout.ADDRESS.byteSize())
            (0 until count).mapNotNull { index ->
                profile(pointers.getAtIndex(ValueLayout.ADDRESS, index.toLong()))
            }
        }
        val active = profile(head.get(ValueLayout.ADDRESS, PulseAbi.CARD_INFO_ACTIVE_PROFILE2))
        return AudioCard(
            id = CardId(name),
            name = name,
            profiles = profiles,
            activeProfile = active?.name,
        )
    }

    private fun profile(pointer: MemorySegment): CardProfile? {
        if (pointer.address() == 0L) return null
        val entry = pointer.reinterpret(PulseAbi.CARD_PROFILE_SIZE)
        val name = entry.get(ValueLayout.ADDRESS, PulseAbi.CARD_PROFILE_NAME).readCString() ?: return null
        return CardProfile(
            name = name,
            description = entry.get(ValueLayout.ADDRESS, PulseAbi.CARD_PROFILE_DESCRIPTION).readCString() ?: name,
            priority = entry.get(ValueLayout.JAVA_INT, PulseAbi.CARD_PROFILE_PRIORITY),
            available = entry.get(ValueLayout.JAVA_INT, PulseAbi.CARD_PROFILE_AVAILABLE) != 0,
        )
    }

    private fun ports(head: MemorySegment, countOffset: Long, arrayOffset: Long): List<DevicePort> {
        val count = head.get(ValueLayout.JAVA_INT, countOffset)
        val array = head.get(ValueLayout.ADDRESS, arrayOffset)
        if (count <= 0 || array.address() == 0L) return emptyList()
        val pointers = array.reinterpret(count.toLong() * ValueLayout.ADDRESS.byteSize())
        return (0 until count).mapNotNull { index ->
            port(pointers.getAtIndex(ValueLayout.ADDRESS, index.toLong()))
        }
    }

    private fun activePort(head: MemorySegment, offset: Long): String? =
        port(head.get(ValueLayout.ADDRESS, offset))?.name

    private fun port(pointer: MemorySegment): DevicePort? {
        if (pointer.address() == 0L) return null
        val entry = pointer.reinterpret(PulseAbi.PORT_INFO_SIZE)
        val name = entry.get(ValueLayout.ADDRESS, PulseAbi.PORT_INFO_NAME).readCString() ?: return null
        return DevicePort(
            name = name,
            description = entry.get(ValueLayout.ADDRESS, PulseAbi.PORT_INFO_DESCRIPTION).readCString() ?: name,
            // Only an explicit no counts as unavailable. Zero means the port
            // has no jack detection, and hiding a connector because the machine
            // cannot tell whether anything is plugged into it would hide most
            // of the ports on most cards.
            available = entry.get(ValueLayout.JAVA_INT, PulseAbi.PORT_INFO_AVAILABLE) != PulseAbi.PORT_AVAILABLE_NO,
        )
    }

    /** The loudest channel, which is what a slider shows. */
    private fun volume(cvolume: MemorySegment): Float {
        val raw = lib.handle("pa_cvolume_max").invokeExact(cvolume) as Int
        return (lib.handle("pa_sw_volume_to_linear").invokeExact(raw) as Double)
            .toFloat().coerceIn(0f, 1f)
    }
}
