/*
 * Oracle for a native PipeWire backend.
 *
 * Not part of the build. Run it against the headers of the pipewire the target
 * systems carry, and transcribe its output into the Kotlin ABI table.
 *
 *   gcc -o pipewire-oracle pipewire-oracle.c $(pkg-config --cflags --libs libpipewire-0.3)
 *   ./pipewire-oracle
 *
 * Three questions, and the third is the one that decides whether the backend is
 * writable at all.
 *
 * What the compatibility layer costs. Everything this library sends today goes
 * through pipewire-pulse, so what it can say is limited to what the PulseAudio
 * protocol can say: pa_sample_format_t has no 64-bit float, and
 * pa_channel_position_t names eighteen of the thirty-six positions a decoder
 * can hand over. Those refusals are the shim's rather than the graph's, and the
 * counts below are how much of that a native path would give back.
 *
 * What a node can ask the graph for. node.latency in a stream's properties is
 * overwritten by pipewire-pulse, which computes it from the pulse request. A
 * native client sets it and keeps it.
 *
 * And the POD. PipeWire negotiates formats with serialised objects built by
 * inline C macros, which Panama cannot call: a binding has to emit the bytes
 * itself. So this prints the layout the bytes have to follow and then dumps a
 * real one, built by the library's own builder, for a Kotlin implementation to
 * be compared against byte for byte.
 */

#include <pipewire/pipewire.h>
#include <spa/param/audio/format-utils.h>
#include <spa/param/audio/raw.h>
#include <spa/param/latency-utils.h>
#include <spa/debug/types.h>
#include <spa/pod/builder.h>
#include <stddef.h>
#include <stdio.h>
#include <string.h>

#define SECTION(name) printf("\n== %s ==\n", name)
#define P(expr) printf("%-46s = %lld\n", #expr, (long long)(expr))
#define HEX(expr) printf("%-46s = 0x%08X\n", #expr, (unsigned)(expr))

/* Every channel position a decoder can hand this library, by the name FFmpeg
 * prints for it, beside the SPA constant that carries it. A row with no
 * constant is a position a native backend still could not place, and the point
 * of the list is that today eighteen of them have no pa_channel_position_t
 * either. */
static const struct {
    const char *ffmpeg;
    enum spa_audio_channel spa;
} CHANNELS[] = {
    { "FL",   SPA_AUDIO_CHANNEL_FL },
    { "FR",   SPA_AUDIO_CHANNEL_FR },
    { "FC",   SPA_AUDIO_CHANNEL_FC },
    { "LFE",  SPA_AUDIO_CHANNEL_LFE },
    { "BL",   SPA_AUDIO_CHANNEL_RL },
    { "BR",   SPA_AUDIO_CHANNEL_RR },
    { "FLC",  SPA_AUDIO_CHANNEL_FLC },
    { "FRC",  SPA_AUDIO_CHANNEL_FRC },
    { "BC",   SPA_AUDIO_CHANNEL_RC },
    { "SL",   SPA_AUDIO_CHANNEL_SL },
    { "SR",   SPA_AUDIO_CHANNEL_SR },
    { "TC",   SPA_AUDIO_CHANNEL_TC },
    { "TFL",  SPA_AUDIO_CHANNEL_TFL },
    { "TFC",  SPA_AUDIO_CHANNEL_TFC },
    { "TFR",  SPA_AUDIO_CHANNEL_TFR },
    { "TBL",  SPA_AUDIO_CHANNEL_TRL },
    { "TBC",  SPA_AUDIO_CHANNEL_TRC },
    { "TBR",  SPA_AUDIO_CHANNEL_TRR },
    /* From here down is what the pulse layer has no name for at all. */
    { "WL",   SPA_AUDIO_CHANNEL_FLW },
    { "WR",   SPA_AUDIO_CHANNEL_FRW },
    { "LFE2", SPA_AUDIO_CHANNEL_LFE2 },
    { "TSL",  SPA_AUDIO_CHANNEL_TSL },
    { "TSR",  SPA_AUDIO_CHANNEL_TSR },
    { "BFC",  SPA_AUDIO_CHANNEL_BC },
    { "BFL",  SPA_AUDIO_CHANNEL_BLC },
    { "BFR",  SPA_AUDIO_CHANNEL_BRC },
};

static void dump(const char *label, const void *data, size_t size) {
    const unsigned char *bytes = data;
    printf("%s, %zu bytes:\n", label, size);
    for (size_t i = 0; i < size; i++) {
        if (i % 16 == 0) printf("  %04zx  ", i);
        printf("%02x ", bytes[i]);
        if (i % 16 == 15) printf("\n");
    }
    if (size % 16) printf("\n");
}

int main(void) {
    pw_init(NULL, NULL);
    printf("pipewire headers: %s\n", pw_get_headers_version());
    printf("pipewire library: %s\n", pw_get_library_version());

    SECTION("what the compatibility layer costs, in channel positions");
    int named = 0;
    for (size_t i = 0; i < SPA_N_ELEMENTS(CHANNELS); i++) {
        const char *spa_name =
            spa_debug_type_find_short_name(spa_type_audio_channel, CHANNELS[i].spa);
        printf("  %-5s -> %-6s = %d\n", CHANNELS[i].ffmpeg,
               spa_name ? spa_name : "(none)", (int)CHANNELS[i].spa);
        if (spa_name) named++;
    }
    printf("  %d of the 36 positions FFmpeg names have a SPA constant here\n", named);
    printf("  (pa_channel_position_t names 18 of them, which is the shim's ceiling)\n");

    SECTION("sample formats, including the one the pulse protocol has no name for");
    P(SPA_AUDIO_FORMAT_U8);
    P(SPA_AUDIO_FORMAT_S16_LE);
    P(SPA_AUDIO_FORMAT_S24_LE);
    P(SPA_AUDIO_FORMAT_S24_32_LE);
    P(SPA_AUDIO_FORMAT_S32_LE);
    P(SPA_AUDIO_FORMAT_F32_LE);
    P(SPA_AUDIO_FORMAT_F64_LE);
    P(SPA_AUDIO_FORMAT_UNKNOWN);

    SECTION("stream, its states and its flags");
    P(PW_STREAM_STATE_ERROR);
    P(PW_STREAM_STATE_UNCONNECTED);
    P(PW_STREAM_STATE_CONNECTING);
    P(PW_STREAM_STATE_PAUSED);
    P(PW_STREAM_STATE_STREAMING);
    HEX(PW_STREAM_FLAG_AUTOCONNECT);
    HEX(PW_STREAM_FLAG_MAP_BUFFERS);
    HEX(PW_STREAM_FLAG_RT_PROCESS);
    HEX(PW_STREAM_FLAG_INACTIVE);
    P(PW_DIRECTION_INPUT);
    P(PW_DIRECTION_OUTPUT);
    P(PW_ID_ANY);

    SECTION("the events struct a stream hands us, which is a vtable by another name");
    P(PW_VERSION_STREAM_EVENTS);
    P(sizeof(struct pw_stream_events));
    P(offsetof(struct pw_stream_events, version));
    P(offsetof(struct pw_stream_events, destroy));
    P(offsetof(struct pw_stream_events, state_changed));
    P(offsetof(struct pw_stream_events, param_changed));
    P(offsetof(struct pw_stream_events, process));
    P(offsetof(struct pw_stream_events, drained));
    P(sizeof(struct spa_hook));

    SECTION("buffers, which is where the audio actually is");
    P(sizeof(struct pw_buffer));
    P(offsetof(struct pw_buffer, buffer));
    P(offsetof(struct pw_buffer, user_data));
    P(offsetof(struct pw_buffer, size));
    P(offsetof(struct pw_buffer, requested));
    P(sizeof(struct spa_buffer));
    P(offsetof(struct spa_buffer, n_datas));
    P(offsetof(struct spa_buffer, datas));
    P(sizeof(struct spa_data));
    P(offsetof(struct spa_data, type));
    P(offsetof(struct spa_data, maxsize));
    P(offsetof(struct spa_data, data));
    P(offsetof(struct spa_data, chunk));
    P(sizeof(struct spa_chunk));
    P(offsetof(struct spa_chunk, offset));
    P(offsetof(struct spa_chunk, size));
    P(offsetof(struct spa_chunk, stride));

    SECTION("POD layout, which a Panama binding has to emit by hand");
    P(sizeof(struct spa_pod));
    P(offsetof(struct spa_pod, size));
    P(offsetof(struct spa_pod, type));
    P(sizeof(struct spa_pod_object));
    P(offsetof(struct spa_pod_object, body));
    P(sizeof(struct spa_pod_object_body));
    P(offsetof(struct spa_pod_object_body, type));
    P(offsetof(struct spa_pod_object_body, id));
    P(sizeof(struct spa_pod_prop));
    P(offsetof(struct spa_pod_prop, key));
    P(offsetof(struct spa_pod_prop, flags));
    P(offsetof(struct spa_pod_prop, value));
    P(SPA_POD_ALIGN);

    SECTION("POD type and key constants");
    P(SPA_TYPE_None);
    P(SPA_TYPE_Bool);
    P(SPA_TYPE_Id);
    P(SPA_TYPE_Int);
    P(SPA_TYPE_Long);
    P(SPA_TYPE_Float);
    P(SPA_TYPE_String);
    P(SPA_TYPE_Bytes);
    P(SPA_TYPE_Array);
    P(SPA_TYPE_Object);
    P(SPA_TYPE_OBJECT_Format);
    P(SPA_TYPE_OBJECT_ParamLatency);
    P(SPA_PARAM_EnumFormat);
    P(SPA_PARAM_Format);
    P(SPA_PARAM_Latency);
    P(SPA_FORMAT_mediaType);
    P(SPA_FORMAT_mediaSubtype);
    P(SPA_FORMAT_AUDIO_format);
    P(SPA_FORMAT_AUDIO_rate);
    P(SPA_FORMAT_AUDIO_channels);
    P(SPA_FORMAT_AUDIO_position);
    P(SPA_MEDIA_TYPE_audio);
    P(SPA_MEDIA_SUBTYPE_raw);

    /* The latency object's own keys. Readable out of the reference dump below
     * by counting, which is exactly the kind of reading that is right until it
     * is not: a key is a small integer and a wrong one is a property the server
     * ignores rather than an error anybody sees. */
    SECTION("SPA_PARAM_LATENCY keys");
    P(SPA_PARAM_LATENCY_direction);
    P(SPA_PARAM_LATENCY_minQuantum);
    P(SPA_PARAM_LATENCY_maxQuantum);
    P(SPA_PARAM_LATENCY_minRate);
    P(SPA_PARAM_LATENCY_maxRate);
    P(SPA_PARAM_LATENCY_minNs);
    P(SPA_PARAM_LATENCY_maxNs);
    P(SPA_DIRECTION_INPUT);
    P(SPA_DIRECTION_OUTPUT);

    /* The artifact a Kotlin builder is checked against. Built by the library's
     * own builder from the shape the sink will ask for, then dumped: a binding
     * that emits the same bytes has got the encoding right, and one that does
     * not has a specific place to look. */
    SECTION("a reference POD: 5.1, S16LE, 48 kHz, positions named");
    {
        uint8_t storage[1024];
        struct spa_pod_builder builder = SPA_POD_BUILDER_INIT(storage, sizeof(storage));
        struct spa_audio_info_raw info;
        spa_zero(info);
        info.format = SPA_AUDIO_FORMAT_S16_LE;
        info.rate = 48000;
        info.channels = 6;
        info.position[0] = SPA_AUDIO_CHANNEL_FL;
        info.position[1] = SPA_AUDIO_CHANNEL_FR;
        info.position[2] = SPA_AUDIO_CHANNEL_FC;
        info.position[3] = SPA_AUDIO_CHANNEL_LFE;
        info.position[4] = SPA_AUDIO_CHANNEL_RL;
        info.position[5] = SPA_AUDIO_CHANNEL_RR;
        const struct spa_pod *pod = spa_format_audio_raw_build(&builder, SPA_PARAM_EnumFormat, &info);
        dump("EnumFormat", pod, SPA_POD_SIZE(pod));
    }

    SECTION("a reference POD: a latency request of 256 frames");
    {
        uint8_t storage[1024];
        struct spa_pod_builder builder = SPA_POD_BUILDER_INIT(storage, sizeof(storage));
        struct spa_latency_info latency;
        spa_zero(latency);
        latency.direction = SPA_DIRECTION_OUTPUT;
        latency.min_quantum = 1.0f;
        latency.max_quantum = 1.0f;
        const struct spa_pod *pod = spa_latency_build(&builder, SPA_PARAM_Latency, &latency);
        dump("ParamLatency", pod, SPA_POD_SIZE(pod));
    }

    SECTION("the property keys a node is named and placed by");
    printf("  %-30s = %s\n", "PW_KEY_MEDIA_TYPE", PW_KEY_MEDIA_TYPE);
    printf("  %-30s = %s\n", "PW_KEY_MEDIA_CATEGORY", PW_KEY_MEDIA_CATEGORY);
    printf("  %-30s = %s\n", "PW_KEY_MEDIA_ROLE", PW_KEY_MEDIA_ROLE);
    printf("  %-30s = %s\n", "PW_KEY_APP_NAME", PW_KEY_APP_NAME);
    printf("  %-30s = %s\n", "PW_KEY_APP_ID", PW_KEY_APP_ID);
    printf("  %-30s = %s\n", "PW_KEY_APP_ICON_NAME", PW_KEY_APP_ICON_NAME);
    printf("  %-30s = %s\n", "PW_KEY_NODE_NAME", PW_KEY_NODE_NAME);
    printf("  %-30s = %s\n", "PW_KEY_NODE_DESCRIPTION", PW_KEY_NODE_DESCRIPTION);
    /* The lever section 4.4 measured pipewire-pulse overwriting. */
    printf("  %-30s = %s\n", "PW_KEY_NODE_LATENCY", PW_KEY_NODE_LATENCY);
    printf("  %-30s = %s\n", "PW_KEY_NODE_RATE", PW_KEY_NODE_RATE);
    printf("  %-30s = %s\n", "PW_KEY_NODE_AUTOCONNECT", PW_KEY_NODE_AUTOCONNECT);
    printf("  %-30s = %s\n", "PW_KEY_TARGET_OBJECT", PW_KEY_TARGET_OBJECT);
    printf("  %-30s = %s\n", "PW_KEY_STREAM_CAPTURE_SINK", PW_KEY_STREAM_CAPTURE_SINK);

    return 0;
}
