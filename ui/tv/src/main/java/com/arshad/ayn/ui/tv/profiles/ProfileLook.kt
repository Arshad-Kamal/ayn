package com.arshad.ayn.ui.tv.profiles

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.arshad.ayn.core.store.ProfileTint
import com.arshad.ayn.ui.tv.R
import com.arshad.ayn.ui.tv.common.rememberRise
import com.arshad.ayn.ui.tv.common.rising
import com.arshad.ayn.ui.tv.focus.TintedIcon
import com.arshad.ayn.ui.tv.focus.drawInflated
import com.arshad.ayn.ui.tv.theme.KeColors
import com.arshad.ayn.ui.tv.theme.KeLayout
import com.arshad.ayn.ui.tv.theme.KeMonogramStyle

/** The Cinema profile tints (`--p-*` in the master mockup). */
internal val ProfileTint.color: Color
    get() = when (this) {
        ProfileTint.Amber -> Color(red = 224, green = 168, blue = 90)
        ProfileTint.Rose -> Color(red = 219, green = 127, blue = 134)
        ProfileTint.Teal -> Color(red = 95, green = 191, blue = 176)
        ProfileTint.Blue -> Color(red = 110, green = 159, blue = 221)
        ProfileTint.Violet -> Color(red = 166, green = 138, blue = 216)
    }

/** The tint's name as the name screen shows it. */
internal val ProfileTint.label: String get() = name

/** A profile's monogram plate (`.pav`) in one size: [size] square, [corner] radius. */
internal class PlateSize(val size: Dp, val corner: Dp, val letter: TextUnit, val letterDrop: Dp)

internal val TilePlate = PlateSize(130.dp, 26.dp, 78.sp, 2.dp)
internal val MediumPlate = PlateSize(66.dp, 15.dp, 40.sp, 2.dp)
internal val MiniPlate = PlateSize(21.dp, 6.dp, 12.5.sp, 0.dp)

private val MonogramInk = KeColors.OnInk.copy(alpha = 0.84f)

/** The plate's face: the tint falling off to 78% (165°), a lit top edge and a shaded foot. */
private fun Modifier.plateFace(tint: Color): Modifier = drawWithCache {
    val face = Brush.linearGradient(
        0f to tint,
        1f to tint.copy(alpha = 0.78f),
        start = Offset(size.width * 0.37f, 0f),
        end = Offset(size.width * 0.63f, size.height),
    )
    val foot = Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.16f))
    onDrawBehind {
        drawRect(KeColors.Ground)
        drawRect(face)
        drawRect(foot)
        drawRect(Color.White.copy(alpha = 0.28f), size = size.copy(height = 1.dp.toPx()))
    }
}

/** The plate's content, filling a box the caller sizes and shapes: the tint and the monogram. */
@Composable
internal fun PlateFace(name: String, tint: ProfileTint, plate: PlateSize, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().plateFace(tint.color), contentAlignment = Alignment.Center) {
        val letter = monogram(name)
        if (letter.isNotEmpty()) {
            Text(
                text = letter,
                style = KeMonogramStyle.copy(fontSize = plate.letter),
                color = MonogramInk,
                modifier = Modifier.graphicsLayer { translationY = plate.letterDrop.toPx() },
            )
        }
    }
}

/** A plate that isn't focusable (unlock, name screen, the rail slot). */
@Composable
internal fun ProfilePlate(name: String, tint: ProfileTint, plate: PlateSize, modifier: Modifier = Modifier) {
    PlateFace(name, tint, plate, modifier.size(plate.size).clip(RoundedCornerShape(plate.corner)))
}

/** The first letter (code point) of the trimmed name, upper-cased. */
internal fun monogram(name: String): String {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return ""
    return trimmed.substring(0, Character.charCount(trimmed.codePointAt(0))).uppercase()
}

/** A requester the screen focuses once it has laid out (TVF-7). */
@Composable
internal fun rememberInitialFocus(): FocusRequester {
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        requester.requestFocus()
    }
    return requester
}

private val KMarkPlate = 18.dp
private val KMarkGlyphWidth = 9.dp
private val KMarkGlyphHeight = 13.dp
private val KMarkShape = RoundedCornerShape(4.dp)
private val InkPlate = ColorProducer { KeColors.Ink }
private val GroundGlyph = ColorProducer { KeColors.Ground }

/** The small Ayn mark beside a kicker (`.kmark`): the ع on an ink plate ([plate], [glyph] swap on
 *  a focused row; read when drawn). */
@Composable
internal fun KMark(modifier: Modifier = Modifier, plate: ColorProducer = InkPlate, glyph: ColorProducer = GroundGlyph) {
    Box(
        modifier.size(KMarkPlate).drawBehind { drawInflated(KMarkShape, 0.dp, plate(), 1f) },
        contentAlignment = Alignment.Center,
    ) {
        TintedIcon(
            painterResource(R.drawable.ic_ayn_mark),
            contentDescription = null,
            tint = glyph,
            modifier = Modifier.size(width = KMarkGlyphWidth, height = KMarkGlyphHeight),
        )
    }
}

/** A guided surface's small line: what this is about (`.guided .what`), after an icon or mark. */
@Composable
internal fun KickerRow(text: String, modifier: Modifier = Modifier, lead: @Composable () -> Unit) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        lead()
        Text(text, style = KickerStyle, color = KeColors.Ink2)
    }
}

@Composable
internal fun KickerIcon(@DrawableRes icon: Int) {
    Icon(painterResource(icon), contentDescription = null, tint = KeColors.Ink2, modifier = Modifier.size(15.dp))
}

private val KickerStyle: TextStyle
    @Composable get() = MaterialTheme.typography.labelMedium.copy(fontSize = 13.5.sp)

// `.guided` (px ÷ 2): copy from the content start, the actions in a column at the right.
private val ActionsGap = 60.dp
internal val GuidedActionsWidth = 260.dp
private const val ActionsDelayMs = 40

/**
 * The guided layout the profile surfaces share (`b3-profiles` welcome, name, restore, unlock):
 * [copy] at the left from [start] (the content start; a terminal over a picture starts at the
 * safe line), [actions] in an [actionsWidth] column at the right, both vertically centred a
 * little above the middle and rising in (D8).
 */
@Composable
internal fun ProfileGuided(
    modifier: Modifier = Modifier,
    start: Dp = KeLayout.ContentStart,
    actionsWidth: Dp = GuidedActionsWidth,
    actionsAlignment: Alignment.Horizontal = Alignment.Start,
    actionsSpacing: Dp = 8.dp,
    copy: @Composable ColumnScope.() -> Unit,
    actions: @Composable ColumnScope.() -> Unit,
) {
    val rise = rememberRise()
    val riseActions = rememberRise(ActionsDelayMs)
    Row(
        modifier = modifier
            .fillMaxSize()
            .padding(start = start, end = KeLayout.SafeHorizontal, bottom = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).rising(rise), content = copy)
        Spacer(Modifier.width(ActionsGap))
        Column(
            modifier = Modifier.width(actionsWidth).rising(riseActions),
            horizontalAlignment = actionsAlignment,
            verticalArrangement = Arrangement.spacedBy(actionsSpacing),
            content = actions,
        )
    }
}

/** A guided headline (`.guided .gt h1`, 84 px), its lines balanced as the mockups' `text-wrap: balance`. */
@Composable
internal fun GuidedTitle(text: String, modifier: Modifier = Modifier, maxWidth: Dp = 480.dp) {
    Text(text = text, style = MaterialTheme.typography.displaySmall.copy(lineBreak = LineBreak.Heading), modifier = modifier.widthIn(max = maxWidth))
}

/** A guided paragraph (`.guided .gt p`, 30 px). */
@Composable
internal fun GuidedBody(text: String, modifier: Modifier = Modifier, color: Color = KeColors.Ink2) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 1.4.em),
        color = color,
        modifier = modifier.padding(top = 13.dp).widthIn(max = 430.dp),
    )
}
