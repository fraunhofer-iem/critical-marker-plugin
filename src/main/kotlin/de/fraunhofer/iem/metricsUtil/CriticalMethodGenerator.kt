package de.fraunhofer.iem.metricsUtil

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.xml.util.XmlStringUtil
import de.fraunhofer.iem.llm.LlmClient
import org.yaml.snakeyaml.Yaml
import kotlin.system.measureTimeMillis

interface CriticalMethodGenerator {
    /** Return map: signature -> explanation and signature -> explanation */
    //TODO: Improve the data structure here
    fun generate(project: Project): Pair<Map<String, String>, Map<String, String>>
}

@Service(Service.Level.PROJECT)
class DefaultCriticalMethodGenerator : CriticalMethodGenerator {
    override fun generate(project: Project): Pair<Map<String, String>, Map<String, String>> {
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

        val res = mutableMapOf<String, String>()

        timeTaken = measureTimeMillis {
            criticalMethods.mapValues { (metSig, metricValue) ->
                if (filter(metSig)) {
                    res[metSig] = "Score using ${metric}: ${metricValue}\nExplanation: Under construction"
                } else {
                    val exp = LlmClient.sendRequest(project, metSig, metric.label, metricValue)
                    val overview = getOverview(exp)
                    val recommendedPractices = getRecommendedPractises(exp)
                    val commonPitfall = getCommonPitfall(exp)
                    res.put(metSig, htmlTooltip(overview, recommendedPractices, commonPitfall, metric.label, metricValue, methodToLevelMap.getOrDefault(metSig, "NA")))
                }
            }
        }

        println("----> Completed generating explanation in $timeTaken milliseconds")

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

        return true
    }
    private fun getSourceRoots(project: Project): List<String> {
        val roots = ProjectRootManager.getInstance(project).contentSourceRoots
        return roots.map { it.path }   // absolute paths on disk
    }

    private fun filterZeros(map: Map<String, Number>): Map<String, Number> {
        return map.filterValues { it.toDouble() != 0.0 }
    }
}