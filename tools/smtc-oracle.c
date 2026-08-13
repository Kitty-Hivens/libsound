/*
 * Slot and GUID oracle for the SMTC subset libsound binds.
 *
 * Not part of the build. Cross-compile it against the real Windows headers and
 * run it (wine is enough), then transcribe its output into the Kotlin ABI table.
 *
 *   x86_64-w64-mingw32-gcc -o smtc-oracle.exe smtc-oracle.c -lole32 -luuid
 *   wine smtc-oracle.exe
 *
 * WinRT rather than classic COM, and the difference matters twice. A runtime
 * class is reached through a name and an activation factory instead of a CLSID,
 * so the strings below are as load-bearing as the GUIDs. And the event handler
 * is a parameterised interface: its identifier is computed by MIDL from the two
 * type arguments rather than written down anywhere, which makes it precisely the
 * kind of value nobody can check by eye and exactly what this program is for.
 *
 * The slot counts of the interfaces we implement are printed too. A vtable
 * shorter than the runtime expects means it calls through whatever memory
 * follows it.
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
#include <windows.foundation.h>
#include <stddef.h>
#include <stdio.h>

#define SECTION(name) printf("\n== %s ==\n", name)

#define SLOT(vt, m) \
    printf("  %-56s = %2llu\n", #m, (unsigned long long)(offsetof(vt, m) / sizeof(void *)))

#define SLOTS(vt) \
    printf("  %-56s = %2llu\n", "slots in " #vt, (unsigned long long)(sizeof(vt) / sizeof(void *)))

#define VALUE(expr) \
    printf("  %-56s = %lld\n", #expr, (long long)(expr))

static void print_guid(const char *name, const GUID *g) {
    printf("  %-56s = %08lX-%04X-%04X-%02X%02X-%02X%02X%02X%02X%02X%02X\n",
           name,
           (unsigned long)g->Data1, g->Data2, g->Data3,
           g->Data4[0], g->Data4[1], g->Data4[2], g->Data4[3],
           g->Data4[4], g->Data4[5], g->Data4[6], g->Data4[7]);
}

#define GUID_OF(g) print_guid(#g, &g)

/* The handler type names are long enough to hide a typo, so they are spelled
 * once here and used through these. */
typedef __FITypedEventHandler_2_Windows__CMedia__CSystemMediaTransportControls_Windows__CMedia__CSystemMediaTransportControlsButtonPressedEventArgs ButtonHandler;
typedef __FITypedEventHandler_2_Windows__CMedia__CSystemMediaTransportControls_Windows__CMedia__CSystemMediaTransportControlsButtonPressedEventArgsVtbl ButtonHandlerVtbl;

int main(void) {
    printf("SMTC oracle -- mingw-w64 headers, x86_64\n");
    printf("pointer size = %llu\n", (unsigned long long)sizeof(void *));

    SECTION("runtime class names (a WinRT class is reached by name)");
    printf("  %-56s = %s\n", "SystemMediaTransportControls",
           "Windows.Media.SystemMediaTransportControls");

    SECTION("IUnknown, then IInspectable (WinRT adds three)");
    SLOT(IInspectableVtbl, QueryInterface);
    SLOT(IInspectableVtbl, AddRef);
    SLOT(IInspectableVtbl, Release);
    SLOT(IInspectableVtbl, GetIids);
    SLOT(IInspectableVtbl, GetRuntimeClassName);
    SLOT(IInspectableVtbl, GetTrustLevel);
    SLOTS(IInspectableVtbl);

    SECTION("ISystemMediaTransportControlsInterop (the only desktop way in)");
    SLOT(ISystemMediaTransportControlsInteropVtbl, GetForWindow);

    SECTION("ISystemMediaTransportControls");
    SLOT(__x_ABI_CWindows_CMedia_CISystemMediaTransportControlsVtbl, get_PlaybackStatus);
    SLOT(__x_ABI_CWindows_CMedia_CISystemMediaTransportControlsVtbl, put_PlaybackStatus);
    SLOT(__x_ABI_CWindows_CMedia_CISystemMediaTransportControlsVtbl, get_DisplayUpdater);
    SLOT(__x_ABI_CWindows_CMedia_CISystemMediaTransportControlsVtbl, get_IsEnabled);
    SLOT(__x_ABI_CWindows_CMedia_CISystemMediaTransportControlsVtbl, put_IsEnabled);
    SLOT(__x_ABI_CWindows_CMedia_CISystemMediaTransportControlsVtbl, put_IsPlayEnabled);
    SLOT(__x_ABI_CWindows_CMedia_CISystemMediaTransportControlsVtbl, put_IsPauseEnabled);
    SLOT(__x_ABI_CWindows_CMedia_CISystemMediaTransportControlsVtbl, put_IsStopEnabled);
    SLOT(__x_ABI_CWindows_CMedia_CISystemMediaTransportControlsVtbl, put_IsNextEnabled);
    SLOT(__x_ABI_CWindows_CMedia_CISystemMediaTransportControlsVtbl, put_IsPreviousEnabled);
    SLOT(__x_ABI_CWindows_CMedia_CISystemMediaTransportControlsVtbl, add_ButtonPressed);
    SLOT(__x_ABI_CWindows_CMedia_CISystemMediaTransportControlsVtbl, remove_ButtonPressed);

    SECTION("ISystemMediaTransportControlsDisplayUpdater");
    SLOT(__x_ABI_CWindows_CMedia_CISystemMediaTransportControlsDisplayUpdaterVtbl, get_Type);
    SLOT(__x_ABI_CWindows_CMedia_CISystemMediaTransportControlsDisplayUpdaterVtbl, put_Type);
    SLOT(__x_ABI_CWindows_CMedia_CISystemMediaTransportControlsDisplayUpdaterVtbl, get_MusicProperties);
    SLOT(__x_ABI_CWindows_CMedia_CISystemMediaTransportControlsDisplayUpdaterVtbl, put_Thumbnail);
    SLOT(__x_ABI_CWindows_CMedia_CISystemMediaTransportControlsDisplayUpdaterVtbl, ClearAll);
    SLOT(__x_ABI_CWindows_CMedia_CISystemMediaTransportControlsDisplayUpdaterVtbl, Update);

    SECTION("IMusicDisplayProperties (what the lock screen shows)");
    SLOT(__x_ABI_CWindows_CMedia_CIMusicDisplayPropertiesVtbl, put_Title);
    SLOT(__x_ABI_CWindows_CMedia_CIMusicDisplayPropertiesVtbl, put_AlbumArtist);
    SLOT(__x_ABI_CWindows_CMedia_CIMusicDisplayPropertiesVtbl, put_Artist);

    SECTION("ISystemMediaTransportControlsButtonPressedEventArgs");
    SLOT(__x_ABI_CWindows_CMedia_CISystemMediaTransportControlsButtonPressedEventArgsVtbl, get_Button);

    /* We implement this one, so both its identifier and its length are ours to
     * get right. The identifier is computed by MIDL from the two type arguments
     * and appears in no documentation as a literal. */
    SECTION("ITypedEventHandler<SMTC, ButtonPressedEventArgs> -- we implement it");
    SLOT(ButtonHandlerVtbl, Invoke);
    SLOTS(ButtonHandlerVtbl);

    SECTION("IActivationFactory (a runtime class with no statics is activated)");
    SLOT(IActivationFactoryVtbl, ActivateInstance);
    GUID_OF(IID_IActivationFactory);

    SECTION("timeline: ISystemMediaTransportControls2 + TimelineProperties");
    SLOT(__x_ABI_CWindows_CMedia_CISystemMediaTransportControls2Vtbl, UpdateTimelineProperties);
    SLOT(__x_ABI_CWindows_CMedia_CISystemMediaTransportControlsTimelinePropertiesVtbl, put_StartTime);
    SLOT(__x_ABI_CWindows_CMedia_CISystemMediaTransportControlsTimelinePropertiesVtbl, put_EndTime);
    SLOT(__x_ABI_CWindows_CMedia_CISystemMediaTransportControlsTimelinePropertiesVtbl, put_Position);
    GUID_OF(IID___x_ABI_CWindows_CMedia_CISystemMediaTransportControls2);
    GUID_OF(IID___x_ABI_CWindows_CMedia_CISystemMediaTransportControlsTimelineProperties);
    printf("  %-56s = %s\n", "TimelineProperties class",
           "Windows.Media.SystemMediaTransportControlsTimelineProperties");

    /* The window a consumer without one still needs. Measured because it is
     * filled field by field, and a wrong offset here writes a pointer into a
     * neighbouring field rather than failing. */
    SECTION("WNDCLASSEXW");
    printf("  %-56s = %llu\n", "offsetof(cbSize)", (unsigned long long)offsetof(WNDCLASSEXW, cbSize));
    printf("  %-56s = %llu\n", "offsetof(lpfnWndProc)", (unsigned long long)offsetof(WNDCLASSEXW, lpfnWndProc));
    printf("  %-56s = %llu\n", "offsetof(hInstance)", (unsigned long long)offsetof(WNDCLASSEXW, hInstance));
    printf("  %-56s = %llu\n", "offsetof(lpszClassName)", (unsigned long long)offsetof(WNDCLASSEXW, lpszClassName));
    printf("  %-56s = %llu\n", "sizeof(WNDCLASSEXW)", (unsigned long long)sizeof(WNDCLASSEXW));
    VALUE(WS_OVERLAPPEDWINDOW);
    VALUE((int)CW_USEDEFAULT);

    SECTION("enums");
    VALUE(MediaPlaybackStatus_Closed);
    VALUE(MediaPlaybackStatus_Changing);
    VALUE(MediaPlaybackStatus_Stopped);
    VALUE(MediaPlaybackStatus_Playing);
    VALUE(MediaPlaybackStatus_Paused);
    VALUE(MediaPlaybackType_Unknown);
    VALUE(MediaPlaybackType_Music);
    VALUE(MediaPlaybackType_Video);
    VALUE(SystemMediaTransportControlsButton_Play);
    VALUE(SystemMediaTransportControlsButton_Pause);
    VALUE(SystemMediaTransportControlsButton_Stop);
    VALUE(SystemMediaTransportControlsButton_Next);
    VALUE(SystemMediaTransportControlsButton_Previous);

    SECTION("RoInitialize");
    VALUE(RO_INIT_SINGLETHREADED);
    VALUE(RO_INIT_MULTITHREADED);

    SECTION("GUIDs");
    GUID_OF(IID_IInspectable);
    GUID_OF(IID_ISystemMediaTransportControlsInterop);
    GUID_OF(IID___x_ABI_CWindows_CMedia_CISystemMediaTransportControls);
    GUID_OF(IID___x_ABI_CWindows_CMedia_CISystemMediaTransportControlsDisplayUpdater);
    GUID_OF(IID___x_ABI_CWindows_CMedia_CIMusicDisplayProperties);
    GUID_OF(IID___x_ABI_CWindows_CMedia_CISystemMediaTransportControlsButtonPressedEventArgs);
    GUID_OF(IID___FITypedEventHandler_2_Windows__CMedia__CSystemMediaTransportControls_Windows__CMedia__CSystemMediaTransportControlsButtonPressedEventArgs);

    return 0;
}
