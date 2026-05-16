package dev.branchlens.editor

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import dev.branchlens.model.BranchLineDifference
import dev.branchlens.model.Confidence
import dev.branchlens.model.FileAnalysisResult
import dev.branchlens.model.LineSummary
import dev.branchlens.popup.BranchLensPopup
import java.awt.Point
import java.awt.datatransfer.StringSelection
import javax.swing.Icon

/**
 * Owns the gutter highlighters for one or more editors. On every [applyVisible] call
 * the visible-range highlighter set is rebuilt from scratch (cheap: at most a few
 * hundred lines); [clear] removes them.
 */
class GutterBadgeRenderer {

    private val highlightersByEditor = HashMap<Editor, MutableList<RangeHighlighter>>()

    fun applyVisible(editor: Editor, result: FileAnalysisResult.Computed, marginLines: Int) {
        clear(editor)
        if (editor.isDisposed) return

        val visible = computeVisibleRange(editor, marginLines, result.totalLines)
        val markup = editor.markupModel
        val newHighlighters = mutableListOf<RangeHighlighter>()

        for (line in visible) {
            val summary = result.summaryForLine(line) ?: continue
            val zeroBased = (line - 1).coerceAtLeast(0)
            if (zeroBased >= editor.document.lineCount) continue
            val highlighter = markup.addLineHighlighter(
                zeroBased,
                HighlighterLayer.ADDITIONAL_SYNTAX,
                null,
            )
            highlighter.gutterIconRenderer = BranchLensGutterIconRenderer(editor.project, summary)
            newHighlighters += highlighter
        }
        highlightersByEditor[editor] = newHighlighters
    }

    fun clear(editor: Editor) {
        val highlighters = highlightersByEditor.remove(editor) ?: return
        if (editor.isDisposed) return
        for (h in highlighters) {
            if (h.isValid) editor.markupModel.removeHighlighter(h)
        }
    }

    private fun computeVisibleRange(editor: Editor, margin: Int, totalLines: Int): IntRange {
        val visibleArea = editor.scrollingModel.visibleArea
        val top = editor.xyToLogicalPosition(Point(0, visibleArea.y)).line + 1
        val bottom = editor.xyToLogicalPosition(Point(0, visibleArea.y + visibleArea.height)).line + 1
        val start = (top - margin).coerceAtLeast(1)
        val end = (bottom + margin).coerceAtMost(totalLines.coerceAtLeast(1))
        return start..end
    }
}

private class BranchLensGutterIconRenderer(
    private val project: Project?,
    private val summary: LineSummary,
) : GutterIconRenderer() {

    override fun getIcon(): Icon {
        val badge = summary.badgeText()
        val accent = when {
            badge == "?" -> BranchLensBadgeIcon.Accent.MISSING
            badge == "+" -> BranchLensBadgeIcon.Accent.INSERT
            badge == "±" -> BranchLensBadgeIcon.Accent.BLOCK
            summary.differences.any {
                it is BranchLineDifference.ChangedBlock && it.confidence == Confidence.BLOCK_ONLY
            } -> BranchLensBadgeIcon.Accent.BLOCK
            else -> BranchLensBadgeIcon.Accent.DEFAULT
        }
        return BranchLensBadgeIcon(badge, accent)
    }

    override fun getTooltipText(): String = BranchLensPopup.renderTooltip(summary)

    override fun isNavigateAction(): Boolean = true

    override fun getClickAction(): AnAction = ShowPopupAction(project, summary)

    override fun getAlignment(): Alignment = Alignment.RIGHT

    override fun equals(other: Any?): Boolean =
        other is BranchLensGutterIconRenderer && other.summary.identity == summary.identity

    override fun hashCode(): Int = summary.identity.hashCode()
}

private class ShowPopupAction(
    private val project: Project?,
    private val summary: LineSummary,
) : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val component = e.inputEvent?.component ?: return
        val actions = DefaultActionGroup().apply {
            add(CopyAction("Copy Summary") { BranchLensPopup.renderPlainText(summary) })
            add(CopyAction("Copy Commit Hashes") {
                summary.differences
                    .map { it.branch.headCommit }
                    .distinct()
                    .joinToString("\n")
            })
        }
        val popup = JBPopupFactory.getInstance().createComponentPopupBuilder(
            BranchLensPopup.buildPanel(project, summary, actions),
            null,
        )
            .setTitle("Branch Lens — line ${summary.currentLine}")
            .setRequestFocus(true)
            .setResizable(true)
            .setMovable(true)
            .createPopup()
        popup.showUnderneathOf(component)

        // Touch ActionManager to keep parity with platform expectations.
        ActionManager.getInstance()
    }
}

private class CopyAction(text: String, private val content: () -> String) : AnAction(text) {
    override fun actionPerformed(e: AnActionEvent) {
        CopyPasteManager.getInstance().setContents(StringSelection(content()))
    }
}
