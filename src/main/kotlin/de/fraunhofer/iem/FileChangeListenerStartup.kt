package de.fraunhofer.iem

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import de.fraunhofer.iem.listeners.FileChangeListener

/**
 * Startup activity to ensure FileChangeListener service is instantiated
 */
class FileChangeListenerStartup : StartupActivity {
    private val log = Logger.getInstance(FileChangeListenerStartup::class.java)
    
    override fun runActivity(project: Project) {
        try {
            // Force instantiation of the FileChangeListener service
            val listener = ApplicationManager.getApplication().getService(FileChangeListener::class.java)
            log.info("FileChangeListener service instantiated for project: ${project.name}")
        } catch (e: Exception) {
            log.error("Failed to instantiate FileChangeListener service for project: ${project.name}", e)
        }
    }
}
