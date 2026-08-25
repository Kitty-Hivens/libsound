package dev.hivens.libsound.audio.pulse

import dev.hivens.libsound.AudioException
import org.slf4j.LoggerFactory
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The `pa_threaded_mainloop` and the `pa_context` on it: one connection to the
 * sound server, shared by the backend's introspection and by every sink it
 * hands out.
 *
 * ## Why the wait belongs to us
 *
 * Every blocking operation in this backend is `pa_threaded_mainloop_wait`,
 * which is our own condition variable behind a lock we hold. That is the
 * structural difference from JavaSound, where a blocked write sits inside the
 * JDK and only closing the line can free it. Here another thread can lock the
 * mainloop and [signal], and a producer parked waiting for buffer space returns
 * in milliseconds -- measured, in the Phase 0 spike -- without the stream being
 * destroyed. The sink contract still requires close to unblock a write, because
 * a backend that does not own its wait has no cheaper option; this one simply
 * has a better lever available above that floor.
 *
 * ## Arena lifetime
 *
 * Upcall stubs are called from the mainloop thread, so the arena holding them
 * must outlive that thread. [close] therefore stops and frees the mainloop
 * first and only then releases the library's arena. Getting this order wrong is
 * the one mistake in this file that would corrupt memory rather than merely
 * fail, and it is the class of mistake the family has already paid for once.
 */
internal class PulseContext private constructor(
    val lib: PulseLibrary,
    private val mainloop: MemorySegment,
    val context: MemorySegment,
) : AutoCloseable {

    private val log = LoggerFactory.getLogger("libsound.Pulse")

    /** Compare-and-set, not check-then-set: a double free of the mainloop is native and uncatchable. */
    private val closed = AtomicBoolean(false)

    /** Notify callback for context and stream state changes: it only signals. */
    lateinit var notifyStub: MemorySegment
        private set

    /** Stream write-space callback: it only signals. */
    lateinit var writeRequestStub: MemorySegment
        private set

    /**
     * Bound to this instance rather than reached through a static field: two
     * contexts in one process would otherwise signal each other's mainloop, and
     * the failure would look like a random stall rather than a wiring mistake.
     */
    private fun installStubs() {
        val linker = Linker.nativeLinker()
        val lookup = MethodHandles.lookup()
        val notify = lookup.findVirtual(
            PulseContext::class.java, "onNotify",
            MethodType.methodType(Void.TYPE, MemorySegment::class.java, MemorySegment::class.java),
        ).bindTo(this)
        val writeRequest = lookup.findVirtual(
            PulseContext::class.java, "onWriteRequest",
            MethodType.methodType(
                Void.TYPE, MemorySegment::class.java, Long::class.javaPrimitiveType, MemorySegment::class.java,
            ),
        ).bindTo(this)
        notifyStub = linker.upcallStub(
            notify,
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS),
            lib.arena,
        )
        writeRequestStub = linker.upcallStub(
            writeRequest,
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS),
            lib.arena,
        )
    }

    // Public, not internal, although the class is internal and nothing outside
    // calls these: Kotlin mangles an internal function's name into
    // `onNotify$libsound_audio`, and `findVirtual` looks up the name as
    // written. The mangled lookup fails, construction falls into the catch, and
    // the backend reports "no sound server" on a machine that has one.

    /** Invoked on the mainloop thread, with its lock already held. */
    fun onNotify(unusedObject: MemorySegment, unusedUserData: MemorySegment) {
        runCatching { signal() }
    }

    fun onWriteRequest(unusedStream: MemorySegment, unusedBytes: Long, unusedUserData: MemorySegment) {
        runCatching { signal() }
    }

    fun lock() {
        lib.handle("pa_threaded_mainloop_lock").invokeExact(mainloop) as Unit
    }

    fun unlock() {
        lib.handle("pa_threaded_mainloop_unlock").invokeExact(mainloop) as Unit
    }

    /** Park until something signals. The mainloop lock must be held. */
    fun await() {
        lib.handle("pa_threaded_mainloop_wait").invokeExact(mainloop) as Unit
    }

    /** Wake everyone parked in [await]. Safe from any thread that holds the lock. */
    fun signal() {
        lib.handle("pa_threaded_mainloop_signal").invokeExact(mainloop, 0) as Unit
    }

    inline fun <T> locked(body: () -> T): T {
        lock()
        try {
            return body()
        } finally {
            unlock()
        }
    }

    /** Release a `pa_operation*` we do not wait on. */
    fun releaseOperation(op: MemorySegment) {
        if (op.address() != 0L) lib.handle("pa_operation_unref").invokeExact(op) as Unit
    }

    fun errno(): Int = lib.handle("pa_context_errno").invokeExact(context) as Int

    fun lastError(): String = lib.strerror(errno())

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching {
            // Both calls inside the lock. libpulse requires it for anything
            // touching an object owned by the mainloop, and unref is the one
            // that frees the context -- doing that while the loop thread is
            // dispatching on it is a use-after-free, not a style point.
            locked {
                lib.handle("pa_context_disconnect").invokeExact(context) as Unit
                lib.handle("pa_context_unref").invokeExact(context) as Unit
            }
        }.onFailure { log.warn("context teardown threw: {}", it.message) }
        runCatching {
            lib.handle("pa_threaded_mainloop_stop").invokeExact(mainloop) as Unit
            lib.handle("pa_threaded_mainloop_free").invokeExact(mainloop) as Unit
        }.onFailure { log.warn("mainloop teardown threw: {}", it.message) }
        // Only here. The mainloop thread is gone, so no upcall can be in flight
        // and nothing native still holds a pointer into this arena.
        lib.close()
    }

    companion object {
        private val log = LoggerFactory.getLogger("libsound.Pulse")

        /**
         * Connect to the sound server, or return null when there is none.
         *
         * Null is the ordinary "no PulseAudio or PipeWire here" answer that the
         * backend selection falls back on, not an error. A refusal that carries
         * a reason is logged once at that point rather than thrown.
         */
        fun connectOrNull(applicationName: String): PulseContext? {
            val lib = PulseLibrary.loadOrNull() ?: run {
                // Info rather than debug, and it names the cause. The library
                // is loaded by soname through dlopen, so this fires when
                // libpulse is absent -- and equally when it is installed but not
                // on the process's search path, which is the ordinary case on a
                // distribution that has no /usr/lib. There the fix is the
                // consumer's packaging, and a silent null gives them nothing to
                // go on.
                log.info(
                    "libpulse could not be loaded by soname ({}). Either it is not installed, " +
                        "or it is not on this process's library search path.",
                    PulseLibrary.LIB_CANDIDATES.joinToString(", "),
                )
                return null
            }
            var mainloop = MemorySegment.NULL
            var context = MemorySegment.NULL
            try {
                mainloop = lib.handle("pa_threaded_mainloop_new").invokeExact() as MemorySegment
                if (mainloop.address() == 0L) throw AudioException("pa_threaded_mainloop_new failed")
                val started = lib.handle("pa_threaded_mainloop_start").invokeExact(mainloop) as Int
                if (started < 0) throw AudioException("pa_threaded_mainloop_start = $started")

                val api = lib.handle("pa_threaded_mainloop_get_api").invokeExact(mainloop) as MemorySegment
                Arena.ofConfined().use { setup ->
                    val name = setup.allocateUtf8(applicationName)
                    context = lib.handle("pa_context_new").invokeExact(api, name) as MemorySegment
                }
                if (context.address() == 0L) throw AudioException("pa_context_new failed")

                val instance = PulseContext(lib, mainloop, context)
                instance.installStubs()

                instance.lock()
                try {
                    lib.handle("pa_context_set_state_callback")
                        .invokeExact(context, instance.notifyStub, MemorySegment.NULL) as Unit
                    val rc = lib.handle("pa_context_connect").invokeExact(
                        context, MemorySegment.NULL, PulseAbi.CONTEXT_NOFLAGS, MemorySegment.NULL,
                    ) as Int
                    if (rc < 0) {
                        throw AudioException("pa_context_connect: ${lib.strerror(instance.errno())}")
                    }
                    while (true) {
                        val state = lib.handle("pa_context_get_state").invokeExact(context) as Int
                        if (state == PulseAbi.CONTEXT_READY) break
                        if (state == PulseAbi.CONTEXT_FAILED || state == PulseAbi.CONTEXT_TERMINATED) {
                            throw AudioException("context state $state: ${lib.strerror(instance.errno())}")
                        }
                        instance.await()
                    }
                } finally {
                    instance.unlock()
                }
                return instance
            } catch (e: Throwable) {
                log.debug("PulseAudio unavailable: {}", e.message)
                // Unwind in the reverse order of construction. The arena goes
                // last for the same reason it does in close().
                runCatching {
                    if (context.address() != 0L) {
                        // Under the lock, for the same reason as close(): the
                        // mainloop is already running by this point.
                        lib.handle("pa_threaded_mainloop_lock").invokeExact(mainloop) as Unit
                        try {
                            lib.handle("pa_context_disconnect").invokeExact(context) as Unit
                            lib.handle("pa_context_unref").invokeExact(context) as Unit
                        } finally {
                            lib.handle("pa_threaded_mainloop_unlock").invokeExact(mainloop) as Unit
                        }
                    }
                }
                runCatching {
                    if (mainloop.address() != 0L) {
                        lib.handle("pa_threaded_mainloop_stop").invokeExact(mainloop) as Unit
                        lib.handle("pa_threaded_mainloop_free").invokeExact(mainloop) as Unit
                    }
                }
                lib.close()
                return null
            }
        }
    }
}
