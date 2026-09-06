package dev.hivens.libsound.dbus

/**
 * Marks the whole of this module: plumbing two libsound artifacts share, and
 * not a D-Bus library offered to anybody else.
 *
 * The family rule is that nobody ships a general D-Bus layer, because the
 * repositories that speak D-Bus use three different shapes of the protocol and
 * a shared artefact would become the union of all of them. That rule is about
 * what is *offered*, and this module offers nothing: `libsound-session`
 * publishes MPRIS with it and `libsound-audio` asks RealtimeKit for a
 * real-time thread with it, and they are the two consumers there will ever be.
 *
 * It exists as its own artifact for one mechanical reason. Both modules need
 * the same code, a Kotlin `internal` cannot cross a module boundary, and the
 * classpath a consumer runs on has to contain what they call. So the types are
 * public because the compiler requires it, and this annotation says what the
 * `internal` keyword would have said: the surface can change in any release,
 * and a consumer that opts in owns the consequences.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "libsound-dbus is internal plumbing shared by libsound-audio and libsound-session. " +
        "Its surface carries no compatibility promise and may change in any release.",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
public annotation class InternalDBusApi
