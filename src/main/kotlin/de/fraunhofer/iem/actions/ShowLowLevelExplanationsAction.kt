package de.fraunhofer.iem.actions

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import de.fraunhofer.iem.Settings
import de.fraunhofer.iem.metricsUtil.SignatureService

class ShowLowLevelExplanationsAction : ToggleAction("Show Low Level Explanations") {
    override fun isSelected(e: AnActionEvent): Boolean {
        return Settings.getInstance().shouldShowLowLevelExplanations()
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.EDT
    }

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        Settings.getInstance().setShowLowLevelExplanations(state)
        
        // Trigger refresh of annotations and gutter icons
        val project = e.project ?: return
        refreshAnnotations(project)
    }
    
    private fun refreshAnnotations(project: Project) {
        // Clear the cache to force regeneration
        val signatureService = project.getService(SignatureService::class.java)
        signatureService.refresh()
        
        // Trigger UI refresh for all open files
        val fem = FileEditorManager.getInstance(project)
        val psiManager = PsiManager.getInstance(project)
        for (vf in fem.openFiles) {
            psiManager.findFile(vf)?.let { psi ->
                if (psi.isValid) {
                    DaemonCodeAnalyzer.getInstance(project).restart(psi)
                }
            }
        }
    }
}
