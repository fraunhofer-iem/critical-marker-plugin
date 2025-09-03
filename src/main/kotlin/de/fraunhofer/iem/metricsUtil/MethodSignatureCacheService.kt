package de.fraunhofer.iem.metricsUtil

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiMethod
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker

@Service(Service.Level.PROJECT)
class MethodSignatureCacheService(private val project: Project) {

    private val cache: CachedValue<Set<String>> =
        CachedValuesManager.getManager(project).createCachedValue({
            // compute once per "project change"
            val value = DumbService.getInstance(project).runReadActionInSmartMode<Set<String>> {
                gatherAllMethodSignatures(project, includeLibraries = false)
            }
            // Invalidate only when PSI or project roots change
            CachedValueProvider.Result.create(
                value,
                PsiModificationTracker.getInstance(project),
                ProjectRootModificationTracker.getInstance(project)
            )
        }, /* trackValue = */ false)

    fun getAll(): Set<String> = cache.value
}

// Same helper from before:
private fun PsiMethod.signatureString(): String {
    val owner = containingClass?.qualifiedName ?: "<anonymous>"
    val params = parameterList.parameters.joinToString(",") { it.type.canonicalText }
    val ret = returnType?.presentableText ?: "void"
    // return "$owner#$name($params):$ret"
    return "$owner#$name($params)"
}

private fun gatherAllMethodSignatures(project: Project, includeLibraries: Boolean): Set<String> {
    val scope = if (includeLibraries)
        GlobalSearchScope.everythingScope(project)
    else
        GlobalSearchScope.projectScope(project)

    return ReadAction.compute<Set<String>, RuntimeException> {
        val cache = PsiShortNamesCache.getInstance(project)
        val out = HashSet<String>()
        for (name in cache.allMethodNames) {
            cache.getMethodsByName(name, scope).forEach { out += it.signatureString() }
        }
        out
    }
}
