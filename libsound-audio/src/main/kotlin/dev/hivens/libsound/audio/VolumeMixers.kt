package dev.hivens.libsound.audio

import dev.hivens.libsound.Capability
import dev.hivens.libsound.VolumeMixer
import dev.hivens.libsound.audio.pipewire.PipeWireMixer
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
 *
 * ## Two rungs on Linux, and the wider one is not the native one
 *
 * The native rung's capabilities are a subset of the libpulse rung's, short by
 * exactly one: [Capability.DEVICE_PROFILES], which covers cards, profiles and
 * ports. Everything else the libpulse mixer does, including virtual devices,
 * the native one does too.
 *
 * That subset is why the widest goes first here, which is the opposite of the
 * order [AudioBackends] uses. There the native rung offered something the one
 * below it did not, so going direct cost nothing. Here going direct would cost
 * a consumer its card panel on every machine that has the shim installed,
 * without that consumer having asked for anything.
 *
 * It also means [open] with a set of needs cannot move the choice on a machine
 * where the libpulse rung opens, since that rung satisfies everything the
 * native one could. What it does there is refuse: a machine without
 * `pipewire-pulse` reaches the native rung, and a caller that named
 * [Capability.DEVICE_PROFILES] is told null rather than handed a mixer whose
 * card list is empty.
 *
 * What the native rung is for is the machine that has no other. PipeWire without
 * `pipewire-pulse` had a backend that played and no mixer at all, which is also
 * where reading a device's volume without being able to set it stopped being an
 * honest capability. That machine now gets one.
 */
public object VolumeMixers {

    private val log = LoggerFactory.getLogger("libsound.Mixer")

    /**
     * Open the mixer this platform has, or null where it has none.
     *
     * Null on macOS for good: the platform exposes no per-application volume
     * in any public API, so a consumer asks here and hides the feature rather
     * than drawing a panel that can never fill.
     *
     * [applicationName] names this process's own connection to the server, so
     * a user looking at what is attached sees something they recognise.
     */
    public fun open(applicationName: String): VolumeMixer? = open(applicationName, emptySet())

    /**
     * The same, refusing a mixer that cannot do what the caller needs.
     *
     * The same question [VolumeMixer.capabilities] answers, asked one step
     * earlier, and for the consumer that cannot adapt rather than the one that
     * can. On Linux the two rungs differ by [Capability.DEVICE_PROFILES] alone,
     * and the rung that has it is already first, so what naming it buys is the
     * refusal: a panel whose whole feature is a card's profile is told null on
     * a machine that offers no such rung, instead of opening one and finding
     * the card list empty.
     *
     * A rung that opens and turns out to be short is closed again before the
     * next is tried, because an open mixer is a connection the server is
     * holding. Naming something no mixer on this platform offers answers null
     * rather than handing back one that was already told it would not do.
     */
    public fun open(applicationName: String, needs: Set<Capability>): VolumeMixer? {
        val osName = System.getProperty("os.name", "").lowercase()
        val mixer = chain(osName, applicationName).firstNotNullOfOrNull { rung ->
            val candidate = rung() ?: return@firstNotNullOfOrNull null
            val missing = needs.filterNot { it in candidate.capabilities }
            if (missing.isEmpty()) return@firstNotNullOfOrNull candidate
            log.info(
                "a mixer was passed over: it does not offer {}",
                missing.joinToString(", ") { it.name },
            )
            runCatching { candidate.close() }
            null
        }
        when {
            mixer != null -> log.info("mixer: {}", mixer.capabilities)
            needs.isEmpty() -> log.info("no mixer available for os.name={}", osName)
            else -> log.info("no mixer on this machine offers {}", needs.joinToString(", ") { it.name })
        }
        return mixer
    }

    /**
     * What this platform offers, widest first.
     *
     * Each rung is a function rather than a mixer, because opening one is a
     * connection to a server: the rung below is never opened on a machine where
     * the one above answers.
     */
    private fun chain(osName: String, applicationName: String): List<() -> VolumeMixer?> = when {
        osName.contains("linux") || osName.contains("bsd") -> listOf(
            { PulseMixer.openOrNull(applicationName) },
            { PipeWireMixer.openOrNull(applicationName) },
        )
        osName.contains("windows") -> listOf({ WasapiMixer.openOrNull() })
        // macOS has no per-application volume to enumerate in any public API,
        // so its absence here is the platform's answer rather than a gap in
        // this library.
        else -> emptyList()
    }
}
