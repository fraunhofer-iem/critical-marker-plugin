package de.fraunhofer.iem

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.util.IconLoader
import com.intellij.psi.PsiElement
import de.fraunhofer.iem.Settings
import de.fraunhofer.iem.metricsUtil.SignatureService
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.getContainingUClass
import org.jetbrains.uast.toUElementOfType
import javax.swing.Icon


class SecurityMarker : LineMarkerProvider {
    private val icon: Icon = IconLoader.getIcon("/icons/shield_very_high.svg", javaClass)
    private val lowIcon: Icon = IconLoader.getIcon("/icons/shield_low.svg", javaClass)
    private val mediumIcon: Icon = IconLoader.getIcon("/icons/shield_medium.svg", javaClass)
    private val highIcon: Icon = IconLoader.getIcon("/icons/shield_high.svg", javaClass)


    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
// Convert PSI -> UAST; only interested in methods
        val uElement = element.toUElementOfType<UMethod>() ?: return null


// For performance, only mark at the UMethod identifier (the name)
        val ident = uElement.uastAnchor?.sourcePsi ?: return null


        val signature = buildSignature(uElement) ?: return null

        val explanation = element.project.getService(SignatureService::class.java).explanationFor(signature)
            ?: return null

        val level = element.project.getService(SignatureService::class.java).levelFor(signature) ?: return null
        
        // Check if we should show this method based on settings
        val settings = Settings.getInstance()
        val showLowLevelExplanations = settings.shouldShowLowLevelExplanations()
        
        if (level.uppercase() == "LOW" && !showLowLevelExplanations) {
            return null
        }

        val tooltip = { "$explanation\nLevel: $level\n\nSignature: $signature" }

        val selectedIcon = when (level) {
            "LOW" -> lowIcon
            "MEDIUM" -> mediumIcon
            "HIGH" -> highIcon
            else -> icon
        }

        return LineMarkerInfo(
            ident,
            ident.textRange,
            selectedIcon,
            { tooltip() },
            null,
            GutterIconRenderer.Alignment.LEFT,
            { "Security Critical" }
        )
    }


    private fun buildSignature(method: UMethod): String? {
        val classFqcn = method.getContainingUClass()?.qualifiedName ?: return null
        val methodName = method.name
        val paramTypes = method.uastParameters.joinToString(",") { p ->
// Try to resolve to fully-qualified canonical type name
            val t = p.type
            val fq = t.canonicalText
            fq
        }
        return "$classFqcn#$methodName($paramTypes)"
    }
}