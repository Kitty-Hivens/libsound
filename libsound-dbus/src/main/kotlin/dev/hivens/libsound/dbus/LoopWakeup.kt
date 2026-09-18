package dev.hivens.libsound.dbus

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

/**
 * What the I/O loop waits on, and the one descriptor that can end that wait
 * early.
 *
 * `dbus_connection_read_write` blocks on the bus socket for the whole interval
 * it is given and offers no way out: the queues a caller pushes into are ours,
 * in Java, and libdbus knows nothing about them. So everything queued waited for
 * the current interval to run out, measured at 3 to 99 ms for a signal and at
 * one interval per round trip for a read of another player.
 *
 * The answer is the shape every other client on the bus already has: wait on the
 * connection's own descriptor rather than on a clock, and put a second
 * descriptor of ours beside it so that queueing something is itself an event.
 * The interval that remains is a heartbeat rather than a latency floor.
 *
 * An `eventfd` rather than a pipe: one descriptor instead of two, and a counter
 * that coalesces, so a burst of queued messages costs one wakeup rather than
 * one each.
 *
 * Every number here is printed by `tools/dbus-oracle.c`, like the rest of this
 * module's ABI, for the reason that file gives: a `struct pollfd` whose fields
 * are at the wrong offsets is a wait on a descriptor nobody named.
 */
internal class LoopWakeup private constructor(
    private val arena: Arena,
    /** Two `struct pollfd`, touched only by the loop thread. */
    private val fds: MemorySegment,
    /** Where the counter is read into. The loop thread's, like [fds]. */
    private val counter: MemorySegment,
    /**
     * The one written to wake the loop.
     *
     * Filled once at construction and read-only afterwards, which is what lets
     * every thread share it: the value is always one, so there is nothing for
     * two writers to disagree about.
     */
    private val one: MemorySegment,
    private val wakeFd: Int,
) {

    /**
     * End the wait now. Called from whichever thread queued the work, and safe
     * to call when nothing is waiting: the counter keeps until it is read.
     */
    fun wake() {
        runCatching { Libc.write.invokeExact(wakeFd, one, java.lang.Long.BYTES.toLong()) as Long }
    }

    /**
     * Wait for the bus, for a [wake], or for [timeoutMillis] to pass.
     *
     * Only the loop thread waits. A negative return is `EINTR` or a descriptor
     * that has gone, and both are answered by going round the loop again, where
     * the connection's own state is what decides.
     */
    fun await(timeoutMillis: Int) {
        val ready = runCatching { Libc.poll.invokeExact(fds, FD_COUNT, timeoutMillis) as Int }.getOrDefault(-1)
        if (ready <= 0) return
        val revents = fds.get(ValueLayout.JAVA_SHORT, WAKE_SLOT + POLLFD_REVENTS).toInt()
        // Anything at all on our own descriptor, not only readability: a
        // counter left unread would make every later wait return at once.
        if (revents != 0) {
            runCatching { Libc.read.invokeExact(wakeFd, counter, java.lang.Long.BYTES.toLong()) as Long }
        }
    }

    /** Give the descriptor back. The loop thread must have stopped. */
    fun close() {
        runCatching { Libc.close.invokeExact(wakeFd) as Int }
        runCatching { arena.close() }
    }

    companion object {

        /** `sizeof(struct pollfd)`, and the three fields inside it. */
        private const val POLLFD_SIZE = 8L
        private const val POLLFD_FD = 0L
        private const val POLLFD_EVENTS = 4L
        private const val POLLFD_REVENTS = 6L

        private const val BUS_SLOT = 0L
        private const val WAKE_SLOT = POLLFD_SIZE

        private const val FD_COUNT = 2L

        private const val POLLIN: Short = 1

        private const val EFD_CLOEXEC = 524288
        private const val EFD_NONBLOCK = 2048

        /**
         * Build the wait, or answer null for a connection with no descriptor to
         * wait on.
         *
         * Null is a supported state rather than a failure: the loop keeps the
         * timed `read_write` it had, which works and is merely slower. A
         * transport that is not a socket, a libc without `eventfd`, and a kernel
         * that refused one all land here.
         */
        fun openOrNull(symbols: DBusSymbols, connection: MemorySegment): LoopWakeup? {
            if (!Libc.loaded) return null
            val busFd = Arena.ofConfined().use { call ->
                val out = call.allocate(ValueLayout.JAVA_INT)
                val answered = runCatching {
                    symbols.handle("dbus_connection_get_unix_fd").invokeExact(connection, out) as Int
                }.getOrDefault(0)
                if (answered == 0) -1 else out.get(ValueLayout.JAVA_INT, 0)
            }
            if (busFd < 0) return null

            val wakeFd = runCatching {
                Libc.eventfd.invokeExact(0, EFD_CLOEXEC or EFD_NONBLOCK) as Int
            }.getOrDefault(-1)
            if (wakeFd < 0) return null

            // Shared rather than confined: the loop thread waits on it and every
            // other thread writes through it.
            val arena = Arena.ofShared()
            return runCatching {
                val fds = arena.allocate(POLLFD_SIZE * FD_COUNT, 4)
                fds.set(ValueLayout.JAVA_INT, BUS_SLOT + POLLFD_FD, busFd)
                fds.set(ValueLayout.JAVA_SHORT, BUS_SLOT + POLLFD_EVENTS, POLLIN)
                fds.set(ValueLayout.JAVA_INT, WAKE_SLOT + POLLFD_FD, wakeFd)
                fds.set(ValueLayout.JAVA_SHORT, WAKE_SLOT + POLLFD_EVENTS, POLLIN)
                val counter = arena.allocate(ValueLayout.JAVA_LONG)
                val one = arena.allocate(ValueLayout.JAVA_LONG)
                one.set(ValueLayout.JAVA_LONG, 0, 1L)
                LoopWakeup(arena, fds, counter, one, wakeFd)
            }.getOrElse {
                runCatching { Libc.close.invokeExact(wakeFd) as Int }
                runCatching { arena.close() }
                null
            }
        }
    }
}

/**
 * The four libc calls the wait needs, out of the process's own lookup.
 *
 * No library is opened by name, for the reason `RealtimeThreads` gives: libc is
 * loaded before anything here runs, and naming it would mean choosing between
 * glibc's soname and musl's.
 */
private object Libc {

    private val linker = Linker.nativeLinker()
    private val lookup = linker.defaultLookup()

    private val handles: Map<String, MethodHandle> = buildMap {
        val descriptors = mapOf(
            // nfds_t is an unsigned long and ssize_t a long, so both cross as
            // a Java long rather than as the int they resemble.
            "poll" to FunctionDescriptor.of(
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
            ),
            "eventfd" to FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
            "read" to FunctionDescriptor.of(
                ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
            ),
            "write" to FunctionDescriptor.of(
                ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
            ),
            "close" to FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
        )
        descriptors.forEach { (name, descriptor) ->
            lookup.find(name).ifPresent { symbol -> put(name, linker.downcallHandle(symbol, descriptor)) }
        }
    }

    /** Every one of them, or none: a partial set has nothing to fall back to. */
    val loaded: Boolean = handles.size == 5

    val poll: MethodHandle get() = handles.getValue("poll")
    val eventfd: MethodHandle get() = handles.getValue("eventfd")
    val read: MethodHandle get() = handles.getValue("read")
    val write: MethodHandle get() = handles.getValue("write")
    val close: MethodHandle get() = handles.getValue("close")
}
