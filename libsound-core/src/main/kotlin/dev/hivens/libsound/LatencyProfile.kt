package dev.hivens.libsound

/**
 * How long the path from a write to the speaker is allowed to be.
 *
 * A named target rather than a number, because most callers know what they are
 * building and not what a buffer should be. [SinkConfig.bufferNanos] stays as
 * the escape hatch for the caller that does know exactly.
 *
 * ## A request and a ceiling, not a promise
 *
 * What a client asks for is what the sound server tries to reach, and what it
 * actually gets is a property of the machine. PipeWire's graph quantum is a
 * floor: a client asking for less than `clock.quantum` gets the quantum, and
 * the default of 1024 frames is 21 ms, so on a desktop nobody has configured
 * for audio work [LOW] and [LOWEST] are not reachable by asking. Going below
 * the quantum means reconfiguring the graph for every other application on the
 * machine, which is not something a library does to a process that only wanted
 * to play a sound.
 *
 * So a sink reports what it was granted, through [AudioSink.latencyNanos] and
 * through one line of log at open. A consumer that asked for [LOWEST] and got
 * 21 ms can find that out without a packet trace, and a settings screen can say
 * why the lowest setting is unavailable rather than letting a user pick one
 * that crackles.
 *
 * At 48 kHz these are, in frames: 9600, 1920, 480 and 240.
 */
public enum class LatencyProfile(
    /**
     * The buffer this profile asks for. A request and a ceiling rather than a
     * promise: the graph's own quantum is a floor under it, and what was
     * granted is what [AudioSink.latencyNanos] reports once the stream is open.
     */
    public val targetNanos: Long,
) {

    /** 200 ms. Never underruns. For a file player nobody is interacting with. */
    RELAXED(200_000_000L),

    /** 40 ms. Video sync and music. The default. */
    BALANCED(40_000_000L),

    /** 10 ms. A keypress is heard as a keypress. */
    LOW(10_000_000L),

    /**
     * 5 ms. What the machine can do, and it will underrun on a busy one.
     *
     * Worth pairing with [SinkConfig.realtime]: at this depth the writing
     * thread has about five milliseconds to wake, and an ordinary JVM thread
     * competing with a compile or a game loop will be late. Every time it is
     * late the audio underruns, which [AudioSink.underrunCount] is there to
     * make visible.
     */
    LOWEST(5_000_000L),
}
