package dev.branchlens.git

import dev.branchlens.model.GitRepo
import java.nio.file.Files
import java.nio.file.Path

class GitRepositoryLocator(private val cli: GitCli, private val timeoutMs: Long) {

    suspend fun locate(forFile: Path): GitRepo? {
        val parent = if (Files.isDirectory(forFile)) forFile else forFile.parent ?: return null
        val rootResult = cli.run(parent, listOf("rev-parse", "--show-toplevel"), timeoutMs)
        if (rootResult.exitCode != 0) return null
        val root = Path.of(rootResult.stdoutText.trim())

        val branchResult = cli.run(root, listOf("symbolic-ref", "--quiet", "--short", "HEAD"), timeoutMs)
        val currentBranch = if (branchResult.exitCode == 0) branchResult.stdoutText.trim().ifEmpty { null } else null

        val headResult = cli.run(root, listOf("rev-parse", "HEAD"), timeoutMs)
        val headCommit = if (headResult.exitCode == 0) headResult.stdoutText.trim().ifEmpty { null } else null

        return GitRepo(root = root, currentBranch = currentBranch, headCommit = headCommit)
    }
}
