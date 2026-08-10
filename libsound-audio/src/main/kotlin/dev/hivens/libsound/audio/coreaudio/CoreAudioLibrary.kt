package dev.hivens.libsound.audio.coreaudio

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

/**
 * Panama bindings to the CoreAudio subset this backend needs.
 *
 * Three frameworks, because the surface is split across them and the split does
 * not follow the naming: the hardware queries are in CoreAudio, everything about
 * an audio unit is in AudioToolbox, and reading a device's name means handling a
 * `CFStringRef` from CoreFoundation. A symbol is looked for in each in turn
 * rather than assigned to one by hand, since which framework re-exports what has
 * changed across releases and is not worth encoding.
 *
 * Loaded by framework path. There is no versioned soname problem here as there
 * is on Linux -- the frameworks live at a fixed location and dyld resolves them
 * out of the shared cache whether or not a file exists at that path.
 */
internal class CoreAudioLibrary private constructor(
    val arena: Arena,
    private val handles: Map<String, MethodHandle>,
) {

    fun handle(name: String): MethodHandle =
        handles[name] ?: error("CoreAudio handle not loaded: $name. Add it to LOAD_SET.")

    fun close() {
        runCatching { arena.close() }
    }

    companion object {
        private val FRAMEWORKS = listOf(
            "/System/Library/Frameworks/AudioToolbox.framework/AudioToolbox",
            "/System/Library/Frameworks/CoreAudio.framework/CoreAudio",
            "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation",
        )

        private val ADDR = ValueLayout.ADDRESS
        private val I32 = ValueLayout.JAVA_INT
        private val I64 = ValueLayout.JAVA_LONG
        private val F32 = ValueLayout.JAVA_FLOAT
        private val I8 = ValueLayout.JAVA_BYTE

        /** name -> (return layout or null for void, argument layouts). */
        private val LOAD_SET: List<Triple<String, MemoryLayout?, List<MemoryLayout>>> = listOf(
            // The hardware: what devices exist, which is default, and a listener
            // for when either answer changes.
            Triple("AudioObjectGetPropertyDataSize", I32, listOf(I32, ADDR, I32, ADDR, ADDR)),
            Triple("AudioObjectGetPropertyData", I32, listOf(I32, ADDR, I32, ADDR, ADDR, ADDR)),
            Triple("AudioObjectAddPropertyListener", I32, listOf(I32, ADDR, ADDR, ADDR)),
            Triple("AudioObjectRemovePropertyListener", I32, listOf(I32, ADDR, ADDR, ADDR)),

            // The output unit, which is the whole playback path.
            Triple("AudioComponentFindNext", ADDR, listOf(ADDR, ADDR)),
            Triple("AudioComponentInstanceNew", I32, listOf(ADDR, ADDR)),
            Triple("AudioComponentInstanceDispose", I32, listOf(ADDR)),
            Triple("AudioUnitInitialize", I32, listOf(ADDR)),
            Triple("AudioUnitUninitialize", I32, listOf(ADDR)),
            Triple("AudioUnitSetProperty", I32, listOf(ADDR, I32, I32, I32, ADDR, I32)),
            Triple("AudioUnitSetParameter", I32, listOf(ADDR, I32, I32, I32, F32, I32)),
            Triple("AudioOutputUnitStart", I32, listOf(ADDR)),
            Triple("AudioOutputUnitStop", I32, listOf(ADDR)),

            // A device's name and uid arrive as CFStringRef and have to be read
            // out and released; nothing else here touches CoreFoundation.
            Triple("CFStringGetCString", I8, listOf(ADDR, ADDR, I64, I32)),
            Triple("CFStringGetLength", I64, listOf(ADDR)),
            Triple("CFRelease", null, listOf(ADDR)),
        )

        /**
         * Load the frameworks and bind every symbol, or return null.
         *
         * Null on any platform that is not macOS, and on a macOS missing a
         * symbol -- either way the selection above falls back, which is a
         * supported state. Never a partially-bound library: finding a missing
         * symbol at the call site is how a backend half-works.
         */
        fun loadOrNull(): CoreAudioLibrary? {
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
            return CoreAudioLibrary(arena, handles)
        }
    }

    // -- shared helpers ------------------------------------------------------

    /** Fill an `AudioObjectPropertyAddress` in place. */
    fun address(into: MemorySegment, selector: Int, scope: Int, element: Int = CoreAudioAbi.ELEMENT_MAIN) {
        into.set(ValueLayout.JAVA_INT, CoreAudioAbi.ADDRESS_SELECTOR, selector)
        into.set(ValueLayout.JAVA_INT, CoreAudioAbi.ADDRESS_SCOPE, scope)
        into.set(ValueLayout.JAVA_INT, CoreAudioAbi.ADDRESS_ELEMENT, element)
    }

    /**
     * Read a `CFStringRef` out and release it.
     *
     * The get-rule applies: `AudioObjectGetPropertyData` returns a string this
     * side owns, and every one of these that is not released is a leak per call
     * on a path a settings screen may poll.
     */
    fun cfStringAndRelease(cf: MemorySegment): String? {
        if (cf.address() == 0L) return null
        try {
            val length = handle("CFStringGetLength").invokeExact(cf) as Long
            // Four bytes per code point is the worst case for UTF-8, plus the
            // terminator CFStringGetCString writes.
            val capacity = (length * 4 + 1).coerceAtLeast(16L)
            Arena.ofConfined().use { call ->
                val buffer = call.allocate(capacity)
                val ok = handle("CFStringGetCString")
                    .invokeExact(cf, buffer, capacity, CoreAudioAbi.CF_ENCODING_UTF8) as Byte
                return if (ok.toInt() == 0) null else buffer.getString(0).ifBlank { null }
            }
        } finally {
            runCatching { handle("CFRelease").invokeExact(cf) as Unit }
        }
    }
}
