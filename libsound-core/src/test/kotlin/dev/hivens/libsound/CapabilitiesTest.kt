package dev.hivens.libsound

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CapabilitiesTest {

    @Test
    fun `membership is what a settings screen asks`() {
        val caps = Capabilities.of(Capability.DEVICE_ENUMERATION, Capability.DEVICE_SELECTION)
        (Capability.DEVICE_ENUMERATION in caps) shouldBe true
        // The macOS case: a control that cannot work must not be drawn, and the
        // only way to know is to ask before drawing it.
        (Capability.STREAM_VOLUME in caps) shouldBe false
    }

    @Test
    fun `anyOf and allOf answer the two questions a consumer actually has`() {
        val caps = Capabilities.of(Capability.DEVICE_ENUMERATION, Capability.STREAM_IDENTITY)
        caps.anyOf(Capability.STREAM_VOLUME, Capability.STREAM_IDENTITY) shouldBe true
        caps.allOf(Capability.STREAM_VOLUME, Capability.STREAM_IDENTITY) shouldBe false
        caps.allOf(Capability.DEVICE_ENUMERATION, Capability.STREAM_IDENTITY) shouldBe true
    }

    @Test
    fun `the set is copied so a caller cannot mutate a backend's answer`() {
        val mutable = mutableSetOf(Capability.STREAM_VOLUME)
        val caps = Capabilities(mutable)
        mutable.add(Capability.SESSION_READ)
        (Capability.SESSION_READ in caps) shouldBe false
    }

    @Test
    fun `equality is by content so a test can state the whole expected set`() {
        Capabilities.of(Capability.STREAM_VOLUME, Capability.SESSION_READ) shouldBe
            Capabilities.of(Capability.SESSION_READ, Capability.STREAM_VOLUME)
        Capabilities.NONE shouldBe Capabilities(emptySet())
    }

    @Test
    fun `the string form names the members, for the one log line that reports them`() {
        Capabilities.of(Capability.STREAM_VOLUME, Capability.DEVICE_EVENTS).toString() shouldBe
            "Capabilities[DEVICE_EVENTS, STREAM_VOLUME]"
    }
}
