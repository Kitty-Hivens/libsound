/*
 * What FFmpeg calls each channel layout, and which one it picks for a bare
 * channel count.
 *
 * Not part of the build. libsound has no dependency on FFmpeg and gains none
 * from this: the decoder on the other side of the seam has one, sends layout
 * names FFmpeg chose, and this is where those names and their decompositions
 * are read from rather than remembered.
 *
 *   gcc -o ffmpeg-layout-oracle ffmpeg-layout-oracle.c $(pkg-config --cflags --libs libavutil)
 *   ./ffmpeg-layout-oracle
 *
 * `ffmpeg -layouts` prints the same standard layouts, and not the defaults: a
 * stream that declares no layout arrives as a channel count, and which layout
 * that means is a decision inside libavutil rather than anything printed.
 */

#include <libavutil/channel_layout.h>
#include <libavutil/avutil.h>
#include <stdio.h>
#include <string.h>

int main(void)
{
    unsigned version = avutil_version();
    printf("libavutil %u.%u.%u\n\n",
           version >> 16, (version >> 8) & 0xFF, version & 0xFF);

    printf("== default layout per channel count ==\n");
    for (int channels = 1; channels <= 24; channels++) {
        AVChannelLayout layout;
        char name[256];
        char decomposition[512];
        av_channel_layout_default(&layout, channels);
        if (av_channel_layout_describe(&layout, name, sizeof name) < 0) continue;

        decomposition[0] = '\0';
        for (int index = 0; index < layout.nb_channels; index++) {
            char channel[64];
            enum AVChannel id = av_channel_layout_channel_from_index(&layout, index);
            if (av_channel_name(channel, sizeof channel, id) < 0) continue;
            if (index) strncat(decomposition, "+", sizeof decomposition - strlen(decomposition) - 1);
            strncat(decomposition, channel, sizeof decomposition - strlen(decomposition) - 1);
        }
        printf("  %2d  %-16s %s\n", channels, name, decomposition);
        av_channel_layout_uninit(&layout);
    }
    return 0;
}
