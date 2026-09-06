package dev.hivens.libsound.audio

import dev.hivens.libsound.AudioBackend
import dev.hivens.libsound.CardId
import dev.hivens.libsound.DeviceId
import dev.hivens.libsound.VolumeMixer
import dev.hivens.libsound.audio.pulse.PulseBackend
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * The devices themselves, and never anybody else's.
 *
 * Every write in this file lands on a device this process created and removes,
 * for the reason the mixer suite gives about streams: a suite that turned the
 * developer's speakers down would be correct and unforgivable. The read-only
 * assertions are made against whatever the machine has.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class PulseDeviceControlTest {

    /**
     * A name no earlier test has used, and the reason is the desktop rather
     * than tidiness.
     *
     * WirePlumber remembers a device's volume and mute against its node name
     * and applies them again when a node with that name appears. A suite that
     * reused one would be racing that restore: the volume set here would land,
     * and a moment later the session manager would put its own remembered value
     * back. Measured, in `~/.local/state/wireplumber/stream-properties`, after
     * this failed once in a full run and never once on its own.
     *
     * JUnit builds one instance per test, so this is per test as well as per
     * process.
     */
    private val name = "libsound_test_sink_${ProcessHandle.current().pid()}_${System.nanoTime()}"

    private var backend: AudioBackend? = null
    private var mixer: VolumeMixer? = null

    @BeforeEach
    fun open() {
        backend = PulseBackend.connectOrNull("libsound device test")
        AudioTestGate.require("pulse", backend != null, "no PulseAudio or PipeWire server")
        mixer = VolumeMixers.open("libsound device test")
        AudioTestGate.require("pulse", mixer != null, "no mixer available")
    }

    @AfterEach
    fun close() {
        // The mixer's close removes what it created, which is also the
        // obligation under test: nothing this suite makes may outlive it.
        mixer?.let { runCatching { it.close() } }
        backend?.let { runCatching { it.close() } }
        mixer = null; backend = null
    }

    private fun devices() = checkNotNull(backend).devices()

    /**
     * Poll until [condition] holds, or give up.
     *
     * The control calls wait for the server's own answer, so a change is
     * applied by the time they return. What is not immediate is the change
     * reaching another connection: this reads the device list through the
     * backend while the change was made through the mixer, and on a graph
     * server the node's own state settles a moment later. Polling is what makes
     * that a wait rather than a flake on a loaded machine.
     */
    private fun eventually(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + SETTLE_TIMEOUT_NANOS
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(SETTLE_POLL_MILLIS)
        }
        // What the devices actually looked like when the wait ran out. A bare
        // "it never happened" sends whoever reads the failure back to the
        // machine to ask the question again, and on a graph server the answer
        // may not be there any more by then.
        val seen = (devices() + checkNotNull(backend).captureDevices()).joinToString("\n") {
            "  ${it.id} volume=${it.volume} muted=${it.muted} suspended=${it.isSuspended}"
        }
        val pactl = runCatching {
            ProcessBuilder("pactl", "list", "sinks").redirectErrorStream(true).start()
                .inputStream.readAllBytes().decodeToString()
                .split("Sink #").firstOrNull { it.contains(name) } ?: "(not listed)"
        }.getOrDefault("(pactl absent)")
        throw AssertionError("the server never reported: $what\nwhat it did report:\n$seen\npactl:\n$pactl")
    }

    private companion object {
        /** Generous: this is a wait for another connection to catch up, not a timeout. */
        const val SETTLE_TIMEOUT_NANOS = 5_000_000_000L
        const val SETTLE_POLL_MILLIS = 50L
    }

    @Test
    fun `a device reports its own volume, mute and ports`() {
        val all = devices() + checkNotNull(backend).captureDevices()
        all.isNotEmpty() shouldBe true
        // Read at oracle-derived offsets past everything this backend used to
        // read, so a wrong one shows up here as a volume outside its range or a
        // port list of nonsense rather than as a plausible label.
        all.all { it.volume != null && it.volume!! in 0f..1f } shouldBe true
        all.all { it.muted != null } shouldBe true
        all.all { device ->
            device.ports.all { it.name.isNotBlank() && it.description.isNotBlank() }
        } shouldBe true
        // The active port is one of the listed ones, or there is none at all.
        all.all { device -> device.activePort == null || device.ports.any { it.name == device.activePort } } shouldBe true
    }

    @Test
    fun `a card lists profiles, and the active one is among them`() {
        // A machine with no card at all is an ordinary answer here: a container
        // whose only device is a null sink has none, and an empty list is what
        // that looks like.
        checkNotNull(mixer).cards().forEach { card ->
            card.id.value.isNotBlank() shouldBe true
            card.profiles.all { it.name.isNotBlank() && it.description.isNotBlank() } shouldBe true
            if (card.activeProfile != null) {
                card.profiles.any { it.name == card.activeProfile } shouldBe true
            }
        }
    }

    @Test
    fun `a virtual sink appears, takes a volume, and goes away again`() {
        val mixer = checkNotNull(mixer)
        val id = mixer.createVirtualSink(name)
        checkNotNull(id) { "the server refused a null sink, which is the one module this needs" }

        val created = checkNotNull(devices().firstOrNull { it.id == id }) {
            "a device that was created should be in the device list"
        }
        // A fresh null sink starts at full volume, which is what makes the
        // change below observable rather than a coincidence.
        checkNotNull(created.volume) shouldBe 1f

        // Asked once for the server's own answer, then asked again until it
        // sticks, and the difference between the two is the point. A device
        // that has just been created is adopted by the session manager a moment
        // after it appears, and an adoption that lands after this call puts the
        // volume back to the value it decided on. Measured: both channels at
        // 100 percent on a set the server had already reported as successful.
        // What the library promises is what the server said about the request,
        // not that nobody else touches the device afterwards.
        mixer.setDeviceVolume(id, 0.4f) shouldBe true
        eventually("the device volume at 0.4") {
            mixer.setDeviceVolume(id, 0.4f)
            val volume = devices().firstOrNull { it.id == id }?.volume ?: return@eventually false
            abs(volume - 0.4f) < 0.05f
        }

        mixer.setDeviceMuted(id, true) shouldBe true
        eventually("the device muted") {
            mixer.setDeviceMuted(id, true)
            devices().firstOrNull { it.id == id }?.muted == true
        }

        // The same obligation the stream half carries, and for a stronger
        // reason: a device left quiet is the one a user blames on their
        // hardware.
        mixer.restoreAll()
        // restoreAll removes what this process created before it restores
        // anything, so the device is gone rather than merely restored.
        eventually("the device gone") { devices().none { it.id == id } }
    }

    @Test
    fun `a virtual sink is removed when the mixer closes`() {
        val id = checkNotNull(checkNotNull(mixer).createVirtualSink(name))
        devices().any { it.id == id } shouldBe true

        checkNotNull(mixer).close()
        // A device in a user's settings that nothing owns and nothing will
        // remove is the failure this obligation exists to prevent.
        eventually("the device gone") { devices().none { it.id == id } }
    }

    @Test
    fun `two devices can be played to as one`() {
        // The combined sink is a device like any other once it exists, which is
        // what makes it useful: the routing this library already has can move an
        // application into it without touching that application's settings.
        val mixer = checkNotNull(mixer)
        val first = checkNotNull(mixer.createVirtualSink("${name}_a"))
        val second = checkNotNull(mixer.createVirtualSink("${name}_b"))

        val both = checkNotNull(mixer.combineSinks("${name}_both", listOf(first, second))) {
            "the server refused module-combine-sink"
        }
        devices().any { it.id == both } shouldBe true
        mixer.removeVirtualSink(both) shouldBe true
        eventually("the combined device gone") { devices().none { it.id == both } }
    }

    @Test
    fun `a device this process did not create is not ours to remove`() {
        val existing = devices().firstOrNull() ?: return
        checkNotNull(mixer).removeVirtualSink(existing.id) shouldBe false
        // And it is still there, which is the half that would matter if the
        // check above were ever inverted.
        devices().any { it.id == existing.id } shouldBe true
    }

    @Test
    fun `a name that would be read as more arguments is refused`() {
        val mixer = checkNotNull(mixer)
        // A module argument is a flat string of key=value pairs, so a space or a
        // quote in the name would become another argument. Refused rather than
        // escaped.
        mixer.createVirtualSink("two words") shouldBe null
        mixer.createVirtualSink("quote\"inside") shouldBe null
        mixer.createVirtualSink("") shouldBe null
    }

    @Test
    fun `control of something that is gone answers false rather than pretending`() {
        val mixer = checkNotNull(mixer)
        val absent = DeviceId("libsound_no_such_device")
        mixer.setDeviceVolume(absent, 0.5f) shouldBe false
        mixer.setDeviceMuted(absent, true) shouldBe false
        mixer.setDefaultDevice(absent) shouldBe false
        mixer.setDevicePort(absent, "any") shouldBe false
        mixer.setCardProfile(CardId("libsound_no_such_card"), "any") shouldBe false
    }
}
