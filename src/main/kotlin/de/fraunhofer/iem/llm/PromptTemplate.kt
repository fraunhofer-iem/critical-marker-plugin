package de.fraunhofer.iem.llm

import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiEllipsisType
import com.intellij.psi.PsiParameterList
import com.intellij.psi.PsiType
import com.intellij.psi.search.GlobalSearchScope


object PromptTemplate {

    fun getSystemPrompt(): String {
        return """You are an assistant with expertise in explaining software security concepts in code snippets. You will be given a code snippet and the software metrics used to identify the security criticality of that method. Your task is to explain the developers and make them understand why the given code is critical and the recommended practices (do's) and common pitfall (don'ts) so that they dont introduce vulnerabilities unintentionally. 

When providing a response, follow the below guidelines:
- The explanation must not exceed 750 words.
- Provide the explanation as a basic string value.
- Do not hallucinate or assume, your response should be based on the given information.
- If the given method is not critical for security, just response with this method is not critical for the security and give the reason why it is not critical, but simply do not assume to make it critical.
- Do not add any other unique characters to the block section, i.e.: triple backticks or triple quotes. Do not include scalars.
- For recommendedPractises and commonPitfall, if you have more than one points, please separate it using the semicolon (;). Please do not use numbering such as 1. 2. etc. Separate the points using semicolon only strictly.
- Format the response as a YAML document using the schema below:

---
overview: "<overview explaining why the given method is critical for security perspective>"
recommendedPractises: "<Provide the recommended practices to avoid security issues for the given method>"
commonPitfall: "<Common pitfall or donts that developer must avoid in the given method>"
"""
    }

    fun buildUserPrompt(metricName: String, metricValue: Number, methodCode: String): String {
        return """Explain the given code why it is security critical, recommended practices, and common pitfall. .

Code Snippet:
```
$methodCode
```

Metric used to compute the security criticality of the given method and its value:
```
$metricName : $metricValue
```

"""
    }

    private val SIG =
        Regex("^\\s*([\\w.$]+)#(\\w+)\\s*\\((.*)\\)\\s*$")

    fun getMethodCode(project: Project, signature: String): String? {
        val m = SIG.matchEntire(signature) ?: return null
        val (classFqn, methodName, paramsBlob) = m.destructured

        var expectedParams = splitParams(paramsBlob)
        if (expectedParams.size == 1 && expectedParams[0].isBlank()) {
            expectedParams = mutableListOf()
        }

        val psiClass = JavaPsiFacade.getInstance(project)
            .findClass(classFqn, GlobalSearchScope.allScope(project))
            ?: return null

        val candidates = psiClass.findMethodsByName(methodName, false)
        for (method in candidates) {
            if (!parametersMatch(method.parameterList, expectedParams)) continue
            if (method.body != null) {
                return method.text
            }
        }
        return null
    }

    // Splits "List<String>, int[], Map<K,V>" by commas not inside <>
    private fun splitParams(src: String): MutableList<String> {
        if (src.isBlank()) return mutableListOf("")
        val out = mutableListOf<String>()
        var depth = 0
        var start = 0
        for (i in src.indices) {
            when (val c = src[i]) {
                '<' -> depth++
                '>' -> if (depth > 0) depth--
                ',' -> if (depth == 0) {
                    out += src.substring(start, i).trim()
                    start = i + 1
                }
            }
        }
        out += src.substring(start).trim()
        return out
    }

    private fun parametersMatch(actualParams: PsiParameterList, expectedParams: List<String>): Boolean {
        if (actualParams.parametersCount != expectedParams.size) return false
        val ps = actualParams.parameters
        for (i in ps.indices) {
            if (!typeMatches(ps[i].type, expectedParams[i])) return false
        }
        return true
    }

    /**
     * Compares an actual PsiType against an expected type string.
     * Supports primitives, arrays (e.g., int[]), varargs (String...),
     * short vs canonical names (String vs java.lang.String), and generic erasures.
     */
    private fun typeMatches(actual: PsiType, expectedRaw: String): Boolean {
        var expected = expectedRaw.trim()
        if (expected.isEmpty()) return false

        // Normalize varargs to array
        if (expected.endsWith("...")) {
            expected = expected.removeSuffix("...").trim() + "[]"
        }

        // Candidates to compare to actual
        val candidates = mutableListOf(
            expected,
            stripGenerics(expected)
        )

        // Add java.lang.* variants for unqualified common types
        if (looksUnqualifiedJavaLang(expected)) {
            candidates += "java.lang.$expected"
        }
        if (expected.endsWith("[]")) {
            val comp = expected.removeSuffix("[]").trim()
            if (looksUnqualifiedJavaLang(comp)) {
                candidates += "java.lang.$comp[]"
            }
        }

        val actualCanon = actual.canonicalText                    // e.g., java.util.List<java.lang.String>
        val actualCanonErased = stripGenerics(actualCanon)        // e.g., java.util.List
        val actualPresentable = actual.presentableText            // e.g., List<String>
        val actualPresentableErased = stripGenerics(actualPresentable) // e.g., List

        for (c in candidates) {
            if (c == actualCanon ||
                c == actualCanonErased ||
                c == actualPresentable ||
                c == actualPresentableErased
            ) return true
        }

        // Special-case: varargs PsiEllipsisType vs expected array
        if (actual is PsiEllipsisType && expected.endsWith("[]")) {
            val compExpected = expected.removeSuffix("[]").trim()
            return typeMatches(actual.componentType, compExpected)
        }

        return false
    }

    // Remove generic <...> segments (shallow) and whitespace for easier equality checks
    private fun stripGenerics(s: String): String {
        val sb = StringBuilder()
        var depth = 0
        for (ch in s) {
            when (ch) {
                '<' -> depth++
                '>' -> if (depth > 0) depth--
                else -> if (depth == 0) sb.append(ch)
            }
        }
        return sb.toString().replace("\\s+".toRegex(), "")
    }

    private fun looksUnqualifiedJavaLang(type: String): Boolean {
        val t = type.replace("[]", "").trim()
        if ('.' in t) return false
        if (t.isNotEmpty() && t[0].isUpperCase()) {
            return when (t) {
                "Byte", "Short", "Integer", "Long",
                "Float", "Double", "Boolean", "Character",
                "String", "Object", "Void" -> true
                else -> false
            }
        }
        return false
    }
}