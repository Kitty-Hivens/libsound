package dev.hivens.libsound.audio.wasapi

import dev.hivens.libsound.ChannelLayout
import dev.hivens.libsound.ChannelPosition

/**
 * Vtable slots, GUIDs and constants for the WASAPI subset this backend binds.
 *
 * Every number here was printed by `tools/wasapi-oracle.c`, cross-compiled
 * against the real Windows headers and run under wine. None of it is
 * remembered, and the discipline earned its keep immediately: counting slots by
 * hand across the methods this backend actually calls put
 * [SESSION_CONTROL_REGISTER_NOTIFICATION] at 8, because `GetGroupingParam` and
 * `SetGroupingParam` sit between `SetIconPath` and it. Calling slot 8 there
 * would have invoked a getter with a callback-registration signature.
 *
 * A slot index is decided by declaration order in the interface and nothing
 * checks it at runtime, which makes this table the same kind of load-bearing
 * artefact as the libpulse offsets -- and the same kind of thing to regenerate
 * rather than edit when the headers move.
 */
internal object WasapiAbi {

    // -- IUnknown: the first three slots of every COM interface --------------

    const val QUERY_INTERFACE = 0
    const val ADD_REF = 1
    const val RELEASE = 2

    // -- IMMDeviceEnumerator -------------------------------------------------

    const val ENUM_AUDIO_ENDPOINTS = 3
    const val GET_DEFAULT_AUDIO_ENDPOINT = 4
    const val GET_DEVICE = 5
    const val REGISTER_ENDPOINT_NOTIFICATION = 6
    const val UNREGISTER_ENDPOINT_NOTIFICATION = 7

    // -- IMMDeviceCollection -------------------------------------------------

    const val COLLECTION_GET_COUNT = 3
    const val COLLECTION_ITEM = 4

    // -- IMMDevice -----------------------------------------------------------

    const val DEVICE_ACTIVATE = 3
    const val DEVICE_OPEN_PROPERTY_STORE = 4
    const val DEVICE_GET_ID = 5
    const val DEVICE_GET_STATE = 6

    // -- IPropertyStore ------------------------------------------------------

    const val PROPERTY_STORE_GET_VALUE = 5

    // -- IAudioClient --------------------------------------------------------

    const val CLIENT_INITIALIZE = 3
    const val CLIENT_GET_BUFFER_SIZE = 4
    const val CLIENT_GET_STREAM_LATENCY = 5
    const val CLIENT_GET_CURRENT_PADDING = 6
    const val CLIENT_IS_FORMAT_SUPPORTED = 7
    const val CLIENT_GET_MIX_FORMAT = 8
    const val CLIENT_GET_DEVICE_PERIOD = 9
    const val CLIENT_START = 10
    const val CLIENT_STOP = 11
    const val CLIENT_RESET = 12
    const val CLIENT_SET_EVENT_HANDLE = 13
    const val CLIENT_GET_SERVICE = 14

    // -- IAudioRenderClient --------------------------------------------------

    const val RENDER_GET_BUFFER = 3
    const val RENDER_RELEASE_BUFFER = 4

    // -- IAudioClock ---------------------------------------------------------

    const val CLOCK_GET_FREQUENCY = 3
    const val CLOCK_GET_POSITION = 4

    // -- ISimpleAudioVolume --------------------------------------------------

    const val VOLUME_SET_MASTER = 3
    const val VOLUME_GET_MASTER = 4
    const val VOLUME_SET_MUTE = 5

    // -- IAudioSessionControl ------------------------------------------------

    const val SESSION_CONTROL_SET_DISPLAY_NAME = 5
    const val SESSION_CONTROL_SET_ICON_PATH = 7

    /** 10, not 8: GetGroupingParam and SetGroupingParam occupy 8 and 9. */
    const val SESSION_CONTROL_REGISTER_NOTIFICATION = 10

    // -- IMMNotificationClient: the one we implement -------------------------

    const val NOTIFY_ON_DEVICE_STATE_CHANGED = 3
    const val NOTIFY_ON_DEVICE_ADDED = 4
    const val NOTIFY_ON_DEVICE_REMOVED = 5
    const val NOTIFY_ON_DEFAULT_DEVICE_CHANGED = 6
    const val NOTIFY_ON_PROPERTY_VALUE_CHANGED = 7

    /** The vtable we synthesise has to be exactly this long. */
    const val NOTIFY_VTABLE_SLOTS = 8

    // -- the mixer half: everyone else's sessions ----------------------------

    /** IAudioSessionManager2, reached through IMMDevice::Activate. */
    const val SESSION_MANAGER_GET_SESSION_ENUMERATOR = 5
    const val SESSION_MANAGER_REGISTER_NOTIFICATION = 6
    const val SESSION_MANAGER_UNREGISTER_NOTIFICATION = 7

    /** IAudioSessionEnumerator. */
    const val SESSION_ENUM_GET_COUNT = 3
    const val SESSION_ENUM_GET_SESSION = 4

    /**
     * IAudioSessionControl2's own methods start at 12, because it extends
     * IAudioSessionControl and that interface ends at 11. Calling 11 for
     * GetSessionIdentifier would invoke UnregisterAudioSessionNotification
     * through the wrong signature -- the exact failure the oracle exists for.
     */
    const val SESSION_CONTROL_GET_STATE = 3
    const val SESSION_CONTROL_GET_DISPLAY_NAME = 4
    const val SESSION_CONTROL_GET_ICON_PATH = 6
    const val SESSION_CONTROL_UNREGISTER_NOTIFICATION = 11
    const val SESSION_CONTROL2_GET_SESSION_INSTANCE_IDENTIFIER = 13
    const val SESSION_CONTROL2_GET_PROCESS_ID = 14
    const val SESSION_CONTROL2_IS_SYSTEM_SOUNDS = 15

    /** ISimpleAudioVolume, the read side as well as the write side. */
    const val VOLUME_GET_MUTE = 6

    /** IAudioSessionNotification, which we implement rather than call. */
    const val SESSION_NOTIFY_ON_SESSION_CREATED = 3
    const val SESSION_NOTIFY_VTABLE_SLOTS = 4

    /** IAudioSessionEvents, likewise ours to fill. */
    const val SESSION_EVENTS_ON_DISPLAY_NAME_CHANGED = 3
    const val SESSION_EVENTS_ON_ICON_PATH_CHANGED = 4
    const val SESSION_EVENTS_ON_SIMPLE_VOLUME_CHANGED = 5
    const val SESSION_EVENTS_ON_CHANNEL_VOLUME_CHANGED = 6
    const val SESSION_EVENTS_ON_GROUPING_PARAM_CHANGED = 7
    const val SESSION_EVENTS_ON_STATE_CHANGED = 8
    const val SESSION_EVENTS_ON_SESSION_DISCONNECTED = 9
    const val SESSION_EVENTS_VTABLE_SLOTS = 10

    const val SESSION_STATE_INACTIVE = 0
    const val SESSION_STATE_ACTIVE = 1
    const val SESSION_STATE_EXPIRED = 2

    // -- GUIDs, as {Data1, Data2, Data3, Data4[8]} ---------------------------

    const val CLSID_MM_DEVICE_ENUMERATOR = "BCDE0395-E52F-467C-8E3D-C4579291692E"
    const val IID_MM_DEVICE_ENUMERATOR = "A95664D2-9614-4F35-A746-DE8DB63617E6"
    const val IID_MM_NOTIFICATION_CLIENT = "7991EEC9-7E89-4D85-8390-6C703CEC60C0"
    const val IID_AUDIO_CLIENT = "1CB9AD4C-DBFA-4C32-B178-C2F568A703B2"
    const val IID_AUDIO_RENDER_CLIENT = "F294ACFC-3146-4483-A7BF-ADDCA7C260E2"
    const val IID_AUDIO_CLOCK = "CD63314F-3FBA-4A1B-812C-EF96358728E7"
    const val IID_SIMPLE_AUDIO_VOLUME = "87CE5498-68D6-44E5-9215-6DA47EF883D8"
    const val IID_AUDIO_SESSION_CONTROL = "F4B1A599-7266-4319-A8CA-E70ACB11E8CD"
    const val IID_AUDIO_SESSION_MANAGER2 = "77AA99A0-1BD6-484F-8BC7-2C654C9A9B6F"
    const val IID_AUDIO_SESSION_CONTROL2 = "BFB7FF88-7239-4FC9-8FA2-07C950BE9C6D"
    const val IID_AUDIO_SESSION_ENUMERATOR = "E2F5BB11-0570-40CA-ACDD-3AA01277DEE8"
    const val IID_AUDIO_SESSION_NOTIFICATION = "641DD20B-4D41-49CC-ABA3-174B9477BB08"
    const val IID_AUDIO_SESSION_EVENTS = "24918ACC-64B3-37C1-8CA9-74A66E9957A8"
    const val IID_UNKNOWN = "00000000-0000-0000-C000-000000000046"

    /** PKEY_Device_FriendlyName: the human label a settings screen shows. */
    const val PKEY_DEVICE_FRIENDLY_NAME_FMTID = "A45C254E-DF1C-4EFD-8020-67D146A850E0"
    const val PKEY_DEVICE_FRIENDLY_NAME_PID = 14

    // -- WAVEFORMATEX / WAVEFORMATEXTENSIBLE ---------------------------------

    const val WFX_FORMAT_TAG = 0L
    const val WFX_CHANNELS = 2L
    const val WFX_SAMPLES_PER_SEC = 4L
    const val WFX_AVG_BYTES_PER_SEC = 8L
    const val WFX_BLOCK_ALIGN = 12L
    const val WFX_BITS_PER_SAMPLE = 14L
    const val WFX_CB_SIZE = 16L

    /** 18, and deliberately not padded to 20: the field after it starts at 18. */
    const val WFX_SIZE = 18L

    const val WFXE_SAMPLES = 18L
    const val WFXE_CHANNEL_MASK = 20L
    const val WFXE_SUB_FORMAT = 24L
    const val WFXE_SIZE = 40L

    const val WAVE_FORMAT_PCM = 1
    const val WAVE_FORMAT_EXTENSIBLE = 0xFFFE

    /**
     * What `wFormatTag` cannot say, and why the extensible form is used for
     * everything.
     *
     * A plain `WAVEFORMATEX` can say PCM and cannot say float, cannot say how
     * many of a sample's bits carry signal, and cannot say what each channel
     * is. A client that writes only that one is a client that sends S16 and
     * nothing else, which is what this backend did.
     */
    const val KSDATAFORMAT_SUBTYPE_PCM = "00000001-0000-0010-8000-00AA00389B71"
    const val KSDATAFORMAT_SUBTYPE_IEEE_FLOAT = "00000003-0000-0010-8000-00AA00389B71"

    // -- SPEAKER_*, one bit per channel position -----------------------------

    const val SPEAKER_FRONT_LEFT = 0x00000001
    const val SPEAKER_FRONT_RIGHT = 0x00000002
    const val SPEAKER_FRONT_CENTER = 0x00000004
    const val SPEAKER_LOW_FREQUENCY = 0x00000008
    const val SPEAKER_BACK_LEFT = 0x00000010
    const val SPEAKER_BACK_RIGHT = 0x00000020
    const val SPEAKER_FRONT_LEFT_OF_CENTER = 0x00000040
    const val SPEAKER_FRONT_RIGHT_OF_CENTER = 0x00000080
    const val SPEAKER_BACK_CENTER = 0x00000100
    const val SPEAKER_SIDE_LEFT = 0x00000200
    const val SPEAKER_SIDE_RIGHT = 0x00000400
    const val SPEAKER_TOP_CENTER = 0x00000800
    const val SPEAKER_TOP_FRONT_LEFT = 0x00001000
    const val SPEAKER_TOP_FRONT_CENTER = 0x00002000
    const val SPEAKER_TOP_FRONT_RIGHT = 0x00004000
    const val SPEAKER_TOP_BACK_LEFT = 0x00008000
    const val SPEAKER_TOP_BACK_CENTER = 0x00010000
    const val SPEAKER_TOP_BACK_RIGHT = 0x00020000

    /**
     * The named layouts, printed beside the bits so an assembled mask can be
     * checked against one rather than believed.
     *
     * The reason they are here at all: a mask carries no order of its own, and
     * `WAVEFORMATEXTENSIBLE` interleaves by ascending bit. That happens to be
     * the order FFmpeg hands its layouts over in, which is convenient and is
     * not evidence. `WasapiChannelMaskTest` asserts the assembled mask against
     * each of these instead.
     */
    const val KSAUDIO_SPEAKER_MONO = 0x00000004
    const val KSAUDIO_SPEAKER_STEREO = 0x00000003
    const val KSAUDIO_SPEAKER_QUAD = 0x00000033
    const val KSAUDIO_SPEAKER_SURROUND = 0x00000107
    const val KSAUDIO_SPEAKER_5POINT1 = 0x0000003F
    const val KSAUDIO_SPEAKER_5POINT1_SURROUND = 0x0000060F
    const val KSAUDIO_SPEAKER_7POINT1 = 0x000000FF
    const val KSAUDIO_SPEAKER_7POINT1_SURROUND = 0x0000063F

    /**
     * The bit for one position, or null where Windows has none.
     *
     * The same eighteen libpulse can name, which is not a coincidence worth
     * relying on and is worth noticing: the positions two unrelated systems
     * both name are the ones a consumer can count on carrying anywhere.
     */
    fun speakerBitOf(position: ChannelPosition): Int? = when (position) {
        ChannelPosition.FL -> SPEAKER_FRONT_LEFT
        ChannelPosition.FR -> SPEAKER_FRONT_RIGHT
        ChannelPosition.FC -> SPEAKER_FRONT_CENTER
        ChannelPosition.LFE -> SPEAKER_LOW_FREQUENCY
        ChannelPosition.BL -> SPEAKER_BACK_LEFT
        ChannelPosition.BR -> SPEAKER_BACK_RIGHT
        ChannelPosition.FLC -> SPEAKER_FRONT_LEFT_OF_CENTER
        ChannelPosition.FRC -> SPEAKER_FRONT_RIGHT_OF_CENTER
        ChannelPosition.BC -> SPEAKER_BACK_CENTER
        ChannelPosition.SL -> SPEAKER_SIDE_LEFT
        ChannelPosition.SR -> SPEAKER_SIDE_RIGHT
        ChannelPosition.TC -> SPEAKER_TOP_CENTER
        ChannelPosition.TFL -> SPEAKER_TOP_FRONT_LEFT
        ChannelPosition.TFC -> SPEAKER_TOP_FRONT_CENTER
        ChannelPosition.TFR -> SPEAKER_TOP_FRONT_RIGHT
        ChannelPosition.TBL -> SPEAKER_TOP_BACK_LEFT
        ChannelPosition.TBC -> SPEAKER_TOP_BACK_CENTER
        ChannelPosition.TBR -> SPEAKER_TOP_BACK_RIGHT
        ChannelPosition.DL, ChannelPosition.DR,
        ChannelPosition.WL, ChannelPosition.WR,
        ChannelPosition.SDL, ChannelPosition.SDR,
        ChannelPosition.LFE2,
        ChannelPosition.TSL, ChannelPosition.TSR,
        ChannelPosition.BFC, ChannelPosition.BFL, ChannelPosition.BFR,
        ChannelPosition.SSL, ChannelPosition.SSR,
        ChannelPosition.TTL, ChannelPosition.TTR,
        ChannelPosition.BIL, ChannelPosition.BIR,
        -> null
    }

    /**
     * The mask for a layout, or null where a position has no bit.
     *
     * Null is a refusal rather than a zero: a mask of zero is legal and means
     * the mapping is unspecified, which is the right thing to send for a layout
     * that names nothing and the wrong thing to send for one that names a
     * channel this cannot place.
     */
    fun channelMaskOf(layout: ChannelLayout): Int? {
        var mask = 0
        for (position in layout.positions) {
            val bit = speakerBitOf(position) ?: return null
            mask = mask or bit
        }
        return mask
    }

    // -- PROPVARIANT ---------------------------------------------------------

    const val PROPVARIANT_VT = 0L
    const val PROPVARIANT_VALUE = 8L
    const val PROPVARIANT_SIZE = 24L

    /** { GUID fmtid; DWORD pid } -- 20 bytes, passed by value. */
    const val PROPERTYKEY_SIZE = 20L
    const val VT_LPWSTR = 31

    // -- enums and flags -----------------------------------------------------

    const val E_RENDER = 0
    const val E_CONSOLE = 0
    const val E_MULTIMEDIA = 1
    const val DEVICE_STATE_ACTIVE = 0x00000001
    const val SHAREMODE_SHARED = 0
    const val STREAMFLAGS_EVENTCALLBACK = 0x00040000
    const val STREAMFLAGS_NOPERSIST = 0x00080000

    /**
     * Lets the audio engine resample and reformat for us, so a consumer's
     * 44.1 kHz stereo does not have to match the mix format exactly. Without it
     * `Initialize` refuses anything but the engine's own format, and the
     * library would owe the resampling it says it does not do.
     */
    val STREAMFLAGS_AUTOCONVERTPCM: Int = 0x80000000u.toInt()
    const val STREAMFLAGS_SRC_DEFAULT_QUALITY = 0x08000000

    const val BUFFERFLAGS_SILENT = 0x00000002
    const val CLSCTX_ALL = 0x00000017
    const val COINIT_MULTITHREADED = 0x00000000

    // -- HRESULTs ------------------------------------------------------------

    const val S_OK = 0
    const val S_FALSE = 1

    /**
     * The device vanished under a live stream -- a yanked USB DAC, a driver
     * update. Every WASAPI call answers this afterwards, and it is the signal
     * to tear the stream down and reopen rather than to keep pushing.
     */
    // Written as the hex the headers use, narrowed rather than negated by hand.
    // The first cut spelled these as negative literals, and one of the six --
    // RPC_E_CHANGED_MODE -- came out wrong by 65290. Nothing catches that at
    // compile time, and the symptom would have been the backend refusing to
    // start on a thread a UI toolkit had already placed in a single-threaded
    // apartment.
    val AUDCLNT_E_DEVICE_INVALIDATED: Int = 0x88890004u.toInt()
    val AUDCLNT_E_UNSUPPORTED_FORMAT: Int = 0x88890008u.toInt()
    val AUDCLNT_E_DEVICE_IN_USE: Int = 0x8889000Au.toInt()
    val AUDCLNT_E_SERVICE_NOT_RUNNING: Int = 0x88890010u.toInt()

    // -- time ----------------------------------------------------------------

    /**
     * REFERENCE_TIME counts 100-nanosecond units. Every duration in this API
     * uses them, and mistaking them for microseconds sizes every buffer wrong
     * by a factor of ten.
     */
    const val REFTIMES_PER_SECOND = 10_000_000L
    const val NANOS_PER_REFTIME = 100L
}
