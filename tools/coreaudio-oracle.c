/*
 * Offset oracle for the CoreAudio subset libsound binds.
 *
 * Not part of the build. It runs on a macOS machine -- in practice the CI
 * runner, since the maintainer has none -- and its output is transcribed into
 * the Kotlin ABI table. Nothing about the macOS backend is written from memory:
 * libtray and libnotify guessed a struct size and wrote past an arena on every
 * call for two releases, and the whole point of this file is that the same
 * class of defect cannot start here.
 *
 * The selector constants matter as much as the offsets. They are four-character
 * codes, so a wrong one is a plausible-looking integer that addresses a
 * different property rather than an error, and the failure surfaces as a
 * silently empty device list.
 *
 *   clang -o coreaudio-oracle coreaudio-oracle.c \
 *       -framework CoreAudio -framework AudioToolbox -framework CoreFoundation
 *   ./coreaudio-oracle
 */

#include <AudioToolbox/AudioToolbox.h>
#include <CoreAudio/CoreAudio.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>

#define P(expr) printf("%-52s = %lld\n", #expr, (long long)(expr))
#define SECTION(name) printf("\n== %s ==\n", name)

/* A four-character code, printed as both, because only one of the two is
 * checkable by eye and only the other one goes into the binding. */
static void fourcc(const char *name, UInt32 value) {
    char c[5] = {
        (char)((value >> 24) & 0xFF), (char)((value >> 16) & 0xFF),
        (char)((value >> 8) & 0xFF), (char)(value & 0xFF), 0,
    };
    for (int i = 0; i < 4; i++) {
        if (c[i] < 32 || c[i] > 126) c[i] = '.';
    }
    printf("%-52s = %10u  '%s'\n", name, (unsigned)value, c);
}

#define F(expr) fourcc(#expr, (UInt32)(expr))

int main(void) {
    printf("pointer size = %zu\n", sizeof(void *));

    SECTION("AudioStreamBasicDescription");
    P(offsetof(AudioStreamBasicDescription, mSampleRate));
    P(offsetof(AudioStreamBasicDescription, mFormatID));
    P(offsetof(AudioStreamBasicDescription, mFormatFlags));
    P(offsetof(AudioStreamBasicDescription, mBytesPerPacket));
    P(offsetof(AudioStreamBasicDescription, mFramesPerPacket));
    P(offsetof(AudioStreamBasicDescription, mBytesPerFrame));
    P(offsetof(AudioStreamBasicDescription, mChannelsPerFrame));
    P(offsetof(AudioStreamBasicDescription, mBitsPerChannel));
    P(offsetof(AudioStreamBasicDescription, mReserved));
    P(sizeof(AudioStreamBasicDescription));

    SECTION("AudioObjectPropertyAddress");
    P(offsetof(AudioObjectPropertyAddress, mSelector));
    P(offsetof(AudioObjectPropertyAddress, mScope));
    P(offsetof(AudioObjectPropertyAddress, mElement));
    P(sizeof(AudioObjectPropertyAddress));

    SECTION("AudioBuffer / AudioBufferList");
    P(offsetof(AudioBuffer, mNumberChannels));
    P(offsetof(AudioBuffer, mDataByteSize));
    P(offsetof(AudioBuffer, mData));
    P(sizeof(AudioBuffer));
    P(offsetof(AudioBufferList, mNumberBuffers));
    P(offsetof(AudioBufferList, mBuffers));
    P(sizeof(AudioBufferList));

    SECTION("AudioTimeStamp");
    P(offsetof(AudioTimeStamp, mSampleTime));
    P(offsetof(AudioTimeStamp, mHostTime));
    P(offsetof(AudioTimeStamp, mRateScalar));
    P(offsetof(AudioTimeStamp, mWordClockTime));
    P(offsetof(AudioTimeStamp, mSMPTETime));
    P(offsetof(AudioTimeStamp, mFlags));
    P(offsetof(AudioTimeStamp, mReserved));
    P(sizeof(AudioTimeStamp));

    SECTION("AudioComponentDescription");
    P(offsetof(AudioComponentDescription, componentType));
    P(offsetof(AudioComponentDescription, componentSubType));
    P(offsetof(AudioComponentDescription, componentManufacturer));
    P(offsetof(AudioComponentDescription, componentFlags));
    P(offsetof(AudioComponentDescription, componentFlagsMask));
    P(sizeof(AudioComponentDescription));

    SECTION("AURenderCallbackStruct");
    P(offsetof(AURenderCallbackStruct, inputProc));
    P(offsetof(AURenderCallbackStruct, inputProcRefCon));
    P(sizeof(AURenderCallbackStruct));

    SECTION("component selection");
    F(kAudioUnitType_Output);
    F(kAudioUnitSubType_DefaultOutput);
    F(kAudioUnitSubType_HALOutput);
    F(kAudioUnitManufacturer_Apple);

    SECTION("stream format");
    F(kAudioFormatLinearPCM);
    P(kAudioFormatFlagIsFloat);
    P(kAudioFormatFlagIsSignedInteger);
    P(kAudioFormatFlagIsPacked);
    P(kAudioFormatFlagIsNonInterleaved);
    P(kAudioFormatFlagIsBigEndian);
    P(kAudioFormatFlagsNativeEndian);

    SECTION("audio unit properties and scopes");
    P(kAudioUnitProperty_StreamFormat);
    P(kAudioUnitProperty_SetRenderCallback);
    P(kAudioUnitProperty_MaximumFramesPerSlice);
    P(kAudioUnitProperty_Latency);
    P(kAudioOutputUnitProperty_CurrentDevice);
    P(kAudioOutputUnitProperty_EnableIO);
    P(kAudioUnitScope_Global);
    P(kAudioUnitScope_Input);
    P(kAudioUnitScope_Output);
    P(kHALOutputParam_Volume);

    SECTION("render action flags");
    P(kAudioUnitRenderAction_OutputIsSilence);

    SECTION("hardware objects and selectors");
    P(kAudioObjectSystemObject);
    F(kAudioHardwarePropertyDevices);
    F(kAudioHardwarePropertyDefaultOutputDevice);
    F(kAudioObjectPropertyName);
    F(kAudioObjectPropertyScopeGlobal);
    F(kAudioObjectPropertyScopeOutput);
    P(kAudioObjectPropertyElementMain);
    F(kAudioDevicePropertyDeviceUID);
    F(kAudioDevicePropertyStreamConfiguration);
    F(kAudioDevicePropertyBufferFrameSize);
    F(kAudioDevicePropertyLatency);
    F(kAudioDevicePropertySafetyOffset);
    F(kAudioDevicePropertyVolumeScalar);
    F(kAudioDevicePropertyNominalSampleRate);

    SECTION("status codes worth naming");
    P(noErr);
    P(kAudioHardwareNoError);
    P(kAudioHardwareBadObjectError);
    P(kAudioHardwareUnknownPropertyError);
    P(kAudioUnitErr_InvalidProperty);
    P(kAudioUnitErr_FormatNotSupported);

    /* Not a layout question, but the one fact that decides whether the contract
     * suite can run on a CI runner at all, and it has to come from the machine
     * rather than from a guess about what a virtual runner provides. */
    SECTION("what this machine actually has");
    AudioObjectPropertyAddress addr = {
        kAudioHardwarePropertyDevices,
        kAudioObjectPropertyScopeGlobal,
        kAudioObjectPropertyElementMain,
    };
    UInt32 size = 0;
    OSStatus rc = AudioObjectGetPropertyDataSize(kAudioObjectSystemObject, &addr, 0, NULL, &size);
    printf("device list query rc = %d, %u device(s)\n",
           (int)rc, (unsigned)(size / sizeof(AudioObjectID)));

    if (rc == noErr && size > 0) {
        AudioObjectID *ids = malloc(size);
        if (AudioObjectGetPropertyData(kAudioObjectSystemObject, &addr, 0, NULL, &size, ids) == noErr) {
            UInt32 count = size / sizeof(AudioObjectID);
            for (UInt32 i = 0; i < count; i++) {
                AudioObjectPropertyAddress name = {
                    kAudioObjectPropertyName,
                    kAudioObjectPropertyScopeGlobal,
                    kAudioObjectPropertyElementMain,
                };
                CFStringRef cf = NULL;
                UInt32 cfSize = sizeof(cf);
                char buffer[256] = "(unnamed)";
                if (AudioObjectGetPropertyData(ids[i], &name, 0, NULL, &cfSize, &cf) == noErr && cf) {
                    CFStringGetCString(cf, buffer, sizeof(buffer), kCFStringEncodingUTF8);
                    CFRelease(cf);
                }

                /* Output channels, which is what decides whether a device can
                 * be played to. A device with none is an input and must not
                 * appear in an output list. */
                AudioObjectPropertyAddress cfg = {
                    kAudioDevicePropertyStreamConfiguration,
                    kAudioObjectPropertyScopeOutput,
                    kAudioObjectPropertyElementMain,
                };
                UInt32 cfgSize = 0;
                UInt32 outputChannels = 0;
                if (AudioObjectGetPropertyDataSize(ids[i], &cfg, 0, NULL, &cfgSize) == noErr && cfgSize > 0) {
                    AudioBufferList *list = malloc(cfgSize);
                    if (AudioObjectGetPropertyData(ids[i], &cfg, 0, NULL, &cfgSize, list) == noErr) {
                        for (UInt32 b = 0; b < list->mNumberBuffers; b++) {
                            outputChannels += list->mBuffers[b].mNumberChannels;
                        }
                    }
                    free(list);
                }
                printf("  device %u: out=%u  \"%s\"\n",
                       (unsigned)ids[i], (unsigned)outputChannels, buffer);
            }
        }
        free(ids);
    }

    AudioObjectPropertyAddress def = {
        kAudioHardwarePropertyDefaultOutputDevice,
        kAudioObjectPropertyScopeGlobal,
        kAudioObjectPropertyElementMain,
    };
    AudioObjectID defaultDevice = 0;
    UInt32 defSize = sizeof(defaultDevice);
    rc = AudioObjectGetPropertyData(kAudioObjectSystemObject, &def, 0, NULL, &defSize, &defaultDevice);
    printf("default output device rc = %d, id = %u\n", (int)rc, (unsigned)defaultDevice);

    /* And whether a default output unit can actually be opened and started,
     * which is the question the contract suite will ask on every run. */
    AudioComponentDescription desc = {
        kAudioUnitType_Output, kAudioUnitSubType_DefaultOutput,
        kAudioUnitManufacturer_Apple, 0, 0,
    };
    AudioComponent comp = AudioComponentFindNext(NULL, &desc);
    printf("default output component found = %s\n", comp ? "yes" : "no");
    if (comp) {
        AudioUnit unit = NULL;
        OSStatus open = AudioComponentInstanceNew(comp, &unit);
        printf("AudioComponentInstanceNew rc = %d\n", (int)open);
        if (open == noErr) {
            OSStatus init = AudioUnitInitialize(unit);
            printf("AudioUnitInitialize rc = %d\n", (int)init);
            if (init == noErr) AudioUnitUninitialize(unit);
            AudioComponentInstanceDispose(unit);
        }
    }

    return 0;
}
