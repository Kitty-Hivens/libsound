/*
 * Does the System Media Transport Controls accept a window the library made?
 *
 * Not an offset oracle -- a behaviour question, and the one that decides an API.
 * SMTC has exactly one entry point for a desktop process:
 *
 *   ISystemMediaTransportControlsInterop::GetForWindow(HWND, REFIID, void**)
 *
 * A library has no window. Its consumer might not have one either: a command
 * line tool or a service has nothing to pass. So either libsound demands a
 * handle in its public API and locks out every consumer without a window, or it
 * creates one itself -- and whether that is allowed is not something to
 * remember, it is something to run.
 *
 * This answers the API half: does GetForWindow succeed against a window we made,
 * and can the controls then be configured. It cannot answer whether Windows
 * actually *shows* those controls for an invisible window; that needs a person
 * looking at a screen, and the exit code here says which of the two questions is
 * still open.
 *
 *   x86_64-w64-mingw32-gcc -o smtc-probe.exe smtc-probe.c \
 *       -lole32 -lruntimeobject -luser32
 *
 * ## Where this stands
 *
 * It builds and runs against a current mingw, and under wine it answers yes --
 * which is worth little, because wine's WinRT is stubbed in places and a stub
 * says S_OK to everything. Wine's WASAPI is a different matter and does real
 * work; this is specifically the young part of it.
 *
 * On a real Windows it compiles clean against the Windows SDK and then fails to
 * link: in C mode the SDK emits no symbol for a WinRT interface's IID, where
 * mingw defines them through DEFINE_GUID. Finishing it means rewriting it as
 * C++ around __uuidof, which is a different program rather than a fix.
 *
 * It is kept because the question is still open and the next person should not
 * start from nothing. It is not kept because the answer is needed: the API it
 * was meant to decide is correct either way -- a consumer may pass a window
 * handle, the library makes one when they cannot, and MediaSessions answers
 * null when neither works. What the probe would settle is how often that
 * fallback succeeds, which belongs in documentation rather than in a signature.
 */

#define INITGUID
#define COBJMACROS
#define WIN32_LEAN_AND_MEAN

#include <initguid.h>
#include <windows.h>
#include <roapi.h>
#include <winstring.h>
#include <systemmediatransportcontrolsinterop.h>
#include <windows.media.h>
#include <stdio.h>

#define STEP(name) printf("\n-- %s\n", name)
#define HR(what, hr) printf("   %-46s hr = 0x%08lX%s\n", what, (unsigned long)(hr), (hr) == S_OK ? "  ok" : "")

static const WCHAR *SMTC_CLASS =
    L"Windows.Media.SystemMediaTransportControls";

/* A window that exists and is never shown. If SMTC refuses this, it refuses
 * every consumer that has no UI of its own, and the answer to the API question
 * is no. */
static HWND make_hidden_window(void) {
    WNDCLASSEXW wc = {0};
    wc.cbSize = sizeof(wc);
    wc.lpfnWndProc = DefWindowProcW;
    wc.hInstance = GetModuleHandleW(NULL);
    wc.lpszClassName = L"libsoundSmtcProbe";
    RegisterClassExW(&wc);
    return CreateWindowExW(
        0, L"libsoundSmtcProbe", L"libsound smtc probe",
        WS_OVERLAPPEDWINDOW, CW_USEDEFAULT, CW_USEDEFAULT, 320, 200,
        NULL, NULL, wc.hInstance, NULL);
}

int main(void) {
    printf("SMTC probe -- does a library-made window get transport controls?\n");

    HRESULT hr = RoInitialize(RO_INIT_MULTITHREADED);
    HR("RoInitialize", hr);
    if (FAILED(hr) && hr != RPC_E_CHANGED_MODE) return 2;

    STEP("activation factory");
    HSTRING className = NULL;
    HSTRING_HEADER header;
    hr = WindowsCreateStringReference(SMTC_CLASS, (UINT32)wcslen(SMTC_CLASS), &header, &className);
    HR("WindowsCreateStringReference", hr);
    if (FAILED(hr)) return 2;

    ISystemMediaTransportControlsInterop *interop = NULL;
    hr = RoGetActivationFactory(className, &IID_ISystemMediaTransportControlsInterop, (void **)&interop);
    HR("RoGetActivationFactory(interop)", hr);
    if (FAILED(hr)) {
        printf("\nVERDICT: no interop factory on this system. SMTC is unavailable here,\n");
        printf("which says nothing about a real Windows desktop.\n");
        return 3;
    }

    STEP("a window nobody sees");
    HWND hidden = make_hidden_window();
    printf("   %-46s hwnd = %p\n", "CreateWindowExW (never shown)", (void *)hidden);
    if (!hidden) {
        printf("\nVERDICT: could not create a window at all.\n");
        return 2;
    }

    STEP("GetForWindow against it");
    __x_ABI_CWindows_CMedia_CISystemMediaTransportControls *controls = NULL;
    hr = interop->lpVtbl->GetForWindow(
        interop, hidden, &IID___x_ABI_CWindows_CMedia_CISystemMediaTransportControls, (void **)&controls);
    HR("GetForWindow(hidden window)", hr);

    if (FAILED(hr) || controls == NULL) {
        printf("\nVERDICT: NO. A window the library makes is not accepted, so a\n");
        printf("consumer without a window of its own cannot publish a session, and\n");
        printf("the handle has to be part of the public API.\n");
        return 1;
    }

    STEP("can the controls be configured");
    hr = controls->lpVtbl->put_IsEnabled(controls, TRUE);
    HR("put_IsEnabled(TRUE)", hr);
    hr = controls->lpVtbl->put_IsPlayEnabled(controls, TRUE);
    HR("put_IsPlayEnabled(TRUE)", hr);
    hr = controls->lpVtbl->put_IsPauseEnabled(controls, TRUE);
    HR("put_IsPauseEnabled(TRUE)", hr);

    __x_ABI_CWindows_CMedia_CISystemMediaTransportControlsDisplayUpdater *updater = NULL;
    hr = controls->lpVtbl->get_DisplayUpdater(controls, &updater);
    HR("get_DisplayUpdater", hr);
    if (SUCCEEDED(hr) && updater) {
        hr = updater->lpVtbl->put_Type(updater, MediaPlaybackType_Music);
        HR("DisplayUpdater::put_Type(Music)", hr);
        hr = updater->lpVtbl->Update(updater);
        HR("DisplayUpdater::Update", hr);
        updater->lpVtbl->Release(updater);
    }

    controls->lpVtbl->Release(controls);
    interop->lpVtbl->Release(interop);
    DestroyWindow(hidden);

    printf("\nVERDICT: YES at the API level -- a window the library made is accepted\n");
    printf("and the controls configure. Whether Windows SHOWS them for a window\n");
    printf("nobody can see is a separate question that needs a person.\n");
    return 0;
}
