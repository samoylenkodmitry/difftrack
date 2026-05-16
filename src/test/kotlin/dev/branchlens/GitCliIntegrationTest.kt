package dev.branchlens

import dev.branchlens.git.BlobResult
import dev.branchlens.git.GitBlobReader
import dev.branchlens.git.GitBranchProvider
import dev.branchlens.git.GitCli
import dev.branchlens.git.GitDiffRunner
import dev.branchlens.git.GitRepositoryLocator
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Integration tests against the real `git` binary. Skipped automatically on hosts where
 * git isn't on PATH (so test runs stay green in restricted CI sandboxes).
 */
class GitCliIntegrationTest {

    private lateinit var repo: Path
    private val cli = GitCli()
    private val timeoutMs = 10_000L

    @Before
    fun setUp() {
        Assume.assumeTrue("git not available", gitAvailable())
        repo = Files.createTempDirectory("branchlens-it-")
        runGit("init", "-b", "main")
        runGit("config", "user.email", "test@example.com")
        runGit("config", "user.name", "Tester")
        runGit("config", "commit.gpgsign", "false")
        writeFile("foo.kt", "line1\nline2\nline3\n")
        runGit("add", ".")
        runGit("commit", "-m", "initial")
    }

    @After
    fun tearDown() {
        if (::repo.isInitialized) {
            try {
                deleteRecursively(repo)
            } catch (_: Throwable) { /* best effort */ }
        }
    }

    @Test
    fun locatesRepoAndCurrentBranch() = runBlocking {
        val locator = GitRepositoryLocator(cli, timeoutMs)
        val gitRepo = locator.locate(repo.resolve("foo.kt"))
        assertNotNull(gitRepo)
        assertEquals("main", gitRepo!!.currentBranch)
        assertNotNull(gitRepo.headCommit)
    }

    @Test
    fun listsLocalBranchesAndExcludesCurrent() = runBlocking {
        runGit("checkout", "-b", "feature/x")
        writeFile("foo.kt", "line1\nLINE2\nline3\n")
        runGit("commit", "-am", "tweak")
        runGit("checkout", "main")

        val provider = GitBranchProvider(cli, timeoutMs)
        val branches = provider.listLocal(repo, "main")
        val names = branches.map { it.name }
        assertTrue("expected main and feature/x: $names", names.containsAll(listOf("main", "feature/x")))
        assertEquals("main", branches.first { it.isCurrent }.name)
    }

    @Test
    fun blobReaderReturnsContentForExistingFileAndNotFoundOtherwise() = runBlocking {
        val locator = GitRepositoryLocator(cli, timeoutMs)
        val gitRepo = locator.locate(repo.resolve("foo.kt"))!!
        val reader = GitBlobReader(cli, timeoutMs)
        val blob = reader.read(repo, gitRepo.headCommit!!, "foo.kt", 2L * 1024 * 1024)
        assertTrue(blob is BlobResult.Text)
        assertTrue((blob as BlobResult.Text).content.startsWith("line1"))

        val missing = reader.read(repo, gitRepo.headCommit, "does-not-exist.kt", 2L * 1024 * 1024)
        assertTrue("got $missing", missing is BlobResult.NotFound)
    }

    @Test
    fun diffBetweenBranchesProducesHunks() = runBlocking {
        runGit("checkout", "-b", "feature/y")
        writeFile("foo.kt", "line1\nCHANGED\nline3\n")
        runGit("commit", "-am", "y")
        runGit("checkout", "main")

        val locator = GitRepositoryLocator(cli, timeoutMs)
        val branches = GitBranchProvider(cli, timeoutMs)
        val blobs = GitBlobReader(cli, timeoutMs)
        val diff = GitDiffRunner(cli, timeoutMs)

        val gitRepo = locator.locate(repo.resolve("foo.kt"))!!
        val all = branches.listLocal(repo, gitRepo.currentBranch)
        val y = all.first { it.name == "feature/y" }
        val branchBlob = blobs.read(repo, y.headCommit, "foo.kt", 2L * 1024 * 1024) as BlobResult.Text

        val current = Files.createTempFile("cli-it-current-", ".txt").also {
            Files.writeString(it, "line1\nline2\nline3\n")
        }
        val branch = Files.createTempFile("cli-it-branch-", ".txt").also {
            Files.writeString(it, branchBlob.content)
        }
        try {
            val result = diff.diff(repo, current, branch, ignoreWhitespace = false)
            assertNotNull(result)
            assertTrue("expected at least one hunk", result!!.hunks.isNotEmpty())
        } finally {
            Files.deleteIfExists(current)
            Files.deleteIfExists(branch)
        }
    }

    private fun writeFile(name: String, contents: String) {
        Files.writeString(
            repo.resolve(name),
            contents,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
    }

    private fun runGit(vararg args: String) {
        val pb = ProcessBuilder(listOf("git") + args.toList())
        pb.directory(repo.toFile())
        pb.redirectErrorStream(true)
        val p = pb.start()
        val output = p.inputStream.bufferedReader().use { it.readText() }
        val exit = p.waitFor()
        if (exit != 0) throw IllegalStateException("git ${args.joinToString(" ")} failed ($exit): $output")
    }

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    private fun gitAvailable(): Boolean = try {
        val p = ProcessBuilder("git", "--version").start()
        p.waitFor() == 0
    } catch (_: IOException) {
        false
    }
}

