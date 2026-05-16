package dev.branchlens.git

import dev.branchlens.model.LocalBranch
import java.nio.file.Path

class GitBranchProvider(private val cli: GitCli, private val timeoutMs: Long) {

    private companion object {
        // `%00` in git's `for-each-ref --format` emits a literal NUL byte (U+0000).
        // We use NUL as the field separator so branch names containing whitespace,
        // commas, or other punctuation remain intact when we split.
        val NUL_CHAR: Char = 0.toChar()
        val NUL_STRING: String = NUL_CHAR.toString()
    }

    suspend fun listLocal(repoRoot: Path, currentBranch: String?): List<LocalBranch> {
        val sep = NUL_STRING
        val format = "%(refname:short)$sep%(objectname)$sep" +
            "%(committerdate:unix)$sep%(authorname)$sep"
        val result = cli.run(
            repoRoot,
            listOf(
                "for-each-ref",
                "--sort=-committerdate",
                "--format=$format",
                "refs/heads",
            ),
            timeoutMs,
        )
        if (result.exitCode != 0) return emptyList()

        return result.stdoutText.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { parseLine(it, currentBranch) }
            .toList()
    }

    private fun parseLine(line: String, currentBranch: String?): LocalBranch? {
        val parts = line.split(NUL_CHAR)
        if (parts.size < 4) return null
        val name = parts[0]
        val sha = parts[1]
        if (name.isEmpty() || sha.isEmpty()) return null
        val committerDate = parts[2].toLongOrNull()
        val author = parts[3].ifEmpty { null }
        return LocalBranch(
            name = name,
            headCommit = sha,
            committerDateEpochSeconds = committerDate,
            authorName = author,
            isCurrent = (currentBranch != null && currentBranch == name),
        )
    }
}
