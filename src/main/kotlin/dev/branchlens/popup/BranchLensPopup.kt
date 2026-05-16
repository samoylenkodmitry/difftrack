package dev.branchlens.popup

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import dev.branchlens.model.BranchLineDifference
import dev.branchlens.model.Confidence
import dev.branchlens.model.LineSummary
import java.awt.BorderLayout
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

object BranchLensPopup {

    fun buildPanel(@Suppress("UNUSED_PARAMETER") project: Project?, summary: LineSummary, actions: ActionGroup): JComponent {
        val root = JPanel(BorderLayout())
        val list = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }

        val header = JBLabel(headerText(summary), SwingConstants.LEFT)
        header.border = JBUI.Borders.empty(6, 8, 4, 8)
        list.add(header)

        for (diff in summary.differences) {
            list.add(rowFor(diff))
        }
        for (insertion in summary.insertions) {
            list.add(rowFor(insertion))
        }

        val toolbar = ActionManager.getInstance()
            .createActionToolbar(ActionPlaces.POPUP, actions, true)
        toolbar.targetComponent = list
        root.add(toolbar.component, BorderLayout.NORTH)
        root.add(JBScrollPane(list), BorderLayout.CENTER)
        root.preferredSize = JBUI.size(420, 280)
        return root
    }

    fun renderTooltip(summary: LineSummary): String {
        val branches = summary.differences.map { it.branch.name }.toSet() +
            summary.insertions.map { it.branch.name }.toSet()
        val verb = when {
            summary.differences.all { it is BranchLineDifference.FileMissingInBranch } &&
                summary.insertions.isEmpty() -> "file missing"
            summary.differences.isEmpty() -> "branches insert lines here"
            summary.differences.any {
                it is BranchLineDifference.ChangedBlock && it.confidence == Confidence.BLOCK_ONLY
            } -> "changed block in"
            else -> "different in"
        }
        val names = branches.take(5).joinToString(", ")
        val tail = if (branches.size > 5) " and ${branches.size - 5} more" else ""
        return "Branch Lens: $verb ${branches.size} branch${if (branches.size == 1) "" else "es"}: $names$tail"
    }

    fun renderPlainText(summary: LineSummary): String = buildString {
        appendLine(headerText(summary))
        for (diff in summary.differences) {
            appendLine(rowText(diff))
            appendLine()
        }
        for (insertion in summary.insertions) {
            appendLine(rowText(insertion))
            appendLine()
        }
    }.trim()

    private fun headerText(summary: LineSummary): String {
        val differing = summary.differences.map { it.branch.name }.toSet()
        val inserting = summary.insertions.map { it.branch.name }.toSet() - differing
        val total = differing.size + inserting.size
        return "Different in $total local branch${if (total == 1) "" else "es"} (line ${summary.currentLine})"
    }

    private fun rowFor(diff: BranchLineDifference): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = JBUI.Borders.empty(4, 8)
        for (line in rowText(diff).split('\n')) {
            panel.add(JBLabel(line))
        }
        return panel
    }

    private fun rowText(diff: BranchLineDifference): String = when (diff) {
        is BranchLineDifference.ReplacedLine -> buildString {
            appendLine("${diff.branch.name}")
            appendLine("  Confidence: ${diff.confidence.name.lowercase()}")
            appendLine("  Branch line: ${diff.branchLine}")
            appendLine("  Current: ${diff.currentText}")
            append("  Branch:  ${diff.branchText}")
        }
        is BranchLineDifference.ChangedBlock -> buildString {
            appendLine("${diff.branch.name}")
            appendLine("  Confidence: changed block")
            val br = diff.branchLines
            if (br != null) appendLine("  Branch lines: ${br.first}-${br.last}")
            appendLine("  This current line belongs to a changed block;")
            append("  exact branch-line pairing is ambiguous.")
        }
        is BranchLineDifference.DeletedInBranch -> buildString {
            appendLine("${diff.branch.name}")
            appendLine("  This line is absent in the branch.")
            append("  No branch-side line exists to blame.")
        }
        is BranchLineDifference.BranchInsertionAfterCurrentLine -> buildString {
            appendLine("${diff.branch.name}")
            appendLine("  Branch inserts ${diff.branchText.size} line${if (diff.branchText.size == 1) "" else "s"} after this line.")
            append("  Branch lines: ${diff.branchLines.first}-${diff.branchLines.last}")
        }
        is BranchLineDifference.FileMissingInBranch -> buildString {
            appendLine("${diff.branch.name}")
            append("  File is missing in this branch.")
        }
    }

    @Suppress("unused")
    private fun formatTime(epochSeconds: Long?): String {
        if (epochSeconds == null) return ""
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochSecond(epochSeconds))
    }
}
