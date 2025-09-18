package de.fraunhofer.iem.metricsUtil

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.xml.util.XmlStringUtil
import de.fraunhofer.iem.Notification
import de.fraunhofer.iem.Settings
import de.fraunhofer.iem.llm.LlmClient
import de.fraunhofer.iem.llm.Pricing
import org.yaml.snakeyaml.Yaml
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.measureTimeMillis

interface CriticalMethodGenerator {
    /** Return map: signature -> explanation and signature -> explanation */
    /** Generate explanations incrementally with callback for updates */
    fun generate(
        project: Project,
        onExplanationReady: (signature: String, explanation: String) -> Unit
    ): Pair<Map<String, String>, Map<String, String>>

    fun isBackgroundTaskRunning(): AtomicBoolean
}


@Service(Service.Level.PROJECT)
class DefaultCriticalMethodGenerator : CriticalMethodGenerator {
    private val log = Logger.getInstance(DefaultCriticalMethodGenerator::class.java)
    private val first: MutableMap<String, String> = mutableMapOf()
    private val second: MutableMap<String, String> = mutableMapOf()
    private val res: Pair<Map<String, String>, Map<String, String>> = Pair(first, second)
    
    // Prevents multiple background tasks from running simultaneously
    val backgroundTaskRunning = AtomicBoolean(false)

    override fun isBackgroundTaskRunning(): AtomicBoolean {
        return backgroundTaskRunning
    }

    override fun generate(
        project: Project,
        onExplanationReady: (signature: String, explanation: String) -> Unit
    ): Pair<Map<String, String>, Map<String, String>> {


        val metric = MetricState.getInstance().getSelected()

        val criticalMethods = mutableMapOf<String, Number>()
        var timeTaken = measureTimeMillis {
            for (srcPath in getSourceRoots(project)) {
                criticalMethods.putAll(getCriticalMethod(srcPath, metric, project))
            }
        }

        val nonZeroCriticalMethods = filterZeros(criticalMethods)

        val methodToLevelMap = QuantileClassificationWithEqualFreq.discretize(
            nonZeroCriticalMethods.mapValues { (_, v) -> v.toDouble() },
            3,
            true,
            listOf("LOW", "MEDIUM", "HIGH")
        )

        Notification.notifyInfo(project, "Completed generating metrics in $timeTaken milliseconds")

        // Process the methods from high critical to low critical
        val orderedCriticalMethods = nonZeroCriticalMethods.toList()
            .sortedByDescending { (_, value) -> value.toDouble() }
            .toMap()

        // Pre-compute method code within read action to avoid threading issues
        val methodCodeMap = mutableMapOf<String, String?>()
        ReadAction.nonBlocking<String> {
            orderedCriticalMethods.keys.forEach { signature ->
                methodCodeMap[signature] = de.fraunhofer.iem.llm.PromptTemplate.getMethodCode(project, signature)
            }

            "Success"
        }.inSmartMode(project).executeSynchronously()

        if (!backgroundTaskRunning.compareAndSet(false, true)) {
            log.info("Background task already running, skipping duplicate request")
            return res
        }

        // Create initial explanations with placeholders
        val res = mutableMapOf<String, String>()
        val settings = Settings.getInstance()
        val showLowLevelExplanations = settings.shouldShowLowLevelExplanations()

        // Start background explanation generation with pre-computed method code
        generateExplanationsInBackground(project, orderedCriticalMethods, methodToLevelMap, methodCodeMap, onExplanationReady)

        first.putAll(res)
        second.putAll(methodToLevelMap)
        return this.res
    }

    private fun htmlTooltip(
        overview: String?,
        recommended: String?,
        metric: String,
        metricValue: Number,
        criticalityLevel: String
    ): String {

        val html = """
        <b>Security Criticality: </b> $metric=$metricValue, $criticalityLevel
        <p>$overview</p>
        <p><b>Precautions: </b>
        ${bulletList(recommended)}</p>
    """.trimIndent()

        // Wrap with <html>…</html> for IntelliJ tooltips
        return XmlStringUtil.wrapInHtml(html)
    }

    private fun bulletList(raw: String?): String {
        if (raw.isNullOrBlank()) return "<i>No items</i>"
        val items = raw.split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { XmlStringUtil.escapeString(it, false) }
            .joinToString("") { "<li>$it</li>" }
        return "<ul>$items</ul>"
    }

    private fun loadAndGetLlmResponseAsMap(llmResponse: String): Map<String, String> {
        val yaml = Yaml()
        val data: Map<String, String> = yaml.load(llmResponse)

        return data
    }

    private fun getOverview(llmResponse: String): String {
        return loadAndGetLlmResponseAsMap(llmResponse).getOrDefault("overview", "NA")
    }

    private fun getRecommendedPractises(llmResponse: String): String {
        return loadAndGetLlmResponseAsMap(llmResponse).getOrDefault("prevention", "NA")
    }

    private fun getCommonPitfall(llmResponse: String): String {
        return loadAndGetLlmResponseAsMap(llmResponse).getOrDefault("commonPitfall", "NA")
    }

    //TODO: This is for testing purposes only. We need to remove this and make sure to run explanation generation in the background so it runs anmd display incrementally
    private fun filter(sig: String): Boolean {
        if (sig.contains("org.springframework.samples.petclinic.vet.Vet#getNrOfSpecialties"))
            return false

        if (sig.contains("org.springframework.samples.petclinic.owner.OwnerController#findOwner"))
            return false

        if (sig.contains("org.springframework.samples.petclinic.PetClinicApplication#main"))
            return false

        if (sig.contains("org.springframework.samples.petclinic.owner.OwnerController#showOwner"))
            return false

        if (sig.contains("org.springframework.samples.petclinic.owner.OwnerController#processCreationForm"))
            return false

        if (sig.contains("org.springframework.samples.petclinic.owner.OwnerController#processFindForm"))
            return false

        if (sig.contains("org.springframework.samples.petclinic.owner.OwnerController#initUpdateOwnerForm"))
            return false

        return true
    }
    private fun getSourceRoots(project: Project): List<String> {
        val roots = ProjectRootManager.getInstance(project).contentSourceRoots
        return roots.map { it.path }   // absolute paths on disk
    }

    private fun filterZeros(map: Map<String, Number>): Map<String, Number> {
        return map.filterValues { it.toDouble() != 0.0 }
    }

    private fun createPlaceholderExplanation(metricLabel: String, metricValue: Number, criticalityLevel: String): String {
        var note = "<b>Note:</b> <i>$metricLabel</i> metric is used to assess security criticality, and its score is <i>$metricValue</i>."

        if (criticalityLevel != "NA") {
            note += " This method falls under the <i>$criticalityLevel</i> level."
        }

        val html = """
        <b>Overview</b><br/>
        <i>Generating explanation...</i>
        <br/><br/>
        <b>Recommended Practices</b>
        <i>Generating recommendations...</i>
        <br/>
        <b>Common Pitfalls</b>
        <i>Generating pitfalls...</i>
        <br/>
        <span style="color:gray;">
            $note
        </span>
    """.trimIndent()

        return XmlStringUtil.wrapInHtml(html)
    }

    private fun shouldGenerateExplanation(
        signature: String, 
        methodToLevelMap: Map<String, String>, 
        showLowLevelExplanations: Boolean
    ): Boolean {
        val level = methodToLevelMap[signature] ?: "NA"
        return when (level.uppercase()) {
            "LOW" -> showLowLevelExplanations
            "MEDIUM", "HIGH", "VERY_LOW", "VERY_HIGH" -> true
            else -> true
        }
    }

    private fun generateExplanationsInBackground(
        project: Project,
        criticalMethods: Map<String, Number>,
        methodToLevelMap: Map<String, String>,
        methodCodeMap: Map<String, String?>,
        onExplanationReady: (signature: String, explanation: String) -> Unit
    ) {
        val metric = MetricState.getInstance().getSelected()
        val settings = Settings.getInstance()
        val showLowLevelExplanations = settings.shouldShowLowLevelExplanations()
        
        // Filter methods based on settings and existing filter
        val methodsToProcess = criticalMethods.filterKeys { signature ->
            !filter(signature) && shouldGenerateExplanation(signature, methodToLevelMap, showLowLevelExplanations)
        }
        val totalMethods = methodsToProcess.size

        val task = object : Task.Backgroundable(project, "Generating Security Critical Explanations", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false
                indicator.fraction = 0.0
                indicator.text = "Starting explanation generation for $totalMethods methods..."

                var completedMethods = 0

                methodsToProcess.forEach { (metSig, metricValue) ->
                    // Check if task was cancelled
                    if (indicator.isCanceled) {
                        return
                    }

                    try {
                        indicator.text = "Generating explanation for: $metSig"
                        
                        // Use pre-computed method code to avoid PSI access from background thread
                        val methodCode = methodCodeMap[metSig] ?: "Method code not available"
                        val exp = LlmClient.sendRequest(metSig, metric.label, metricValue, methodCode)
                        val overview = getOverview(exp)
                        val recommendedPractices = getRecommendedPractises(exp)
                        val commonPitfall = getCommonPitfall(exp)
                        val fullExplanation = htmlTooltip(
                            overview,
                            recommendedPractices,
                            metric.label,
                            metricValue,
                            methodToLevelMap.getOrDefault(metSig, "NA")
                        )

                        completedMethods++

                        // Update progress
                        val progress = completedMethods.toDouble() / totalMethods
                        indicator.fraction = progress
                        indicator.text2 = "Completed $completedMethods of $totalMethods methods"

                        // Notify on UI thread
                        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                            onExplanationReady(metSig, fullExplanation)
                        }
                    } catch (e: Exception) {
                        log.warn("Failed to generate explanation for $metSig: ${e.message}")
                        completedMethods++

                        // Create error explanation
                        val errorExplanation = createErrorExplanation(metric.label, metricValue, methodToLevelMap.getOrDefault(metSig, "NA"), e.message ?: "Unknown error")
                        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                            onExplanationReady(metSig, errorExplanation)
                        }

                        // Update progress even for failed methods
                        val progress = completedMethods.toDouble() / totalMethods
                        indicator.fraction = progress
                        indicator.text2 = "Completed $completedMethods of $totalMethods methods (with errors)"
                    }
                }

                // Final logging
                log.warn("\uD83D\uDD34Total input tokens = " + Pricing.totalInputTokens)
                log.warn("\uD83D\uDD34Total output tokens = " + Pricing.totalOutputTokens)
                log.warn("\uD83D\uDD34Total request sent = " + Pricing.totalRequestSent)
                log.warn("\uD83D\uDD34Total cost = " + Pricing.totalCost)
                log.warn("\uD83D\uDD34Total discounted cost = " + Pricing.discountedTotalCost)
                log.warn("\uD83D\uDD34Total number of methods for explanation generation = " + methodsToProcess.size)
            }

            override fun onSuccess() {
                try {
                    Notification.notifyInfo(project, "Successfully completed explanation generation for all methods")
                    log.info("Successfully completed explanation generation for all methods")
                } finally {
                    // Always reset the flag when the task completes
                    backgroundTaskRunning.set(false)
                }
            }

            override fun onCancel() {
                try {
                    Notification.notifyInfo(project, "Explanation generation was cancelled by user")
                    log.warn("Explanation generation was cancelled by user")
                } finally {
                    // Always reset the flag when the task is cancelled
                    backgroundTaskRunning.set(false)
                }
            }
        }

        // Start the background task
        task.queue()
    }

    private fun createErrorExplanation(metric: String, metricValue: Number, criticalityLevel: String, errorMessage: String): String {

        val html = """
        <p><b>Security Criticality: </b> $metric=$metricValue, $criticalityLevel</p>
        <p>A security critical assessment explanation is not available for this method.</p>
    """.trimIndent()

        return XmlStringUtil.wrapInHtml(html)
    }
}