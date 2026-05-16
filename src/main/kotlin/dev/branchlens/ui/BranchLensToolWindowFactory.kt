package dev.branchlens.ui

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import dev.branchlens.BranchLensProjectService
import dev.branchlens.model.BlameInfo
import dev.branchlens.model.BranchLineDifference
import dev.branchlens.model.Confidence
import dev.branchlens.model.FileAnalysisResult
import dev.branchlens.model.LineSummary
import dev.branchlens.model.LocalBranch
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.SwingConstants

class BranchLensToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = BranchLensToolWindowPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}

internal class BranchLensToolWindowPanel(private val project: Project) :
    JPanel(BorderLayout()), com.intellij.openapi.Disposable {

    private val log = thisLogger()

    private val model = DefaultListModel<Row>()
    private val list = JBList(model).apply {
        cellRenderer = RowRenderer()
        selectionMode = javax.swing.ListSelectionModel.SINGLE_SELECTION
    }
    private val statusLabel = JBLabel(" ", SwingConstants.LEFT).apply {
        border = JBUI.Borders.empty(4, 8)
    }

    private val service = project.service<BranchLensProjectService>()
    private val listenerDisposer: com.intellij.openapi.Disposable

    init {
        val toolbar = buildToolbar()
        add(toolbar.component, BorderLayout.NORTH)

        val center = JPanel(BorderLayout())
        center.add(statusLabel, BorderLayout.NORTH)
        center.add(JBScrollPane(list), BorderLayout.CENTER)
        add(center, BorderLayout.CENTER)

        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount < 2) return
                val row = list.selectedValue ?: return
                onRowActivated(row, openDiff = true)
            }
        })
        list.addListSelectionListener { e ->
            if (e.valueIsAdjusting) return@addListSelectionListener
            val row = list.selectedValue ?: return@addListSelectionListener
            onRowActivated(row, openDiff = false)
        }

        listenerDisposer = service.addResultListener { editor, file, result ->
            javax.swing.SwingUtilities.invokeLater { rebuild(editor, file, result) }
        }
        com.intellij.openapi.util.Disposer.register(this, listenerDisposer)
    }

    private fun buildToolbar(): ActionToolbar {
        val group = DefaultActionGroup()
        group.add(object : AnAction("Refresh", "Re-run Branch Lens analysis", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) {
                service.refreshActive()
            }
        })
        return ActionManager.getInstance()
            .createActionToolbar(ActionPlaces.TOOLWINDOW_CONTENT, group, true)
            .also { it.targetComponent = this }
    }

    private fun rebuild(editor: Editor?, file: VirtualFile?, result: FileAnalysisResult?) {
        model.clear()
        when {
            editor == null || file == null -> statusLabel.text = "Open a text file in a Git repo to see branch differences."
            result == null -> statusLabel.text = "${file.name}: analyzing…"
            result is FileAnalysisResult.Skipped ->
                statusLabel.text = "${file.name}: skipped (${result.reason.name.lowercase().replace('_', ' ')})"
            result is FileAnalysisResult.Computed -> {
                val rows = entriesFor(result, editor, file)
                rows.forEach(model::addElement)
                statusLabel.text = if (rows.isEmpty()) {
                    "${file.name}: no diverging local branches"
                } else {
                    "${file.name}: ${rows.size} differing line${if (rows.size == 1) "" else "s"} across ${result.branchCount} branch${if (result.branchCount == 1) "" else "es"}"
                }
            }
            else -> statusLabel.text = "${file.name}: idle"
        }
    }

    private fun entriesFor(
        result: FileAnalysisResult.Computed,
        editor: Editor,
        file: VirtualFile,
    ): List<Row> {
        val rows = mutableListOf<Row>()
        val touchedLines = (result.perLineDifferences.keys + result.insertionsAfter.keys).sorted()
        for (line in touchedLines) {
            val summary = result.summaryForLine(line) ?: continue
            for (diff in summary.differences) {
                rows += Row.LineRow(
                    editor = editor,
                    file = file,
                    currentLine = line,
                    branch = diff.branch,
                    kindLabel = kindLabel(diff),
                    blame = summary.blameFor(diff),
                    branchText = result.branchContents[diff.branch.name],
                )
            }
            for (insertion in summary.insertions) {
                rows += Row.LineRow(
                    editor = editor,
                    file = file,
                    currentLine = line,
                    branch = insertion.branch,
                    kindLabel = "inserts ${insertion.branchText.size} line${if (insertion.branchText.size == 1) "" else "s"}",
                    blame = summary.blameFor(insertion),
                    branchText = result.branchContents[insertion.branch.name],
                )
            }
        }
        // Files entirely missing in a branch — show one row per branch.
        for (missing in result.missingInBranches) {
            rows += Row.LineRow(
                editor = editor,
                file = file,
                currentLine = 1,
                branch = missing.branch,
                kindLabel = "file missing",
                blame = null,
                branchText = null,
            )
        }
        return rows
    }

    private fun onRowActivated(row: Row, openDiff: Boolean) {
        when (row) {
            is Row.LineRow -> {
                jumpToLine(row)
                if (openDiff) openDiffFor(row)
            }
        }
    }

    private fun jumpToLine(row: Row.LineRow) {
        val manager = FileEditorManager.getInstance(project)
        manager.openFile(row.file, true)
        val editor = row.editor
        if (!editor.isDisposed) {
            val zero = (row.currentLine - 1).coerceAtLeast(0).coerceAtMost(editor.document.lineCount - 1)
            val pos = com.intellij.openapi.editor.LogicalPosition(zero, 0)
            editor.caretModel.moveToLogicalPosition(pos)
            editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
        }
    }

    private fun openDiffFor(row: Row.LineRow) {
        val factory = DiffContentFactory.getInstance()
        val fileType = row.file.fileType
        val currentContent = factory.create(project, row.editor.document.text, fileType)
        val branchContent = if (row.branchText != null) {
            factory.create(project, row.branchText, fileType)
        } else {
            factory.createEmpty()
        }
        val request = SimpleDiffRequest(
            "Branch Lens — ${row.file.name}: current vs ${row.branch.name}",
            currentContent,
            branchContent,
            "${row.file.name}  (current)",
            "${row.file.name}  @ ${row.branch.name}",
        )
        try {
            DiffManager.getInstance().showDiff(project, request)
        } catch (t: Throwable) {
            log.warn("Branch Lens tool-window diff open failed: ${t.message}", t)
        }
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

    override fun dispose() {
        // listenerDisposer is already registered with `Disposer.register(this, …)`
    }

    private sealed class Row {
        data class LineRow(
            val editor: Editor,
            val file: VirtualFile,
            val currentLine: Int,
            val branch: LocalBranch,
            val kindLabel: String,
            val blame: BlameInfo?,
            val branchText: String?,
        ) : Row()
    }

    private class RowRenderer : ColoredListCellRenderer<Row>() {
        private val dateFmt: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

        override fun customizeCellRenderer(
            list: JList<out Row>,
            value: Row,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean,
        ) {
            when (value) {
                is Row.LineRow -> {
                    append("L${value.currentLine}", SimpleTextAttributes.GRAY_ATTRIBUTES)
                    append("  ")
                    append(value.branch.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append("  ")
                    append(value.kindLabel, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    val blame = value.blame
                    if (blame != null) {
                        val author = blame.author
                        val time = blame.authorTimeEpochSeconds
                        val pieces = buildList {
                            author?.let { add(it) }
                            time?.let { add(dateFmt.format(Instant.ofEpochSecond(it))) }
                        }
                        if (pieces.isNotEmpty()) {
                            append("  — ${pieces.joinToString(", ")}", SimpleTextAttributes.GRAY_ITALIC_ATTRIBUTES)
                        }
                        blame.summary?.let { append("  · ${it.take(60)}", SimpleTextAttributes.GRAY_ITALIC_ATTRIBUTES) }
                    }
                }
            }
        }
    }
}

