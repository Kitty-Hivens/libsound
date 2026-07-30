package dev.hivens.libsound

/**
 * Opaque handle for an output device, stable enough to persist in settings.
 *
 * The string is the backend's own identifier -- a PulseAudio sink name, a
 * WASAPI endpoint id, a CoreAudio UID. It is deliberately not an index: sink
 * indices are recycled across a server restart and a stored index would select
 * somebody else's device after a reboot.
 */
@JvmInline
public value class DeviceId(public val value: String) {
    init {
        require(value.isNotBlank()) { "DeviceId must not be blank" }
    }

    override fun toString(): String = value
}

/**
 * An output device the backend can play to.
 *
 * [isDefault] is a snapshot, not a subscription -- the default moves when the
 * user plugs in headphones, and a consumer that cares subscribes through
 * [AudioBackend.onDevicesChanged] rather than re-reading this field.
 */
public data class AudioDevice(
    public val id: DeviceId,
    /** Human-readable label, already localised by the system where it localises. */
    public val name: String,
    public val isDefault: Boolean = false,
)
