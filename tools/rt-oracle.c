/*
 * Offset oracle for the pieces a real-time writer thread needs.
 *
 * Not part of the build. Run it by hand on the target libc and transcribe its
 * output into the Kotlin ABI table. The numbers here are small and dull, which
 * is exactly why they get guessed: RLIMIT_RTTIME is a plain integer that means
 * something else entirely if it is wrong, and struct rlimit is two words whose
 * width is not the same on every libc this library runs against.
 *
 *   gcc -o rt-oracle rt-oracle.c
 *   ./rt-oracle
 */

#define _GNU_SOURCE

#include <sched.h>
#include <stddef.h>
#include <stdio.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <sys/time.h>
#include <unistd.h>

#define P(expr) printf("%-46s = %lld\n", #expr, (long long)(expr))
#define SECTION(name) printf("\n== %s ==\n", name)

int main(void) {
    SECTION("setrlimit, which RealtimeKit refuses a process without");
    P(RLIMIT_RTTIME);
    P(sizeof(struct rlimit));
    P(offsetof(struct rlimit, rlim_cur));
    P(offsetof(struct rlimit, rlim_max));
    P(sizeof(rlim_t));

    /* The kernel thread id, which is what RealtimeKit takes. Not the JVM's
     * thread object and not the pid: a process has one pid and as many tids as
     * it has threads. */
    SECTION("gettid");
    P(SYS_gettid);
    P(gettid());
    P(getpid());

    /* Printed for the record rather than to be used. RealtimeKit makes the
     * sched_setscheduler call itself, which is the whole reason it exists: a
     * process that could make it directly would not need a daemon to ask. */
    SECTION("scheduling policies, for reading /proc back");
    P(SCHED_OTHER);
    P(SCHED_FIFO);
    P(SCHED_RR);

    return 0;
}
