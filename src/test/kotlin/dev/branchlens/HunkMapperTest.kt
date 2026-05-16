package dev.branchlens

import dev.branchlens.diff.HunkMapper
import dev.branchlens.diff.UnifiedDiffParser
import dev.branchlens.model.BranchLineDifference
import dev.branchlens.model.Confidence
import dev.branchlens.model.LocalBranch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HunkMapperTest {

    private val branch = LocalBranch("feature/a", "abcdef0", 0L, "Tester", false)

    @Test
    fun singleReplacementProducesHighConfidence() {
        val raw = """
            @@ -2 +2 @@
            -old
            +new
        """.trimIndent()
        val diffs = HunkMapper.map(branch, UnifiedDiffParser.parse(raw))
        assertEquals(1, diffs.size)
        val r = diffs[0] as BranchLineDifference.ReplacedLine
        assertEquals(2, r.currentLine)
        assertEquals(2, r.branchLine)
        assertEquals(Confidence.HIGH, r.confidence)
    }

    @Test
    fun equalSizeMultiLineReplacementIsMediumConfidence() {
        val raw = """
            @@ -10,2 +10,2 @@
            -a()
            -b()
            +x()
            +y()
        """.trimIndent()
        val diffs = HunkMapper.map(branch, UnifiedDiffParser.parse(raw))
        assertEquals(2, diffs.size)
        for (d in diffs) {
            val r = d as BranchLineDifference.ReplacedLine
            assertEquals(Confidence.MEDIUM, r.confidence)
        }
    }

    @Test
    fun unequalReplacementProducesBlockOnly() {
        val raw = """
            @@ -10,2 +10,3 @@
            -a()
            -b()
            +x()
            +y()
            +z()
        """.trimIndent()
        val diffs = HunkMapper.map(branch, UnifiedDiffParser.parse(raw))
        assertEquals(2, diffs.size)
        for (d in diffs) {
            val b = d as BranchLineDifference.ChangedBlock
            assertEquals(Confidence.BLOCK_ONLY, b.confidence)
            assertEquals(10..11, b.currentLines)
            assertEquals(10..12, b.branchLines)
        }
    }

    @Test
    fun pureDeletionMapsToDeletedInBranch() {
        val raw = """
            @@ -5,2 +4,0 @@
            -foo
            -bar
        """.trimIndent()
        val diffs = HunkMapper.map(branch, UnifiedDiffParser.parse(raw))
        assertEquals(2, diffs.size)
        val lines = diffs.map { (it as BranchLineDifference.DeletedInBranch).currentLine }
        assertEquals(listOf(5, 6), lines)
    }

    @Test
    fun pureInsertionMapsToInsertionAnchor() {
        val raw = """
            @@ -8,0 +9,2 @@
            +extra1
            +extra2
        """.trimIndent()
        val diffs = HunkMapper.map(branch, UnifiedDiffParser.parse(raw))
        assertEquals(1, diffs.size)
        val insertion = diffs[0] as BranchLineDifference.BranchInsertionAfterCurrentLine
        // Anchor is the last current-side line before the insertion (oldStart - 1).
        assertEquals(7, insertion.anchorCurrentLine)
        assertEquals(9..10, insertion.branchLines)
        assertEquals(listOf("extra1", "extra2"), insertion.branchText)
    }

    @Test
    fun insertionBeforeFirstLineAnchorsToZero() {
        val raw = """
            @@ -0,0 +1,1 @@
            +new-first-line
        """.trimIndent()
        val diffs = HunkMapper.map(branch, UnifiedDiffParser.parse(raw))
        assertEquals(1, diffs.size)
        val insertion = diffs[0] as BranchLineDifference.BranchInsertionAfterCurrentLine
        assertEquals(0, insertion.anchorCurrentLine)
    }

    @Test
    fun multipleHunksAreMappedIndependently() {
        val raw = """
            @@ -1 +1 @@
            -one
            +ONE
            @@ -3,0 +4,1 @@
            +inserted
        """.trimIndent()
        val diffs = HunkMapper.map(branch, UnifiedDiffParser.parse(raw))
        assertEquals(2, diffs.size)
        assertTrue(diffs[0] is BranchLineDifference.ReplacedLine)
        assertTrue(diffs[1] is BranchLineDifference.BranchInsertionAfterCurrentLine)
    }
}
