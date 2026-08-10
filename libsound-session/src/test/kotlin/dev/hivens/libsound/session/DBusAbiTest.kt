package dev.hivens.libsound.session

import dev.hivens.libsound.session.dbus.DBusAbi
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.lang.foreign.Arena

/**
 * The two numbers that cost the sibling libraries two releases each.
 *
 * `DBusMessageIter` is opaque in the public header, so its size is knowable
 * only from the compiler -- and both libtray and libnotify guessed it, low, and
 * had libdbus write past the allocation on every iterator call. These
 * assertions are cheap, they run anywhere, and they fail the moment somebody
 * edits the layout to something that no longer matches the oracle.
 */
class DBusAbiTest {

    @Test
    fun `the iterator scratch is the size libdbus actually writes`() {
        // tools/dbus-oracle.c against libdbus 1.16.2, x86_64.
        DBusAbi.MESSAGE_ITER_LAYOUT.byteSize() shouldBe 72L
    }

    @Test
    fun `the iterator scratch is pointer-aligned, not byte-aligned`() {
        // The half of the mistake that a bigger reservation does not fix. A byte
        // sequence layout carries an alignment of one; libdbus writes pointers
        // in there, which x86_64 tolerates and aarch64 need not.
        DBusAbi.MESSAGE_ITER_LAYOUT.byteAlignment() shouldBe 8L
        Arena.ofConfined().use { arena ->
            val scratch = arena.allocate(DBusAbi.MESSAGE_ITER_LAYOUT)
            (scratch.address() % 8) shouldBe 0L
        }
    }

    @Test
    fun `the error struct matches what dbus_error_init fills`() {
        DBusAbi.ERROR_LAYOUT.byteSize() shouldBe 32L
        DBusAbi.ERROR_LAYOUT.byteAlignment() shouldBe 8L
    }

    @Test
    fun `type codes are the ASCII the wire format uses`() {
        // A transposed pair here produces a message the daemon rejects with a
        // signature error that names neither field.
        DBusAbi.TYPE_STRING shouldBe 's'.code.toByte()
        DBusAbi.TYPE_OBJECT_PATH shouldBe 'o'.code.toByte()
        DBusAbi.TYPE_SIGNATURE shouldBe 'g'.code.toByte()
        DBusAbi.TYPE_ARRAY shouldBe 'a'.code.toByte()
        DBusAbi.TYPE_VARIANT shouldBe 'v'.code.toByte()
        DBusAbi.TYPE_DICT_ENTRY shouldBe 'e'.code.toByte()
        DBusAbi.TYPE_UINT32 shouldBe 'u'.code.toByte()
        DBusAbi.TYPE_INT64 shouldBe 'x'.code.toByte()
        DBusAbi.TYPE_UINT64 shouldBe 't'.code.toByte()
        DBusAbi.TYPE_INVALID shouldBe 0.toByte()
    }
}
