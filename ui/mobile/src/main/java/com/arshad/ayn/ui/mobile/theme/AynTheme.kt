package com.arshad.ayn.ui.mobile.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.arshad.ayn.feature.R

/**
 * Cinema colour tokens for the phone (`m6-*` sandboxes' `style.css`, oklch converted to sRGB):
 * a warm near-black ground, every text tone the one ink at a lower alpha, ivory the only accent.
 */
internal object AynColors {
    val Ground = Color(0xFF0D0907)
    val Surface1 = Color(0xFF14110D)
    val Surface2 = Color(0xFF1D1915)
    val Surface3 = Color(0xFF28231F)
    val Ink = Color(0xFFF6F3EF)
    val Ink2 = Ink.copy(alpha = 0.74f)
    val Ink3 = Ink.copy(alpha = 0.54f)
    val Line = Ink.copy(alpha = 0.12f)
    val OnInk = Color(0xFF100C09)

    /** Tonal buttons and the OK key's rest fill (`oklch(96% 0.01 80 / .14)`). */
    val Tonal = Color(0xFFF5F1EA).copy(alpha = 0.14f)

    /** The navigation bar's indicator under the current tab, and the channel's own name in a comment. */
    val Indicator = Color(0xFFF5F1EA).copy(alpha = 0.16f)

    /** A list row lit while pressed. */
    val Pressed = Color(0xFFF5F1EA).copy(alpha = 0.06f)

    /** A list row marking what plays now (`.chr.on`). */
    val RowLit = Color(0xFFF5F1EA).copy(alpha = 0.09f)

    /** A wrong try's line and its rejected dots. */
    val ErrorInk = Color(0xFFFFB4AD)
    val Rejected = Color(0xFFF66D67)

    /** The sequence pad's well, lit a little above its middle. */
    val PadLight = Color(0xFF201C18)

    /** A profile monogram's ink on its tint. */
    val Monogram = Color(0xFF1F1307)

    val Scrim = Color(0xFF010100).copy(alpha = 0.5f)

    /** A live stream's badge (`.dur.live`, oklch(55% 0.2 25)). */
    val Live = Color(0xFFC72B37)

    /** The warm pool where a picture will land while it loads (`.pool`). */
    val Pool = Color(0xFF4A3A2B).copy(alpha = 0.55f)

    /** A part that didn't answer (oklch(80% 0.13 75)): the partial note and a failed channel's mark. */
    val Warn = Color(0xFFEEB154)

    /** A destructive confirm's fill and ink (the TV's Danger), and a destructive row's ink (oklch(74% 0.14 28)). */
    val Danger = Color(0xFFF45249)
    val OnDanger = Color(0xFFFCFCFC)
    val DangerInk = Color(0xFFF68678)
}

/**
 * Motion tokens (compose-tv D8 on the phone: every state change animates, transform and alpha
 * only). Press = touch feedback (120 ms, the mockups' notes), Base = state and colour, Move =
 * entrances; [Ease] for state changes, [EaseOut] for what rises in.
 */
internal object AynMotion {
    const val PRESS_MS: Int = 120
    const val BASE_MS: Int = 190
    const val MOVE_MS: Int = 220

    val Ease: Easing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
    val EaseOut: Easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

    fun <T> press(): TweenSpec<T> = tween(PRESS_MS, easing = Ease)
    fun <T> base(): TweenSpec<T> = tween(BASE_MS, easing = Ease)
}

/** Archivo (variable: wght 100–900, wdth 62–125) at one weight and width; display roles condense. */
private fun archivo(weight: Int, width: Float = 100f): FontFamily = FontFamily(
    Font(
        resId = R.font.archivo,
        weight = FontWeight(weight),
        variationSettings = FontVariation.Settings(FontVariation.weight(weight), FontVariation.width(width)),
    ),
)

private fun style(sizeSp: Float, weight: Int, width: Float = 100f, lineHeightEm: Float? = null, trackingEm: Float = 0f): TextStyle =
    TextStyle(
        fontFamily = archivo(weight, width),
        fontWeight = FontWeight(weight),
        fontSize = sizeSp.sp,
        lineHeight = lineHeightEm?.em ?: TextStyle.Default.lineHeight,
        letterSpacing = trackingEm.em,
    )

/**
 * The phone's type scale (sandbox px = sp): display = guided and picker headlines, headline =
 * statements, title = names and rows, body = copy, hints and messages, label = buttons, kickers
 * and the bar's labels.
 */
internal val AynTypography: Typography = Typography(
    displayLarge = style(46f, 730, width = 64f, lineHeightEm = 0.96f, trackingEm = -0.01f),
    displayMedium = style(44f, 730, width = 64f, lineHeightEm = 0.96f, trackingEm = -0.01f),
    displaySmall = style(42f, 730, width = 64f, lineHeightEm = 0.95f),
    headlineLarge = style(40f, 730, width = 64f, lineHeightEm = 0.96f),
    headlineMedium = style(40f, 720, width = 70f, lineHeightEm = 1f),
    titleLarge = style(17f, 660),
    titleMedium = style(16f, 620),
    titleSmall = style(14f, 650),
    bodyLarge = style(16f, 400, lineHeightEm = 1.45f),
    bodyMedium = style(14f, 400, lineHeightEm = 1.45f),
    bodySmall = style(13f, 400, lineHeightEm = 1.4f),
    labelLarge = style(16f, 640),
    labelMedium = style(14f, 600),
    labelSmall = style(12f, 600),
)

/** A text role (width 100) at [weight]: each family holds one `wght` instance, so it changes with it. */
internal fun TextStyle.atWeight(weight: Int): TextStyle = copy(fontFamily = archivo(weight), fontWeight = FontWeight(weight))

/** A run of text at [weight] inside another style ("Because you watched **…**"). */
internal fun weightSpan(weight: Int, color: Color = Color.Unspecified): SpanStyle =
    SpanStyle(color = color, fontFamily = archivo(weight), fontWeight = FontWeight(weight))

/** A profile's monogram: condensed and heavy; each size sets its own font size. */
internal val MonogramStyle: TextStyle = style(32f, 760, width = 62f, lineHeightEm = 1f)

private val AynColorScheme = darkColorScheme(
    primary = AynColors.Ink,
    onPrimary = AynColors.OnInk,
    secondaryContainer = AynColors.Indicator,
    onSecondaryContainer = AynColors.Ink,
    background = AynColors.Ground,
    onBackground = AynColors.Ink,
    surface = AynColors.Ground,
    onSurface = AynColors.Ink,
    onSurfaceVariant = AynColors.Ink2,
    surfaceContainerLowest = AynColors.Ground,
    surfaceContainerLow = AynColors.Surface1,
    surfaceContainer = AynColors.Surface1,
    surfaceContainerHigh = AynColors.Surface2,
    surfaceContainerHighest = AynColors.Surface3,
    outline = AynColors.Line,
    outlineVariant = AynColors.Line,
    error = AynColors.ErrorInk,
    scrim = AynColors.Scrim,
)

/** Cinema on Material 3 (M6-D3): wraps the whole phone UI; ink is the default content colour. */
@Composable
internal fun AynTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AynColorScheme, typography = AynTypography) {
        CompositionLocalProvider(LocalContentColor provides AynColors.Ink, content = content)
    }
}
