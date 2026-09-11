package dev.hivens.libsound.audio.pipewire

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

/**
 * Panama bindings to `libpipewire`, in the family's style: the symbols this
 * backend needs and no others, no generator, small enough to read in one
 * sitting.
 *
 * The library is loaded by exact soname, for the reason libpulse is: the
 * unversioned `libpipewire-0.3.so` symlink belongs to the development package
 * and is absent on most machines that run the graph perfectly well.
 *
 * ## Why the surface is this small
 *
 * Audio travels on `pw_stream` rather than on a `pw_node` assembled by hand. A
 * stream is a node with the buffer handling done, which is the same trade
 * `pa_stream` is against the raw PulseAudio protocol, and it means a playback
 * path needs a thread loop, a stream and nothing between them.
 *
 * The context and core below it are here for the registry, which is a second
 * connection doing a different job: what is on the graph, which device is
 * default, and what each one's volume is. Section 13.8 of the plan draws that
 * line, and what stays on its far side is arbitrary port linking.
 */
internal class PipeWireLibrary private constructor(
    val arena: Arena,
    private val lookup: SymbolLookup,
    private val handles: Map<String, MethodHandle>,
) {
    /**
     * A previously-resolved handle. Throws when the symbol was not in the load
     * set, which is a programmer error rather than a runtime condition.
     */
    fun handle(name: String): MethodHandle =
        handles[name] ?: error("libpipewire handle not loaded: $name. Add it to LOAD_SET.")

    fun close() {
        runCatching { arena.close() }
    }

    companion object {
        /** Exact soname first; the bare name only as a development courtesy. */
        val LIB_CANDIDATES: List<String> = listOf("libpipewire-0.3.so.0", "libpipewire-0.3.so")

        /** Everything past the fourth argument of pw_stream_set_control is variadic. */
        private const val VARIADIC_FROM = 4

        private val ADDR = ValueLayout.ADDRESS
        private val I32 = ValueLayout.JAVA_INT
        private val I64 = ValueLayout.JAVA_LONG

        /**
         * name -> (return layout or null for void, argument layouts).
         *
         * Around thirty, plus the one variadic call bound separately below.
         * Expand deliberately: every addition is a new piece of ABI surface to
         * keep correct.
         */
        private val LOAD_SET: List<Triple<String, MemoryLayout?, List<MemoryLayout>>> = listOf(
            // Initialised once per process and never torn down. pw_deinit is
            // bound and not called, for the reason CoUninitialize is on the
            // Windows side: the balance cannot be struck from a library that
            // does not own the process, and a binding is what makes it possible
            // the day it can be.
            Triple("pw_init", null, listOf(ADDR, ADDR)),
            Triple("pw_deinit", null, emptyList()),
            Triple("pw_get_library_version", ADDR, emptyList()),

            // The loop, which owns its own thread the way pa_threaded_mainloop
            // does, with the same lock and the same signal.
            Triple("pw_thread_loop_new", ADDR, listOf(ADDR, ADDR)),
            Triple("pw_thread_loop_destroy", null, listOf(ADDR)),
            Triple("pw_thread_loop_start", I32, listOf(ADDR)),
            Triple("pw_thread_loop_stop", null, listOf(ADDR)),
            Triple("pw_thread_loop_lock", null, listOf(ADDR)),
            Triple("pw_thread_loop_unlock", null, listOf(ADDR)),
            Triple("pw_thread_loop_wait", null, listOf(ADDR)),
            // The bounded sibling, for a wait that has to end even when the
            // thing it is waiting for never arrives. A connect uses it: a
            // server that accepts a sync and never answers it would otherwise
            // park the calling thread for the life of the process.
            Triple("pw_thread_loop_timed_wait", I32, listOf(ADDR, I32)),
            // The second argument is a C bool, not an int. They travel in the
            // same register here and this is only ever called with zero, so the
            // wrong layout worked; declared properly because Panama checks
            // neither, and a stray high bit would turn this into a wait for an
            // accept nothing in this binding ever sends.
            Triple("pw_thread_loop_signal", null, listOf(ADDR, ValueLayout.JAVA_BOOLEAN)),
            Triple("pw_thread_loop_get_loop", ADDR, listOf(ADDR)),

            // The stream, which is the whole playback path.
            Triple("pw_stream_new_simple", ADDR, listOf(ADDR, ADDR, ADDR, ADDR, ADDR)),
            Triple("pw_stream_destroy", null, listOf(ADDR)),
            Triple("pw_stream_connect", I32, listOf(ADDR, I32, I32, I32, ADDR, I32)),
            Triple("pw_stream_disconnect", I32, listOf(ADDR)),
            Triple("pw_stream_set_active", I32, listOf(ADDR, ValueLayout.JAVA_BOOLEAN)),
            Triple("pw_stream_flush", I32, listOf(ADDR, ValueLayout.JAVA_BOOLEAN)),
            Triple("pw_stream_get_state", I32, listOf(ADDR, ADDR)),
            Triple("pw_stream_get_time_n", I32, listOf(ADDR, ADDR, I64)),
            Triple("pw_stream_dequeue_buffer", ADDR, listOf(ADDR)),
            Triple("pw_stream_queue_buffer", I32, listOf(ADDR, ADDR)),
            Triple("pw_stream_update_params", I32, listOf(ADDR, ADDR, I32)),

            // The properties a node is named and placed by. Built with the
            // varargs constructor's non-varargs sibling, because a Panama
            // downcall to a varargs function needs a descriptor per call shape
            // and this needs none.
            Triple("pw_properties_new_dict", ADDR, listOf(ADDR)),
            Triple("pw_properties_set", I32, listOf(ADDR, ADDR, ADDR)),
            Triple("pw_properties_free", null, listOf(ADDR)),

            // The context and the core, which exist for one thing: a registry
            // hangs off a core and a stream created with pw_stream_new_simple
            // gets a core of its own. That separation is deliberate rather than
            // incidental, and the reason is the one the libpulse mixer already
            // gives: a registry reports every object on the graph, and putting
            // that traffic on the connection carrying audio timing is what the
            // other backend takes a second connection to avoid.
            Triple("pw_context_new", ADDR, listOf(ADDR, ADDR, I64)),
            Triple("pw_context_destroy", null, listOf(ADDR)),
            Triple("pw_context_connect", ADDR, listOf(ADDR, ADDR, I64)),
            Triple("pw_core_disconnect", I32, listOf(ADDR)),

            // A proxy's listener. Getting the registry itself is a macro over
            // the core's method table, so it is walked by hand rather than
            // bound: see SpaAbi's note on calling a proxy method.
            Triple("pw_proxy_add_object_listener", null, listOf(ADDR, ADDR, ADDR, ADDR)),
            Triple("pw_proxy_destroy", null, listOf(ADDR)),
            // Which proxy a refusal was about. The core reports one by the id
            // it gave the object, and a write that wants to answer for itself
            // has to match the two up.
            Triple("pw_proxy_get_id", I32, listOf(ADDR)),
        )

        /**
         * Load libpipewire and bind every symbol in the load set, or return
         * null.
         *
         * Null where the library is absent or any symbol is missing, either of
         * which sends the selection to the next backend. Never a partially
         * bound library: finding a missing symbol at the call site is how a
         * backend half works.
         */
        fun loadOrNull(): PipeWireLibrary? {
            val arena = Arena.ofShared()
            val lookup = LIB_CANDIDATES.firstNotNullOfOrNull { name ->
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
            val library = PipeWireLibrary(arena, lookup, handles)
            // Once per process. Calling it twice is documented as harmless and
            // this is the only path that reaches it.
            runCatching {
                library.handle("pw_init").invokeExact(MemorySegment.NULL, MemorySegment.NULL) as Unit
            }
            return library
        }
    }

    /**
     * `pw_stream_set_control`, which is variadic and therefore bound apart from
     * the rest.
     *
     * A Panama downcall to a variadic function needs a descriptor per call
     * shape, so it cannot live in a table of name-to-descriptor like everything
     * else here. This is the one shape the library uses: one control, then the
     * zero that ends the list, because the implementation reads triples until
     * it meets one.
     */
    val setControl: MethodHandle by lazy {
        val symbol = checkNotNull(lookup.find("pw_stream_set_control").orElse(null)) {
            "libpipewire has no pw_stream_set_control"
        }
        Linker.nativeLinker().downcallHandle(
            symbol,
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
            ),
            Linker.Option.firstVariadicArg(VARIADIC_FROM),
        )
    }

    /** The runtime's own version, for the one line of log that says what was reached. */
    fun version(): String? =
        (handle("pw_get_library_version").invokeExact() as MemorySegment).readCString()
}
