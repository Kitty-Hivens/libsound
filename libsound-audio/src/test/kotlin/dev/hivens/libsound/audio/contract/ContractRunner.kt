package dev.hivens.libsound.audio.contract

import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
import org.junit.platform.launcher.listeners.SummaryGeneratingListener
import java.io.PrintWriter
import kotlin.system.exitProcess

/**
 * Runs one JUnit class without Gradle.
 *
 * The Windows backend's contract suite had never executed anywhere, and the
 * reason was mechanical rather than deep. CI runs the audible check on a Windows
 * JVM under wine; the contract suite is a JUnit class that Gradle launches, and
 * Gradle cannot run there. So the two halves sat a metre apart -- a Windows JVM
 * carrying the test classes, and a suite nothing on that JVM knew how to start.
 *
 * This is the missing half. The launcher API is already on the classpath the
 * smoke check uses, so nothing new is fetched, and this lives in the test source
 * set beside that check rather than in anything published.
 *
 * Not a replacement for Gradle anywhere Gradle runs. It exists for the one
 * platform where Gradle cannot.
 */
fun main(args: Array<String>) {
    val className = args.firstOrNull() ?: error("usage: ContractRunner <fully.qualified.TestClass>")
    println("running $className")
    println("os.name       ${System.getProperty("os.name")} ${System.getProperty("os.version")}")
    println("java          ${System.getProperty("java.version")} (${System.getProperty("java.vendor")})")
    println("LIBSOUND_REQUIRE=${System.getenv("LIBSOUND_REQUIRE") ?: "(unset)"}")
    println()

    val listener = SummaryGeneratingListener()
    LauncherFactory.create().execute(
        LauncherDiscoveryRequestBuilder.request().selectors(selectClass(className)).build(),
        listener,
    )

    val summary = listener.summary
    val out = PrintWriter(System.out)
    summary.printTo(out)
    summary.printFailuresTo(out, MAX_STACK_FRAMES)
    out.flush()

    // A suite that discovered nothing is the failure this whole exercise is
    // about: it reads exactly like a suite that passed. So the count is checked
    // rather than trusted, the same way LIBSOUND_EXPECT checks Gradle's.
    if (summary.testsFoundCount == 0L) {
        println("FAIL: no test was discovered in $className")
        exitProcess(NOTHING_DISCOVERED)
    }
    println()
    println(
        "${summary.testsSucceededCount} passed, " +
            "${summary.testsFailedCount} failed, " +
            "${summary.testsSkippedCount} skipped",
    )
    exitProcess(if (summary.testsFailedCount == 0L) 0 else 1)
}

/** Enough of a stack to name the assertion and the line under it. */
private const val MAX_STACK_FRAMES = 12

/** Distinct from a test failure: nothing ran at all. */
private const val NOTHING_DISCOVERED = 2
