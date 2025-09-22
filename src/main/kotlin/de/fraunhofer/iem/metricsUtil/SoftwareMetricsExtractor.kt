@file:JvmName("SoftwareMetricsExtractor")

package de.fraunhofer.iem.metricsUtil

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.github.mauricioaniche.ck.CK
import com.intellij.openapi.project.Project
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.IOException

// ---------- Mutable metric stores ----------
private val wmc = mutableMapOf<String, Int>()
private val loc = mutableMapOf<String, Int>()
private val lcomNorm = mutableMapOf<String, Float>()
private val tryCatchQty = mutableMapOf<String, Int>()
// private val uniqueWordsQty = mutableMapOf<String, Int>()
private val logStmtQty = mutableMapOf<String, Int>()

// ---------- Models ----------
private data class MethodSearchResult(
    val status: Boolean,
    val methodSignature: String?
)

@Throws(IOException::class)
private fun saveMetrics(sortedMethods: List<String>, filename: String) {
    val target = File(filename)
    target.parentFile?.mkdirs()
    ObjectMapper(YAMLFactory()).writeValue(target, sortedMethods)
}

// ---------- Sorting / filtering ----------
private fun getSortedListFromMapInteger(map: Map<String, Int>): List<String> =
    map.entries
        .sortedByDescending { it.value }
        .map { it.key }

private fun getSortedListFromMapFloat(map: Map<String, Float>): List<String> =
    map.entries
        .sortedByDescending { it.value }
        .map { it.key }

private fun filterZerosInFloatMap(map: Map<String, Float>): Map<String, Float> =
    map.filterValues { it != 0.0f }

private fun filterZerosInIntMap(map: Map<String, Int>): Map<String, Int> =
    map.filterValues { it != 0 }

// ---------- String utils ----------
fun extractBracketedContent(input: String): String? {
    val start = input.indexOf('[')
    if (start == -1) return null

    var count = 1
    var i = start + 1
    while (i < input.length && count > 0) {
        when (input[i]) {
            '[' -> count++
            ']' -> count--
        }
        i++
    }
    return if (count == 0) input.substring(start + 1, i - 1) else null
}

// ---------- Method lookup ----------
private fun getMethod(
    className: String,
    methodName: String
): MethodSearchResult {
    var foundSig: String? = null
    val duplicates = mutableSetOf<String>()

    val parts = methodName.split("/")
    var pureMethodName = parts[0]
    val classSimple = className.substringAfterLast('.')

    val params: String = if (parts.size > 1) {
        val tail = parts[1].trim()
        if (tail == "0") "" else extractBracketedContent(tail).orEmpty()
    } else {
        ""
    }

    val sig = "${className}#${pureMethodName}(${params})"
    return MethodSearchResult(true, sig)
}

fun getCriticalMethod(projSrcPath: String, metric: Metric, project: Project): MutableMap<String, out Number> {
    CK().calculate(projSrcPath) { classResult ->
        classResult.methods.forEach { method ->
            val searchRes = getMethod(
                className = classResult.className,
                methodName = method.methodName
            )

            if (searchRes.status) {
                val sig = searchRes.methodSignature!!
                wmc[sig] = method.wmc
                loc[sig] = method.loc
                lcomNorm[sig] = classResult.lcomNormalized
                tryCatchQty[sig] = method.tryCatchQty
                // uniqueWordsQty[sig] = method.uniqueWordsQty
                logStmtQty[sig] = method.logStatementsQty
            } else {
                println("Could not find method: ${classResult.className}.${method.methodName}")
            }
        }
    }

    val processedWmc = processMethodSignatures(wmc, project)
    val processedLoc = processMethodSignatures(loc, project)
    val processedLcomNorm = processMethodSignatures(lcomNorm, project)
    val processedTryCatchQty = processMethodSignatures(tryCatchQty, project)
    // val processedUniqueWordsQty = processMethodSignatures(uniqueWordsQty, project)
    val processedLogStmtQty = processMethodSignatures(logStmtQty, project)

    return when (metric) {
        Metric.COMPLEXITY -> processedWmc
        Metric.LOC -> processedLoc
        Metric.LCOM -> processedLcomNorm
        Metric.TRYCATCHQTY -> processedTryCatchQty
        // Metric.UNIQUEWORDS -> processedUniqueWordsQty
        Metric.LOGSTMT -> processedLogStmtQty
    }
}

fun processMethodSignatures(data: Map<String, Number>, project: Project): MutableMap<String, Number> {
    val allMethods =  project.getService(MethodSignatureCacheService::class.java).getAll()
    val processedData = mutableMapOf<String, Number>()

    data.forEach { (sig, score) ->
        if (allMethods.contains(sig)) {
            processedData[sig] = score
        } else {
            processedData[getClosestMatchedMethod(sig, allMethods)] = score
        }
    }

    return processedData
}

fun getClosestMatchedMethod(sig: String, allMethods: Set<String>): String {
    val paramsA = sig.split("(")[1].split(")")[0].split(",")
    val firstPartA = sig.split("(")[0]

    outer@ for (method in allMethods) {
        val firstPartB = method.split("(")[0]

        if (firstPartB != firstPartA) {
            continue@outer
        }

        val paramsB = method.split("(")[1].split(")")[0].split(",")

        if (paramsB.size != paramsA.size) {
            continue@outer
        }

        if (paramsB.size == 1) {
            if (paramsB[0] == "")
                continue@outer
        }

        var newParams = ""

        inner@ for ((index, param) in paramsA.withIndex()) {
            if (paramsB[index] == param) {
                newParams += "$param,"
                continue@inner
            }

            val typeA = param.split(".")[param.split(".").size - 1]
            val typeB = paramsB[index].split(".")[paramsB[index].split(".").size - 1]

            if (typeA != typeB) {
                continue@outer
            }

            newParams += "${paramsB[index]},"
        }

        newParams = newParams.dropLast(1)

        return method
    }

    return sig
}
