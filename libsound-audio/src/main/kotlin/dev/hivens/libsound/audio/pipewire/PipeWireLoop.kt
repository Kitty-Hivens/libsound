package dev.hivens.libsound.audio.pipewire

import org.slf4j.LoggerFactory
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The `pw_thread_loop` every stream on this backend runs on.
 *
 * The same object `pa_threaded_mainloop` is, spelled differently: a thread the
 * library owns, a lock a caller takes before touching anything belonging to it,
 * and a condition to park on. So the rules that were expensive to learn on the
 * libpulse side transfer unchanged, and they are worth restating because they
 * are the ones that corrupt memory rather than merely fail.
 *
 * **The wait releases the lock.** That is what makes [AudioSink.latencyNanos]
 * and the playhead answerable while a write is parked, which the sink contract
 * requires and which every backend here satisfies by construction rather than
 * by care.
 *
 * **Nothing that belongs to the loop is touched without the lock**, including
 * teardown. A stream destroyed while the loop thread is dispatching on it is a
 * use-after-free inside libpipewire, not a style point.
 *
 * **The arena holding the upcall stubs outlives the thread that calls them.**
 * [close] stops the loop first and releases the library's arena afterwards.
 * Getting that order wrong is the one mistake in this file that corrupts memory
 * rather than failing, and it is the class of mistake the family has paid for
 * once already.
 */
internal class PipeWireLoop private constructor(
    val lib: PipeWireLibrary,
    private val threadLoop: MemorySegment,
) : AutoCloseable {

    private val log = LoggerFactory.getLogger("libsound.PipeWire")

    /** Compare-and-set, not check-then-set: a double destroy is native and uncatchable. */
    private val closed = AtomicBoolean(false)

    /** The `pw_loop` a stream is created against. */
    val loop: MemorySegment = lib.handle("pw_thread_loop_get_loop").invokeExact(threadLoop) as MemorySegment

    fun lock() {
        lib.handle("pw_thread_loop_lock").invokeExact(threadLoop) as Unit
    }

    fun unlock() {
        lib.handle("pw_thread_loop_unlock").invokeExact(threadLoop) as Unit
    }

    /** Park until something signals. The loop lock must be held, and is released while waiting. */
    fun await() {
        lib.handle("pw_thread_loop_wait").invokeExact(threadLoop) as Unit
    }

    /** Wake everyone parked in [await]. Safe from any thread that holds the lock. */
    fun signal() {
        lib.handle("pw_thread_loop_signal").invokeExact(threadLoop, 0) as Unit
    }

    inline fun <T> locked(body: () -> T): T {
        lock()
        try {
            return body()
        } finally {
            unlock()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Stopped before destroyed, and both before the arena goes. Stopping
        // joins the loop thread, which is the point past which no upcall can be
        // in flight.
        runCatching { lib.handle("pw_thread_loop_stop").invokeExact(threadLoop) as Unit }
            .onFailure { log.warn("loop stop threw: {}", it.message) }
        runCatching { lib.handle("pw_thread_loop_destroy").invokeExact(threadLoop) as Unit }
            .onFailure { log.warn("loop destroy threw: {}", it.message) }
        lib.close()
    }

    companion object {
        private val log = LoggerFactory.getLogger("libsound.PipeWire")

        /**
         * Start a loop, or return null where there is no graph to reach.
         *
         * Null is the ordinary "no PipeWire here" answer the selection falls
         * back on, not an error. A machine running real PulseAudio answers it,
         * and so does one with no sound server at all.
         */
        fun startOrNull(name: String): PipeWireLoop? {
            val lib = PipeWireLibrary.loadOrNull() ?: run {
                log.info(
                    "libpipewire could not be loaded by soname ({}). Either it is not installed, " +
                        "or it is not on this process's library search path.",
                    PipeWireLibrary.LIB_CANDIDATES.joinToString(", "),
                )
                return null
            }
            var threadLoop = MemorySegment.NULL
            return runCatching {
                threadLoop = Arena.ofConfined().use { setup ->
                    lib.handle("pw_thread_loop_new")
                        .invokeExact(setup.allocateFrom(name), MemorySegment.NULL) as MemorySegment
                }
                check(threadLoop.address() != 0L) { "pw_thread_loop_new failed" }
                val started = lib.handle("pw_thread_loop_start").invokeExact(threadLoop) as Int
                check(started >= 0) { "pw_thread_loop_start = $started" }
                PipeWireLoop(lib, threadLoop)
            }.getOrElse {
                log.debug("PipeWire unavailable: {}", it.message)
                // Unwind in the reverse order of construction, arena last, for
                // the reason close() does.
                if (threadLoop.address() != 0L) {
                    runCatching { lib.handle("pw_thread_loop_stop").invokeExact(threadLoop) as Unit }
                    runCatching { lib.handle("pw_thread_loop_destroy").invokeExact(threadLoop) as Unit }
                }
                lib.close()
                null
            }
        }
    }
}

/**
 * Read a `const char *` out of a pointer-valued field.
 *
 * Bounded rather than reinterpreted to `Long.MAX_VALUE`, for the reason the
 * libpulse and WASAPI readers are: a size is needed before the first read, and
 * an unbounded one turns a stray pointer into a scan of the whole address
 * space. Everything read here is a version string or a property value, so the
 * ceiling is a ceiling and not an expectation.
 */
internal fun MemorySegment.readCString(): String? {
    if (address() == 0L) return null
    return runCatching { reinterpret(MAX_C_STRING_BYTES).getString(0) }.getOrNull()
}

private const val MAX_C_STRING_BYTES = 64L * 1024
