package dev.hivens.libsound.audio

import dev.hivens.libsound.AudioBackend
import dev.hivens.libsound.audio.coreaudio.CoreAudioBackend
import dev.hivens.libsound.audio.javasound.JavaSoundBackend
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
     */
    public fun open(applicationName: String): AudioBackend? {
        val osName = System.getProperty("os.name", "").lowercase()
        val backend = when {
            osName.contains("linux") || osName.contains("bsd") ->
                PulseBackend.connectOrNull(applicationName) ?: fallback("no PulseAudio or PipeWire")
            osName.contains("windows") ->
                WasapiBackend.connectOrNull() ?: fallback("WASAPI unavailable")
            osName.contains("mac") ->
                CoreAudioBackend.connectOrNull() ?: fallback("CoreAudio unavailable")
            else -> fallback("no native backend for os.name=$osName")
        }
        if (backend == null) {
            log.warn("no audio backend available on this machine")
        } else {
            log.info("audio backend: {} {}", backend.name, backend.capabilities)
        }
        return backend
    }

    private fun fallback(reason: String): AudioBackend? {
        log.debug("falling back to JavaSound: {}", reason)
        return JavaSoundBackend.createOrNull()
    }
}
