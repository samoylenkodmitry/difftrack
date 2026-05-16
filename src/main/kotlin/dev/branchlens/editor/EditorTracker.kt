package dev.branchlens.editor

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project

/**
 * Helper for the project service: enumerates currently selected text editors for a project.
 *
 * Listeners that need to react to document changes are registered directly on the
 * editor factory by [dev.branchlens.BranchLensProjectService] using a [DocumentListener];
 * we keep this class as a thin discovery utility.
 */
object EditorTracker {

    fun currentlyOpenTextEditors(project: Project): List<Editor> {
        if (project.isDisposed) return emptyList()
        val manager = FileEditorManager.getInstance(project)
        val out = mutableListOf<Editor>()
        for (file in manager.selectedFiles) {
            for (editor in manager.getEditors(file)) {
                val te = (editor as? TextEditor)?.editor ?: continue
                if (te.project === project) out += te
            }
        }
        return out
    }
}
