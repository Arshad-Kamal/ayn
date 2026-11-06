package com.arshad.ayn.ui.mobile.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalAccessibilityManager

/**
 * [ms], or longer when the accessibility settings ask for more time to read or act on what shows
 * (`calculateRecommendedTimeoutMillis` with what the content [containsIcons], [containsText] and
 * [containsControls]).
 */
@Composable
internal fun rememberTimeout(ms: Long, containsIcons: Boolean = false, containsText: Boolean = false, containsControls: Boolean = false): Long {
    val accessibility = LocalAccessibilityManager.current
    return remember(accessibility, ms, containsIcons, containsText, containsControls) {
        accessibility?.calculateRecommendedTimeoutMillis(ms, containsIcons, containsText, containsControls) ?: ms
    }
}
