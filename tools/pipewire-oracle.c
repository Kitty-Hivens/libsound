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
/* Metadata is an extension rather than core, and the default sink lives in it. */
#include <pipewire/extensions/metadata.h>
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
    /* The same table as a property value. audio.position takes names rather
     * than numbers, which is what creating a device with a layout needs. */
    printf("  as audio.position names:\n");
    for (size_t i = 0; i < SPA_N_ELEMENTS(CHANNELS); i++) {
        const char *spa_name =
            spa_debug_type_find_short_name(spa_type_audio_channel, CHANNELS[i].spa);
        printf("    %-5s -> \"%s\"\n", CHANNELS[i].ffmpeg, spa_name ? spa_name : "");
    }
    printf("  (pa_channel_position_t names 18 of them, which is the shim's ceiling)\n");

    /* The ceiling a format has to be refused above. accepts() promises that a
     * false answer is exactly an open that would throw, so it needs the number
     * rather than finding out from a connect that fails. */
    P(SPA_AUDIO_MAX_CHANNELS);

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
    /* What keeps a stream on the object it was aimed at. Recording one
     * application means naming its node, and a stream that reconnected when
     * that node went would quietly start recording something else, which is the
     * same trap PA_STREAM_DONT_MOVE answers on the other side. */
    HEX(PW_STREAM_FLAG_DONT_RECONNECT);
    P(PW_DIRECTION_INPUT);
    P(PW_DIRECTION_OUTPUT);
    P(PW_ID_ANY);

    SECTION("the events struct a stream hands us, which is a vtable by another name");
    P(PW_VERSION_STREAM_EVENTS);
    P(sizeof(struct pw_stream_events));
    /* Every field, not only the ones this library implements. The struct is
     * handed over whole and the library calls whatever is in it, so a slot left
     * out of the table is a slot filled with whatever follows the allocation.
     * The offsets are deliberately not contiguous in the printout below:
     * reading four of them and assuming the rest are eight apart is how a
     * synthesised struct ends up calling a callback through the wrong
     * signature. */
    P(offsetof(struct pw_stream_events, version));
    P(offsetof(struct pw_stream_events, destroy));
    P(offsetof(struct pw_stream_events, state_changed));
    P(offsetof(struct pw_stream_events, control_info));
    P(offsetof(struct pw_stream_events, io_changed));
    P(offsetof(struct pw_stream_events, param_changed));
    P(offsetof(struct pw_stream_events, add_buffer));
    P(offsetof(struct pw_stream_events, remove_buffer));
    P(offsetof(struct pw_stream_events, process));
    P(offsetof(struct pw_stream_events, drained));
    P(offsetof(struct pw_stream_events, command));
    P(offsetof(struct pw_stream_events, trigger_done));
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

    /* Where the playhead and the latency come from. queued is what this client
     * has handed over and not yet had played, delay is the graph's own share,
     * and the two together are what the contract calls the whole path. */
    SECTION("pw_time, which is the playhead and the latency");
    P(sizeof(struct pw_time));
    P(offsetof(struct pw_time, now));
    P(offsetof(struct pw_time, rate));
    P(offsetof(struct pw_time, ticks));
    P(offsetof(struct pw_time, delay));
    P(offsetof(struct pw_time, queued));
    P(offsetof(struct pw_time, buffered));
    P(offsetof(struct pw_time, queued_buffers));
    P(offsetof(struct pw_time, avail_buffers));
    P(sizeof(struct spa_fraction));
    P(offsetof(struct spa_fraction, num));
    P(offsetof(struct spa_fraction, denom));

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

    /* And a third, for the other direction. A device's own volume arrives as a
     * Props object the graph wrote, so this is the reference a reader is checked
     * against: the encoder above is proved by emitting these bytes, and the
     * decoder by pulling the same values back out of them. */
    SECTION("a reference POD: a Props object, which is what a volume is");
    {
        uint8_t storage[1024];
        struct spa_pod_builder builder = SPA_POD_BUILDER_INIT(storage, sizeof(storage));
        float volumes[2] = { 0.25f, 0.5f };
        const struct spa_pod *pod = spa_pod_builder_add_object(&builder,
            SPA_TYPE_OBJECT_Props, SPA_PARAM_Props,
            SPA_PROP_volume, SPA_POD_Float(0.25f),
            SPA_PROP_mute, SPA_POD_Bool(true),
            SPA_PROP_channelVolumes, SPA_POD_Array(sizeof(float), SPA_TYPE_Float, 2, volumes));
        dump("Props", pod, SPA_POD_SIZE(pod));
    }

    /* Properties are built from a dict rather than from pw_properties_new,
     * which is variadic: a Panama downcall to a variadic function needs a
     * descriptor per call shape, and a dict needs none. */
    SECTION("spa_dict, which is how properties are handed over without varargs");
    P(sizeof(struct spa_dict));
    P(offsetof(struct spa_dict, flags));
    P(offsetof(struct spa_dict, n_items));
    P(offsetof(struct spa_dict, items));
    P(sizeof(struct spa_dict_item));
    P(offsetof(struct spa_dict_item, key));
    P(offsetof(struct spa_dict_item, value));

    /* The registry, which is how a client finds out what is on the graph. Every
     * global arrives as an id, a type string and a property dict, and a device
     * list is that stream filtered by media.class. The events struct is a
     * vtable like pw_stream's and has the same rule: handed over whole, so the
     * size is as load bearing as the offsets. */
    SECTION("registry and proxies");
    P(PW_VERSION_REGISTRY);
    P(PW_VERSION_REGISTRY_EVENTS);
    P(sizeof(struct pw_registry_events));
    P(offsetof(struct pw_registry_events, version));
    P(offsetof(struct pw_registry_events, global));
    P(offsetof(struct pw_registry_events, global_remove));
    P(PW_VERSION_CORE);
    printf("  %-44s = %s\n", "PW_TYPE_INTERFACE_Node", PW_TYPE_INTERFACE_Node);
    printf("  %-44s = %s\n", "PW_TYPE_INTERFACE_Device", PW_TYPE_INTERFACE_Device);
    printf("  %-44s = %s\n", "PW_TYPE_INTERFACE_Metadata", PW_TYPE_INTERFACE_Metadata);
    /* What the registry proxy declares itself to be, which is the sanity check a
     * walk of its method table can make before calling through it. */
    printf("  %-44s = %s\n", "PW_TYPE_INTERFACE_Registry", PW_TYPE_INTERFACE_Registry);

    /* Calling a method on a proxy, which the headers do through macros. A proxy
     * pointer is cast straight to a spa_interface, whose cb.funcs points at the
     * interface's method table, and the macro reads a function out of it. Panama
     * cannot call the macro, so the layout is what a binding walks by hand: the
     * same discipline the WASAPI vtable indices are held to.
     *
     * Not used yet. The device list needs no method call at all, because a
     * registry global arrives with its whole property dict attached. Binding a
     * global does, and binding is what the default device and a node's volume
     * are behind, so the numbers are printed now and the code that needs them
     * is a named follow-up rather than a guess later. */
    SECTION("calling a proxy method by hand");
    P(sizeof(struct spa_interface));
    P(offsetof(struct spa_interface, type));
    P(offsetof(struct spa_interface, version));
    P(offsetof(struct spa_interface, cb));
    P(sizeof(struct spa_callbacks));
    P(offsetof(struct spa_callbacks, funcs));
    P(offsetof(struct spa_callbacks, data));
    P(sizeof(struct pw_registry_methods));
    P(offsetof(struct pw_registry_methods, version));
    P(offsetof(struct pw_registry_methods, add_listener));
    P(offsetof(struct pw_registry_methods, bind));
    P(offsetof(struct pw_registry_methods, destroy));
    P(PW_VERSION_REGISTRY_METHODS);
    P(sizeof(struct pw_core_methods));
    P(offsetof(struct pw_core_methods, version));
    P(offsetof(struct pw_core_methods, add_listener));
    P(offsetof(struct pw_core_methods, hello));
    P(offsetof(struct pw_core_methods, sync));
    P(offsetof(struct pw_core_methods, pong));
    P(offsetof(struct pw_core_methods, error));
    P(offsetof(struct pw_core_methods, get_registry));
    P(offsetof(struct pw_core_methods, create_object));
    P(offsetof(struct pw_core_methods, destroy));
    P(PW_VERSION_CORE_METHODS);

    /* The core's own events, which is where a round trip comes back. A connect
     * has two things to wait for and no clock worth waiting on: the burst of
     * globals the registry sends, and the properties of anything bound out of
     * it. Both are answered by sync, whose done arrives after everything queued
     * before it, so this is what replaces a sleep with a barrier.
     *
     * Measured rather than assumed: pw_proxy_add_object_listener does deliver
     * these on a core, which is not obvious from the header, because the core
     * also carries a listener list of its own that pw_core_add_listener
     * reaches. Both the listener and a sync called by walking the method table
     * were confirmed against a live graph before these numbers were used. */
    SECTION("the core's events, which is where a round trip comes back");
    P(PW_VERSION_CORE_EVENTS);
    P(sizeof(struct pw_core_events));
    P(offsetof(struct pw_core_events, version));
    P(offsetof(struct pw_core_events, info));
    P(offsetof(struct pw_core_events, done));
    P(offsetof(struct pw_core_events, ping));
    P(offsetof(struct pw_core_events, error));
    P(offsetof(struct pw_core_events, remove_id));
    P(offsetof(struct pw_core_events, bound_id));
    P(offsetof(struct pw_core_events, add_mem));
    P(offsetof(struct pw_core_events, remove_mem));
    P(offsetof(struct pw_core_events, bound_props));
    P(PW_ID_CORE);

    /* The default sink and source are not a property of the graph: they are a
     * value the session manager writes into a metadata object, so reading them
     * means binding that object and listening to it. */
    SECTION("metadata, which is where the default device lives");
    P(PW_VERSION_METADATA);
    P(PW_VERSION_METADATA_EVENTS);
    P(sizeof(struct pw_metadata_methods));
    P(offsetof(struct pw_metadata_methods, version));
    P(offsetof(struct pw_metadata_methods, add_listener));
    P(offsetof(struct pw_metadata_methods, set_property));
    P(offsetof(struct pw_metadata_methods, clear));
    P(PW_VERSION_METADATA_METHODS);
    P(sizeof(struct pw_metadata_events));
    P(offsetof(struct pw_metadata_events, version));
    P(offsetof(struct pw_metadata_events, property));
    printf("  %-44s = %s\n", "PW_KEY_METADATA_NAME", PW_KEY_METADATA_NAME);

    /* A device's own volume is a parameter of its node, which means binding
     * the node, subscribing to the parameter, and reading the object that comes
     * back. So this is the one place a binding needs to read a POD rather than
     * write one: everything else here is a format or a latency this library
     * sends. */
    SECTION("the node, whose parameters carry a device's own volume");
    P(PW_VERSION_NODE);
    P(PW_VERSION_NODE_EVENTS);
    P(sizeof(struct pw_node_events));
    P(offsetof(struct pw_node_events, version));
    P(offsetof(struct pw_node_events, info));
    P(offsetof(struct pw_node_events, param));
    P(sizeof(struct pw_node_methods));
    P(offsetof(struct pw_node_methods, version));
    P(offsetof(struct pw_node_methods, add_listener));
    P(offsetof(struct pw_node_methods, subscribe_params));
    P(offsetof(struct pw_node_methods, enum_params));
    P(offsetof(struct pw_node_methods, set_param));
    P(offsetof(struct pw_node_methods, send_command));
    P(PW_VERSION_NODE_METHODS);

    /* The info event carries whether the node is suspended, which is the other
     * thing a device row on the libpulse side reports and this one could not. */
    P(sizeof(struct pw_node_info));
    P(offsetof(struct pw_node_info, id));
    P(offsetof(struct pw_node_info, change_mask));
    P(offsetof(struct pw_node_info, state));
    P(offsetof(struct pw_node_info, props));
    /* Which fields of the info struct the event actually refreshed. The props
     * pointer is only good when its bit is set, and the props on the info are a
     * larger set than the ones the registry global carries: an application's
     * process id is on one and not the other. */
    HEX(PW_NODE_CHANGE_MASK_PROPS);
    HEX(PW_NODE_CHANGE_MASK_STATE);
    P(PW_NODE_STATE_ERROR);
    P(PW_NODE_STATE_CREATING);
    P(PW_NODE_STATE_SUSPENDED);
    P(PW_NODE_STATE_IDLE);
    P(PW_NODE_STATE_RUNNING);

    /* Reading a POD needs the array body's shape as well as the header's: an
     * array is a child size and a child type before the elements, which is what
     * makes a six element array of ids thirty two bytes rather than twenty
     * four. Writing one already depends on this and gets it checked by the
     * reference dumps above; reading one depends on it in the other direction. */
    SECTION("POD array and struct bodies, which reading one needs");
    P(sizeof(struct spa_pod_array));
    P(offsetof(struct spa_pod_array, body));
    P(sizeof(struct spa_pod_array_body));
    P(offsetof(struct spa_pod_array_body, child));
    P(sizeof(struct spa_pod_bool));
    P(sizeof(struct spa_pod_float));

    /* A link, which is how a mixer finds out which device a stream is playing
     * to. Nothing else on the graph says: a stream names a target only when it
     * asked for one, and most do not. */
    SECTION("links, which is what a stream playing to a device is");
    printf("  %-44s = %s\n", "PW_TYPE_INTERFACE_Link", PW_TYPE_INTERFACE_Link);
    printf("  %-30s = %s\n", "PW_KEY_LINK_OUTPUT_NODE", PW_KEY_LINK_OUTPUT_NODE);
    printf("  %-30s = %s\n", "PW_KEY_LINK_INPUT_NODE", PW_KEY_LINK_INPUT_NODE);
    printf("  %-30s = %s\n", "PW_KEY_MEDIA_NAME", PW_KEY_MEDIA_NAME);
    printf("  %-30s = %s\n", "PW_KEY_CLIENT_ID", PW_KEY_CLIENT_ID);
    /* Which process a node belongs to, which is how a mixer marks its own rows
     * without needing to know its own client id on a second connection. */
    printf("  %-30s = %s\n", "PW_KEY_APP_PROCESS_ID", PW_KEY_APP_PROCESS_ID);

    /* Making a device that is not hardware. The core's create_object takes a
     * factory by name and a property set, and which factory is the adapter's
     * business rather than the core's: support.null-audio-sink is named in the
     * daemon's own shipped configuration. object.linger decides whether what
     * comes back outlives the connection that asked for it. */
    printf("  %-30s = %s\n", "PW_KEY_FACTORY_NAME", PW_KEY_FACTORY_NAME);
    printf("  %-30s = %s\n", "PW_KEY_OBJECT_LINGER", PW_KEY_OBJECT_LINGER);
    printf("  %-30s = %s\n", "SPA_KEY_AUDIO_CHANNELS", SPA_KEY_AUDIO_CHANNELS);
    printf("  %-30s = %s\n", "SPA_KEY_AUDIO_POSITION", SPA_KEY_AUDIO_POSITION);
    printf("  %-30s = %s\n", "MEDIA_CLASS Stream/Input/Audio", "Stream/Input/Audio");

    /* A stream's own volume, which is a control on its node rather than
     * anything pw_stream carries directly. */
    SECTION("SPA_PROP, which is what a volume is");
    P(SPA_TYPE_OBJECT_Props);
    P(SPA_PARAM_Props);
    P(SPA_PROP_volume);
    P(SPA_PROP_mute);
    P(SPA_PROP_channelVolumes);
    P(SPA_PROP_channelMap);

    SECTION("the property keys a node is named and placed by");
    printf("  %-30s = %s\n", "PW_KEY_MEDIA_TYPE", PW_KEY_MEDIA_TYPE);
    printf("  %-30s = %s\n", "PW_KEY_MEDIA_CATEGORY", PW_KEY_MEDIA_CATEGORY);
    printf("  %-30s = %s\n", "PW_KEY_MEDIA_ROLE", PW_KEY_MEDIA_ROLE);
    printf("  %-30s = %s\n", "PW_KEY_APP_NAME", PW_KEY_APP_NAME);
    printf("  %-30s = %s\n", "PW_KEY_APP_ID", PW_KEY_APP_ID);
    printf("  %-30s = %s\n", "PW_KEY_APP_ICON_NAME", PW_KEY_APP_ICON_NAME);
    printf("  %-30s = %s\n", "PW_KEY_APP_PROCESS_BINARY", PW_KEY_APP_PROCESS_BINARY);
    printf("  %-30s = %s\n", "PW_KEY_NODE_NAME", PW_KEY_NODE_NAME);
    printf("  %-30s = %s\n", "PW_KEY_NODE_DESCRIPTION", PW_KEY_NODE_DESCRIPTION);
    /* The lever section 4.4 measured pipewire-pulse overwriting. */
    printf("  %-30s = %s\n", "PW_KEY_NODE_LATENCY", PW_KEY_NODE_LATENCY);
    printf("  %-30s = %s\n", "PW_KEY_NODE_RATE", PW_KEY_NODE_RATE);
    printf("  %-30s = %s\n", "PW_KEY_NODE_AUTOCONNECT", PW_KEY_NODE_AUTOCONNECT);
    printf("  %-30s = %s\n", "PW_KEY_TARGET_OBJECT", PW_KEY_TARGET_OBJECT);
    printf("  %-30s = %s\n", "PW_KEY_STREAM_CAPTURE_SINK", PW_KEY_STREAM_CAPTURE_SINK);
    printf("  %-30s = %s\n", "PW_KEY_NODE_DONT_RECONNECT", PW_KEY_NODE_DONT_RECONNECT);
    /* What a Stream node calls itself, which is what a per-application capture
     * has to find in the registry before it can aim at one. */
    printf("  %-30s = %s\n", "MEDIA_CLASS Stream/Output/Audio", "Stream/Output/Audio");
    printf("  %-30s = %s\n", "MEDIA_CLASS Stream/Input/Audio", "Stream/Input/Audio");
    /* What a registry global says it is, which is the whole of a device list:
     * Audio/Sink and Audio/Source are devices, Stream/Output/Audio and its
     * sibling are somebody playing. */
    printf("  %-30s = %s\n", "PW_KEY_MEDIA_CLASS", PW_KEY_MEDIA_CLASS);
    printf("  %-30s = %s\n", "PW_KEY_OBJECT_SERIAL", PW_KEY_OBJECT_SERIAL);
    printf("  %-30s = %s\n", "PW_KEY_NODE_NICK", PW_KEY_NODE_NICK);
    printf("  %-30s = %s\n", "PW_KEY_DEVICE_DESCRIPTION", PW_KEY_DEVICE_DESCRIPTION);

    return 0;
}
