package dev.hivens.libsound.session

import dev.hivens.libsound.session.dbus.DBusAbi
import dev.hivens.libsound.session.dbus.DBusSymbols
import dev.hivens.libsound.session.dbus.allocateUtf8
import dev.hivens.libsound.session.dbus.argType
import dev.hivens.libsound.session.dbus.dict
import dev.hivens.libsound.session.dbus.next
import dev.hivens.libsound.session.dbus.readDouble
import dev.hivens.libsound.session.dbus.readInt64
import dev.hivens.libsound.session.dbus.readString
import dev.hivens.libsound.session.dbus.readStringArray
import dev.hivens.libsound.session.dbus.recurse
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment

/**
 * The marshalling layer, round-tripped through a real libdbus message.
 *
 * No bus and no daemon: a message can be built and re-read with the library
 * alone, so these run on any machine that has libdbus at all -- including a CI
 * runner with no desktop. That matters because this is the code where a mistake
 * becomes a message the daemon rejects with a signature error naming neither
 * the field nor the caller, and it is the code the family has copied four times.
 */
class DBusMarshalTest {

    companion object {
        private var symbols: DBusSymbols? = null

        @JvmStatic
        @BeforeAll
        fun load() {
            symbols = DBusSymbols.loadOrNull()
        }

        @JvmStatic
        @AfterAll
        fun unload() {
            symbols?.close()
            symbols = null
        }
    }

    @BeforeEach
    fun gate() {
        SessionTestGate.require("dbus", symbols != null, "libdbus not loadable")
    }

    /** A message we can write into and read back, with no peer involved. */
    private fun newMessage(call: Arena): MemorySegment {
        val s = checkNotNull(symbols)
        val message = s.handle("dbus_message_new_method_call").invokeExact(
            call.allocateUtf8("dev.hivens.libsound.test"),
            call.allocateUtf8("/dev/hivens/libsound/test"),
            call.allocateUtf8("dev.hivens.libsound.test"),
            call.allocateUtf8("Probe"),
        ) as MemorySegment
        check(message.address() != 0L)
        return message
    }

    private fun appendIter(call: Arena, message: MemorySegment): MemorySegment {
        val iter = call.allocate(DBusAbi.MESSAGE_ITER_LAYOUT)
        checkNotNull(symbols).handle("dbus_message_iter_init_append").invokeExact(message, iter) as Unit
        return iter
    }

    private fun readIter(call: Arena, message: MemorySegment): MemorySegment {
        val iter = call.allocate(DBusAbi.MESSAGE_ITER_LAYOUT)
        check((checkNotNull(symbols).handle("dbus_message_iter_init").invokeExact(message, iter) as Int) != 0)
        return iter
    }

    @Test
    fun `every value type survives the trip out and back`() {
        val s = checkNotNull(symbols)
        Arena.ofConfined().use { call ->
            val message = newMessage(call)
            try {
                s.dict(call, appendIter(call, message)) { entries ->
                    entries.string("xesam:title", "Bus Stop")
                    entries.stringArray("xesam:artist", listOf("Jun Maeda", "Anri Kumaki"))
                    entries.int64("mpris:length", 245_000_000L)
                    entries.double("xesam:userRating", 0.75)
                    entries.objectPath("mpris:trackid", "/dev/hivens/libsound/track/1")
                    entries.boolean("xesam:autoRating", true)
                }

                val found = mutableMapOf<String, Any?>()
                val array = s.recurse(call, readIter(call, message))
                while (s.argType(array) != DBusAbi.TYPE_INVALID) {
                    val entry = s.recurse(call, array)
                    val key = checkNotNull(s.readString(call, entry))
                    s.next(entry)
                    val value = s.recurse(call, entry)   // into the variant
                    found[key] = when (s.argType(value)) {
                        DBusAbi.TYPE_STRING, DBusAbi.TYPE_OBJECT_PATH -> s.readString(call, value)
                        DBusAbi.TYPE_INT64 -> s.readInt64(call, value)
                        DBusAbi.TYPE_DOUBLE -> s.readDouble(call, value)
                        DBusAbi.TYPE_ARRAY -> s.readStringArray(call, value)
                        DBusAbi.TYPE_BOOLEAN -> true
                        else -> null
                    }
                    s.next(array)
                }

                found["xesam:title"] shouldBe "Bus Stop"
                found["xesam:artist"] shouldBe listOf("Jun Maeda", "Anri Kumaki")
                found["mpris:length"] shouldBe 245_000_000L
                found["xesam:userRating"] shouldBe 0.75
                found["mpris:trackid"] shouldBe "/dev/hivens/libsound/track/1"
                found.size shouldBe 6
            } finally {
                s.handle("dbus_message_unref").invokeExact(message) as Unit
            }
        }
    }

    @Test
    fun `a null value is left out rather than written blank`() {
        // MPRIS readers tell an absent key from a present-but-empty one, and a
        // blank title is a widget showing a blank title.
        val s = checkNotNull(symbols)
        Arena.ofConfined().use { call ->
            val message = newMessage(call)
            try {
                s.dict(call, appendIter(call, message)) { entries ->
                    entries.string("present", "yes")
                    entries.string("absent", null)
                    entries.int64("alsoAbsent", null)
                    entries.stringArray("emptyArray", emptyList())
                }
                val keys = mutableListOf<String>()
                val array = s.recurse(call, readIter(call, message))
                while (s.argType(array) != DBusAbi.TYPE_INVALID) {
                    val entry = s.recurse(call, array)
                    s.readString(call, entry)?.let { keys.add(it) }
                    s.next(array)
                }
                keys shouldBe listOf("present")
            } finally {
                s.handle("dbus_message_unref").invokeExact(message) as Unit
            }
        }
    }

    @Test
    fun `an empty dictionary is still a well-formed dictionary`() {
        // The state a player is in before it has anything to say. It has to
        // marshal, because GetAll is answered whether or not a track is loaded.
        val s = checkNotNull(symbols)
        Arena.ofConfined().use { call ->
            val message = newMessage(call)
            try {
                s.dict(call, appendIter(call, message)) { }
                val array = s.recurse(call, readIter(call, message))
                s.argType(array) shouldBe DBusAbi.TYPE_INVALID
            } finally {
                s.handle("dbus_message_unref").invokeExact(message) as Unit
            }
        }
    }
}
