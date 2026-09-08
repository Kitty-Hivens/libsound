package dev.hivens.libsound.dbus

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/**
 * Writing and reading D-Bus argument values.
 *
 * The other half of the canonical pair described in [DBusAbi], and copied
 * verbatim across the family for the same reason: this is the code every D-Bus
 * consumer writes identically, and the code where a mistake is a message the
 * daemon rejects with a signature error naming neither the field nor the
 * caller.
 *
 * Every function here allocates only out of the `call` arena the caller owns
 * and never anything that has to outlive the call.
 *
 * The scratch iterator is [DBusAbi.MESSAGE_ITER_LAYOUT] everywhere -- 72 bytes
 * aligned to 8, from the oracle. A container opened here must be closed here;
 * libdbus writes the closing bookkeeping into the parent, and an unbalanced
 * pair corrupts the message rather than failing it.
 */

private fun DBusSymbols.scratch(call: Arena): MemorySegment = call.allocate(DBusAbi.MESSAGE_ITER_LAYOUT)

// -- basics -------------------------------------------------------------------

fun DBusSymbols.appendString(call: Arena, iter: MemorySegment, type: Byte, value: String) {
    val text = call.allocateUtf8(value)
    val pointer = call.allocate(ValueLayout.ADDRESS)
    pointer.set(ValueLayout.ADDRESS, 0, text)
    handle("dbus_message_iter_append_basic").invokeExact(iter, type.toInt(), pointer) as Int
}

fun DBusSymbols.appendInt32(call: Arena, iter: MemorySegment, value: Int) {
    val buffer = call.allocate(ValueLayout.JAVA_INT)
    buffer.set(ValueLayout.JAVA_INT, 0, value)
    handle("dbus_message_iter_append_basic").invokeExact(iter, DBusAbi.TYPE_INT32.toInt(), buffer) as Int
}

fun DBusSymbols.appendUint32(call: Arena, iter: MemorySegment, value: Int) {
    val buffer = call.allocate(ValueLayout.JAVA_INT)
    buffer.set(ValueLayout.JAVA_INT, 0, value)
    handle("dbus_message_iter_append_basic").invokeExact(iter, DBusAbi.TYPE_UINT32.toInt(), buffer) as Int
}

/** `t` on the wire, which is where a kernel thread id goes. */
fun DBusSymbols.appendUint64(call: Arena, iter: MemorySegment, value: Long) {
    val buffer = call.allocate(ValueLayout.JAVA_LONG)
    buffer.set(ValueLayout.JAVA_LONG, 0, value)
    handle("dbus_message_iter_append_basic").invokeExact(iter, DBusAbi.TYPE_UINT64.toInt(), buffer) as Int
}

fun DBusSymbols.appendInt64(call: Arena, iter: MemorySegment, value: Long) {
    val buffer = call.allocate(ValueLayout.JAVA_LONG)
    buffer.set(ValueLayout.JAVA_LONG, 0, value)
    handle("dbus_message_iter_append_basic").invokeExact(iter, DBusAbi.TYPE_INT64.toInt(), buffer) as Int
}

fun DBusSymbols.appendDouble(call: Arena, iter: MemorySegment, value: Double) {
    val buffer = call.allocate(ValueLayout.JAVA_DOUBLE)
    buffer.set(ValueLayout.JAVA_DOUBLE, 0, value)
    handle("dbus_message_iter_append_basic").invokeExact(iter, DBusAbi.TYPE_DOUBLE.toInt(), buffer) as Int
}

fun DBusSymbols.appendBoolean(call: Arena, iter: MemorySegment, value: Boolean) {
    // dbus_bool_t is four bytes on the wire, not one.
    val buffer = call.allocate(ValueLayout.JAVA_INT)
    buffer.set(ValueLayout.JAVA_INT, 0, if (value) 1 else 0)
    handle("dbus_message_iter_append_basic").invokeExact(iter, DBusAbi.TYPE_BOOLEAN.toInt(), buffer) as Int
}

// -- containers ----------------------------------------------------------------

fun DBusSymbols.openContainer(
    parent: MemorySegment,
    type: Byte,
    signature: MemorySegment,
    sub: MemorySegment,
) {
    handle("dbus_message_iter_open_container").invokeExact(parent, type.toInt(), signature, sub) as Int
}

fun DBusSymbols.closeContainer(parent: MemorySegment, sub: MemorySegment) {
    handle("dbus_message_iter_close_container").invokeExact(parent, sub) as Int
}

/** Open a variant of [signature], let [body] write the single value, close it. */
inline fun DBusSymbols.variant(
    call: Arena,
    parent: MemorySegment,
    signature: String,
    body: (MemorySegment) -> Unit,
) {
    val sub = call.allocate(DBusAbi.MESSAGE_ITER_LAYOUT)
    openContainer(parent, DBusAbi.TYPE_VARIANT, call.allocateUtf8(signature), sub)
    body(sub)
    closeContainer(parent, sub)
}

fun DBusSymbols.appendVariantString(call: Arena, parent: MemorySegment, value: String) {
    variant(call, parent, "s") { appendString(call, it, DBusAbi.TYPE_STRING, value) }
}

fun DBusSymbols.appendVariantObjectPath(call: Arena, parent: MemorySegment, value: String) {
    variant(call, parent, "o") { appendString(call, it, DBusAbi.TYPE_OBJECT_PATH, value) }
}

fun DBusSymbols.appendVariantInt32(call: Arena, parent: MemorySegment, value: Int) {
    variant(call, parent, "i") { appendInt32(call, it, value) }
}

fun DBusSymbols.appendVariantInt64(call: Arena, parent: MemorySegment, value: Long) {
    variant(call, parent, "x") { appendInt64(call, it, value) }
}

fun DBusSymbols.appendVariantDouble(call: Arena, parent: MemorySegment, value: Double) {
    variant(call, parent, "d") { appendDouble(call, it, value) }
}

fun DBusSymbols.appendVariantBoolean(call: Arena, parent: MemorySegment, value: Boolean) {
    variant(call, parent, "b") { appendBoolean(call, it, value) }
}

/** A variant holding `as` -- the shape MPRIS uses for artists and genres. */
fun DBusSymbols.appendVariantStringArray(call: Arena, parent: MemorySegment, values: List<String>) {
    variant(call, parent, "as") { inner ->
        val array = call.allocate(DBusAbi.MESSAGE_ITER_LAYOUT)
        openContainer(inner, DBusAbi.TYPE_ARRAY, call.allocateUtf8("s"), array)
        values.forEach { appendString(call, array, DBusAbi.TYPE_STRING, it) }
        closeContainer(inner, array)
    }
}

/**
 * Open an `a{sv}` dictionary, let [body] add entries through
 * [DictWriter], and close it. The map every property bundle in MPRIS is.
 */
inline fun DBusSymbols.dict(call: Arena, parent: MemorySegment, body: (DictWriter) -> Unit) {
    val array = call.allocate(DBusAbi.MESSAGE_ITER_LAYOUT)
    openContainer(parent, DBusAbi.TYPE_ARRAY, call.allocateUtf8("{sv}"), array)
    body(DictWriter(this, call, array))
    closeContainer(parent, array)
}

/**
 * Adds `{sv}` entries to an open dictionary.
 *
 * A value is skipped when it is null rather than written as an empty one: MPRIS
 * readers distinguish an absent key from a present-but-blank one, and a blank
 * title is a widget showing a blank title.
 */
@InternalDBusApi
class DictWriter(
    private val symbols: DBusSymbols,
    private val call: Arena,
    private val array: MemorySegment,
) {
    private inline fun entry(key: String, body: (MemorySegment) -> Unit) {
        val entry = call.allocate(DBusAbi.MESSAGE_ITER_LAYOUT)
        symbols.openContainer(array, DBusAbi.TYPE_DICT_ENTRY, MemorySegment.NULL, entry)
        symbols.appendString(call, entry, DBusAbi.TYPE_STRING, key)
        body(entry)
        symbols.closeContainer(array, entry)
    }

    /**
     * Give the entry up instead of closing it, for a writer that declined.
     *
     * A dict entry closed with a key and no value is not a message the daemon
     * rejects, it is an assertion inside libdbus and a core dump, and the guard
     * standing between the two is a caller filtering the same list it writes
     * from. This is what libdbus offers for the case where that guard is wrong.
     */
    private fun abandon(entry: MemorySegment) {
        symbols.handle("dbus_message_iter_abandon_container_if_open").invokeExact(array, entry) as Unit
    }

    fun string(key: String, value: String?) {
        if (value == null) return
        entry(key) { symbols.appendVariantString(call, it, value) }
    }

    fun objectPath(key: String, value: String?) {
        if (value == null) return
        entry(key) { symbols.appendVariantObjectPath(call, it, value) }
    }

    fun int64(key: String, value: Long?) {
        if (value == null) return
        entry(key) { symbols.appendVariantInt64(call, it, value) }
    }

    /** `i` on the wire, which is what the metadata specification asks for a track number. */
    fun int32(key: String, value: Int?) {
        if (value == null) return
        entry(key) { symbols.appendVariantInt32(call, it, value) }
    }

    fun double(key: String, value: Double?) {
        if (value == null) return
        entry(key) { symbols.appendVariantDouble(call, it, value) }
    }

    fun boolean(key: String, value: Boolean?) {
        if (value == null) return
        entry(key) { symbols.appendVariantBoolean(call, it, value) }
    }

    /** Omitted entirely when empty -- an empty `as` and an absent key differ. */
    fun stringArray(key: String, values: List<String>) {
        if (values.isEmpty()) return
        entry(key) { symbols.appendVariantStringArray(call, it, values) }
    }

    /**
     * An entry whose variant the caller writes.
     *
     * For values whose type is decided somewhere else -- a property table that
     * knows which of a dozen shapes each name carries. [write] returns false
     * for a value it has none of, and the entry is then given up rather than
     * closed: closing it would leave a key with no value, which libdbus meets
     * with an assertion and a core dump rather than with a rejected message.
     */
    fun raw(key: String, write: (MemorySegment) -> Boolean) {
        val entry = call.allocate(DBusAbi.MESSAGE_ITER_LAYOUT)
        symbols.openContainer(array, DBusAbi.TYPE_DICT_ENTRY, MemorySegment.NULL, entry)
        symbols.appendString(call, entry, DBusAbi.TYPE_STRING, key)
        if (write(entry)) symbols.closeContainer(array, entry) else abandon(entry)
    }
}

// -- reads ---------------------------------------------------------------------

/** The type code at the iterator's cursor. */
fun DBusSymbols.argType(iter: MemorySegment): Byte =
    (handle("dbus_message_iter_get_arg_type").invokeExact(iter) as Int).toByte()

fun DBusSymbols.next(iter: MemorySegment): Boolean =
    (handle("dbus_message_iter_next").invokeExact(iter) as Int) != 0

fun DBusSymbols.recurse(call: Arena, iter: MemorySegment): MemorySegment {
    val sub = scratch(call)
    handle("dbus_message_iter_recurse").invokeExact(iter, sub) as Unit
    return sub
}

/**
 * Recurse into the container at the cursor, or null where the cursor is not on
 * one.
 *
 * Not a convenience, and the reason is the same one [DBusAbi] gives for reading
 * the ABI off an oracle. `dbus_message_iter_recurse` asserts that the current
 * type is a container, and libdbus answers a failed assertion with
 * `_dbus_abort()`: it dumps core and takes the host process with it. Measured,
 * on a `Properties.Set` carrying two arguments where the signature says three.
 *
 * Every argument here comes off a socket that anybody on the bus can write to,
 * so a peer that sends the wrong shape must get an error rather than the last
 * word on whether this process keeps running.
 */
fun DBusSymbols.recurseOrNull(call: Arena, iter: MemorySegment): MemorySegment? {
    val type = argType(iter)
    val container = type == DBusAbi.TYPE_ARRAY || type == DBusAbi.TYPE_VARIANT ||
        type == DBusAbi.TYPE_STRUCT || type == DBusAbi.TYPE_DICT_ENTRY
    return if (container) recurse(call, iter) else null
}

/**
 * Read a string at the cursor.
 *
 * STRING, OBJECT_PATH and SIGNATURE are all NUL-terminated `char *` on the
 * wire, so all three are accepted; refusing the last two is how a path argument
 * silently reads as null.
 */
fun DBusSymbols.readString(call: Arena, iter: MemorySegment): String? {
    val type = argType(iter)
    if (type != DBusAbi.TYPE_STRING && type != DBusAbi.TYPE_OBJECT_PATH && type != DBusAbi.TYPE_SIGNATURE) {
        return null
    }
    val out = call.allocate(ValueLayout.ADDRESS)
    handle("dbus_message_iter_get_basic").invokeExact(iter, out) as Unit
    return out.get(ValueLayout.ADDRESS, 0).readCString()
}

fun DBusSymbols.readInt64(call: Arena, iter: MemorySegment): Long? {
    val type = argType(iter)
    if (type != DBusAbi.TYPE_INT64 && type != DBusAbi.TYPE_UINT64) return null
    val out = call.allocate(ValueLayout.JAVA_LONG)
    handle("dbus_message_iter_get_basic").invokeExact(iter, out) as Unit
    return out.get(ValueLayout.JAVA_LONG, 0)
}

fun DBusSymbols.readInt32(call: Arena, iter: MemorySegment): Int? {
    val type = argType(iter)
    if (type != DBusAbi.TYPE_INT32 && type != DBusAbi.TYPE_UINT32) return null
    val out = call.allocate(ValueLayout.JAVA_INT)
    handle("dbus_message_iter_get_basic").invokeExact(iter, out) as Unit
    return out.get(ValueLayout.JAVA_INT, 0)
}

/** `b` is four bytes at the cursor, the same width the append side writes. */
fun DBusSymbols.readBoolean(call: Arena, iter: MemorySegment): Boolean? {
    if (argType(iter) != DBusAbi.TYPE_BOOLEAN) return null
    val out = call.allocate(ValueLayout.JAVA_INT)
    handle("dbus_message_iter_get_basic").invokeExact(iter, out) as Unit
    return out.get(ValueLayout.JAVA_INT, 0) != 0
}

fun DBusSymbols.readDouble(call: Arena, iter: MemorySegment): Double? {
    if (argType(iter) != DBusAbi.TYPE_DOUBLE) return null
    val out = call.allocate(ValueLayout.JAVA_DOUBLE)
    handle("dbus_message_iter_get_basic").invokeExact(iter, out) as Unit
    return out.get(ValueLayout.JAVA_DOUBLE, 0)
}

/** Walk an `as` at the cursor. Empty for anything that is not an array. */
fun DBusSymbols.readStringArray(call: Arena, iter: MemorySegment): List<String> {
    if (argType(iter) != DBusAbi.TYPE_ARRAY) return emptyList()
    val sub = recurse(call, iter)
    val values = mutableListOf<String>()
    while (argType(sub) != DBusAbi.TYPE_INVALID) {
        readString(call, sub)?.let { values.add(it) }
        next(sub)
    }
    return values
}
