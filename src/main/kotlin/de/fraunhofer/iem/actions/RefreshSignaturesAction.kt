package de.fraunhofer.iem.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import de.fraunhofer.iem.metricsUtil.SignatureService

class RefreshSignaturesAction : AnAction("Refresh") {
    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.project ?: return
        project.getService(SignatureService::class.java).refresh()
    }
}