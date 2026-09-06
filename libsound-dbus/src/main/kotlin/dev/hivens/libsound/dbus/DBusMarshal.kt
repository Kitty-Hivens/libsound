package dev.hivens.libsound.session.dbus

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

internal fun DBusSymbols.appendString(call: Arena, iter: MemorySegment, type: Byte, value: String) {
    val text = call.allocateUtf8(value)
    val pointer = call.allocate(ValueLayout.ADDRESS)
    pointer.set(ValueLayout.ADDRESS, 0, text)
    handle("dbus_message_iter_append_basic").invokeExact(iter, type.toInt(), pointer) as Int
}

internal fun DBusSymbols.appendInt32(call: Arena, iter: MemorySegment, value: Int) {
    val buffer = call.allocate(ValueLayout.JAVA_INT)
    buffer.set(ValueLayout.JAVA_INT, 0, value)
    handle("dbus_message_iter_append_basic").invokeExact(iter, DBusAbi.TYPE_INT32.toInt(), buffer) as Int
}

internal fun DBusSymbols.appendUint32(call: Arena, iter: MemorySegment, value: Int) {
    val buffer = call.allocate(ValueLayout.JAVA_INT)
    buffer.set(ValueLayout.JAVA_INT, 0, value)
    handle("dbus_message_iter_append_basic").invokeExact(iter, DBusAbi.TYPE_UINT32.toInt(), buffer) as Int
}

internal fun DBusSymbols.appendInt64(call: Arena, iter: MemorySegment, value: Long) {
    val buffer = call.allocate(ValueLayout.JAVA_LONG)
    buffer.set(ValueLayout.JAVA_LONG, 0, value)
    handle("dbus_message_iter_append_basic").invokeExact(iter, DBusAbi.TYPE_INT64.toInt(), buffer) as Int
}

internal fun DBusSymbols.appendDouble(call: Arena, iter: MemorySegment, value: Double) {
    val buffer = call.allocate(ValueLayout.JAVA_DOUBLE)
    buffer.set(ValueLayout.JAVA_DOUBLE, 0, value)
    handle("dbus_message_iter_append_basic").invokeExact(iter, DBusAbi.TYPE_DOUBLE.toInt(), buffer) as Int
}

internal fun DBusSymbols.appendBoolean(call: Arena, iter: MemorySegment, value: Boolean) {
    // dbus_bool_t is four bytes on the wire, not one.
    val buffer = call.allocate(ValueLayout.JAVA_INT)
    buffer.set(ValueLayout.JAVA_INT, 0, if (value) 1 else 0)
    handle("dbus_message_iter_append_basic").invokeExact(iter, DBusAbi.TYPE_BOOLEAN.toInt(), buffer) as Int
}

// -- containers ----------------------------------------------------------------

internal fun DBusSymbols.openContainer(
    parent: MemorySegment,
    type: Byte,
    signature: MemorySegment,
    sub: MemorySegment,
) {
    handle("dbus_message_iter_open_container").invokeExact(parent, type.toInt(), signature, sub) as Int
}

internal fun DBusSymbols.closeContainer(parent: MemorySegment, sub: MemorySegment) {
    handle("dbus_message_iter_close_container").invokeExact(parent, sub) as Int
}

/** Open a variant of [signature], let [body] write the single value, close it. */
internal inline fun DBusSymbols.variant(
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

internal fun DBusSymbols.appendVariantString(call: Arena, parent: MemorySegment, value: String) {
    variant(call, parent, "s") { appendString(call, it, DBusAbi.TYPE_STRING, value) }
}

internal fun DBusSymbols.appendVariantObjectPath(call: Arena, parent: MemorySegment, value: String) {
    variant(call, parent, "o") { appendString(call, it, DBusAbi.TYPE_OBJECT_PATH, value) }
}

internal fun DBusSymbols.appendVariantInt64(call: Arena, parent: MemorySegment, value: Long) {
    variant(call, parent, "x") { appendInt64(call, it, value) }
}

internal fun DBusSymbols.appendVariantDouble(call: Arena, parent: MemorySegment, value: Double) {
    variant(call, parent, "d") { appendDouble(call, it, value) }
}

internal fun DBusSymbols.appendVariantBoolean(call: Arena, parent: MemorySegment, value: Boolean) {
    variant(call, parent, "b") { appendBoolean(call, it, value) }
}

/** A variant holding `as` -- the shape MPRIS uses for artists and genres. */
internal fun DBusSymbols.appendVariantStringArray(call: Arena, parent: MemorySegment, values: List<String>) {
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
internal inline fun DBusSymbols.dict(call: Arena, parent: MemorySegment, body: (DictWriter) -> Unit) {
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
internal class DictWriter(
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
     * knows which of a dozen shapes each name carries. [write] returns false to
     * abandon the entry, which still has to be closed: libdbus records the
     * closing bookkeeping in the parent, and an unbalanced pair corrupts the
     * message rather than failing it.
     */
    fun raw(key: String, write: (MemorySegment) -> Boolean) {
        entry(key) { write(it) }
    }
}

// -- reads ---------------------------------------------------------------------

/** The type code at the iterator's cursor. */
internal fun DBusSymbols.argType(iter: MemorySegment): Byte =
    (handle("dbus_message_iter_get_arg_type").invokeExact(iter) as Int).toByte()

internal fun DBusSymbols.next(iter: MemorySegment): Boolean =
    (handle("dbus_message_iter_next").invokeExact(iter) as Int) != 0

internal fun DBusSymbols.recurse(call: Arena, iter: MemorySegment): MemorySegment {
    val sub = scratch(call)
    handle("dbus_message_iter_recurse").invokeExact(iter, sub) as Unit
    return sub
}

/**
 * Read a string at the cursor.
 *
 * STRING, OBJECT_PATH and SIGNATURE are all NUL-terminated `char *` on the
 * wire, so all three are accepted; refusing the last two is how a path argument
 * silently reads as null.
 */
internal fun DBusSymbols.readString(call: Arena, iter: MemorySegment): String? {
    val type = argType(iter)
    if (type != DBusAbi.TYPE_STRING && type != DBusAbi.TYPE_OBJECT_PATH && type != DBusAbi.TYPE_SIGNATURE) {
        return null
    }
    val out = call.allocate(ValueLayout.ADDRESS)
    handle("dbus_message_iter_get_basic").invokeExact(iter, out) as Unit
    return out.get(ValueLayout.ADDRESS, 0).readCString()
}

internal fun DBusSymbols.readInt64(call: Arena, iter: MemorySegment): Long? {
    val type = argType(iter)
    if (type != DBusAbi.TYPE_INT64 && type != DBusAbi.TYPE_UINT64) return null
    val out = call.allocate(ValueLayout.JAVA_LONG)
    handle("dbus_message_iter_get_basic").invokeExact(iter, out) as Unit
    return out.get(ValueLayout.JAVA_LONG, 0)
}

internal fun DBusSymbols.readDouble(call: Arena, iter: MemorySegment): Double? {
    if (argType(iter) != DBusAbi.TYPE_DOUBLE) return null
    val out = call.allocate(ValueLayout.JAVA_DOUBLE)
    handle("dbus_message_iter_get_basic").invokeExact(iter, out) as Unit
    return out.get(ValueLayout.JAVA_DOUBLE, 0)
}

/** Walk an `as` at the cursor. Empty for anything that is not an array. */
internal fun DBusSymbols.readStringArray(call: Arena, iter: MemorySegment): List<String> {
    if (argType(iter) != DBusAbi.TYPE_ARRAY) return emptyList()
    val sub = recurse(call, iter)
    val values = mutableListOf<String>()
    while (argType(sub) != DBusAbi.TYPE_INVALID) {
        readString(call, sub)?.let { values.add(it) }
        next(sub)
    }
    return values
}
