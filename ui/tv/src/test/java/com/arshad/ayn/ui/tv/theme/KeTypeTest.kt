package com.arshad.ayn.ui.tv.theme

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.ResourceFont
import org.junit.Assert.assertEquals
import org.junit.Test

class KeTypeTest {

    @Test
    fun `a role at another weight sets the variable font's wght axis, not only the weight`() {
        val bold = KeTypography.bodyMedium.atWeight(600)

        assertEquals(FontWeight(600), bold.fontWeight)
        assertAxes(bold.fontFamily, weight = 600)
    }

    @Test
    fun `a weight span carries the family at its weight`() {
        val span = weightSpan(450)

        assertEquals(FontWeight(450), span.fontWeight)
        assertAxes(span.fontFamily, weight = 450)
    }

    private fun assertAxes(family: FontFamily?, weight: Int) {
        val font = (family as FontListFontFamily).fonts.single() as ResourceFont
        assertEquals(FontWeight(weight), font.weight)
        assertEquals(FontVariation.Settings(FontVariation.weight(weight), FontVariation.width(100f)), font.variationSettings)
    }
}
