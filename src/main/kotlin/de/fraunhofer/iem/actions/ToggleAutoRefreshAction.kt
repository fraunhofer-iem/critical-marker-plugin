package de.fraunhofer.iem.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import de.fraunhofer.iem.Settings

class ToggleAutoRefreshAction : ToggleAction({"Auto Refresh on Save"}, null) {
    
    override fun isSelected(e: AnActionEvent): Boolean {
        return Settings.getInstance().isAutoRefreshEnabled()
    }
    
    override fun setSelected(e: AnActionEvent, state: Boolean) {
        Settings.getInstance().setAutoRefreshEnabled(state)
    }
    
    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.EDT
    }
}
