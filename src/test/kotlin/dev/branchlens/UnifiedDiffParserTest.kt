package dev.branchlens

import dev.branchlens.diff.DiffLine
import dev.branchlens.diff.UnifiedDiffParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedDiffParserTest {

    @Test
    fun parsesSingleLineReplacement() {
        val raw = """
            @@ -2 +2 @@
            -val x = old()
            +val x = new()
        """.trimIndent()
        val diff = UnifiedDiffParser.parse(raw)
        assertEquals(1, diff.hunks.size)
        val hunk = diff.hunks[0]
        assertEquals(2, hunk.oldStart)
        assertEquals(1, hunk.oldLength)
        assertEquals(2, hunk.newStart)
        assertEquals(1, hunk.newLength)
        val removed = hunk.lines.filterIsInstance<DiffLine.Removed>()
        val added = hunk.lines.filterIsInstance<DiffLine.Added>()
        assertEquals(1, removed.size)
        assertEquals(1, added.size)
        assertEquals("val x = old()", removed[0].text)
        assertEquals("val x = new()", added[0].text)
    }

    @Test
    fun parsesUnequalMultiLineReplacement() {
        val raw = """
            @@ -10,2 +10,3 @@
            -a()
            -b()
            +x()
            +y()
            +z()
        """.trimIndent()
        val diff = UnifiedDiffParser.parse(raw)
        val hunk = diff.hunks[0]
        assertEquals(10, hunk.oldStart)
        assertEquals(2, hunk.oldLength)
        assertEquals(3, hunk.newLength)
        assertEquals(2, hunk.lines.count { it is DiffLine.Removed })
        assertEquals(3, hunk.lines.count { it is DiffLine.Added })
    }

    @Test
    fun parsesDeletionOnly() {
        val raw = """
            @@ -5,2 +4,0 @@
            -foo()
            -bar()
        """.trimIndent()
        val diff = UnifiedDiffParser.parse(raw)
        val hunk = diff.hunks[0]
        assertEquals(5, hunk.oldStart)
        assertEquals(2, hunk.oldLength)
        assertEquals(4, hunk.newStart)
        assertEquals(0, hunk.newLength)
        assertEquals(2, hunk.lines.count { it is DiffLine.Removed })
        assertEquals(0, hunk.lines.count { it is DiffLine.Added })
    }

    @Test
    fun parsesInsertionOnly() {
        val raw = """
            @@ -8,0 +9,2 @@
            +extra1()
            +extra2()
        """.trimIndent()
        val diff = UnifiedDiffParser.parse(raw)
        val hunk = diff.hunks[0]
        assertEquals(8, hunk.oldStart)
        assertEquals(0, hunk.oldLength)
        assertEquals(9, hunk.newStart)
        assertEquals(2, hunk.newLength)
        assertEquals(2, hunk.lines.count { it is DiffLine.Added })
    }

    @Test
    fun parsesMultipleHunks() {
        val raw = """
            @@ -1 +1 @@
            -one
            +ONE
            @@ -3 +3 @@
            -three
            +THREE
        """.trimIndent()
        val diff = UnifiedDiffParser.parse(raw)
        assertEquals(2, diff.hunks.size)
        assertEquals(1, diff.hunks[0].oldStart)
        assertEquals(3, diff.hunks[1].oldStart)
    }

    @Test
    fun toleratesNoNewlineMarker() {
        val raw = """
            @@ -1 +1 @@
            -hello
            \ No newline at end of file
            +HELLO
            \ No newline at end of file
        """.trimIndent()
        val diff = UnifiedDiffParser.parse(raw)
        val hunk = diff.hunks[0]
        assertEquals(1, hunk.lines.count { it is DiffLine.Removed })
        assertEquals(1, hunk.lines.count { it is DiffLine.Added })
    }

    @Test
    fun parsesFileHeader() {
        val raw = """
            diff --git a/foo.kt b/foo.kt
            --- a/foo.kt
            +++ b/foo.kt
            @@ -1 +1 @@
            -hi
            +hello
        """.trimIndent()
        val diff = UnifiedDiffParser.parse(raw)
        assertNotNull(diff.fileHeader)
        assertEquals("a/foo.kt", diff.fileHeader?.oldPath)
        assertEquals("b/foo.kt", diff.fileHeader?.newPath)
        assertEquals(1, diff.hunks.size)
    }

    @Test
    fun emptyInputProducesNoHunks() {
        val diff = UnifiedDiffParser.parse("")
        assertEquals(0, diff.hunks.size)
        assertNull(diff.fileHeader)
    }

    @Test
    fun hunkHeaderWithoutExplicitLengthIsParsedAsOne() {
        val raw = """
            @@ -4 +4 @@
            -val a = 1
            +val a = 2
        """.trimIndent()
        val diff = UnifiedDiffParser.parse(raw)
        val hunk = diff.hunks[0]
        assertEquals(1, hunk.oldLength)
        assertEquals(1, hunk.newLength)
        assertTrue(hunk.lines.any { it is DiffLine.Removed })
        assertTrue(hunk.lines.any { it is DiffLine.Added })
    }
}
