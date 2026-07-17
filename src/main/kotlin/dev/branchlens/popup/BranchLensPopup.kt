package dev.branchlens.popup

import dev.branchlens.model.BlameInfo
import dev.branchlens.model.BranchLineDifference
import dev.branchlens.model.Confidence
import dev.branchlens.model.LineSummary
import dev.branchlens.model.displayName
import dev.branchlens.model.isUncommitted
import dev.branchlens.diff.outcomeFingerprint
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
    fun renderTooltip(
        summary: LineSummary,
        lineRange: IntRange = summary.currentLine..summary.currentLine,
    ): String {
        val sb = StringBuilder("<html><body style='margin:4px 6px;'>")
        sb.append("<b>")
            .append(escape(headerText(summary, lineRange)))
            .append("</b><br><br>")

        val byBranch = LinkedHashMap<String, TooltipEntry>()
        for (diff in summary.differences) {
            byBranch.putIfAbsent(
                diff.branch.name,
                TooltipEntry(
                    diff.branch.displayName,
                    kindLabel(summary, diff),
                    summary.blameFor(diff),
                    summary.branchPaths[diff.branch.name],
                    diff.outcomeFingerprint(),
                ),
            )
        }
        for (insertion in summary.insertions) {
            byBranch.putIfAbsent(
                insertion.branch.name,
                TooltipEntry(
                    insertion.branch.displayName,
                    insertionKindLabel(insertion),
                    summary.blameFor(insertion),
                    summary.branchPaths[insertion.branch.name],
                    insertion.outcomeFingerprint(),
                ),
            )
        }
        for (group in byBranch.values.groupBy { it.outcomeKey }.values) {
            if (group.size == 1) {
                val entry = group.single()
                appendBranchRow(sb, entry.branchName, entry.kind, entry.blame, entry.branchPath)
            } else {
                appendGroupedBranchRow(sb, group)
            }
        }
        sb.append("</body></html>")
        return sb.toString()
    }

    private data class TooltipEntry(
        val branchName: String,
        val kind: String,
        val blame: BlameInfo?,
        val branchPath: String?,
        val outcomeKey: String,
    )

    private fun appendGroupedBranchRow(sb: StringBuilder, entries: List<TooltipEntry>) {
        sb.append("<b>${entries.size} branches</b>")
            .append(" &mdash; ")
            .append(escape(entries.first().kind))
        val shown = entries.take(5).joinToString(", ") { it.branchName }
        val remaining = entries.size - 5
        sb.append("<br><span style='color:gray;'>")
            .append(escape(shown))
        if (remaining > 0) sb.append(", +").append(remaining).append(" more")
        sb.append("</span>")
        val commonBlame = entries.map { it.blame }.distinct().singleOrNull()
        appendBlame(sb, commonBlame)
        sb.append("<br><br>")
    }

    /**
     * Plain-text "Copy summary" payload — used by chooser menu actions.
     */
    fun renderPlainText(
        summary: LineSummary,
        lineRange: IntRange = summary.currentLine..summary.currentLine,
    ): String = buildString {
        appendLine(headerText(summary, lineRange))
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

    private fun appendBranchRow(
        sb: StringBuilder,
        branchName: String,
        kind: String,
        blame: BlameInfo?,
        branchPath: String?,
    ) {
        sb.append("<b>").append(escape(branchName)).append("</b>")
        sb.append(" &mdash; ").append(escape(kind))
        branchPath?.let {
            sb.append("<br><span style='color:gray;'>Path: ")
                .append(escape(it))
                .append("</span>")
        }
        appendBlame(sb, blame)
        sb.append("<br><br>")
    }

    private fun appendBlame(sb: StringBuilder, blame: BlameInfo?) {
        if (blame == null) return
        sb.append("<br><span style='color:gray;'>")
        if (blame.isUncommitted) {
            sb.append("Uncommitted working-tree change</span>")
            return
        }
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

    private fun headerText(summary: LineSummary, lineRange: IntRange): String {
        val differing = summary.differences.map { it.branch.name }.toSet()
        val inserting = summary.insertions.map { it.branch.name }.toSet() - differing
        val total = differing.size + inserting.size
        val location = if (lineRange.first == lineRange.last) {
            "line ${lineRange.first}"
        } else {
            "lines ${lineRange.first}–${lineRange.last} (${lineRange.count()} lines)"
        }
        return "Different in $total selected branch${plural(total, "", "es")} — $location"
    }

    private fun kindLabel(summary: LineSummary, diff: BranchLineDifference): String {
        val lineage = summary.lineageFor(diff)
        if (lineage != dev.branchlens.model.ChangeLineage.UNKNOWN) return lineage.label
        return when (diff) {
        is BranchLineDifference.ReplacedLine -> when (diff.confidence) {
            Confidence.HIGH -> "replaced (high confidence)"
            Confidence.MEDIUM -> "replaced (in a multi-line edit)"
            Confidence.BLOCK_ONLY -> "changed block"
        }
        is BranchLineDifference.ChangedBlock -> "changed block (ambiguous line pairing)"
        is BranchLineDifference.DeletedInBranch -> "line absent in branch"
        is BranchLineDifference.BranchInsertionAfterCurrentLine ->
            insertionKindLabel(diff)
        is BranchLineDifference.FileMissingInBranch -> "file missing in branch"
        }
    }

    private fun renderDiffPlain(summary: LineSummary, diff: BranchLineDifference): String = buildString {
        appendLine(diff.branch.displayName)
        appendLine("  Kind: ${kindLabel(summary, diff)}")
        summary.branchPaths[diff.branch.name]?.let { appendLine("  Branch path: $it") }
        summary.blameFor(diff)?.let { blame ->
            if (blame.isUncommitted) {
                appendLine("  Attribution: uncommitted working-tree change")
            } else {
            blame.author?.let { appendLine("  Modified by: $it") }
            blame.authorTimeEpochSeconds?.let {
                appendLine("  Date: ${DATE_FMT.format(Instant.ofEpochSecond(it))}")
            }
            appendLine("  Commit: ${blame.commitHash.take(8)} ${blame.summary.orEmpty()}".trimEnd())
            }
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
        appendLine(insertion.branch.displayName)
        appendLine("  Kind: ${kindLabel(summary, insertion)}")
        summary.branchPaths[insertion.branch.name]?.let { appendLine("  Branch path: $it") }
        summary.blameFor(insertion)?.let { blame ->
            if (blame.isUncommitted) {
                appendLine("  Attribution: uncommitted working-tree change")
            } else {
            blame.author?.let { appendLine("  Modified by: $it") }
            blame.authorTimeEpochSeconds?.let {
                appendLine("  Date: ${DATE_FMT.format(Instant.ofEpochSecond(it))}")
            }
            appendLine("  Commit: ${blame.commitHash.take(8)} ${blame.summary.orEmpty()}".trimEnd())
            }
        }
        append("  Branch lines: ${insertion.branchLines.first}-${insertion.branchLines.last}")
    }

    private fun plural(n: Int, singular: String = "", plural: String = "s"): String =
        if (n == 1) singular else plural

    private fun insertionKindLabel(
        insertion: BranchLineDifference.BranchInsertionAfterCurrentLine,
    ): String {
        val lines = "${insertion.branchText.size} line${plural(insertion.branchText.size)}"
        return if (insertion.beforeFirstLine) "inserts $lines before line 1" else "inserts $lines"
    }

    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\n", "<br>")
}
