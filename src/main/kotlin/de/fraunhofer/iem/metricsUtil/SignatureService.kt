package de.fraunhofer.iem.metricsUtil

import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.psi.PsiManager
import com.intellij.util.concurrency.EdtScheduledExecutorService
import de.fraunhofer.iem.Notification
import de.fraunhofer.iem.cache.PersistentCacheService

@Service(Service.Level.PROJECT)
class SignatureService(private val project: Project) {
    private val log = Logger.getInstance(SignatureService::class.java)
    private val persistentCache = project.getService(PersistentCacheService::class.java)


    // Pull the generator (your impl) from DI
    private val generator: CriticalMethodGenerator =
        project.getService(CriticalMethodGenerator::class.java)
            ?: error("CriticalMethodGenerator service not found")

    init {
        warmUpAsync(isRecompute = false)
    }

    /** Called at project open (and can be called manually) to recompute the list. */
    fun refresh() = warmUpAsync(isRecompute = true)

    /**
     * Returns an explanation if known. If it's a miss, queue a debounced recompute and return null.
     * Your LineMarkerProvider will be invoked again after recompute finishes due to the daemon restart.
     */
    fun explanationFor(signature: String): String? {
        return persistentCache.getExplanation(signature)
    }

    fun levelFor(signature: String): String? {
        return persistentCache.getLevel(signature)
    }

    fun allSignatures(): Set<String> = persistentCache.getAllSignatures()
    
    /**
     * Clear all caches (for testing/debugging)
     */
    fun clearAllCaches() {
        persistentCache.clearAllCaches()
    }
    
    /**
     * Check if cache is valid for current project state
     */
    fun isCacheValid(): Boolean {
        return persistentCache.isCacheValid()
    }

    private fun warmUpAsync(isRecompute: Boolean) {
        val bgTask = project.getService(CriticalMethodGenerator::class.java).isBackgroundTaskRunning()

        // Prevent multiple background tasks from running simultaneously
        if (!bgTask.compareAndSet(false, false)) {
            log.info("Background task already running, skipping duplicate request")
            return
        }

        var computingWord = "computing"

        if (isRecompute) {
            computingWord = "re-computing"
        }

        val message = "Started $computingWord critical methods using ${MetricState.getInstance().getSelected().label}. Explanations will appear as they are generated."
        Notification.notifyInfo(project, message)

        ReadAction
            .nonBlocking<Pair<Map<String, String>, Map<String, String>>> {
                // Use incremental generation for better UX
                generator.generate(project) { signature, explanation ->
                    // Update persistent cache immediately when explanation is ready
                    persistentCache.storeExplanation(signature, explanation)
                    
                    // Trigger UI refresh for this specific method
                    refreshAnnotationsForSignature(signature)
                }
            }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.nonModal()) { result ->
                // Store all results in persistent cache
                persistentCache.storeExplanations(result.first)
                persistentCache.storeLevels(result.second)

                // 🔄 Re-run LineMarkerProviders for open files so getLineMarkerInfo(...) runs again
                val fem = FileEditorManager.getInstance(project)
                val psiManager = PsiManager.getInstance(project)
                for (vf in fem.openFiles) {
                    psiManager.findFile(vf)?.let { psi ->
                        if (psi.isValid) {
                            DaemonCodeAnalyzer.getInstance(project).restart(psi)
                        }
                    }
                }
                // For a heavier global refresh you could use:
                // DaemonCodeAnalyzer.getInstance(project).restart()
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun refreshAnnotationsForSignature(signature: String) {
        // Trigger a focused refresh for the specific signature
        // This will cause the annotator to re-run for methods matching this signature
        ApplicationManager.getApplication().invokeLater {
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
}
