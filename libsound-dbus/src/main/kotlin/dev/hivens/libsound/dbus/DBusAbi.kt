package dev.hivens.libsound.session.dbus

import java.lang.foreign.MemoryLayout
import java.lang.foreign.ValueLayout

/**
 * Constants and layouts for libdbus, printed by `tools/dbus-oracle.c`.
 *
 * ## Canonical copy
 *
 * This file is meant to be identical in every repository of the family that
 * speaks D-Bus -- libtray, libnotify, libvault and this one. The family rule is
 * that nobody ships a general D-Bus layer, because the four use three different
 * shapes of the protocol and a shared artefact would become the union of all of
 * them. But the *marshalling* is the same everywhere, and the same defect has
 * now been fixed three times in three places, so the file is cut along that
 * seam: this half is copied verbatim and fixed once, while the symbol set each
 * repository actually loads stays local to it.
 *
 * A change here is a change everywhere. Do not edit a local copy.
 *
 * ## Why the two sizes below are not guesses
 *
 * `DBusMessageIter` is opaque by design: the public header declares padding
 * fields and tells the caller only that it is "small". libtray and libnotify
 * each allocated 64 bytes for it, which is 72 on x86_64, so libdbus wrote its
 * trailing pointer past the allocation on every single iterator call -- through
 * two shipped releases each, silently. The fix reserved 80, which is a safer
 * guess and still a guess.
 *
 * The oracle prints 72, and prints an alignment of 8. Both matter. A byte
 * sequence layout carries an alignment of one, so a scratch declared that way
 * is only byte-aligned as far as the API is concerned, while libdbus writes
 * pointers into it -- which x86_64 tolerates and aarch64 need not.
 */
internal object DBusAbi {

    // -- the two structs a caller allocates -----------------------------------

    /**
     * 72 bytes, aligned to 8. Declared as a struct of longs rather than a byte
     * sequence so the alignment survives into the allocation.
     */
    val MESSAGE_ITER_LAYOUT: MemoryLayout = MemoryLayout.sequenceLayout(9, ValueLayout.JAVA_LONG)

    /** 32 bytes: two pointers, a flags word with its padding, and a trailing pointer. */
    val ERROR_LAYOUT: MemoryLayout = MemoryLayout.structLayout(
        ValueLayout.ADDRESS.withName("name"),
        ValueLayout.ADDRESS.withName("message"),
        ValueLayout.JAVA_INT.withName("flags"),
        MemoryLayout.paddingLayout(4),
        ValueLayout.ADDRESS.withName("padding1"),
    )

    // -- bus types -------------------------------------------------------------

    const val BUS_SESSION = 0
    const val BUS_SYSTEM = 1

    // -- message types ---------------------------------------------------------

    const val MESSAGE_TYPE_INVALID = 0
    const val MESSAGE_TYPE_METHOD_CALL = 1
    const val MESSAGE_TYPE_METHOD_RETURN = 2
    const val MESSAGE_TYPE_ERROR = 3
    const val MESSAGE_TYPE_SIGNAL = 4

    // -- type codes, single-byte ASCII on the wire -----------------------------

    const val TYPE_INVALID: Byte = 0
    const val TYPE_BYTE: Byte = 'y'.code.toByte()
    const val TYPE_BOOLEAN: Byte = 'b'.code.toByte()
    const val TYPE_INT16: Byte = 'n'.code.toByte()
    const val TYPE_UINT16: Byte = 'q'.code.toByte()
    const val TYPE_INT32: Byte = 'i'.code.toByte()
    const val TYPE_UINT32: Byte = 'u'.code.toByte()
    const val TYPE_INT64: Byte = 'x'.code.toByte()
    const val TYPE_UINT64: Byte = 't'.code.toByte()
    const val TYPE_DOUBLE: Byte = 'd'.code.toByte()
    const val TYPE_STRING: Byte = 's'.code.toByte()
    const val TYPE_OBJECT_PATH: Byte = 'o'.code.toByte()
    const val TYPE_SIGNATURE: Byte = 'g'.code.toByte()
    const val TYPE_ARRAY: Byte = 'a'.code.toByte()
    const val TYPE_VARIANT: Byte = 'v'.code.toByte()
    const val TYPE_STRUCT: Byte = 'r'.code.toByte()
    const val TYPE_DICT_ENTRY: Byte = 'e'.code.toByte()

    // -- owning a name ---------------------------------------------------------

    const val NAME_FLAG_ALLOW_REPLACEMENT = 1
    const val NAME_FLAG_REPLACE_EXISTING = 2
    const val NAME_FLAG_DO_NOT_QUEUE = 4

    const val REQUEST_NAME_REPLY_PRIMARY_OWNER = 1
    const val REQUEST_NAME_REPLY_IN_QUEUE = 2
    const val REQUEST_NAME_REPLY_EXISTS = 3
    const val REQUEST_NAME_REPLY_ALREADY_OWNER = 4

    // -- dispatch and handlers -------------------------------------------------

    const val DISPATCH_DATA_REMAINS = 0
    const val DISPATCH_COMPLETE = 1

    const val HANDLER_RESULT_HANDLED = 0
    const val HANDLER_RESULT_NOT_YET_HANDLED = 1

    // -- timeouts --------------------------------------------------------------

    const val TIMEOUT_USE_DEFAULT = -1

    /** Library names, in the order the JDK's lookup should try them. */
    val LIB_CANDIDATES: List<String> = listOf("libdbus-1.so.3", "dbus-1.so.3", "dbus-1")
}
