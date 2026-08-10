package dev.hivens.libsound.session.dbus

import org.slf4j.LoggerFactory
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A private session-bus connection and the single thread that owns it.
 *
 * ## One thread, not two
 *
 * The obvious design puts the blocking poll on one thread and outgoing sends on
 * another, so a caller never waits on a flush. libtray shipped exactly that and
 * measured what it costs: libdbus serialises all socket work behind a
 * per-connection io-path lock, and `dbus_connection_read_write` holds that lock
 * for the whole of its blocking poll. Every send from the other thread waited
 * the poll out, so a property query took about a second to answer and opening a
 * menu took two.
 *
 * So polling and sending share this thread, and the queue is drained between
 * poll iterations. The poll interval becomes the worst-case latency for
 * something the caller pushes, which is why it is short.
 *
 * ## Every message crosses the socket on this thread
 *
 * Including round trips. The first cut let [call] run
 * `dbus_connection_send_with_reply_and_block` on the caller's thread while the
 * loop kept polling, and the integration test caught it as a one-in-three flake:
 * `dbus_connection_pop_message` takes messages straight off the incoming queue,
 * replies to pending calls included, so the loop was stealing the answer the
 * caller was blocked waiting for. That is the same message theft the family met
 * on a shared connection, except both thieves are ours.
 *
 * So [call] hands the round trip to this thread and waits on a future, which is
 * the shape libnotify already uses for the same reason. The cost is that a
 * blocking call stalls the loop for its duration; the alternative is a reply
 * that sometimes never arrives.
 *
 * [send] is fire-and-forget from any thread. Neither may be used from a message
 * handler -- handlers run on this thread, and it is the one that would do the
 * work they are waiting for.
 */
internal class DBusConnection private constructor(
    val symbols: DBusSymbols,
    val connection: MemorySegment,
    private val threadLabel: String,
) : AutoCloseable {

    private val log = LoggerFactory.getLogger("libsound.DBus")

    private val open = AtomicBoolean(true)

    /** Outgoing messages, drained by [ioThread] between polls. */
    private val outgoing = LinkedBlockingQueue<MemorySegment>()

    /** Round trips, run by [ioThread] so nothing races it for the reply. */
    private val tasks = LinkedBlockingQueue<Runnable>()

    private val handlers = mutableListOf<(MemorySegment) -> Boolean>()

    private val ioThread = Thread(::run, "libsound-dbus-$threadLabel").apply { isDaemon = true }

    val isOpen: Boolean get() = open.get()

    /**
     * Register a message handler. Returns true from the handler when it consumed
     * the message. Called on the I/O thread with nothing held; a handler that
     * blocks blocks the connection, so it must not.
     */
    fun onMessage(handler: (MemorySegment) -> Boolean) {
        synchronized(handlers) { handlers.add(handler) }
    }

    fun start() {
        ioThread.start()
    }

    /**
     * Queue a message to go out. Takes ownership: the I/O thread unrefs it, and
     * a closed connection unrefs it here rather than leaking.
     */
    fun send(message: MemorySegment) {
        if (message.address() == 0L) return
        if (!open.get()) {
            runCatching { symbols.handle("dbus_message_unref").invokeExact(message) as Unit }
            return
        }
        outgoing.put(message)
    }

    /**
     * Send and wait for the reply, on the calling thread. Returns null when the
     * peer did not answer -- absent, or past [timeoutMillis].
     *
     * The reply is the caller's to unref. Never call this from a message
     * handler: handlers run on the I/O thread, and this would have that thread
     * waiting for itself.
     */
    fun call(message: MemorySegment, timeoutMillis: Int = DEFAULT_REPLY_TIMEOUT_MS): MemorySegment? {
        if (!open.get()) {
            runCatching { symbols.handle("dbus_message_unref").invokeExact(message) as Unit }
            return null
        }
        val future = CompletableFuture<MemorySegment?>()
        tasks.put(Runnable { future.complete(runCatching { blockingCall(message, timeoutMillis) }.getOrNull()) })
        return runCatching {
            // Past the peer's own timeout, plus slack for the loop to pick the
            // task up. A caller that waits forever here is a caller the I/O
            // thread can hang.
            future.get(timeoutMillis.toLong() + TASK_PICKUP_SLACK_MS, TimeUnit.MILLISECONDS)
        }.getOrElse {
            log.warn("round trip did not complete in time: {}", it.message)
            null
        }
    }

    /** The round trip itself, only ever on the I/O thread. */
    private fun blockingCall(message: MemorySegment, timeoutMillis: Int): MemorySegment? =
        Arena.ofConfined().use { call ->
            val error = call.allocate(DBusAbi.ERROR_LAYOUT)
            symbols.handle("dbus_error_init").invokeExact(error) as Unit
            try {
                val reply = symbols.handle("dbus_connection_send_with_reply_and_block")
                    .invokeExact(connection, message, timeoutMillis, error) as MemorySegment
                takeError(symbols, error)?.let { log.debug("round trip answered with an error: {}", it) }
                if (reply.address() == 0L) null else reply
            } finally {
                runCatching { symbols.handle("dbus_message_unref").invokeExact(message) as Unit }
            }
        }

    /**
     * Claim a well-known name. Returns the libdbus reply code; MPRIS needs
     * [DBusAbi.REQUEST_NAME_REPLY_PRIMARY_OWNER] and nothing else will do --
     * being queued behind another owner means the desktop talks to them.
     */
    fun requestName(name: String, flags: Int = DBusAbi.NAME_FLAG_DO_NOT_QUEUE): Int =
        onIoThread { requestNameHere(name, flags) } ?: -1

    private fun requestNameHere(name: String, flags: Int): Int =
        Arena.ofConfined().use { call ->
            val error = call.allocate(DBusAbi.ERROR_LAYOUT)
            symbols.handle("dbus_error_init").invokeExact(error) as Unit
            val result = symbols.handle("dbus_bus_request_name")
                .invokeExact(connection, call.allocateUtf8(name), flags, error) as Int
            takeError(symbols, error)?.let { log.warn("RequestName [{}] failed: {}", name, it) }
            result
        }

    /** Give a well-known name back. Safe to call for a name we never held. */
    fun releaseName(name: String): Int =
        onIoThread { releaseNameHere(name) } ?: -1

    private fun releaseNameHere(name: String): Int =
        Arena.ofConfined().use { call ->
            val error = call.allocate(DBusAbi.ERROR_LAYOUT)
            symbols.handle("dbus_error_init").invokeExact(error) as Unit
            val result = symbols.handle("dbus_bus_release_name")
                .invokeExact(connection, call.allocateUtf8(name), error) as Int
            takeError(symbols, error)?.let { log.debug("ReleaseName [{}]: {}", name, it) }
            result
        }

    /** The `:1.42` style name the bus assigned this connection. */
    fun uniqueName(): String? =
        (symbols.handle("dbus_bus_get_unique_name").invokeExact(connection) as MemorySegment).readCString()

    /** Build a method call. The caller hands it to [send] or [call], which own it. */
    fun newCall(arena: Arena, destination: String, path: String, iface: String, member: String): MemorySegment? {
        val message = symbols.handle("dbus_message_new_method_call").invokeExact(
            arena.allocateUtf8(destination),
            arena.allocateUtf8(path),
            arena.allocateUtf8(iface),
            arena.allocateUtf8(member),
        ) as MemorySegment
        return if (message.address() == 0L) null else message
    }

    /**
     * Run [body] on the I/O thread and wait for it.
     *
     * Anything that talks to the bus and expects an answer goes through here,
     * for the reason in the class KDoc: a reply the loop can pop is a reply the
     * caller may never see. Returns null if the loop never got to it.
     */
    private fun <T> onIoThread(body: () -> T): T? {
        if (!open.get()) return null
        if (Thread.currentThread() === ioThread) return body()
        val future = CompletableFuture<T?>()
        tasks.put(Runnable { future.complete(runCatching(body).getOrNull()) })
        return runCatching {
            future.get(DEFAULT_REPLY_TIMEOUT_MS.toLong() + TASK_PICKUP_SLACK_MS, TimeUnit.MILLISECONDS)
        }.getOrElse {
            log.warn("bus task did not complete in time: {}", it.message)
            null
        }
    }

    /** Subscribe to a class of signals. Failure is logged, not fatal. */
    fun addMatch(rule: String) {
        Arena.ofConfined().use { call ->
            val error = call.allocate(DBusAbi.ERROR_LAYOUT)
            symbols.handle("dbus_error_init").invokeExact(error) as Unit
            runCatching {
                symbols.handle("dbus_bus_add_match")
                    .invokeExact(connection, call.allocateUtf8(rule), error) as Unit
            }.onFailure { log.warn("AddMatch failed for [{}]: {}", rule, it.message) }
            freeErrorIfSet(symbols, error)
        }
    }

    override fun close() {
        if (!open.compareAndSet(true, false)) return
        ioThread.join(JOIN_TIMEOUT_MS)

        // Anything the loop enqueued after its last drain, unreffed rather than
        // leaked to libdbus.
        while (true) {
            val leftover = outgoing.poll() ?: break
            runCatching { symbols.handle("dbus_message_unref").invokeExact(leftover) as Unit }
        }

        // Everything below frees memory the I/O thread may still be reading. A
        // handler that blocks, or a flush against a socket nobody drains, can
        // hold it past the budget -- and unreffing a connection a live dbus_*
        // call is using is a segfault inside libdbus that takes the host with
        // it. Leaking one connection at shutdown is the cheaper failure. Both
        // siblings learned this the same way.
        if (ioThread.isAlive) {
            log.warn(
                "{} did not stop within {} ms; leaving the connection open rather than freeing " +
                    "memory it is still using",
                ioThread.name, JOIN_TIMEOUT_MS,
            )
            return
        }
        runCatching { symbols.handle("dbus_connection_close").invokeExact(connection) as Unit }
            .onFailure { log.warn("dbus_connection_close threw on shutdown: {}", it.message) }
        runCatching { symbols.handle("dbus_connection_unref").invokeExact(connection) as Unit }
            .onFailure { log.warn("dbus_connection_unref threw on shutdown: {}", it.message) }
        symbols.close()
    }

    // -- the one thread ------------------------------------------------------

    private fun run() {
        val readWrite = symbols.handle("dbus_connection_read_write")
        val popMessage = symbols.handle("dbus_connection_pop_message")
        val sendMessage = symbols.handle("dbus_connection_send")
        val flush = symbols.handle("dbus_connection_flush")
        val unref = symbols.handle("dbus_message_unref")

        while (open.get()) {
            try {
                // Tasks first: a caller is blocked on each of them, so the poll
                // interval must not be added to their latency.
                var task = tasks.poll()
                while (task != null) {
                    runCatching { task.run() }.onFailure { log.warn("bus task threw: {}", it.message) }
                    task = tasks.poll()
                }
                // Then the fire-and-forget queue, so something a caller pushed
                // goes out within one poll rather than waiting on the next.
                drainOutgoing(sendMessage, flush, unref)

                val live = readWrite.invokeExact(connection, POLL_INTERVAL_MS) as Int
                if (live == 0) {
                    // FALSE means the connection disconnected. With
                    // exit_on_disconnect off it no longer _exit()s us, but
                    // read_write returns immediately from here on -- sleep so a
                    // dead bus does not pin a core.
                    Thread.sleep(DEAD_BUS_SLEEP_MS)
                    continue
                }
                while (open.get()) {
                    val message = popMessage.invokeExact(connection) as MemorySegment
                    if (message.address() == 0L) break
                    try {
                        dispatch(message)
                    } catch (t: Throwable) {
                        log.warn("handler threw, dropping message: {}", t.message)
                    } finally {
                        runCatching { unref.invokeExact(message) as Unit }
                    }
                }
            } catch (t: Throwable) {
                log.warn("D-Bus loop iteration threw: {}", t.message)
                Thread.sleep(ERROR_BACKOFF_MS)
            }
        }
        // Final drain, so nothing queued during shutdown is leaked to libdbus.
        while (true) {
            val leftover = outgoing.poll() ?: break
            runCatching { unref.invokeExact(leftover) as Unit }
        }
    }

    private fun drainOutgoing(
        sendMessage: java.lang.invoke.MethodHandle,
        flush: java.lang.invoke.MethodHandle,
        unref: java.lang.invoke.MethodHandle,
    ) {
        var sentAny = false
        while (true) {
            val message = outgoing.poll() ?: break
            try {
                Arena.ofConfined().use { call ->
                    val serial = call.allocate(ValueLayout.JAVA_INT)
                    sendMessage.invokeExact(connection, message, serial) as Int
                }
                sentAny = true
            } catch (t: Throwable) {
                log.warn("send failed, dropping message: {}", t.message)
            } finally {
                runCatching { unref.invokeExact(message) as Unit }
            }
        }
        // One flush for the whole batch rather than one per message: the flush
        // is the expensive half, and a burst of property changes is the normal
        // case for a media session.
        if (sentAny) runCatching { flush.invokeExact(connection) as Unit }
    }

    private fun dispatch(message: MemorySegment) {
        val snapshot = synchronized(handlers) { handlers.toList() }
        for (handler in snapshot) {
            if (handler(message)) return
        }
    }

    internal companion object {
        private val log = LoggerFactory.getLogger("libsound.DBus")

        /**
         * Worst-case latency for an outgoing message, because the drain happens
         * between polls. 100 ms is what libtray settled on after measuring the
         * two-thread design it replaced.
         */
        private const val POLL_INTERVAL_MS = 100

        private const val DEAD_BUS_SLEEP_MS = 1_000L
        private const val ERROR_BACKOFF_MS = 200L

        /**
         * Shorter than [DEFAULT_REPLY_TIMEOUT_MS] on purpose: a host shutting
         * down should not hang on a wedged daemon. The cost is that the guard in
         * close() can trip and leak a connection, which is the cheaper failure.
         */
        private const val JOIN_TIMEOUT_MS = 2_000L

        const val DEFAULT_REPLY_TIMEOUT_MS = 5_000

        /** Slack over the peer's timeout, for the loop to pick the task up. */
        private const val TASK_PICKUP_SLACK_MS = 2_000L

        /**
         * Read a set DBusError, then free it.
         *
         * libdbus reports failure through the error struct and a sentinel
         * return; a caller that frees without reading throws away the only
         * explanation there is. Field offsets are the oracle's: name at 0,
         * message at 8.
         */
        fun takeError(symbols: DBusSymbols, error: MemorySegment): String? {
            if ((symbols.handle("dbus_error_is_set").invokeExact(error) as Int) == 0) return null
            val name = error.get(ValueLayout.ADDRESS, 0).readCString()
            val message = error.get(ValueLayout.ADDRESS, 8).readCString()
            runCatching { symbols.handle("dbus_error_free").invokeExact(error) as Unit }
            return listOfNotNull(name, message).joinToString(": ").ifEmpty { null }
        }

        fun freeErrorIfSet(symbols: DBusSymbols, error: MemorySegment) {
            if ((symbols.handle("dbus_error_is_set").invokeExact(error) as Int) != 0) {
                // libdbus heap-allocates the name and message; a confined arena
                // does not own them.
                runCatching { symbols.handle("dbus_error_free").invokeExact(error) as Unit }
            }
        }

        /**
         * Open a private connection to the session bus, or return null when
         * there is none -- the ordinary answer on a headless box, and not an
         * error.
         */
        fun openOrNull(threadLabel: String): DBusConnection? {
            val symbols = DBusSymbols.loadOrNull() ?: run {
                log.debug("libdbus not loadable")
                return null
            }
            var connection = MemorySegment.NULL
            return runCatching {
                Arena.ofConfined().use { setup ->
                    val error = setup.allocate(DBusAbi.ERROR_LAYOUT)
                    symbols.handle("dbus_error_init").invokeExact(error) as Unit
                    connection = symbols.handle("dbus_bus_get_private")
                        .invokeExact(DBusAbi.BUS_SESSION, error) as MemorySegment
                    freeErrorIfSet(symbols, error)
                    check(connection.address() != 0L) { "no session bus" }
                    symbols.handle("dbus_connection_set_exit_on_disconnect")
                        .invokeExact(connection, 0) as Unit
                }
                DBusConnection(symbols, connection, threadLabel)
            }.getOrElse {
                log.debug("session bus unavailable: {}", it.message)
                if (connection.address() != 0L) {
                    runCatching { symbols.handle("dbus_connection_close").invokeExact(connection) as Unit }
                    runCatching { symbols.handle("dbus_connection_unref").invokeExact(connection) as Unit }
                }
                symbols.close()
                null
            }
        }
    }
}
