package com.arshad.ayn.ui.tv.shell

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.ui.tv.focus.ControlStyle
import com.arshad.ayn.ui.tv.focus.ControlSurface
import com.arshad.ayn.ui.tv.focus.TintedIcon
import com.arshad.ayn.ui.tv.focus.TintedText
import com.arshad.ayn.ui.tv.focus.atAlpha
import com.arshad.ayn.ui.tv.focus.contentTint
import com.arshad.ayn.ui.tv.theme.KeColors
import com.arshad.ayn.ui.tv.theme.KeLayout
import com.arshad.ayn.ui.tv.theme.KeMotion
import com.arshad.ayn.ui.tv.R
import com.arshad.ayn.ui.tv.profiles.MiniPlate
import com.arshad.ayn.ui.tv.profiles.ProfilePlate

/** How much of the rail shows over a page: all of it, dimmed with the page under a sheet, or
 *  none behind a screen that covers the shell (a confirm, a lock entry, the add form). */
internal enum class RailShade(val alpha: Float) {
    Lit(1f),
    Dimmed(0.38f),
    Hidden(0f),
}

/** A rail entry: one top-level destination that is built (the rail never leads to a dead end). */
internal class RailDestination(
    val label: String,
    @param:DrawableRes val icon: Int,
    val route: Any,
) {
    // NavDestination.hasRoute is declared inside NavDestination.Companion (not importable as
    // a top-level extension), so it is reached through the companion.
    fun isShowing(destination: NavDestination?): Boolean =
        NavDestination.Companion.run { destination?.hasRoute(route::class) == true }
}

// Master mockup rail geometry (px ÷ 2): items start 3 dp inside the safe line so the focused
// pill's growth stays inside it.
private val RailStart = KeLayout.SafeHorizontal + 3.dp
private val MarkSize = 32.dp
// The Ayn mark (the launcher icon's ع) on an ink plate.
private val MarkPlate = 26.dp
private val MarkGlyphWidth = 13.dp
private val MarkGlyphHeight = 19.dp
private val MarkToItems = 84.dp
private val ItemWidth = 150.dp
private val ItemHeight = 32.dp
private val ItemGap = 7.dp
private val ItemPaddingStart = 8.dp
private val IconSize = 16.dp
private val IconToLabel = 11.dp
private val LabelShift = 6.dp
private val CurrentDot = 2.dp
private val ScrimWidth = 360.dp
private val SlotHeight = 36.dp
private val SlotPaddingStart = 6.dp
private val SlotBottom = 2.dp
private const val SlotHintAlpha = 0.72f
private val SlotLockSize = 10.dp

/**
 * The navigation rail: icons at rest; when it holds focus it opens (labels slide in over the
 * rail's scrim; the shell owns the open state and recedes the page). Only the current item is
 * focusable while the rail is closed, so moving left into it always lands on the current
 * destination. Up and down never cross between the rail and the page: the closed rail's
 * item spans into the page's columns, so a vertical move there would open it by accident.
 * RIGHT from any item or the slot goes to [page] (the page sits under the rail, so spatial
 * search finds nothing to the right). [profile]'s slot sits at the bottom (`b3-profiles`
 * rail): reachable once the rail is open, it opens the profiles ([onProfile]), locking a
 * locked profile first; a kids profile's slot leaves it behind the owner's lock (`kp-kids`
 * kidrail, KP-D9). While [inert] (a screen shows over the rail) focus can't enter it.
 */
@Composable
internal fun KeRail(
    destinations: List<RailDestination>,
    current: RailDestination,
    open: Boolean,
    currentItem: FocusRequester,
    page: FocusRequester,
    profile: Profile,
    inert: Boolean = false,
    onSelect: (RailDestination) -> Unit,
    onProfile: () -> Unit,
    onOpenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val openness = animateFloatAsState(if (open) 1f else 0f, KeMotion.base(), label = "rail")

    Box(modifier = modifier.fillMaxHeight().width(ScrimWidth)) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = openness.value }
                .background(RailScrimBrush),
        )
        Box(
            Modifier
                .fillMaxHeight()
                .padding(start = RailStart, top = KeLayout.SafeVertical, bottom = KeLayout.SafeVertical + SlotBottom)
                .onFocusChanged { onOpenChange(it.hasFocus) }
                .focusProperties {
                    onEnter = { if (inert || requestedFocusDirection.isVertical()) cancelFocusChange() }
                    onExit = { if (requestedFocusDirection.isVertical()) cancelFocusChange() }
                }
                .focusGroup(),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(ItemGap)) {
                Mark()
                // The column's spacing lands on both sides of the spacer.
                Spacer(Modifier.height(MarkToItems - ItemGap * 2))
                destinations.forEach { destination ->
                    val isCurrent = destination === current
                    RailItem(
                        destination = destination,
                        isCurrent = isCurrent,
                        open = open,
                        openness = openness,
                        onClick = { onSelect(destination) },
                        modifier = Modifier
                            .then(if (isCurrent) Modifier.focusRequester(currentItem) else Modifier)
                            .focusProperties {
                                canFocus = open || isCurrent
                                right = page
                            },
                    )
                }
            }
            ProfileSlot(
                profile = profile,
                openness = openness,
                onClick = onProfile,
                modifier = Modifier.align(Alignment.BottomStart).focusProperties {
                    canFocus = open
                    right = page
                },
            )
        }
    }
}

private val RailScrimBrush = Brush.horizontalGradient(
    0f to KeColors.RailScrim.copy(alpha = 0.98f),
    0.53f to KeColors.RailScrim.copy(alpha = 0.94f),
    0.78f to KeColors.RailScrim.copy(alpha = 0.5f),
    1f to KeColors.RailScrim.copy(alpha = 0f),
)

@Composable
private fun Mark(modifier: Modifier = Modifier) {
    Box(modifier.size(MarkSize), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(MarkPlate).background(KeColors.Ink, RoundedCornerShape(5.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_ayn_mark),
                contentDescription = "Ayn",
                tint = KeColors.Ground,
                modifier = Modifier.size(width = MarkGlyphWidth, height = MarkGlyphHeight),
            )
        }
    }
}

@Composable
private fun RailItem(
    destination: RailDestination,
    isCurrent: Boolean,
    open: Boolean,
    openness: State<Float>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val restContent = animateColorAsState(
        targetValue = when {
            isCurrent -> KeColors.Ink
            open -> KeColors.Ink2
            else -> KeColors.Ink3
        },
        animationSpec = KeMotion.fast(),
        label = "railItem",
    )
    ControlSurface(
        onClick = onClick,
        style = ControlStyle.Rail,
        shape = CircleShape,
        restContent = { restContent.value },
        modifier = modifier
            .width(ItemWidth)
            .height(ItemHeight)
            .drawWithContent {
                drawContent()
                // The closed rail marks the current destination with a dot under its icon.
                if (isCurrent) {
                    drawCircle(
                        color = KeColors.Ink,
                        radius = CurrentDot.toPx() / 2,
                        center = Offset(
                            x = (ItemPaddingStart + IconSize / 2).toPx(),
                            y = size.height - 2.dp.toPx(),
                        ),
                        alpha = 1f - openness.value,
                    )
                }
            },
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(start = ItemPaddingStart),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TintedIcon(
                painter = painterResource(destination.icon),
                contentDescription = null,
                modifier = Modifier.size(IconSize),
            )
            Spacer(Modifier.width(IconToLabel))
            TintedText(
                text = destination.label,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.graphicsLayer {
                    alpha = openness.value
                    translationX = -LabelShift.toPx() * (1f - openness.value)
                },
            )
        }
    }
}

/**
 * The rail's profile slot: the current profile's plate, its name and what the slot does, which
 * slide in with the other labels. A locked profile's slot says it locks first; a kids profile's
 * says it leaves, with the lock it takes.
 */
@Composable
private fun ProfileSlot(profile: Profile, openness: State<Float>, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ControlSurface(
        onClick = onClick,
        style = ControlStyle.Rail,
        shape = CircleShape,
        modifier = modifier.width(ItemWidth).height(SlotHeight),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(start = SlotPaddingStart),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProfilePlate(profile.name, profile.tint, MiniPlate)
            Spacer(Modifier.width(IconToLabel))
            Column(
                Modifier.graphicsLayer {
                    alpha = openness.value
                    translationX = -LabelShift.toPx() * (1f - openness.value)
                },
            ) {
                TintedText(text = profile.name, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                val ink = contentTint()
                val hintColor = remember(ink) { ink.atAlpha(SlotHintAlpha) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    TintedText(
                        text = when {
                            profile.isKids -> "Leave"
                            profile.lock != null -> "Lock and switch"
                            else -> "Switch profile"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        tint = hintColor,
                        maxLines = 1,
                    )
                    if (profile.isKids) {
                        TintedIcon(painterResource(R.drawable.ic_lock), contentDescription = "needs a lock", tint = hintColor, modifier = Modifier.size(SlotLockSize))
                    }
                }
            }
        }
    }
}

private fun FocusDirection.isVertical(): Boolean = this == FocusDirection.Up || this == FocusDirection.Down
