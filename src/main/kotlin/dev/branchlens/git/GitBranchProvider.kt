package dev.branchlens.git

import dev.branchlens.model.LocalBranch
import java.nio.file.Path

class GitBranchProvider(private val cli: GitCli, private val timeoutMs: Long) {

    private companion object {
        // Java's ProcessBuilder forbids NUL bytes inside command-line arguments, so we
        // can't use `%00` as the git for-each-ref field separator. ASCII 0x1E (Record
        // Separator) works just as well: git refnames must not contain ASCII control
        // characters, so the separator is unambiguous, and argv accepts it.
        val SEPARATOR_CHAR: Char = 30.toChar()
        val SEPARATOR_STRING: String = SEPARATOR_CHAR.toString()
    }

    suspend fun listLocal(repoRoot: Path, currentBranch: String?): List<LocalBranch> {
        val sep = SEPARATOR_STRING
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
        val parts = line.split(SEPARATOR_CHAR)
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
