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
    // On the compile classpath rather than runtime-only, and only in this
    // module: ContractRunner starts a suite through the launcher API by hand,
    // which is the one way the Windows contract suite can execute at all. It
    // was already on the test runtime classpath -- nothing new is fetched, and
    // nothing here reaches the published jar.
    testImplementation(libs.junit.platform.launcher)
    testRuntimeOnly(libs.slf4j.simple)
    testImplementation(libs.kotest.assertions)
    testImplementation(testFixtures(project(":libsound-core")))
}

tasks.test {
    jvmArgs("--enable-native-access=ALL-UNNAMED")

    // GuideSamplesTest reads the guide and the sample sources as text. Gradle
    // cannot see that from the classpath, so undeclared they are not inputs:
    // the task caches green after the first pass and the check never runs
    // again, however far the guide drifts. Measured -- editing the guide and
    // rerunning gave BUILD SUCCESSFUL in two seconds without executing a test.
    inputs.file(rootProject.file("docs/GUIDE.md"))
        .withPropertyName("guide")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(rootProject.file("libsound-audio/src/test/kotlin/dev/hivens/libsound/audio/samples"))
        .withPropertyName("audioSampleSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(rootProject.file("libsound-session/src/test/kotlin/dev/hivens/libsound/session/samples"))
        .withPropertyName("sessionSampleSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

// The hand check for platforms no runner can verify. Windows and macOS runners
// have no output device worth trusting, so their backends would otherwise reach
// a release having never executed; this is what a person runs instead.
//
// In the test source set, so it stays out of the published jar.
tasks.register<JavaExec>("smoke") {
    group = "verification"
    description = "Run the audible hand check against whatever backend this machine has."
    mainClass.set("dev.hivens.libsound.audio.smoke.SmokeCheckKt")
    classpath = sourceSets["test"].runtimeClasspath
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    standardInput = System.`in`
}

// The classpath the smoke check needs, written out so something other than
// Gradle can launch it -- specifically a Windows JVM under wine, which runs the
// same bytecode but cannot run this build.
tasks.register("smokeClasspath") {
    val classpath = sourceSets["test"].runtimeClasspath
    val output = layout.buildDirectory.file("smoke-classpath.txt")
    inputs.files(classpath)
    outputs.file(output)
    doLast {
        output.get().asFile.writeText(classpath.joinToString("\n") { it.absolutePath })
    }
}

mavenPublishing {
    pom { description.set("Audio output channel for libsound: PulseAudio, WASAPI, CoreAudio, with a JavaSound fallback.") }
}
