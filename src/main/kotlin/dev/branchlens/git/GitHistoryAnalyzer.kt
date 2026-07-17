package dev.branchlens.git

import dev.branchlens.model.BlameInfo
import dev.branchlens.model.BranchLineDifference
import dev.branchlens.model.ChangeLineage
import dev.branchlens.model.isUncommitted
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

class GitHistoryAnalyzer(private val cli: GitCli, private val timeoutMs: Long) {
    private val mergeBaseCache = ConcurrentHashMap<String, String>()
    private val ancestorCache = ConcurrentHashMap<String, Boolean>()

    suspend fun classify(
        repoRoot: Path,
        currentHead: String,
        difference: BranchLineDifference,
        currentBlame: BlameInfo?,
        branchBlame: BlameInfo?,
    ): ChangeLineage {
        if (currentBlame?.isUncommitted == true) return ChangeLineage.UNCOMMITTED_CURRENT
        val base = mergeBase(repoRoot, currentHead, difference.branch.headCommit)
            ?: return ChangeLineage.UNKNOWN
        val currentAfter = currentBlame?.commitHash?.let { !isAncestor(repoRoot, it, base) }
        val branchAfter = branchBlame?.commitHash?.let { !isAncestor(repoRoot, it, base) }

        return when (difference) {
            is BranchLineDifference.DeletedInBranch -> when (currentAfter) {
                true -> ChangeLineage.ADDED_ON_CURRENT
                false -> ChangeLineage.REMOVED_ON_BRANCH
                null -> ChangeLineage.UNKNOWN
            }
            is BranchLineDifference.BranchInsertionAfterCurrentLine -> when (branchAfter) {
                true -> ChangeLineage.ADDED_ON_BRANCH
                false -> ChangeLineage.REMOVED_ON_CURRENT
                null -> ChangeLineage.UNKNOWN
            }
            is BranchLineDifference.ReplacedLine,
            is BranchLineDifference.ChangedBlock -> when {
                currentAfter == true && branchAfter == true -> ChangeLineage.MODIFIED_INDEPENDENTLY
                currentAfter == true -> ChangeLineage.MODIFIED_ON_CURRENT
                branchAfter == true -> ChangeLineage.MODIFIED_ON_BRANCH
                currentAfter == false && branchAfter == false -> ChangeLineage.DIFFERED_BEFORE_DIVERGENCE
                else -> ChangeLineage.UNKNOWN
            }
            is BranchLineDifference.FileMissingInBranch -> ChangeLineage.UNKNOWN
        }
    }

    suspend fun isContained(repoRoot: Path, commit: String, branchHead: String): Boolean =
        isAncestor(repoRoot, commit, branchHead)

    private suspend fun mergeBase(repoRoot: Path, a: String, b: String): String? {
        val key = "$repoRoot|$a|$b"
        mergeBaseCache[key]?.let { return it }
        val result = cli.run(repoRoot, listOf("merge-base", a, b), timeoutMs)
        if (result.exitCode == 1) return null
        check(result.exitCode == 0) { "git merge-base failed: ${result.stderr}" }
        val base = result.stdoutText.trim().takeIf { it.isNotEmpty() } ?: return null
        mergeBaseCache[key] = base
        return base
    }

    private suspend fun isAncestor(repoRoot: Path, ancestor: String, descendant: String): Boolean {
        if (ancestor.all { it == '0' }) return false
        val key = "$repoRoot|$ancestor|$descendant"
        ancestorCache[key]?.let { return it }
        val result = cli.run(repoRoot, listOf("merge-base", "--is-ancestor", ancestor, descendant), timeoutMs)
        val value = when (result.exitCode) {
            0 -> true
            1 -> false
            else -> error("git merge-base --is-ancestor failed: ${result.stderr}")
        }
        ancestorCache[key] = value
        return value
    }
}
