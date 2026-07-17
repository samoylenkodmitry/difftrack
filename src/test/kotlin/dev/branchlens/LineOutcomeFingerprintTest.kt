package dev.branchlens

import dev.branchlens.diff.outcomeFingerprint
import dev.branchlens.model.BranchLineDifference
import dev.branchlens.model.Confidence
import dev.branchlens.model.LocalBranch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LineOutcomeFingerprintTest {
    private val first = branch("one")
    private val second = branch("two")

    @Test
    fun currentOnlyOutcomeGroupsAcrossBranches() {
        val a = BranchLineDifference.DeletedInBranch(first, 7, "new line")
        val b = BranchLineDifference.DeletedInBranch(second, 7, "new line")

        assertEquals(a.outcomeFingerprint(), b.outcomeFingerprint())
    }

    @Test
    fun differentReplacementTextStaysInSeparateGroups() {
        val a = BranchLineDifference.ReplacedLine(first, 7, 7, "current", "alpha", Confidence.HIGH)
        val b = BranchLineDifference.ReplacedLine(second, 7, 7, "current", "beta", Confidence.HIGH)

        assertNotEquals(a.outcomeFingerprint(), b.outcomeFingerprint())
    }

    private fun branch(name: String) =
        LocalBranch(name, name, 0L, "Tester", false, false, null, null, 0, 0)
}
