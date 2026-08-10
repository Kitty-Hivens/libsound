package dev.hivens.libsound.audio

import dev.hivens.libsound.audio.wasapi.WasapiAbi
import dev.hivens.libsound.audio.wasapi.WasapiCom
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout

/**
 * The parts of the COM layer that are pure marshalling, and therefore testable
 * on any machine.
 *
 * Most of the WASAPI backend can only be exercised on Windows, which is exactly
 * why the parts that can be exercised anywhere should be. Both of these carry
 * the sort of defect that survives a reading: a GUID whose first three fields
 * are byte-swapped looks plausible in the source and matches no interface, and
 * a wide string terminated by the wrong character reads whatever follows it in
 * memory.
 */
class WasapiComTest {

    @Test
    fun `a GUID lands in memory in the layout COM expects`() {
        Arena.ofConfined().use { arena ->
            val segment = WasapiCom.guid(arena, WasapiAbi.CLSID_MM_DEVICE_ENUMERATOR)
            val bytes = segment.reinterpret(16).toArray(ValueLayout.JAVA_BYTE)
                .map { it.toInt() and 0xFF }

            // BCDE0395-E52F-467C-8E3D-C4579291692E. The first three fields are
            // little-endian integers, so they appear reversed against the text;
            // the last eight are bytes in written order. Getting that boundary
            // wrong is the classic GUID bug, and it fails as E_NOINTERFACE at
            // the far end where nothing points back here.
            bytes shouldBe listOf(
                0x95, 0x03, 0xDE, 0xBC,
                0x2F, 0xE5,
                0x7C, 0x46,
                0x8E, 0x3D, 0xC4, 0x57, 0x92, 0x91, 0x69, 0x2E,
            )
        }
    }

    @Test
    fun `every GUID in the table parses`() {
        // A typo in one of these is otherwise found only on Windows, as a call
        // that returns E_NOINTERFACE for no visible reason.
        val all = listOf(
            WasapiAbi.CLSID_MM_DEVICE_ENUMERATOR,
            WasapiAbi.IID_MM_DEVICE_ENUMERATOR,
            WasapiAbi.IID_MM_NOTIFICATION_CLIENT,
            WasapiAbi.IID_AUDIO_CLIENT,
            WasapiAbi.IID_AUDIO_RENDER_CLIENT,
            WasapiAbi.IID_AUDIO_CLOCK,
            WasapiAbi.IID_SIMPLE_AUDIO_VOLUME,
            WasapiAbi.IID_AUDIO_SESSION_CONTROL,
            WasapiAbi.IID_UNKNOWN,
            WasapiAbi.PKEY_DEVICE_FRIENDLY_NAME_FMTID,
        )
        Arena.ofConfined().use { arena ->
            all.forEach { WasapiCom.guid(arena, it) }
        }
        all.distinct().size shouldBe all.size
    }

    @Test
    fun `a malformed GUID is refused rather than truncated`() {
        Arena.ofConfined().use { arena ->
            assertThrows<IllegalArgumentException> { WasapiCom.guid(arena, "not-a-guid") }
            assertThrows<IllegalArgumentException> { WasapiCom.guid(arena, "BCDE0395-E52F-467C-8E3D") }
        }
    }

    @Test
    fun `a wide string round-trips through its own terminator`() {
        // The first cut wrote a space where the NUL belonged. Nothing about the
        // source looked wrong, and the result would have been a device id
        // running off into whatever followed it.
        Arena.ofConfined().use { arena ->
            listOf("", "Aurora", "устройство вывода", "a b  c").forEach { original ->
                WasapiCom.readWide(WasapiCom.wide(arena, original)) shouldBe original
            }
        }
    }

    @Test
    fun `a wide string is terminated by an actual zero`() {
        Arena.ofConfined().use { arena ->
            val segment = WasapiCom.wide(arena, "AB")
            segment.get(ValueLayout.JAVA_CHAR, 0) shouldBe 'A'
            segment.get(ValueLayout.JAVA_CHAR, 2) shouldBe 'B'
            segment.get(ValueLayout.JAVA_CHAR, 4) shouldBe WasapiCom.NUL
            // Two bytes per character plus the terminator, not one.
            segment.byteSize() shouldBe 6L
        }
    }

    @Test
    fun `a null pointer reads as null rather than as an empty name`() {
        // A device with no friendly name and a failed property read must not
        // look the same to a settings screen.
        WasapiCom.readWide(java.lang.foreign.MemorySegment.NULL) shouldBe null
    }

    @Test
    fun `every HRESULT constant is the value the headers give`() {
        // Hand-narrowing a 32-bit hex code to a negative Int is error-prone in
        // the quietest way: one of the six was wrong by 65290, the compiler was
        // content, and the only symptom would have been the backend refusing to
        // start on a thread already placed in a single-threaded apartment.
        WasapiAbi.AUDCLNT_E_DEVICE_INVALIDATED shouldBe 0x88890004L.toInt()
        WasapiAbi.AUDCLNT_E_UNSUPPORTED_FORMAT shouldBe 0x88890008L.toInt()
        WasapiAbi.AUDCLNT_E_DEVICE_IN_USE shouldBe 0x8889000AL.toInt()
        WasapiAbi.AUDCLNT_E_SERVICE_NOT_RUNNING shouldBe 0x88890010L.toInt()
        WasapiAbi.STREAMFLAGS_AUTOCONVERTPCM shouldBe 0x80000000L.toInt()
        WasapiCom.RPC_E_CHANGED_MODE shouldBe 0x80010106L.toInt()

        // And an HRESULT failure is exactly the sign bit, which is how every
        // call site here decides whether it failed.
        (WasapiAbi.AUDCLNT_E_DEVICE_INVALIDATED < 0) shouldBe true
        (WasapiAbi.S_OK < 0) shouldBe false
        (WasapiAbi.S_FALSE < 0) shouldBe false
    }

    @Test
    fun `the notification vtable is exactly as long as the interface`() {
        // The synthesised object has to fill every slot: a short vtable means
        // the shell calls through whatever memory follows it.
        WasapiAbi.NOTIFY_VTABLE_SLOTS shouldBe WasapiAbi.NOTIFY_ON_PROPERTY_VALUE_CHANGED + 1
    }
}
