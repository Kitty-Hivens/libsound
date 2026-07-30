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

// Three artifacts so a consumer pays only for what it uses: MPRIS without
// libpulse, an output channel without D-Bus. The split is the reason the
// modules exist -- see the plan, section 2.
include(":libsound-core")
include(":libsound-audio")
include(":libsound-session")
