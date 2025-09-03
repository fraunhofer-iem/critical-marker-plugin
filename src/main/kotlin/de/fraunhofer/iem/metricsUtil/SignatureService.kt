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
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.psi.PsiManager
import com.intellij.util.concurrency.EdtScheduledExecutorService

@Service(Service.Level.PROJECT)
class SignatureService(private val project: Project) {
    private val log = Logger.getInstance(SignatureService::class.java)
    private val cache = ConcurrentHashMap<String, String>()
    private val levelsCache = ConcurrentHashMap<String, String>()

    // Prevents scheduling many recomputes when lots of misses happen back-to-back
    private val recomputeQueued = AtomicBoolean(false)

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
        cache[signature]?.let { return it }
        queueDebouncedRecompute()
        return null
    }

    fun levelFor(signature: String): String? {
        levelsCache[signature]?.let { return it }
        return null
    }

    fun allSignatures(): Set<String> = cache.keys

    // ---- internals ----

    private fun queueDebouncedRecompute() {
        if (recomputeQueued.compareAndSet(false, true)) {
            // Debounce a little so a burst of misses becomes a single recompute
            EdtScheduledExecutorService.getInstance().schedule({
                try {
                    // This triggers a single background recompute
                    warmUpAsync(isRecompute = false)
                } finally {
                    // allow future recomputes after this one is scheduled
                    recomputeQueued.set(false)
                }
            },
                30, TimeUnit.SECONDS)
        }
    }

    private fun warmUpAsync(isRecompute: Boolean) {
        ReadAction
            .nonBlocking<Pair<Map<String, String>, Map<String, String>>> {
                generator.generate(project) // <-- your method runs here (potentially heavy)
            }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.nonModal()) { result ->
                cache.clear()
                cache.putAll(result.first)
                levelsCache.clear()
                levelsCache.putAll(result.second)

                var computingWord = "computing"

                if (isRecompute) {
                    computingWord = "re-computing"
                }

                val message = "Completed $computingWord critical methods using ${MetricState.getInstance().getSelected().label}"
                ApplicationManager.getApplication().executeOnPooledThread {
                    Notifications.Bus.notify(
                        Notification(
                            "SecurityMarkerNotifications",
                            "",
                            message,
                            NotificationType.INFORMATION
                        )
                    )
                }

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
}
