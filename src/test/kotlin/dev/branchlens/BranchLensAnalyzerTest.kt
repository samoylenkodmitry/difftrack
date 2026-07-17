package dev.branchlens

import dev.branchlens.git.GitBlameRunner
import dev.branchlens.git.GitBlobReader
import dev.branchlens.git.GitBranchProvider
import dev.branchlens.git.GitCli
import dev.branchlens.git.GitDiffRunner
import dev.branchlens.git.GitRepositoryLocator
import dev.branchlens.git.GitHistoryAnalyzer
import dev.branchlens.git.GitRenameResolver
import dev.branchlens.model.BranchLineDifference
import dev.branchlens.model.ChangeLineage
import dev.branchlens.model.FileAnalysisResult
import dev.branchlens.model.SkippedReason
import dev.branchlens.settings.BranchScopeMode
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
    fun filesOverMaxBytesAreSkipped() = runBlocking {
        val analyzer = buildAnalyzer(maxFileBytes = 8)
        val result = analyzer.analyze(
            EditorSnapshot(filePath = repo.resolve("foo.kt"), text = "alpha\nbeta\n", documentStamp = 0L),
        )
        assertTrue(result is FileAnalysisResult.Skipped)
        assertEquals(SkippedReason.TOO_LARGE, (result as FileAnalysisResult.Skipped).reason)
    }

    @Test
    fun blameIsPopulatedForBranchSideLines() = runBlocking {
        git("checkout", "-b", "feature/blame")
        writeFile("foo.kt", "alpha\nBETA-MODIFIED\ngamma\n")
        // Different committer name so the blame attribution is obvious.
        val authoredAt = "1700000000"
        ProcessBuilder("git", "-c", "user.email=alice@example.com", "-c", "user.name=Alice", "commit", "-am", "tweak beta")
            .directory(repo.toFile())
            .redirectErrorStream(true)
            .also { pb ->
                val env = pb.environment()
                env["GIT_AUTHOR_NAME"] = "Alice"
                env["GIT_AUTHOR_EMAIL"] = "alice@example.com"
                env["GIT_AUTHOR_DATE"] = "$authoredAt +0000"
                env["GIT_COMMITTER_NAME"] = "Alice"
                env["GIT_COMMITTER_EMAIL"] = "alice@example.com"
                env["GIT_COMMITTER_DATE"] = "$authoredAt +0000"
            }
            .start()
            .also { p ->
                p.inputStream.bufferedReader().use { it.readText() }
                assertEquals(0, p.waitFor())
            }
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
        val blame = computed.branchBlames["feature/blame"]
        assertTrue("expected blame for feature/blame, got ${computed.branchBlames.keys}", blame != null)
        val line2 = blame!![2]
        assertTrue("expected blame for line 2, got $blame", line2 != null)
        assertEquals("Alice", line2!!.author)
        assertEquals(1700000000L, line2.authorTimeEpochSeconds)
    }

    @Test
    fun linePresentOnlyInCurrentUsesCurrentSnapshotBlame() = runBlocking {
        git("branch", "feature/without-new-line")

        val analyzer = buildAnalyzer()
        val result = analyzer.analyze(
            EditorSnapshot(
                filePath = repo.resolve("foo.kt"),
                text = "alpha\nnew working tree line\nbeta\ngamma\n",
                documentStamp = 1L,
            ),
        ) as FileAnalysisResult.Computed

        val diff = result.perLineDifferences[2]
            ?.filterIsInstance<dev.branchlens.model.BranchLineDifference.DeletedInBranch>()
            ?.single()
        assertTrue("expected a current-only line", diff != null)
        val attribution = result.summaryForLine(2)?.blameFor(diff!!)
        assertTrue("expected current-side blame", attribution != null)
        assertTrue("expected an uncommitted pseudo-commit", attribution!!.commitHash.all { it == '0' })
        assertEquals(ChangeLineage.UNCOMMITTED_CURRENT, result.summaryForLine(2)?.lineageFor(diff))
    }

    @Test
    fun committedCurrentOnlyLineIsClassifiedFromMergeBaseAndPropagationIsCounted() = runBlocking {
        git("branch", "feature/before-addition")
        writeFile("foo.kt", "alpha\nnew committed line\nbeta\ngamma\n")
        git("add", "foo.kt")
        git("commit", "-m", "add current line")
        val additionCommit = gitOutput("rev-parse", "HEAD")

        val result = buildAnalyzer().analyze(
            EditorSnapshot(repo.resolve("foo.kt"), "alpha\nnew committed line\nbeta\ngamma\n", 2L),
        ) as FileAnalysisResult.Computed

        val diff = result.perLineDifferences[2]
            ?.filterIsInstance<BranchLineDifference.DeletedInBranch>()
            ?.single()
        assertTrue("expected a current-only line", diff != null)
        assertEquals(ChangeLineage.ADDED_ON_CURRENT, result.summaryForLine(2)?.lineageFor(diff!!))
        assertEquals(emptySet<String>(), result.commitContainment[additionCommit])
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

    @Test
    fun pinnedBranchPatternsRestrictAnalysis() = runBlocking {
        git("checkout", "-b", "release/one")
        writeFile("foo.kt", "alpha\nRELEASE\ngamma\n")
        git("commit", "-am", "release edit")
        git("checkout", "main")
        git("checkout", "-b", "feature/other")
        writeFile("foo.kt", "alpha\nFEATURE\ngamma\n")
        git("commit", "-am", "feature edit")
        git("checkout", "main")

        val result = buildAnalyzer(
            branchScopeMode = BranchScopeMode.PINNED,
            pinnedBranches = setOf("release/*"),
        ).analyze(EditorSnapshot(repo.resolve("foo.kt"), "alpha\nbeta\ngamma\n", 0L))

        val computed = result as FileAnalysisResult.Computed
        assertEquals(1, computed.branchCount)
        assertEquals(setOf("release/one"), computed.perLineDifferences.values.flatten().map { it.branch.name }.toSet())
    }

    @Test
    fun renamedBranchSideFileIsResolvedBeforeDiffing() = runBlocking {
        git("checkout", "-b", "feature/renamed")
        git("mv", "foo.kt", "old-name.kt")
        writeFile("old-name.kt", "alpha\nBETA\ngamma\n")
        git("commit", "-am", "rename and edit")
        git("checkout", "main")

        val result = buildAnalyzer().analyze(
            EditorSnapshot(repo.resolve("foo.kt"), "alpha\nbeta\ngamma\n", 0L),
        ) as FileAnalysisResult.Computed

        assertEquals("old-name.kt", result.branchPaths["feature/renamed"])
        assertTrue(result.perLineDifferences[2].orEmpty().any { it.branch.name == "feature/renamed" })
    }

    private fun buildAnalyzer(
        maxLines: Int = 10_000,
        maxFileBytes: Long = 2L * 1024 * 1024,
        ignoreWhitespace: Boolean = false,
        excluded: List<String> = emptyList(),
        branchScopeMode: BranchScopeMode = BranchScopeMode.RECENT,
        pinnedBranches: Set<String> = emptySet(),
    ): BranchLensAnalyzer = BranchLensAnalyzer(
        locator = GitRepositoryLocator(cli, timeoutMs),
        branches = GitBranchProvider(cli, timeoutMs),
        blobs = GitBlobReader(cli, timeoutMs),
        renameResolver = GitRenameResolver(cli, timeoutMs),
        diffRunner = GitDiffRunner(cli, timeoutMs),
        blameRunner = GitBlameRunner(cli, timeoutMs),
        historyAnalyzer = GitHistoryAnalyzer(cli, timeoutMs),
        settings = BranchLensAnalyzer.AnalyzerSettings(
            maxLines = maxLines,
            maxFileBytes = maxFileBytes,
            maxBranches = 30,
            staleBranchDays = 365 * 10,
            includeStaleBranches = true,
            ignoreWhitespace = ignoreWhitespace,
            useMoveAwareBlame = true,
            useCopyAwareBlame = false,
            excludedBranchPatterns = excluded,
            maxConcurrentGitProcesses = 3,
            branchScopeMode = branchScopeMode,
            pinnedBranchNames = pinnedBranches,
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

    private fun gitOutput(vararg args: String): String {
        val pb = ProcessBuilder(listOf("git") + args.toList())
        pb.directory(repo.toFile())
        pb.redirectErrorStream(true)
        val p = pb.start()
        val output = p.inputStream.bufferedReader().use { it.readText() }
        val exit = p.waitFor()
        if (exit != 0) throw IllegalStateException("git ${args.joinToString(" ")} failed ($exit): $output")
        return output.trim()
    }

    private fun gitAvailable(): Boolean = try {
        val p = ProcessBuilder("git", "--version").start()
        p.waitFor() == 0
    } catch (_: IOException) {
        false
    }
}
