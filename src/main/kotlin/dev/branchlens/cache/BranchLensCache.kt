package dev.branchlens.cache

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import dev.branchlens.model.BlameInfo
import dev.branchlens.model.FileAnalysisResult
import java.nio.file.Path

/**
 * Memory caches that let the analyzer skip git when document/branches haven't moved.
 *
 * - [analysisCache]: full per-file analysis result, keyed by file + document hash +
 *   branch-tips fingerprint + relevant settings hash. A document edit changes the
 *   document hash, a commit on any branch changes the tips fingerprint, a settings
 *   change changes the settings hash — any of these forces a re-analysis.
 * - [blameCache]: per-branch-tip blame, keyed by (branchCommit, relativePath) plus
 *   blame-relevant flags. Blame is the most expensive step and rarely changes, so
 *   reusing it across many analyses is the biggest win.
 */
@Service(Service.Level.PROJECT)
class BranchLensCache {

    private val analysisLock = Any()
    private val blameLock = Any()

    private val analysisCache: LinkedHashMap<AnalysisKey, FileAnalysisResult.Computed> =
        object : LinkedHashMap<AnalysisKey, FileAnalysisResult.Computed>(MAX_ANALYSIS, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<AnalysisKey, FileAnalysisResult.Computed>): Boolean =
                size > MAX_ANALYSIS
        }

    private val blameCache: LinkedHashMap<BlameKey, Map<Int, BlameInfo>> =
        object : LinkedHashMap<BlameKey, Map<Int, BlameInfo>>(MAX_BLAME, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<BlameKey, Map<Int, BlameInfo>>): Boolean =
                size > MAX_BLAME
        }

    fun getAnalysis(key: AnalysisKey): FileAnalysisResult.Computed? =
        synchronized(analysisLock) { analysisCache[key] }

    fun putAnalysis(key: AnalysisKey, value: FileAnalysisResult.Computed) {
        synchronized(analysisLock) { analysisCache[key] = value }
    }

    fun getBlame(key: BlameKey): Map<Int, BlameInfo>? =
        synchronized(blameLock) { blameCache[key] }

    fun putBlame(key: BlameKey, value: Map<Int, BlameInfo>) {
        synchronized(blameLock) { blameCache[key] = value }
    }

    fun clear() {
        synchronized(analysisLock) { analysisCache.clear() }
        synchronized(blameLock) { blameCache.clear() }
    }

    companion object {
        private const val MAX_ANALYSIS = 200
        private const val MAX_BLAME = 100

        fun getInstance(project: Project): BranchLensCache =
            project.getService(BranchLensCache::class.java)
    }
}

data class AnalysisKey(
    val repoRoot: Path,
    val relativePath: String,
    val documentHash: String,
    val branchTipsHash: String,
    val settingsHash: String,
)

data class BlameKey(
    val repoRoot: Path,
    val branchCommit: String,
    val relativePath: String,
    val moveAware: Boolean,
    val copyAware: Boolean,
)
