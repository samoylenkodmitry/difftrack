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
)

data class BlameInfo(
    val commitHash: String,
    val author: String?,
    val authorMail: String?,
    val authorTimeEpochSeconds: Long?,
    val authorTimezone: String?,
    val summary: String?,
)

enum class Confidence { HIGH, MEDIUM, BLOCK_ONLY }

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
        val branchBlames: Map<String, Map<Int, BlameInfo>>,
        val computedAtNanos: Long,
        val documentHash: String,
        val branchCount: Int,
    ) : FileAnalysisResult() {
        fun summaryForLine(line: Int): LineSummary? {
            val diffs = perLineDifferences[line] ?: emptyList()
            val inserts = insertionsAfter[line] ?: emptyList()
            if (diffs.isEmpty() && inserts.isEmpty()) return null
            return LineSummary(line, diffs, inserts, branchBlames)
        }
    }
}

data class LineSummary(
    val currentLine: Int,
    val differences: List<BranchLineDifference>,
    val insertions: List<BranchLineDifference.BranchInsertionAfterCurrentLine>,
    val branchBlames: Map<String, Map<Int, BlameInfo>> = emptyMap(),
) {
    val identity: String =
        "$currentLine|${differences.size}|${insertions.size}|" +
            differences.joinToString(",") { "${it.branch.name}:${it.branch.headCommit}:${it.javaClass.simpleName}" } +
            "|" + insertions.joinToString(",") { "${it.branch.name}:${it.branch.headCommit}" }

    /**
     * Returns the most relevant branch-side line for a diff (i.e. the line we should
     * blame to attribute the change to an author). `null` when the diff has no
     * branch-side line (FileMissing, DeletedInBranch).
     */
    fun representativeBranchLine(diff: BranchLineDifference): Int? = when (diff) {
        is BranchLineDifference.ReplacedLine -> diff.branchLine
        is BranchLineDifference.ChangedBlock -> diff.branchLines?.first
        is BranchLineDifference.BranchInsertionAfterCurrentLine -> diff.branchLines.first
        is BranchLineDifference.DeletedInBranch -> null
        is BranchLineDifference.FileMissingInBranch -> null
    }

    fun blameFor(diff: BranchLineDifference): BlameInfo? {
        val line = representativeBranchLine(diff) ?: return null
        return branchBlames[diff.branch.name]?.get(line)
    }

    fun blameFor(insertion: BranchLineDifference.BranchInsertionAfterCurrentLine): BlameInfo? {
        return branchBlames[insertion.branch.name]?.get(insertion.branchLines.first)
    }

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
