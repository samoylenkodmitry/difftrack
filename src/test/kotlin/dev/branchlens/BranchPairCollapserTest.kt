package dev.branchlens

import dev.branchlens.git.BranchPairCollapser
import dev.branchlens.model.LocalBranch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BranchPairCollapserTest {
    @Test
    fun collapsesLocalAndUpstreamWhenTheirTipsMatch() {
        val local = branch("topic", "abc", upstream = "origin/topic")
        val remote = branch("origin/topic", "abc", remote = true)

        val result = BranchPairCollapser.collapse(listOf(local, remote))

        assertEquals(1, result.size)
        assertEquals("origin/topic", result.single().pairedRemoteName)
    }

    @Test
    fun keepsDivergedLocalAndRemoteRefsSeparate() {
        val local = branch("topic", "local", upstream = "origin/topic")
        val remote = branch("origin/topic", "remote", remote = true)

        val result = BranchPairCollapser.collapse(listOf(local, remote))

        assertEquals(2, result.size)
        assertNull(result.first { it.name == "topic" }.pairedRemoteName)
    }

    private fun branch(
        name: String,
        head: String,
        remote: Boolean = false,
        upstream: String? = null,
    ) = LocalBranch(name, head, 1L, "Tester", false, remote, upstream, null, 0, 0)
}
