package de.fraunhofer.iem.annotators

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import de.fraunhofer.iem.SecurityTextAttributes
import de.fraunhofer.iem.Settings
import de.fraunhofer.iem.metricsUtil.SignatureService
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.getContainingUClass
import org.jetbrains.uast.toUElementOfType

class SecurityMethodAnnotator : Annotator, DumbAware {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val javaMethod = (element.parent as? PsiMethod)?.takeIf { it.nameIdentifier == element } ?: return
        val uMethod = javaMethod.toUElementOfType<UMethod>() ?: return

        val signature = buildSignature(uMethod) ?: return
        val service = element.project.getService(SignatureService::class.java)
        val explanation = service.explanationFor(signature) ?: return
        val level = service.levelFor(signature) ?: return
        
        // Check if we should show this method based on settings
        val settings = Settings.getInstance()
        val showLowLevelExplanations = settings.shouldShowLowLevelExplanations()
        
        if (level.uppercase() == "LOW" && !showLowLevelExplanations) {
            return
        }

        val color = when (level.uppercase()) {
            "LOW" -> SecurityTextAttributes.Low
            "HIGH" -> SecurityTextAttributes.High
            "MEDIUM" -> SecurityTextAttributes.Medium
            else -> SecurityTextAttributes.Low
        }

        val informationLevel = when (level.uppercase()) {
            "LOW" -> HighlightSeverity.INFORMATION
            "HIGH" -> HighlightSeverity.WARNING
            "MEDIUM" -> HighlightSeverity.WEAK_WARNING
            else -> HighlightSeverity.INFORMATION
        }

        // ✅ highlight only the name token (always inside the element)
        holder.newAnnotation(HighlightSeverity.WARNING, explanation)
            .range(element.textRange)
            .tooltip(explanation)
            .enforcedTextAttributes(color)
            .create()
    }

    private fun buildSignature(method: UMethod): String? {
        val cls = method.getContainingUClass()?.qualifiedName ?: return null
        val name = method.name
        val params = method.uastParameters.joinToString(",") { it.type.canonicalText }
        return "$cls#$name($params)"
    }
}
