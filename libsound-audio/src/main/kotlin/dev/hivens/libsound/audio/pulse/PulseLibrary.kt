package dev.hivens.libsound.audio.pulse

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

/**
 * Panama bindings to `libpulse` -- only the symbols this task needs, in the
 * family's style: no general libpulse binding, no generator, small enough to
 * read in one sitting.
 *
 * The async API rather than the simple one, because the simple API offers
 * neither per-stream volume nor device selection, which are two of the three
 * reasons this backend exists.
 *
 * The library is loaded by exact soname. The unversioned `libpulse.so` symlink
 * belongs to the `-dev` package and is absent on most machines that can play
 * audio perfectly well; loading it by the name a developer sees would make the
 * backend unavailable exactly where it should work.
 */
internal class PulseLibrary private constructor(
    val arena: Arena,
    private val handles: Map<String, MethodHandle>,
) {
    /**
     * A previously-resolved handle. Throws when the symbol was not in the load
     * set -- a programmer error, not a runtime condition to degrade on.
     */
    fun handle(name: String): MethodHandle =
        handles[name] ?: error("libpulse handle not loaded: $name. Add it to LOAD_SET.")

    fun close() {
        runCatching { arena.close() }
    }

    companion object {
        /** Exact soname first; the bare name only as a development courtesy. */
        private val LIB_CANDIDATES = listOf("libpulse.so.0", "libpulse.so", "libpulse.0.dylib")

        private val ADDR = ValueLayout.ADDRESS
        private val I32 = ValueLayout.JAVA_INT
        private val I64 = ValueLayout.JAVA_LONG
        private val F64 = ValueLayout.JAVA_DOUBLE

        /**
         * name -> (return layout or null for void, argument layouts).
         *
         * Roughly thirty symbols, as the plan budgeted. Expand deliberately:
         * every addition here is a new piece of ABI surface to keep correct.
         */
        private val LOAD_SET: List<Triple<String, MemoryLayout?, List<MemoryLayout>>> = listOf(
            // Threaded mainloop -- the library owns its own loop thread.
            Triple("pa_threaded_mainloop_new", ADDR, emptyList()),
            Triple("pa_threaded_mainloop_free", null, listOf(ADDR)),
            Triple("pa_threaded_mainloop_start", I32, listOf(ADDR)),
            Triple("pa_threaded_mainloop_stop", null, listOf(ADDR)),
            Triple("pa_threaded_mainloop_lock", null, listOf(ADDR)),
            Triple("pa_threaded_mainloop_unlock", null, listOf(ADDR)),
            Triple("pa_threaded_mainloop_wait", null, listOf(ADDR)),
            Triple("pa_threaded_mainloop_signal", null, listOf(ADDR, I32)),
            Triple("pa_threaded_mainloop_get_api", ADDR, listOf(ADDR)),

            // Context lifecycle.
            Triple("pa_context_new", ADDR, listOf(ADDR, ADDR)),
            Triple("pa_context_set_state_callback", null, listOf(ADDR, ADDR, ADDR)),
            Triple("pa_context_connect", I32, listOf(ADDR, ADDR, I32, ADDR)),
            Triple("pa_context_disconnect", null, listOf(ADDR)),
            Triple("pa_context_unref", null, listOf(ADDR)),
            Triple("pa_context_get_state", I32, listOf(ADDR)),
            Triple("pa_context_errno", I32, listOf(ADDR)),

            // Server introspection and events.
            Triple("pa_context_get_sink_info_list", ADDR, listOf(ADDR, ADDR, ADDR)),
            Triple("pa_context_get_server_info", ADDR, listOf(ADDR, ADDR, ADDR)),
            Triple("pa_context_subscribe", ADDR, listOf(ADDR, I32, ADDR, ADDR)),
            Triple("pa_context_set_subscribe_callback", null, listOf(ADDR, ADDR, ADDR)),
            Triple("pa_context_set_sink_input_volume", ADDR, listOf(ADDR, I32, ADDR, ADDR, ADDR)),

            // The mixer half: everyone else's streams, on the same context the
            // sink already opened and through the same introspection shape.
            Triple("pa_context_get_sink_input_info_list", ADDR, listOf(ADDR, ADDR, ADDR)),
            Triple("pa_context_get_sink_input_info", ADDR, listOf(ADDR, I32, ADDR, ADDR)),
            Triple("pa_context_set_sink_input_mute", ADDR, listOf(ADDR, I32, I32, ADDR, ADDR)),
            Triple("pa_context_move_sink_input_by_name", ADDR, listOf(ADDR, I32, ADDR, ADDR, ADDR)),
            Triple("pa_context_get_sink_info_by_index", ADDR, listOf(ADDR, I32, ADDR, ADDR)),
            Triple("pa_proplist_gets", ADDR, listOf(ADDR, ADDR)),
            Triple("pa_sw_volume_to_linear", F64, listOf(I32)),
            Triple("pa_cvolume_max", I32, listOf(ADDR)),

            // Stream lifecycle.
            Triple("pa_stream_new_with_proplist", ADDR, listOf(ADDR, ADDR, ADDR, ADDR, ADDR)),
            Triple("pa_stream_set_state_callback", null, listOf(ADDR, ADDR, ADDR)),
            Triple("pa_stream_set_write_callback", null, listOf(ADDR, ADDR, ADDR)),
            Triple("pa_stream_connect_playback", I32, listOf(ADDR, ADDR, ADDR, I32, ADDR, ADDR)),
            Triple("pa_stream_disconnect", I32, listOf(ADDR)),
            Triple("pa_stream_unref", null, listOf(ADDR)),
            Triple("pa_stream_get_state", I32, listOf(ADDR)),
            Triple("pa_stream_get_index", I32, listOf(ADDR)),
            Triple("pa_stream_get_buffer_attr", ADDR, listOf(ADDR)),

            // Stream data path and playhead.
            Triple("pa_stream_write", I32, listOf(ADDR, ADDR, I64, ADDR, I64, I32)),
            Triple("pa_stream_writable_size", I64, listOf(ADDR)),
            Triple("pa_stream_cork", ADDR, listOf(ADDR, I32, ADDR, ADDR)),
            Triple("pa_stream_flush", ADDR, listOf(ADDR, ADDR, ADDR)),
            Triple("pa_stream_get_time", I32, listOf(ADDR, ADDR)),
            Triple("pa_stream_get_latency", I32, listOf(ADDR, ADDR, ADDR)),
            Triple("pa_stream_update_timing_info", ADDR, listOf(ADDR, ADDR, ADDR)),

            // Proplist -- the stream's identity.
            Triple("pa_proplist_new", ADDR, emptyList()),
            Triple("pa_proplist_sets", I32, listOf(ADDR, ADDR, ADDR)),
            Triple("pa_proplist_free", null, listOf(ADDR)),

            // Volume helpers. Filling pa_cvolume by hand is possible with the
            // oracle's offsets, but the library's own setter cannot disagree
            // with the library's own layout.
            Triple("pa_cvolume_set", ADDR, listOf(ADDR, I32, I32)),
            Triple("pa_sw_volume_from_linear", I32, listOf(F64)),

            // Operations and diagnostics.
            Triple("pa_operation_unref", null, listOf(ADDR)),
            Triple("pa_strerror", ADDR, listOf(I32)),
        )

        /**
         * Load libpulse and bind every symbol in the load set.
         *
         * Returns null when the library is absent or any symbol is missing --
         * either way the selection above falls back, which is a supported state
         * rather than a failure. A partially-bound library is never returned:
         * discovering a missing symbol at the call site is how a backend
         * half-works.
         */
        fun loadOrNull(): PulseLibrary? {
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
            return PulseLibrary(arena, handles)
        }
    }

    /** `pa_strerror`, for the one log line that explains a refusal. */
    fun strerror(code: Int): String {
        val p = handle("pa_strerror").invokeExact(code) as MemorySegment
        return if (p.address() == 0L) "errno $code" else p.reinterpret(Long.MAX_VALUE).getString(0)
    }
}

/** Allocate a NUL-terminated UTF-8 string; libpulse takes `const char *` throughout. */
internal fun Arena.allocateUtf8(value: String): MemorySegment = allocateFrom(value)
