/*
 * Does IAudioSessionManager2 enumerate sessions on a capture endpoint?
 *
 * The one question the Windows half of capture waits on, in sections 6.2 and
 * 11 of docs/PLAN.md. The documentation says a session manager activated on a
 * capture endpoint enumerates that endpoint's sessions. Nothing has measured
 * it, and the answer decides whether this library can list what is recording
 * on Windows at all, or whether the capability is absent there for good.
 *
 * Behaviour, not ABI, which is why this needs a real Windows machine. Wine
 * reimplements the same vtable and answers the ABI questions correctly, and it
 * is a poor oracle for what the session manager actually does, so a run here
 * proves the program works and nothing about the answer.
 *
 *   x86_64-w64-mingw32-gcc -o wasapi-capture-probe.exe wasapi-capture-probe.c -lole32 -loleaut32
 *   wine wasapi-capture-probe.exe          (proves it runs, answers nothing)
 *   wasapi-capture-probe.exe               (on Windows, the actual answer)
 *
 * Reads and prints. It opens no stream, plays nothing, records nothing and
 * changes no setting: a session enumeration is a list of what other programs
 * are doing, and this asks for the list and puts it on the screen.
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
#include <stdio.h>

#define SECTION(name) printf("\n== %s ==\n", name)

static void fail(const char *what, HRESULT hr)
{
    printf("  %-44s FAILED 0x%08lX\n", what, (unsigned long)hr);
}

static void print_name(IMMDevice *device)
{
    IPropertyStore *store = NULL;
    PROPVARIANT value;
    HRESULT hr = IMMDevice_OpenPropertyStore(device, STGM_READ, &store);
    if (FAILED(hr)) {
        fail("OpenPropertyStore", hr);
        return;
    }
    PropVariantInit(&value);
    hr = IPropertyStore_GetValue(store, &PKEY_Device_FriendlyName, &value);
    if (SUCCEEDED(hr) && value.vt == VT_LPWSTR) {
        printf("  endpoint = %ls\n", value.pwszVal);
    } else {
        fail("PKEY_Device_FriendlyName", hr);
    }
    PropVariantClear(&value);
    IPropertyStore_Release(store);
}

/* The whole question, asked of one endpoint. Printed rather than judged: what
 * a count of zero means on a machine with nothing recording is different from
 * what it means with a recorder running, and only the person at the keyboard
 * knows which they have. */
static void enumerate(IMMDevice *device, const char *label)
{
    IAudioSessionManager2 *manager = NULL;
    IAudioSessionEnumerator *sessions = NULL;
    int count = 0;
    int index;

    SECTION(label);
    print_name(device);

    HRESULT hr = IMMDevice_Activate(
        device, &IID_IAudioSessionManager2, CLSCTX_ALL, NULL, (void **)&manager);
    if (FAILED(hr)) {
        fail("Activate(IAudioSessionManager2)", hr);
        printf("  answer: no session manager on this endpoint at all\n");
        return;
    }
    printf("  %-44s ok\n", "Activate(IAudioSessionManager2)");

    hr = IAudioSessionManager2_GetSessionEnumerator(manager, &sessions);
    if (FAILED(hr)) {
        fail("GetSessionEnumerator", hr);
        printf("  answer: no\n");
        IAudioSessionManager2_Release(manager);
        return;
    }

    hr = IAudioSessionEnumerator_GetCount(sessions, &count);
    if (FAILED(hr)) {
        fail("GetCount", hr);
        count = 0;
    }
    printf("  %-44s %d\n", "sessions", count);

    for (index = 0; index < count; index++) {
        IAudioSessionControl *control = NULL;
        IAudioSessionControl2 *control2 = NULL;
        LPWSTR display = NULL;
        DWORD pid = 0;
        AudioSessionState state = AudioSessionStateInactive;

        if (FAILED(IAudioSessionEnumerator_GetSession(sessions, index, &control))) {
            printf("  [%d] GetSession failed\n", index);
            continue;
        }
        IAudioSessionControl_GetState(control, &state);
        if (SUCCEEDED(IAudioSessionControl_QueryInterface(
                control, &IID_IAudioSessionControl2, (void **)&control2))) {
            IAudioSessionControl2_GetProcessId(control2, &pid);
            IAudioSessionControl2_GetDisplayName(control2, &display);
        }
        printf("  [%d] pid=%lu state=%d name=%ls\n",
               index, (unsigned long)pid, (int)state,
               (display && *display) ? display : L"(none)");
        if (display) CoTaskMemFree(display);
        if (control2) IAudioSessionControl2_Release(control2);
        IAudioSessionControl_Release(control);
    }

    printf("  answer: the enumerator exists and reported %d session(s)\n", count);
    IAudioSessionEnumerator_Release(sessions);
    IAudioSessionManager2_Release(manager);
}

int main(void)
{
    IMMDeviceEnumerator *devices = NULL;
    IMMDevice *capture = NULL;
    IMMDevice *render = NULL;
    HRESULT hr;

    printf("WASAPI capture session probe\n");
    printf("Reads only. Nothing is opened, played, recorded or changed.\n");

    hr = CoInitializeEx(NULL, COINIT_MULTITHREADED);
    if (FAILED(hr)) {
        fail("CoInitializeEx", hr);
        return 1;
    }

    hr = CoCreateInstance(&CLSID_MMDeviceEnumerator, NULL, CLSCTX_ALL,
                          &IID_IMMDeviceEnumerator, (void **)&devices);
    if (FAILED(hr)) {
        fail("CoCreateInstance(MMDeviceEnumerator)", hr);
        CoUninitialize();
        return 1;
    }

    /* The render side first, as the control. It is known to enumerate, so a run
     * where both come back empty says the machine had nothing playing or
     * recording rather than that capture cannot be enumerated. */
    hr = IMMDeviceEnumerator_GetDefaultAudioEndpoint(devices, eRender, eConsole, &render);
    if (SUCCEEDED(hr)) {
        enumerate(render, "render endpoint (the control, known to work)");
        IMMDevice_Release(render);
    } else {
        fail("GetDefaultAudioEndpoint(eRender)", hr);
    }

    hr = IMMDeviceEnumerator_GetDefaultAudioEndpoint(devices, eCapture, eConsole, &capture);
    if (SUCCEEDED(hr)) {
        enumerate(capture, "capture endpoint (the question)");
        IMMDevice_Release(capture);
    } else {
        fail("GetDefaultAudioEndpoint(eCapture)", hr);
        printf("  no default input on this machine, so the question is unanswered\n");
    }

    printf("\nRun it once with nothing recording and once with something recording,\n");
    printf("a voice call or the Windows Voice Recorder, and send both.\n");

    IMMDeviceEnumerator_Release(devices);
    CoUninitialize();
    return 0;
}
