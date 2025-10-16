package de.fraunhofer.iem.annotators

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNameIdentifierOwner
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.getUastParentOfType
import org.jetbrains.uast.toUElementOfType
import de.fraunhofer.iem.SecurityTextAttributes
import de.fraunhofer.iem.Settings
import de.fraunhofer.iem.metricsUtil.SignatureService

class KotlinSecurityMethodAnnotator : Annotator, DumbAware {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        // Find the nearest UMethod regardless of Java/Kotlin
        val uMethod = element.toUElementOfType<UMethod>()
            ?: element.getUastParentOfType<UMethod>()
            ?: return

        // Highlight only the method/function name token
        val nameRange = (uMethod.sourcePsi as? PsiNameIdentifierOwner)
            ?.nameIdentifier
            ?.textRange
            ?: return

        // If we're not currently sitting on the name token, bail out
        if (element.textRange != nameRange) return

        val signature = buildSignature(uMethod) ?: return
        val service = element.project.getService(SignatureService::class.java)
        val explanation = service.explanationFor(signature) ?: return
        val level = service.levelFor(signature) ?: return

        val settings = Settings.getInstance()
        val showLowLevelExplanations = settings.shouldShowLowLevelExplanations()
        if (level.equals("LOW", ignoreCase = true) && !showLowLevelExplanations) return

        val color = when (level.uppercase()) {
            "LOW" -> SecurityTextAttributes.Low
            "MEDIUM" -> SecurityTextAttributes.Medium
            "HIGH" -> SecurityTextAttributes.High
            else -> SecurityTextAttributes.Low
        }

        val informationLevel = when (level.uppercase()) {
            "VERY_LOW", "LOW" -> HighlightSeverity.INFORMATION
            "MEDIUM" -> HighlightSeverity.WEAK_WARNING
            "HIGH", "VERY_HIGH" -> HighlightSeverity.WARNING
            else -> HighlightSeverity.INFORMATION
        }

        holder.newAnnotation(informationLevel, explanation)
            .range(nameRange)
            .tooltip(explanation)
            .enforcedTextAttributes(color)
            .create()
    }

    private fun buildSignature(uMethod: UMethod): String? {
        // Prefer the UClass; for Kotlin top-level functions this becomes XxxKt
        val cls = uMethod.javaPsi.containingClass?.qualifiedName
            ?: uMethod.javaPsi.containingClass?.qualifiedName
            ?: return null

        val name = uMethod.name

        // Use the Java PSI view to normalize types across Java/Kotlin
        val params = uMethod.javaPsi.parameterList.parameters
            .joinToString(",") { it.type.canonicalText }

        return "$cls#$name($params)"
    }
}
