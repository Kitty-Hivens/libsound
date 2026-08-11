package dev.hivens.libsound.audio

import dev.hivens.libsound.AudioMixer
import dev.hivens.libsound.audio.pulse.PulseMixer
import dev.hivens.libsound.audio.wasapi.WasapiMixer
import org.slf4j.LoggerFactory

/**
 * Opens the mixer: every playback stream on the machine, and the means to
 * change them.
 *
 * Separate from [AudioBackends] because they answer different questions. A
 * player asks for a channel to write into; a shell asks what everyone else is
 * doing. Most consumers want one or the other, and the two open their own
 * connections so that neither's traffic sits on the other's path.
 *
 * Returns null where the platform has no mixer to offer -- no sound server on
 * Linux, and macOS at all, which has no per-application volume in any public
 * API. That is a real answer rather than a failure, and
 * [dev.hivens.libsound.Capability.STREAM_ENUMERATION] is how a consumer decides
 * whether to draw the screen at all.
 */
public object AudioMixers {

    private val log = LoggerFactory.getLogger("libsound.Mixer")

    public fun open(applicationName: String): AudioMixer? {
        val osName = System.getProperty("os.name", "").lowercase()
        val mixer = when {
            osName.contains("linux") || osName.contains("bsd") -> PulseMixer.openOrNull(applicationName)
            osName.contains("windows") -> WasapiMixer.openOrNull()
            // macOS has no per-application volume to enumerate in any public
            // API, so its absence here is the platform's answer rather than a
            // gap in this library.
            else -> null
        }
        if (mixer == null) log.info("no mixer available for os.name={}", osName)
        else log.info("mixer: {}", mixer.capabilities)
        return mixer
    }
}
