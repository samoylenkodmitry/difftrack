package dev.branchlens

import dev.branchlens.cache.AnalysisKey
import dev.branchlens.cache.BlameKey
import dev.branchlens.cache.BranchLensCache
import dev.branchlens.diff.HunkMapper
import dev.branchlens.diff.LineDifferenceClassifier
import dev.branchlens.git.BlobResult
import dev.branchlens.git.GitBlameRunner
import dev.branchlens.git.GitBlobReader
import dev.branchlens.git.GitBranchProvider
import dev.branchlens.git.GitDiffRunner
import dev.branchlens.git.GitRepositoryLocator
import dev.branchlens.model.BlameInfo
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
    private val blameRunner: GitBlameRunner,
    private val settings: AnalyzerSettings,
    private val cache: BranchLensCache? = null,
) {
    data class AnalyzerSettings(
        val maxLines: Int,
        val maxFileBytes: Long,
        val maxBranches: Int,
        val staleBranchDays: Int,
        val includeStaleBranches: Boolean,
        val ignoreWhitespace: Boolean,
        val useMoveAwareBlame: Boolean,
        val useCopyAwareBlame: Boolean,
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

        val documentHash = TextUtil.stableHash(normalizedText)
        val branchTipsHash = computeBranchTipsHash(filtered)
        val settingsHash = computeSettingsHash()

        val cacheKey = AnalysisKey(
            repoRoot = repo.root,
            relativePath = relativePath,
            documentHash = documentHash,
            branchTipsHash = branchTipsHash,
            settingsHash = settingsHash,
        )
        cache?.getAnalysis(cacheKey)?.let { return@coroutineScope it }

        val currentTmp = TempFileUtil.writeTempUtf8("branchlens-current-", ".txt", normalizedText)

        val collected = mutableListOf<BranchLineDifference>()
        val branchContents = mutableMapOf<String, String>()
        val branchBlames = mutableMapOf<String, Map<Int, BlameInfo>>()

        try {
            for (branch in filtered) {
                coroutineContext.ensureActive()
                analyzeBranch(repo, relativePath, currentTmp, branch, collected, branchContents, branchBlames)
            }
        } finally {
            TempFileUtil.safeDelete(currentTmp)
        }

        val result = LineDifferenceClassifier.aggregate(
            documentText = normalizedText,
            differences = collected,
            branchCount = filtered.size,
            branchContents = branchContents,
            branchBlames = branchBlames,
        )
        cache?.putAnalysis(cacheKey, result)
        result
    }

    private suspend fun analyzeBranch(
        repo: GitRepo,
        relativePath: String,
        currentTmp: Path,
        branch: LocalBranch,
        collected: MutableList<BranchLineDifference>,
        branchContents: MutableMap<String, String>,
        branchBlames: MutableMap<String, Map<Int, BlameInfo>>,
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
            is BlobResult.Text -> {
                branchContents[branch.name] = blob.content
                val branchTmp = TempFileUtil.writeTempUtf8("branchlens-branch-", ".txt", blob.content)
                try {
                    val diff = diffRunner.diff(repo.root, currentTmp, branchTmp, settings.ignoreWhitespace)
                        ?: return
                    if (diff.hunks.isEmpty()) return
                    collected += HunkMapper.map(branch, diff)

                    val blame = fetchBlame(repo.root, branch.headCommit, relativePath)
                    if (blame.isNotEmpty()) branchBlames[branch.name] = blame
                } finally {
                    TempFileUtil.safeDelete(branchTmp)
                }
            }
        }
    }

    private suspend fun fetchBlame(
        repoRoot: Path,
        branchCommit: String,
        relativePath: String,
    ): Map<Int, BlameInfo> {
        val key = BlameKey(
            repoRoot = repoRoot,
            branchCommit = branchCommit,
            relativePath = relativePath,
            moveAware = settings.useMoveAwareBlame,
            copyAware = settings.useCopyAwareBlame,
        )
        cache?.getBlame(key)?.let { return it }
        val fresh = try {
            blameRunner.blame(
                repoRoot = repoRoot,
                branchCommit = branchCommit,
                relativePath = relativePath,
                range = null,
                useMoveAware = settings.useMoveAwareBlame,
                useCopyAware = settings.useCopyAwareBlame,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            emptyMap()
        }
        if (fresh.isNotEmpty()) cache?.putBlame(key, fresh)
        return fresh
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

    private fun computeBranchTipsHash(filtered: List<LocalBranch>): String =
        TextUtil.stableHash(filtered.joinToString("|") { "${it.name}=${it.headCommit}" })

    private fun computeSettingsHash(): String = TextUtil.stableHash(
        buildString {
            append(settings.maxBranches).append('|')
            append(settings.staleBranchDays).append('|')
            append(settings.includeStaleBranches).append('|')
            append(settings.ignoreWhitespace).append('|')
            append(settings.useMoveAwareBlame).append('|')
            append(settings.useCopyAwareBlame).append('|')
            append(settings.excludedBranchPatterns.joinToString(","))
        },
    )

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
