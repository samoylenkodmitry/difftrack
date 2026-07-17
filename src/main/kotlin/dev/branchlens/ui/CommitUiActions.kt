package dev.branchlens.ui

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import dev.branchlens.model.BlameInfo
import java.awt.datatransfer.StringSelection
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object CommitUiActions {
    private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

    fun actions(project: Project, repoRoot: Path?, blame: BlameInfo?): List<AnAction> {
        if (repoRoot == null || blame == null || blame.commitHash.all { it == '0' }) return emptyList()
        return listOf(
            object : AnAction("Show Commit Details") {
                override fun actionPerformed(e: AnActionEvent) {
                    val details = buildList {
                        add("Commit: ${blame.commitHash}")
                        blame.author?.let { add("Author: $it") }
                        blame.authorMail?.let { add("Email: $it") }
                        blame.authorTimeEpochSeconds?.let { add("Date: ${dateFmt.format(Instant.ofEpochSecond(it))}") }
                        blame.summary?.let { add("\n$it") }
                    }.joinToString("\n")
                    Messages.showInfoMessage(project, details, "Branch Lens Commit")
                }
            },
            object : AnAction("Show Commit in Git Log") {
                override fun actionPerformed(e: AnActionEvent) = showInLog(project, repoRoot, blame.commitHash)
            },
            object : AnAction("Copy Commit Hash") {
                override fun actionPerformed(e: AnActionEvent) {
                    CopyPasteManager.getInstance().setContents(StringSelection(blame.commitHash))
                }
            },
        )
    }

    private fun showInLog(project: Project, repoRoot: Path, commitHash: String) {
        try {
            val root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(repoRoot)
                ?: error("Repository root is unavailable")
            val hashClass = Class.forName("com.intellij.vcs.log.impl.HashImpl")
            val hash = hashClass.getMethod("build", String::class.java).invoke(null, commitHash)
            val hashApi = Class.forName("com.intellij.vcs.log.Hash")
            val filePathApi = Class.forName("com.intellij.openapi.vcs.FilePath")
            val navigation = Class.forName("com.intellij.vcs.log.impl.VcsLogNavigationUtil")
            navigation.getMethod(
                "jumpToRevisionAsync",
                Project::class.java,
                com.intellij.openapi.vfs.VirtualFile::class.java,
                hashApi,
                filePathApi,
            ).invoke(null, project, root, hash, null)
        } catch (t: Throwable) {
            Messages.showInfoMessage(
                project,
                "Unable to open Git Log automatically. Commit: $commitHash\n${t.message.orEmpty()}",
                "Branch Lens",
            )
        }
    }
}
