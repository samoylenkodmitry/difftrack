package dev.branchlens.editor

import com.intellij.openapi.editor.Editor
import java.awt.Point

/**
 * Computes the currently visible 1-based line range of an editor, extended by [marginLines]
 * on each side and clamped to [totalLines].
 */
object VisibleRangeTracker {

    fun visibleLines(editor: Editor, marginLines: Int, totalLines: Int): IntRange {
        if (editor.isDisposed) return IntRange.EMPTY
        val visibleArea = editor.scrollingModel.visibleArea
        val top = editor.xyToLogicalPosition(Point(0, visibleArea.y)).line + 1
        val bottom = editor.xyToLogicalPosition(Point(0, visibleArea.y + visibleArea.height)).line + 1
        val start = (top - marginLines).coerceAtLeast(1)
        val end = (bottom + marginLines).coerceAtMost(totalLines.coerceAtLeast(1))
        return start..end
    }
}
