import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    // Dokka before mavenPublish, and the order is load-bearing: the root build
    // reacts to the publish plugin by pointing the javadoc jar at a Dokka task,
    // and that reaction runs while the publish plugin is still being applied.
    // Applied after it, Dokka's tasks would not exist yet.
    alias(libs.plugins.dokka)
    alias(libs.plugins.mavenPublish)
    signing
}

java {
    sourceCompatibility = JavaVersion.toVersion(libs.versions.javaTarget.get())
    targetCompatibility = JavaVersion.toVersion(libs.versions.javaTarget.get())
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(libs.versions.javaTarget.get()))
        freeCompilerArgs.add("-jvm-default=enable")
        // Its own marker, opted into here for the same reason a module can use
        // its own internals: the fence is aimed outward.
        freeCompilerArgs.add("-opt-in=dev.hivens.libsound.dbus.InternalDBusApi")
    }
    // Deliberately no explicitApi(). The other modules enable it because their
    // public surface is a contract somebody compiles against; everything here
    // is public only because a Kotlin `internal` cannot cross a module
    // boundary, and InternalDBusApi is what says so.
}

// Every other module reports its undocumented symbols and has none. This one
// opts out, and the reason is the one InternalDBusApi already gives: nothing
// here is offered to anybody. A reader who reaches these types has opted in to
// a surface that carries no compatibility promise, and what they need is the
// module's own documentation rather than a line on each of seventy handles
// wrapping a libdbus call of the same name.
dokka {
    dokkaSourceSets.configureEach {
        reportUndocumented.set(false)
    }
}

dependencies {
    api(libs.slf4j.api)

    // No test source set of its own. The suites that exercise this code need a
    // session bus and the gate that turns a missing one into a failure rather
    // than a skip, both of which live in libsound-session, and they assert
    // through gdbus and playerctl, which is the only way to prove a message a
    // desktop can read.
}

mavenPublishing {
    pom {
        description.set(
            "Internal D-Bus plumbing shared by libsound-audio and libsound-session. " +
                "Not an API: every type is fenced behind an opt-in marker.",
        )
    }
}
