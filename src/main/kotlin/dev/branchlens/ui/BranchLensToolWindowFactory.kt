package dev.branchlens.ui

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.diff.util.Side
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.util.Pair
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
import dev.branchlens.diff.BRANCH_LENS_DIFF_ANNOTATIONS
import dev.branchlens.diff.BranchLensDiffAnnotationData
import dev.branchlens.diff.ChangeRegion
import dev.branchlens.diff.ChangeRegionGrouper
import dev.branchlens.model.BlameInfo
import dev.branchlens.model.BranchLineDifference
import dev.branchlens.model.ChangeLineage
import dev.branchlens.model.Confidence
import dev.branchlens.model.FileAnalysisResult
import dev.branchlens.model.LineSummary
import dev.branchlens.model.LocalBranch
import dev.branchlens.model.displayName
import dev.branchlens.model.isUncommitted
import dev.branchlens.model.trackingStatus
import dev.branchlens.settings.BranchLensProjectSettings
import dev.branchlens.settings.BranchScopeMode
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import com.intellij.openapi.ide.CopyPasteManager

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
    private val branchStatusLabel = JBLabel(" ", SwingConstants.LEFT).apply {
        border = JBUI.Borders.empty(6, 8, 0, 8)
        isVisible = false
    }

    private val service = project.service<BranchLensProjectService>()
    private val projectSettings = BranchLensProjectSettings.getInstance(project)
    private val listenerDisposer: com.intellij.openapi.Disposable
    private var groupByChange = true
    private var lastEditor: Editor? = null
    private var lastFile: VirtualFile? = null
    private var lastResult: FileAnalysisResult? = null

    init {
        val toolbar = buildToolbar()
        add(toolbar.component, BorderLayout.NORTH)

        val center = JPanel(BorderLayout())
        val header = JPanel(java.awt.GridLayout(0, 1)).apply {
            add(branchStatusLabel)
            add(statusLabel)
        }
        center.add(header, BorderLayout.NORTH)
        center.add(JBScrollPane(list), BorderLayout.CENTER)
        add(center, BorderLayout.CENTER)

        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    showRowActions(e)
                    return
                }
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
        group.addSeparator()
        group.add(object : ToggleAction("By Change", "Group identical branch results by originating change", null) {
            override fun isSelected(e: AnActionEvent): Boolean = groupByChange

            override fun setSelected(e: AnActionEvent, state: Boolean) {
                groupByChange = state
                rebuild(lastEditor, lastFile, lastResult)
            }
        })
        BranchScopeMode.entries.forEach { mode -> group.add(ScopeModeAction(mode)) }
        group.add(object : ToggleAction("Remotes", "Include remote-tracking branches", AllIcons.Vcs.Branch) {
            override fun isSelected(e: AnActionEvent): Boolean =
                projectSettings.state.includeRemoteTrackingBranches

            override fun setSelected(e: AnActionEvent, state: Boolean) {
                projectSettings.update { includeRemoteTrackingBranches = state }
            }
        })
        group.addSeparator()
        group.add(object : AnAction("Branch Scope", "Configure branches analyzed by Branch Lens", AllIcons.General.Settings) {
            override fun actionPerformed(e: AnActionEvent) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, "Branch Lens")
            }
        })
        return ActionManager.getInstance()
            .createActionToolbar(ActionPlaces.TOOLWINDOW_CONTENT, group, true)
            .also { it.targetComponent = this }
    }

    private fun rebuild(editor: Editor?, file: VirtualFile?, result: FileAnalysisResult?) {
        lastEditor = editor
        lastFile = file
        lastResult = result
        model.clear()
        branchStatusLabel.isVisible = false
        when {
            editor == null || file == null -> statusLabel.text = "Open a text file in a Git repo to see branch differences."
            result == null -> statusLabel.text = "${file.name}: analyzing…"
            result is FileAnalysisResult.Skipped ->
                statusLabel.text = "${file.name}: skipped (${result.reason.name.lowercase().replace('_', ' ')})"
            result is FileAnalysisResult.Computed -> {
                result.currentBranch?.trackingStatus?.let { status ->
                    branchStatusLabel.text = status
                    branchStatusLabel.icon = AllIcons.Vcs.Branch
                    branchStatusLabel.isVisible = true
                }
                val rows = entriesFor(result, editor, file)
                rows.forEach(model::addElement)
                val uniqueLines = (result.perLineDifferences.keys + result.insertionsAfter.keys).size
                val affectedBranches = (
                    result.perLineDifferences.values.flatten().map { it.branch.name } +
                        result.insertionsAfter.values.flatten().map { it.branch.name }
                    ).toSet().size
                statusLabel.text = if (uniqueLines == 0) {
                    "${file.name}: no branch differences"
                } else {
                    "${file.name}: $uniqueLines differing line${if (uniqueLines == 1) "" else "s"} across " +
                        "$affectedBranches branch${if (affectedBranches == 1) "" else "es"}"
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
        return if (groupByChange) entriesByChange(result, editor, file) else entriesByBranch(result, editor, file)
    }

    private fun entriesByBranch(
        result: FileAnalysisResult.Computed,
        editor: Editor,
        file: VirtualFile,
    ): List<Row> {
        val lineRows = mutableListOf<Row.LineRow>()
        val touchedLines = (result.perLineDifferences.keys + result.insertionsAfter.keys).sorted()
        for (line in touchedLines) {
            val summary = result.summaryForLine(line) ?: continue
            for (diff in summary.differences) {
                lineRows += Row.LineRow(
                    editor = editor,
                    file = file,
                    currentLine = line,
                    branch = diff.branch,
                    kindLabel = kindLabel(diff, summary.lineageFor(diff)),
                    blame = summary.blameFor(diff),
                    branchText = result.branchContents[diff.branch.name],
                    branchPath = result.branchPaths[diff.branch.name],
                    currentAnnotations = result.currentBlame,
                    branchAnnotations = result.branchBlames[diff.branch.name].orEmpty(),
                    repoRoot = result.repoRoot,
                )
            }
            for (insertion in summary.insertions) {
                lineRows += Row.LineRow(
                    editor = editor,
                    file = file,
                    currentLine = line,
                    branch = insertion.branch,
                    kindLabel = kindLabel(insertion, summary.lineageFor(insertion)),
                    blame = summary.blameFor(insertion),
                    branchText = result.branchContents[insertion.branch.name],
                    branchPath = result.branchPaths[insertion.branch.name],
                    currentAnnotations = result.currentBlame,
                    branchAnnotations = result.branchBlames[insertion.branch.name].orEmpty(),
                    repoRoot = result.repoRoot,
                )
            }
        }
        return lineRows
            .distinctBy { listOf(it.currentLine, it.branch.name, it.kindLabel) }
            .groupBy { it.branch.name }
            .toSortedMap()
            .flatMap { (_, branchRows) ->
                listOf<Row>(Row.BranchHeader(branchRows.first().branch.displayName, branchRows.size)) +
                    branchRows.sortedWith(compareBy<Row.LineRow> { it.currentLine }.thenBy { it.kindLabel })
            }
    }

    private fun entriesByChange(
        result: FileAnalysisResult.Computed,
        editor: Editor,
        file: VirtualFile,
    ): List<Row> {
        val regions = ChangeRegionGrouper.group(result)
        val grouped = regions.groupBy { region ->
            originFor(region, result)?.let { "commit:${it.commitHash}" }
                ?: "mixed:${region.lines.first}:${region.lines.last}"
        }
        return grouped.values.flatMap { changeRegions ->
            val origin = originFor(changeRegions.first(), result)
            val childRows = changeRegions.flatMap { region ->
                val summary = region.summary
                buildList {
                    summary.differences.forEach { diff ->
                        add(
                            lineRow(
                                result, editor, file, region.lines.first, diff.branch,
                                kindLabel(diff, summary.lineageFor(diff)), summary.blameFor(diff), indented = true,
                            ),
                        )
                    }
                    summary.insertions.forEach { insertion ->
                        add(
                            lineRow(
                                result, editor, file, region.lines.first, insertion.branch,
                                kindLabel(insertion, summary.lineageFor(insertion)),
                                summary.blameFor(insertion), indented = true,
                            ),
                        )
                    }
                }
            }.distinctBy { it.currentLine to it.branch.name }
                .sortedWith(compareBy<Row.LineRow> { it.currentLine }.thenBy { it.branch.displayName })

            val affected = childRows.map { it.branch.name }.distinct()
            val total = result.analyzedBranches.size.coerceAtLeast(result.branchCount)
            val contained = origin?.takeUnless { it.isUncommitted }
                ?.let { result.commitContainment[it.commitHash]?.size }
            val title = when {
                origin == null -> "Mixed-history change"
                origin.isUncommitted -> "Working-tree change"
                !origin.summary.isNullOrBlank() -> origin.summary
                else -> "Commit ${origin.commitHash.take(8)}"
            }
            val ranges = changeRegions.map { region ->
                if (region.lines.first == region.lines.last) "line ${region.lines.first}"
                else "lines ${region.lines.first}–${region.lines.last}"
            }
            val lineLabel = ranges.take(4).joinToString(", ") +
                if (ranges.size > 4) ", +${ranges.size - 4} more" else ""
            val propagation = when {
                origin == null -> "origin varies across the block"
                origin.isUncommitted -> "not committed yet"
                contained != null -> "commit present on $contained/$total selected branches"
                else -> "commit propagation unavailable"
            }
            listOf<Row>(
                Row.ChangeHeader(
                    editor = editor,
                    file = file,
                    startLine = changeRegions.first().lines.first,
                    title = title,
                    detail = "$lineLabel · differs on ${affected.size}/$total selected branches · $propagation",
                    blame = origin,
                    repoRoot = result.repoRoot,
                    branchNames = affected,
                ),
            ) + childRows
        }
    }

    private fun lineRow(
        result: FileAnalysisResult.Computed,
        editor: Editor,
        file: VirtualFile,
        currentLine: Int,
        branch: LocalBranch,
        kindLabel: String,
        blame: BlameInfo?,
        indented: Boolean,
    ) = Row.LineRow(
        editor = editor,
        file = file,
        currentLine = currentLine,
        branch = branch,
        kindLabel = kindLabel,
        blame = blame,
        branchText = result.branchContents[branch.name],
        branchPath = result.branchPaths[branch.name],
        currentAnnotations = result.currentBlame,
        branchAnnotations = result.branchBlames[branch.name].orEmpty(),
        repoRoot = result.repoRoot,
        indented = indented,
    )

    private fun originFor(region: ChangeRegion, result: FileAnalysisResult.Computed): BlameInfo? {
        val currentOrigins = region.lines.mapNotNull(result.currentBlame::get)
            .distinctBy { it.commitHash }
        if (currentOrigins.size == 1) return currentOrigins.single()
        if (currentOrigins.size > 1) return null
        val branchOrigins = (region.summary.differences.mapNotNull(region.summary::blameFor) +
            region.summary.insertions.mapNotNull(region.summary::blameFor))
            .distinctBy { it.commitHash }
        return branchOrigins.singleOrNull()
    }

    private fun onRowActivated(row: Row, openDiff: Boolean) {
        when (row) {
            is Row.BranchHeader -> Unit
            is Row.ChangeHeader -> jumpToLine(row.editor, row.file, row.startLine)
            is Row.LineRow -> {
                jumpToLine(row)
                if (openDiff) openDiffFor(row)
            }
        }
    }

    private fun jumpToLine(row: Row.LineRow) {
        jumpToLine(row.editor, row.file, row.currentLine)
    }

    private fun jumpToLine(editor: Editor, file: VirtualFile, currentLine: Int) {
        val manager = FileEditorManager.getInstance(project)
        manager.openFile(file, true)
        if (!editor.isDisposed) {
            val zero = (currentLine - 1).coerceAtLeast(0).coerceAtMost(editor.document.lineCount - 1)
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
            "Branch Lens — ${row.file.name}: current vs ${row.branch.displayName}",
            currentContent,
            branchContent,
            "${row.file.name}  (current)",
            "${row.branchPath ?: row.file.name}  @ ${row.branch.displayName}",
        )
        request.putUserData(
            DiffUserDataKeys.SCROLL_TO_LINE,
            Pair.create(Side.LEFT, (row.currentLine - 1).coerceAtLeast(0)),
        )
        request.putUserData(
            BRANCH_LENS_DIFF_ANNOTATIONS,
            BranchLensDiffAnnotationData(
                current = row.currentAnnotations,
                branch = row.branchAnnotations,
                repoRoot = row.repoRoot,
            ),
        )
        try {
            DiffManager.getInstance().showDiff(project, request)
        } catch (t: Throwable) {
            log.warn("Branch Lens tool-window diff open failed: ${t.message}", t)
        }
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

    private fun showRowActions(e: MouseEvent) {
        val index = list.locationToIndex(e.point)
        if (index < 0) return
        if (list.getCellBounds(index, index)?.contains(e.point) != true) return
        list.selectedIndex = index
        val row = model.getElementAt(index)
        val group = DefaultActionGroup()
        when (row) {
            is Row.ChangeHeader -> {
                CommitUiActions.actions(project, row.repoRoot, row.blame).forEach(group::add)
                if (row.branchNames.isNotEmpty()) {
                    group.add(object : AnAction("Copy Affected Branch Names") {
                        override fun actionPerformed(event: AnActionEvent) {
                            CopyPasteManager.getInstance().setContents(
                                StringSelection(row.branchNames.joinToString("\n")),
                            )
                        }
                    })
                }
            }
            is Row.LineRow -> CommitUiActions.actions(project, row.repoRoot, row.blame).forEach(group::add)
            is Row.BranchHeader -> return
        }
        if (group.childActionsOrStubs.isEmpty()) return
        ActionManager.getInstance().createActionPopupMenu(ActionPlaces.TOOLWINDOW_POPUP, group)
            .component.show(list, e.x, e.y)
    }

    override fun dispose() {
        // listenerDisposer is already registered with `Disposer.register(this, …)`
    }

    private sealed class Row {
        data class BranchHeader(val branchName: String, val differenceCount: Int) : Row()

        data class ChangeHeader(
            val editor: Editor,
            val file: VirtualFile,
            val startLine: Int,
            val title: String,
            val detail: String,
            val blame: BlameInfo?,
            val repoRoot: java.nio.file.Path?,
            val branchNames: List<String>,
        ) : Row()

        data class LineRow(
            val editor: Editor,
            val file: VirtualFile,
            val currentLine: Int,
            val branch: LocalBranch,
            val kindLabel: String,
            val blame: BlameInfo?,
            val branchText: String?,
            val branchPath: String?,
            val currentAnnotations: Map<Int, BlameInfo>,
            val branchAnnotations: Map<Int, BlameInfo>,
            val repoRoot: java.nio.file.Path?,
            val indented: Boolean = false,
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
                is Row.ChangeHeader -> {
                    icon = AllIcons.Vcs.CommitNode
                    val shortHash = value.blame?.takeUnless { it.isUncommitted }?.commitHash?.take(8)
                    if (shortHash != null) append("$shortHash  ", SimpleTextAttributes.GRAY_ATTRIBUTES)
                    append(value.title.take(80), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append("  ${value.detail}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is Row.BranchHeader -> {
                    icon = AllIcons.Vcs.Branch
                    append(value.branchName, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append(
                        "  ${value.differenceCount} difference${if (value.differenceCount == 1) "" else "s"}",
                        SimpleTextAttributes.GRAYED_ATTRIBUTES,
                    )
                }
                is Row.LineRow -> {
                    if (value.indented) append("    ")
                    append("L${value.currentLine}", SimpleTextAttributes.GRAY_ATTRIBUTES)
                    append("  ")
                    append(value.branch.displayName, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append("  ")
                    append(value.kindLabel, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    val blame = value.blame
                    if (blame != null) {
                        if (blame.isUncommitted) {
                            append("  — uncommitted working-tree change", SimpleTextAttributes.GRAY_ITALIC_ATTRIBUTES)
                            return
                        }
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

    private inner class ScopeModeAction(private val mode: BranchScopeMode) :
        ToggleAction(mode.name.lowercase().replaceFirstChar(Char::uppercase), "Analyze ${mode.name.lowercase()} branches", null) {
        override fun isSelected(e: AnActionEvent): Boolean = projectSettings.state.branchScopeMode == mode

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            if (state) projectSettings.update { branchScopeMode = mode }
        }
    }

    private fun insertionKindLabel(
        insertion: BranchLineDifference.BranchInsertionAfterCurrentLine,
    ): String {
        val count = insertion.branchText.size
        val lines = "$count line${if (count == 1) "" else "s"}"
        return if (insertion.beforeFirstLine) "inserts $lines before line 1" else "inserts $lines"
    }
}
