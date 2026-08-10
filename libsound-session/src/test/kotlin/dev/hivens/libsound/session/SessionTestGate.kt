package dev.hivens.libsound.session

import org.junit.jupiter.api.Assumptions

/**
 * The same anti-false-green gate the audio module uses, for the same reason.
 *
 * A suite that needs a session bus and skips when there is none reads exactly
 * like one that passed. `LIBSOUND_REQUIRE` names what a run is supposed to
 * exercise: a named backend that turns out to be unavailable fails the build,
 * an unnamed one is skipped.
 *
 *     LIBSOUND_REQUIRE=dbus ./gradlew test
 */
internal object SessionTestGate {

    private val required: Set<String> =
        (System.getenv("LIBSOUND_REQUIRE") ?: "")
            .split(',')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()

    fun require(backend: String, available: Boolean, detail: String) {
        if (backend in required) {
            check(available) { "LIBSOUND_REQUIRE names '$backend' but it is not available here: $detail" }
            return
        }
        Assumptions.assumeTrue(available, "$backend unavailable, skipping: $detail")
    }
}
