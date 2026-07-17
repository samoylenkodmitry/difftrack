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

    private val analysisCache = LinkedHashMap<AnalysisKey, FileAnalysisResult.Computed>(16, 0.75f, true)
    private val blameCache = LinkedHashMap<BlameKey, Map<Int, BlameInfo>>(16, 0.75f, true)
    private var analysisWeightBytes = 0L
    private var blameWeightBytes = 0L

    fun getAnalysis(key: AnalysisKey): FileAnalysisResult.Computed? =
        synchronized(analysisLock) { analysisCache[key] }

    fun putAnalysis(key: AnalysisKey, value: FileAnalysisResult.Computed) {
        synchronized(analysisLock) {
            analysisCache.remove(key)?.let { analysisWeightBytes -= estimateAnalysisBytes(it) }
            analysisCache[key] = value
            analysisWeightBytes += estimateAnalysisBytes(value)
            trimAnalysisCache()
        }
    }

    fun getBlame(key: BlameKey): Map<Int, BlameInfo>? =
        synchronized(blameLock) { blameCache[key] }

    fun putBlame(key: BlameKey, value: Map<Int, BlameInfo>) {
        synchronized(blameLock) {
            blameCache.remove(key)?.let { blameWeightBytes -= estimateBlameBytes(it) }
            blameCache[key] = value
            blameWeightBytes += estimateBlameBytes(value)
            trimBlameCache()
        }
    }

    fun clear() {
        synchronized(analysisLock) {
            analysisCache.clear()
            analysisWeightBytes = 0L
        }
        synchronized(blameLock) {
            blameCache.clear()
            blameWeightBytes = 0L
        }
    }

    private fun trimAnalysisCache() {
        val iterator = analysisCache.entries.iterator()
        while ((analysisWeightBytes > MAX_ANALYSIS_BYTES || analysisCache.size > MAX_ANALYSIS_ENTRIES) &&
            iterator.hasNext()
        ) {
            val entry = iterator.next()
            analysisWeightBytes -= estimateAnalysisBytes(entry.value)
            iterator.remove()
        }
    }

    private fun trimBlameCache() {
        val iterator = blameCache.entries.iterator()
        while ((blameWeightBytes > MAX_BLAME_BYTES || blameCache.size > MAX_BLAME_ENTRIES) &&
            iterator.hasNext()
        ) {
            val entry = iterator.next()
            blameWeightBytes -= estimateBlameBytes(entry.value)
            iterator.remove()
        }
    }

    private fun estimateAnalysisBytes(result: FileAnalysisResult.Computed): Long {
        val branchTextBytes = result.branchContents.values.sumOf { it.length.toLong() * 2L }
        val differenceCount = result.perLineDifferences.values.sumOf { it.size } +
            result.insertionsAfter.values.sumOf { it.size }
        return branchTextBytes + differenceCount * ESTIMATED_DIFFERENCE_BYTES + BASE_ANALYSIS_BYTES
    }

    private fun estimateBlameBytes(blame: Map<Int, BlameInfo>): Long =
        blame.size.toLong() * ESTIMATED_BLAME_ENTRY_BYTES

    companion object {
        private const val MAX_ANALYSIS_ENTRIES = 64
        private const val MAX_BLAME_ENTRIES = 128
        private const val MAX_ANALYSIS_BYTES = 96L * 1024L * 1024L
        private const val MAX_BLAME_BYTES = 48L * 1024L * 1024L
        private const val BASE_ANALYSIS_BYTES = 512L
        private const val ESTIMATED_DIFFERENCE_BYTES = 256L
        private const val ESTIMATED_BLAME_ENTRY_BYTES = 256L

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
    val rangeStart: Int,
    val rangeEnd: Int,
)
