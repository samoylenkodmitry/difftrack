package dev.branchlens

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.VisibleAreaEvent
import com.intellij.openapi.editor.event.VisibleAreaListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.branchlens.editor.GutterBadgeRenderer
import dev.branchlens.git.GitBlobReader
import dev.branchlens.git.GitBranchProvider
import dev.branchlens.git.GitCli
import dev.branchlens.git.GitDiffRunner
import dev.branchlens.git.GitRepositoryLocator
import dev.branchlens.model.BranchLensSettingsState
import dev.branchlens.model.FileAnalysisResult
import dev.branchlens.settings.BranchLensSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class BranchLensProjectService(
    private val project: Project,
    private val scope: CoroutineScope,
) {
    private val log = thisLogger()

    private val analysisJobs = ConcurrentHashMap<Editor, Job>()
    private val lastResult = ConcurrentHashMap<Editor, FileAnalysisResult>()
    private val attachedDocs = ConcurrentHashMap<Document, MutableSet<Editor>>()
    private val renderer = GutterBadgeRenderer()

    fun start() {
        val bus = project.messageBus.connect(project)
        bus.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) = scheduleForCurrentlyOpen()
            override fun fileOpened(source: FileEditorManager, file: VirtualFile) = scheduleForCurrentlyOpen()
            override fun fileClosed(source: FileEditorManager, file: VirtualFile) = cleanupClosed()
        })

        EditorFactory.getInstance().eventMulticaster.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    val doc = event.document
                    val editors = attachedDocs[doc] ?: return
                    for (editor in editors) {
                        renderer.clear(editor)
                        scheduleAnalysis(editor)
                    }
                }
            },
            project,
        )

        EditorFactory.getInstance().eventMulticaster.addVisibleAreaListener(
            object : VisibleAreaListener {
                override fun visibleAreaChanged(e: VisibleAreaEvent) {
                    val editor = e.editor
                    if (editor.project !== project) return
                    val result = lastResult[editor] ?: return
                    if (result is FileAnalysisResult.Computed) {
                        renderer.applyVisible(editor, result, marginLines())
                    }
                }
            },
            project,
        )

        scheduleForCurrentlyOpen()
    }

    fun stop() {
        for (job in analysisJobs.values) job.cancel()
        analysisJobs.clear()
        for ((editor, _) in lastResult) renderer.clear(editor)
        lastResult.clear()
        attachedDocs.clear()
    }

    private fun marginLines(): Int = BranchLensSettings.getInstance().state.visibleRenderMarginLines

    private fun scheduleForCurrentlyOpen() {
        if (project.isDisposed) return
        val manager = FileEditorManager.getInstance(project)
        for (file in manager.selectedFiles) {
            for (editor in manager.getEditors(file)) {
                val textEditor = (editor as? TextEditor)?.editor ?: continue
                if (textEditor.project !== project) continue
                attachedDocs.getOrPut(textEditor.document) { mutableSetOf() }.add(textEditor)
                scheduleAnalysis(textEditor)
            }
        }
    }

    private fun cleanupClosed() {
        if (project.isDisposed) return
        val activeEditors = mutableSetOf<Editor>()
        val manager = FileEditorManager.getInstance(project)
        for (file in manager.openFiles) {
            for (editor in manager.getEditors(file)) {
                val te = (editor as? TextEditor)?.editor ?: continue
                activeEditors += te
            }
        }
        val closedEditors = analysisJobs.keys - activeEditors
        for (editor in closedEditors) {
            analysisJobs.remove(editor)?.cancel()
            lastResult.remove(editor)
            renderer.clear(editor)
            for ((_, set) in attachedDocs) set.remove(editor)
        }
    }

    private fun scheduleAnalysis(editor: Editor) {
        if (project.isDisposed) return
        val settings = BranchLensSettings.getInstance().state.copyState()
        if (!settings.enabled) {
            renderer.clear(editor)
            return
        }
        analysisJobs[editor]?.cancel()
        val job = scope.launch(Dispatchers.Default) {
            try {
                delay(settings.analysisDebounceMs.toLong())
                runAnalysis(editor, settings)
            } catch (_: CancellationException) {
                // Expected — superseded by a newer event.
            } catch (t: Throwable) {
                log.warn("Branch Lens analysis failed: ${t.message}", t)
            }
        }
        analysisJobs[editor] = job
    }

    private suspend fun runAnalysis(editor: Editor, settings: BranchLensSettingsState) {
        val snapshot = readAction {
            buildSnapshot(editor)
        }
        if (snapshot == null) {
            withContext(Dispatchers.EDT) { renderer.clear(editor) }
            return
        }

        val cli = GitCli(maxConcurrent = settings.maxConcurrentGitProcesses)
        val timeout = settings.gitCommandTimeoutMs.toLong()
        val analyzer = BranchLensAnalyzer(
            locator = GitRepositoryLocator(cli, timeout),
            branches = GitBranchProvider(cli, timeout),
            blobs = GitBlobReader(cli, timeout),
            diffRunner = GitDiffRunner(cli, timeout),
            settings = BranchLensAnalyzer.AnalyzerSettings(
                maxLines = settings.maxLines,
                maxFileBytes = settings.maxFileBytes,
                maxBranches = settings.maxBranches,
                staleBranchDays = settings.staleBranchDays,
                includeStaleBranches = settings.includeStaleBranches,
                ignoreWhitespace = settings.ignoreWhitespaceInDiff,
                excludedBranchPatterns = settings.excludedBranchPatterns.toList(),
            ),
        )

        val result = analyzer.analyze(snapshot)
        lastResult[editor] = result
        withContext(Dispatchers.EDT) {
            if (project.isDisposed) return@withContext
            when (result) {
                is FileAnalysisResult.Computed -> renderer.applyVisible(editor, result, marginLines())
                is FileAnalysisResult.Skipped -> renderer.clear(editor)
                FileAnalysisResult.NotComputed -> renderer.clear(editor)
            }
        }
    }

    private fun buildSnapshot(editor: Editor): EditorSnapshot? {
        if (project.isDisposed) return null
        val document = editor.document
        val file = FileDocumentManager.getInstance().getFile(document) ?: return null
        if (file.isDirectory || !file.isInLocalFileSystem) return null
        val filePath = try {
            file.toNioPath()
        } catch (_: Throwable) {
            return null
        }
        return EditorSnapshot(
            filePath = filePath,
            text = document.text,
            documentStamp = document.modificationStamp,
        )
    }
}
