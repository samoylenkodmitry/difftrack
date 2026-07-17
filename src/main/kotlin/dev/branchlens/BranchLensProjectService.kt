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
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.branchlens.actions.CompareFileWithBranchAction
import dev.branchlens.cache.BranchLensCache
import dev.branchlens.editor.GutterBadgeRenderer
import dev.branchlens.git.GitBlameRunner
import dev.branchlens.git.GitBlobReader
import dev.branchlens.git.GitBranchProvider
import dev.branchlens.git.GitCli
import dev.branchlens.git.GitDiffRunner
import dev.branchlens.git.GitRepositoryLocator
import dev.branchlens.git.GitRenameResolver
import dev.branchlens.git.GitHistoryAnalyzer
import dev.branchlens.model.BranchLensSettingsState
import dev.branchlens.model.FileAnalysisResult
import dev.branchlens.settings.BranchLensSettings
import dev.branchlens.settings.BranchLensSettingsListener
import dev.branchlens.settings.BranchLensProjectSettings
import dev.branchlens.settings.BranchLensProjectSettingsListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

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
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<ResultListener>()
    private val started = AtomicBoolean(false)
    private val cliLock = Any()
    @Volatile private var cliHolder: Pair<Int, GitCli>? = null
    @Volatile private var activeEditor: Editor? = null

    fun interface ResultListener {
        fun onResultChanged(editor: Editor?, virtualFile: VirtualFile?, result: FileAnalysisResult?)
    }

    fun addResultListener(listener: ResultListener): com.intellij.openapi.Disposable {
        listeners.add(listener)
        // Push the current snapshot so a freshly-opened tool window sees state without
        // waiting for the next analysis tick.
        val current = activeEditor
        val file = current?.let { FileDocumentManager.getInstance().getFile(it.document) }
        listener.onResultChanged(current, file, current?.let { lastResult[it] })
        return com.intellij.openapi.Disposable { listeners.remove(listener) }
    }

    private fun fireResult(editor: Editor?, result: FileAnalysisResult?) {
        val file = editor?.let { FileDocumentManager.getInstance().getFile(it.document) }
        for (l in listeners) {
            try {
                l.onResultChanged(editor, file, result)
            } catch (t: Throwable) {
                log.warn("Branch Lens result listener threw: ${t.message}", t)
            }
        }
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        val bus = project.messageBus.connect(project)
        bus.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) = scheduleForCurrentlyOpen()
            override fun fileOpened(source: FileEditorManager, file: VirtualFile) = scheduleForCurrentlyOpen()
            override fun fileClosed(source: FileEditorManager, file: VirtualFile) = cleanupClosed()
        })
        bus.subscribe(BranchLensSettings.TOPIC, BranchLensSettingsListener { settingsChanged() })
        bus.subscribe(
            BranchLensProjectSettings.TOPIC,
            BranchLensProjectSettingsListener { settingsChanged() },
        )

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
        started.set(false)
        for (job in analysisJobs.values) job.cancel()
        analysisJobs.clear()
        for ((editor, _) in lastResult) renderer.clear(editor)
        lastResult.clear()
        attachedDocs.clear()
    }

    /**
     * Launches the "Compare file with branch…" chooser using the service's coroutine
     * scope so cancellation is tied to the project lifecycle.
     */
    fun launchCompareFlow(editor: com.intellij.openapi.editor.Editor, file: VirtualFile, e: AnActionEvent) {
        if (project.isDisposed) return
        scope.launch(Dispatchers.Default) {
            try {
                val settings = BranchLensSettings.getInstance().state.copyState()
                CompareFileWithBranchAction.runCompareFlow(
                    scope,
                    project,
                    editor,
                    file,
                    e,
                    gitCli(settings),
                )
            } catch (_: CancellationException) {
                // expected on project dispose
            } catch (t: Throwable) {
                log.warn("Branch Lens compare flow failed: ${t.message}", t)
            }
        }
    }

    private fun marginLines(): Int = BranchLensSettings.getInstance().state.visibleRenderMarginLines

    private fun scheduleForCurrentlyOpen() {
        if (project.isDisposed) return
        val manager = FileEditorManager.getInstance(project)
        var firstEditor: Editor? = null
        for (file in manager.selectedFiles) {
            for (editor in manager.getEditors(file)) {
                val textEditor = (editor as? TextEditor)?.editor ?: continue
                if (textEditor.project !== project) continue
                attachedDocs.getOrPut(textEditor.document) { mutableSetOf() }.add(textEditor)
                if (firstEditor == null) firstEditor = textEditor
                scheduleAnalysis(textEditor)
            }
        }
        activeEditor = firstEditor
        fireResult(firstEditor, firstEditor?.let { lastResult[it] })
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

        val cli = gitCli(settings)
        val timeout = settings.gitCommandTimeoutMs.toLong()
        val projectSettings = BranchLensProjectSettings.getInstance(project).state.copyState()
        val analyzer = BranchLensAnalyzer(
            locator = GitRepositoryLocator(cli, timeout),
            branches = GitBranchProvider(cli, timeout),
            blobs = GitBlobReader(cli, timeout),
            renameResolver = GitRenameResolver(cli, timeout),
            diffRunner = GitDiffRunner(cli, timeout),
            blameRunner = GitBlameRunner(cli, timeout),
            historyAnalyzer = GitHistoryAnalyzer(cli, timeout),
            settings = BranchLensAnalyzer.AnalyzerSettings(
                maxLines = settings.maxLines,
                maxFileBytes = settings.maxFileBytes,
                maxBranches = settings.maxBranches,
                staleBranchDays = settings.staleBranchDays,
                includeStaleBranches = settings.includeStaleBranches,
                ignoreWhitespace = settings.ignoreWhitespaceInDiff,
                useMoveAwareBlame = settings.useMoveAwareBlame,
                useCopyAwareBlame = settings.useCopyAwareBlame,
                excludedBranchPatterns = settings.excludedBranchPatterns.toList(),
                maxConcurrentGitProcesses = settings.maxConcurrentGitProcesses,
                branchScopeMode = projectSettings.branchScopeMode,
                pinnedBranchNames = projectSettings.pinnedBranches.toSet(),
                includeRemoteTrackingBranches = projectSettings.includeRemoteTrackingBranches,
            ),
            cache = BranchLensCache.getInstance(project),
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
            if (editor === activeEditor) fireResult(editor, result)
        }
    }

    /**
     * Re-runs analysis for the currently active editor on demand (used by the tool-window
     * Refresh action).
     */
    fun refreshActive() {
        val editor = activeEditor ?: return
        scheduleAnalysis(editor)
    }

    fun activeEditor(): Editor? = activeEditor

    fun repositoryChanged() {
        invalidateAndSchedule()
    }

    private fun settingsChanged() {
        invalidateAndSchedule()
    }

    private fun invalidateAndSchedule() {
        if (project.isDisposed) return
        scope.launch(Dispatchers.EDT) {
            if (project.isDisposed) return@launch
            for (job in analysisJobs.values) job.cancel()
            analysisJobs.clear()
            BranchLensCache.getInstance(project).clear()
            for (editor in lastResult.keys) renderer.clear(editor)
            lastResult.clear()
            activeEditor?.let { fireResult(it, null) }
            scheduleForCurrentlyOpen()
        }
    }

    private fun gitCli(settings: BranchLensSettingsState): GitCli {
        val maxConcurrent = settings.maxConcurrentGitProcesses.coerceAtLeast(1)
        cliHolder?.takeIf { it.first == maxConcurrent }?.let { return it.second }
        return synchronized(cliLock) {
            cliHolder?.takeIf { it.first == maxConcurrent }?.second
                ?: GitCli(maxConcurrent = maxConcurrent).also { cliHolder = maxConcurrent to it }
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
