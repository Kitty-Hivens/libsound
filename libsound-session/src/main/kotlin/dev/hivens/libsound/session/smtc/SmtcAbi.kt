package dev.hivens.libsound.session.smtc

/**
 * Slots, identifiers and constants for the SMTC subset this backend binds.
 *
 * Every number was printed by `tools/smtc-oracle.c` against the real headers.
 * The discipline paid immediately here: the enable flags are not consecutive,
 * because a getter sits between each pair, and writing them in order by eye
 * would have called `put_IsPauseEnabled` when it meant `put_IsStopEnabled` --
 * through a signature that happens to match, so nothing would have complained
 * and the wrong button would have lit up.
 *
 * Taken from mingw-w64's headers, x86_64.
 *
 * ## WinRT, not classic COM
 *
 * Two differences that matter. Every interface here derives from `IInspectable`
 * rather than `IUnknown`, so the first usable slot is 6 and not 3. And the event
 * handler is a *parameterised* interface: its identifier is computed by MIDL
 * from the two type arguments and appears nowhere as a literal, which makes it
 * exactly the kind of value nobody can check by reading.
 */
internal object SmtcAbi {

    /** A WinRT class is reached by name through an activation factory, not by CLSID. */
    const val CLASS_SYSTEM_MEDIA_TRANSPORT_CONTROLS = "Windows.Media.SystemMediaTransportControls"

    // -- IUnknown, then IInspectable ------------------------------------------

    const val QUERY_INTERFACE = 0
    const val ADD_REF = 1
    const val RELEASE = 2
    const val GET_IIDS = 3
    const val GET_RUNTIME_CLASS_NAME = 4
    const val GET_TRUST_LEVEL = 5

    /** Where an interface's own methods start, WinRT having spent six on the base. */
    const val INSPECTABLE_SLOTS = 6

    // -- ISystemMediaTransportControlsInterop ---------------------------------

    const val INTEROP_GET_FOR_WINDOW = 6

    // -- ISystemMediaTransportControls ----------------------------------------

    const val CONTROLS_GET_PLAYBACK_STATUS = 6
    const val CONTROLS_PUT_PLAYBACK_STATUS = 7
    const val CONTROLS_GET_DISPLAY_UPDATER = 8
    const val CONTROLS_PUT_IS_ENABLED = 11

    /**
     * Deliberately spelled out rather than derived from a base and an offset.
     * They run 13, 15, 17, 25, 27 -- a getter between each -- and the arithmetic
     * that looks obvious is wrong for every one of them past the first.
     */
    const val CONTROLS_PUT_IS_PLAY_ENABLED = 13
    const val CONTROLS_PUT_IS_STOP_ENABLED = 15
    const val CONTROLS_PUT_IS_PAUSE_ENABLED = 17
    const val CONTROLS_PUT_IS_PREVIOUS_ENABLED = 25
    const val CONTROLS_PUT_IS_NEXT_ENABLED = 27

    const val CONTROLS_ADD_BUTTON_PRESSED = 32
    const val CONTROLS_REMOVE_BUTTON_PRESSED = 33

    // -- ISystemMediaTransportControlsDisplayUpdater --------------------------

    const val UPDATER_PUT_TYPE = 7
    const val UPDATER_GET_MUSIC_PROPERTIES = 12
    const val UPDATER_CLEAR_ALL = 16
    const val UPDATER_UPDATE = 17

    // -- IMusicDisplayProperties ----------------------------------------------

    const val MUSIC_PUT_TITLE = 7
    const val MUSIC_PUT_ALBUM_ARTIST = 9
    const val MUSIC_PUT_ARTIST = 11

    // -- ISystemMediaTransportControlsButtonPressedEventArgs ------------------

    const val BUTTON_ARGS_GET_BUTTON = 6

    // -- ITypedEventHandler<SMTC, ButtonPressedEventArgs>, which we implement --

    const val HANDLER_INVOKE = 3

    /** Four, and a vtable shorter means the runtime calls past the end of it. */
    const val HANDLER_VTABLE_SLOTS = 4

    // -- enums -----------------------------------------------------------------

    const val PLAYBACK_STATUS_CLOSED = 0
    const val PLAYBACK_STATUS_CHANGING = 1
    const val PLAYBACK_STATUS_STOPPED = 2
    const val PLAYBACK_STATUS_PLAYING = 3
    const val PLAYBACK_STATUS_PAUSED = 4

    const val MEDIA_TYPE_UNKNOWN = 0
    const val MEDIA_TYPE_MUSIC = 1

    /**
     * Next is 6 and Previous is 7, not 3 and 4: the record and channel buttons
     * occupy the gap. Counted from the header rather than from the order they
     * appear on a keyboard.
     */
    const val BUTTON_PLAY = 0
    const val BUTTON_PAUSE = 1
    const val BUTTON_STOP = 2
    const val BUTTON_NEXT = 6
    const val BUTTON_PREVIOUS = 7

    // -- the timeline, so a widget can draw a position ------------------------

    /** Reached by activating the class; it has no statics to ask for. */
    const val CLASS_TIMELINE_PROPERTIES = "Windows.Media.SystemMediaTransportControlsTimelineProperties"

    const val ACTIVATION_FACTORY_ACTIVATE_INSTANCE = 6

    const val CONTROLS2_UPDATE_TIMELINE_PROPERTIES = 12

    /**
     * 7, 9, 15 -- and the gap before Position is wide enough that guessing it
     * from the other two lands on `put_MaxSeekTime`, which takes the same
     * argument and would have been accepted in silence.
     */
    const val TIMELINE_PUT_START_TIME = 7
    const val TIMELINE_PUT_END_TIME = 9
    const val TIMELINE_PUT_POSITION = 15

    // -- RoInitialize ----------------------------------------------------------

    /** The apartment every thread here enters; none of them pumps a message loop. */
    const val RO_INIT_MULTITHREADED = 1

    // -- identifiers -----------------------------------------------------------

    const val IID_INSPECTABLE = "AF86E2E0-B12D-4C6A-9C5A-D7AA65101E90"
    const val IID_INTEROP = "DDB0472D-C911-4A1F-86D9-DC3D71A95F5A"
    const val IID_CONTROLS = "99FA3FF4-1742-42A6-902E-087D41F965EC"
    const val IID_DISPLAY_UPDATER = "8ABBC53E-FA55-4ECF-AD8E-C984E5DD1550"
    const val IID_MUSIC_PROPERTIES = "6BBF0C59-D0A0-4D26-92A0-F978E1D18E7B"
    const val IID_BUTTON_ARGS = "B7F47116-A56F-4DC8-9E11-92031F4A87C2"
    const val IID_ACTIVATION_FACTORY = "00000035-0000-0000-C000-000000000046"
    const val IID_CONTROLS2 = "EA98D2F6-7F3C-4AF2-A586-72889808EFB1"
    const val IID_TIMELINE_PROPERTIES = "5125316A-C3A2-475B-8507-93534DC88F15"

    /**
     * Computed by MIDL from `ITypedEventHandler<SystemMediaTransportControls,
     * SystemMediaTransportControlsButtonPressedEventArgs>`, and written down
     * nowhere. There is no way to sanity-check this one by reading it, which is
     * the whole argument for printing it from the headers.
     */
    const val IID_BUTTON_HANDLER = "0557E996-7B23-5BAE-AA81-EA0D671143A4"
}
