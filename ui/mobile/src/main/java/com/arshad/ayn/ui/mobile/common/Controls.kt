package com.arshad.ayn.ui.mobile.common

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import com.arshad.ayn.ui.mobile.theme.atWeight
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arshad.ayn.ui.mobile.R
import com.arshad.ayn.ui.mobile.theme.AynColors
import com.arshad.ayn.ui.mobile.theme.AynMotion

/** A button's weight: the one ivory call to action, a quieter tonal one, a destructive confirm's,
 *  or [Quiet], unfilled under the others (`.btn.dimb`: Delete this backup). */
internal enum class ButtonKind { Primary, Tonal, Danger, DangerTonal, Quiet }

/** The kind's fill and ink. */
private fun ButtonKind.colors(): Pair<Color, Color> = when (this) {
    ButtonKind.Primary -> AynColors.Ink to AynColors.OnInk
    ButtonKind.Tonal -> AynColors.Tonal to AynColors.Ink
    ButtonKind.Danger -> AynColors.Danger to AynColors.OnDanger
    ButtonKind.DangerTonal -> AynColors.Danger.copy(alpha = 0.22f) to AynColors.DangerInk
    ButtonKind.Quiet -> Color.Transparent to AynColors.Ink3
}

/** How much of a disabled button shows (`.btn.off`). */
private const val DisabledAlpha = 0.4f

private val WideHeight = 52.dp
/** A pressed control or card sinks to this scale (M6-D10). */
internal const val PressedScale = 0.97f

/** Sinks to [PressedScale] while [interaction] is pressed (M6-D10). */
@Composable
internal fun Modifier.pressScale(interaction: InteractionSource): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) PressedScale else 1f, AynMotion.press(), label = "press")
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * A full-width button (`.btn.wide`): [kind] picks the fill; [busy] shows progress in place of the
 * label and takes no taps (a restore running); [locked] ends it with a small lock (it asks for a
 * lock first). It sinks a little while pressed.
 */
@Composable
internal fun WideButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Primary,
    @DrawableRes icon: Int? = null,
    enabled: Boolean = true,
    busy: Boolean = false,
    locked: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val (container, content) = kind.colors()
    Button(
        onClick = { if (!busy) onClick() },
        enabled = enabled,
        interactionSource = interaction,
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
            disabledContainerColor = container.copy(alpha = container.alpha * 0.5f),
            disabledContentColor = content.copy(alpha = 0.5f),
        ),
        contentPadding = PaddingValues(horizontal = 18.dp),
        modifier = modifier
            .fillMaxWidth()
            .height(WideHeight)
            .pressScale(interaction),
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(22.dp), color = content, strokeWidth = 2.5.dp)
        } else {
            if (icon != null) {
                Icon(painterResource(icon), contentDescription = null, modifier = Modifier.padding(end = 8.dp).size(20.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge)
            if (locked) {
                Icon(painterResource(R.drawable.ic_lock), contentDescription = null, modifier = Modifier.padding(start = 6.dp).size(14.dp).alpha(0.7f))
            }
        }
    }
}

/**
 * A pill button (`.btn`, 44 dp; [small]: `.btn.sm` in a row's actions, 32 dp): [kind] picks the
 * fill, [glow] tints the primary's shadow (the hero's Play takes its picture's colour); not
 * [enabled], it shows dimmed and takes no taps. It sinks a little while pressed.
 */
@Composable
internal fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Primary,
    @DrawableRes icon: Int? = null,
    glow: Color? = null,
    enabled: Boolean = true,
    small: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val (container, content) = kind.colors()
    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
            disabledContainerColor = container.copy(alpha = container.alpha * DisabledAlpha),
            disabledContentColor = content.copy(alpha = DisabledAlpha),
        ),
        contentPadding = when {
            small -> PaddingValues(start = if (icon != null) 9.dp else 12.dp, end = 12.dp)
            else -> PaddingValues(start = if (icon != null) 14.dp else 18.dp, end = 18.dp)
        },
        modifier = modifier
            .height(if (small) SmallPillHeight else PillHeight)
            .pressScale(interaction)
            .graphicsLayer {
                if (glow != null) {
                    shadowElevation = 14.dp.toPx()
                    shape = RoundedCornerShape(50)
                    spotShadowColor = glow
                    ambientShadowColor = glow
                }
            },
    ) {
        if (icon != null) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.padding(end = if (small) 6.dp else 8.dp).size(if (small) 18.dp else 20.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge.copy(fontSize = if (small) 13.sp else 15.sp))
    }
}

private val PillHeight = 44.dp
private val SmallPillHeight = 32.dp

/** A round tonal icon button ([size] across, `.ib.tonal`), read out as [label]; it sinks while pressed. */
@Composable
internal fun RoundButton(@DrawableRes icon: Int, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 52.dp) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(size)
            .pressScale(interaction)
            .clip(CircleShape)
            .background(AynColors.Tonal)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = AynColors.Ink, modifier = Modifier.size(22.dp))
    }
}

/**
 * The bar over a guided page (`.tbar.plain`): the back arrow, and [label] beside it when the page
 * says what it is for ([labelIcon] before it, `.tlabel`); [end] at the far side (a step count).
 */
@Composable
internal fun BackBar(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    @DrawableRes labelIcon: Int? = null,
    end: (@Composable () -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth().height(56.dp).padding(start = 4.dp, end = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(painterResource(R.drawable.ic_back), contentDescription = "Back", tint = AynColors.Ink)
        }
        if (labelIcon != null) {
            Icon(painterResource(labelIcon), contentDescription = null, tint = AynColors.Ink2, modifier = Modifier.padding(end = 6.dp).size(18.dp))
        }
        if (label != null) Text(label, style = MaterialTheme.typography.titleSmall, color = AynColors.Ink2)
        if (end != null) {
            Spacer(Modifier.weight(1f))
            end()
        }
    }
}

/** A text field's colours on Cinema (`.field`): a raised fill with an ink underline and caret. */
@Composable
internal fun aynFieldColors(): TextFieldColors = TextFieldDefaults.colors(
    focusedContainerColor = AynColors.Surface2,
    unfocusedContainerColor = AynColors.Surface2,
    errorContainerColor = AynColors.Surface2,
    focusedIndicatorColor = AynColors.Ink,
    focusedLabelColor = AynColors.Ink2,
    unfocusedLabelColor = AynColors.Ink2,
    cursorColor = AynColors.Ink,
    focusedSupportingTextColor = AynColors.Ink3,
    unfocusedSupportingTextColor = AynColors.Ink3,
)

/** A guided page's small line over its headline (`.kick`): an icon or the Ayn mark, then the text. */
@Composable
internal fun Kicker(text: String, modifier: Modifier = Modifier, lead: @Composable () -> Unit) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        lead()
        Text(text, style = MaterialTheme.typography.labelMedium, color = AynColors.Ink2)
    }
}

@Composable
internal fun KickerIcon(@DrawableRes icon: Int) {
    Icon(painterResource(icon), contentDescription = null, tint = AynColors.Ink2, modifier = Modifier.size(18.dp))
}

/** The Ayn mark (`.mark`): the ع on an ivory plate, [size] square. */
@Composable
internal fun AynMark(modifier: Modifier = Modifier, size: Dp = 34.dp) {
    val corner = size * 0.27f
    Box(modifier.size(size).background(AynColors.Ink, RoundedCornerShape(corner)), contentAlignment = Alignment.Center) {
        Icon(
            painterResource(R.drawable.ic_ayn_mark),
            contentDescription = null,
            tint = AynColors.Ground,
            modifier = Modifier.width(size * 0.44f).height(size * 0.65f),
        )
    }
}

/** A tinted light rising from below the page (`.glow`), behind its content. */
internal fun Modifier.floorGlow(tint: () -> Color): Modifier = drawBehind {
    val center = Offset(size.width / 2, size.height + 20.dp.toPx())
    val radius = size.width * 0.84f
    drawCircle(Brush.radialGradient(0f to tint().copy(alpha = 0.22f), 0.7f to Color.Transparent, center = center, radius = radius), radius, center)
}

private val RiseBy = 16.dp

/** The page rises in as it first shows (alpha and transform only, D8). */
@Composable
internal fun Modifier.risingIn(): Modifier {
    val rise = remember { Animatable(0f) }
    LaunchedEffect(Unit) { rise.animateTo(1f, tween(AynMotion.MOVE_MS, easing = AynMotion.EaseOut)) }
    return graphicsLayer {
        alpha = rise.value
        translationY = (1f - rise.value) * RiseBy.toPx()
    }
}

/**
 * A chip picking one of a set (`.chall`, `.ctab`): ink while [selected], the raised well otherwise
 * (colours animate); sinks while pressed. Read out as a selected tab.
 */
@Composable
internal fun SelectChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, weight: Int = 640) {
    val fill by animateColorAsState(if (selected) AynColors.Ink else AynColors.Surface2, AynMotion.base(), label = "chipFill")
    val ink by animateColorAsState(if (selected) AynColors.OnInk else AynColors.Ink2, AynMotion.base(), label = "chipInk")
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .pressScale(interaction)
            .clip(CircleShape)
            .drawBehind { drawRect(fill) }
            .selectable(selected = selected, interactionSource = interaction, indication = null, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium.atWeight(weight), color = ink)
    }
}

/** A list item arriving fades in; one leaving (a block, another pick) fades out as the rest close up. */
internal fun LazyItemScope.listItemMotion(): Modifier =
    Modifier.animateItem(fadeInSpec = AynMotion.base(), placementSpec = AynMotion.base(), fadeOutSpec = AynMotion.base())
