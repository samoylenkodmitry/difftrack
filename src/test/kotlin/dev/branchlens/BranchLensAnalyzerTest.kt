package dev.branchlens

import dev.branchlens.git.GitBlobReader
import dev.branchlens.git.GitBranchProvider
import dev.branchlens.git.GitCli
import dev.branchlens.git.GitDiffRunner
import dev.branchlens.git.GitRepositoryLocator
import dev.branchlens.model.BranchLineDifference
import dev.branchlens.model.FileAnalysisResult
import dev.branchlens.model.SkippedReason
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class BranchLensAnalyzerTest {

    private lateinit var repo: Path
    private val cli = GitCli()
    private val timeoutMs = 15_000L

    @Before
    fun setUp() {
        Assume.assumeTrue("git not available", gitAvailable())
        repo = Files.createTempDirectory("branchlens-analyzer-")
        git("init", "-b", "main")
        git("config", "user.email", "test@example.com")
        git("config", "user.name", "Tester")
        git("config", "commit.gpgsign", "false")
        writeFile("foo.kt", "alpha\nbeta\ngamma\n")
        git("add", ".")
        git("commit", "-m", "initial")
    }

    @After
    fun tearDown() {
        if (::repo.isInitialized) {
            try {
                Files.walk(repo).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            } catch (_: Throwable) { /* best effort */ }
        }
    }

    @Test
    fun replacedLineIsDetectedAcrossBranch() = runBlocking {
        git("checkout", "-b", "feature/x")
        writeFile("foo.kt", "alpha\nBETA\ngamma\n")
        git("commit", "-am", "x")
        git("checkout", "main")

        val analyzer = buildAnalyzer()
        val result = analyzer.analyze(
            EditorSnapshot(
                filePath = repo.resolve("foo.kt"),
                text = "alpha\nbeta\ngamma\n",
                documentStamp = 0L,
            ),
        )
        assertTrue("got $result", result is FileAnalysisResult.Computed)
        val computed = result as FileAnalysisResult.Computed
        val perLine = computed.perLineDifferences[2] ?: emptyList()
        assertTrue("expected ReplacedLine on line 2: $perLine", perLine.any { it is BranchLineDifference.ReplacedLine })
    }

    @Test
    fun missingFileInBranchIsReported() = runBlocking {
        git("checkout", "-b", "feature/missing")
        Files.deleteIfExists(repo.resolve("foo.kt"))
        git("commit", "-am", "remove")
        git("checkout", "main")

        val analyzer = buildAnalyzer()
        val result = analyzer.analyze(
            EditorSnapshot(
                filePath = repo.resolve("foo.kt"),
                text = "alpha\nbeta\ngamma\n",
                documentStamp = 0L,
            ),
        )
        val computed = result as FileAnalysisResult.Computed
        assertEquals(1, computed.missingInBranches.size)
        assertEquals("feature/missing", computed.missingInBranches[0].branch.name)
    }

    @Test
    fun filesOverMaxLinesAreSkipped() = runBlocking {
        val analyzer = buildAnalyzer(maxLines = 4)
        val text = (1..10).joinToString("\n") { "line $it" } + "\n"
        val result = analyzer.analyze(
            EditorSnapshot(filePath = repo.resolve("foo.kt"), text = text, documentStamp = 0L),
        )
        assertTrue("got $result", result is FileAnalysisResult.Skipped)
        assertEquals(SkippedReason.TOO_LARGE, (result as FileAnalysisResult.Skipped).reason)
    }

    @Test
    fun noOtherBranchesYieldsSkipped() = runBlocking {
        val analyzer = buildAnalyzer()
        val result = analyzer.analyze(
            EditorSnapshot(filePath = repo.resolve("foo.kt"), text = "alpha\nbeta\ngamma\n", documentStamp = 0L),
        )
        assertTrue(result is FileAnalysisResult.Skipped)
        assertEquals(SkippedReason.NO_OTHER_BRANCHES, (result as FileAnalysisResult.Skipped).reason)
    }

    private fun buildAnalyzer(
        maxLines: Int = 10_000,
        ignoreWhitespace: Boolean = false,
        excluded: List<String> = emptyList(),
    ): BranchLensAnalyzer = BranchLensAnalyzer(
        locator = GitRepositoryLocator(cli, timeoutMs),
        branches = GitBranchProvider(cli, timeoutMs),
        blobs = GitBlobReader(cli, timeoutMs),
        diffRunner = GitDiffRunner(cli, timeoutMs),
        settings = BranchLensAnalyzer.AnalyzerSettings(
            maxLines = maxLines,
            maxFileBytes = 2L * 1024 * 1024,
            maxBranches = 30,
            staleBranchDays = 365 * 10,
            includeStaleBranches = true,
            ignoreWhitespace = ignoreWhitespace,
            excludedBranchPatterns = excluded,
        ),
    )

    private fun writeFile(name: String, contents: String) {
        Files.writeString(
            repo.resolve(name),
            contents,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
    }

    private fun git(vararg args: String) {
        val pb = ProcessBuilder(listOf("git") + args.toList())
        pb.directory(repo.toFile())
        pb.redirectErrorStream(true)
        val p = pb.start()
        val output = p.inputStream.bufferedReader().use { it.readText() }
        val exit = p.waitFor()
        if (exit != 0) throw IllegalStateException("git ${args.joinToString(" ")} failed ($exit): $output")
    }

    private fun gitAvailable(): Boolean = try {
        val p = ProcessBuilder("git", "--version").start()
        p.waitFor() == 0
    } catch (_: IOException) {
        false
    }
}
