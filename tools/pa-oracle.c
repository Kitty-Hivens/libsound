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

    SECTION("pa_cvolume, read side");
    P(offsetof(pa_cvolume, channels));
    P(offsetof(pa_cvolume, values));

    SECTION("sample formats");
    P(PA_SAMPLE_S16LE);
    P(PA_SAMPLE_S16BE);
    P(PA_SAMPLE_FLOAT32LE);

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
