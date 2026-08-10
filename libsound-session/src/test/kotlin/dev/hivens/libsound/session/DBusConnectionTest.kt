package dev.hivens.libsound.session

import dev.hivens.libsound.session.dbus.DBusAbi
import dev.hivens.libsound.session.dbus.DBusConnection
import dev.hivens.libsound.session.dbus.readCString
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.util.concurrent.TimeUnit

/**
 * The layer exercised against a real bus, which is what neither sibling does.
 *
 * libtray and libnotify both compile in CI and stop there; every D-Bus defect
 * either has shipped was found by a human watching a desktop. These assertions
 * need a session bus and nothing else -- no tray host, no notification daemon --
 * so they run wherever one exists, including under `dbus-run-session` on a
 * runner that has no desktop at all.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class DBusConnectionTest {

    private var connection: DBusConnection? = null

    @BeforeEach
    fun connect() {
        connection = DBusConnection.openOrNull("test")
        SessionTestGate.require("dbus", connection != null, "no session bus reachable")
        connection?.start()
    }

    @AfterEach
    fun disconnect() {
        connection?.let { runCatching { it.close() } }
        connection = null
    }

    @Test
    fun `the bus assigns a unique name`() {
        val unique = checkNotNull(connection).uniqueName()
        checkNotNull(unique)
        unique.startsWith(":") shouldBe true
    }

    @Test
    fun `a well-known name can be claimed and given back`() {
        val bus = checkNotNull(connection)
        val name = "dev.hivens.libsound.test.Claim${ProcessHandle.current().pid()}"

        // PRIMARY_OWNER and nothing else: being queued behind another owner
        // means the desktop is talking to them, not to us, which for a media
        // session is indistinguishable from not being there.
        bus.requestName(name) shouldBe DBusAbi.REQUEST_NAME_REPLY_PRIMARY_OWNER
        listNames().contains(name) shouldBe true

        bus.releaseName(name)
        listNames().contains(name) shouldBe false
    }

    @Test
    fun `a round trip returns a reply the caller owns`() {
        val bus = checkNotNull(connection)
        Arena.ofConfined().use { call ->
            val message = checkNotNull(
                bus.newCall(call, DBUS_SERVICE, DBUS_PATH, DBUS_INTERFACE, "GetId"),
            )
            val reply = checkNotNull(bus.call(message)) { "the bus always answers GetId" }
            runCatching { unref(reply) }
        }
    }

    @Test
    fun `a call to nobody answers null rather than hanging`() {
        // The failure a media session actually meets: a player that quit between
        // the listing and the read. It has to come back, and it has to come back
        // as an absence rather than an exception.
        val bus = checkNotNull(connection)
        Arena.ofConfined().use { call ->
            val message = checkNotNull(
                bus.newCall(
                    call,
                    "dev.hivens.libsound.test.NoSuchService",
                    "/nope", "dev.hivens.libsound.test.Nope", "Nope",
                ),
            )
            bus.call(message, timeoutMillis = 2_000) shouldBe null
        }
    }

    @Test
    fun `close is idempotent and leaves nothing running`() {
        val bus = checkNotNull(connection)
        bus.close()
        bus.close()
        bus.isOpen shouldBe false
        connection = null
    }

    // -- helpers -------------------------------------------------------------

    /** `org.freedesktop.DBus.ListNames`, parsed out of the `as` reply. */
    private fun listNames(): List<String> {
        val bus = checkNotNull(connection)
        return Arena.ofConfined().use { call ->
            val message = checkNotNull(bus.newCall(call, DBUS_SERVICE, DBUS_PATH, DBUS_INTERFACE, "ListNames"))
            val reply = bus.call(message) ?: return@use emptyList()
            try {
                val symbols = bus.symbols
                val iter = call.allocate(DBusAbi.MESSAGE_ITER_LAYOUT)
                if ((symbols.handle("dbus_message_iter_init").invokeExact(reply, iter) as Int) == 0) {
                    return@use emptyList()
                }
                val array = call.allocate(DBusAbi.MESSAGE_ITER_LAYOUT)
                symbols.handle("dbus_message_iter_recurse").invokeExact(iter, array) as Unit
                val names = mutableListOf<String>()
                while (true) {
                    val type = (symbols.handle("dbus_message_iter_get_arg_type").invokeExact(array) as Int).toByte()
                    if (type == DBusAbi.TYPE_INVALID) break
                    val out = call.allocate(ValueLayout.ADDRESS)
                    symbols.handle("dbus_message_iter_get_basic").invokeExact(array, out) as Unit
                    out.get(ValueLayout.ADDRESS, 0).readCString()?.let { names.add(it) }
                    symbols.handle("dbus_message_iter_next").invokeExact(array) as Int
                }
                names
            } finally {
                runCatching { unref(reply) }
            }
        }
    }

    private fun unref(message: MemorySegment) {
        checkNotNull(connection).symbols.handle("dbus_message_unref").invokeExact(message) as Unit
    }

    private companion object {
        const val DBUS_SERVICE = "org.freedesktop.DBus"
        const val DBUS_PATH = "/org/freedesktop/DBus"
        const val DBUS_INTERFACE = "org.freedesktop.DBus"
    }
}
