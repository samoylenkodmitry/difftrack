package dev.branchlens.diff

import dev.branchlens.model.BranchLineDifference

/**
 * Branch-independent identity of what a branch does at the selected current line.
 * Used only for presentation grouping; individual branches remain available.
 */
fun BranchLineDifference.outcomeFingerprint(): String = when (this) {
    is BranchLineDifference.ReplacedLine ->
        "replace|$confidence|$branchText"
    is BranchLineDifference.ChangedBlock ->
        "block|$confidence|${branchText.joinToString("\u001f")}"
    is BranchLineDifference.DeletedInBranch ->
        "current-only|$currentText"
    is BranchLineDifference.BranchInsertionAfterCurrentLine ->
        "insertion|$beforeFirstLine|${branchText.joinToString("\u001f")}"
    is BranchLineDifference.FileMissingInBranch ->
        "file-missing"
}
