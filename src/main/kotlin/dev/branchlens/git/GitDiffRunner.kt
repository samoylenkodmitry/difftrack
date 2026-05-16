package dev.branchlens.git

import dev.branchlens.diff.UnifiedDiff
import dev.branchlens.diff.UnifiedDiffParser
import java.nio.file.Path

class GitDiffRunner(private val cli: GitCli, private val timeoutMs: Long) {

    /**
     * Runs `git diff --no-index -U0` between two on-disk paths and parses the unified output.
     *
     * Git's exit codes for --no-index:
     *   0 = identical
     *   1 = differ
     *  >1 = error
     */
    suspend fun diff(
        repoRoot: Path,
        currentSnapshot: Path,
        branchBlob: Path,
        ignoreWhitespace: Boolean,
    ): UnifiedDiff? {
        val args = buildList {
            add("diff")
            add("--no-index")
            add("--unified=0")
            add("--no-color")
            add("--no-ext-diff")
            add("--text")
            if (ignoreWhitespace) add("--ignore-all-space")
            add("--")
            add(currentSnapshot.toString())
            add(branchBlob.toString())
        }
        val result = cli.run(repoRoot, args, timeoutMs)
        return when (result.exitCode) {
            0 -> UnifiedDiff(null, emptyList())
            1 -> UnifiedDiffParser.parse(result.stdoutText)
            else -> null
        }
    }
}
