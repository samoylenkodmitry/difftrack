package dev.branchlens.git

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import dev.branchlens.BranchLensProjectService
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener

/** Git4Idea-backed refresh hook, loaded only when the optional Git plugin is present. */
class BranchLensGitStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (project.isDisposed) return
        project.messageBus.connect(project).subscribe(
            GitRepository.GIT_REPO_CHANGE,
            GitRepositoryChangeListener {
                project.service<BranchLensProjectService>().repositoryChanged()
            },
        )
    }
}
