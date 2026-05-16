package dev.branchlens.diff

data class DiffFileHeader(val oldPath: String?, val newPath: String?)

data class DiffHunk(
    val oldStart: Int,
    val oldLength: Int,
    val newStart: Int,
    val newLength: Int,
    val lines: List<DiffLine>,
)

sealed class DiffLine {
    data class Context(val oldLine: Int, val newLine: Int, val text: String) : DiffLine()
    data class Removed(val oldLine: Int, val text: String) : DiffLine()
    data class Added(val newLine: Int, val text: String) : DiffLine()
}

data class UnifiedDiff(
    val fileHeader: DiffFileHeader?,
    val hunks: List<DiffHunk>,
)

/**
 * Parses the unified diff output of `git diff --no-index -U0 -- a b`.
 *
 * Handles hunk headers of the form `@@ -a[,b] +c[,d] @@`, the
 * `\ No newline at end of file` marker, and a single optional `---`/`+++` file header pair.
 */
object UnifiedDiffParser {
    private val HUNK = Regex("""^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@.*$""")

    fun parse(raw: String): UnifiedDiff {
        val normalized = raw.replace("\r\n", "\n").replace('\r', '\n')
        val lines = normalized.split('\n')
        var i = 0

        var oldPath: String? = null
        var newPath: String? = null

        while (i < lines.size) {
            val line = lines[i]
            when {
                line.startsWith("--- ") -> {
                    oldPath = line.removePrefix("--- ").trim()
                    i++
                }
                line.startsWith("+++ ") -> {
                    newPath = line.removePrefix("+++ ").trim()
                    i++
                }
                line.startsWith("diff ") || line.startsWith("index ") ||
                    line.startsWith("new file") || line.startsWith("deleted file") ||
                    line.startsWith("similarity ") || line.startsWith("rename ") ||
                    line.startsWith("Binary files") -> i++
                line.startsWith("@@") -> break
                line.isEmpty() -> i++
                else -> i++
            }
        }

        val hunks = mutableListOf<DiffHunk>()
        while (i < lines.size) {
            val header = lines[i]
            val match = HUNK.find(header)
            if (match == null) {
                i++
                continue
            }
            val oldStart = match.groupValues[1].toInt()
            val oldLength = match.groupValues[2].ifEmpty { "1" }.toInt()
            val newStart = match.groupValues[3].toInt()
            val newLength = match.groupValues[4].ifEmpty { "1" }.toInt()
            i++

            val body = mutableListOf<DiffLine>()
            var oldCursor = oldStart
            var newCursor = newStart

            while (i < lines.size) {
                val l = lines[i]
                if (l.startsWith("@@")) break
                if (l.startsWith("diff ") || l.startsWith("--- ") || l.startsWith("+++ ")) break
                if (l.startsWith("\\ ")) {
                    // "\ No newline at end of file" — ignored for line classification.
                    i++
                    continue
                }
                if (l.isEmpty() && i == lines.lastIndex) {
                    // Trailing empty string from final \n; safe to ignore.
                    i++
                    continue
                }
                when {
                    l.startsWith(" ") -> {
                        body += DiffLine.Context(oldCursor, newCursor, l.substring(1))
                        oldCursor++
                        newCursor++
                    }
                    l.startsWith("-") -> {
                        body += DiffLine.Removed(oldCursor, l.substring(1))
                        oldCursor++
                    }
                    l.startsWith("+") -> {
                        body += DiffLine.Added(newCursor, l.substring(1))
                        newCursor++
                    }
                    else -> {
                        // Unknown payload; stop this hunk.
                        break
                    }
                }
                i++
            }

            hunks += DiffHunk(oldStart, oldLength, newStart, newLength, body)
        }

        val header = if (oldPath != null || newPath != null) DiffFileHeader(oldPath, newPath) else null
        return UnifiedDiff(header, hunks)
    }
}
