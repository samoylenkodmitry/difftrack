package dev.branchlens

import dev.branchlens.diff.HunkMapper
import dev.branchlens.diff.LineDifferenceClassifier
import dev.branchlens.git.BlobResult
import dev.branchlens.git.GitBlobReader
import dev.branchlens.git.GitBranchProvider
import dev.branchlens.git.GitDiffRunner
import dev.branchlens.git.GitRepositoryLocator
import dev.branchlens.model.BranchLineDifference
import dev.branchlens.model.FileAnalysisResult
import dev.branchlens.model.GitRepo
import dev.branchlens.model.LocalBranch
import dev.branchlens.model.SkippedReason
import dev.branchlens.util.TempFileUtil
import dev.branchlens.util.TextUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import java.nio.file.Path
import kotlin.coroutines.coroutineContext

/**
 * Captured editor contents to analyze. [filePath] is absolute; the repo root is discovered
 * by the analyzer's [GitRepositoryLocator].
 */
data class EditorSnapshot(
    val filePath: Path,
    val text: String,
    val documentStamp: Long,
)

class BranchLensAnalyzer(
    private val locator: GitRepositoryLocator,
    private val branches: GitBranchProvider,
    private val blobs: GitBlobReader,
    private val diffRunner: GitDiffRunner,
    private val settings: AnalyzerSettings,
) {
    data class AnalyzerSettings(
        val maxLines: Int,
        val maxFileBytes: Long,
        val maxBranches: Int,
        val staleBranchDays: Int,
        val includeStaleBranches: Boolean,
        val ignoreWhitespace: Boolean,
        val excludedBranchPatterns: List<String>,
    )

    suspend fun analyze(snapshot: EditorSnapshot): FileAnalysisResult = coroutineScope {
        val normalized = TextUtil.normalizeLineEndings(snapshot.text)
        if (TextUtil.countLines(normalized) > settings.maxLines) {
            return@coroutineScope FileAnalysisResult.Skipped(SkippedReason.TOO_LARGE)
        }
        if (TextUtil.looksBinary(normalized)) {
            return@coroutineScope FileAnalysisResult.Skipped(SkippedReason.BINARY)
        }

        val repo = locator.locate(snapshot.filePath)
            ?: return@coroutineScope FileAnalysisResult.Skipped(SkippedReason.NOT_IN_REPO)

        val relativePath = computeRelativePath(repo.root, snapshot.filePath)

        analyzeAgainstBranches(repo, relativePath, normalized)
    }

    suspend fun analyzeAgainstBranches(
        repo: GitRepo,
        relativePath: String,
        normalizedText: String,
    ): FileAnalysisResult = coroutineScope {
        val allBranches = branches.listLocal(repo.root, repo.currentBranch)
        val filtered = filterBranches(allBranches)
        if (filtered.isEmpty()) {
            return@coroutineScope FileAnalysisResult.Skipped(SkippedReason.NO_OTHER_BRANCHES)
        }

        val currentTmp = TempFileUtil.writeTempUtf8("branchlens-current-", ".txt", normalizedText)

        val collected = mutableListOf<BranchLineDifference>()
        try {
            for (branch in filtered) {
                coroutineContext.ensureActive()
                analyzeBranch(repo, relativePath, currentTmp, branch, collected)
            }
        } finally {
            TempFileUtil.safeDelete(currentTmp)
        }

        LineDifferenceClassifier.aggregate(normalizedText, collected, filtered.size)
    }

    private suspend fun analyzeBranch(
        repo: GitRepo,
        relativePath: String,
        currentTmp: Path,
        branch: LocalBranch,
        collected: MutableList<BranchLineDifference>,
    ) {
        val blob = try {
            blobs.read(repo.root, branch.headCommit, relativePath, settings.maxFileBytes)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            return
        }
        when (blob) {
            BlobResult.NotFound -> {
                collected += BranchLineDifference.FileMissingInBranch(branch, relativePath)
            }
            BlobResult.Binary -> { /* skip */ }
            is BlobResult.Error -> { /* skip */ }
            is BlobResult.Text -> {
                val branchTmp = TempFileUtil.writeTempUtf8("branchlens-branch-", ".txt", blob.content)
                try {
                    val diff = diffRunner.diff(repo.root, currentTmp, branchTmp, settings.ignoreWhitespace)
                        ?: return
                    if (diff.hunks.isEmpty()) return
                    collected += HunkMapper.map(branch, diff)
                } finally {
                    TempFileUtil.safeDelete(branchTmp)
                }
            }
        }
    }

    private fun filterBranches(branches: List<LocalBranch>): List<LocalBranch> {
        val now = System.currentTimeMillis() / 1000L
        val staleThreshold = settings.staleBranchDays.toLong() * 24L * 3600L

        return branches.asSequence()
            .filter { !it.isCurrent }
            .filter { branch -> settings.excludedBranchPatterns.none { matchesGlob(branch.name, it) } }
            .filter { branch ->
                if (settings.includeStaleBranches) {
                    true
                } else {
                    val committed = branch.committerDateEpochSeconds ?: return@filter true
                    (now - committed) <= staleThreshold
                }
            }
            .take(settings.maxBranches.coerceAtLeast(0))
            .toList()
    }

    private fun computeRelativePath(root: Path, file: Path): String =
        try {
            root.relativize(file).toString().replace('\\', '/')
        } catch (_: Throwable) {
            file.fileName.toString()
        }

    private fun matchesGlob(name: String, pattern: String): Boolean {
        if (pattern.isBlank()) return false
        val regex = StringBuilder("^")
        for (ch in pattern) {
            when (ch) {
                '*' -> regex.append(".*")
                '?' -> regex.append('.')
                '.', '(', ')', '[', ']', '+', '^', '$', '{', '}', '|', '\\' ->
                    regex.append('\\').append(ch)
                else -> regex.append(ch)
            }
        }
        regex.append('$')
        return Regex(regex.toString()).matches(name)
    }
}
