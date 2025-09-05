package de.fraunhofer.iem.metricsUtil

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import de.fraunhofer.iem.llm.LlmClient
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
                    res.put(metSig, exp)
                }
            }
        }

        println("----> Completed generating explanation in $timeTaken milliseconds")

        return Pair(res, methodToLevelMap)
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