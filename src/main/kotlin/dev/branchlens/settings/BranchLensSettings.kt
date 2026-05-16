package dev.branchlens.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil
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
        state.block()
    }

    companion object {
        fun getInstance(): BranchLensSettings =
            ApplicationManager.getApplication().getService(BranchLensSettings::class.java)
    }
}
