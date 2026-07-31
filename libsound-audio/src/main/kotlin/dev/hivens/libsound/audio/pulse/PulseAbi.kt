package dev.hivens.libsound.audio.pulse

/**
 * Constants and struct layouts for the libpulse subset this backend binds.
 *
 * Every number here was printed by `tools/pa-oracle.c` against the pinned
 * headers, not read off a reference and not inferred. The sibling libraries
 * inferred one struct size instead -- 64 bytes for a 72-byte `DBusMessageIter`
 * -- and wrote past an arena on every call through two shipped releases. The
 * oracle is the whole answer to that class of defect, and rerunning it is the
 * first step of any libpulse version bump.
 *
 * Taken from libpulse 17.0.0, x86_64. The layouts that matter are small and
 * have been stable for the life of the 1.x ABI; the ones that are not stable
 * (`pa_sink_info` and friends) are read field by field at the offsets below
 * rather than mapped wholesale, so a field appended upstream costs nothing.
 */
internal object PulseAbi {

    // -- pa_sample_spec ------------------------------------------------------

    const val SAMPLE_SPEC_FORMAT = 0L
    const val SAMPLE_SPEC_RATE = 4L
    const val SAMPLE_SPEC_CHANNELS = 8L
    const val SAMPLE_SPEC_SIZE = 12L

    // -- pa_buffer_attr ------------------------------------------------------

    const val BUFFER_ATTR_MAXLENGTH = 0L
    const val BUFFER_ATTR_TLENGTH = 4L
    const val BUFFER_ATTR_PREBUF = 8L
    const val BUFFER_ATTR_MINREQ = 12L
    const val BUFFER_ATTR_FRAGSIZE = 16L
    const val BUFFER_ATTR_SIZE = 20L

    /** Every pa_buffer_attr field takes this to mean "server default". */
    const val ATTR_DEFAULT: Int = -1

    // -- pa_cvolume ----------------------------------------------------------

    const val CVOLUME_SIZE = 132L
    const val VOLUME_NORM = 65_536

    // -- pa_sink_info, read field by field -----------------------------------

    const val SINK_INFO_NAME = 0L
    const val SINK_INFO_INDEX = 8L
    const val SINK_INFO_DESCRIPTION = 16L

    // -- pa_server_info, for which sink is currently default -----------------

    const val SERVER_INFO_DEFAULT_SINK_NAME = 48L

    // -- sample formats ------------------------------------------------------

    const val SAMPLE_S16LE = 3
    const val SAMPLE_FLOAT32LE = 5

    // -- context state -------------------------------------------------------

    const val CONTEXT_READY = 4
    const val CONTEXT_FAILED = 5
    const val CONTEXT_TERMINATED = 6
    const val CONTEXT_NOFLAGS = 0

    // -- stream state --------------------------------------------------------

    const val STREAM_READY = 2
    const val STREAM_FAILED = 3
    const val STREAM_TERMINATED = 4

    // -- stream flags --------------------------------------------------------

    const val STREAM_START_CORKED = 1
    const val STREAM_INTERPOLATE_TIMING = 2
    const val STREAM_AUTO_TIMING_UPDATE = 8

    /**
     * The pair without which `pa_stream_get_time` answers `-PA_ERR_NODATA` for
     * the life of the stream, however often it is asked. AUTO_TIMING_UPDATE has
     * the server push timing blocks unprompted; INTERPOLATE_TIMING fills the
     * gaps between them locally, which is what makes reading the playhead cheap
     * enough to do per frame.
     */
    const val STREAM_TIMING_FLAGS = STREAM_INTERPOLATE_TIMING or STREAM_AUTO_TIMING_UPDATE

    // -- misc ----------------------------------------------------------------

    const val SEEK_RELATIVE = 0

    /** `pa_stream_writable_size` returns this on failure. */
    const val SIZE_ERROR = -1L

    const val ERR_NODATA = 16

    // -- subscription --------------------------------------------------------

    const val SUBSCRIPTION_MASK_SINK = 0x0001
    const val SUBSCRIPTION_MASK_SERVER = 0x0100

    /** Properties the stream carries so the desktop can name and route it. */
    const val PROP_APPLICATION_NAME = "application.name"
    const val PROP_APPLICATION_ID = "application.id"
    const val PROP_APPLICATION_ICON_NAME = "application.icon_name"
    const val PROP_MEDIA_ROLE = "media.role"
}
