package dev.hivens.libsound.audio.wasapi

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

/**
 * Classic COM through Panama: vtable navigation, IUnknown, GUIDs, wide strings,
 * and the synthesis of a callable COM object for the one interface this backend
 * has to implement rather than call.
 *
 * The shape is libnotify's, which already does this for WinRT event handlers.
 * What differs is only the way in: libnotify activates runtime classes through
 * `combase`, while WASAPI is `CoCreateInstance` on `ole32`. Past the entry
 * point the discipline is identical -- an interface pointer points at a
 * `{ vtable* }`, method *i* lives at `vtable[i]`, and every method takes the
 * interface pointer as its first argument and returns an HRESULT.
 *
 * ## What is not checked for you
 *
 * A slot index is a plain integer. There is no type, no symbol, and no error:
 * calling the wrong slot invokes whatever function sits there through whatever
 * signature you declared. That is why [WasapiAbi]'s indices come from an oracle
 * run against the headers, and why nothing here accepts a slot as a literal.
 */
internal class WasapiCom private constructor(
    /** Holds the library lookup, the downcall handles and every upcall stub. */
    val arena: Arena,
    private val handles: Map<String, MethodHandle>,
) : AutoCloseable {

    private val linker = Linker.nativeLinker()

    /** COM apartment state is per thread, so the record of it has to be too. */
    private val initialised = ThreadLocal.withInitial { false }

    fun handle(name: String): MethodHandle =
        handles[name] ?: error("ole32 handle not loaded: $name. Add it to LOAD_SET.")

    /**
     * Threads that touch COM have to have entered an apartment, and each thread
     * enters its own.
     *
     * Multithreaded, because every thread here is one we or the consumer owns
     * and none of them pumps a Windows message loop -- which is what a
     * single-threaded apartment would require to deliver anything. The flag is
     * per thread and idempotent, so this is called at the head of each public
     * entry point rather than tracked by the caller.
     *
     * S_FALSE means already initialised on this thread, which is a success.
     */
    fun ensureComOnThisThread() {
        if (initialised.get()) return
        val hr = handle("CoInitializeEx")
            .invokeExact(MemorySegment.NULL, WasapiAbi.COINIT_MULTITHREADED) as Int
        // RPC_E_CHANGED_MODE means somebody already put this thread in another
        // apartment. Ours still works there, so it is not worth refusing over.
        if (hr < 0 && hr != RPC_E_CHANGED_MODE) {
            throw IllegalStateException("CoInitializeEx failed: 0x" + Integer.toHexString(hr))
        }
        initialised.set(true)
    }

    // -- vtable navigation ---------------------------------------------------

    /**
     * The function pointer in slot [index] of the interface [iface] points at.
     *
     * Two dereferences: the interface pointer holds a vtable pointer, and the
     * vtable is an array of function pointers. Each `reinterpret` is sized to
     * exactly what is read, because a raw pointer segment has zero length and
     * would throw on first access.
     */
    fun vtableFn(iface: MemorySegment, index: Int): MemorySegment {
        val vtable = iface.reinterpret(ValueLayout.ADDRESS.byteSize()).get(ValueLayout.ADDRESS, 0)
        return vtable.reinterpret((index + 1) * POINTER_SIZE).getAtIndex(ValueLayout.ADDRESS, index.toLong())
    }

    /** A downcall handle for one vtable method's exact ABI. */
    fun method(iface: MemorySegment, index: Int, descriptor: FunctionDescriptor): MethodHandle =
        linker.downcallHandle(vtableFn(iface, index), descriptor)

    /**
     * `IUnknown::QueryInterface`. Returns the requested interface pointer, or
     * null when the object does not implement it.
     */
    fun queryInterface(iface: MemorySegment, iid: String): MemorySegment? {
        Arena.ofConfined().use { call ->
            val out = call.allocate(ValueLayout.ADDRESS)
            val hr = method(
                iface, WasapiAbi.QUERY_INTERFACE,
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
            ).invokeExact(iface, guid(call, iid), out) as Int
            if (hr != WasapiAbi.S_OK) return null
            val result = out.get(ValueLayout.ADDRESS, 0)
            return if (result.address() == 0L) null else result
        }
    }

    /** `IUnknown::Release`. Safe on a null pointer, so teardown paths stay flat. */
    fun release(iface: MemorySegment?) {
        if (iface == null || iface.address() == 0L) return
        runCatching {
            method(iface, WasapiAbi.RELEASE, FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS))
                .invokeExact(iface) as Int
        }
    }

    /** Release memory the shell allocated for us -- device ids, mix formats. */
    fun coTaskMemFree(pointer: MemorySegment?) {
        if (pointer == null || pointer.address() == 0L) return
        runCatching { handle("CoTaskMemFree").invokeExact(pointer) as Unit }
    }

    override fun close() {
        runCatching { arena.close() }
    }

    internal companion object {

        // -- GUIDs ---------------------------------------------------------------

        /**
         * Allocate a `GUID` from its canonical text form.
         *
         * The first three fields are little-endian integers and the last is a byte
         * array in written order -- which is why the text form looks byte-swapped
         * against a hex dump and why this is worth doing in one place.
         */
        fun guid(arena: Arena, text: String): MemorySegment {
        val clean = text.replace("-", "")
        require(clean.length == 32) { "not a GUID: $text" }
        val segment = arena.allocate(GUID_LAYOUT)
        segment.set(ValueLayout.JAVA_INT, 0, clean.substring(0, 8).toLong(16).toInt())
        segment.set(ValueLayout.JAVA_SHORT, 4, clean.substring(8, 12).toInt(16).toShort())
        segment.set(ValueLayout.JAVA_SHORT, 6, clean.substring(12, 16).toInt(16).toShort())
        for (i in 0 until 8) {
            val byte = clean.substring(16 + i * 2, 18 + i * 2).toInt(16).toByte()
            segment.set(ValueLayout.JAVA_BYTE, 8L + i, byte)
        }
        return segment
        }

        // -- wide strings --------------------------------------------------------

        /** Allocate a NUL-terminated UTF-16LE string; every COM text argument is one. */
        fun wide(arena: Arena, text: String): MemorySegment {
        val chars = text.toCharArray()
        val segment = arena.allocate((chars.size + 1) * 2L, 2)
        for (i in chars.indices) segment.set(ValueLayout.JAVA_CHAR, i * 2L, chars[i])
        segment.set(ValueLayout.JAVA_CHAR, chars.size * 2L, NUL)
        return segment
        }

        /** Read a NUL-terminated UTF-16LE string, or null from a null pointer. */
        fun readWide(pointer: MemorySegment): String? {
        if (pointer.address() == 0L) return null
        val sized = pointer.reinterpret(MAX_WIDE_BYTES)
        val builder = StringBuilder()
        var offset = 0L
        while (offset < MAX_WIDE_BYTES) {
            val ch = sized.get(ValueLayout.JAVA_CHAR, offset)
            if (ch == NUL) break
            builder.append(ch)
            offset += 2
        }
        return builder.toString()
        }


        /**
         * Named rather than written as a literal. The first cut of the two
         * lines that use it carried a space instead of a zero, which reads
         * identically and would have terminated no string and matched no
         * terminator -- device ids would have come back as whatever followed
         * them in memory, up to the ceiling below.
         */
        const val NUL: Char = '\u0000'

        const val POINTER_SIZE = 8L

        /** `GUID` is `{ uint32, uint16, uint16, uint8[8] }` -- 16 bytes, aligned to 4. */
        val GUID_LAYOUT: MemoryLayout = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("Data1"),
            ValueLayout.JAVA_SHORT.withName("Data2"),
            ValueLayout.JAVA_SHORT.withName("Data3"),
            MemoryLayout.sequenceLayout(8, ValueLayout.JAVA_BYTE).withName("Data4"),
        )

        /**
         * A ceiling on a device id, not an expectation. `reinterpret` needs a
         * size before the first read, and the alternative -- `Long.MAX_VALUE` --
         * turns a stray pointer into a scan of the whole address space.
         */
        const val MAX_WIDE_BYTES = 4096L

        /**
         * The thread is already in a different apartment; ours works there
         * anyway. Printed by the oracle, after a first cut computed the two's
         * complement by hand and landed 65290 away from it.
         */
        val RPC_E_CHANGED_MODE: Int = 0x80010106u.toInt()

        private val ADDR = ValueLayout.ADDRESS
        private val I32 = ValueLayout.JAVA_INT

        private val LOAD_SET: List<Triple<String, MemoryLayout?, List<MemoryLayout>>> = listOf(
            // COM has to be initialised per thread. Multithreaded apartment,
            // because every call here happens on threads we own and none of
            // them pumps a Windows message loop.
            Triple("CoInitializeEx", I32, listOf(ADDR, I32)),
            Triple("CoUninitialize", null, emptyList()),
            Triple("CoCreateInstance", I32, listOf(ADDR, ADDR, I32, ADDR, ADDR)),
            Triple("CoTaskMemFree", null, listOf(ADDR)),
        )

        /**
         * Load `ole32` and bind the entry points.
         *
         * Returns null anywhere that is not Windows, which is the ordinary
         * answer on the machine this was written on -- the selection falls back
         * and nothing about it is exceptional.
         */
        fun loadOrNull(): WasapiCom? {
            val arena = Arena.ofShared()
            val lookup = runCatching { SymbolLookup.libraryLookup("ole32.dll", arena) }.getOrNull()
                ?: run {
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
            return WasapiCom(arena, handles)
        }
    }
}
