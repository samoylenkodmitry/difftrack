package dev.branchlens.actions

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import dev.branchlens.BranchLensProjectService
import dev.branchlens.git.BlobResult
import dev.branchlens.git.GitBlobReader
import dev.branchlens.git.GitBranchProvider
import dev.branchlens.git.GitCli
import dev.branchlens.git.GitRepositoryLocator
import dev.branchlens.model.LocalBranch
import dev.branchlens.settings.BranchLensSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JList

/**
 * Editor / VCS-menu action: pick any local branch, diff the current file against its
 * version on that branch with IntelliJ's built-in diff viewer.
 *
 * Surfaced via `Compare File with Local Branch…` so users who don't notice the gutter
 * badges still have a discoverable entry point (Find Action / right-click in editor).
 */
class CompareFileWithBranchAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        val file = editor?.let { FileDocumentManager.getInstance().getFile(it.document) }
        e.presentation.isEnabledAndVisible = project != null && editor != null && file != null && !file.isDirectory
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val virtualFile = FileDocumentManager.getInstance().getFile(editor.document) ?: return

        val service = project.service<BranchLensProjectService>()
        service.launchCompareFlow(editor, virtualFile, e)
    }

    companion object {
        suspend fun runCompareFlow(
            scope: CoroutineScope,
            project: Project,
            editor: Editor,
            virtualFile: VirtualFile,
            e: AnActionEvent,
        ) {
            val log = thisLogger()
            val state = BranchLensSettings.getInstance().state.copyState()
            val cli = GitCli(maxConcurrent = state.maxConcurrentGitProcesses)
            val timeout = state.gitCommandTimeoutMs.toLong()
            val locator = GitRepositoryLocator(cli, timeout)
            val branchProvider = GitBranchProvider(cli, timeout)
            val blobs = GitBlobReader(cli, timeout)

            val filePath = try {
                virtualFile.toNioPath()
            } catch (t: Throwable) {
                log.warn("Compare with branch: unable to resolve file path: ${t.message}")
                return
            }

            val repo = locator.locate(filePath)
            if (repo == null) {
                showHint(editor, e, "Branch Lens: file is not inside a Git repository")
                return
            }

            val allBranches = branchProvider.listLocal(repo.root, repo.currentBranch)
            val pickable = allBranches.filter { !it.isCurrent }
            if (pickable.isEmpty()) {
                showHint(editor, e, "Branch Lens: no other local branches to compare against")
                return
            }

            withContext(Dispatchers.EDT) {
                if (project.isDisposed) return@withContext
                showChooser(project, editor, virtualFile, repo.root, pickable, blobs, e, scope)
            }
        }

        private fun showChooser(
            project: Project,
            editor: Editor,
            virtualFile: VirtualFile,
            repoRoot: java.nio.file.Path,
            branches: List<LocalBranch>,
            blobs: GitBlobReader,
            e: AnActionEvent,
            scope: CoroutineScope,
        ) {
            val popup = JBPopupFactory.getInstance()
                .createPopupChooserBuilder(branches)
                .setTitle("Compare ${virtualFile.name} with…")
                .setRenderer(BranchListRenderer())
                .setNamerForFiltering { it.name }
                .setItemChosenCallback { branch ->
                    scope.launch {
                        openDiff(project, editor, virtualFile, repoRoot, branch, blobs)
                    }
                }
                .createPopup()
            val component = e.inputEvent?.component
            if (component != null) popup.showUnderneathOf(component) else popup.showInBestPositionFor(e.dataContext)
        }

        private suspend fun openDiff(
            project: Project,
            editor: Editor,
            virtualFile: VirtualFile,
            repoRoot: java.nio.file.Path,
            branch: LocalBranch,
            blobs: GitBlobReader,
        ) {
            val relativePath = try {
                repoRoot.relativize(virtualFile.toNioPath()).toString().replace('\\', '/')
            } catch (_: Throwable) {
                virtualFile.name
            }
            val state = BranchLensSettings.getInstance().state.copyState()
            val branchText: String? = when (val blob = blobs.read(repoRoot, branch.headCommit, relativePath, state.maxFileBytes)) {
                is BlobResult.Text -> blob.content
                BlobResult.NotFound -> null
                BlobResult.Binary -> {
                    withContext(Dispatchers.EDT) {
                        showHint(editor, null, "Branch Lens: '${relativePath}' is binary or too large in ${branch.name}")
                    }
                    return
                }
            }

            withContext(Dispatchers.EDT) {
                if (project.isDisposed) return@withContext
                val factory = DiffContentFactory.getInstance()
                val fileType = virtualFile.fileType
                val currentContent = factory.create(project, editor.document.text, fileType)
                val branchContent = if (branchText != null) {
                    factory.create(project, branchText, fileType)
                } else {
                    factory.createEmpty()
                }
                val request = SimpleDiffRequest(
                    "Branch Lens — ${virtualFile.name}: current vs ${branch.name}",
                    currentContent,
                    branchContent,
                    "${virtualFile.name}  (current)",
                    "${virtualFile.name}  @ ${branch.name}",
                )
                DiffManager.getInstance().showDiff(project, request)
            }
        }

        private fun showHint(editor: Editor, @Suppress("UNUSED_PARAMETER") e: AnActionEvent?, text: String) {
            val balloon = JBPopupFactory.getInstance()
                .createHtmlTextBalloonBuilder(text, com.intellij.openapi.ui.MessageType.INFO, null)
                .setFadeoutTime(4_000)
                .createBalloon()
            balloon.show(
                com.intellij.ui.awt.RelativePoint.getNorthEastOf(editor.contentComponent),
                com.intellij.openapi.ui.popup.Balloon.Position.atRight,
            )
        }
    }
}

private class BranchListRenderer : ColoredListCellRenderer<LocalBranch>() {
    private val dateFmt: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

    override fun customizeCellRenderer(
        list: JList<out LocalBranch>,
        value: LocalBranch,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean,
    ) {
        append(value.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
        val author = value.authorName
        val time = value.committerDateEpochSeconds
        val tail = buildList {
            author?.let { add(it) }
            time?.let { add(dateFmt.format(Instant.ofEpochSecond(it))) }
        }
        if (tail.isNotEmpty()) {
            append("  — ${tail.joinToString(", ")}", SimpleTextAttributes.GRAY_ITALIC_ATTRIBUTES)
        }
    }
}
