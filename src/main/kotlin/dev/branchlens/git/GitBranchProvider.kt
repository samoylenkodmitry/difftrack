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
        val AHEAD_REGEX = Regex("ahead ([0-9]+)")
        val BEHIND_REGEX = Regex("behind ([0-9]+)")
    }

    suspend fun listLocal(
        repoRoot: Path,
        currentBranch: String?,
        includeRemoteTracking: Boolean = false,
    ): List<LocalBranch> {
        val sep = SEPARATOR_STRING
        val format = "%(refname)$sep%(refname:short)$sep%(objectname)$sep" +
            "%(committerdate:unix)$sep%(authorname)$sep%(symref)$sep" +
            "%(upstream:short)$sep%(upstream:track)$sep"
        val namespaces = buildList {
            add("refs/heads")
            if (includeRemoteTracking) add("refs/remotes")
        }
        val result = cli.run(
            repoRoot,
            buildList {
                add("for-each-ref")
                add("--sort=-committerdate")
                add("--format=$format")
                addAll(namespaces)
            },
            timeoutMs,
        )
        if (result.exitCode != 0) return emptyList()

        return result.stdoutText.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { parseLine(it, currentBranch) }
            .toList()
    }

    private fun parseLine(line: String, currentBranch: String?): LocalBranch? {
        val parts = line.split(SEPARATOR_CHAR, ignoreCase = false, limit = 9)
        if (parts.size < 8) return null
        val fullRef = parts[0]
        val name = parts[1]
        val sha = parts[2]
        if (name.isEmpty() || sha.isEmpty()) return null
        val committerDate = parts[3].toLongOrNull()
        val author = parts[4].ifEmpty { null }
        val symbolicTarget = parts[5]
        val upstream = parts[6].ifEmpty { null }
        val tracking = parts[7]
        val ahead = AHEAD_REGEX.find(tracking)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val behind = BEHIND_REGEX.find(tracking)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val isRemoteTracking = fullRef.startsWith("refs/remotes/")
        if (isRemoteTracking && symbolicTarget.isNotEmpty()) return null
        return LocalBranch(
            name = name,
            headCommit = sha,
            committerDateEpochSeconds = committerDate,
            authorName = author,
            isCurrent = (currentBranch != null && currentBranch == name),
            isRemoteTracking = isRemoteTracking,
            upstreamName = upstream,
            commitsAheadOfUpstream = ahead,
            commitsBehindUpstream = behind,
        )
    }
}
