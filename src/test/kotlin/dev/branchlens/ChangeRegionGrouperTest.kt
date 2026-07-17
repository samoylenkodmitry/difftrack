package dev.branchlens

import dev.branchlens.diff.ChangeRegionGrouper
import dev.branchlens.diff.LineDifferenceClassifier
import dev.branchlens.model.BlameInfo
import dev.branchlens.model.BranchLineDifference
import dev.branchlens.model.LocalBranch
import org.junit.Assert.assertEquals
import org.junit.Test

class ChangeRegionGrouperTest {
    private val branch = LocalBranch("other", "other", 0L, "Tester", false, false, null, null, 0, 0)

    @Test
    fun consecutiveCurrentOnlyLinesFromOneCommitBecomeOneRegion() {
        val result = LineDifferenceClassifier.aggregate(
            documentText = "one\ntwo\nthree\n",
            differences = listOf(
                BranchLineDifference.DeletedInBranch(branch, 1, "one"),
                BranchLineDifference.DeletedInBranch(branch, 2, "two"),
                BranchLineDifference.DeletedInBranch(branch, 3, "three"),
            ),
            branchCount = 1,
            branchContents = mapOf(branch.name to ""),
            branchBlames = emptyMap(),
            currentBlame = mapOf(1 to blame("same"), 2 to blame("same"), 3 to blame("same")),
        )

        assertEquals(listOf(1..3), ChangeRegionGrouper.group(result).map { it.lines })
    }

    @Test
    fun perLineBlameChangesDoNotFragmentOneBranchCohort() {
        val result = LineDifferenceClassifier.aggregate(
            documentText = "one\ntwo\n",
            differences = listOf(
                BranchLineDifference.DeletedInBranch(branch, 1, "one"),
                BranchLineDifference.DeletedInBranch(branch, 2, "two"),
            ),
            branchCount = 1,
            branchContents = mapOf(branch.name to ""),
            branchBlames = emptyMap(),
            currentBlame = mapOf(1 to blame("first"), 2 to blame("second")),
        )

        assertEquals(listOf(1..2), ChangeRegionGrouper.group(result).map { it.lines })
    }

    @Test
    fun aDifferentAffectedBranchSetStartsAnotherRegion() {
        val secondBranch = LocalBranch("second", "second", 0L, "Tester", false, false, null, null, 0, 0)
        val result = LineDifferenceClassifier.aggregate(
            documentText = "one\ntwo\n",
            differences = listOf(
                BranchLineDifference.DeletedInBranch(branch, 1, "one"),
                BranchLineDifference.DeletedInBranch(secondBranch, 2, "two"),
            ),
            branchCount = 2,
            branchContents = mapOf(branch.name to "", secondBranch.name to ""),
            branchBlames = emptyMap(),
            currentBlame = mapOf(1 to blame("same"), 2 to blame("same")),
        )

        assertEquals(listOf(1..1, 2..2), ChangeRegionGrouper.group(result).map { it.lines })
    }

    private fun blame(commit: String) = BlameInfo(commit, "Tester", null, 1L, null, "change")
}
