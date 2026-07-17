package dev.branchlens.git

import java.nio.file.Path

/** Resolves the path a currently-open file had at another branch tip. */
class GitRenameResolver(private val cli: GitCli, private val timeoutMs: Long) {
    suspend fun pathInBranch(
        repoRoot: Path,
        branchCommit: String,
        currentCommit: String,
        currentPath: String,
    ): String? {
        val result = cli.run(
            repoRoot,
            listOf(
                "diff",
                "--name-status",
                "--find-renames",
                "--diff-filter=R",
                branchCommit,
                currentCommit,
            ),
            timeoutMs,
        )
        if (result.exitCode != 0) return null
        return result.stdoutText.lineSequence().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size >= 3 && parts[0].startsWith('R') && parts[2] == currentPath) parts[1] else null
        }.firstOrNull()
    }
}
