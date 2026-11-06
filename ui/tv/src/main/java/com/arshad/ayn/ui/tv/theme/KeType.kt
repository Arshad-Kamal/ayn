package com.arshad.ayn.ui.tv.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Typography
import com.arshad.ayn.feature.R

/**
 * Archivo (bundled variable font, axes wght 100–900 and wdth 62–125) at one weight and width.
 * Display roles use the condensed widths the mockups set with `font-stretch`.
 */
private fun archivo(weight: Int, width: Float = 100f): FontFamily = FontFamily(
    Font(
        resId = R.font.archivo,
        weight = FontWeight(weight),
        variationSettings = FontVariation.Settings(
            FontVariation.weight(weight),
            FontVariation.width(width),
        ),
    ),
)

private fun style(
    sizeSp: Float,
    weight: Int,
    width: Float = 100f,
    lineHeightEm: Float? = null,
    trackingEm: Float = 0f,
): TextStyle = TextStyle(
    fontFamily = archivo(weight, width),
    fontWeight = FontWeight(weight),
    fontSize = sizeSp.sp,
    lineHeight = lineHeightEm?.em ?: TextStyle.Default.lineHeight,
    letterSpacing = trackingEm.em,
)

/**
 * The master mockup's type scale (`.t-*`, px ÷ 2 = sp) on tv-material roles (TVU-2):
 * display = channel / hero / guided headlines, headline = page / player / section titles,
 * title = row heading / list item / card, body = blurb / meta / small, label = button /
 * control label / badge.
 */
internal val KeTypography: Typography = Typography(
    displayLarge = style(52f, 740, width = 66f, lineHeightEm = 0.92f, trackingEm = -0.014f),
    displayMedium = style(46f, 720, width = 68f, lineHeightEm = 0.94f, trackingEm = -0.012f),
    displaySmall = style(42f, 720, width = 68f, lineHeightEm = 0.94f, trackingEm = -0.012f),
    headlineLarge = style(36f, 720, width = 68f, lineHeightEm = 0.9f),
    headlineMedium = style(30f, 720, width = 70f, lineHeightEm = 1f, trackingEm = -0.008f),
    headlineSmall = style(24f, 720, width = 72f, lineHeightEm = 1f, trackingEm = -0.006f),
    titleLarge = style(16f, 640, trackingEm = -0.005f),
    titleMedium = style(13.5f, 600, lineHeightEm = 1.22f),
    titleSmall = style(13f, 600, lineHeightEm = 1.22f),
    bodyLarge = style(14f, 400, lineHeightEm = 1.38f),
    bodyMedium = style(13f, 400),
    bodySmall = style(11.5f, 450, lineHeightEm = 1.3f),
    labelLarge = style(14f, 620),
    labelMedium = style(14f, 560),
    labelSmall = style(11f, 640),
)

/**
 * A text role (title, body, label: width 100) at [weight]. Each role's family holds one `wght`
 * instance, so a `fontWeight` alone can't move it (it at most fakes bold): the family changes
 * with it. Display and headline roles are condensed; they take no other weight today.
 */
internal fun TextStyle.atWeight(weight: Int): TextStyle =
    copy(fontFamily = archivo(weight), fontWeight = FontWeight(weight))

/** A run of text at [weight] inside a text role (see [atWeight]), in [color]. */
internal fun weightSpan(weight: Int, color: Color = Color.Unspecified): SpanStyle =
    SpanStyle(color = color, fontFamily = archivo(weight), fontWeight = FontWeight(weight))

/** A sign-in code to type on another device (`m8-sync-tv` `.ucode`): large, condensed, spaced out. */
internal val KeCodeStyle: TextStyle = style(26f, 720, width = 80f, lineHeightEm = 1.1f, trackingEm = 0.06f)

/** A profile's monogram (`b3-profiles` `.pav b`): condensed and heavy; each size sets its own font size. */
internal val KeMonogramStyle: TextStyle = style(78f, 780, width = 62f, lineHeightEm = 1f)
