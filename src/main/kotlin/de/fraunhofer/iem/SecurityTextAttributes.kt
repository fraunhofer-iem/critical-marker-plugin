package de.fraunhofer.iem

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Font

object SecurityTextAttributes {
    private const val OPACITY = 40 // adjust 0–255 as needed
    private const val STYLE = Font.PLAIN

    private fun bg(r: Int, g: Int, b: Int, a: Int): TextAttributes =
        TextAttributes(
            null,
            JBColor(Color(r, g, b, a), Color(r, g, b, a)),
            null,
            null,
            STYLE
        )

    val Low     = bg(86, 204, 242, OPACITY)   //
    val Medium  = bg(242, 201, 76,  OPACITY)  // #F2C94C
    val High    = bg(242, 153, 74,  OPACITY)  // #F2994A
}
