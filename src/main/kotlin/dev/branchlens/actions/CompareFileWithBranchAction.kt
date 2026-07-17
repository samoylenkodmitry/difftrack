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
import dev.branchlens.git.BranchPairCollapser
import dev.branchlens.git.GitBlobReader
import dev.branchlens.git.GitBlameRunner
import dev.branchlens.git.GitBranchProvider
import dev.branchlens.git.GitCli
import dev.branchlens.git.GitRepositoryLocator
import dev.branchlens.git.GitPathUtil
import dev.branchlens.git.GitRenameResolver
import dev.branchlens.model.LocalBranch
import dev.branchlens.model.displayName
import dev.branchlens.settings.BranchLensSettings
import dev.branchlens.diff.BRANCH_LENS_DIFF_ANNOTATIONS
import dev.branchlens.diff.BranchLensDiffAnnotationData
import dev.branchlens.util.TempFileUtil
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
 * Surfaced via `Compare File with Git Branch…` so users who don't notice the gutter
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
            cli: GitCli,
        ) {
            val log = thisLogger()
            val state = BranchLensSettings.getInstance().state.copyState()
            val timeout = state.gitCommandTimeoutMs.toLong()
            val locator = GitRepositoryLocator(cli, timeout)
            val branchProvider = GitBranchProvider(cli, timeout)
            val blobs = GitBlobReader(cli, timeout)
            val blameRunner = GitBlameRunner(cli, timeout)
            val renameResolver = GitRenameResolver(cli, timeout)

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

            val includeRemote = dev.branchlens.settings.BranchLensProjectSettings
                .getInstance(project).state.includeRemoteTrackingBranches
            val allBranches = branchProvider.listLocal(repo.root, repo.currentBranch, includeRemote)
            val pickable = BranchPairCollapser.collapse(allBranches).filter { !it.isCurrent }
            if (pickable.isEmpty()) {
                showHint(editor, e, "Branch Lens: no other Git branches to compare against")
                return
            }

            withContext(Dispatchers.EDT) {
                if (project.isDisposed) return@withContext
                showChooser(
                    project,
                    editor,
                    virtualFile,
                    repo.root,
                    repo.headCommit,
                    pickable,
                    blobs,
                    blameRunner,
                    renameResolver,
                    e,
                    scope,
                )
            }
        }

        private fun showChooser(
            project: Project,
            editor: Editor,
            virtualFile: VirtualFile,
            repoRoot: java.nio.file.Path,
            currentHead: String?,
            branches: List<LocalBranch>,
            blobs: GitBlobReader,
            blameRunner: GitBlameRunner,
            renameResolver: GitRenameResolver,
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
                        openDiff(
                            project, editor, virtualFile, repoRoot, currentHead, branch,
                            blobs, blameRunner, renameResolver,
                        )
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
            currentHead: String?,
            branch: LocalBranch,
            blobs: GitBlobReader,
            blameRunner: GitBlameRunner,
            renameResolver: GitRenameResolver,
        ) {
            val relativePath = GitPathUtil.relativePath(repoRoot, virtualFile.toNioPath())
            if (relativePath == null) {
                withContext(Dispatchers.EDT) {
                    showHint(editor, null, "Branch Lens: file is outside the Git repository")
                }
                return
            }
            val state = BranchLensSettings.getInstance().state.copyState()
            var branchPath = relativePath
            var blob = blobs.read(repoRoot, branch.headCommit, branchPath, state.maxFileBytes)
            if (blob is BlobResult.NotFound && currentHead != null) {
                val renamedPath = renameResolver.pathInBranch(
                    repoRoot,
                    branch.headCommit,
                    currentHead,
                    relativePath,
                )
                if (renamedPath != null) {
                    branchPath = renamedPath
                    blob = blobs.read(repoRoot, branch.headCommit, branchPath, state.maxFileBytes)
                }
            }
            val branchText: String? = when (blob) {
                is BlobResult.Text -> blob.content
                BlobResult.NotFound -> null
                BlobResult.Binary -> {
                    withContext(Dispatchers.EDT) {
                        showHint(editor, null, "Branch Lens: '${relativePath}' is binary or too large in ${branch.name}")
                    }
                    return
                }
            }

            val branchBlame = blameRunner.blame(
                repoRoot,
                branch.headCommit,
                branchPath,
                range = null,
                useMoveAware = state.useMoveAwareBlame,
                useCopyAware = state.useCopyAwareBlame,
            )
            val currentBlame = if (currentHead == null) {
                emptyMap()
            } else {
                val currentTmp = TempFileUtil.writeTempUtf8("branchlens-annotate-", ".txt", editor.document.text)
                try {
                    blameRunner.blameContents(
                        repoRoot,
                        currentHead,
                        relativePath,
                        currentTmp,
                        range = null,
                        useMoveAware = state.useMoveAwareBlame,
                        useCopyAware = state.useCopyAwareBlame,
                    )
                } finally {
                    TempFileUtil.safeDelete(currentTmp)
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
                    "Branch Lens — ${virtualFile.name}: current vs ${branch.displayName}",
                    currentContent,
                    branchContent,
                    "${virtualFile.name}  (current)",
                    "$branchPath  @ ${branch.displayName}",
                )
                request.putUserData(
                    BRANCH_LENS_DIFF_ANNOTATIONS,
                    BranchLensDiffAnnotationData(currentBlame, branchBlame, repoRoot),
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
        append(value.displayName, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
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
