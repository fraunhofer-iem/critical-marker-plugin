package de.fraunhofer.iem.metricsUtil

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.xml.util.XmlStringUtil
import de.fraunhofer.iem.llm.LlmClient
import de.fraunhofer.iem.llm.Pricing
import org.yaml.snakeyaml.Yaml
import kotlin.system.measureTimeMillis

interface CriticalMethodGenerator {
    /** Return map: signature -> explanation and signature -> explanation */
    /** Generate explanations incrementally with callback for updates */
    fun generate(
        project: Project,
        onExplanationReady: (signature: String, explanation: String) -> Unit
    ): Pair<Map<String, String>, Map<String, String>>
}


@Service(Service.Level.PROJECT)
class DefaultCriticalMethodGenerator : CriticalMethodGenerator {
    private val log = Logger.getInstance(DefaultCriticalMethodGenerator::class.java)

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

        val nonZerCriticalMethods = filterZeros(criticalMethods)

        val methodToLevelMap = QuantileClassificationWithEqualFreq.discretize(
            nonZerCriticalMethods.mapValues { (_, v) -> v.toDouble() },
            3,
            true,
            listOf("LOW", "MEDIUM", "HIGH")
        )

        println("----> Completed generating metrics in $timeTaken milliseconds")

        // Pre-compute method code within read action to avoid threading issues
        val methodCodeMap = mutableMapOf<String, String?>()
        com.intellij.openapi.application.ReadAction.run<RuntimeException> {
            criticalMethods.keys.forEach { signature ->
                methodCodeMap[signature] = de.fraunhofer.iem.llm.PromptTemplate.getMethodCode(project, signature)
            }
        }

        // Create initial explanations with placeholders
        val res = mutableMapOf<String, String>()
        criticalMethods.forEach { (metSig, metricValue) ->
            val placeholderExplanation = createPlaceholderExplanation(metric.label, metricValue, methodToLevelMap.getOrDefault(metSig, "NA"))
            res[metSig] = placeholderExplanation
        }

        // Start background explanation generation with pre-computed method code
        generateExplanationsInBackground(criticalMethods, methodToLevelMap, methodCodeMap, onExplanationReady)

        return Pair(res, methodToLevelMap)
    }

    private fun htmlTooltip(
        overview: String?,
        recommended: String?,
        pitfalls: String?,
        metric: String,
        metricValue: Number,
        criticalityLevel: String
    ): String {
        var note = "<b>Note:</b> <i>$metric</i> metric is used to assess security criticality, and its score is <i>$metricValue</i>."

        if (criticalityLevel != "NA") {
            note += " This method falls under the <i>$criticalityLevel</i> level."
        }

        val ov = if (overview.isNullOrBlank())
            "<i>No overview</i>"
        else
            XmlStringUtil.escapeString(overview.trim(), false).replace("\n", "<br/>")

        val html = """
        <b>Overview</b><br/>
        $ov
        <br/><br/>
        <b>Recommended Practices</b>
        ${bulletList(recommended)}
        <br/>
        <b>Common Pitfalls</b>
        ${bulletList(pitfalls)}
        <br/>
        <span style="color:gray;">
            $note
        </span>
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
        return loadAndGetLlmResponseAsMap(llmResponse).getOrDefault("recommendedPractises", "NA")
    }

    private fun getCommonPitfall(llmResponse: String): String {
        return loadAndGetLlmResponseAsMap(llmResponse).getOrDefault("commonPitfall", "NA")
    }

    //TODO: This is for testing purposes only. We need to remove this and make sure to run explanation generation in the background so it runs anmd display incrementally
    private fun filter(sig: String): Boolean {
        if (sig.contains("getNrOfSpecialties"))
            return false

        if (sig.contains("findOwner"))
            return false

        if (sig.contains("main"))
            return false

        if (sig.contains("showOwner"))
            return false

        if (sig.contains("processCreationForm"))
            return false

        if (sig.contains("processFindForm"))
            return false

        if (sig.contains("initUpdateOwnerForm"))
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

    private fun generateExplanationsInBackground(
        criticalMethods: Map<String, Number>,
        methodToLevelMap: Map<String, String>,
        methodCodeMap: Map<String, String?>,
        onExplanationReady: (signature: String, explanation: String) -> Unit
    ) {
        val metric = MetricState.getInstance().getSelected()
        val methodsToProcess = criticalMethods.filterKeys { !filter(it) }

        //val methodsToProcess = criticalMethods
        val totalMethods = methodsToProcess.size
        var completedMethods = 0

        // Process explanations in background thread
        Thread {
            methodsToProcess.forEach { (metSig, metricValue) ->
                try {
                    // Use pre-computed method code to avoid PSI access from background thread
                    val methodCode = methodCodeMap[metSig] ?: "Method code not available"
                    val exp = LlmClient.sendRequest(metSig, metric.label, metricValue, methodCode)
                    val overview = getOverview(exp)
                    val recommendedPractices = getRecommendedPractises(exp)
                    val commonPitfall = getCommonPitfall(exp)
                    val fullExplanation = htmlTooltip(
                        overview,
                        recommendedPractices,
                        commonPitfall,
                        metric.label,
                        metricValue,
                        methodToLevelMap.getOrDefault(metSig, "NA")
                    )

                    completedMethods++

                    // Notify on UI thread
                    com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                        onExplanationReady(metSig, fullExplanation)

                        // Show progress notification at 25%, 50%, 75%, and 100% completion
                        val progressPercentage = (completedMethods * 100) / totalMethods
                        val shouldNotify = when {
                            completedMethods == 1 -> true // First method
                            progressPercentage >= 25 && (completedMethods - 1) * 100 / totalMethods < 25 -> true
                            progressPercentage >= 50 && (completedMethods - 1) * 100 / totalMethods < 50 -> true
                            progressPercentage >= 75 && (completedMethods - 1) * 100 / totalMethods < 75 -> true
                            completedMethods == totalMethods -> true // Last method
                            else -> false
                        }

                        if (shouldNotify) {
                            val progressMessage = "Generated explanations for $completedMethods/$totalMethods methods ($progressPercentage%)"
                            com.intellij.notification.Notifications.Bus.notify(
                                com.intellij.notification.Notification(
                                    "SecurityMarkerNotifications",
                                    "",
                                    progressMessage,
                                    com.intellij.notification.NotificationType.INFORMATION
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    println("Failed to generate explanation for $metSig: ${e.message}")
                    completedMethods++

                    // Create error explanation
                    val errorExplanation = createErrorExplanation(metric.label, metricValue, methodToLevelMap.getOrDefault(metSig, "NA"), e.message ?: "Unknown error")
                    com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                        onExplanationReady(metSig, errorExplanation)
                    }
                }
            }

            log.warn("\uD83D\uDD34Total input tokens = " + Pricing.totalInputTokens)
            log.warn("\uD83D\uDD34Total output tokens = " + Pricing.totalOutputTokens)
            log.warn("\uD83D\uDD34Total request sent = " + Pricing.totalRequestSent)
            log.warn("\uD83D\uDD34Total cost = " + Pricing.totalCost)
            log.warn("\uD83D\uDD34Total discounted cost = " + Pricing.discountedTotalCost)
            log.warn("\uD83D\uDD34Total number of methods for explanation generation = " + methodsToProcess.size)
        }.start()
    }

    private fun createErrorExplanation(metricLabel: String, metricValue: Number, criticalityLevel: String, errorMessage: String): String {
        var note = "<b>Note:</b> <i>$metricLabel</i> metric is used to assess security criticality, and its score is <i>$metricValue</i>."

        if (criticalityLevel != "NA") {
            note += " This method falls under the <i>$criticalityLevel</i> level."
        }

        val html = """
        <b>Overview</b><br/>
        <i style="color: red;">Failed to generate explanation: $errorMessage</i>
        <br/><br/>
        <b>Recommended Practices</b>
        <i>Unable to generate recommendations due to error</i>
        <br/>
        <b>Common Pitfalls</b>
        <i>Unable to generate pitfalls due to error</i>
        <br/>
        <span style="color:gray;">
            $note
        </span>
    """.trimIndent()

        return XmlStringUtil.wrapInHtml(html)
    }
}