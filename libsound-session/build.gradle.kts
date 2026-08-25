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
