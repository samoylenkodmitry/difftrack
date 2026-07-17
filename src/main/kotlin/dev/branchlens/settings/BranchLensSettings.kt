package dev.branchlens.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil
import com.intellij.util.messages.Topic
import dev.branchlens.model.BranchLensSettingsState

@Service(Service.Level.APP)
@State(
    name = "dev.branchlens.BranchLensSettings",
    storages = [Storage("branchlens.xml")],
)
class BranchLensSettings : PersistentStateComponent<BranchLensSettingsState> {

    private var state: BranchLensSettingsState = BranchLensSettingsState()

    override fun getState(): BranchLensSettingsState = state

    override fun loadState(state: BranchLensSettingsState) {
        XmlSerializerUtil.copyBean(state, this.state)
    }

    fun update(block: BranchLensSettingsState.() -> Unit) {
        val before = state.copyState()
        state.block()
        if (before != state) {
            ApplicationManager.getApplication().messageBus.syncPublisher(TOPIC).settingsChanged()
        }
    }

    companion object {
        @JvmField
        val TOPIC: Topic<BranchLensSettingsListener> = Topic.create(
            "Branch Lens application settings changed",
            BranchLensSettingsListener::class.java,
            Topic.BroadcastDirection.TO_CHILDREN,
        )

        fun getInstance(): BranchLensSettings =
            ApplicationManager.getApplication().getService(BranchLensSettings::class.java)
    }
}

fun interface BranchLensSettingsListener {
    fun settingsChanged()
}
