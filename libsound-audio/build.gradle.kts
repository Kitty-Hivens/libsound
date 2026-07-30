import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    alias(libs.plugins.mavenPublish)
    signing
}

java {
    // Java 22: java.lang.foreign finalized as JEP 454. The backends are where
    // Panama actually lives.
    sourceCompatibility = JavaVersion.toVersion(libs.versions.javaTarget.get())
    targetCompatibility = JavaVersion.toVersion(libs.versions.javaTarget.get())
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(libs.versions.javaTarget.get()))
        freeCompilerArgs.add("-jvm-default=enable")
    }
    explicitApi()
}

dependencies {
    api(project(":libsound-core"))
    // SLF4J only -- consumers wire their own binding. Backends log at
    // DEBUG/INFO/WARN; nothing fires at ERROR in normal operation, because
    // failures degrade through the capability query rather than throwing.
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

mavenPublishing {
    pom { description.set("Audio output channel for libsound: PulseAudio, WASAPI, CoreAudio, with a JavaSound fallback.") }
}
