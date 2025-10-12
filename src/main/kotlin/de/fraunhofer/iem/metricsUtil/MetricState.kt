package de.fraunhofer.iem.metricsUtil

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

enum class Metric(val id: String, val label: String) {
    COMPLEXITY("CC", "Cyclomatic Complexity"),
    LOC("LOC", "Lines of Code"),
    LCOM("LCOM", "Lack of Cohesion of Methods"),
    //TRYCATCHQTY("TRYCATCHQTY", "Number of try catch blocks"),
    //LOGSTMT("LOGSTMT", "Number of log statements"),
}

@State(name = "SecurityMarkerMetricsState", storages = [Storage("security-marker.xml")])
@Service(Service.Level.APP)
class MetricState : PersistentStateComponent<MetricState.State> {

    data class State(var selected: String = Metric.COMPLEXITY.id)

    private var state = State()

    override fun getState(): State = state
    override fun loadState(state: State) { this.state = state }

    fun getSelected(): Metric = Metric.values().firstOrNull { it.id == state.selected } ?: Metric.COMPLEXITY
    fun setSelected(metric: Metric) { state.selected = metric.id }

    companion object {
        fun getInstance(): MetricState =
            ApplicationManager.getApplication().getService(MetricState::class.java)
    }
}