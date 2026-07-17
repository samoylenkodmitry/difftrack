package dev.branchlens.diff

import com.intellij.diff.DiffContext
import com.intellij.diff.DiffExtension
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer
import com.intellij.diff.util.Side
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorGutter
import com.intellij.openapi.editor.TextAnnotationGutterProvider
import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.util.Key
import dev.branchlens.model.BlameInfo
import dev.branchlens.model.isUncommitted
import java.awt.Color
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.nio.file.Path
import dev.branchlens.ui.CommitUiActions

data class BranchLensDiffAnnotationData(
    val current: Map<Int, BlameInfo>,
    val branch: Map<Int, BlameInfo>,
    val repoRoot: Path? = null,
)

val BRANCH_LENS_DIFF_ANNOTATIONS: Key<BranchLensDiffAnnotationData> =
    Key.create("dev.branchlens.diff.annotations")

/** Adds Branch Lens author/date columns to the two editors in our diff windows. */
class BranchLensDiffExtension : DiffExtension() {
    override fun onViewerCreated(
        viewer: FrameDiffTool.DiffViewer,
        context: DiffContext,
        request: DiffRequest,
    ) {
        val data = request.getUserData(BRANCH_LENS_DIFF_ANNOTATIONS) ?: return
        val textViewer = viewer as? TwosideTextDiffViewer ?: return
        install(textViewer.getEditor(Side.LEFT).gutter, data.current, data.repoRoot)
        install(textViewer.getEditor(Side.RIGHT).gutter, data.branch, data.repoRoot)
    }

    private fun install(gutter: EditorGutter, blame: Map<Int, BlameInfo>, repoRoot: Path?) {
        if (blame.isNotEmpty()) gutter.registerTextAnnotation(BlameAnnotationProvider(blame, repoRoot))
    }
}

private class BlameAnnotationProvider(
    private val blameByOneBasedLine: Map<Int, BlameInfo>,
    private val repoRoot: Path?,
) : TextAnnotationGutterProvider {
    private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

    override fun getLineText(line: Int, editor: Editor): String {
        val blame = blameByOneBasedLine[line + 1] ?: return ""
        if (blame.isUncommitted) return "Uncommitted"
        val author = blame.author?.take(24).orEmpty()
        val date = blame.authorTimeEpochSeconds?.let { dateFmt.format(Instant.ofEpochSecond(it)) }.orEmpty()
        return listOf(author, date).filter { it.isNotEmpty() }.joinToString("  ")
    }

    override fun getToolTip(line: Int, editor: Editor): String? {
        val blame = blameByOneBasedLine[line + 1] ?: return null
        if (blame.isUncommitted) return "Uncommitted working-tree change"
        return buildList {
            blame.author?.let { add(it) }
            blame.authorTimeEpochSeconds?.let { add(dateFmt.format(Instant.ofEpochSecond(it))) }
            add(blame.commitHash.take(8))
            blame.summary?.let { add(it) }
        }.joinToString(" · ")
    }

    override fun getStyle(line: Int, editor: Editor): EditorFontType = EditorFontType.PLAIN
    override fun getColor(line: Int, editor: Editor): ColorKey = EditorColors.ANNOTATIONS_COLOR
    override fun getBgColor(line: Int, editor: Editor): Color? = null
    override fun getPopupActions(line: Int, editor: Editor): List<AnAction> =
        editor.project?.let { CommitUiActions.actions(it, repoRoot, blameByOneBasedLine[line + 1]) }.orEmpty()
    override fun gutterClosed() = Unit

}
