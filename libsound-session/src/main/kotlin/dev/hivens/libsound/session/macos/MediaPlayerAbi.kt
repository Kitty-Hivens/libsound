package dev.hivens.libsound.session.macos

/**
 * Layouts, selectors and constants for the MediaPlayer subset this backend binds.
 *
 * Every value was printed by `tools/mpnowplaying-oracle.m` on a macOS runner,
 * because there is no Mac here to run it on.
 *
 * ## Why an Objective-C binding still needs measuring
 *
 * Most of this API is reached by name, and a wrong name fails at the point of
 * use -- the pleasant kind of wrong. Two things here are not like that.
 *
 * The block is a struct with an ABI and nothing checks it: an invoke pointer at
 * the wrong offset has the runtime call whatever is there, exactly like a wrong
 * vtable slot. And the dictionary keys are not spelled the way their symbols
 * are: [KEY_TITLE] is `title`, while [KEY_ELAPSED] is its own full name. Half of
 * them would have been wrong if written from the symbol.
 */
internal object MediaPlayerAbi {

    // -- the block we synthesise ----------------------------------------------

    const val BLOCK_ISA = 0L
    const val BLOCK_FLAGS = 8L
    const val BLOCK_RESERVED = 12L
    const val BLOCK_INVOKE = 16L
    const val BLOCK_DESCRIPTOR = 24L
    const val BLOCK_SIZE = 32L

    const val DESCRIPTOR_RESERVED = 0L
    const val DESCRIPTOR_SIZE = 8L
    const val DESCRIPTOR_SIGNATURE = 16L
    const val DESCRIPTOR_WITH_SIGNATURE_SIZE = 24L

    /**
     * `BLOCK_IS_GLOBAL` and `BLOCK_HAS_SIGNATURE` together, which is what the
     * compiler sets for a block like ours -- measured as `0x50000000` rather
     * than assumed to be the global bit alone.
     *
     * Global matters: such a block is never copied or freed, and anything else
     * would have the runtime try to free memory an arena owns.
     */
    const val BLOCK_FLAGS_GLOBAL_WITH_SIGNATURE = 0x5000_0000

    /**
     * The type encoding the compiler writes for
     * `MPRemoteCommandHandlerStatus (^)(MPRemoteCommandEvent *)`.
     *
     * Copied from what the compiler emitted, not composed: `q` is the long
     * return, `@?` the block itself, and the event's class name appears inside
     * the encoding. Nothing about that is guessable.
     */
    const val BLOCK_SIGNATURE = "q16@?0@\"MPRemoteCommandEvent\"8"

    /** The class a global block's isa must point at. */
    const val CONCRETE_GLOBAL_BLOCK = "_NSConcreteGlobalBlock"

    // -- classes ---------------------------------------------------------------

    const val CLASS_NOW_PLAYING_INFO_CENTER = "MPNowPlayingInfoCenter"
    const val CLASS_REMOTE_COMMAND_CENTER = "MPRemoteCommandCenter"
    const val CLASS_MUTABLE_DICTIONARY = "NSMutableDictionary"
    const val CLASS_STRING = "NSString"
    const val CLASS_NUMBER = "NSNumber"

    // -- selectors -------------------------------------------------------------

    const val SEL_DEFAULT_CENTER = "defaultCenter"
    const val SEL_SHARED_COMMAND_CENTER = "sharedCommandCenter"
    const val SEL_SET_NOW_PLAYING_INFO = "setNowPlayingInfo:"
    const val SEL_SET_PLAYBACK_STATE = "setPlaybackState:"
    const val SEL_PLAY_COMMAND = "playCommand"
    const val SEL_PAUSE_COMMAND = "pauseCommand"
    const val SEL_STOP_COMMAND = "stopCommand"
    const val SEL_NEXT_COMMAND = "nextTrackCommand"
    const val SEL_PREVIOUS_COMMAND = "previousTrackCommand"
    const val SEL_SET_ENABLED = "setEnabled:"
    const val SEL_ADD_TARGET_WITH_HANDLER = "addTargetWithHandler:"
    const val SEL_DICTIONARY = "dictionary"
    const val SEL_SET_OBJECT_FOR_KEY = "setObject:forKey:"
    const val SEL_NUMBER_WITH_DOUBLE = "numberWithDouble:"
    const val SEL_STRING_WITH_UTF8 = "stringWithUTF8String:"

    // -- nowPlayingInfo keys, as the framework spells them ---------------------

    /**
     * Short where the symbol is long, and long where it looks like it should be
     * short. Printed from the framework's own globals; deriving them from the
     * symbol names would have got three of the six wrong.
     */
    const val KEY_TITLE = "title"
    const val KEY_ARTIST = "artist"
    const val KEY_ALBUM = "albumTitle"
    const val KEY_DURATION = "playbackDuration"
    const val KEY_ELAPSED = "MPNowPlayingInfoPropertyElapsedPlaybackTime"
    const val KEY_RATE = "MPNowPlayingInfoPropertyPlaybackRate"

    // -- enums -----------------------------------------------------------------

    const val PLAYBACK_STATE_UNKNOWN = 0L
    const val PLAYBACK_STATE_PLAYING = 1L
    const val PLAYBACK_STATE_PAUSED = 2L
    const val PLAYBACK_STATE_STOPPED = 3L

    /** What a handler returns to say it dealt with the command. */
    const val HANDLER_STATUS_SUCCESS = 0L
    const val HANDLER_STATUS_COMMAND_FAILED = 200L
}
