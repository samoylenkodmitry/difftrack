package dev.branchlens.diff

import dev.branchlens.model.BranchLineDifference
import dev.branchlens.model.BranchLineDifference.BranchInsertionAfterCurrentLine
import dev.branchlens.model.FileAnalysisResult
import dev.branchlens.util.TextUtil

/**
 * Aggregates [BranchLineDifference] entries from many branches into a single
 * [FileAnalysisResult.Computed], keyed by current editor line number (1-based).
 */
object LineDifferenceClassifier {
    fun aggregate(
        documentText: String,
        differences: List<BranchLineDifference>,
        branchCount: Int,
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
                is BranchLineDifference.FileMissingInBranch -> missing += d
            }
        }

        return FileAnalysisResult.Computed(
            totalLines = TextUtil.countLines(documentText),
            perLineDifferences = perLine,
            insertionsAfter = insertions,
            missingInBranches = missing,
            computedAtNanos = System.nanoTime(),
            documentHash = TextUtil.stableHash(documentText),
            branchCount = branchCount,
        )
    }
}
