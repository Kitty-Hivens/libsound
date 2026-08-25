package dev.hivens.libsound.session

import dev.hivens.libsound.SessionReader
import dev.hivens.libsound.session.mpris.MprisReader
import org.slf4j.LoggerFactory

/**
 * Reads and drives everybody else's media sessions.
 *
 * The half a shell needs rather than a player: what is playing anywhere on the
 * machine, its title and artwork, and the means to pause or skip it. This is
 * what a panel widget or a media-key handler is built on.
 *
 * Control here is not the same kind of act as changing a stream's volume through
 * [dev.hivens.libsound.VolumeMixer]. A player publishes the methods it is willing
 * to accept and advertises whether it will accept them; calling one is taking it
 * up on that offer, which is why [dev.hivens.libsound.ForeignPlayer.canControl]
 * exists and why a player that says no is left alone.
 *
 * Returns null where there is nothing to read: no session bus on Linux, and no
 * backend elsewhere. macOS in particular has no public API for reading another
 * application's session at all, so its absence there is the platform's answer
 * rather than a gap here.
 */
public object SessionReaders {

    private val log = LoggerFactory.getLogger("libsound.Session")

    public fun open(): SessionReader? {
        val osName = System.getProperty("os.name", "").lowercase()
        val reader = when {
            osName.contains("linux") || osName.contains("bsd") -> MprisReader.openOrNull()
            else -> null
        }
        if (reader == null) log.info("no session reader available for os.name={}", osName)
        else log.info("session reader: {}", reader.capabilities)
        return reader
    }
}
