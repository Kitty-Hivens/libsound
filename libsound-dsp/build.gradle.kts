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
    // Core's floor, not the backends'. Nothing here touches java.lang.foreign
    // or any platform library: it is arithmetic over a buffer, and a consumer
    // that has libsound-core on a runtime without Panama can have this too.
    sourceCompatibility = JavaVersion.toVersion(libs.versions.javaCoreTarget.get())
    targetCompatibility = JavaVersion.toVersion(libs.versions.javaCoreTarget.get())
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(libs.versions.javaCoreTarget.get()))
        freeCompilerArgs.add("-jvm-default=enable")
    }
    explicitApi()
}

dependencies {
    // The contract and nothing else. The seam this module hangs off is the
    // public AudioSink, so it needs no backend, and depending on one would put
    // libpulse behind a gain stage.
    api(project(":libsound-core"))

    testImplementation(platform("org.junit:junit-bom:${libs.versions.junit.get()}"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotest.assertions)
    // The decorator fixture, which is the whole reason this module can be
    // trusted not to break a consumer's clock.
    testImplementation(testFixtures(project(":libsound-core")))
}

mavenPublishing {
    pom {
        description.set(
            "Processing for libsound: gain, biquad, limiter and a tap, as sinks that wrap a sink. " +
                "Depends on the contracts alone.",
        )
    }
}
