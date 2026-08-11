package dev.hivens.libsound.session

import dev.hivens.libsound.MediaSession
import dev.hivens.libsound.SessionConfig
import dev.hivens.libsound.session.mpris.MprisSession
import org.slf4j.LoggerFactory

/**
 * Publishes this application's media session to the desktop.
 *
 * What turns a process that happens to be playing audio into a player the
 * desktop knows about: the name and artwork on the lock screen, the media keys
 * on a keyboard, and the widget in a panel. Whether any of that appears is the
 * desktop's decision, not ours -- we publish, and it either listens or does not.
 *
 * Returns null where there is nothing to publish to: no session bus on Linux, no
 * backend at all elsewhere yet. That is a real answer and a consumer can carry
 * on playing audio without it, which is why it is null rather than an exception.
 *
 * Null also means **somebody already owns the name**. Being queued behind
 * another owner is refused rather than accepted: the desktop talks to whichever
 * process holds the name, so a queued player is indistinguishable from one that
 * is not there, except that it also believes it is publishing.
 */
public object MediaSessions {

    private val log = LoggerFactory.getLogger("libsound.Session")

    public fun open(config: SessionConfig): MediaSession? {
        val osName = System.getProperty("os.name", "").lowercase()
        val session = when {
            osName.contains("linux") || osName.contains("bsd") -> MprisSession.openOrNull(config)
            // Windows has SMTC and macOS has MPNowPlayingInfoCenter; neither is
            // written yet, and a null here says so rather than a stub that
            // accepts every update and shows nothing.
            else -> null
        }
        if (session == null) log.info("no media session available for os.name={}", osName)
        return session
    }
}
