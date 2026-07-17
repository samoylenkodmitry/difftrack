package dev.branchlens.editor

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.diff.util.Side
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.LineMarkerRenderer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.util.Pair
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.openapi.ide.CopyPasteManager
import dev.branchlens.model.BlameInfo
import dev.branchlens.diff.BRANCH_LENS_DIFF_ANNOTATIONS
import dev.branchlens.diff.BranchLensDiffAnnotationData
import dev.branchlens.diff.outcomeFingerprint
import dev.branchlens.diff.ChangeRegionGrouper
import dev.branchlens.model.BranchLineDifference
import dev.branchlens.model.Confidence
import dev.branchlens.model.FileAnalysisResult
import dev.branchlens.model.LineSummary
import dev.branchlens.model.LocalBranch
import dev.branchlens.model.ChangeLineage
import dev.branchlens.model.displayName
import dev.branchlens.model.isUncommitted
import dev.branchlens.popup.BranchLensPopup
import dev.branchlens.ui.CommitUiActions
import java.awt.Point
import java.awt.Graphics
import java.awt.Rectangle
import java.awt.datatransfer.StringSelection
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

        for (region in ChangeRegionGrouper.group(result)) {
            if (region.lines.first !in visible) continue
            val line = region.lines.first
            val summary = region.summary
            val zeroBased = (line - 1).coerceAtLeast(0)
            if (zeroBased >= editor.document.lineCount) continue
            val highlighter = if (region.lines.first == region.lines.last) {
                markup.addLineHighlighter(
                    zeroBased,
                    HighlighterLayer.ADDITIONAL_SYNTAX,
                    null,
                )
            } else {
                val endZeroBased = (region.lines.last - 1).coerceAtMost(editor.document.lineCount - 1)
                markup.addRangeHighlighter(
                    editor.document.getLineStartOffset(zeroBased),
                    editor.document.getLineEndOffset(endZeroBased),
                    HighlighterLayer.ADDITIONAL_SYNTAX,
                    null,
                    HighlighterTargetArea.LINES_IN_RANGE,
                ).also { it.lineMarkerRenderer = MultiLineChangeMarkerRenderer }
            }
            highlighter.gutterIconRenderer = BranchLensGutterIconRenderer(editor, result, summary, region.lines)
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

private object MultiLineChangeMarkerRenderer : LineMarkerRenderer {
    override fun paint(editor: Editor, g: Graphics, r: Rectangle) {
        val width = com.intellij.util.ui.JBUI.scale(3)
        val inset = com.intellij.util.ui.JBUI.scale(1)
        val color = editor.colorsScheme.getColor(EditorColors.MODIFIED_LINES_COLOR)
            ?: com.intellij.ui.JBColor(0x4A90E2, 0x5B9BD5)
        g.color = color
        g.fillRoundRect(
            r.x + r.width - width - inset,
            r.y,
            width,
            r.height.coerceAtLeast(width),
            width,
            width,
        )
    }
}

private class BranchLensGutterIconRenderer(
    private val editor: Editor,
    private val result: FileAnalysisResult.Computed,
    private val summary: LineSummary,
    private val lineRange: IntRange,
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

    override fun getTooltipText(): String = BranchLensPopup.renderTooltip(summary, lineRange)

    override fun getAccessibleName(): String =
        "Branch Lens: ${(summary.differences.map { it.branch.name } + summary.insertions.map { it.branch.name }).toSet().size} " +
            "branch differences on ${rangeLabel(lineRange)}"

    override fun isNavigateAction(): Boolean = true

    override fun getClickAction(): AnAction = OpenDiffAction(editor, result, summary, lineRange)

    override fun getPopupMenuActions(): ActionGroup = DefaultActionGroup().apply {
        add(OpenDiffAction(editor, result, summary, lineRange))
        val project = editor.project
        if (project != null) {
            val origin = result.currentBlame[summary.currentLine]
                ?: summary.differences.firstNotNullOfOrNull(summary::blameFor)
                ?: summary.insertions.firstNotNullOfOrNull(summary::blameFor)
            for (action in CommitUiActions.actions(project, result.repoRoot, origin)) {
                add(action)
            }
        }
        add(object : AnAction("Copy Branch Lens Summary") {
            override fun actionPerformed(e: AnActionEvent) {
                CopyPasteManager.getInstance().setContents(
                    StringSelection(BranchLensPopup.renderPlainText(summary, lineRange)),
                )
            }
        })
        addSeparator()
        add(object : AnAction("Configure Branch Scope…") {
            override fun actionPerformed(e: AnActionEvent) {
                val project = editor.project ?: e.project ?: return
                ShowSettingsUtil.getInstance().showSettingsDialog(project, "Branch Lens")
            }
        })
    }

    override fun getAlignment(): Alignment = Alignment.RIGHT

    override fun equals(other: Any?): Boolean =
        other is BranchLensGutterIconRenderer && other.summary.identity == summary.identity &&
            other.lineRange == lineRange

    override fun hashCode(): Int = 31 * summary.identity.hashCode() + lineRange.hashCode()
}

private class OpenDiffAction(
    private val editor: Editor,
    private val result: FileAnalysisResult.Computed,
    private val summary: LineSummary,
    private val lineRange: IntRange,
) : AnAction("Open Branch Diff") {

    override fun actionPerformed(e: AnActionEvent) {
        val project = editor.project ?: e.project ?: return
        val virtualFile = FileDocumentManager.getInstance().getFile(editor.document)

        val entries = collectEntries(summary)
        when (entries.size) {
            0 -> return
            1 -> openDiff(project, virtualFile, entries.first())
            else -> showOutcomeChooser(project, virtualFile, entries, e)
        }
    }

    private fun showOutcomeChooser(
        project: Project,
        virtualFile: VirtualFile?,
        entries: List<DiffEntry>,
        e: AnActionEvent,
    ) {
        val choices = entries.groupBy { it.outcomeKey }.values.map { equivalent ->
            if (equivalent.size == 1) DiffChoice.Single(equivalent.single())
            else DiffChoice.Group(equivalent)
        }
        val popup = JBPopupFactory.getInstance()
            .createPopupChooserBuilder(choices)
            .setTitle("Branch Lens — ${rangeLabel(lineRange)}")
            .setRenderer(DiffChoiceRenderer())
            .setItemChosenCallback { choice ->
                when (choice) {
                    is DiffChoice.Single -> openDiff(project, virtualFile, choice.entry)
                    is DiffChoice.Group -> {
                        if (choice.hasIdenticalFullDiff) {
                            openDiff(project, virtualFile, choice.entries.first(), choice.entries)
                        } else {
                            showBranchChooser(project, virtualFile, choice.entries, e)
                        }
                    }
                }
            }
            .setNamerForFiltering { it.filterText }
            .createPopup()
        showPopup(popup, e)
    }

    private fun showBranchChooser(
        project: Project,
        virtualFile: VirtualFile?,
        entries: List<DiffEntry>,
        e: AnActionEvent,
    ) {
        val popup = JBPopupFactory.getInstance()
            .createPopupChooserBuilder(entries)
            .setTitle("${entries.size} equivalent branches — ${entries.first().kindLabel}")
            .setRenderer(DiffEntryRenderer())
            .setItemChosenCallback { entry -> openDiff(project, virtualFile, entry) }
            .setNamerForFiltering { it.branch.name }
            .createPopup()
        showPopup(popup, e)
    }

    private fun showPopup(
        popup: com.intellij.openapi.ui.popup.JBPopup,
        e: AnActionEvent,
    ) {
        val component = e.inputEvent?.component
        if (component != null) {
            popup.showUnderneathOf(component)
        } else {
            popup.showInBestPositionFor(e.dataContext)
        }
    }

    private fun openDiff(
        project: Project,
        virtualFile: VirtualFile?,
        entry: DiffEntry,
        equivalentEntries: List<DiffEntry>? = null,
    ) {
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
        val branchPath = result.branchPaths[entry.branch.name] ?: fileName
        val targetLabel = equivalentEntries?.let { "${it.size} equivalent branches" }
            ?: entry.branch.displayName
        val request = SimpleDiffRequest(
            "Branch Lens — $fileName: current vs $targetLabel",
            currentContent,
            branchContent,
            "$fileName  (current)",
            "$branchPath  @ $targetLabel",
        )
        request.putUserData(
            DiffUserDataKeys.SCROLL_TO_LINE,
            Pair.create(Side.LEFT, (summary.currentLine - 1).coerceAtLeast(0)),
        )
        request.putUserData(
            BRANCH_LENS_DIFF_ANNOTATIONS,
            BranchLensDiffAnnotationData(
                current = result.currentBlame,
                branch = result.branchBlames[entry.branch.name].orEmpty(),
                repoRoot = result.repoRoot,
            ),
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
                    kindLabel = kindLabel(diff, summary.lineageFor(diff)),
                    blame = summary.blameFor(diff),
                    branchPath = result.branchPaths[diff.branch.name],
                    outcomeKey = diff.outcomeFingerprint(),
                    snapshotText = result.branchContents[diff.branch.name],
                    branchBlame = result.branchBlames[diff.branch.name].orEmpty(),
                )
            }
        }
        for (insertion in summary.insertions) {
            byBranch.getOrPut(insertion.branch.name) {
                DiffEntry(
                    branch = insertion.branch,
                    kindLabel = kindLabel(insertion, summary.lineageFor(insertion)),
                    blame = summary.blameFor(insertion),
                    branchPath = result.branchPaths[insertion.branch.name],
                    outcomeKey = insertion.outcomeFingerprint(),
                    snapshotText = result.branchContents[insertion.branch.name],
                    branchBlame = result.branchBlames[insertion.branch.name].orEmpty(),
                )
            }
        }
        return byBranch.values.toList()
    }

    private fun kindLabel(diff: BranchLineDifference, lineage: ChangeLineage): String {
        if (lineage != ChangeLineage.UNKNOWN) return lineage.label
        return when (diff) {
        is BranchLineDifference.ReplacedLine -> when (diff.confidence) {
            Confidence.HIGH -> "replaced"
            Confidence.MEDIUM -> "replaced (multi-line)"
            Confidence.BLOCK_ONLY -> "changed block"
        }
        is BranchLineDifference.ChangedBlock -> "changed block"
        is BranchLineDifference.DeletedInBranch -> "present only in current"
        is BranchLineDifference.BranchInsertionAfterCurrentLine ->
            insertionKindLabel(diff)
        is BranchLineDifference.FileMissingInBranch -> "file missing"
        }
    }
}

private fun insertionKindLabel(
    insertion: BranchLineDifference.BranchInsertionAfterCurrentLine,
): String {
    val count = insertion.branchText.size
    val lines = "$count line${if (count == 1) "" else "s"}"
    return if (insertion.beforeFirstLine) "inserts $lines before line 1" else "inserts $lines"
}

private fun rangeLabel(lines: IntRange): String =
    if (lines.first == lines.last) "line ${lines.first}" else "lines ${lines.first}–${lines.last}"

private data class DiffEntry(
    val branch: LocalBranch,
    val kindLabel: String,
    val blame: BlameInfo?,
    val branchPath: String?,
    val outcomeKey: String,
    val snapshotText: String?,
    val branchBlame: Map<Int, BlameInfo>,
)

private sealed class DiffChoice {
    abstract val filterText: String

    data class Single(val entry: DiffEntry) : DiffChoice() {
        override val filterText: String = entry.branch.name
    }

    data class Group(val entries: List<DiffEntry>) : DiffChoice() {
        override val filterText: String = entries.joinToString(" ") { it.branch.name }
        val hasIdenticalFullDiff: Boolean =
            entries.map { it.snapshotText }.distinct().size == 1 &&
                entries.map { it.branchPath }.distinct().size == 1 &&
                entries.map { it.branchBlame }.distinct().size == 1
    }
}

private class DiffChoiceRenderer : ColoredListCellRenderer<DiffChoice>() {
    private val entryRenderer = DiffEntryTextRenderer()

    override fun customizeCellRenderer(
        list: JList<out DiffChoice>,
        value: DiffChoice,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean,
    ) {
        when (value) {
            is DiffChoice.Single -> entryRenderer.appendTo(this, value.entry)
            is DiffChoice.Group -> {
                val entries = value.entries
                append("${entries.size} branches", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                append("  ${entries.first().kindLabel}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                if (value.hasIdenticalFullDiff) {
                    append("  · identical full diff", SimpleTextAttributes.GRAY_ITALIC_ATTRIBUTES)
                }
                val names = entries.take(3).joinToString(", ") { it.branch.displayName }
                val remainder = entries.size - 3
                append(
                    "  · $names${if (remainder > 0) ", +$remainder" else ""}",
                    SimpleTextAttributes.GRAYED_ATTRIBUTES,
                )
                val commonBlame = entries.map { it.blame }.distinct().singleOrNull()
                entryRenderer.appendBlame(this, commonBlame, entries.first().branch)
            }
        }
    }
}

private class DiffEntryRenderer : ColoredListCellRenderer<DiffEntry>() {
    private val textRenderer = DiffEntryTextRenderer()

    override fun customizeCellRenderer(
        list: JList<out DiffEntry>,
        value: DiffEntry,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean,
    ) {
        textRenderer.appendTo(this, value)
    }
}

private class DiffEntryTextRenderer {
    private val dateFmt: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

    fun appendTo(renderer: ColoredListCellRenderer<*>, value: DiffEntry) = with(renderer) {
        append(value.branch.displayName, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
        append("  ${value.kindLabel}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        value.branchPath?.let { append("  · $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
        appendBlame(renderer, value.blame, value.branch)
    }

    fun appendBlame(
        renderer: ColoredListCellRenderer<*>,
        blame: BlameInfo?,
        branch: LocalBranch,
    ) = with(renderer) {
        if (blame != null) {
            if (blame.isUncommitted) {
                append("  — uncommitted working-tree change", SimpleTextAttributes.GRAY_ITALIC_ATTRIBUTES)
                return@with
            }
            val author = blame.author ?: branch.authorName
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
        } else if (branch.authorName != null) {
            append("  — ${branch.authorName}", SimpleTextAttributes.GRAY_ITALIC_ATTRIBUTES)
        }
    }
}
