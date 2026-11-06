package com.arshad.ayn.ui.mobile.shell

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arshad.ayn.ui.mobile.R
import com.arshad.ayn.ui.mobile.common.pressScale
import com.arshad.ayn.ui.mobile.kids.TimeLeftChip
import com.arshad.ayn.ui.mobile.kids.TimeLeftChipHeight
import com.arshad.ayn.ui.mobile.kids.TimeLeftLineHeight
import com.arshad.ayn.ui.mobile.kids.rememberTimeLeft
import com.arshad.ayn.ui.mobile.profiles.color
import com.arshad.ayn.ui.mobile.profiles.ProfileAvatar
import com.arshad.ayn.ui.mobile.theme.AynColors
import com.arshad.ayn.ui.mobile.theme.atWeight

internal val PillHeight = 56.dp

/** The head the pill sits in (`.pillhead`): 10 dp above and below, so the bar is 76 dp tall. */
internal val PillHeadPadding = PaddingValues(start = 12.dp, end = 14.dp, top = 10.dp, bottom = 10.dp)
internal val PillHeadHeight = PillHeight + 20.dp

// `.pillhead.over .spill`: a dark see-through fill and a hairline over the hero (no blur on minSdk 30).
private val OverFill = Color(0xFF0E0B08).copy(alpha = 0.58f)
private val OverLine = Color.White.copy(alpha = 0.14f)
private val ActiveRing = 2.dp

/** The query's words in the pill (18 sp); the placeholder is lighter and dimmer. */
internal val PillTextStyle: TextStyle @Composable get() = MaterialTheme.typography.bodyLarge.atWeight(600).copy(fontSize = 18.sp, lineHeight = 22.sp)

/** What the pill says before anything is typed (M6-D13). */
internal fun searchPlaceholder(kids: Boolean): String = if (kids) "Search what’s approved" else "Search YouTube"

/** A kids profile's pill leads with the shield: its Search looks only through what is approved. */
@DrawableRes
internal fun searchIcon(kids: Boolean): Int = if (kids) R.drawable.ic_shield else R.drawable.ic_nav_search

/**
 * The pill's ground (`.spill`), drawn so changes don't recompose: Surface 2 at rest, [over] (0..1)
 * towards the see-through fill over the hero, [active] (0..1) towards the field (Surface 3 in an
 * ink ring, 150 ms).
 */
internal fun Modifier.pillGround(over: () -> Float = { 0f }, active: () -> Float = { 0f }): Modifier = drawBehind {
    val radius = CornerRadius(size.height / 2)
    val overAmount = over()
    val activeAmount = active()
    drawRoundRect(lerp(lerp(AynColors.Surface2, OverFill, overAmount), AynColors.Surface3, activeAmount), cornerRadius = radius)
    if (overAmount > 0f) insetRing(OverLine.copy(alpha = OverLine.alpha * overAmount), 1.dp.toPx(), radius.x)
    if (activeAmount > 0f) insetRing(AynColors.Ink.copy(alpha = activeAmount), ActiveRing.toPx(), radius.x)
}

/** A [width] stroke just inside the pill's edge. */
private fun DrawScope.insetRing(color: Color, width: Float, radius: Float) {
    drawRoundRect(
        color,
        topLeft = Offset(width / 2, width / 2),
        size = Size(size.width - width, size.height - width),
        cornerRadius = CornerRadius(radius - width / 2),
        style = Stroke(width),
    )
}

/**
 * The top bar on every tab (`m6-search` 1–2, M6-D13): the search pill, which opens Search over the
 * shown tab, and the profile's avatar, which opens its menu. Over Home's hero the bar and pill are
 * see-through until the page scrolls past it ([ground]: 0..1). Over a scrolled list it lifts
 * ([lift]: 0..1, a hairline at its foot).
 */
@Composable
internal fun ShellTopBar(chrome: ShellChrome, modifier: Modifier = Modifier, ground: () -> Float = { 1f }, lift: () -> Float = { 0f }) {
    val lined = modifier.drawBehind {
        drawRect(AynColors.Ground.copy(alpha = ground()))
        val line = 1.dp.toPx()
        drawRect(AynColors.Line.copy(alpha = AynColors.Line.alpha * lift()), topLeft = Offset(0f, size.height - line), size = Size(size.width, line))
    }
    PillHead(chrome, lined) {
        val kids = chrome.profile.isKids
        val interaction = remember { MutableInteractionSource() }
        Row(
            modifier = Modifier
                .weight(1f)
                .height(PillHeight)
                .pressScale(interaction)
                .pillGround(over = { 1f - ground() })
                .clickable(interactionSource = interaction, indication = null, role = Role.Button) { chrome.nav.open(SearchKey) }
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(painterResource(searchIcon(kids)), contentDescription = null, tint = AynColors.Ink2, modifier = Modifier.size(22.dp))
            Text(
                searchPlaceholder(kids),
                style = PillTextStyle.atWeight(500),
                color = AynColors.Ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The pill head's height under the status bar: the pill's row, and a kids profile's time-left
 *  line while its time is limited. */
@Composable
internal fun pillHeadHeight(chrome: ShellChrome): Dp =
    PillHeadHeight + if (rememberTimeLeft(chrome.kidsClock) != null) TimeLeftLineHeight else 0.dp

/** A row under the status bar holding [pill] and, after it, the profile's avatar; a kids
 *  profile's time left today under it ([TimeLeftLineHeight], Q55). */
@Composable
internal fun PillHead(chrome: ShellChrome, modifier: Modifier = Modifier, pill: @Composable RowScope.() -> Unit) {
    val left = rememberTimeLeft(chrome.kidsClock)
    Column(modifier.fillMaxWidth().statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(PillHeadPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            pill()
            IconButton(
                onClick = chrome.openProfileMenu,
                modifier = Modifier.semantics { contentDescription = "${chrome.profile.name}’s profile menu" },
            ) {
                ProfileAvatar(chrome.profile.name, chrome.profile.tint)
            }
        }
        if (left != null) TimeLeftChip(left, chrome.profile.tint.color, Modifier.padding(start = 20.dp, bottom = TimeLeftLineHeight - TimeLeftChipHeight))
    }
}
