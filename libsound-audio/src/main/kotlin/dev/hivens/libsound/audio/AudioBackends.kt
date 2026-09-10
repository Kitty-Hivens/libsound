package dev.hivens.libsound.audio

import dev.hivens.libsound.AudioBackend
import dev.hivens.libsound.Capability
import dev.hivens.libsound.audio.coreaudio.CoreAudioBackend
import dev.hivens.libsound.audio.javasound.JavaSoundBackend
import dev.hivens.libsound.audio.pipewire.PipeWireBackend
import dev.hivens.libsound.audio.pulse.PulseBackend
import dev.hivens.libsound.audio.wasapi.WasapiBackend
import org.slf4j.LoggerFactory

/**
 * Backend selection: try the real sound server, fall back, say which one won
 * exactly once.
 *
 * The fallback chain is part of the design rather than an error path. A machine
 * with no sound server, a missing libpulse or a refused connection still plays
 * audio; what it loses is stream identity, system volume and device selection,
 * and it reports that loss through [AudioBackend.capabilities] instead of
 * failing at the call site where a consumer would have to guess why.
 *
 * The one line of log is deliberate. Which backend won determines what the
 * settings screen shows and how the volume slider behaves, so a bug report that
 * says "the device selector is missing" is answered by that line -- and a line
 * printed once is a line people read, unlike one printed per stream.
 *
 * ## Two rungs on Linux, and neither is a compromise
 *
 * PipeWire is spoken natively first, then through its PulseAudio server. The
 * difference is not speed: the compatibility layer can only say what the
 * PulseAudio protocol can say, which is eighteen of the thirty-six channel
 * positions a decoder sends and no 64-bit float at all, so four of the forty
 * layouts FFmpeg names came back as the machine being unable to play the file.
 *
 * The second rung stays, and stays supported, for two machines. One runs real
 * PulseAudio, where the native path has no graph to reach. The other runs
 * PipeWire without `pipewire-pulse`, which is the machine that would otherwise
 * fall all the way to JavaSound.
 *
 * The two report the same capabilities on the same graph but one, which is
 * [Capability.SAMPLE_CACHE]: uploading a sound the server answers to by name is
 * a feature of the PulseAudio protocol and there is nothing behind it in the
 * graph. That is what [open]'s second parameter is for.
 */
public object AudioBackends {

    private val log = LoggerFactory.getLogger("libsound.Backends")

    /**
     * Open the best backend available on this machine.
     *
     * [applicationName] is what the desktop shows for the connection itself,
     * distinct from the per-stream name in `SinkConfig`; on a server that
     * supports neither it is dropped.
     *
     * Returns null only when the JVM cannot play audio at all -- headless, or a
     * container with no device. That is a real state and the caller has to
     * handle it, but it is not the same as "no sound server", which is served
     * by the fallback.
     *
     * @throws IllegalArgumentException where `-Dlibsound.backend=` names
     *   nothing this library has.
     */
    public fun open(applicationName: String): AudioBackend? = open(applicationName, emptySet())

    /**
     * The same, refusing a backend that cannot do what the caller needs.
     *
     * The chain is ordered by what it can say rather than by what any one
     * consumer wants, and on Linux the first rung is missing exactly one thing
     * the second has. A consumer that uses that thing names it here and gets
     * the backend that has it, instead of finding out from a null return at the
     * moment it wanted a sound.
     *
     * This is the same question [AudioBackend.capabilities] answers, asked one
     * step earlier. Asking it afterwards works too and is what a consumer that
     * adapts should do; naming it here is for the one that cannot adapt,
     * because for it the capability is not a feature to hide but the reason it
     * chose a backend.
     *
     * A rung that opens and turns out to be short is closed again before the
     * next is tried, so a refusal costs a connection made and dropped rather
     * than one left attached. Whichever wins, the line of log says what was
     * skipped and why.
     *
     * Naming something no backend on this platform has returns null. That is
     * the honest answer and not a fallback: handing back a backend that was
     * explicitly told it would not do is worse than saying so.
     *
     * One exception, and it is the property. A run that pins a backend with
     * `-Dlibsound.backend=` gets that backend even when it does not offer what
     * was asked for, with the disagreement logged as a warning. The pin exists
     * so somebody comparing two backends on one machine can be sure which one
     * answered, and a pin that silently lost to a capability request would
     * measure the wrong thing and say nothing about it.
     *
     * @throws IllegalArgumentException where `-Dlibsound.backend=` names
     *   nothing this library has, which is a mistake worth failing on rather
     *   than ignoring.
     */
    public fun open(applicationName: String, needs: Set<Capability>): AudioBackend? {
        val osName = System.getProperty("os.name", "").lowercase()
        forced()?.let { return open(it, applicationName, needs) }
        val backend = chain(osName, applicationName).firstNotNullOfOrNull { rung ->
            val candidate = rung() ?: return@firstNotNullOfOrNull null
            val missing = needs.filterNot { it in candidate.capabilities }
            if (missing.isEmpty()) return@firstNotNullOfOrNull candidate
            log.info(
                "{} was passed over: it does not offer {}",
                candidate.name,
                missing.joinToString(", ") { it.name },
            )
            // Closed rather than left attached. A backend that opened is a
            // connection the server is holding and a row in somebody's mixer.
            runCatching { candidate.close() }
            null
        }
        if (backend == null) {
            if (needs.isEmpty()) {
                log.warn("no audio backend available on this machine")
            } else {
                log.warn(
                    "no audio backend on this machine offers {}",
                    needs.joinToString(", ") { it.name },
                )
            }
        } else {
            log.info("audio backend: {} {}", backend.name, backend.capabilities)
            // The type rather than the name: two string literals in two files
            // that have to agree is a note that stops printing the day somebody
            // renames the backend, and nothing would say so.
            if (backend is CoreAudioBackend) log.info(MACOS_SCOPE)
        }
        return backend
    }

    /**
     * What this platform offers, best first.
     *
     * Each rung is a function rather than a backend, because opening one is a
     * connection to a server: the rung below is never reached on a machine
     * where the one above answers, and never opened on a machine where it does.
     */
    private fun chain(osName: String, applicationName: String): List<() -> AudioBackend?> = when {
        osName.contains("linux") || osName.contains("bsd") -> listOf(
            { PipeWireBackend.connectOrNull(applicationName) },
            { PulseBackend.connectOrNull(applicationName) },
            { JavaSoundBackend.createOrNull() },
        )
        osName.contains("windows") -> listOf(
            { WasapiBackend.connectOrNull() },
            { JavaSoundBackend.createOrNull() },
        )
        osName.contains("mac") -> listOf(
            { CoreAudioBackend.connectOrNull() },
            { JavaSoundBackend.createOrNull() },
        )
        // Every JVM has JavaSound, so an unknown platform is a fallback rather
        // than a failure.
        else -> listOf({ JavaSoundBackend.createOrNull() })
    }

    /**
     * Said once, beside the line that names the backend, because a consumer
     * meeting macOS should not have to read a capability set to find out that
     * half the library is not there.
     *
     * Not a warning: the sink is held to the same contract every other backend
     * passes and its suite runs on every push against a real output unit. What
     * is narrow here is the platform, and the two things it costs are worth
     * naming rather than discovering.
     */
    private const val MACOS_SCOPE =
        "coreaudio is the whole of libsound on macOS: the platform has no per-application " +
            "volume in any public API, so no mixer exists here and none will, and a published " +
            "media session has never been confirmed to appear anywhere by a person."

    /**
     * Which backend a run named, or null for the ordinary selection above.
     *
     * `-Dlibsound.backend=pulse` and its siblings. The first person to hit a
     * difference between two backends on one machine needs to be able to pin
     * each of them without rebuilding, and a measurement is worth nothing if
     * the selection quietly moved underneath it.
     *
     * A name nothing matches is a mistake worth failing on rather than
     * silently ignoring: a run that asked for a backend and got another one
     * measures the wrong thing and says nothing about it.
     */
    private fun forced(): String? = System.getProperty(BACKEND_PROPERTY)?.lowercase()?.takeIf { it.isNotBlank() }

    private fun open(named: String, applicationName: String, needs: Set<Capability>): AudioBackend? {
        val backend = when (named) {
            "pipewire" -> PipeWireBackend.connectOrNull(applicationName)
            "pulse" -> PulseBackend.connectOrNull(applicationName)
            "wasapi" -> WasapiBackend.connectOrNull()
            "coreaudio" -> CoreAudioBackend.connectOrNull()
            "javasound" -> JavaSoundBackend.createOrNull()
            else -> throw IllegalArgumentException(
                "$BACKEND_PROPERTY=$named names no backend. One of: pipewire, pulse, wasapi, coreaudio, javasound.",
            )
        }
        if (backend == null) {
            // No fallback here, and deliberately. A run that named a backend
            // and quietly got a different one is a run that measured something
            // else and did not say so, which is the same shade of green a
            // skipped hardware suite is.
            log.warn("{}={} was asked for and is not available here", BACKEND_PROPERTY, named)
            return null
        }
        // The pin wins over the request, and the disagreement is said out loud.
        // Choosing between them the other way would mean a run that pinned a
        // backend and got a different one, which is the one thing the pin
        // exists to prevent.
        val missing = needs.filterNot { it in backend.capabilities }
        if (missing.isNotEmpty()) {
            log.warn(
                "{}={} was asked for and does not offer {}; the pin wins",
                BACKEND_PROPERTY, named, missing.joinToString(", ") { it.name },
            )
        }
        log.info("audio backend: {} {} (asked for by {})", backend.name, backend.capabilities, BACKEND_PROPERTY)
        return backend
    }

    /** What a run names to pin one backend, for the reasons in [forced]. */
    private const val BACKEND_PROPERTY = "libsound.backend"
}
