package dev.branchlens.model

data class BranchLensSettingsState(
    var enabled: Boolean = true,
    var maxLines: Int = 10_000,
    var maxFileBytes: Long = 2L * 1024L * 1024L,
    var maxBranches: Int = 30,
    var staleBranchDays: Int = 180,
    var includeStaleBranches: Boolean = false,
    var ignoreWhitespaceInDiff: Boolean = false,
    var useMoveAwareBlame: Boolean = true,
    var useCopyAwareBlame: Boolean = false,
    var analysisDebounceMs: Int = 300,
    var gitCommandTimeoutMs: Int = 5_000,
    var visibleRenderMarginLines: Int = 80,
    var maxConcurrentGitProcesses: Int = 3,
    var excludedBranchPatterns: MutableList<String> = mutableListOf(
        "backup/*",
        "tmp/*",
        "wip/*",
    ),
) {
    fun copyState(): BranchLensSettingsState = BranchLensSettingsState(
        enabled = enabled,
        maxLines = maxLines,
        maxFileBytes = maxFileBytes,
        maxBranches = maxBranches,
        staleBranchDays = staleBranchDays,
        includeStaleBranches = includeStaleBranches,
        ignoreWhitespaceInDiff = ignoreWhitespaceInDiff,
        useMoveAwareBlame = useMoveAwareBlame,
        useCopyAwareBlame = useCopyAwareBlame,
        analysisDebounceMs = analysisDebounceMs,
        gitCommandTimeoutMs = gitCommandTimeoutMs,
        visibleRenderMarginLines = visibleRenderMarginLines,
        maxConcurrentGitProcesses = maxConcurrentGitProcesses,
        excludedBranchPatterns = excludedBranchPatterns.toMutableList(),
    )
}
