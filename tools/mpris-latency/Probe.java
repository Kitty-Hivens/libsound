import dev.hivens.libsound.ForeignPlayer;
import dev.hivens.libsound.MediaSession;
import dev.hivens.libsound.PlaybackState;
import dev.hivens.libsound.SessionCommand;
import dev.hivens.libsound.SessionConfig;
import dev.hivens.libsound.SessionReader;
import dev.hivens.libsound.SessionState;
import dev.hivens.libsound.TrackMetadata;
import dev.hivens.libsound.session.MediaSessions;
import dev.hivens.libsound.session.SessionReaders;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * What each MPRIS path costs, in three measurements.
 *
 * Driven by `run.sh` beside it, which gives it a bus of its own. One line per
 * measurement and nothing else on stdout, so the harness can pair the publishes
 * with what an outside client saw arrive.
 *
 * The publishing side prints when it handed the state over rather than a
 * duration, because the duration that matters ends somewhere else: a `publish`
 * returns as soon as the signal is queued, and what a widget feels is when that
 * signal reached the bus.
 *
 * The reading side times itself. `players()` is the one that scales with the
 * number of players, which is why the harness sweeps that count rather than
 * running once and reporting an average over an arrangement nobody has.
 */
public final class Probe {

    private static final long PID = ProcessHandle.current().pid();

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "publish";
        switch (mode) {
            case "publish" -> publish(Integer.parseInt(args[1]), Long.parseLong(args[2]));
            case "read" -> read(Integer.parseInt(args[1]), Integer.parseInt(args[2]));
            default -> System.out.println("unknown mode " + mode);
        }
    }

    // -- the publishing side ---------------------------------------------------

    private static void publish(int rounds, long gapMillis) throws Exception {
        MediaSession session = MediaSessions.INSTANCE.open(config("libsoundProbe" + PID));
        if (session == null) {
            System.out.println("NOSESSION");
            return;
        }
        // The first publish says everything, so it is not a diff and not
        // representative. Measured from the second onwards.
        session.publish(state(PlaybackState.PAUSED, track("warm", "/w"), 0));
        Thread.sleep(700);

        for (int round = 0; round < rounds; round++) {
            PlaybackState playback = round % 2 == 0 ? PlaybackState.PLAYING : PlaybackState.PAUSED;
            SessionState next = state(playback, track("track " + round, "/t" + round), round * 1_000_000L);
            long before = micros();
            session.publish(next);
            long after = micros();
            System.out.println("PUB " + round + " " + before + " " + (after - before));
            Thread.sleep(gapMillis);
        }
        Thread.sleep(300);
        session.close();
    }

    // -- the reading side ------------------------------------------------------

    private static void read(int players, int rounds) throws Exception {
        List<MediaSession> published = new ArrayList<>();
        for (int index = 0; index < players; index++) {
            MediaSession session = MediaSessions.INSTANCE.open(config("libsoundProbe" + PID + "x" + index));
            if (session == null) continue;
            session.publish(state(PlaybackState.PLAYING, track("track", "/t"), 0));
            published.add(session);
        }
        Thread.sleep(600);

        SessionReader reader = SessionReaders.INSTANCE.open();
        if (reader == null) {
            System.out.println("NOREADER");
            return;
        }
        // Warm the connection and the owner cache the way a consumer's first
        // draw would.
        List<ForeignPlayer> seen = reader.players();
        System.out.println("WARM " + seen.size());

        for (int round = 0; round < rounds; round++) {
            long before = nanos();
            List<ForeignPlayer> now = reader.players();
            long elapsed = nanos() - before;
            System.out.println("PLAYERS " + now.size() + " " + (elapsed / 1000));
            Thread.sleep(250);
        }

        String target = seen.isEmpty() ? null : seen.get(0).getId();
        // Both calls are made from the same phase of the poll window, so what
        // differs between them is the peer's own response time and nothing
        // else: one is answered by our own session, the other by the daemon.
        for (int round = 0; round < rounds; round++) {
            if (target != null) {
                Thread.sleep(250);
                long before = nanos();
                reader.control(target, SessionCommand.PlayPause.INSTANCE);
                System.out.println("CONTROL " + ((nanos() - before) / 1000));
            }
            Thread.sleep(250);
            long before = nanos();
            reader.control("org.mpris.MediaPlayer2.libsoundNobody" + PID, SessionCommand.PlayPause.INSTANCE);
            System.out.println("CONTROLMISS " + ((nanos() - before) / 1000));
        }

        reader.close();
        published.forEach(MediaSession::close);
    }

    // -- fixtures --------------------------------------------------------------

    private static SessionConfig config(String name) {
        return new SessionConfig(name, "libsound probe", null, false, true, false, null);
    }

    private static TrackMetadata track(String title, String id) {
        return new TrackMetadata(title, List.of("probe"), "album", List.of(), 180_000_000L, 1, null, id);
    }

    private static SessionState state(PlaybackState playback, TrackMetadata metadata, long position) {
        return new SessionState(
            playback, metadata, position,
            true, true, true, true, true,
            1.0, 1.0, null, null, null
        );
    }

    private static long micros() {
        Instant now = Instant.now();
        return now.getEpochSecond() * 1_000_000L + now.getNano() / 1_000L;
    }

    private static long nanos() {
        return System.nanoTime();
    }
}
