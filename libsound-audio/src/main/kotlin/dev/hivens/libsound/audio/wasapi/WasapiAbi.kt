package dev.hivens.libsound.audio.wasapi

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

    // -- GUIDs, as {Data1, Data2, Data3, Data4[8]} ---------------------------

    const val CLSID_MM_DEVICE_ENUMERATOR = "BCDE0395-E52F-467C-8E3D-C4579291692E"
    const val IID_MM_DEVICE_ENUMERATOR = "A95664D2-9614-4F35-A746-DE8DB63617E6"
    const val IID_MM_NOTIFICATION_CLIENT = "7991EEC9-7E89-4D85-8390-6C703CEC60C0"
    const val IID_AUDIO_CLIENT = "1CB9AD4C-DBFA-4C32-B178-C2F568A703B2"
    const val IID_AUDIO_RENDER_CLIENT = "F294ACFC-3146-4483-A7BF-ADDCA7C260E2"
    const val IID_AUDIO_CLOCK = "CD63314F-3FBA-4A1B-812C-EF96358728E7"
    const val IID_SIMPLE_AUDIO_VOLUME = "87CE5498-68D6-44E5-9215-6DA47EF883D8"
    const val IID_AUDIO_SESSION_CONTROL = "F4B1A599-7266-4319-A8CA-E70ACB11E8CD"
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

    // -- PROPVARIANT ---------------------------------------------------------

    const val PROPVARIANT_VT = 0L
    const val PROPVARIANT_VALUE = 8L
    const val PROPVARIANT_SIZE = 24L
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
    const val STREAMFLAGS_AUTOCONVERTPCM: Int = -0x80000000 // 0x80000000
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
    const val AUDCLNT_E_DEVICE_INVALIDATED: Int = -0x7776FFFC // 0x88890004
    const val AUDCLNT_E_UNSUPPORTED_FORMAT: Int = -0x7776FFF8 // 0x88890008
    const val AUDCLNT_E_DEVICE_IN_USE: Int = -0x7776FFF6 // 0x8889000A
    const val AUDCLNT_E_SERVICE_NOT_RUNNING: Int = -0x7776FFF0 // 0x88890010

    // -- time ----------------------------------------------------------------

    /**
     * REFERENCE_TIME counts 100-nanosecond units. Every duration in this API
     * uses them, and mistaking them for microseconds sizes every buffer wrong
     * by a factor of ten.
     */
    const val REFTIMES_PER_SECOND = 10_000_000L
    const val NANOS_PER_REFTIME = 100L
}
