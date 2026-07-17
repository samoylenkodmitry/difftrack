package dev.branchlens.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import com.intellij.util.xmlb.XmlSerializerUtil

private const val PROJECT_SETTINGS_SCHEMA_VERSION = 1

enum class BranchScopeMode {
    RECENT,
    PINNED,
    ALL,
}

data class BranchLensProjectSettingsState(
    var branchScopeMode: BranchScopeMode = BranchScopeMode.RECENT,
    var pinnedBranches: MutableList<String> = mutableListOf("main", "master"),
    var includeRemoteTrackingBranches: Boolean = true,
    var schemaVersion: Int = PROJECT_SETTINGS_SCHEMA_VERSION,
) {
    fun copyState(): BranchLensProjectSettingsState = BranchLensProjectSettingsState(
        branchScopeMode = branchScopeMode,
        pinnedBranches = pinnedBranches.toMutableList(),
        includeRemoteTrackingBranches = includeRemoteTrackingBranches,
        schemaVersion = schemaVersion,
    )
}

fun interface BranchLensProjectSettingsListener {
    fun settingsChanged()
}

@Service(Service.Level.PROJECT)
@State(
    name = "dev.branchlens.BranchLensProjectSettings",
    storages = [Storage(StoragePathMacros.WORKSPACE_FILE)],
)
class BranchLensProjectSettings(private val project: Project) :
    PersistentStateComponent<BranchLensProjectSettingsState> {

    private var state = BranchLensProjectSettingsState()

    override fun getState(): BranchLensProjectSettingsState = state

    override fun loadState(state: BranchLensProjectSettingsState) {
        XmlSerializerUtil.copyBean(state, this.state)
        if (this.state.schemaVersion < PROJECT_SETTINGS_SCHEMA_VERSION) {
            this.state.includeRemoteTrackingBranches = true
            this.state.schemaVersion = PROJECT_SETTINGS_SCHEMA_VERSION
        }
    }

    fun update(block: BranchLensProjectSettingsState.() -> Unit) {
        val before = state.copyState()
        state.block()
        if (before != state) project.messageBus.syncPublisher(TOPIC).settingsChanged()
    }

    companion object {
        @JvmField
        val TOPIC: Topic<BranchLensProjectSettingsListener> = Topic.create(
            "Branch Lens project settings changed",
            BranchLensProjectSettingsListener::class.java,
        )

        fun getInstance(project: Project): BranchLensProjectSettings =
            project.getService(BranchLensProjectSettings::class.java)
    }
}
