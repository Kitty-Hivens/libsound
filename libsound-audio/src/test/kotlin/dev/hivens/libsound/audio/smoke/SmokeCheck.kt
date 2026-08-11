package dev.hivens.libsound.audio.smoke

import dev.hivens.libsound.AudioBackend
import dev.hivens.libsound.AudioFormat
import dev.hivens.libsound.AudioMixer
import dev.hivens.libsound.AudioSink
import dev.hivens.libsound.Capability
import dev.hivens.libsound.MediaRole
import dev.hivens.libsound.SinkConfig
import dev.hivens.libsound.audio.AudioBackends
import dev.hivens.libsound.audio.AudioMixers
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.system.exitProcess

/**
 * The hand check, for the platforms no runner can verify.
 *
 * Windows runners have no output device, so the contract suite cannot run there
 * and the backend would reach a release having never executed. This is what a
 * person runs instead: it exercises the same rules the contract asserts, prints
 * a pass or a failure for each, and stops at the questions no assertion can
 * answer -- whether a tone was audible, whether the desktop's mixer shows the
 * stream under our name, whether the slider moves with our volume, whether
 * stopping actually silences the device, and whether the applications we list
 * are the ones the system mixer shows.
 *
 *     ./gradlew smoke
 *
 * Nothing here is a test in the JUnit sense and none of it ships: it lives in
 * the test source set so it stays out of the published jar.
 */
private const val APP = "libsound smoke check"

private const val TONE_HZ = 440.0
private const val TONE_SECONDS = 3
private const val QUIET_VOLUME = 0.25f

private val results = mutableListOf<Pair<String, Boolean>>()

fun main() {
    banner("libsound smoke check")
    println("os.name       ${System.getProperty("os.name")} ${System.getProperty("os.version")}")
    println("os.arch       ${System.getProperty("os.arch")}")
    println("java          ${System.getProperty("java.version")} (${System.getProperty("java.vendor")})")
    println()

    val backend = AudioBackends.open(APP)
    if (backend == null) {
        println("FAIL  no backend at all. Nothing below can run.")
        exitProcess(1)
    }
    println("backend       ${backend.name}")
    println("capabilities  ${backend.capabilities}")
    println()

    try {
        devices(backend)
        playback(backend)
        mixer(backend)
    } finally {
        runCatching { backend.close() }
    }

    banner("automatic checks")
    results.forEach { (label, ok) -> println("${if (ok) "pass" else "FAIL"}  $label") }
    val failed = results.count { !it.second }
    println()
    println("${results.size - failed} passed, $failed failed")
    println()
    println("Then report the answers to the numbered questions above. A run with no")
    println("failures and a yes to each is what this platform needs to ship.")
    exitProcess(if (failed == 0) 0 else 1)
}

private fun devices(backend: AudioBackend) {
    banner("devices")
    if (Capability.DEVICE_ENUMERATION !in backend.capabilities) {
        println("(backend does not enumerate; skipping)")
        return
    }
    val devices = backend.devices()
    devices.forEach { device ->
        println("  ${if (device.isDefault) "*" else " "} ${device.name}")
        println("      id: ${device.id.value}")
    }
    check("devices() returns at least one device", devices.isNotEmpty())
    check("every device has a non-blank name", devices.all { it.name.isNotBlank() })
    check("exactly one device is the default", devices.count { it.isDefault } == 1)
    check("defaultDevice() agrees with the list", backend.defaultDevice()?.isDefault == true)
}

private fun playback(backend: AudioBackend) {
    banner("playback")
    val format = AudioFormat(48_000, 2)
    val sink = backend.createSink(
        SinkConfig(
            applicationName = APP,
            applicationId = "dev.hivens.libsound.smoke",
            iconName = "audio-x-generic",
            mediaRole = MediaRole.MUSIC,
        ),
    )
    try {
        sink.open(format)
        check("open() leaves the sink open", sink.isOpen)
        check("open() reports the format it was given", sink.format == format)
        check("open() starts the frame position at zero", sink.framePosition() == 0L)

        pause(
            "Open the system volume mixer before the tone starts, so you can watch",
            "the row appear:  Windows -> Win+R, sndvol      macOS -> no per-app mixer",
        )
        ask(
            "A steady 440 Hz tone now plays for about $TONE_SECONDS seconds.",
            "1. Do you HEAR it?",
            "2. Does a row named \"$APP\" appear in the mixer?",
        )

        val second = tone(format, seconds = 1)
        sink.write(second, 0, second.size)
        val afterOne = sink.framePosition()
        val expected = format.sampleRate.toLong()
        // A device is not a stopwatch and a buffer holds some of what was
        // written, so this is a sanity band rather than an equality.
        check(
            "playhead is plausible after one second (got $afterOne, want ${expected / 2}..$expected)",
            afterOne in (expected / 2)..expected,
        )
        check("playhead never runs past what was written", afterOne <= expected)

        val latency = sink.latencyNanos()
        println("  latency       ${latency / 1_000_000} ms")
        check("latency is reported and under a second", latency in 0..1_000_000_000L)

        sink.write(second, 0, second.size)
        println()
        println("  -> setting volume to ${(QUIET_VOLUME * 100).roundToInt()}%")
        sink.setVolume(QUIET_VOLUME)
        ask("3. Did the tone get QUIETER, and did the mixer's slider MOVE with it?")
        sink.write(second, 0, second.size)
        check("volume() reports what was set", sink.volume() in (QUIET_VOLUME - 0.01f)..(QUIET_VOLUME + 0.01f))
        sink.setVolume(1f)

        println()
        println("  -> stopping for two seconds")
        val beforeStop = sink.framePosition()
        sink.stop()
        Thread.sleep(2_000)
        val afterStop = sink.framePosition()
        ask("4. Was it SILENT for those two seconds?")
        // The rule a clock depends on: a stopped device consumes nothing, so a
        // position that keeps climbing is a clock that runs away while paused.
        check(
            "stop() freezes the playhead (before=$beforeStop after=$afterStop)",
            afterStop - beforeStop <= format.sampleRate / 10,
        )

        sink.start()
        sink.write(second, 0, second.size)
        check("start() resumes and the playhead moves again", sink.framePosition() > afterStop)

        sink.stop()
        sink.flush()
        check("flush() is valid while stopped and does not throw", true)

        sink.open(format)
        check("re-open() resets the frame position to zero", sink.framePosition() == 0L)
    } catch (e: Throwable) {
        check("playback ran without throwing (${e::class.simpleName}: ${e.message})", false)
    } finally {
        runCatching { sink.close() }
        checkCloseIsIdempotent(sink)
    }
}

private fun checkCloseIsIdempotent(sink: AudioSink) {
    val ok = runCatching { sink.close() }.isSuccess
    check("close() twice does not throw", ok)
    check("a closed sink reports itself closed", !sink.isOpen)
}

private fun mixer(backend: AudioBackend) {
    banner("mixer")
    val mixer: AudioMixer? = AudioMixers.open(APP)
    if (mixer == null) {
        println("(no mixer here. Expected on macOS, which has no per-application")
        println(" volume in any public API; anywhere else it is a finding.)")
        return
    }
    // A stream of our own, alive for this section. The list is otherwise empty
    // on a machine where nothing else happens to be playing, which is every
    // clean test runner -- and an empty list would read as a broken mixer
    // rather than as an idle machine.
    val ours = backend.createSink(SinkConfig(applicationName = APP, mediaRole = MediaRole.MUSIC))
    try {
        val format = AudioFormat(48_000, 2)
        ours.open(format)
        val silence = ByteArray(format.sampleRate / 4 * format.bytesPerFrame)
        ours.write(silence, 0, silence.size)
        Thread.sleep(300)
    } catch (e: Throwable) {
        println("  (could not open a stream of our own: ${e.message})")
    }
    try {
        println("capabilities  ${mixer.capabilities}")
        val streams = mixer.streams()
        streams.forEach { stream ->
            println("  ${if (stream.isOurs) ">" else " "} ${stream.applicationName ?: "(unnamed)"}")
            println("      volume ${(stream.volume * 100).roundToInt()}%  muted=${stream.muted}  active=${stream.active}")
        }
        check("the mixer lists at least one stream", streams.isNotEmpty())
        check("the mixer sees the stream this process just opened", streams.any { it.isOurs })
        check("no stream is listed twice", streams.map { it.id.value }.let { it.size == it.distinct().size })
        // Deliberately reads and does not write: a diagnostic that quietly
        // changed the volume of whatever the tester had playing would be an
        // unpleasant surprise on a machine that is not ours.
        ask(
            "5. Compare this list with what the system mixer shows.",
            "   Are the same applications there, under names you recognise?",
            "   A row named \"javaw\" or blank where the mixer shows a real name",
            "   is a defect worth reporting even though the list is not empty.",
        )
        println("  (read-only: this check never changes anybody else's volume)")
    } finally {
        runCatching { mixer.close() }
        runCatching { ours.close() }
    }
}

/** A fade at each end, so the check reports on the backend and not on a click. */
private fun tone(format: AudioFormat, seconds: Int): ByteArray {
    val frames = format.sampleRate * seconds
    val fade = format.sampleRate / 100
    val out = ByteArray(frames * format.bytesPerFrame)
    var at = 0
    for (frame in 0 until frames) {
        val envelope = when {
            frame < fade -> frame.toDouble() / fade
            frame > frames - fade -> (frames - frame).toDouble() / fade
            else -> 1.0
        }
        val value = sin(2.0 * PI * TONE_HZ * frame / format.sampleRate) * envelope * 0.3
        val sample = (value * Short.MAX_VALUE).toInt().toShort()
        repeat(format.channels) {
            out[at++] = (sample.toInt() and 0xFF).toByte()
            out[at++] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }
    }
    return out
}

private fun check(label: String, ok: Boolean) {
    results += label to ok
    println("  ${if (ok) "pass" else "FAIL"}  $label")
}

private fun ask(vararg lines: String) {
    println()
    lines.forEach { println("  ?  $it") }
    println()
}

/**
 * Wait for the tester, but never for a pipe.
 *
 * Read from a redirected or closed stdin this returns immediately, so the same
 * program is still usable non-interactively -- a check that blocks forever in
 * CI is a check nobody runs twice.
 */
private fun pause(vararg lines: String) {
    println()
    lines.forEach { println("  >  $it") }
    print("  >  Press Enter when ready. ")
    System.out.flush()
    runCatching { readlnOrNull() }
    println()
}

private fun banner(title: String) {
    println()
    println("== $title ${"=".repeat((70 - title.length).coerceAtLeast(3))}")
    println()
}
