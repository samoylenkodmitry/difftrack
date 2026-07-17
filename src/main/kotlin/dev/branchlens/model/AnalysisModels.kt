package dev.branchlens.model

import java.nio.file.Path

data class GitRepo(
    val root: Path,
    val currentBranch: String?,
    val headCommit: String?,
)

data class LocalBranch(
    val name: String,
    val headCommit: String,
    val committerDateEpochSeconds: Long?,
    val authorName: String?,
    val isCurrent: Boolean,
    val isRemoteTracking: Boolean = false,
    val upstreamName: String? = null,
    val pairedRemoteName: String? = null,
    val commitsAheadOfUpstream: Int = 0,
    val commitsBehindUpstream: Int = 0,
)

val LocalBranch.displayName: String
    get() = when {
        pairedRemoteName != null -> "$name ↔ $pairedRemoteName"
        upstreamName != null && commitsAheadOfUpstream > 0 && commitsBehindUpstream > 0 ->
            "$name ↔ $upstreamName — diverged ↑$commitsAheadOfUpstream ↓$commitsBehindUpstream"
        upstreamName != null && commitsAheadOfUpstream > 0 ->
            "$name ↔ $upstreamName — ahead ↑$commitsAheadOfUpstream"
        upstreamName != null && commitsBehindUpstream > 0 ->
            "$name ↔ $upstreamName — behind ↓$commitsBehindUpstream"
        else -> name
    }

val LocalBranch.trackingStatus: String?
    get() = when {
        upstreamName == null -> null
        commitsAheadOfUpstream > 0 && commitsBehindUpstream > 0 ->
            "$name vs $upstreamName: diverged ↑$commitsAheadOfUpstream ↓$commitsBehindUpstream"
        commitsAheadOfUpstream > 0 -> "$name vs $upstreamName: ahead ↑$commitsAheadOfUpstream"
        commitsBehindUpstream > 0 -> "$name vs $upstreamName: behind ↓$commitsBehindUpstream"
        else -> "$name ↔ $upstreamName: up to date"
    }

data class BlameInfo(
    val commitHash: String,
    val author: String?,
    val authorMail: String?,
    val authorTimeEpochSeconds: Long?,
    val authorTimezone: String?,
    val summary: String?,
)

val BlameInfo.isUncommitted: Boolean
    get() = commitHash.all { it == '0' } ||
        author == "Not Committed Yet" || author == "External file (--contents)"

enum class Confidence { HIGH, MEDIUM, BLOCK_ONLY }

enum class ChangeLineage(val label: String) {
    ADDED_ON_CURRENT("added on current"),
    REMOVED_ON_BRANCH("removed on branch"),
    ADDED_ON_BRANCH("added on branch"),
    REMOVED_ON_CURRENT("removed on current"),
    MODIFIED_ON_CURRENT("modified on current"),
    MODIFIED_ON_BRANCH("modified on branch"),
    MODIFIED_INDEPENDENTLY("modified independently"),
    UNCOMMITTED_CURRENT("uncommitted on current"),
    DIFFERED_BEFORE_DIVERGENCE("already different at divergence"),
    UNKNOWN("history unclear"),
}

sealed class BranchLineDifference {
    abstract val branch: LocalBranch

    data class ReplacedLine(
        override val branch: LocalBranch,
        val currentLine: Int,
        val branchLine: Int,
        val currentText: String,
        val branchText: String,
        val confidence: Confidence,
    ) : BranchLineDifference()

    data class ChangedBlock(
        override val branch: LocalBranch,
        val currentLines: IntRange,
        val branchLines: IntRange?,
        val currentText: List<String>,
        val branchText: List<String>,
        val confidence: Confidence,
    ) : BranchLineDifference()

    data class DeletedInBranch(
        override val branch: LocalBranch,
        val currentLine: Int,
        val currentText: String,
    ) : BranchLineDifference()

    data class BranchInsertionAfterCurrentLine(
        override val branch: LocalBranch,
        val anchorCurrentLine: Int,
        val branchLines: IntRange,
        val branchText: List<String>,
        val beforeFirstLine: Boolean = false,
    ) : BranchLineDifference()

    data class FileMissingInBranch(
        override val branch: LocalBranch,
        val relativePath: String,
    ) : BranchLineDifference()
}

enum class SkippedReason {
    TOO_LARGE,
    BINARY,
    NOT_IN_REPO,
    NO_OTHER_BRANCHES,
}

sealed class FileAnalysisResult {
    object NotComputed : FileAnalysisResult()

    data class Skipped(val reason: SkippedReason, val detail: String? = null) : FileAnalysisResult()

    data class Computed(
        val totalLines: Int,
        val perLineDifferences: Map<Int, List<BranchLineDifference>>,
        val insertionsAfter: Map<Int, List<BranchLineDifference.BranchInsertionAfterCurrentLine>>,
        val missingInBranches: List<BranchLineDifference.FileMissingInBranch>,
        val branchContents: Map<String, String>,
        val branchPaths: Map<String, String>,
        val branchBlames: Map<String, Map<Int, BlameInfo>>,
        val currentBlame: Map<Int, BlameInfo>,
        val computedAtNanos: Long,
        val documentHash: String,
        val branchCount: Int,
        val currentBranch: LocalBranch? = null,
        val analyzedBranches: List<LocalBranch> = emptyList(),
        val lineages: Map<String, ChangeLineage> = emptyMap(),
        val commitContainment: Map<String, Set<String>> = emptyMap(),
        val repoRoot: Path? = null,
    ) : FileAnalysisResult() {
        fun summaryForLine(line: Int): LineSummary? {
            val diffs = perLineDifferences[line] ?: emptyList()
            val inserts = insertionsAfter[line] ?: emptyList()
            if (diffs.isEmpty() && inserts.isEmpty()) return null
            return LineSummary(line, diffs, inserts, branchBlames, currentBlame, branchPaths, lineages)
        }
    }
}

data class LineSummary(
    val currentLine: Int,
    val differences: List<BranchLineDifference>,
    val insertions: List<BranchLineDifference.BranchInsertionAfterCurrentLine>,
    val branchBlames: Map<String, Map<Int, BlameInfo>> = emptyMap(),
    val currentBlame: Map<Int, BlameInfo> = emptyMap(),
    val branchPaths: Map<String, String> = emptyMap(),
    val lineages: Map<String, ChangeLineage> = emptyMap(),
) {
    val identity: String =
        "$currentLine|${differences.size}|${insertions.size}|" +
            differences.joinToString(",") { "${it.branch.name}:${it.branch.headCommit}:${it.javaClass.simpleName}" } +
            "|" + insertions.joinToString(",") { "${it.branch.name}:${it.branch.headCommit}" }

    /**
     * Returns the most relevant branch-side line for a diff (i.e. the line we should
     * blame to attribute the change to an author). `null` when the diff has no
     * branch-side line. A line that is only present in the current snapshot is
     * attributed from current-side blame instead.
     */
    fun representativeBranchLine(diff: BranchLineDifference): Int? = when (diff) {
        is BranchLineDifference.ReplacedLine -> diff.branchLine
        is BranchLineDifference.ChangedBlock -> diff.branchLines?.first
        is BranchLineDifference.BranchInsertionAfterCurrentLine -> diff.branchLines.first
        is BranchLineDifference.DeletedInBranch -> diff.currentLine
        is BranchLineDifference.FileMissingInBranch -> null
    }

    fun blameFor(diff: BranchLineDifference): BlameInfo? {
        if (diff is BranchLineDifference.DeletedInBranch) return currentBlame[diff.currentLine]
        val line = representativeBranchLine(diff) ?: return null
        return branchBlames[diff.branch.name]?.get(line)
    }

    fun blameFor(insertion: BranchLineDifference.BranchInsertionAfterCurrentLine): BlameInfo? {
        return branchBlames[insertion.branch.name]?.get(insertion.branchLines.first)
    }

    fun lineageFor(difference: BranchLineDifference): ChangeLineage =
        lineages[differenceIdentity(difference)] ?: ChangeLineage.UNKNOWN

    fun badgeText(): String {
        val differingBranches = differences.map { it.branch.name }.toSet()
        val insertingBranches = insertions.map { it.branch.name }.toSet() - differingBranches
        val total = differingBranches.size + insertingBranches.size

        val onlyInsertions = differences.isEmpty() && insertions.isNotEmpty()
        val onlyMissing = differences.all { it is BranchLineDifference.FileMissingInBranch } && insertions.isEmpty()
        val onlyBlock = differences.isNotEmpty() && differences.all {
            (it is BranchLineDifference.ChangedBlock && it.confidence == Confidence.BLOCK_ONLY) ||
                it is BranchLineDifference.FileMissingInBranch
        } && insertions.isEmpty()

        return when {
            onlyMissing -> "?"
            onlyInsertions -> "+"
            onlyBlock -> "±"
            total in 1..9 -> total.toString()
            total >= 10 -> "9+"
            else -> "±"
        }
    }
}

fun differenceIdentity(difference: BranchLineDifference): String {
    val location = when (difference) {
        is BranchLineDifference.ReplacedLine -> difference.currentLine
        is BranchLineDifference.ChangedBlock -> difference.currentLines.first
        is BranchLineDifference.DeletedInBranch -> difference.currentLine
        is BranchLineDifference.BranchInsertionAfterCurrentLine -> difference.anchorCurrentLine
        is BranchLineDifference.FileMissingInBranch -> 1
    }
    return "${difference.branch.name}|$location|${difference.javaClass.simpleName}"
}
