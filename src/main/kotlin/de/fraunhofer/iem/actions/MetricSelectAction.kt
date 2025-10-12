package de.fraunhofer.iem.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import de.fraunhofer.iem.metricsUtil.Metric
import de.fraunhofer.iem.metricsUtil.MetricState
import de.fraunhofer.iem.metricsUtil.SignatureService


class MetricSelectAction(private val metric: Metric) :
    ToggleAction(metric.label + " (${metric.id})") {

    override fun isSelected(e: AnActionEvent): Boolean =
        MetricState.getInstance().getSelected() == metric

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        if (state) {
            MetricState.getInstance().setSelected(metric)
            // Optionally refresh signatures when selection changes
            e.project?.getService(SignatureService::class.java)?.refresh()
        }
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.EDT
    }
}
