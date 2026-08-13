/*
 * Layout and constant oracle for the MediaPlayer subset libsound binds.
 *
 * Not part of the build. It runs on a macOS machine -- in practice the CI
 * runner -- and its output is transcribed into the Kotlin ABI table.
 *
 *   clang -o mpnowplaying-oracle mpnowplaying-oracle.m \
 *       -framework Foundation -framework MediaPlayer
 *
 * ## Why an Objective-C binding needs an oracle at all
 *
 * Most of this API is reached by name: a class by its name, a method by its
 * selector, a dictionary key by a global NSString. Those are strings, and a
 * wrong one fails loudly at the point of use, which is the pleasant case.
 *
 * The block is the unpleasant one. `addTargetWithHandler:` takes an Objective-C
 * block, and a block is a struct with an ABI: an isa pointer, flags, a function
 * pointer and a descriptor. Nothing checks it. Hand the runtime a struct whose
 * invoke pointer sits at the wrong offset and it calls whatever is there, which
 * is the same failure as a wrong vtable slot and is why this file exists.
 */

#import <Foundation/Foundation.h>
#import <MediaPlayer/MediaPlayer.h>
#include <objc/runtime.h>
#include <stddef.h>
#include <stdio.h>

#define SECTION(name) printf("\n== %s ==\n", name)
#define P(expr) printf("  %-54s = %lld\n", #expr, (long long)(expr))

/* The shape libclang emits for every block, from the ABI documentation the
 * compiler itself implements. Declared here so offsetof can be taken of it and
 * checked against what the compiler actually produces below. */
struct BlockLiteral {
    void *isa;
    int flags;
    int reserved;
    void (*invoke)(void *, ...);
    struct BlockDescriptor *descriptor;
};

struct BlockDescriptor {
    unsigned long reserved;
    unsigned long size;
};

extern void *_NSConcreteGlobalBlock[32];

int main(void) {
    @autoreleasepool {
        printf("MediaPlayer oracle -- macOS, %s\n", sizeof(void *) == 8 ? "64-bit" : "32-bit");

        SECTION("block literal (we synthesise one of these)");
        P(offsetof(struct BlockLiteral, isa));
        P(offsetof(struct BlockLiteral, flags));
        P(offsetof(struct BlockLiteral, reserved));
        P(offsetof(struct BlockLiteral, invoke));
        P(offsetof(struct BlockLiteral, descriptor));
        P(sizeof(struct BlockLiteral));
        P(offsetof(struct BlockDescriptor, reserved));
        P(offsetof(struct BlockDescriptor, size));
        P(sizeof(struct BlockDescriptor));

        /* BLOCK_IS_GLOBAL. A global block is never copied or freed, which is
         * what a block owned by an arena for the life of a session must be:
         * anything else would have the runtime try to free memory we own. */
        SECTION("block flags");
        P(1 << 28);
        printf("  %-54s = %s\n", "_NSConcreteGlobalBlock resolves",
               (void *)_NSConcreteGlobalBlock ? "yes" : "NO");

        /* Cross-check: what the compiler emits for a real global block, so the
         * struct above is confirmed rather than assumed. */
        SECTION("a real block, as the compiler built it");
        MPRemoteCommandHandlerStatus (^handler)(MPRemoteCommandEvent *) =
            ^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent *event) {
                (void)event;
                return MPRemoteCommandHandlerStatusSuccess;
            };
        struct BlockLiteral *real = (__bridge struct BlockLiteral *)handler;
        printf("  %-54s = %s\n", "isa is _NSConcreteGlobalBlock",
               real->isa == (void *)_NSConcreteGlobalBlock ? "yes" : "no (stack block)");
        printf("  %-54s = 0x%08X\n", "flags the compiler set", (unsigned)real->flags);
        printf("  %-54s = %s\n", "invoke is non-null", real->invoke ? "yes" : "NO");
        printf("  %-54s = %lu\n", "descriptor->size", real->descriptor->size);

        SECTION("MPNowPlayingPlaybackState");
        P(MPNowPlayingPlaybackStateUnknown);
        P(MPNowPlayingPlaybackStatePlaying);
        P(MPNowPlayingPlaybackStatePaused);
        P(MPNowPlayingPlaybackStateStopped);
        P(MPNowPlayingPlaybackStateInterrupted);

        SECTION("MPRemoteCommandHandlerStatus");
        P(MPRemoteCommandHandlerStatusSuccess);
        P(MPRemoteCommandHandlerStatusNoSuchContent);
        P(MPRemoteCommandHandlerStatusCommandFailed);

        /* Dictionary keys are global NSStrings. The Kotlin side builds them from
         * their text, so what matters is that the text is what the framework
         * actually uses -- printed rather than assumed. */
        SECTION("nowPlayingInfo keys, as the framework spells them");
        printf("  %-54s = %s\n", "MPMediaItemPropertyTitle", MPMediaItemPropertyTitle.UTF8String);
        printf("  %-54s = %s\n", "MPMediaItemPropertyArtist", MPMediaItemPropertyArtist.UTF8String);
        printf("  %-54s = %s\n", "MPMediaItemPropertyAlbumTitle", MPMediaItemPropertyAlbumTitle.UTF8String);
        printf("  %-54s = %s\n", "MPMediaItemPropertyPlaybackDuration",
               MPMediaItemPropertyPlaybackDuration.UTF8String);
        printf("  %-54s = %s\n", "MPNowPlayingInfoPropertyElapsedPlaybackTime",
               MPNowPlayingInfoPropertyElapsedPlaybackTime.UTF8String);
        printf("  %-54s = %s\n", "MPNowPlayingInfoPropertyPlaybackRate",
               MPNowPlayingInfoPropertyPlaybackRate.UTF8String);

        SECTION("classes and selectors the binding sends");
        const char *classes[] = {
            "MPNowPlayingInfoCenter", "MPRemoteCommandCenter", "MPRemoteCommand",
            "NSMutableDictionary", "NSString", "NSNumber", NULL,
        };
        for (int i = 0; classes[i]; i++) {
            printf("  %-54s = %s\n", classes[i], objc_getClass(classes[i]) ? "found" : "MISSING");
        }
        const char *selectors[] = {
            "defaultCenter", "sharedCommandCenter", "nowPlayingInfo", "setNowPlayingInfo:",
            "setPlaybackState:", "playCommand", "pauseCommand", "stopCommand",
            "nextTrackCommand", "previousTrackCommand", "setEnabled:",
            "addTargetWithHandler:", "removeTarget:", "dictionary", "setObject:forKey:",
            "numberWithDouble:", "stringWithUTF8String:", NULL,
        };
        for (int i = 0; selectors[i]; i++) {
            printf("  %-54s = %s\n", selectors[i], sel_registerName(selectors[i]) ? "ok" : "MISSING");
        }

        return 0;
    }
}
