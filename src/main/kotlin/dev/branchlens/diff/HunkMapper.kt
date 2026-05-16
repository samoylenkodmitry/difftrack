package dev.branchlens.diff

import dev.branchlens.model.BranchLineDifference
import dev.branchlens.model.Confidence
import dev.branchlens.model.LocalBranch

/**
 * Converts parsed unified-diff hunks into conservative [BranchLineDifference] records.
 *
 * The diff was produced with current snapshot on the "old" side and branch blob on the
 * "new" side, so a `-` line means *the current line is absent/different in the branch*
 * and a `+` line means *the branch has an additional line not present in the current file*.
 */
object HunkMapper {
    fun map(branch: LocalBranch, diff: UnifiedDiff): List<BranchLineDifference> {
        val out = mutableListOf<BranchLineDifference>()
        for (hunk in diff.hunks) {
            mapHunk(branch, hunk, out)
        }
        return out
    }

    private fun mapHunk(branch: LocalBranch, hunk: DiffHunk, out: MutableList<BranchLineDifference>) {
        // Split the hunk body into edit groups separated by Context lines.
        val groups = mutableListOf<MutableList<DiffLine>>()
        var current: MutableList<DiffLine>? = null
        for (line in hunk.lines) {
            if (line is DiffLine.Context) {
                current = null
                continue
            }
            if (current == null) {
                current = mutableListOf()
                groups += current
            }
            current.add(line)
        }

        for (group in groups) {
            val removed = group.filterIsInstance<DiffLine.Removed>()
            val added = group.filterIsInstance<DiffLine.Added>()

            when {
                removed.size == 1 && added.size == 1 -> {
                    out += BranchLineDifference.ReplacedLine(
                        branch = branch,
                        currentLine = removed[0].oldLine,
                        branchLine = added[0].newLine,
                        currentText = removed[0].text,
                        branchText = added[0].text,
                        confidence = Confidence.HIGH,
                    )
                }
                removed.size > 1 && added.size > 1 && removed.size == added.size -> {
                    for (i in removed.indices) {
                        out += BranchLineDifference.ReplacedLine(
                            branch = branch,
                            currentLine = removed[i].oldLine,
                            branchLine = added[i].newLine,
                            currentText = removed[i].text,
                            branchText = added[i].text,
                            confidence = Confidence.MEDIUM,
                        )
                    }
                }
                removed.isNotEmpty() && added.isNotEmpty() -> {
                    val currentRange = removed.first().oldLine..removed.last().oldLine
                    val branchRange = added.first().newLine..added.last().newLine
                    val currentTexts = removed.map { it.text }
                    val branchTexts = added.map { it.text }
                    for (r in removed) {
                        out += BranchLineDifference.ChangedBlock(
                            branch = branch,
                            currentLines = currentRange,
                            branchLines = branchRange,
                            currentText = currentTexts,
                            branchText = branchTexts,
                            confidence = Confidence.BLOCK_ONLY,
                        )
                    }
                }
                removed.isNotEmpty() && added.isEmpty() -> {
                    for (r in removed) {
                        out += BranchLineDifference.DeletedInBranch(
                            branch = branch,
                            currentLine = r.oldLine,
                            currentText = r.text,
                        )
                    }
                }
                removed.isEmpty() && added.isNotEmpty() -> {
                    val anchor = (hunk.oldStart - 1).coerceAtLeast(0)
                    val branchRange = added.first().newLine..added.last().newLine
                    out += BranchLineDifference.BranchInsertionAfterCurrentLine(
                        branch = branch,
                        anchorCurrentLine = anchor,
                        branchLines = branchRange,
                        branchText = added.map { it.text },
                    )
                }
            }
        }
    }
}
