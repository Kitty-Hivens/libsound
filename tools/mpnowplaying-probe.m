/*
 * Can a plain process publish a Now Playing session on macOS?
 *
 * A behaviour question, like the SMTC one, and it decides whether the macOS
 * half of libsound-session is worth binding at all. MediaPlayer's
 * MPNowPlayingInfoCenter is documented for applications, and a JVM launched from
 * a shell is not an application in the sense the framework means: no bundle, no
 * Info.plist, no activation. Whether it refuses that, ignores it, or accepts it
 * is not something to remember.
 *
 * Written in Objective-C rather than C-with-objc_msgSend on purpose. The binding
 * will have to go through the runtime, but the question here is what the
 * framework does, and asking it in the language it was written in keeps the
 * answer about the framework rather than about my message sends.
 *
 *   clang -o mpnowplaying-probe mpnowplaying-probe.m \
 *       -framework Foundation -framework MediaPlayer
 *   ./mpnowplaying-probe
 *
 * Exit code says whether the question could be ASKED, not what the answer was.
 * "No" is a real answer and must not read as a broken probe.
 */

#import <Foundation/Foundation.h>
#import <MediaPlayer/MediaPlayer.h>
#include <stdio.h>

#define STEP(name) printf("\n-- %s\n", name)

int main(void) {
    @autoreleasepool {
        printf("MPNowPlayingInfoCenter probe -- a process with no bundle\n");
        printf("bundle identifier = %s\n",
               [[NSBundle mainBundle] bundleIdentifier].UTF8String ?: "(none)");

        STEP("the info centre");
        MPNowPlayingInfoCenter *centre = [MPNowPlayingInfoCenter defaultCenter];
        printf("   defaultCenter                    = %s\n", centre ? "yes" : "NO");
        if (!centre) {
            printf("\nVERDICT: NO -- the framework hands a process like this nothing.\n");
            return 0;
        }

        STEP("publishing something");
        centre.nowPlayingInfo = @{
            MPMediaItemPropertyTitle : @"Bus Stop",
            MPMediaItemPropertyArtist : @"libsound probe",
            MPMediaItemPropertyPlaybackDuration : @(214.0),
            MPNowPlayingInfoPropertyElapsedPlaybackTime : @(42.0),
            MPNowPlayingInfoPropertyPlaybackRate : @(1.0),
        };
        centre.playbackState = MPNowPlayingPlaybackStatePlaying;

        NSDictionary *readBack = centre.nowPlayingInfo;
        printf("   nowPlayingInfo accepted          = %s\n", readBack.count ? "yes" : "NO");
        printf("   title read back                  = %s\n",
               [readBack[MPMediaItemPropertyTitle] UTF8String] ?: "(nil)");
        printf("   playbackState read back          = %ld\n", (long)centre.playbackState);

        /* The half that matters more than the display: whether the media keys
         * reach a process like this. Without it the session is a picture rather
         * than a control surface. */
        STEP("remote commands");
        MPRemoteCommandCenter *commands = [MPRemoteCommandCenter sharedCommandCenter];
        printf("   sharedCommandCenter              = %s\n", commands ? "yes" : "NO");
        if (commands) {
            commands.playCommand.enabled = YES;
            commands.pauseCommand.enabled = YES;
            [commands.playCommand addTargetWithHandler:^MPRemoteCommandHandlerStatus(
                MPRemoteCommandEvent *event) {
                (void)event;
                return MPRemoteCommandHandlerStatusSuccess;
            }];
            printf("   play command enabled + handler   = yes\n");
        }

        printf("\nVERDICT: the API accepted everything asked of it.\n");
        printf("Whether the Now Playing widget SHOWS it, and whether a media key\n");
        printf("actually reaches the handler, needs a person at a real desktop --\n");
        printf("a CI runner has no user session to press one.\n");
        return 0;
    }
}
