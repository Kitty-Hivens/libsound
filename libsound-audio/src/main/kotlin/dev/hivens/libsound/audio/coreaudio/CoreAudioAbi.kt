package dev.hivens.libsound.audio.coreaudio

/**
 * Constants and struct layouts for the CoreAudio subset this backend binds.
 *
 * Every number here was printed by `tools/coreaudio-oracle.c`, which runs on a
 * macOS CI runner because there is no Mac to run it on -- see the Oracle
 * workflow. None of it is remembered, and the oracle asserts each value back so
 * that a layout that differs on an Intel Mac is a compile error there rather
 * than something a user finds by hearing nothing.
 *
 * Taken from the macOS 26.5 SDK on arm64, cross-checked for x86_64.
 *
 * The selectors deserve their own warning. They are four-character codes, so a
 * wrong one is a plausible integer that addresses a different property: the
 * failure is an empty device list or a silent refusal, never a type error.
 */
internal object CoreAudioAbi {

    // -- AudioStreamBasicDescription -----------------------------------------

    const val ASBD_SAMPLE_RATE = 0L
    const val ASBD_FORMAT_ID = 8L
    const val ASBD_FORMAT_FLAGS = 12L
    const val ASBD_BYTES_PER_PACKET = 16L
    const val ASBD_FRAMES_PER_PACKET = 20L
    const val ASBD_BYTES_PER_FRAME = 24L
    const val ASBD_CHANNELS_PER_FRAME = 28L
    const val ASBD_BITS_PER_CHANNEL = 32L
    const val ASBD_SIZE = 40L

    // -- AudioObjectPropertyAddress ------------------------------------------

    const val ADDRESS_SELECTOR = 0L
    const val ADDRESS_SCOPE = 4L
    const val ADDRESS_ELEMENT = 8L
    const val ADDRESS_SIZE = 12L

    // -- AudioBuffer / AudioBufferList ---------------------------------------

    const val BUFFER_NUMBER_CHANNELS = 0L
    const val BUFFER_DATA_BYTE_SIZE = 4L
    const val BUFFER_DATA = 8L
    const val BUFFER_SIZE = 16L

    const val BUFFER_LIST_NUMBER_BUFFERS = 0L

    /** Four bytes of padding sit between the count and the first buffer. */
    const val BUFFER_LIST_BUFFERS = 8L

    // -- AudioComponentDescription -------------------------------------------

    const val COMPONENT_TYPE = 0L
    const val COMPONENT_SUBTYPE = 4L
    const val COMPONENT_MANUFACTURER = 8L
    const val COMPONENT_FLAGS = 12L
    const val COMPONENT_FLAGS_MASK = 16L
    const val COMPONENT_SIZE = 20L

    // -- AURenderCallbackStruct ----------------------------------------------

    const val RENDER_CALLBACK_PROC = 0L
    const val RENDER_CALLBACK_REFCON = 8L
    const val RENDER_CALLBACK_SIZE = 16L

    // -- component selection -------------------------------------------------

    /** `'auou'` */
    const val UNIT_TYPE_OUTPUT = 1_635_086_197

    /** `'def '`, which follows the system default and follows it when it moves. */
    const val UNIT_SUBTYPE_DEFAULT_OUTPUT = 1_684_366_880

    /** `'ahal'`, the only subtype that will let a specific device be pinned. */
    const val UNIT_SUBTYPE_HAL_OUTPUT = 1_634_230_636

    /** `'appl'` */
    const val UNIT_MANUFACTURER_APPLE = 1_634_758_764

    // -- stream format -------------------------------------------------------

    /** `'lpcm'` */
    const val FORMAT_LINEAR_PCM = 1_819_304_813

    const val FORMAT_FLAG_IS_FLOAT = 1
    const val FORMAT_FLAG_IS_SIGNED_INTEGER = 4
    const val FORMAT_FLAG_IS_PACKED = 8

    /**
     * Zero, because every Mac this can run on is little-endian. Named rather
     * than inlined so that the one place it would have to change is findable.
     */
    const val FORMAT_FLAGS_NATIVE_ENDIAN = 0

    // -- audio unit properties, scopes and parameters ------------------------

    const val UNIT_PROPERTY_STREAM_FORMAT = 8
    const val UNIT_PROPERTY_SET_RENDER_CALLBACK = 23
    const val UNIT_PROPERTY_MAXIMUM_FRAMES_PER_SLICE = 14
    const val OUTPUT_UNIT_PROPERTY_CURRENT_DEVICE = 2_000

    /** Global is 0 and Output is 2, which is the pair most easily misremembered. */
    const val SCOPE_GLOBAL = 0
    const val SCOPE_INPUT = 1
    const val SCOPE_OUTPUT = 2

    const val ELEMENT_OUTPUT = 0

    /**
     * The output unit's own gain, applied to our samples inside the unit.
     *
     * Not system-level volume: macOS has no per-application volume in any public
     * API, so nothing in the OS shows or follows this. Which is exactly why the
     * backend does not claim [dev.hivens.libsound.Capability.STREAM_VOLUME].
     */
    const val HAL_PARAM_VOLUME = 14

    /** Set on the render flags when the callback had nothing real to hand over. */
    const val RENDER_ACTION_OUTPUT_IS_SILENCE = 16

    // -- hardware objects and selectors --------------------------------------

    const val SYSTEM_OBJECT = 1

    /** `'dev#'` */
    const val PROPERTY_DEVICES = 1_684_370_979

    /** `'dOut'` */
    const val PROPERTY_DEFAULT_OUTPUT_DEVICE = 1_682_929_012

    /** `'lnam'` */
    const val PROPERTY_NAME = 1_819_173_229

    /** `'uid '`, and the only device identity worth storing -- see [PROPERTY_NAME]. */
    const val PROPERTY_DEVICE_UID = 1_969_841_184

    /** `'slay'`, whose buffer list is how many output channels a device has. */
    const val PROPERTY_STREAM_CONFIGURATION = 1_936_482_681

    /** `'glob'` */
    const val SCOPE_GLOBAL_SELECTOR = 1_735_159_650

    /** `'outp'` */
    const val SCOPE_OUTPUT_SELECTOR = 1_869_968_496

    const val ELEMENT_MAIN = 0

    // -- status codes --------------------------------------------------------

    const val NO_ERROR = 0

    /** `kCFStringEncodingUTF8`. */
    const val CF_ENCODING_UTF8 = 0x0800_0100
}
