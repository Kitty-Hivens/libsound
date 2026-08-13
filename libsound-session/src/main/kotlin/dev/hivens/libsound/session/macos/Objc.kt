package dev.hivens.libsound.session.macos

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.util.concurrent.ConcurrentHashMap

/**
 * The Objective-C runtime through Panama: classes, selectors, message sends and
 * a block the framework will accept.
 *
 * ## objc_msgSend needs one handle per signature
 *
 * It is not a normal function. The compiler emits a call to it with the exact
 * argument list of the method being sent, and the ABI is that list -- there is
 * no single descriptor that covers `-setEnabled:` and `-setObject:forKey:`.
 * So the load set below binds the same symbol several times under different
 * names, one per shape this backend actually sends. Adding a call shape means
 * adding an entry, deliberately: the alternative is a generic sender that is
 * wrong for every shape but one.
 *
 * The same shape as libtray's `ObjcBindings`, for the same reason, and not
 * shared with it -- these are separate libraries and neither should depend on
 * the other to send a message.
 */
internal class Objc private constructor(
    val arena: Arena,
    private val handles: Map<String, MethodHandle>,
    private val lookups: List<SymbolLookup>,
) : AutoCloseable {

    private val classes = ConcurrentHashMap<String, MemorySegment>()
    private val selectors = ConcurrentHashMap<String, MemorySegment>()

    fun handle(name: String): MethodHandle =
        handles[name] ?: error("objc handle not loaded: $name. Add it to LOAD_SET.")

    /** A class by name, cached. Null-checked by the caller: a missing class is a real answer. */
    fun cls(name: String): MemorySegment = classes.getOrPut(name) {
        Arena.ofConfined().use { call ->
            handle("objc_getClass").invokeExact(call.allocateFrom(name)) as MemorySegment
        }
    }

    /** A selector by name, cached. Registering the same name twice returns the same pointer. */
    fun sel(name: String): MemorySegment = selectors.getOrPut(name) {
        Arena.ofConfined().use { call ->
            handle("sel_registerName").invokeExact(call.allocateFrom(name)) as MemorySegment
        }
    }

    /** `[receiver selector]` returning an object. */
    fun send(receiver: MemorySegment, selector: String): MemorySegment =
        handle("msgSend_obj").invokeExact(receiver, sel(selector)) as MemorySegment

    /** `[receiver selector: object]` returning an object. */
    fun send(receiver: MemorySegment, selector: String, arg: MemorySegment): MemorySegment =
        handle("msgSend_obj_obj").invokeExact(receiver, sel(selector), arg) as MemorySegment

    /** `[receiver selector: a forKey: b]`, returning nothing worth keeping. */
    fun send(receiver: MemorySegment, selector: String, a: MemorySegment, b: MemorySegment) {
        handle("msgSend_void_obj_obj").invokeExact(receiver, sel(selector), a, b) as Unit
    }

    /** `[receiver selector: aLong]`, for the enums and the BOOL setters. */
    fun sendLong(receiver: MemorySegment, selector: String, value: Long) {
        handle("msgSend_void_long").invokeExact(receiver, sel(selector), value) as Unit
    }

    /** `[receiver selector: aDouble]` returning an object -- `+numberWithDouble:`. */
    fun sendDouble(receiver: MemorySegment, selector: String, value: Double): MemorySegment =
        handle("msgSend_obj_double").invokeExact(receiver, sel(selector), value) as MemorySegment

    /** An autoreleased `NSString`, valid until the enclosing pool drains. */
    fun nsString(arena: Arena, text: String): MemorySegment =
        handle("msgSend_obj_ptr").invokeExact(
            cls(MediaPlayerAbi.CLASS_STRING),
            sel(MediaPlayerAbi.SEL_STRING_WITH_UTF8),
            arena.allocateFrom(text),
        ) as MemorySegment

    /**
     * A block the runtime will accept, wrapping an upcall stub.
     *
     * Global rather than stack: a global block is never copied and never freed,
     * which is the only kind safe to hand over when the memory belongs to an
     * arena we close ourselves. A stack block would have the runtime copy it to
     * the heap and later free something it does not own.
     *
     * The signature is the encoding the compiler writes for this block's type.
     * Without `BLOCK_HAS_SIGNATURE` and that string, the object claims to be a
     * different kind of block from the one the framework was compiled against.
     */
    fun globalBlock(invoke: MemorySegment): MemorySegment {
        val descriptor = arena.allocate(MediaPlayerAbi.DESCRIPTOR_WITH_SIGNATURE_SIZE, 8)
        descriptor.set(ValueLayout.JAVA_LONG, MediaPlayerAbi.DESCRIPTOR_RESERVED, 0L)
        descriptor.set(ValueLayout.JAVA_LONG, MediaPlayerAbi.DESCRIPTOR_SIZE, MediaPlayerAbi.BLOCK_SIZE)
        descriptor.set(
            ValueLayout.ADDRESS, MediaPlayerAbi.DESCRIPTOR_SIGNATURE,
            arena.allocateFrom(MediaPlayerAbi.BLOCK_SIGNATURE),
        )

        val block = arena.allocate(MediaPlayerAbi.BLOCK_SIZE, 8)
        block.set(ValueLayout.ADDRESS, MediaPlayerAbi.BLOCK_ISA, concreteGlobalBlock())
        block.set(ValueLayout.JAVA_INT, MediaPlayerAbi.BLOCK_FLAGS, MediaPlayerAbi.BLOCK_FLAGS_GLOBAL_WITH_SIGNATURE)
        block.set(ValueLayout.JAVA_INT, MediaPlayerAbi.BLOCK_RESERVED, 0)
        block.set(ValueLayout.ADDRESS, MediaPlayerAbi.BLOCK_INVOKE, invoke)
        block.set(ValueLayout.ADDRESS, MediaPlayerAbi.BLOCK_DESCRIPTOR, descriptor)
        return block
    }

    private fun concreteGlobalBlock(): MemorySegment =
        lookups.firstNotNullOfOrNull { it.find(MediaPlayerAbi.CONCRETE_GLOBAL_BLOCK).orElse(null) }
            ?: error("libSystem has no ${MediaPlayerAbi.CONCRETE_GLOBAL_BLOCK}")

    override fun close() {
        runCatching { arena.close() }
    }

    internal companion object {
        private val ADDR = ValueLayout.ADDRESS
        private val I64 = ValueLayout.JAVA_LONG
        private val F64 = ValueLayout.JAVA_DOUBLE

        /**
         * alias -> (symbol, return layout or null, arguments).
         *
         * `objc_msgSend` appears several times because its ABI is the ABI of
         * whatever method is being sent; one entry per shape this backend uses.
         */
        private val LOAD_SET: List<Triple<String, String, FunctionDescriptor>> = listOf(
            Triple("objc_getClass", "objc_getClass", FunctionDescriptor.of(ADDR, ADDR)),
            Triple("sel_registerName", "sel_registerName", FunctionDescriptor.of(ADDR, ADDR)),

            Triple("msgSend_obj", "objc_msgSend", FunctionDescriptor.of(ADDR, ADDR, ADDR)),
            Triple("msgSend_obj_obj", "objc_msgSend", FunctionDescriptor.of(ADDR, ADDR, ADDR, ADDR)),
            Triple("msgSend_obj_ptr", "objc_msgSend", FunctionDescriptor.of(ADDR, ADDR, ADDR, ADDR)),
            Triple("msgSend_obj_double", "objc_msgSend", FunctionDescriptor.of(ADDR, ADDR, ADDR, F64)),
            Triple("msgSend_void_obj_obj", "objc_msgSend", FunctionDescriptor.ofVoid(ADDR, ADDR, ADDR, ADDR)),
            Triple("msgSend_void_long", "objc_msgSend", FunctionDescriptor.ofVoid(ADDR, ADDR, I64)),
        )

        private val FRAMEWORKS = listOf(
            "/usr/lib/libobjc.A.dylib",
            "/System/Library/Frameworks/Foundation.framework/Foundation",
            "/System/Library/Frameworks/MediaPlayer.framework/MediaPlayer",
        )

        /** Load the runtime and MediaPlayer, or null anywhere that is not macOS. */
        fun loadOrNull(): Objc? {
            if (!System.getProperty("os.name", "").lowercase().contains("mac")) return null
            val arena = Arena.ofShared()
            val lookups = FRAMEWORKS.mapNotNull { path ->
                runCatching { SymbolLookup.libraryLookup(path, arena) }.getOrNull()
            }
            if (lookups.isEmpty()) {
                arena.close()
                return null
            }
            val linker = Linker.nativeLinker()
            val handles = HashMap<String, MethodHandle>(LOAD_SET.size * 2)
            for ((alias, symbol, descriptor) in LOAD_SET) {
                val address = lookups.firstNotNullOfOrNull { it.find(symbol).orElse(null) } ?: run {
                    arena.close()
                    return null
                }
                handles[alias] = linker.downcallHandle(address, descriptor)
            }
            return Objc(arena, handles, lookups)
        }
    }
}
