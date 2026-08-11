package dev.hivens.libsound.audio

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Every Kotlin block in `docs/GUIDE.md` is code that compiles.
 *
 * The same discipline as the ABI oracles, applied to prose: an example that no
 * longer compiles reads exactly like one that does, and the person it misleads
 * is the reader who had no other way to check. So the guide quotes the sample
 * files rather than paraphrasing them, and this fails the build when the two
 * drift apart.
 *
 * It compares with indentation and blank lines removed, so a block may be
 * dedented out of its enclosing function without breaking the match, but the
 * lines themselves -- including the comments -- have to be the ones that were
 * compiled.
 */
class GuideSamplesTest {

    @Test
    fun `every kotlin block in the guide comes from a file that compiles`() {
        val guide = repoFile("docs/GUIDE.md")
        val samples = SAMPLE_FILES.map { normalise(repoFile(it).readText()) }

        val blocks = kotlinBlocks(guide.readText())
        // A guide with no examples would pass every assertion below, which is
        // the failure mode this whole test exists to prevent.
        (blocks.size >= 8) shouldBe true

        val orphans = blocks.filter { block ->
            samples.none { sample -> containsRun(sample, normalise(block)) }
        }
        withClue(orphans) { orphans.isEmpty() shouldBe true }
    }

    @Test
    fun `the sample files are where the guide says they are`() {
        // The guide names them by file, so a rename that left the prose behind
        // would send a reader looking for something that is not there.
        val guideText = repoFile("docs/GUIDE.md").readText()
        SAMPLE_FILES.forEach { path ->
            val name = path.substringAfterLast('/')
            withClue(name) { guideText.contains(name) shouldBe true }
        }
    }

    private fun withClue(clue: Any?, body: () -> Unit) {
        runCatching(body).onFailure { throw AssertionError("$clue", it) }
    }

    /** Lines, stripped of indentation, with blanks dropped. */
    private fun normalise(text: String): List<String> =
        text.lines().map { it.trim() }.filter { it.isNotEmpty() }

    private fun containsRun(haystack: List<String>, needle: List<String>): Boolean {
        if (needle.isEmpty() || needle.size > haystack.size) return false
        for (start in 0..(haystack.size - needle.size)) {
            if ((needle.indices).all { haystack[start + it] == needle[it] }) return true
        }
        return false
    }

    private fun kotlinBlocks(markdown: String): List<String> {
        val blocks = mutableListOf<String>()
        val current = StringBuilder()
        var inside = false
        for (line in markdown.lines()) {
            when {
                !inside && line.trim() == FENCE_OPEN -> inside = true
                inside && line.trim() == FENCE_CLOSE -> {
                    blocks += current.toString()
                    current.setLength(0)
                    inside = false
                }
                inside -> current.appendLine(line)
            }
        }
        return blocks
    }

    /**
     * Tests run with the module directory as the working directory, so the repo
     * root is one level up. Resolved rather than assumed: a missing file has to
     * fail loudly here, not quietly turn this into a test of nothing.
     */
    private fun repoFile(path: String): File {
        val file = File("..", path)
        check(file.isFile) { "expected $path at ${file.absolutePath}" }
        return file
    }

    private companion object {
        const val FENCE_OPEN = "```kotlin"
        const val FENCE_CLOSE = "```"

        val SAMPLE_FILES = listOf(
            "libsound-audio/src/test/kotlin/dev/hivens/libsound/audio/samples/AudioSamples.kt",
            "libsound-session/src/test/kotlin/dev/hivens/libsound/session/samples/SessionSamples.kt",
        )
    }
}
