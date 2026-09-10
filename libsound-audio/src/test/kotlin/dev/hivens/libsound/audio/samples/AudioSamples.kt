package dev.hivens.libsound.audio.samples

import dev.hivens.libsound.AudioBackend
import dev.hivens.libsound.AudioDevice
import dev.hivens.libsound.AudioFormat
import dev.hivens.libsound.AudioSink
import dev.hivens.libsound.AudioSource
import dev.hivens.libsound.AudioStream
import dev.hivens.libsound.Capability
import dev.hivens.libsound.CardId
import dev.hivens.libsound.DeviceId
import dev.hivens.libsound.LatencyProfile
import dev.hivens.libsound.MediaRole
import dev.hivens.libsound.SinkConfig
import dev.hivens.libsound.SourceConfig
import dev.hivens.libsound.VolumeMixer
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

    // -- choosing a rung by what it can do -----------------------------------

    /**
     * For the consumer that cannot adapt to a capability being absent.
     *
     * On Linux the native rung and the libpulse one below it differ by exactly
     * this, so naming it here is what decides which one answers.
     */
    fun needingASampleCache(): AudioBackend? {
        val backend = AudioBackends.open("Example", setOf(Capability.SAMPLE_CACHE))
        return backend
    }

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

    // -- asking for a shorter path -------------------------------------------

    fun lowLatencySink(backend: AudioBackend): AudioSink {
        // A profile is a request rather than a promise: the graph's quantum is
        // a floor under it, and what the server granted is what latencyNanos
        // reports once the stream is open.
        val sink = backend.createSink(
            SinkConfig(
                applicationName = "Example",
                latency = LatencyProfile.LOW,
                realtime = true,
            ),
        )
        sink.open(AudioFormat(48_000, 2))
        if (Capability.REALTIME_THREAD !in sink.capabilities) {
            // The system refused the promotion, so the lowest profiles will
            // underrun under load. Worth saying in a settings screen rather
            // than letting a user pick a setting that crackles.
        }
        return sink
    }

    fun profileWasTooAggressive(backend: AudioBackend, sink: AudioSink, play: () -> Unit): Boolean {
        // A latency target nobody can validate is a setting rather than a
        // guarantee. Where the backend cannot count, the number is zero
        // forever, which is what the capability tells apart.
        if (Capability.UNDERRUN_COUNT !in backend.capabilities) return false
        val before = sink.underrunCount()
        play()
        return sink.underrunCount() > before
    }

    // -- recording ------------------------------------------------------------

    fun record(backend: AudioBackend, write: (ByteArray) -> Unit) {
        // A capture stream shows in the desktop's privacy indicator, and the
        // row that names the application reads exactly these fields.
        val source = backend.createSource(
            SourceConfig(
                applicationName = "Example",
                applicationId = "com.example.recorder",
                iconName = "audio-input-microphone",
            ),
        )
        source.use {
            it.open(AudioFormat(48_000, 1))
            val frame = ByteArray(4_800 * 2)
            // Returns when the microphone has produced every byte, which is
            // what makes a recording loop need no timer of its own.
            it.read(frame, 0, frame.size)
            write(frame)
        }
    }

    fun recordOneApplication(backend: AudioBackend, stream: AudioStream): AudioSource? {
        // That application's output and nothing else: not the desktop, not
        // whatever else is playing through the same speakers. It is not told.
        if (Capability.PER_STREAM_CAPTURE !in backend.capabilities) return null
        return backend.createSource(
            SourceConfig(applicationName = "Example", captureStream = stream.id),
        )
    }

    fun microphones(backend: AudioBackend): List<Pair<DeviceId, String>> {
        // A monitor is what the machine is playing, offered back as something
        // to record. Both are inputs and they are not interchangeable.
        return backend.captureDevices().filter { !it.isMonitor }.map { it.id to it.name }
    }

    // -- the devices themselves ----------------------------------------------

    fun quietTheSpeakerItself(mixer: VolumeMixer, device: AudioDevice): Boolean {
        // The other half of a mixer: one application quieted, and the device
        // everything plays through. Put back by restoreAll, like a stream's.
        if (Capability.DEVICE_VOLUME !in mixer.capabilities) return false
        return mixer.setDeviceVolume(device.id, 0.5f)
    }

    fun offerCardProfiles(mixer: VolumeMixer): List<Pair<CardId, String>> {
        // The bluetooth case: good playback, or the low quality mode that has a
        // working microphone. Two profiles of one card.
        return mixer.cards().flatMap { card ->
            card.profiles.filter { it.available }.map { card.id to it.name }
        }
    }

    fun splitAGameOntoItsOwnBus(mixer: VolumeMixer, game: String) {
        // A device this process owns. close() removes whatever is left, which
        // matters more here than for a volume: a virtual sink left behind is a
        // device in a user's settings that nothing owns.
        val bus = mixer.createVirtualSink("example_voice_bus") ?: return
        mixer.streams()
            .filter { it.applicationName == game }
            .forEach { mixer.moveTo(it.id, bus) }
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
