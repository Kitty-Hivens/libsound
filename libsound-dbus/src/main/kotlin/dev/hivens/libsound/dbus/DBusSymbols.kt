package dev.hivens.libsound.session.dbus

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

/**
 * The libdbus symbols this repository loads.
 *
 * The local half of the split described in [DBusAbi]: the marshalling layouts
 * are identical across the family and copied verbatim, while the symbol set is
 * whatever each consumer's protocol actually needs and stays here.
 *
 * libsound needs both shapes at once, which is why the split earns its keep
 * here first. MPRIS publishes -- own a bus name, answer method calls, emit
 * signals, like libtray's tray item -- and reads, sending plain method calls to
 * other players, like libnotify's client. Neither sibling needs both.
 *
 * No upcall stubs. Incoming messages are pulled off the connection with
 * `dbus_connection_read_write` and `dbus_connection_pop_message` on a thread we
 * own, which is what both siblings settled on: registering an object path
 * through `dbus_connection_register_object_path` would mean handing libdbus a
 * callable function pointer, and the arena lifetime that comes with it, for no
 * behaviour we cannot get by polling.
 */
internal class DBusSymbols private constructor(
    val arena: Arena,
    private val handles: Map<String, MethodHandle>,
) {
    /**
     * A resolved handle. Throws when the symbol was not in the load set --
     * programmer error, not a runtime condition to degrade on.
     */
    fun handle(name: String): MethodHandle =
        handles[name] ?: error("libdbus handle not loaded: $name. Add it to LOAD_SET.")

    fun close() {
        runCatching { arena.close() }
    }

    companion object {
        private val ADDR = ValueLayout.ADDRESS
        private val I32 = ValueLayout.JAVA_INT

        private val LOAD_SET: List<Triple<String, MemoryLayout?, List<MemoryLayout>>> = listOf(
            // Connection lifecycle. Private, never the process-shared one: this
            // backend drains the bus with its own pop_message loop, and a shared
            // connection's single incoming queue would let another libdbus user
            // in the process -- a sibling tray or notification library, which is
            // exactly the situation in the downstream launcher -- pop and drop
            // messages meant for us. The family has already shipped that bug
            // three times.
            Triple("dbus_bus_get_private", ADDR, listOf(I32, ADDR)),
            Triple("dbus_connection_close", null, listOf(ADDR)),
            Triple("dbus_connection_unref", null, listOf(ADDR)),
            // libdbus defaults exit_on_disconnect ON, which _exit()s the whole
            // process when the session bus drops.
            Triple("dbus_connection_set_exit_on_disconnect", null, listOf(ADDR, I32)),
            Triple("dbus_connection_read_write", I32, listOf(ADDR, I32)),
            Triple("dbus_connection_pop_message", ADDR, listOf(ADDR)),
            Triple("dbus_connection_send", I32, listOf(ADDR, ADDR, ADDR)),
            Triple("dbus_connection_send_with_reply_and_block", ADDR, listOf(ADDR, ADDR, I32, ADDR)),
            Triple("dbus_connection_flush", null, listOf(ADDR)),

            // Owning a name -- the publishing half.
            Triple("dbus_bus_request_name", I32, listOf(ADDR, ADDR, I32, ADDR)),
            Triple("dbus_bus_release_name", I32, listOf(ADDR, ADDR, ADDR)),
            Triple("dbus_bus_add_match", null, listOf(ADDR, ADDR, ADDR)),
            Triple("dbus_bus_get_unique_name", ADDR, listOf(ADDR)),

            // Messages.
            Triple("dbus_message_new_method_call", ADDR, listOf(ADDR, ADDR, ADDR, ADDR)),
            Triple("dbus_message_new_method_return", ADDR, listOf(ADDR)),
            Triple("dbus_message_new_error", ADDR, listOf(ADDR, ADDR, ADDR)),
            Triple("dbus_message_new_signal", ADDR, listOf(ADDR, ADDR, ADDR)),
            Triple("dbus_message_unref", null, listOf(ADDR)),
            Triple("dbus_message_get_type", I32, listOf(ADDR)),
            Triple("dbus_message_get_member", ADDR, listOf(ADDR)),
            Triple("dbus_message_get_interface", ADDR, listOf(ADDR)),
            Triple("dbus_message_get_path", ADDR, listOf(ADDR)),
            Triple("dbus_message_get_sender", ADDR, listOf(ADDR)),
            Triple("dbus_message_get_no_reply", I32, listOf(ADDR)),

            // Iterators -- the whole of the marshalling surface.
            Triple("dbus_message_iter_init", I32, listOf(ADDR, ADDR)),
            Triple("dbus_message_iter_init_append", null, listOf(ADDR, ADDR)),
            Triple("dbus_message_iter_append_basic", I32, listOf(ADDR, I32, ADDR)),
            Triple("dbus_message_iter_open_container", I32, listOf(ADDR, I32, ADDR, ADDR)),
            Triple("dbus_message_iter_close_container", I32, listOf(ADDR, ADDR)),
            Triple("dbus_message_iter_recurse", null, listOf(ADDR, ADDR)),
            Triple("dbus_message_iter_next", I32, listOf(ADDR)),
            Triple("dbus_message_iter_get_arg_type", I32, listOf(ADDR)),
            Triple("dbus_message_iter_get_basic", null, listOf(ADDR, ADDR)),

            // Errors.
            Triple("dbus_error_init", null, listOf(ADDR)),
            Triple("dbus_error_is_set", I32, listOf(ADDR)),
            Triple("dbus_error_free", null, listOf(ADDR)),
        )

        /**
         * Load libdbus and bind every symbol.
         *
         * Returns null when the library is absent or any symbol is missing.
         * Never a partially-bound set: discovering a gap at the call site is how
         * a backend half-works, and half a media session is worse than none.
         *
         * Not cached. The caller owns the arena and closes it on its own failure
         * paths -- memoizing this would make one such close poison every later
         * user, which is a mistake libnotify's KDoc claimed and its code did not
         * make.
         */
        fun loadOrNull(): DBusSymbols? {
            val arena = Arena.ofShared()
            val lookup = DBusAbi.LIB_CANDIDATES.firstNotNullOfOrNull { name ->
                runCatching { SymbolLookup.libraryLookup(name, arena) }.getOrNull()
            } ?: run {
                arena.close()
                return null
            }
            val linker = Linker.nativeLinker()
            val handles = HashMap<String, MethodHandle>(LOAD_SET.size * 2)
            for ((name, ret, args) in LOAD_SET) {
                val symbol = lookup.find(name).orElse(null) ?: run {
                    arena.close()
                    return null
                }
                val descriptor = if (ret == null) {
                    FunctionDescriptor.ofVoid(*args.toTypedArray())
                } else {
                    FunctionDescriptor.of(ret, *args.toTypedArray())
                }
                handles[name] = linker.downcallHandle(symbol, descriptor)
            }
            return DBusSymbols(arena, handles)
        }
    }
}

/** Allocate a NUL-terminated UTF-8 string; libdbus takes `const char *` throughout. */
internal fun Arena.allocateUtf8(value: String): MemorySegment = allocateFrom(value)

/**
 * Read a `const char *` a libdbus accessor returned, or null from NULL.
 *
 * Bounded for the reason the WASAPI reader gives: a size is needed before the
 * first read, and reinterpreting to `Long.MAX_VALUE` turns a stray pointer into
 * a scan of the whole address space. Bus names, paths and metadata strings all
 * sit far below the ceiling.
 */
internal fun MemorySegment.readCString(): String? {
    if (address() == 0L) return null
    return runCatching { reinterpret(MAX_C_STRING_BYTES).getString(0) }.getOrNull()
}

private const val MAX_C_STRING_BYTES = 64L * 1024
