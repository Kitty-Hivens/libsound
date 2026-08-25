package dev.hivens.libsound.audio.samples

import dev.hivens.libsound.AudioBackend
import dev.hivens.libsound.AudioFormat
import dev.hivens.libsound.VolumeMixer
import dev.hivens.libsound.Capability
import dev.hivens.libsound.DeviceId
import dev.hivens.libsound.MediaRole
import dev.hivens.libsound.SinkConfig
import dev.hivens.libsound.audio.AudioBackends
import dev.hivens.libsound.audio.VolumeMixers

/**
 * Every audio example in `docs/GUIDE.md`, as code that compiles.
 *
 * The guide quotes these verbatim and `GuideSamplesTest` fails if it drifts.
 * The same reason the ABI numbers come from an oracle rather than from memory:
 * an example that no longer compiles reads exactly like one that does, and the
 * person it misleads is the one who had no other way to check.
 *
 * Nothing here is run. It exists to be compiled and quoted.
 */
@Suppress("unused", "UNUSED_PARAMETER")
internal object AudioSamples {

    // -- playing something ---------------------------------------------------

    fun play(pcm: ByteArray) {
        val backend = AudioBackends.open("Example") ?: error("this machine cannot play audio at all")
        backend.use {
            val sink = it.createSink(
                SinkConfig(
                    applicationName = "Example",
                    applicationId = "com.example.player",
                    iconName = "audio-x-generic",
                    mediaRole = MediaRole.MUSIC,
                ),
            )
            sink.use { channel ->
                channel.open(AudioFormat(48_000, 2))
                // Returns when the device has taken the bytes, not when they
                // were queued. That is what lets a write loop double as a clock.
                channel.write(pcm, 0, pcm.size)
            }
        }
    }

    // -- asking before drawing -----------------------------------------------

    fun deviceMenu(backend: AudioBackend): List<Pair<DeviceId, String>> {
        if (Capability.DEVICE_SELECTION !in backend.capabilities) {
            // A JavaSound fallback cannot choose a device. Drawing the menu
            // anyway would offer a control that silently does nothing.
            return emptyList()
        }
        return backend.devices().map { it.id to it.name }
    }

    // -- quieting everyone else while a video plays --------------------------

    fun duckOthers(mixer: VolumeMixer, factor: Float) {
        if (Capability.STREAM_CONTROL !in mixer.capabilities) return
        mixer.streams()
            .filter { !it.isOurs && it.active }
            .forEach { mixer.setVolume(it.id, it.volume * factor) }
    }

    fun stopDucking(mixer: VolumeMixer) {
        // Undoes every change this process made and has not already undone.
        // close() calls it too, but a feature that ends with the video should
        // not wait for the process to end.
        mixer.restoreAll()
    }

    fun duckAroundAVideo(playVideo: () -> Unit) {
        val mixer = VolumeMixers.open("Example")
        if (mixer == null) {
            // macOS has no per-application volume in any public API. The video
            // still plays; it just plays over the music.
            playVideo()
            return
        }
        mixer.use {
            duckOthers(it, factor = 0.3f)
            try {
                playVideo()
            } finally {
                stopDucking(it)
            }
        }
    }

    // -- preferring the role, where the desktop honours it --------------------

    fun videoSink(backend: AudioBackend): SinkConfig {
        // A role is enforced by the session manager and vanishes with the stream
        // that asked for it. Direct volume does neither, which is why it is the
        // fallback rather than the default: a process that lowers something and
        // then crashes leaves a user with quiet audio and nothing to point at.
        val duckingWorks = Capability.DUCKS_OTHERS in backend.capabilities
        return SinkConfig(
            applicationName = "Example",
            mediaRole = if (duckingWorks) MediaRole.VIDEO else MediaRole.MUSIC,
        )
    }
}
