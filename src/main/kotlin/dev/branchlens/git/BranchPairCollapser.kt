package dev.branchlens.git

import dev.branchlens.model.LocalBranch

/** Collapses an up-to-date local branch and its configured remote-tracking ref. */
object BranchPairCollapser {
    fun collapse(branches: List<LocalBranch>): List<LocalBranch> {
        val remoteByName = branches.asSequence()
            .filter { it.isRemoteTracking }
            .associateBy { it.name }
        val pairedRemoteNames = mutableSetOf<String>()

        val locals = branches.filterNot { it.isRemoteTracking }.map { local ->
            val remote = local.upstreamName?.let(remoteByName::get)
            if (remote != null && remote.headCommit == local.headCommit) {
                pairedRemoteNames += remote.name
                local.copy(pairedRemoteName = remote.name)
            } else {
                local
            }
        }
        val unpairedRemotes = branches.filter { it.isRemoteTracking && it.name !in pairedRemoteNames }
        return (locals + unpairedRemotes).sortedByDescending { it.committerDateEpochSeconds ?: Long.MIN_VALUE }
    }
}
