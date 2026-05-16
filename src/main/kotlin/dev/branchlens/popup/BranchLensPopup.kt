package dev.branchlens.popup

import dev.branchlens.model.BlameInfo
import dev.branchlens.model.BranchLineDifference
import dev.branchlens.model.Confidence
import dev.branchlens.model.LineSummary
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object BranchLensPopup {

    private val DATE_FMT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

    /**
     * HTML tooltip rendered on gutter-badge hover. Lists each branch with the
     * blame author + date for the branch-side line.
     */
    fun renderTooltip(summary: LineSummary): String {
        val sb = StringBuilder("<html><body style='margin:4px 6px;'>")
        sb.append("<b>")
            .append(escape(headerText(summary)))
            .append("</b><br><br>")

        val seenBranches = mutableSetOf<String>()
        for (diff in summary.differences) {
            if (!seenBranches.add(diff.branch.name)) continue
            appendBranchRow(sb, diff.branch.name, kindLabel(diff), summary.blameFor(diff))
        }
        for (insertion in summary.insertions) {
            if (!seenBranches.add(insertion.branch.name)) continue
            appendBranchRow(
                sb,
                insertion.branch.name,
                "inserts ${insertion.branchText.size} line${plural(insertion.branchText.size)}",
                summary.blameFor(insertion),
            )
        }
        sb.append("</body></html>")
        return sb.toString()
    }

    /**
     * Plain-text "Copy summary" payload — used by chooser menu actions.
     */
    fun renderPlainText(summary: LineSummary): String = buildString {
        appendLine(headerText(summary))
        appendLine()
        for (diff in summary.differences) {
            appendLine(renderDiffPlain(summary, diff))
            appendLine()
        }
        for (insertion in summary.insertions) {
            appendLine(renderInsertionPlain(summary, insertion))
            appendLine()
        }
    }.trim()

    private fun appendBranchRow(sb: StringBuilder, branchName: String, kind: String, blame: BlameInfo?) {
        sb.append("<b>").append(escape(branchName)).append("</b>")
        sb.append(" &mdash; ").append(escape(kind))
        if (blame != null) {
            sb.append("<br><span style='color:gray;'>")
            blame.author?.let { sb.append(escape(it)) }
            blame.authorTimeEpochSeconds?.let {
                if (blame.author != null) sb.append(", ")
                sb.append(escape(DATE_FMT.format(Instant.ofEpochSecond(it))))
            }
            blame.summary?.let { summary ->
                if (blame.author != null || blame.authorTimeEpochSeconds != null) sb.append(" — ")
                sb.append(escape(summary.take(80)))
            }
            sb.append("</span>")
        }
        sb.append("<br><br>")
    }

    private fun headerText(summary: LineSummary): String {
        val differing = summary.differences.map { it.branch.name }.toSet()
        val inserting = summary.insertions.map { it.branch.name }.toSet() - differing
        val total = differing.size + inserting.size
        return "Different in $total local branch${plural(total, "", "es")} — line ${summary.currentLine}"
    }

    private fun kindLabel(diff: BranchLineDifference): String = when (diff) {
        is BranchLineDifference.ReplacedLine -> when (diff.confidence) {
            Confidence.HIGH -> "replaced (high confidence)"
            Confidence.MEDIUM -> "replaced (in a multi-line edit)"
            Confidence.BLOCK_ONLY -> "changed block"
        }
        is BranchLineDifference.ChangedBlock -> "changed block (ambiguous line pairing)"
        is BranchLineDifference.DeletedInBranch -> "line absent in branch"
        is BranchLineDifference.BranchInsertionAfterCurrentLine ->
            "inserts ${diff.branchText.size} line${plural(diff.branchText.size)}"
        is BranchLineDifference.FileMissingInBranch -> "file missing in branch"
    }

    private fun renderDiffPlain(summary: LineSummary, diff: BranchLineDifference): String = buildString {
        appendLine(diff.branch.name)
        appendLine("  Kind: ${kindLabel(diff)}")
        summary.blameFor(diff)?.let { blame ->
            blame.author?.let { appendLine("  Modified by: $it") }
            blame.authorTimeEpochSeconds?.let {
                appendLine("  Date: ${DATE_FMT.format(Instant.ofEpochSecond(it))}")
            }
            appendLine("  Commit: ${blame.commitHash.take(8)} ${blame.summary.orEmpty()}".trimEnd())
        }
        when (diff) {
            is BranchLineDifference.ReplacedLine -> {
                appendLine("  Branch line: ${diff.branchLine}")
                appendLine("  Current: ${diff.currentText}")
                append("  Branch:  ${diff.branchText}")
            }
            is BranchLineDifference.ChangedBlock -> {
                diff.branchLines?.let { appendLine("  Branch lines: ${it.first}-${it.last}") }
                append("  Branch-side line pairing is ambiguous; see Diff for the full block.")
            }
            is BranchLineDifference.DeletedInBranch -> {
                append("  This line is absent in the branch; no branch-side line to blame.")
            }
            is BranchLineDifference.BranchInsertionAfterCurrentLine -> {
                append("  Branch lines: ${diff.branchLines.first}-${diff.branchLines.last}")
            }
            is BranchLineDifference.FileMissingInBranch -> {
                append("  File is missing entirely in this branch.")
            }
        }
    }

    private fun renderInsertionPlain(
        summary: LineSummary,
        insertion: BranchLineDifference.BranchInsertionAfterCurrentLine,
    ): String = buildString {
        appendLine(insertion.branch.name)
        appendLine("  Kind: ${kindLabel(insertion)}")
        summary.blameFor(insertion)?.let { blame ->
            blame.author?.let { appendLine("  Modified by: $it") }
            blame.authorTimeEpochSeconds?.let {
                appendLine("  Date: ${DATE_FMT.format(Instant.ofEpochSecond(it))}")
            }
            appendLine("  Commit: ${blame.commitHash.take(8)} ${blame.summary.orEmpty()}".trimEnd())
        }
        append("  Branch lines: ${insertion.branchLines.first}-${insertion.branchLines.last}")
    }

    private fun plural(n: Int, singular: String = "", plural: String = "s"): String =
        if (n == 1) singular else plural

    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\n", "<br>")
}
