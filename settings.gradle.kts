pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "libsound"

// Artifacts split so a consumer pays only for what it uses: MPRIS without
// libpulse, an output channel without D-Bus. The split is the reason the
// modules exist; the README's module table says which is which.
include(":libsound-core")
include(":libsound-audio")
include(":libsound-session")

// The exception to that rule, and it is a mechanical one. Both the audio module
// (RealtimeKit, for a thread that wakes on time) and the session module (MPRIS)
// talk to a bus, a Kotlin `internal` cannot cross a module boundary, and a
// consumer's classpath has to hold what they call. Nothing here is offered as a
// D-Bus library: see InternalDBusApi.
include(":libsound-dbus")
