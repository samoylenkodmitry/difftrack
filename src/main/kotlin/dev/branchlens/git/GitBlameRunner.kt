package dev.branchlens.git

import dev.branchlens.model.BlameInfo
import java.nio.file.Path

class GitBlameRunner(private val cli: GitCli, private val timeoutMs: Long) {

    /**
     * Returns a map from 1-based branch-side line number to [BlameInfo].
     *
     * If [range] is provided, only that range is blamed; otherwise the full file is blamed.
     */
    suspend fun blame(
        repoRoot: Path,
        branchCommit: String,
        relativePath: String,
        range: IntRange?,
        useMoveAware: Boolean,
        useCopyAware: Boolean,
    ): Map<Int, BlameInfo> {
        val args = buildList {
            add("blame")
            add("--line-porcelain")
            add("-w")
            if (useMoveAware) add("-M")
            if (useCopyAware) add("-C")
            if (range != null) {
                add("-L")
                add("${range.first},${range.last}")
            }
            add(branchCommit)
            add("--")
            add(relativePath)
        }
        val result = cli.run(repoRoot, args, timeoutMs)
        if (result.exitCode != 0) return emptyMap()
        return PorcelainBlameParser.parse(result.stdoutText)
    }

    /** Blames an editor snapshot while retaining HEAD history for unchanged lines. */
    suspend fun blameContents(
        repoRoot: Path,
        branchCommit: String,
        relativePath: String,
        contentsPath: Path,
        range: IntRange?,
        useMoveAware: Boolean,
        useCopyAware: Boolean,
    ): Map<Int, BlameInfo> {
        val args = buildList {
            add("blame")
            add("--line-porcelain")
            add("-w")
            if (useMoveAware) add("-M")
            if (useCopyAware) add("-C")
            if (range != null) {
                add("-L")
                add("${range.first},${range.last}")
            }
            add("--contents")
            add(contentsPath.toString())
            add(branchCommit)
            add("--")
            add(relativePath)
        }
        val result = cli.run(repoRoot, args, timeoutMs)
        if (result.exitCode != 0) return emptyMap()
        return PorcelainBlameParser.parse(result.stdoutText)
    }
}

internal object PorcelainBlameParser {

    fun parse(raw: String): Map<Int, BlameInfo> {
        val out = HashMap<Int, BlameInfo>()
        val lines = raw.split('\n')
        var i = 0
        val commitInfo = HashMap<String, MutableMap<String, String>>()

        while (i < lines.size) {
            val header = lines[i]
            if (header.isEmpty()) {
                i++
                continue
            }
            val parts = header.split(' ')
            if (parts.size < 3) {
                i++
                continue
            }
            val sha = parts[0]
            if (!sha.matches(Regex("^[0-9a-f]{6,40}$"))) {
                i++
                continue
            }
            val resultLine = parts[2].toIntOrNull()
            if (resultLine == null) {
                i++
                continue
            }
            i++

            val info = commitInfo.getOrPut(sha) { HashMap() }
            while (i < lines.size && !lines[i].startsWith("\t")) {
                val meta = lines[i]
                if (meta.isEmpty()) { i++; continue }
                val space = meta.indexOf(' ')
                if (space < 0) {
                    info.putIfAbsent(meta, "")
                } else {
                    val key = meta.substring(0, space)
                    val value = meta.substring(space + 1)
                    info.putIfAbsent(key, value)
                }
                i++
            }
            // Skip the actual content line (starts with TAB) if present.
            if (i < lines.size && lines[i].startsWith("\t")) i++

            out[resultLine] = BlameInfo(
                commitHash = sha,
                author = info["author"],
                authorMail = info["author-mail"]?.trim('<', '>'),
                authorTimeEpochSeconds = info["author-time"]?.toLongOrNull(),
                authorTimezone = info["author-tz"],
                summary = info["summary"],
            )
        }
        return out
    }
}
