package de.fraunhofer.iem.actions

import com.intellij.openapi.actionSystem.*
import de.fraunhofer.iem.metricsUtil.Metric

class SecurityMarkerActionGroup : ActionGroup("Security Critical Marker", true) {
    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        // Create a submenu called "Metric" containing all single-choice toggles
        val metricGroup = DefaultActionGroup("Metric", true).apply {
            Metric.values().forEach { add(MetricSelectAction(it)) }
        }

        // Add toggle for Low level explanations
        val showLowLevelAction = ActionManager.getInstance().getAction("de.fraunhofer.iem.ShowLowLevelExplanations")
            ?: ShowLowLevelExplanationsAction()

        // Add toggle for Auto refresh
        val toggleAutoRefreshAction = ActionManager.getInstance().getAction("de.fraunhofer.iem.ToggleAutoRefresh")
            ?: ToggleAutoRefreshAction()

        val refresh = ActionManager.getInstance().getAction("de.fraunhofer.iem.RefreshSignatures")
            ?: RefreshSignaturesAction()

        return arrayOf(metricGroup, showLowLevelAction, toggleAutoRefreshAction, refresh)
    }
}