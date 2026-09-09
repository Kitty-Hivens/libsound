/*
 * Offset oracle for the libpulse subset libsound binds.
 *
 * Not part of the build. Run it by hand against the headers of the libpulse
 * the target systems carry, and transcribe its output into the Kotlin ABI
 * table. libtray and libnotify guessed a struct size instead and wrote past
 * an arena on every call for two releases; this program is the alternative.
 *
 *   gcc -o pa-oracle pa-oracle.c $(pkg-config --cflags --libs libpulse)
 *   ./pa-oracle
 */

#include <pulse/pulseaudio.h>
#include <stddef.h>
#include <stdio.h>

#define P(expr) printf("%-46s = %lld\n", #expr, (long long)(expr))
#define SECTION(name) printf("\n== %s ==\n", name)

#define HEX(expr) printf("%-46s = 0x%04X\n", #expr, (unsigned)(expr))

int main(void) {
    printf("libpulse headers: %s\n", pa_get_headers_version());
    printf("libpulse runtime: %s\n", pa_get_library_version());

    SECTION("pa_sample_spec");
    P(offsetof(pa_sample_spec, format));
    P(offsetof(pa_sample_spec, rate));
    P(offsetof(pa_sample_spec, channels));
    P(sizeof(pa_sample_spec));

    SECTION("pa_buffer_attr");
    P(offsetof(pa_buffer_attr, maxlength));
    P(offsetof(pa_buffer_attr, tlength));
    P(offsetof(pa_buffer_attr, prebuf));
    P(offsetof(pa_buffer_attr, minreq));
    P(offsetof(pa_buffer_attr, fragsize));
    P(sizeof(pa_buffer_attr));

    SECTION("pa_cvolume");
    P(offsetof(pa_cvolume, channels));
    P(offsetof(pa_cvolume, values));
    P(sizeof(pa_cvolume));
    P(PA_CHANNELS_MAX);
    P(PA_VOLUME_MUTED);
    P(PA_VOLUME_NORM);

    SECTION("pa_timing_info");
    P(offsetof(pa_timing_info, timestamp));
    P(offsetof(pa_timing_info, synchronized_clocks));
    P(offsetof(pa_timing_info, sink_usec));
    P(offsetof(pa_timing_info, source_usec));
    P(offsetof(pa_timing_info, transport_usec));
    P(offsetof(pa_timing_info, playing));
    P(offsetof(pa_timing_info, write_index_corrupt));
    P(offsetof(pa_timing_info, write_index));
    P(offsetof(pa_timing_info, read_index_corrupt));
    P(offsetof(pa_timing_info, read_index));
    P(offsetof(pa_timing_info, configured_sink_usec));
    P(offsetof(pa_timing_info, configured_source_usec));
    P(offsetof(pa_timing_info, since_underrun));
    P(sizeof(pa_timing_info));

    SECTION("pa_sink_info (device enumeration, Phase 2)");
    P(offsetof(pa_sink_info, name));
    P(offsetof(pa_sink_info, index));
    P(offsetof(pa_sink_info, description));
    P(offsetof(pa_sink_info, sample_spec));
    P(offsetof(pa_sink_info, channel_map));
    P(offsetof(pa_sink_info, owner_module));
    P(offsetof(pa_sink_info, volume));
    P(offsetof(pa_sink_info, mute));
    P(offsetof(pa_sink_info, monitor_source));
    P(offsetof(pa_sink_info, flags));
    P(offsetof(pa_sink_info, proplist));
    P(offsetof(pa_sink_info, state));
    P(sizeof(pa_sink_info));

    /* Whether the session manager acts on media roles is a fact about the
     * desktop, not about this library, and the only evidence reachable over the
     * protocol is which modules the server loaded. */
    SECTION("pa_module_info (is role ducking loaded at all)");
    P(offsetof(pa_module_info, index));
    P(offsetof(pa_module_info, name));
    P(offsetof(pa_module_info, argument));
    P(offsetof(pa_module_info, n_used));
    P(offsetof(pa_module_info, proplist));
    P(sizeof(pa_module_info));

    SECTION("pa_server_info (which sink is default)");
    P(offsetof(pa_server_info, user_name));
    P(offsetof(pa_server_info, host_name));
    P(offsetof(pa_server_info, server_version));
    P(offsetof(pa_server_info, server_name));
    P(offsetof(pa_server_info, sample_spec));
    P(offsetof(pa_server_info, default_sink_name));
    P(offsetof(pa_server_info, default_source_name));
    P(sizeof(pa_server_info));

    SECTION("pa_sink_input_info (per-stream volume, Phase 2)");
    P(offsetof(pa_sink_input_info, index));
    P(offsetof(pa_sink_input_info, name));
    P(offsetof(pa_sink_input_info, client));
    P(offsetof(pa_sink_input_info, sink));
    P(offsetof(pa_sink_input_info, volume));
    P(offsetof(pa_sink_input_info, mute));
    P(offsetof(pa_sink_input_info, proplist));
    P(offsetof(pa_sink_input_info, corked));
    P(offsetof(pa_sink_input_info, has_volume));
    P(offsetof(pa_sink_input_info, volume_writable));
    P(sizeof(pa_sink_input_info));

    /* The capture half. pa_source_info is the microphone list, and
     * monitor_of_sink is what separates a real input from a sink's monitor:
     * offering "Monitor of Built-in Audio" as a microphone confuses everyone
     * who reads the list, and both are legitimate things to want. */
    SECTION("pa_source_info (capture enumeration)");
    P(offsetof(pa_source_info, name));
    P(offsetof(pa_source_info, index));
    P(offsetof(pa_source_info, description));
    P(offsetof(pa_source_info, volume));
    P(offsetof(pa_source_info, mute));
    P(offsetof(pa_source_info, monitor_of_sink));
    P(offsetof(pa_source_info, monitor_of_sink_name));
    P(offsetof(pa_source_info, proplist));
    P(offsetof(pa_source_info, state));
    P(offsetof(pa_source_info, card));
    P(offsetof(pa_source_info, n_ports));
    P(offsetof(pa_source_info, ports));
    P(offsetof(pa_source_info, active_port));
    P(sizeof(pa_source_info));

    SECTION("pa_source_output_info (somebody else's capture stream)");
    P(offsetof(pa_source_output_info, index));
    P(offsetof(pa_source_output_info, name));
    P(offsetof(pa_source_output_info, client));
    P(offsetof(pa_source_output_info, source));
    P(offsetof(pa_source_output_info, proplist));
    P(offsetof(pa_source_output_info, corked));
    P(offsetof(pa_source_output_info, volume));
    P(offsetof(pa_source_output_info, mute));
    P(offsetof(pa_source_output_info, has_volume));
    P(offsetof(pa_source_output_info, volume_writable));
    P(sizeof(pa_source_output_info));

    /* Which device is suspended, and which port it is playing out of. Both
     * live at the tail of the info structs, past everything already read. */
    SECTION("pa_sink_info, the device half");
    P(offsetof(pa_sink_info, volume));
    P(offsetof(pa_sink_info, mute));
    P(offsetof(pa_sink_info, state));
    P(offsetof(pa_sink_info, card));
    P(offsetof(pa_sink_info, n_ports));
    P(offsetof(pa_sink_info, ports));
    P(offsetof(pa_sink_info, active_port));

    SECTION("ports, one struct per direction with the same shape");
    P(offsetof(pa_sink_port_info, name));
    P(offsetof(pa_sink_port_info, description));
    P(offsetof(pa_sink_port_info, priority));
    P(offsetof(pa_sink_port_info, available));
    P(sizeof(pa_sink_port_info));
    P(offsetof(pa_source_port_info, name));
    P(offsetof(pa_source_port_info, description));
    P(offsetof(pa_source_port_info, priority));
    P(offsetof(pa_source_port_info, available));
    P(sizeof(pa_source_port_info));
    P(PA_PORT_AVAILABLE_UNKNOWN);
    P(PA_PORT_AVAILABLE_NO);
    P(PA_PORT_AVAILABLE_YES);

    /* Cards carry the profiles: headphones against speakers on one card, and
     * the bluetooth switch between high quality playback and the low quality
     * mode that has a microphone. profiles2 rather than profiles, which is
     * deprecated and carries no availability flag. */
    SECTION("pa_card_info and its profiles");
    P(offsetof(pa_card_info, index));
    P(offsetof(pa_card_info, name));
    P(offsetof(pa_card_info, driver));
    P(offsetof(pa_card_info, n_profiles));
    P(offsetof(pa_card_info, proplist));
    P(offsetof(pa_card_info, n_ports));
    P(offsetof(pa_card_info, ports));
    P(offsetof(pa_card_info, profiles2));
    P(offsetof(pa_card_info, active_profile2));
    P(sizeof(pa_card_info));
    P(offsetof(pa_card_profile_info2, name));
    P(offsetof(pa_card_profile_info2, description));
    P(offsetof(pa_card_profile_info2, n_sinks));
    P(offsetof(pa_card_profile_info2, n_sources));
    P(offsetof(pa_card_profile_info2, priority));
    P(offsetof(pa_card_profile_info2, available));
    P(sizeof(pa_card_profile_info2));

    SECTION("device state (is it suspended)");
    P(PA_SINK_INVALID_STATE);
    P(PA_SINK_RUNNING);
    P(PA_SINK_IDLE);
    P(PA_SINK_SUSPENDED);
    P(PA_SOURCE_RUNNING);
    P(PA_SOURCE_IDLE);
    P(PA_SOURCE_SUSPENDED);

    SECTION("pa_cvolume, read side");
    P(offsetof(pa_cvolume, channels));
    P(offsetof(pa_cvolume, values));

    SECTION("sample formats");
    P(PA_SAMPLE_S16LE);
    P(PA_SAMPLE_S16BE);
    P(PA_SAMPLE_FLOAT32LE);
    /* The rest of what a decoder actually sends. There is no 64-bit float in
     * this enum at all, which is the answer to whether the backend can take
     * one: it cannot, and it has to refuse rather than substitute. */
    P(PA_SAMPLE_U8);
    P(PA_SAMPLE_S32LE);
    P(PA_SAMPLE_S24LE);
    P(PA_SAMPLE_S24_32LE);
    P(PA_SAMPLE_INVALID);

    /* What each channel is, which a sample spec does not carry. A stream opened
     * with a null map gets PA_CHANNEL_MAP_DEFAULT, which is the ALSA order, and
     * that is not FFmpeg's: ALSA lays six channels out as front pair, rear
     * pair, centre, LFE, and a decoder hands them over as front pair, centre,
     * LFE, rear pair. Sending one where the other is expected puts a film's
     * dialogue in the rears, and nothing anywhere reports it.
     *
     * The default map for each count is printed beside the positions, because
     * the failure above is a fact about that default rather than about any one
     * constant. */
    SECTION("pa_channel_map");
    P(offsetof(pa_channel_map, channels));
    P(offsetof(pa_channel_map, map));
    P(sizeof(pa_channel_map));

    SECTION("pa_channel_position_t");
    P(PA_CHANNEL_POSITION_INVALID);
    P(PA_CHANNEL_POSITION_MONO);
    P(PA_CHANNEL_POSITION_FRONT_LEFT);
    P(PA_CHANNEL_POSITION_FRONT_RIGHT);
    P(PA_CHANNEL_POSITION_FRONT_CENTER);
    P(PA_CHANNEL_POSITION_REAR_CENTER);
    P(PA_CHANNEL_POSITION_REAR_LEFT);
    P(PA_CHANNEL_POSITION_REAR_RIGHT);
    P(PA_CHANNEL_POSITION_LFE);
    P(PA_CHANNEL_POSITION_FRONT_LEFT_OF_CENTER);
    P(PA_CHANNEL_POSITION_FRONT_RIGHT_OF_CENTER);
    P(PA_CHANNEL_POSITION_SIDE_LEFT);
    P(PA_CHANNEL_POSITION_SIDE_RIGHT);
    P(PA_CHANNEL_POSITION_TOP_CENTER);
    P(PA_CHANNEL_POSITION_TOP_FRONT_LEFT);
    P(PA_CHANNEL_POSITION_TOP_FRONT_RIGHT);
    P(PA_CHANNEL_POSITION_TOP_FRONT_CENTER);
    P(PA_CHANNEL_POSITION_TOP_REAR_LEFT);
    P(PA_CHANNEL_POSITION_TOP_REAR_RIGHT);
    P(PA_CHANNEL_POSITION_TOP_REAR_CENTER);
    P(PA_CHANNEL_POSITION_MAX);

    SECTION("what a null channel map means, per count");
    for (unsigned n = 1; n <= 8; n++) {
        pa_channel_map map;
        pa_channel_map_init_extend(&map, n, PA_CHANNEL_MAP_DEFAULT);
        printf("%2u channels ->", n);
        for (unsigned i = 0; i < map.channels; i++) {
            printf(" %s", pa_channel_position_to_string(map.map[i]));
        }
        printf("\n");
    }

    SECTION("context state");
    P(PA_CONTEXT_UNCONNECTED);
    P(PA_CONTEXT_CONNECTING);
    P(PA_CONTEXT_AUTHORIZING);
    P(PA_CONTEXT_SETTING_NAME);
    P(PA_CONTEXT_READY);
    P(PA_CONTEXT_FAILED);
    P(PA_CONTEXT_TERMINATED);
    P(PA_CONTEXT_NOFLAGS);
    P(PA_CONTEXT_NOAUTOSPAWN);

    SECTION("stream state");
    P(PA_STREAM_UNCONNECTED);
    P(PA_STREAM_CREATING);
    P(PA_STREAM_READY);
    P(PA_STREAM_FAILED);
    P(PA_STREAM_TERMINATED);

    SECTION("stream flags");
    P(PA_STREAM_NOFLAGS);
    P(PA_STREAM_START_CORKED);
    P(PA_STREAM_INTERPOLATE_TIMING);
    P(PA_STREAM_NOT_MONOTONIC);
    P(PA_STREAM_AUTO_TIMING_UPDATE);
    P(PA_STREAM_ADJUST_LATENCY);
    P(PA_STREAM_EARLY_REQUESTS);

    /* The subscription constants were transcribed from the header by hand and
     * the ABI table said so in a comment that claimed otherwise. One of them
     * was wrong once already -- MASK_SERVER is 0x0080, and 0x0100 is the
     * deprecated AUTOLOAD -- which is exactly why they belong here. */
    /* Peak metering is a recording stream on the sink's monitor source, aimed at
     * one sink input. Everything below is what that needs. */
    SECTION("peak metering");
    P(PA_STREAM_PEAK_DETECT);
    P(PA_STREAM_ADJUST_LATENCY);
    P(PA_STREAM_DONT_MOVE);
    P(offsetof(pa_sink_info, monitor_source));
    P(offsetof(pa_sink_info, monitor_source_name));
    P(PA_SAMPLE_FLOAT32LE);
    P(PA_INVALID_INDEX);

    SECTION("subscription");
    HEX(PA_SUBSCRIPTION_MASK_SINK);
    HEX(PA_SUBSCRIPTION_MASK_SINK_INPUT);
    HEX(PA_SUBSCRIPTION_MASK_SERVER);
    HEX(PA_SUBSCRIPTION_EVENT_FACILITY_MASK);
    HEX(PA_SUBSCRIPTION_EVENT_TYPE_MASK);
    HEX(PA_SUBSCRIPTION_EVENT_SINK_INPUT);
    HEX(PA_SUBSCRIPTION_EVENT_NEW);
    HEX(PA_SUBSCRIPTION_EVENT_CHANGE);
    HEX(PA_SUBSCRIPTION_EVENT_REMOVE);
    HEX(PA_SUBSCRIPTION_MASK_SOURCE);
    HEX(PA_SUBSCRIPTION_MASK_SOURCE_OUTPUT);
    HEX(PA_SUBSCRIPTION_MASK_CARD);
    HEX(PA_SUBSCRIPTION_MASK_MODULE);
    HEX(PA_SUBSCRIPTION_EVENT_SOURCE);
    HEX(PA_SUBSCRIPTION_EVENT_SOURCE_OUTPUT);
    HEX(PA_SUBSCRIPTION_EVENT_CARD);

    SECTION("error codes and sentinels");
    P(PA_ERR_NODATA);

    SECTION("misc");
    P(PA_SEEK_RELATIVE);
    P(PA_SEEK_ABSOLUTE);
    P(PA_SEEK_RELATIVE_ON_READ);
    P(PA_SEEK_RELATIVE_END);
    P(PA_INVALID_INDEX);
    P(PA_OPERATION_RUNNING);
    P(PA_OPERATION_DONE);
    P(PA_OPERATION_CANCELLED);

    return 0;
}
