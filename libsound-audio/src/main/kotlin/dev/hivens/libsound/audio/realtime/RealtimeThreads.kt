package dev.hivens.libsound.audio.realtime

import dev.hivens.libsound.dbus.DBusAbi
import dev.hivens.libsound.dbus.DBusConnection
import dev.hivens.libsound.dbus.appendString
import dev.hivens.libsound.dbus.appendUint32
import dev.hivens.libsound.dbus.appendUint64
import dev.hivens.libsound.dbus.argType
import dev.hivens.libsound.dbus.readInt32
import dev.hivens.libsound.dbus.readInt64
import dev.hivens.libsound.dbus.recurse
import org.slf4j.LoggerFactory
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

/**
 * Asking the system for a writer thread that wakes on time.
 *
 * A five millisecond buffer means the writing thread wakes every five
 * milliseconds and is never late. An ordinary JVM thread competing with a
 * compile, a browser or a game loop will be late, and every time it is late the
 * audio underruns. This is the difference between asking for low latency and
 * having it.
 *
 * ## Why a daemon does it and not us
 *
 * `sched_setscheduler` needs a privilege an ordinary desktop process does not
 * have, so Linux hands the decision to RealtimeKit: a system service that
 * grants a bounded real-time priority to processes that have first limited how
 * long they may spend at it. That limit, `RLIMIT_RTTIME`, is the mechanism that
 * keeps a buggy client from locking the machine, and RealtimeKit refuses a
 * process that has not set it.
 *
 * ## What it costs the process
 *
 * `setrlimit` is process wide, so a consumer that asks for one real-time sink
 * gets the limit on every thread it has. That is the price of the grant rather
 * than a choice made here, and it is why the whole path is behind
 * [dev.hivens.libsound.SinkConfig.realtime] and off by default. Taking a
 * priority that will not be preempted, on behalf of a process that did not ask
 * for it, is not something a library does.
 *
 * Every failure is ordinary and none is fatal: no RealtimeKit, no system bus, a
 * refusal, a kernel without the limit. The caller logs the reason once and the
 * audio keeps playing at whatever priority it already had.
 */
internal object RealtimeThreads {

    private val log = LoggerFactory.getLogger("libsound.Realtime")

    /**
     * Promote the calling thread, or say why not.
     *
     * Returns null on success, and otherwise a reason short enough for one line
     * of log and specific enough to act on.
     */
    fun promoteCurrentThread(priority: Int = DEFAULT_PRIORITY): String? {
        if (!System.getProperty("os.name", "").lowercase().contains("linux")) {
            return "RealtimeKit is a Linux service"
        }
        val tid = currentThreadId() ?: return "gettid is not in this libc"
        val bus = DBusConnection.openOrNull("rtkit", DBusAbi.BUS_SYSTEM)
            ?: return "no system bus"
        bus.start()
        return try {
            val limits = readLimits(bus)
            val applied = applyRtTimeLimit(limits.rtTimeUsecMax)
            if (applied != null) return applied
            makeRealtime(bus, tid, priority.coerceIn(1, limits.maxPriority))
        } finally {
            runCatching { bus.close() }
        }
    }

    /** What RealtimeKit will grant, read from the service rather than assumed. */
    private class Limits(val rtTimeUsecMax: Long, val maxPriority: Int)

    private fun readLimits(bus: DBusConnection): Limits {
        val rtTime = property(bus, "RTTimeUSecMax") { call, iter -> bus.symbols.readInt64(call, iter) }
        val maxPriority = property(bus, "MaxRealtimePriority") { call, iter -> bus.symbols.readInt32(call, iter) }
        // The fallbacks are the values the service has shipped with for its
        // whole life, used only where the property could not be read at all. A
        // grant that then fails is a refusal like any other.
        return Limits(
            rtTimeUsecMax = rtTime ?: DEFAULT_RTTIME_USEC,
            maxPriority = (maxPriority ?: DEFAULT_MAX_PRIORITY).coerceAtLeast(1),
        )
    }

    private inline fun <T> property(
        bus: DBusConnection,
        name: String,
        read: (Arena, MemorySegment) -> T?,
    ): T? = Arena.ofConfined().use { call ->
        val message = bus.newCall(call, SERVICE, PATH, PROPERTIES_INTERFACE, "Get") ?: return null
        val iter = call.allocate(DBusAbi.MESSAGE_ITER_LAYOUT)
        bus.symbols.handle("dbus_message_iter_init_append").invokeExact(message, iter) as Unit
        bus.symbols.appendString(call, iter, DBusAbi.TYPE_STRING, INTERFACE)
        bus.symbols.appendString(call, iter, DBusAbi.TYPE_STRING, name)

        val reply = bus.call(message) ?: return null
        try {
            val replyIter = call.allocate(DBusAbi.MESSAGE_ITER_LAYOUT)
            if ((bus.symbols.handle("dbus_message_iter_init").invokeExact(reply, replyIter) as Int) == 0) {
                return null
            }
            // Properties.Get answers a variant, so the value is one level down.
            if (bus.symbols.argType(replyIter) != DBusAbi.TYPE_VARIANT) return null
            read(call, bus.symbols.recurse(call, replyIter))
        } finally {
            runCatching { bus.symbols.handle("dbus_message_unref").invokeExact(reply) as Unit }
        }
    }

    /**
     * Limit how long this process may spend at real-time priority, which is
     * what the grant is conditional on.
     *
     * Never raises the hard limit: an unprivileged process cannot, and asking
     * would turn a working promotion into a refusal on a machine that had
     * simply been configured more tightly.
     */
    private fun applyRtTimeLimit(wantedUsec: Long): String? {
        val getrlimit = libc("getrlimit") ?: return "getrlimit is not in this libc"
        val setrlimit = libc("setrlimit") ?: return "setrlimit is not in this libc"
        return Arena.ofConfined().use { call ->
            val limit = call.allocate(RealtimeAbi.RLIMIT_SIZE, 8)
            val read = getrlimit.invokeExact(RealtimeAbi.RLIMIT_RTTIME, limit) as Int
            val currentMax = if (read == 0) limit.get(ValueLayout.JAVA_LONG, RealtimeAbi.RLIMIT_MAX) else RLIM_INFINITY
            val ceiling = if (currentMax == RLIM_INFINITY) wantedUsec else minOf(wantedUsec, currentMax)
            limit.set(ValueLayout.JAVA_LONG, RealtimeAbi.RLIMIT_CUR, ceiling)
            limit.set(ValueLayout.JAVA_LONG, RealtimeAbi.RLIMIT_MAX, ceiling)
            val written = setrlimit.invokeExact(RealtimeAbi.RLIMIT_RTTIME, limit) as Int
            if (written != 0) "setrlimit(RLIMIT_RTTIME) was refused" else null
        }
    }

    private fun makeRealtime(bus: DBusConnection, tid: Long, priority: Int): String? =
        Arena.ofConfined().use { call ->
            val message = bus.newCall(call, SERVICE, PATH, INTERFACE, "MakeThreadRealtime")
                ?: return "could not build the request"
            val iter = call.allocate(DBusAbi.MESSAGE_ITER_LAYOUT)
            bus.symbols.handle("dbus_message_iter_init_append").invokeExact(message, iter) as Unit
            bus.symbols.appendUint64(call, iter, tid)
            bus.symbols.appendUint32(call, iter, priority)

            var refusal: String? = null
            val reply = bus.call(message) { refusal = it }
            if (reply == null) return refusal ?: "RealtimeKit did not answer"
            runCatching { bus.symbols.handle("dbus_message_unref").invokeExact(reply) as Unit }
            log.debug("thread {} promoted at priority {}", tid, priority)
            null
        }

    /**
     * The kernel thread id, which is what RealtimeKit takes: a process has one
     * pid and as many tids as it has threads, and the JVM's own thread id is
     * neither.
     */
    private fun currentThreadId(): Long? {
        val gettid = libc("gettid") ?: return null
        return runCatching { (gettid.invokeExact() as Int).toLong() }.getOrNull()
    }

    /**
     * A libc symbol out of the process's own lookup.
     *
     * No library is opened by name: libc is loaded before anything here runs,
     * and naming it would mean choosing between glibc's soname and musl's,
     * which is a distinction this library has spent effort not making
     * elsewhere.
     */
    private fun libc(name: String): MethodHandle? = HANDLES[name]

    private val HANDLES: Map<String, MethodHandle> by lazy {
        val linker = Linker.nativeLinker()
        val lookup = linker.defaultLookup()
        val descriptors = mapOf(
            "gettid" to FunctionDescriptor.of(ValueLayout.JAVA_INT),
            "getrlimit" to FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
            "setrlimit" to FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
        )
        buildMap {
            descriptors.forEach { (name, descriptor) ->
                lookup.find(name).ifPresent { symbol -> put(name, linker.downcallHandle(symbol, descriptor)) }
            }
        }
    }

    private const val SERVICE = "org.freedesktop.RealtimeKit1"
    private const val PATH = "/org/freedesktop/RealtimeKit1"
    private const val INTERFACE = "org.freedesktop.RealtimeKit1"
    private const val PROPERTIES_INTERFACE = "org.freedesktop.DBus.Properties"

    /**
     * The priority a client asks for.
     *
     * Five is what PulseAudio's own daemon runs its real-time threads at, and a
     * client that outranked the server it feeds would be preempting the thing
     * that consumes its audio. Clamped to whatever RealtimeKit says it will
     * grant, which is usually twenty.
     */
    const val DEFAULT_PRIORITY: Int = 5

    /** Used only where the service would not say; 200 ms is what it has always shipped. */
    private const val DEFAULT_RTTIME_USEC = 200_000L

    private const val DEFAULT_MAX_PRIORITY = 20

    /** `RLIM_INFINITY`, which is the unsigned maximum rather than a sentinel of its own. */
    private const val RLIM_INFINITY = -1L
}
