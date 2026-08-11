import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    // The fake backend and the contract suite ship as test fixtures rather
    // than test sources: every backend module has to run the same contract
    // assertions, and a downstream consumer testing its own adapter wants the
    // fake too. Test sources cannot cross a module boundary; fixtures can,
    // without putting test doubles on the main published surface.
    `java-test-fixtures`
    // Dokka before mavenPublish, and the order is load-bearing: the root build
    // reacts to the publish plugin by pointing the javadoc jar at a Dokka task,
    // and that reaction runs while the publish plugin is still being applied.
    // Applied after it, Dokka's tasks would not exist yet.
    alias(libs.plugins.dokka)
    alias(libs.plugins.mavenPublish)
    signing
}

java {
    // Deliberately below the backends' 22. Nothing here touches
    // java.lang.foreign, and a lower floor is what keeps core reusable on a
    // runtime without Panama. If this ever needs raising, the reason has to be
    // written down -- drifting up by inertia closes the Android option
    // silently.
    sourceCompatibility = JavaVersion.toVersion(libs.versions.javaCoreTarget.get())
    targetCompatibility = JavaVersion.toVersion(libs.versions.javaCoreTarget.get())
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(libs.versions.javaCoreTarget.get()))
        freeCompilerArgs.add("-jvm-default=enable")
    }
    // This is the published contract; every public symbol declares its
    // visibility and its return type, so the surface stays deliberate.
    explicitApi()
}

dependencies {
    // No production dependencies at all, on purpose. A consumer must be able
    // to compile against the contract without dragging in a backend, libpulse
    // or D-Bus -- not even a logging facade, which the backends carry
    // themselves.
    testImplementation(platform("org.junit:junit-bom:${libs.versions.junit.get()}"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotest.assertions)

    // The contract suite is JUnit-shaped, so the fixtures carry the test
    // framework as an api dependency -- a backend extends the abstract class
    // and gets everything it needs from this one line.
    testFixturesApi(platform("org.junit:junit-bom:${libs.versions.junit.get()}"))
    testFixturesApi(libs.junit.jupiter)
    testFixturesApi(libs.kotest.assertions)
}

mavenPublishing {
    pom { description.set("Contracts and types for libsound: audio sink, media session, capabilities. No natives, no platform code.") }
}
