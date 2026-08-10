import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// No mavenPublish and no signing yet, deliberately. This module has no source
// in it: the session contracts live in libsound-core and the MPRIS, SMTC and
// MPNowPlayingInfoCenter backends are the next phases. Publishing an empty,
// signed artifact whose POM advertises all three would promise a consumer
// something they cannot obtain -- and a Central version, once taken, cannot be
// reissued. The plugin goes back the moment there is an implementation.
plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

java {
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

// Prints the test runtime classpath, so a scratch harness can run against the
// module without a published artifact. Diagnostic only.
tasks.register("printTestCp") {
    val cp = sourceSets.test.map { it.runtimeClasspath }
    doLast { println(cp.get().asPath) }
}
