package dev.branchlens.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import javax.swing.JComponent
import javax.swing.JPanel

class BranchLensConfigurable : Configurable {

    private val enabled = JBCheckBox("Enable Branch Lens")
    private val maxLines = JBTextField()
    private val maxBranches = JBTextField()
    private val staleDays = JBTextField()
    private val includeStale = JBCheckBox("Include stale branches")
    private val ignoreWhitespace = JBCheckBox("Ignore whitespace in diff")
    private val moveAware = JBCheckBox("Move-aware blame (git blame -M)")
    private val copyAware = JBCheckBox("Copy-aware blame (git blame -C, slower)")
    private val debounceMs = JBTextField()
    private val gitTimeoutMs = JBTextField()
    private val renderMargin = JBTextField()
    private val maxConcurrent = JBTextField()
    private val excludedPatterns = JBTextArea(4, 30)

    private var panel: JPanel? = null

    override fun getDisplayName(): String = "Branch Lens"

    override fun createComponent(): JComponent {
        excludedPatterns.lineWrap = false
        val built = FormBuilder.createFormBuilder()
            .addComponent(enabled)
            .addLabeledComponent("Max lines per file:", maxLines)
            .addLabeledComponent("Max branches to analyze:", maxBranches)
            .addLabeledComponent("Hide branches older than (days):", staleDays)
            .addComponent(includeStale)
            .addComponent(ignoreWhitespace)
            .addComponent(moveAware)
            .addComponent(copyAware)
            .addLabeledComponent("Analysis debounce (ms):", debounceMs)
            .addLabeledComponent("Git command timeout (ms):", gitTimeoutMs)
            .addLabeledComponent("Visible render margin (lines):", renderMargin)
            .addLabeledComponent("Max concurrent git processes:", maxConcurrent)
            .addLabeledComponent(
                JBLabel("Excluded branch patterns (one per line):"),
                excludedPatterns,
            )
            .addComponentFillVertically(JPanel(), 0)
            .panel
        built.border = JBUI.Borders.empty(12)
        panel = built
        reset()
        return built
    }

    override fun isModified(): Boolean {
        val s = BranchLensSettings.getInstance().state
        return enabled.isSelected != s.enabled ||
            maxLines.text.toIntOrNull() != s.maxLines ||
            maxBranches.text.toIntOrNull() != s.maxBranches ||
            staleDays.text.toIntOrNull() != s.staleBranchDays ||
            includeStale.isSelected != s.includeStaleBranches ||
            ignoreWhitespace.isSelected != s.ignoreWhitespaceInDiff ||
            moveAware.isSelected != s.useMoveAwareBlame ||
            copyAware.isSelected != s.useCopyAwareBlame ||
            debounceMs.text.toIntOrNull() != s.analysisDebounceMs ||
            gitTimeoutMs.text.toIntOrNull() != s.gitCommandTimeoutMs ||
            renderMargin.text.toIntOrNull() != s.visibleRenderMarginLines ||
            maxConcurrent.text.toIntOrNull() != s.maxConcurrentGitProcesses ||
            patternsFromUi() != s.excludedBranchPatterns
    }

    override fun apply() {
        BranchLensSettings.getInstance().update {
            enabled = this@BranchLensConfigurable.enabled.isSelected
            maxLines = this@BranchLensConfigurable.maxLines.text.toIntOrNull()?.coerceAtLeast(1) ?: maxLines
            maxBranches = this@BranchLensConfigurable.maxBranches.text.toIntOrNull()?.coerceAtLeast(0) ?: maxBranches
            staleBranchDays = this@BranchLensConfigurable.staleDays.text.toIntOrNull()?.coerceAtLeast(0) ?: staleBranchDays
            includeStaleBranches = this@BranchLensConfigurable.includeStale.isSelected
            ignoreWhitespaceInDiff = this@BranchLensConfigurable.ignoreWhitespace.isSelected
            useMoveAwareBlame = this@BranchLensConfigurable.moveAware.isSelected
            useCopyAwareBlame = this@BranchLensConfigurable.copyAware.isSelected
            analysisDebounceMs = this@BranchLensConfigurable.debounceMs.text.toIntOrNull()?.coerceAtLeast(0) ?: analysisDebounceMs
            gitCommandTimeoutMs = this@BranchLensConfigurable.gitTimeoutMs.text.toIntOrNull()?.coerceAtLeast(500) ?: gitCommandTimeoutMs
            visibleRenderMarginLines = this@BranchLensConfigurable.renderMargin.text.toIntOrNull()?.coerceAtLeast(0) ?: visibleRenderMarginLines
            maxConcurrentGitProcesses = this@BranchLensConfigurable.maxConcurrent.text.toIntOrNull()?.coerceAtLeast(1) ?: maxConcurrentGitProcesses
            excludedBranchPatterns = patternsFromUi().toMutableList()
        }
    }

    override fun reset() {
        val s = BranchLensSettings.getInstance().state
        enabled.isSelected = s.enabled
        maxLines.text = s.maxLines.toString()
        maxBranches.text = s.maxBranches.toString()
        staleDays.text = s.staleBranchDays.toString()
        includeStale.isSelected = s.includeStaleBranches
        ignoreWhitespace.isSelected = s.ignoreWhitespaceInDiff
        moveAware.isSelected = s.useMoveAwareBlame
        copyAware.isSelected = s.useCopyAwareBlame
        debounceMs.text = s.analysisDebounceMs.toString()
        gitTimeoutMs.text = s.gitCommandTimeoutMs.toString()
        renderMargin.text = s.visibleRenderMarginLines.toString()
        maxConcurrent.text = s.maxConcurrentGitProcesses.toString()
        excludedPatterns.text = s.excludedBranchPatterns.joinToString("\n")
    }

    private fun patternsFromUi(): List<String> =
        excludedPatterns.text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
}
