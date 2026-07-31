package dev.hivens.libsound.audio

import org.junit.jupiter.api.Assumptions

/**
 * Turns "the suite skipped" into either "the suite ran" or "the build failed",
 * never into a green row that means nothing.
 *
 * A hardware-touching suite that quietly skips when its device is missing reads
 * exactly like one that passed. skinema paid for that directly: libass loaded on
 * Linux but exported no symbols, and the entire subtitle suite had been skipping
 * for weeks behind a green tick.
 *
 * So `LIBSOUND_REQUIRE` names the backends this run is *supposed* to exercise.
 * A named backend that turns out to be unavailable fails the build. An unnamed
 * one is skipped, which is the convenience a developer wants on a laptop with no
 * sound server. CI names what the row is for; a laptop names nothing.
 *
 *     LIBSOUND_REQUIRE=pulse,javasound ./gradlew test
 */
internal object AudioTestGate {

    private val required: Set<String> =
        (System.getenv("LIBSOUND_REQUIRE") ?: "")
            .split(',')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()

    /**
     * Gate a suite on [available].
     *
     * When [backend] is named in `LIBSOUND_REQUIRE`, an unavailable backend is a
     * failure with the reason attached. Otherwise it is a skip.
     */
    fun require(backend: String, available: Boolean, detail: String) {
        if (backend in required) {
            check(available) {
                "LIBSOUND_REQUIRE names '$backend' but it is not available here: $detail"
            }
            return
        }
        Assumptions.assumeTrue(available, "$backend unavailable, skipping: $detail")
    }

    /** True when this run was told to exercise [backend]. */
    fun isRequired(backend: String): Boolean = backend in required
}
