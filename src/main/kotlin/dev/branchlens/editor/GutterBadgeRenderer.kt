package dev.branchlens.editor

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import dev.branchlens.model.BlameInfo
import dev.branchlens.model.BranchLineDifference
import dev.branchlens.model.Confidence
import dev.branchlens.model.FileAnalysisResult
import dev.branchlens.model.LineSummary
import dev.branchlens.model.LocalBranch
import dev.branchlens.popup.BranchLensPopup
import java.awt.Point
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.Icon
import javax.swing.JList

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
            highlighter.gutterIconRenderer = BranchLensGutterIconRenderer(editor, result, summary)
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
    private val editor: Editor,
    private val result: FileAnalysisResult.Computed,
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

    override fun getClickAction(): AnAction = OpenDiffAction(editor, result, summary)

    override fun getAlignment(): Alignment = Alignment.RIGHT

    override fun equals(other: Any?): Boolean =
        other is BranchLensGutterIconRenderer && other.summary.identity == summary.identity

    override fun hashCode(): Int = summary.identity.hashCode()
}

private class OpenDiffAction(
    private val editor: Editor,
    private val result: FileAnalysisResult.Computed,
    private val summary: LineSummary,
) : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = editor.project ?: e.project ?: return
        val virtualFile = FileDocumentManager.getInstance().getFile(editor.document)

        val entries = collectEntries(summary)
        when (entries.size) {
            0 -> return
            1 -> openDiff(project, virtualFile, entries.first())
            else -> showChooser(project, virtualFile, entries, e)
        }
    }

    private fun showChooser(
        project: Project,
        virtualFile: VirtualFile?,
        entries: List<DiffEntry>,
        e: AnActionEvent,
    ) {
        val popup = JBPopupFactory.getInstance()
            .createPopupChooserBuilder(entries)
            .setTitle("Branch Lens — line ${summary.currentLine}")
            .setRenderer(DiffEntryRenderer())
            .setItemChosenCallback { entry -> openDiff(project, virtualFile, entry) }
            .setNamerForFiltering { it.branch.name }
            .createPopup()
        val component = e.inputEvent?.component
        if (component != null) {
            popup.showUnderneathOf(component)
        } else {
            popup.showInBestPositionFor(e.dataContext)
        }
    }

    private fun openDiff(project: Project, virtualFile: VirtualFile?, entry: DiffEntry) {
        val branchText = result.branchContents[entry.branch.name]
        val fileType = virtualFile?.fileType
        val factory = DiffContentFactory.getInstance()
        val currentContent = factory.create(project, editor.document.text, fileType)
        val branchContent = if (branchText != null) {
            factory.create(project, branchText, fileType)
        } else {
            // File is missing in this branch — render empty branch side.
            factory.createEmpty()
        }
        val fileName = virtualFile?.name ?: "(unsaved)"
        val request = SimpleDiffRequest(
            "Branch Lens — $fileName: current vs ${entry.branch.name}",
            currentContent,
            branchContent,
            "$fileName  (current)",
            "$fileName  @ ${entry.branch.name}",
        )
        DiffManager.getInstance().showDiff(project, request)
    }

    private fun collectEntries(summary: LineSummary): List<DiffEntry> {
        val byBranch = LinkedHashMap<String, DiffEntry>()
        for (diff in summary.differences) {
            val existing = byBranch[diff.branch.name]
            if (existing == null) {
                byBranch[diff.branch.name] = DiffEntry(
                    branch = diff.branch,
                    kindLabel = kindLabel(diff),
                    blame = summary.blameFor(diff),
                )
            }
        }
        for (insertion in summary.insertions) {
            byBranch.getOrPut(insertion.branch.name) {
                DiffEntry(
                    branch = insertion.branch,
                    kindLabel = "inserts ${insertion.branchText.size} line${if (insertion.branchText.size == 1) "" else "s"}",
                    blame = summary.blameFor(insertion),
                )
            }
        }
        return byBranch.values.toList()
    }

    private fun kindLabel(diff: BranchLineDifference): String = when (diff) {
        is BranchLineDifference.ReplacedLine -> when (diff.confidence) {
            Confidence.HIGH -> "replaced"
            Confidence.MEDIUM -> "replaced (multi-line)"
            Confidence.BLOCK_ONLY -> "changed block"
        }
        is BranchLineDifference.ChangedBlock -> "changed block"
        is BranchLineDifference.DeletedInBranch -> "deleted in branch"
        is BranchLineDifference.BranchInsertionAfterCurrentLine ->
            "inserts ${diff.branchText.size} line${if (diff.branchText.size == 1) "" else "s"}"
        is BranchLineDifference.FileMissingInBranch -> "file missing"
    }
}

private data class DiffEntry(
    val branch: LocalBranch,
    val kindLabel: String,
    val blame: BlameInfo?,
)

private class DiffEntryRenderer : ColoredListCellRenderer<DiffEntry>() {
    private val dateFmt: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

    override fun customizeCellRenderer(
        list: JList<out DiffEntry>,
        value: DiffEntry,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean,
    ) {
        append(value.branch.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
        append("  ${value.kindLabel}", SimpleTextAttributes.GRAYED_ATTRIBUTES)

        val blame = value.blame
        if (blame != null) {
            val author = blame.author ?: value.branch.authorName
            val time = blame.authorTimeEpochSeconds
            val parts = buildList {
                author?.let { add(it) }
                time?.let { add(dateFmt.format(Instant.ofEpochSecond(it))) }
            }
            if (parts.isNotEmpty()) {
                append("  — ${parts.joinToString(", ")}", SimpleTextAttributes.GRAY_ITALIC_ATTRIBUTES)
            }
            blame.summary?.let {
                append("  · ${it.take(60)}", SimpleTextAttributes.GRAY_ITALIC_ATTRIBUTES)
            }
        } else if (value.branch.authorName != null) {
            append("  — ${value.branch.authorName}", SimpleTextAttributes.GRAY_ITALIC_ATTRIBUTES)
        }
    }
}
