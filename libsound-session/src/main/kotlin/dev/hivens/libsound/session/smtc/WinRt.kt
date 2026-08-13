package dev.hivens.libsound.session.smtc

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

/**
 * WinRT through Panama: activation by class name, `HSTRING`, vtable navigation,
 * and a window to hang a session on when the consumer has none.
 *
 * ## Why this is not shared with the audio module
 *
 * `WasapiCom` does the same vtable arithmetic, and this repeats it. The
 * alternative is worse: sharing it would make `libsound-session` depend on
 * `libsound-audio`, so an application that wants media keys and nothing else
 * would pull in libpulse. The split earns its keep by native dependency, and a
 * hundred lines of pointer arithmetic is the price of keeping it honest.
 *
 * ## WinRT differs from classic COM in two ways that reach this file
 *
 * A runtime class is activated by *name* rather than by CLSID, so the strings
 * are as load-bearing as the identifiers. And every interface derives from
 * `IInspectable`, which adds three slots before an interface's own methods
 * begin -- see [SmtcAbi.INSPECTABLE_SLOTS].
 */
internal class WinRt private constructor(
    val arena: Arena,
    private val handles: Map<String, MethodHandle>,
) : AutoCloseable {

    private val linker = Linker.nativeLinker()

    /** The apartment is per thread, so the record of having entered one is too. */
    private val initialised = ThreadLocal.withInitial { false }

    fun handle(name: String): MethodHandle =
        handles[name] ?: error("WinRT handle not loaded: $name. Add it to LOAD_SET.")

    /**
     * Enter the multithreaded apartment on this thread.
     *
     * Multithreaded because every thread here is one we or the consumer owns and
     * none of them pumps a Windows message loop, which a single-threaded
     * apartment would require before it delivered anything. Idempotent per
     * thread, so public entry points call it rather than tracking it.
     */
    fun ensureApartment() {
        if (initialised.get()) return
        val hr = handle("RoInitialize").invokeExact(SmtcAbi.RO_INIT_MULTITHREADED) as Int
        // The thread is already in another apartment. Ours works there, and
        // refusing over it would break a consumer that initialised COM first.
        if (hr < 0 && hr != RPC_E_CHANGED_MODE) {
            throw IllegalStateException("RoInitialize failed: 0x" + Integer.toHexString(hr))
        }
        initialised.set(true)
    }

    // -- vtable navigation ----------------------------------------------------

    fun vtableFn(iface: MemorySegment, index: Int): MemorySegment {
        val vtable = iface.reinterpret(ValueLayout.ADDRESS.byteSize()).get(ValueLayout.ADDRESS, 0)
        return vtable.reinterpret((index + 1) * POINTER_SIZE).getAtIndex(ValueLayout.ADDRESS, index.toLong())
    }

    fun method(iface: MemorySegment, index: Int, descriptor: FunctionDescriptor): MethodHandle =
        linker.downcallHandle(vtableFn(iface, index), descriptor)

    /**
     * `IUnknown::QueryInterface`, or null when the object does not implement it.
     *
     * Reached for more here than in classic COM: a WinRT object hands back the
     * one interface that was asked for, and its other faces -- the second
     * revision of the controls, the timeline -- come only through this.
     */
    fun queryInterface(iface: MemorySegment, iid: String): MemorySegment? = Arena.ofConfined().use { call ->
        val out = call.allocate(ValueLayout.ADDRESS)
        val hr = method(
            iface, SmtcAbi.QUERY_INTERFACE,
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
        ).invokeExact(iface, guid(call, iid), out) as Int
        if (hr != S_OK) return null
        out.get(ValueLayout.ADDRESS, 0).takeIf { it.address() != 0L }
    }

    fun release(iface: MemorySegment?) {
        if (iface == null || iface.address() == 0L) return
        runCatching {
            method(iface, SmtcAbi.RELEASE, FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS))
                .invokeExact(iface) as Int
        }
    }

    /** Get the activation factory for a runtime class, or null when it has none here. */
    fun activationFactory(className: String, iid: String): MemorySegment? = Arena.ofConfined().use { call ->
        withHString(call, className) { name ->
            val out = call.allocate(ValueLayout.ADDRESS)
            val hr = handle("RoGetActivationFactory")
                .invokeExact(name, guid(call, iid), out) as Int
            if (hr != S_OK) return null
            out.get(ValueLayout.ADDRESS, 0).takeIf { it.address() != 0L }
        }
    }

    /**
     * Run [body] with an `HSTRING` for [text], and delete it afterwards.
     *
     * WinRT strings are reference counted and owned by the caller that made
     * them. One leaked per metadata update is one per track, on a path a player
     * runs for as long as it is open.
     */
    inline fun <T> withHString(arena: Arena, text: String, body: (MemorySegment) -> T): T {
        val out = arena.allocate(ValueLayout.ADDRESS)
        val wide = wide(arena, text)
        val hr = handle("WindowsCreateString").invokeExact(wide, text.length, out) as Int
        check(hr == S_OK) { "WindowsCreateString failed: 0x" + Integer.toHexString(hr) }
        val string = out.get(ValueLayout.ADDRESS, 0)
        try {
            return body(string)
        } finally {
            runCatching { handle("WindowsDeleteString").invokeExact(string) as Int }
        }
    }

    /**
     * A window for a consumer that has none.
     *
     * Never shown. It exists because Windows offers a desktop process no way to
     * reach the transport controls except through a window, so a consumer
     * without one -- a command line tool, a service -- would otherwise be shut
     * out of media keys entirely.
     */
    fun createOwnWindow(title: String): MemorySegment {
        Arena.ofConfined().use { call ->
            val className = wide(call, WINDOW_CLASS)
            val wndClass = call.allocate(WNDCLASSEXW_SIZE, 8)
            wndClass.fill(0)
            wndClass.set(ValueLayout.JAVA_INT, 0, WNDCLASSEXW_SIZE.toInt())
            wndClass.set(ValueLayout.ADDRESS, WNDCLASSEXW_WNDPROC, defWindowProc())
            wndClass.set(ValueLayout.ADDRESS, WNDCLASSEXW_INSTANCE, moduleHandle())
            wndClass.set(ValueLayout.ADDRESS, WNDCLASSEXW_CLASS_NAME, className)
            // Ignored when the class is already registered, which is the second
            // session in one process rather than an error.
            handle("RegisterClassExW").invokeExact(wndClass) as Short

            return handle("CreateWindowExW").invokeExact(
                0, className, wide(call, title), WS_OVERLAPPEDWINDOW,
                CW_USEDEFAULT, CW_USEDEFAULT, 320, 200,
                MemorySegment.NULL, MemorySegment.NULL, moduleHandle(), MemorySegment.NULL,
            ) as MemorySegment
        }
    }

    fun destroyWindow(window: MemorySegment) {
        if (window.address() == 0L) return
        runCatching { handle("DestroyWindow").invokeExact(window) as Int }
    }

    private fun moduleHandle(): MemorySegment =
        handle("GetModuleHandleW").invokeExact(MemorySegment.NULL) as MemorySegment

    private fun defWindowProc(): MemorySegment =
        user32.find("DefWindowProcW").orElseThrow { IllegalStateException("user32 has no DefWindowProcW") }

    private lateinit var user32: SymbolLookup

    override fun close() {
        runCatching { arena.close() }
    }

    internal companion object {
        const val S_OK = 0

        /** Somebody put this thread in another apartment; ours still works there. */
        val RPC_E_CHANGED_MODE: Int = 0x80010106u.toInt()

        const val POINTER_SIZE = 8L

        /**
         * Named rather than written as a literal, because the first cut of the
         * line below carried a space instead of a zero -- which reads
         * identically, terminates nothing, and is a mistake this family has
         * already made once in WasapiCom.
         */
        const val NUL: Char = '\u0000'

        private const val WINDOW_CLASS = "libsoundMediaSession"

        // WNDCLASSEXW, printed by tools/smtc-oracle.c. The struct is filled
        // field by field, so a wrong offset here writes a pointer into a
        // neighbouring field and the window is created against nonsense rather
        // than failing.
        private const val WNDCLASSEXW_SIZE = 80L
        private const val WNDCLASSEXW_WNDPROC = 8L
        private const val WNDCLASSEXW_INSTANCE = 24L
        private const val WNDCLASSEXW_CLASS_NAME = 64L

        private const val WS_OVERLAPPEDWINDOW = 13_565_952
        private const val CW_USEDEFAULT = -2_147_483_648

        val GUID_LAYOUT: MemoryLayout = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("Data1"),
            ValueLayout.JAVA_SHORT.withName("Data2"),
            ValueLayout.JAVA_SHORT.withName("Data3"),
            MemoryLayout.sequenceLayout(8, ValueLayout.JAVA_BYTE).withName("Data4"),
        )

        /**
         * A `GUID` from its canonical text. The first three fields are
         * little-endian integers and the last is a byte array in written order,
         * which is why the text form looks byte-swapped against a hex dump.
         */
        fun guid(arena: Arena, text: String): MemorySegment {
            val clean = text.replace("-", "")
            require(clean.length == 32) { "not a GUID: $text" }
            val segment = arena.allocate(GUID_LAYOUT)
            segment.set(ValueLayout.JAVA_INT, 0, clean.substring(0, 8).toLong(16).toInt())
            segment.set(ValueLayout.JAVA_SHORT, 4, clean.substring(8, 12).toInt(16).toShort())
            segment.set(ValueLayout.JAVA_SHORT, 6, clean.substring(12, 16).toInt(16).toShort())
            for (i in 0 until 8) {
                segment.set(ValueLayout.JAVA_BYTE, 8L + i, clean.substring(16 + i * 2, 18 + i * 2).toInt(16).toByte())
            }
            return segment
        }

        /** NUL-terminated UTF-16LE; every Windows text argument here is one. */
        fun wide(arena: Arena, text: String): MemorySegment {
            val chars = text.toCharArray()
            val segment = arena.allocate((chars.size + 1) * 2L, 2)
            for (i in chars.indices) segment.set(ValueLayout.JAVA_CHAR, i * 2L, chars[i])
            segment.set(ValueLayout.JAVA_CHAR, chars.size * 2L, NUL)
            return segment
        }

        private val ADDR = ValueLayout.ADDRESS
        private val I32 = ValueLayout.JAVA_INT
        private val I16 = ValueLayout.JAVA_SHORT

        private val LOAD_SET: List<Triple<String, MemoryLayout?, List<MemoryLayout>>> = listOf(
            // combase: the WinRT runtime itself.
            Triple("RoInitialize", I32, listOf(I32)),
            Triple("RoUninitialize", null, emptyList()),
            Triple("RoGetActivationFactory", I32, listOf(ADDR, ADDR, ADDR)),
            Triple("WindowsCreateString", I32, listOf(ADDR, I32, ADDR)),
            Triple("WindowsDeleteString", I32, listOf(ADDR)),

            // user32: the window a consumer without one still needs.
            Triple("RegisterClassExW", I16, listOf(ADDR)),
            Triple("CreateWindowExW", ADDR, listOf(I32, ADDR, ADDR, I32, I32, I32, I32, I32, ADDR, ADDR, ADDR, ADDR)),
            Triple("DestroyWindow", I32, listOf(ADDR)),
            Triple("GetModuleHandleW", ADDR, listOf(ADDR)),
        )

        private val LIBRARIES = listOf("combase.dll", "user32.dll", "kernel32.dll")

        /** Load the runtime, or null anywhere that is not Windows. */
        fun loadOrNull(): WinRt? {
            if (!System.getProperty("os.name", "").lowercase().contains("windows")) return null
            val arena = Arena.ofShared()
            val lookups = LIBRARIES.mapNotNull { name ->
                runCatching { SymbolLookup.libraryLookup(name, arena) }.getOrNull()
            }
            if (lookups.isEmpty()) {
                arena.close()
                return null
            }
            val linker = Linker.nativeLinker()
            val handles = HashMap<String, MethodHandle>(LOAD_SET.size * 2)
            for ((name, ret, args) in LOAD_SET) {
                val symbol = lookups.firstNotNullOfOrNull { it.find(name).orElse(null) } ?: run {
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
            val runtime = WinRt(arena, handles)
            runtime.user32 = lookups.first { it.find("DefWindowProcW").isPresent }
            return runtime
        }
    }
}
