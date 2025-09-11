package de.fraunhofer.iem

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.diagnostic.Logger


@State(name = "SecurityMarkerSettings", storages = [Storage("security-marker-settings.xml")])
@Service(Service.Level.APP)
class Settings : PersistentStateComponent<Settings.State> {
    private val log = Logger.getInstance(Settings::class.java)

    data class State(
        var showLowLevelExplanations: Boolean = false
    )

    private var state = State()

    override fun getState(): State = state
    override fun loadState(state: State) { this.state = state }

    fun shouldShowLowLevelExplanations(): Boolean = state.showLowLevelExplanations
    fun setShowLowLevelExplanations(show: Boolean) { state.showLowLevelExplanations = show }

    companion object {
        fun getInstance(): Settings = ApplicationManager.getApplication().getService(Settings::class.java)

        private fun parseJsonMap(text: String): Map<String, String> {
// tiny JSON parser to avoid bringing a dependency; assumes a flat object of string:string
            val regex = "\\\"([^\\\"]+)\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"".toRegex()
            return regex.findAll(text).associate { it.groupValues[1] to it.groupValues[2] }
        }
    }
}