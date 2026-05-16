package dev.branchlens.git

import dev.branchlens.util.TextUtil
import java.nio.file.Path

sealed class BlobResult {
    object NotFound : BlobResult()
    object Binary : BlobResult()
    data class Text(val content: String) : BlobResult()
    data class Error(val message: String) : BlobResult()
}

class GitBlobReader(private val cli: GitCli, private val timeoutMs: Long) {

    suspend fun read(repoRoot: Path, branchCommit: String, relativePath: String, maxBytes: Long): BlobResult {
        val result = cli.run(
            repoRoot,
            listOf("cat-file", "-p", "$branchCommit:$relativePath"),
            timeoutMs,
            captureBinaryStdout = true,
        )
        if (result.exitCode != 0) {
            // For `git cat-file -p <tree-ish>:<path>` the path is unambiguous, so any
            // non-zero exit means the branch does not have a usable blob at that path.
            // Real connector/I/O failures throw IOException before this point.
            return BlobResult.NotFound
        }
        if (result.stdout.size.toLong() > maxBytes) return BlobResult.Binary
        if (TextUtil.looksBinary(result.stdout)) return BlobResult.Binary

        val decoded = try {
            String(result.stdout, Charsets.UTF_8)
        } catch (_: Throwable) {
            return BlobResult.Binary
        }
        return BlobResult.Text(TextUtil.normalizeLineEndings(decoded))
    }
}
