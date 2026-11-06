package com.arshad.ayn.ui.tv.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

// tv-material components read these roles; Cinema code reads KeColors directly.
private val KeColorScheme = darkColorScheme(
    primary = KeColors.Ink,
    onPrimary = KeColors.OnInk,
    background = KeColors.Ground,
    onBackground = KeColors.Ink,
    surface = KeColors.Surface1,
    onSurface = KeColors.Ink,
    surfaceVariant = KeColors.Surface2,
    onSurfaceVariant = KeColors.Ink2,
    error = KeColors.Danger,
    onError = KeColors.OnInk,
    border = KeColors.Line,
    borderVariant = KeColors.Ink3,
)

/** Wraps the whole TV subtree (TVF-4); ink is the default content colour, never tv's Black. */
@Composable
internal fun KeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = KeColorScheme, typography = KeTypography) {
        CompositionLocalProvider(LocalContentColor provides KeColors.Ink, content = content)
    }
}
