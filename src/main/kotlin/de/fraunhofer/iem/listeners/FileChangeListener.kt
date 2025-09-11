package de.fraunhofer.iem.listeners

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.concurrency.AppExecutorUtil
import de.fraunhofer.iem.Settings
import de.fraunhofer.iem.metricsUtil.SignatureService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Listens for file changes and automatically triggers refresh of security metrics
 * when source files are saved.
 */
class FileChangeListener : BulkFileListener {
    
    private val log = Logger.getInstance(FileChangeListener::class.java)
    private val refreshScheduled = AtomicBoolean(false)
    private val debounceDelayMs = 2000L // 2 seconds debounce
    private val projectsToRefresh = mutableSetOf<Project>()
    
    init {
        log.info("FileChangeListener created")
    }
    
    override fun before(events: MutableList<out VFileEvent>) {
        // We don't need to do anything before file changes
    }
    
    override fun after(events: MutableList<out VFileEvent>) {
        log.info("FileChangeListener.after() called with ${events.size} events")
        
        // Filter for file save events in source files and collect affected projects
        val sourceFileEvents = events.filter { event ->
            isSourceFileEvent(event)
        }
        
        log.info("Found ${sourceFileEvents.size} source file events out of ${events.size} total events")
        
        if (sourceFileEvents.isNotEmpty()) {
            // Find which projects these files belong to
            val affectedProjects = sourceFileEvents.mapNotNull { event ->
                findProjectForFile(event.file)
            }.toSet()
            
            if (affectedProjects.isNotEmpty()) {
                synchronized(projectsToRefresh) {
                    projectsToRefresh.addAll(affectedProjects)
                }
                log.info("Detected ${sourceFileEvents.size} source file changes in ${affectedProjects.size} project(s), scheduling refresh")
                scheduleDebouncedRefresh()
            } else {
                log.info("No projects found for the changed files")
            }
        }
    }
    
    private fun isSourceFileEvent(event: VFileEvent): Boolean {
        val file = event.file
        if (file == null || file.isDirectory) return false
        
        // Check if it's a source file (Java, Kotlin, etc.)
        val fileName = file.name.lowercase()
        val isSourceFile = fileName.endsWith(".java") || 
                          fileName.endsWith(".kt") || 
                          fileName.endsWith(".kts")
        
        // Only trigger on content changes (not just metadata changes)
        val isContentChange = event.isFromSave || event.isFromRefresh
        
        return isSourceFile && isContentChange
    }
    
    private fun findProjectForFile(file: com.intellij.openapi.vfs.VirtualFile?): Project? {
        if (file == null) return null
        
        val projectManager = ProjectManager.getInstance()
        val openProjects = projectManager.openProjects
        
        for (project in openProjects) {
            if (project.isDisposed) continue
            
            try {
                val projectRootManager = ProjectRootManager.getInstance(project)
                val contentRoots = projectRootManager.contentRoots
                
                // Check if the file is within any of the project's content roots
                for (contentRoot in contentRoots) {
                    if (file.path.startsWith(contentRoot.path)) {
                        return project
                    }
                }
            } catch (e: Exception) {
                log.warn("Error checking project ${project.name} for file ${file.path}: ${e.message}")
            }
        }
        
        return null
    }
    
    private fun scheduleDebouncedRefresh() {
        // Check if auto refresh is enabled
        val settings = Settings.getInstance()
        if (!settings.isAutoRefreshEnabled()) {
            log.info("Auto refresh is disabled, skipping refresh")
            return
        }
        
        // Use compareAndSet to ensure only one refresh is scheduled at a time
        if (refreshScheduled.compareAndSet(false, true)) {
            log.info("Scheduling debounced refresh in ${debounceDelayMs}ms")
            AppExecutorUtil.getAppScheduledExecutorService().schedule({
                try {
                    refreshAllProjects()
                } finally {
                    refreshScheduled.set(false)
                }
            }, debounceDelayMs, TimeUnit.MILLISECONDS)
        } else {
            log.info("Refresh already scheduled, skipping duplicate request")
        }
    }
    
    private fun refreshAllProjects() {
        ApplicationManager.getApplication().invokeLater {
            val projectsToRefreshCopy = synchronized(projectsToRefresh) {
                val copy = projectsToRefresh.toSet()
                projectsToRefresh.clear()
                copy
            }
            
            if (projectsToRefreshCopy.isEmpty()) {
                log.info("No projects to refresh")
                return@invokeLater
            }
            
            log.info("Starting auto-refresh for ${projectsToRefreshCopy.size} affected project(s)")
            
            for (project in projectsToRefreshCopy) {
                if (project.isDisposed) continue
                
                try {
                    val signatureService = project.getService(SignatureService::class.java)
                    signatureService?.refresh()
                    log.info("Triggered refresh for project: ${project.name}")
                } catch (e: Exception) {
                    // Log error but don't fail the entire operation
                    log.warn("Error refreshing project ${project.name}: ${e.message}")
                }
            }
        }
    }
}
