package dev.branchlens.diff

import dev.branchlens.model.BlameInfo
import dev.branchlens.model.BranchLineDifference
import dev.branchlens.model.BranchLineDifference.BranchInsertionAfterCurrentLine
import dev.branchlens.model.FileAnalysisResult
import dev.branchlens.model.LocalBranch
import dev.branchlens.model.ChangeLineage
import java.nio.file.Path
import dev.branchlens.util.TextUtil

/**
 * Aggregates [BranchLineDifference] entries from many branches into a single
 * [FileAnalysisResult.Computed], keyed by current editor line number (1-based).
 *
 * [branchContents] maps branch name → full branch-side file text (so the click action
 * can hand it to IntelliJ's DiffManager without re-running git).
 * [branchBlames] maps branch name → (branch line → blame), already computed by the
 * caller.
 */
object LineDifferenceClassifier {
    fun aggregate(
        documentText: String,
        differences: List<BranchLineDifference>,
        branchCount: Int,
        branchContents: Map<String, String>,
        branchPaths: Map<String, String> = emptyMap(),
        branchBlames: Map<String, Map<Int, BlameInfo>>,
        currentBlame: Map<Int, BlameInfo> = emptyMap(),
        currentBranch: LocalBranch? = null,
        analyzedBranches: List<LocalBranch> = emptyList(),
        lineages: Map<String, ChangeLineage> = emptyMap(),
        commitContainment: Map<String, Set<String>> = emptyMap(),
        repoRoot: Path? = null,
    ): FileAnalysisResult.Computed {
        val perLine = HashMap<Int, MutableList<BranchLineDifference>>()
        val insertions = HashMap<Int, MutableList<BranchInsertionAfterCurrentLine>>()
        val missing = mutableListOf<BranchLineDifference.FileMissingInBranch>()

        for (d in differences) {
            when (d) {
                is BranchLineDifference.ReplacedLine ->
                    perLine.getOrPut(d.currentLine) { mutableListOf() }.add(d)
                is BranchLineDifference.ChangedBlock ->
                    for (line in d.currentLines) {
                        perLine.getOrPut(line) { mutableListOf() }.add(d)
                    }
                is BranchLineDifference.DeletedInBranch ->
                    perLine.getOrPut(d.currentLine) { mutableListOf() }.add(d)
                is BranchInsertionAfterCurrentLine ->
                    insertions.getOrPut(d.anchorCurrentLine) { mutableListOf() }.add(d)
                is BranchLineDifference.FileMissingInBranch -> {
                    missing += d
                    // Surface as a per-line entry on line 1 too, so the editor renders a
                    // `?` badge somewhere visible. The popup still reads the full set
                    // from `missingInBranches`.
                    perLine.getOrPut(1) { mutableListOf() }.add(d)
                }
            }
        }

        return FileAnalysisResult.Computed(
            totalLines = TextUtil.countLines(documentText),
            perLineDifferences = perLine,
            insertionsAfter = insertions,
            missingInBranches = missing,
            branchContents = branchContents,
            branchPaths = branchPaths,
            branchBlames = branchBlames,
            currentBlame = currentBlame,
            computedAtNanos = System.nanoTime(),
            documentHash = TextUtil.stableHash(documentText),
            branchCount = branchCount,
            currentBranch = currentBranch,
            analyzedBranches = analyzedBranches,
            lineages = lineages,
            commitContainment = commitContainment,
            repoRoot = repoRoot,
        )
    }
}
