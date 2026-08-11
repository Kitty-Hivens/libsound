import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.plugins.signing.SigningExtension
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SourcesJar
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.mavenPublish) apply false
    alias(libs.plugins.dokka) apply false
}

// Releases pass -PappVersion=<tag> (tag first, then publish -- the libtray
// flow). The git-describe fallback keeps a local build honest about being a
// build off some commit rather than claiming a release number.
val appVersion: String = providers.gradleProperty("appVersion")
    .orElse(
        providers.exec {
            commandLine("git", "describe", "--tags", "--always", "--dirty")
            isIgnoreExitValue = true
        }.standardOutput.asText.map { it.trim().ifEmpty { "0.0.0-SNAPSHOT" } },
    )
    .getOrElse("0.0.0-SNAPSHOT")

// Present only where the key cannot come from a keyring -- see the signing
// block below.
val inMemoryKey = providers.gradleProperty("signingInMemoryKey")

allprojects {
    group = "dev.hivens"
    version = appVersion
}

subprojects {
    // CI logs carry only the console; without the message a failed assertion
    // is a bare file:line, on every module that ever fails.
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("failed")
            exceptionFormat = TestExceptionFormat.FULL
        }
    }

    // Every warning is an error. Warnings otherwise pile up unseen behind the
    // build cache, which replays a cached compile without re-emitting them; as
    // errors none of them ever caches green.
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions.allWarningsAsErrors.set(true)
    }

    tasks.withType<Jar>().configureEach {
        manifest {
            attributes(
                "Implementation-Title" to project.name,
                "Implementation-Version" to project.version,
                "Implementation-Vendor" to "Kitty-Hivens",
            )
        }
    }

    // Shared Central Portal publishing for every module that opts in by
    // applying the vanniktech plugin; modules add only their description.
    plugins.withId("com.vanniktech.maven.publish") {
        configure<MavenPublishBaseExtension> {
            // The javadoc jar carries the KDoc, because otherwise it carries
            // nothing. Left to itself the publish plugin falls back to
            // plainJavadocJar, which runs the *Java* javadoc tool over a project
            // with no Java sources: the jar it produced held a manifest and no
            // other entry, 25 bytes in total. Central's requirement was
            // satisfied and every word of the documentation stayed on the
            // machine that wrote it.
            //
            // Dokka is applied by each module rather than here: applied from
            // inside this block it would land while the publish plugin is still
            // being applied, and the task named below would not exist yet.
            configure(KotlinJvm(javadocJar = JavadocJar.Dokka("dokkaGeneratePublicationHtml"), sourcesJar = SourcesJar.Sources()))
            // Explicit auto-release: the no-arg form leaves the deployment
            // VALIDATED in the portal, waiting for a manual Publish click.
            publishToMavenCentral(automaticRelease = true)
            signAllPublications()
            coordinates("dev.hivens", project.name, project.version.toString())
            pom {
                name.set(project.name)
                url.set("https://github.com/Kitty-Hivens/libsound")
                licenses {
                    license {
                        name.set("Apache-2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("kitty-hivens")
                        name.set("Kitty-Hivens")
                    }
                }
                scm {
                    url.set("https://github.com/Kitty-Hivens/libsound")
                    connection.set("scm:git:https://github.com/Kitty-Hivens/libsound.git")
                }
            }
        }
    }

    plugins.withId("signing") {
        // CI hands the key over as signingInMemoryKey and the publish plugin
        // wires that up itself; a machine with the key in its keyring has no
        // such property and signs through the gpg agent. Setting both would
        // leave whichever ran last in charge.
        if (!inMemoryKey.isPresent) {
            configure<SigningExtension> {
                useGpgCmd()
            }
        }
    }
}
