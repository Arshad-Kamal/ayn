package com.arshad.ayn.ui.tv.common

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.arshad.ayn.core.access.AccessFailure
import com.arshad.ayn.ui.tv.R
import com.arshad.ayn.ui.tv.focus.ControlStyle
import com.arshad.ayn.ui.tv.focus.ControlSurface
import com.arshad.ayn.ui.tv.focus.TintedIcon
import com.arshad.ayn.ui.tv.focus.TintedText
import com.arshad.ayn.ui.tv.focus.focusMemory
import com.arshad.ayn.ui.tv.theme.KeColors

// Master `.statement` geometry (px ÷ 2): full where the hero copy sits, compact in a short hero.
private val BodyWidth = 450.dp
private val CompactBodyWidth = 600.dp
private val TitleWidth = 530.dp
private val StampTop = 60.dp

/**
 * A screen-level statement (errors, blank slates): what happened in a small line ([kicker], if
 * any), a headline, one paragraph, the actions, and [stamp] (the last attempt). It rises in when
 * it appears (D8).
 * [compact] is the short form that keeps rows in view below it (`b3-home` state 9); [smallTitle]
 * keeps the full form under a smaller headline (master `h1.sm`, below a channel's header).
 */
@Composable
internal fun Statement(
    kicker: Kicker?,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    smallTitle: Boolean = false,
    stamp: String? = null,
    actions: @Composable RowScope.() -> Unit,
) {
    Column(modifier.rising(rememberRise())) {
        if (kicker != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Icon(painterResource(kicker.icon), contentDescription = null, tint = KeColors.Ink2, modifier = Modifier.size(16.dp))
                Text(
                    text = kicker.text,
                    style = MaterialTheme.typography.labelMedium.let { if (compact) it.copy(fontSize = 12.5.sp) else it },
                    color = KeColors.Ink2,
                )
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.displayMedium.let {
                when {
                    compact -> it.copy(fontSize = 32.sp)
                    smallTitle -> it.copy(fontSize = 36.sp)
                    else -> it
                }
            },
            modifier = Modifier
                .padding(top = if (compact) 9.dp else 12.dp)
                .then(if (compact) Modifier else Modifier.widthIn(max = TitleWidth)),
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge.let {
                if (compact) it else it.copy(fontSize = 15.sp, lineHeight = 1.4.em)
            },
            color = KeColors.Ink2,
            modifier = Modifier
                .padding(top = if (compact) 9.dp else 14.dp)
                .widthIn(max = if (compact) CompactBodyWidth else BodyWidth),
        )
        Row(
            modifier = Modifier.padding(top = if (compact) 16.dp else 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (compact) 14.dp else 9.dp),
        ) {
            actions()
            if (compact && stamp != null) StampText(stamp)
        }
        if (!compact && stamp != null) StampText(stamp, Modifier.padding(top = StampTop))
    }
}

/** A statement's small line: what happened, after its icon. */
internal data class Kicker(@param:DrawableRes val icon: Int, val text: String)

/** Offline says so; any other failure is YouTube's reply KE couldn't read (D8). */
internal fun failureKicker(failure: AccessFailure): Kicker =
    if (failure == AccessFailure.OFFLINE) {
        Kicker(R.drawable.ic_offline, "No connection to YouTube")
    } else {
        Kicker(R.drawable.ic_alert, "YouTube changed something")
    }

/** What an unreadable reply means for the user (D8), however the statement opens. */
internal const val UnreadableReply =
    "The Shield is online, but Ayn couldn’t read YouTube’s reply. This usually needs an Ayn update."

@Composable
private fun StampText(text: String, modifier: Modifier = Modifier) {
    Text(text = text, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = KeColors.Ink3, modifier = modifier)
}

/** A pill button (control focus family): an icon, if any, and a label (master `.btn`, 72 px tall). */
@Composable
internal fun PillButton(
    @DrawableRes icon: Int?,
    label: String,
    focusKey: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ControlStyle = ControlStyle.Primary,
    light: Color = KeColors.ControlLight,
) {
    ControlSurface(
        onClick = onClick,
        style = style,
        light = light,
        modifier = modifier.focusMemory(focusKey).height(PillButtonHeight),
    ) {
        PillButtonContent(icon, label)
    }
}

internal val PillButtonHeight = 36.dp

/** A round icon button beside pill buttons (master `.btn.round`, 72 px), named [label]. */
@Composable
internal fun RoundButton(
    @DrawableRes icon: Int,
    label: String,
    focusKey: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ControlSurface(
        onClick = onClick,
        modifier = modifier.focusMemory(focusKey).size(PillButtonHeight).semantics { contentDescription = label },
    ) {
        TintedIcon(painterResource(icon), contentDescription = null, modifier = Modifier.align(Alignment.Center).size(16.dp))
    }
}

/** A guided surface's action (master `.guided .ga .btn`, 80 px tall): it fills its column; a
 *  [trailing] icon sits faint at its end (`.ga .btn .lk`, what the action asks for). */
@Composable
internal fun GuidedButton(
    @DrawableRes icon: Int,
    label: String,
    style: ControlStyle,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes trailing: Int? = null,
) {
    ControlSurface(onClick = onClick, style = style, modifier = modifier.fillMaxWidth().height(GuidedButtonHeight)) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 17.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            TintedIcon(painterResource(icon), contentDescription = null, modifier = Modifier.size(16.dp))
            TintedText(label, style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.5.sp))
            trailing?.let {
                Spacer(Modifier.weight(1f))
                TintedIcon(painterResource(it), contentDescription = null, modifier = Modifier.size(14.dp).alpha(TrailingAlpha))
            }
        }
    }
}

private const val TrailingAlpha = 0.7f

private val GuidedButtonHeight = 40.dp

/** A pill button's icon, if any, and label (for a pill whose content changes, like Subscribe). */
@Composable
internal fun PillButtonContent(@DrawableRes icon: Int?, label: String) {
    Row(
        modifier = Modifier.fillMaxHeight().padding(start = if (icon != null) 14.dp else 17.dp, end = 17.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        icon?.let { TintedIcon(painterResource(it), contentDescription = null, modifier = Modifier.size(16.dp)) }
        TintedText(text = label, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * A small pill on a page (search suggestions, Edit search, channel tabs): control focus family,
 * a faint well at rest unless [style] says otherwise (master `.pill`, 56 px tall).
 */
@Composable
internal fun PagePill(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ControlStyle = ControlStyle.Pill,
    content: @Composable RowScope.() -> Unit,
) {
    ControlSurface(onClick = onClick, style = style, modifier = modifier.height(PagePillHeight)) {
        Row(
            modifier = Modifier.fillMaxHeight().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            content = content,
        )
    }
}

internal val PagePillHeight = 28.dp

/** The one sheen every skeleton block on a screen shares: 0..1 across, linear, 1.6 s. */
@Composable
internal fun rememberSheen(): State<Float> =
    rememberInfiniteTransition(label = "sheen").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(SheenMs, easing = LinearEasing), RepeatMode.Restart),
        label = "sheenAcross",
    )

private const val SheenMs = 1600
private val BlockShape = RoundedCornerShape(7.dp)

/** A skeleton block in the final layout's shape, the [sheen] passing over it (draw-time read;
 *  outline and gradient built once per size). */
internal fun Modifier.skeleton(sheen: State<Float>, shape: Shape = BlockShape): Modifier = drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    val clip = Path().apply { addOutline(outline) }
    val band = Brush.horizontalGradient(
        0f to Color.Transparent,
        0.5f to KeColors.Sheen,
        1f to Color.Transparent,
        startX = 0f,
        endX = size.width,
    )
    onDrawBehind {
        drawOutline(outline, KeColors.Skeleton)
        clipPath(clip) {
            translate(left = size.width * (2f * sheen.value - 1f)) { drawRect(band) }
        }
    }
}

/** A rounded bar of the skeleton's text lines. */
internal val LineShape: Shape = RoundedCornerShape(4.dp)

/** A text line as a skeleton: [fraction] of its column's width, [top] below the line above. */
@Composable
internal fun SkeletonLine(fraction: Float, top: Dp, sheen: State<Float>, height: Dp = 11.dp) {
    Spacer(Modifier.padding(top = top).fillMaxWidth(fraction).height(height).skeleton(sheen, LineShape))
}

