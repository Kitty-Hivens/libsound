package dev.hivens.libsound.audio.realtime

/**
 * The handful of libc numbers a real-time promotion needs, printed by
 * `tools/rt-oracle.c` against the target libc.
 *
 * Small and dull, which is exactly why they get guessed. `RLIMIT_RTTIME` is a
 * plain integer that names a different limit if it is wrong, and `struct
 * rlimit` is two words whose width is not the same on every platform this
 * library runs on.
 *
 * Taken from glibc 2.42, x86_64. musl agrees on all of it, which the Alpine row
 * in CI is what actually checks.
 */
internal object RealtimeAbi {

    /** The limit RealtimeKit refuses a process for not having set. */
    const val RLIMIT_RTTIME = 15

    // -- struct rlimit { rlim_t rlim_cur; rlim_t rlim_max; } ------------------

    const val RLIMIT_CUR = 0L
    const val RLIMIT_MAX = 8L
    const val RLIMIT_SIZE = 16L
}
