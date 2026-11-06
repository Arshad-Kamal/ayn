package com.arshad.ayn.ui.tv.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.TextUnit
import androidx.tv.material3.MaterialTheme
import com.arshad.ayn.ui.tv.focus.TintedText
import com.arshad.ayn.ui.tv.theme.KeColors

// The monogram's plate (b3-channel: oklch 42% 0.09 40 → 30% 0.06 20).
private val MonogramFrom = Color(0xFF753A24)
private val MonogramTo = Color(0xFF472021)

/** A channel's avatar clipped round, filling its box; its initials on a warm plate when it has none,
 *  in the content ink (a control's, as it fades). */
@Composable
internal fun AvatarPicture(name: String, url: String?, monogramSize: TextUnit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxSize()
            .clip(CircleShape)
            .background(if (url == null) Brush.linearGradient(listOf(MonogramFrom, MonogramTo)) else SolidColor(KeColors.Surface2)),
        contentAlignment = Alignment.Center,
    ) {
        if (url == null) {
            TintedText(initials(name), style = MaterialTheme.typography.headlineSmall.copy(fontSize = monogramSize))
        } else {
            Picture(url)
        }
    }
}

/** The first letter (code point) of the name's first two words, skipping words that don't start
 *  with a letter or digit ("Omar & Hana": OH) unless no word does. */
internal fun initials(name: String): String {
    val words = name.split(' ').filter { it.isNotEmpty() }
    return words.filter { Character.isLetterOrDigit(it.codePointAt(0)) }.ifEmpty { words }.take(2)
        .joinToString("") { it.substring(0, Character.charCount(it.codePointAt(0))).uppercase() }
}
