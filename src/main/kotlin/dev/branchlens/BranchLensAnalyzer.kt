package dev.branchlens

import dev.branchlens.cache.AnalysisKey
import dev.branchlens.cache.BlameKey
import dev.branchlens.cache.BranchLensCache
import dev.branchlens.diff.HunkMapper
import dev.branchlens.diff.LineDifferenceClassifier
import dev.branchlens.git.BlobResult
import dev.branchlens.git.BranchPairCollapser
import dev.branchlens.git.GitBlameRunner
import dev.branchlens.git.GitBlobReader
import dev.branchlens.git.GitBranchProvider
import dev.branchlens.git.GitDiffRunner
import dev.branchlens.git.GitRepositoryLocator
import dev.branchlens.git.GitPathUtil
import dev.branchlens.git.GitRenameResolver
import dev.branchlens.git.GitHistoryAnalyzer
import dev.branchlens.model.BlameInfo
import dev.branchlens.model.BranchLineDifference
import dev.branchlens.model.FileAnalysisResult
import dev.branchlens.model.GitRepo
import dev.branchlens.model.LocalBranch
import dev.branchlens.model.SkippedReason
import dev.branchlens.model.ChangeLineage
import dev.branchlens.model.differenceIdentity
import dev.branchlens.model.isUncommitted
import dev.branchlens.util.TempFileUtil
import dev.branchlens.util.TextUtil
import dev.branchlens.settings.BranchScopeMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
    private val renameResolver: GitRenameResolver,
    private val diffRunner: GitDiffRunner,
    private val blameRunner: GitBlameRunner,
    private val historyAnalyzer: GitHistoryAnalyzer,
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
        val maxConcurrentGitProcesses: Int = 3,
        val branchScopeMode: BranchScopeMode = BranchScopeMode.RECENT,
        val pinnedBranchNames: Set<String> = emptySet(),
        val includeRemoteTrackingBranches: Boolean = false,
    )

    suspend fun analyze(snapshot: EditorSnapshot): FileAnalysisResult = coroutineScope {
        val normalized = TextUtil.normalizeLineEndings(snapshot.text)
        if (TextUtil.countLines(normalized) > settings.maxLines) {
            return@coroutineScope FileAnalysisResult.Skipped(SkippedReason.TOO_LARGE)
        }
        if (TextUtil.utf8SizeExceeds(normalized, settings.maxFileBytes)) {
            return@coroutineScope FileAnalysisResult.Skipped(SkippedReason.TOO_LARGE)
        }
        if (TextUtil.looksBinary(normalized)) {
            return@coroutineScope FileAnalysisResult.Skipped(SkippedReason.BINARY)
        }

        val repo = locator.locate(snapshot.filePath)
            ?: return@coroutineScope FileAnalysisResult.Skipped(SkippedReason.NOT_IN_REPO)

        val relativePath = GitPathUtil.relativePath(repo.root, snapshot.filePath)
            ?: return@coroutineScope FileAnalysisResult.Skipped(SkippedReason.NOT_IN_REPO)

        analyzeAgainstBranches(repo, relativePath, normalized)
    }

    suspend fun analyzeAgainstBranches(
        repo: GitRepo,
        relativePath: String,
        normalizedText: String,
    ): FileAnalysisResult = coroutineScope {
        val allBranches = branches.listLocal(
            repo.root,
            repo.currentBranch,
            includeRemoteTracking = settings.includeRemoteTrackingBranches,
        )
        val filtered = filterBranches(allBranches)
        if (filtered.isEmpty()) {
            return@coroutineScope FileAnalysisResult.Skipped(SkippedReason.NO_OTHER_BRANCHES)
        }

        val documentHash = TextUtil.stableHash(normalizedText)
        val currentBranch = BranchPairCollapser.collapse(allBranches).firstOrNull { it.isCurrent }
        val branchTipsHash = computeBranchTipsHash(filtered, repo.headCommit, currentBranch)
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

        var currentBlame: Map<Int, BlameInfo> = emptyMap()
        val analyses = try {
            val limiter = Semaphore(settings.maxConcurrentGitProcesses.coerceAtLeast(1))
            val completed = filtered.map { branch ->
                async {
                    limiter.withPermit {
                        coroutineContext.ensureActive()
                        analyzeBranch(repo, relativePath, currentTmp, branch)
                    }
                }
            }.awaitAll()
            val currentRange = currentLineRange(completed.flatMap { it.differences })
            if (currentRange != null && repo.headCommit != null) {
                currentBlame = fetchCurrentBlame(
                    repo.root,
                    repo.headCommit,
                    relativePath,
                    currentTmp,
                    currentRange,
                )
            }
            completed
        } finally {
            TempFileUtil.safeDelete(currentTmp)
        }

        val collected = analyses.flatMap { it.differences }
        val branchContents = analyses.mapNotNull { analysis ->
            analysis.branchContent?.let { analysis.branch.name to it }
        }.toMap()
        val branchBlames = analyses.mapNotNull { analysis ->
            analysis.blame.takeIf { it.isNotEmpty() }?.let { analysis.branch.name to it }
        }.toMap()
        val branchPaths = analyses.mapNotNull { analysis ->
            analysis.branchPath?.takeIf { it != relativePath }?.let { analysis.branch.name to it }
        }.toMap()

        val lineages = resolveLineages(
            repo = repo,
            differences = collected,
            currentBlame = currentBlame,
            branchBlames = branchBlames,
        )
        val commitContainment = resolveCommitContainment(
            repo, filtered, collected, currentBlame, branchBlames,
        )

        val result = LineDifferenceClassifier.aggregate(
            documentText = normalizedText,
            differences = collected,
            branchCount = filtered.size,
            branchContents = branchContents,
            branchPaths = branchPaths,
            branchBlames = branchBlames,
            currentBlame = currentBlame,
            currentBranch = currentBranch,
            analyzedBranches = filtered,
            lineages = lineages,
            commitContainment = commitContainment,
            repoRoot = repo.root,
        )
        cache?.putAnalysis(cacheKey, result)
        result
    }

    private data class BranchAnalysis(
        val branch: LocalBranch,
        val differences: List<BranchLineDifference> = emptyList(),
        val branchContent: String? = null,
        val branchPath: String? = null,
        val blame: Map<Int, BlameInfo> = emptyMap(),
    )

    private suspend fun analyzeBranch(
        repo: GitRepo,
        relativePath: String,
        currentTmp: Path,
        branch: LocalBranch,
    ): BranchAnalysis {
        var branchPath = relativePath
        var blob = readBlob(repo.root, branch.headCommit, relativePath)
            ?: return BranchAnalysis(branch)
        if (blob is BlobResult.NotFound && repo.headCommit != null) {
            val renamedPath = try {
                renameResolver.pathInBranch(
                    repo.root,
                    branch.headCommit,
                    repo.headCommit,
                    relativePath,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                null
            }
            if (renamedPath != null) {
                branchPath = renamedPath
                blob = readBlob(repo.root, branch.headCommit, branchPath)
                    ?: return BranchAnalysis(branch)
            }
        }
        return when (blob) {
            BlobResult.NotFound -> BranchAnalysis(
                branch = branch,
                differences = listOf(BranchLineDifference.FileMissingInBranch(branch, relativePath)),
            )
            BlobResult.Binary -> BranchAnalysis(branch)
            is BlobResult.Text -> {
                val branchTmp = TempFileUtil.writeTempUtf8("branchlens-branch-", ".txt", blob.content)
                try {
                    val diff = diffRunner.diff(repo.root, currentTmp, branchTmp, settings.ignoreWhitespace)
                        ?: return BranchAnalysis(branch)
                    if (diff.hunks.isEmpty()) return BranchAnalysis(branch)
                    val mapped = HunkMapper.map(branch, diff)
                    val blameRange = branchLineRange(mapped)
                    val blame = if (blameRange == null) {
                        emptyMap()
                    } else {
                        fetchBlame(repo.root, branch.headCommit, branchPath, blameRange)
                    }
                    BranchAnalysis(branch, mapped, blob.content, branchPath, blame)
                } finally {
                    TempFileUtil.safeDelete(branchTmp)
                }
            }
        }
    }

    private suspend fun readBlob(repoRoot: Path, branchCommit: String, relativePath: String): BlobResult? =
        try {
            blobs.read(repoRoot, branchCommit, relativePath, settings.maxFileBytes)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }

    private fun branchLineRange(differences: List<BranchLineDifference>): IntRange? {
        val ranges = differences.mapNotNull { difference ->
            when (difference) {
                is BranchLineDifference.ReplacedLine -> difference.branchLine..difference.branchLine
                is BranchLineDifference.ChangedBlock -> difference.branchLines
                is BranchLineDifference.BranchInsertionAfterCurrentLine -> difference.branchLines
                is BranchLineDifference.DeletedInBranch,
                is BranchLineDifference.FileMissingInBranch -> null
            }
        }
        if (ranges.isEmpty()) return null
        return ranges.minOf { it.first }..ranges.maxOf { it.last }
    }

    private fun currentLineRange(differences: List<BranchLineDifference>): IntRange? {
        val ranges = differences.mapNotNull { difference ->
            when (difference) {
                is BranchLineDifference.ReplacedLine -> difference.currentLine..difference.currentLine
                is BranchLineDifference.ChangedBlock -> difference.currentLines
                is BranchLineDifference.DeletedInBranch -> difference.currentLine..difference.currentLine
                is BranchLineDifference.BranchInsertionAfterCurrentLine ->
                    difference.anchorCurrentLine..difference.anchorCurrentLine
                is BranchLineDifference.FileMissingInBranch -> null
            }
        }
        if (ranges.isEmpty()) return null
        return ranges.minOf { it.first }..ranges.maxOf { it.last }
    }

    private suspend fun fetchCurrentBlame(
        repoRoot: Path,
        headCommit: String,
        relativePath: String,
        contentsPath: Path,
        range: IntRange,
    ): Map<Int, BlameInfo> = try {
        blameRunner.blameContents(
            repoRoot = repoRoot,
            branchCommit = headCommit,
            relativePath = relativePath,
            contentsPath = contentsPath,
            range = range,
            useMoveAware = settings.useMoveAwareBlame,
            useCopyAware = settings.useCopyAwareBlame,
        )
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        emptyMap()
    }

    private suspend fun resolveLineages(
        repo: GitRepo,
        differences: List<BranchLineDifference>,
        currentBlame: Map<Int, BlameInfo>,
        branchBlames: Map<String, Map<Int, BlameInfo>>,
    ): Map<String, ChangeLineage> {
        val currentHead = repo.headCommit ?: return emptyMap()
        val result = LinkedHashMap<String, ChangeLineage>()
        for (difference in differences) {
            val currentLine = when (difference) {
                is BranchLineDifference.ReplacedLine -> difference.currentLine
                is BranchLineDifference.ChangedBlock -> difference.currentLines.first
                is BranchLineDifference.DeletedInBranch -> difference.currentLine
                is BranchLineDifference.BranchInsertionAfterCurrentLine -> difference.anchorCurrentLine
                is BranchLineDifference.FileMissingInBranch -> 1
            }
            val branchLine = when (difference) {
                is BranchLineDifference.ReplacedLine -> difference.branchLine
                is BranchLineDifference.ChangedBlock -> difference.branchLines?.first
                is BranchLineDifference.BranchInsertionAfterCurrentLine -> difference.branchLines.first
                is BranchLineDifference.DeletedInBranch,
                is BranchLineDifference.FileMissingInBranch -> null
            }
            val lineage = try {
                historyAnalyzer.classify(
                    repo.root,
                    currentHead,
                    difference,
                    currentBlame[currentLine],
                    branchLine?.let { branchBlames[difference.branch.name]?.get(it) },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                ChangeLineage.UNKNOWN
            }
            result[differenceIdentity(difference)] = lineage
        }
        return result
    }

    private suspend fun resolveCommitContainment(
        repo: GitRepo,
        branches: List<LocalBranch>,
        differences: List<BranchLineDifference>,
        currentBlame: Map<Int, BlameInfo>,
        branchBlames: Map<String, Map<Int, BlameInfo>>,
    ): Map<String, Set<String>> {
        val relevantBlame = differences.flatMap { difference ->
            val currentLines: IntRange? = when (difference) {
                is BranchLineDifference.ReplacedLine -> difference.currentLine..difference.currentLine
                is BranchLineDifference.ChangedBlock -> difference.currentLines
                is BranchLineDifference.DeletedInBranch -> difference.currentLine..difference.currentLine
                is BranchLineDifference.BranchInsertionAfterCurrentLine ->
                    difference.anchorCurrentLine..difference.anchorCurrentLine
                is BranchLineDifference.FileMissingInBranch -> null
            }
            val branchLines: IntRange? = when (difference) {
                is BranchLineDifference.ReplacedLine -> difference.branchLine..difference.branchLine
                is BranchLineDifference.ChangedBlock -> difference.branchLines
                is BranchLineDifference.BranchInsertionAfterCurrentLine -> difference.branchLines
                is BranchLineDifference.DeletedInBranch,
                is BranchLineDifference.FileMissingInBranch -> null
            }
            buildList {
                currentLines?.forEach { line -> currentBlame[line]?.let(::add) }
                branchLines?.forEach { line -> branchBlames[difference.branch.name]?.get(line)?.let(::add) }
            }
        }
        val commits = relevantBlame
            .filterNot { it.isUncommitted }
            .map { it.commitHash }
            .toSet()
        return commits.associateWith { commit ->
            branches.mapNotNullTo(linkedSetOf()) { branch ->
                val contained = try {
                    historyAnalyzer.isContained(repo.root, commit, branch.headCommit)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    false
                }
                branch.name.takeIf { contained }
            }
        }
    }

    private suspend fun fetchBlame(
        repoRoot: Path,
        branchCommit: String,
        relativePath: String,
        range: IntRange,
    ): Map<Int, BlameInfo> {
        val key = BlameKey(
            repoRoot = repoRoot,
            branchCommit = branchCommit,
            relativePath = relativePath,
            moveAware = settings.useMoveAwareBlame,
            copyAware = settings.useCopyAwareBlame,
            rangeStart = range.first,
            rangeEnd = range.last,
        )
        cache?.getBlame(key)?.let { return it }
        val fresh = try {
            blameRunner.blame(
                repoRoot = repoRoot,
                branchCommit = branchCommit,
                relativePath = relativePath,
                range = range,
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

        val eligible = BranchPairCollapser.collapse(branches).asSequence()
            .filter { !it.isCurrent }
            .filter { branch -> settings.excludedBranchPatterns.none { matchesGlob(branch.name, it) } }
            .filter { branch ->
                settings.branchScopeMode != BranchScopeMode.PINNED ||
                    settings.pinnedBranchNames.any { pattern ->
                        branch.name == pattern || matchesGlob(branch.name, pattern)
                    }
            }
            .filter { branch ->
                if (settings.includeStaleBranches || settings.branchScopeMode != BranchScopeMode.RECENT) {
                    true
                } else {
                    val committed = branch.committerDateEpochSeconds ?: return@filter true
                    (now - committed) <= staleThreshold
                }
            }
            .toList()
        return eligible.take(settings.maxBranches.coerceAtLeast(0))
            .toList()
    }

    private fun computeBranchTipsHash(
        filtered: List<LocalBranch>,
        currentHead: String?,
        currentBranch: LocalBranch?,
    ): String =
        TextUtil.stableHash(
            buildString {
                append("HEAD=").append(currentHead.orEmpty())
                currentBranch?.let {
                    append("|UPSTREAM=").append(it.upstreamName.orEmpty())
                    append(':').append(it.commitsAheadOfUpstream)
                    append(':').append(it.commitsBehindUpstream)
                }
                filtered.forEach { append('|').append(it.name).append('=').append(it.headCommit) }
            },
        )

    private fun computeSettingsHash(): String = TextUtil.stableHash(
        buildString {
            append(settings.maxBranches).append('|')
            append(settings.maxFileBytes).append('|')
            append(settings.staleBranchDays).append('|')
            append(settings.includeStaleBranches).append('|')
            append(settings.ignoreWhitespace).append('|')
            append(settings.useMoveAwareBlame).append('|')
            append(settings.useCopyAwareBlame).append('|')
            append(settings.maxConcurrentGitProcesses).append('|')
            append(settings.branchScopeMode).append('|')
            append(settings.pinnedBranchNames.sorted().joinToString(",")).append('|')
            append(settings.includeRemoteTrackingBranches).append('|')
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
