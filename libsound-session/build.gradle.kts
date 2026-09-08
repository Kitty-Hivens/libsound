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
        // The bus plumbing is fenced behind a marker so that nobody else picks
        // it up by accident. This module is one of the two it was written for.
        freeCompilerArgs.add("-opt-in=dev.hivens.libsound.dbus.InternalDBusApi")
    }
    explicitApi()
}

dependencies {
    api(project(":libsound-core"))
    // implementation, not api: MPRIS is the surface, and no type from the bus
    // layer appears in it. A consumer gets the artifact at runtime and never
    // compiles against it.
    implementation(project(":libsound-dbus"))
    api(libs.slf4j.api)

    testImplementation(platform("org.junit:junit-bom:${libs.versions.junit.get()}"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.slf4j.simple)
    testImplementation(libs.kotest.assertions)
    testImplementation(testFixtures(project(":libsound-core")))
}

tasks.test {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

// The half of the session nothing can assert: whether a desktop drew it, and
// whether a key on a keyboard reached this process. The audio module has the
// same task for the same reason, and this is the layer above it.
//
// In the test source set, so it stays out of the published jar.
tasks.register<JavaExec>("sessionSmoke") {
    group = "verification"
    description = "Publish a session and hold it up for a person to look at."
    mainClass.set("dev.hivens.libsound.session.smoke.SessionSmokeCheckKt")
    classpath = sourceSets["test"].runtimeClasspath
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    standardInput = System.`in`
}

mavenPublishing {
    // What is in the artifact, not what is planned for it -- which is why this
    // line moved when SMTC and MPNowPlayingInfoCenter landed. Naming only MPRIS
    // understated the module by two thirds, and a Central version once taken
    // cannot be reissued to correct it.
    pom {
        description.set(
            "Media session for libsound: publish through MPRIS, SMTC and MPNowPlayingInfoCenter, " +
                "and read other players over D-Bus.",
        )
    }
}
