package dev.branchlens.diff

import dev.branchlens.model.FileAnalysisResult
import dev.branchlens.model.LineSummary

data class ChangeRegion(
    val lines: IntRange,
    val summary: LineSummary,
)

/** Coalesces consecutive lines produced by the same branches and commit-level change. */
object ChangeRegionGrouper {
    fun group(result: FileAnalysisResult.Computed): List<ChangeRegion> {
        val lines = (result.perLineDifferences.keys + result.insertionsAfter.keys).sorted()
        val regions = mutableListOf<ChangeRegion>()
        var start = 0
        var end = 0
        var key: String? = null
        var firstSummary: LineSummary? = null

        fun flush() {
            val summary = firstSummary ?: return
            regions += ChangeRegion(start..end, summary)
        }

        for (line in lines) {
            val summary = result.summaryForLine(line) ?: continue
            val nextKey = presentationKey(summary)
            if (firstSummary == null || line != end + 1 || nextKey != key) {
                flush()
                start = line
                firstSummary = summary
                key = nextKey
            }
            end = line
        }
        flush()
        return regions
    }

    /**
     * A visual region follows the comparison cohort, not per-line pairing or blame.
     * A single Git hunk may mix replacements and current-only lines, and branch-side
     * blame may change inside it even though every branch produces the same diff.
     */
    private fun presentationKey(summary: LineSummary): String =
        (summary.differences.map { it.branch.name } + summary.insertions.map { it.branch.name })
            .distinct()
            .sorted()
            .joinToString("\u001f")
}
