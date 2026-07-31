/*
 * Slot and GUID oracle for the WASAPI subset libsound binds.
 *
 * Not part of the build. Cross-compile it against the real Windows headers and
 * run it (wine is enough), then transcribe its output into the Kotlin ABI table.
 *
 *   x86_64-w64-mingw32-gcc -o wasapi-oracle.exe wasapi-oracle.c -lole32 -luuid
 *   wine wasapi-oracle.exe
 *
 * Why an oracle for COM specifically. A vtable slot index is decided by the
 * order methods are declared in the interface, and nothing at runtime checks
 * it: calling slot 4 when you meant slot 5 invokes a different function with a
 * different signature, which is a crash if you are lucky and silent corruption
 * if you are not. The numbers below are printed from the headers that define
 * them rather than read off documentation, for the same reason the libpulse
 * offsets are.
 */

#define INITGUID
#define COBJMACROS
#define WIN32_LEAN_AND_MEAN

#include <initguid.h>
#include <windows.h>
#include <mmdeviceapi.h>
#include <audioclient.h>
#include <audiopolicy.h>
#include <functiondiscoverykeys_devpkey.h>
#include <stddef.h>
#include <stdio.h>

#define SECTION(name) printf("\n== %s ==\n", name)

/* A vtable is an array of function pointers; the slot index is the offset in
 * pointer-sized units. */
#define SLOT(vt, m) \
    printf("  %-52s = %2llu\n", #m, (unsigned long long)(offsetof(vt, m) / sizeof(void *)))

#define VALUE(expr) \
    printf("  %-52s = %lld\n", #expr, (long long)(expr))

#define HEX(expr) \
    printf("  %-52s = 0x%08llX\n", #expr, (unsigned long long)(unsigned int)(expr))

#define OFFSET(t, m) \
    printf("  %-52s = %llu\n", #t "." #m, (unsigned long long)offsetof(t, m))

#define SIZE(t) \
    printf("  %-52s = %llu\n", "sizeof(" #t ")", (unsigned long long)sizeof(t))

static void print_guid(const char *name, const GUID *g) {
    printf("  %-52s = %08lX-%04X-%04X-%02X%02X-%02X%02X%02X%02X%02X%02X\n",
           name,
           (unsigned long)g->Data1, g->Data2, g->Data3,
           g->Data4[0], g->Data4[1], g->Data4[2], g->Data4[3],
           g->Data4[4], g->Data4[5], g->Data4[6], g->Data4[7]);
}

#define GUID_OF(g) print_guid(#g, &g)

int main(void) {
    printf("WASAPI oracle -- mingw-w64 headers, x86_64\n");
    printf("pointer size = %llu\n", (unsigned long long)sizeof(void *));

    /* Every COM interface starts with IUnknown's three slots, so 0/1/2 are
     * always QueryInterface/AddRef/Release. Printed once to prove it. */
    SECTION("IUnknown (the first three slots of everything)");
    SLOT(IUnknownVtbl, QueryInterface);
    SLOT(IUnknownVtbl, AddRef);
    SLOT(IUnknownVtbl, Release);

    SECTION("IMMDeviceEnumerator");
    SLOT(IMMDeviceEnumeratorVtbl, EnumAudioEndpoints);
    SLOT(IMMDeviceEnumeratorVtbl, GetDefaultAudioEndpoint);
    SLOT(IMMDeviceEnumeratorVtbl, GetDevice);
    SLOT(IMMDeviceEnumeratorVtbl, RegisterEndpointNotificationCallback);
    SLOT(IMMDeviceEnumeratorVtbl, UnregisterEndpointNotificationCallback);

    SECTION("IMMDeviceCollection");
    SLOT(IMMDeviceCollectionVtbl, GetCount);
    SLOT(IMMDeviceCollectionVtbl, Item);

    SECTION("IMMDevice");
    SLOT(IMMDeviceVtbl, Activate);
    SLOT(IMMDeviceVtbl, OpenPropertyStore);
    SLOT(IMMDeviceVtbl, GetId);
    SLOT(IMMDeviceVtbl, GetState);

    SECTION("IPropertyStore (device friendly name)");
    SLOT(IPropertyStoreVtbl, GetCount);
    SLOT(IPropertyStoreVtbl, GetAt);
    SLOT(IPropertyStoreVtbl, GetValue);

    SECTION("IAudioClient");
    SLOT(IAudioClientVtbl, Initialize);
    SLOT(IAudioClientVtbl, GetBufferSize);
    SLOT(IAudioClientVtbl, GetStreamLatency);
    SLOT(IAudioClientVtbl, GetCurrentPadding);
    SLOT(IAudioClientVtbl, IsFormatSupported);
    SLOT(IAudioClientVtbl, GetMixFormat);
    SLOT(IAudioClientVtbl, GetDevicePeriod);
    SLOT(IAudioClientVtbl, Start);
    SLOT(IAudioClientVtbl, Stop);
    SLOT(IAudioClientVtbl, Reset);
    SLOT(IAudioClientVtbl, SetEventHandle);
    SLOT(IAudioClientVtbl, GetService);

    SECTION("IAudioRenderClient");
    SLOT(IAudioRenderClientVtbl, GetBuffer);
    SLOT(IAudioRenderClientVtbl, ReleaseBuffer);

    SECTION("IAudioClock (the honest playhead)");
    SLOT(IAudioClockVtbl, GetFrequency);
    SLOT(IAudioClockVtbl, GetPosition);
    SLOT(IAudioClockVtbl, GetCharacteristics);

    SECTION("ISimpleAudioVolume (per-application volume)");
    SLOT(ISimpleAudioVolumeVtbl, SetMasterVolume);
    SLOT(ISimpleAudioVolumeVtbl, GetMasterVolume);
    SLOT(ISimpleAudioVolumeVtbl, SetMute);
    SLOT(ISimpleAudioVolumeVtbl, GetMute);

    SECTION("IAudioSessionControl (the name the volume mixer shows)");
    SLOT(IAudioSessionControlVtbl, GetState);
    SLOT(IAudioSessionControlVtbl, GetDisplayName);
    SLOT(IAudioSessionControlVtbl, SetDisplayName);
    SLOT(IAudioSessionControlVtbl, GetIconPath);
    SLOT(IAudioSessionControlVtbl, SetIconPath);
    SLOT(IAudioSessionControlVtbl, RegisterAudioSessionNotification);
    SLOT(IAudioSessionControlVtbl, UnregisterAudioSessionNotification);

    SECTION("IMMNotificationClient (we implement this one, so slots are ours to fill)");
    SLOT(IMMNotificationClientVtbl, OnDeviceStateChanged);
    SLOT(IMMNotificationClientVtbl, OnDeviceAdded);
    SLOT(IMMNotificationClientVtbl, OnDeviceRemoved);
    SLOT(IMMNotificationClientVtbl, OnDefaultDeviceChanged);
    SLOT(IMMNotificationClientVtbl, OnPropertyValueChanged);
    printf("  %-52s = %llu\n", "slots in IMMNotificationClientVtbl",
           (unsigned long long)(sizeof(IMMNotificationClientVtbl) / sizeof(void *)));

    SECTION("GUIDs");
    GUID_OF(CLSID_MMDeviceEnumerator);
    GUID_OF(IID_IMMDeviceEnumerator);
    GUID_OF(IID_IMMNotificationClient);
    GUID_OF(IID_IAudioClient);
    GUID_OF(IID_IAudioRenderClient);
    GUID_OF(IID_IAudioClock);
    GUID_OF(IID_ISimpleAudioVolume);
    GUID_OF(IID_IAudioSessionControl);
    GUID_OF(IID_IUnknown);

    SECTION("WAVEFORMATEX / WAVEFORMATEXTENSIBLE");
    OFFSET(WAVEFORMATEX, wFormatTag);
    OFFSET(WAVEFORMATEX, nChannels);
    OFFSET(WAVEFORMATEX, nSamplesPerSec);
    OFFSET(WAVEFORMATEX, nAvgBytesPerSec);
    OFFSET(WAVEFORMATEX, nBlockAlign);
    OFFSET(WAVEFORMATEX, wBitsPerSample);
    OFFSET(WAVEFORMATEX, cbSize);
    SIZE(WAVEFORMATEX);
    OFFSET(WAVEFORMATEXTENSIBLE, Samples);
    OFFSET(WAVEFORMATEXTENSIBLE, dwChannelMask);
    OFFSET(WAVEFORMATEXTENSIBLE, SubFormat);
    SIZE(WAVEFORMATEXTENSIBLE);
    VALUE(WAVE_FORMAT_PCM);
    HEX(WAVE_FORMAT_EXTENSIBLE);

    SECTION("PROPVARIANT / PROPERTYKEY (the friendly name)");
    OFFSET(PROPVARIANT, vt);
    OFFSET(PROPVARIANT, pwszVal);
    SIZE(PROPVARIANT);
    SIZE(PROPERTYKEY);
    print_guid("PKEY_Device_FriendlyName.fmtid", &PKEY_Device_FriendlyName.fmtid);
    VALUE(PKEY_Device_FriendlyName.pid);
    VALUE(VT_LPWSTR);

    SECTION("enums and flags");
    VALUE(eRender);
    VALUE(eCapture);
    VALUE(eConsole);
    VALUE(eMultimedia);
    VALUE(eCommunications);
    HEX(DEVICE_STATE_ACTIVE);
    VALUE(AUDCLNT_SHAREMODE_SHARED);
    VALUE(AUDCLNT_SHAREMODE_EXCLUSIVE);
    HEX(AUDCLNT_STREAMFLAGS_EVENTCALLBACK);
    HEX(AUDCLNT_STREAMFLAGS_NOPERSIST);
    HEX(AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM);
    HEX(AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY);
    HEX(AUDCLNT_BUFFERFLAGS_SILENT);
    HEX(CLSCTX_ALL);
    HEX(COINIT_MULTITHREADED);
    HEX(COINIT_APARTMENTTHREADED);

    SECTION("HRESULTs worth naming");
    HEX(S_OK);
    HEX(S_FALSE);
    HEX(E_NOINTERFACE);
    HEX(E_POINTER);
    HEX(E_OUTOFMEMORY);
    HEX(AUDCLNT_E_DEVICE_INVALIDATED);
    HEX(AUDCLNT_E_NOT_INITIALIZED);
    HEX(AUDCLNT_E_ALREADY_INITIALIZED);
    HEX(AUDCLNT_E_UNSUPPORTED_FORMAT);
    HEX(AUDCLNT_E_DEVICE_IN_USE);
    HEX(AUDCLNT_E_BUFFER_TOO_LARGE);
    HEX(AUDCLNT_E_BUFFER_SIZE_ERROR);
    HEX(AUDCLNT_E_SERVICE_NOT_RUNNING);
    HEX(AUDCLNT_S_BUFFER_EMPTY);

    /* REFERENCE_TIME is 100-nanosecond units; every duration in this API is in
     * them, and getting the factor wrong is a buffer off by a factor of ten. */
    SECTION("time base");
    printf("  %-52s = %llu\n", "REFERENCE_TIME units per second", 10000000ULL);
    SIZE(REFERENCE_TIME);

    return 0;
}
