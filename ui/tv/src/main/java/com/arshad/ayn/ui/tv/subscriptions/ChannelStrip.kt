package com.arshad.ayn.ui.tv.subscriptions

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.arshad.ayn.feature.subscriptions.StripChannel
import com.arshad.ayn.ui.tv.common.AvatarPicture
import com.arshad.ayn.ui.tv.common.LightRoom
import com.arshad.ayn.ui.tv.common.Picture
import com.arshad.ayn.ui.tv.common.ProvideBringIntoView
import com.arshad.ayn.ui.tv.common.RowFit
import com.arshad.ayn.ui.tv.common.withoutLightRoom
import com.arshad.ayn.ui.tv.common.screenWidth
import com.arshad.ayn.ui.tv.focus.AvatarSurface
import com.arshad.ayn.ui.tv.focus.ControlStyle
import com.arshad.ayn.ui.tv.focus.ControlSurface
import com.arshad.ayn.ui.tv.focus.SelectDwell
import com.arshad.ayn.ui.tv.focus.SelectDwellMs
import com.arshad.ayn.ui.tv.focus.TintedText
import com.arshad.ayn.ui.tv.focus.drawInflated
import com.arshad.ayn.ui.tv.focus.focusMemory
import com.arshad.ayn.ui.tv.theme.KeColors
import com.arshad.ayn.ui.tv.theme.KeLayout
import com.arshad.ayn.ui.tv.theme.KeMotion

// b3-subscriptions strip (px ÷ 2): 52 dp tall; All is a 32 dp pill, avatars 38 dp, 11 apart.
internal val StripHeight = 52.dp
private val AllWidth = 59.dp
private val AllHeight = 32.dp
private val AvatarSize = 38.dp
private val StripGap = 11.dp
private val NameGap = 7.dp
private val NameRise = 3.dp
private val SelectedRingGap = 1.5.dp
private val SelectedRingOuter = 2.5.dp
private val FailedDot = 11.dp
private val FailedDotRing = 2.dp
private const val FailedAlpha = 0.4f
private val NameSize = 11.5.sp
private val MonogramSize = 14.sp
private val AllTextSize = 12.5.sp

/** A selected All rests as a light keyline pill; unselected, as the page's faint well. */
private val AllSelected = ControlStyle.Pill.copy(
    restFill = KeColors.Ink.copy(alpha = 0.16f),
    restContent = KeColors.Ink,
    restKeyline = KeColors.Ink.copy(alpha = 0.5f),
)

private val Grayscale = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

/**
 * The channel strip (`b3-subscriptions`): All, then each channel's avatar, selecting on focus
 * (TVF-6) so the feed below follows. A focused channel is selected once focus has rested on it
 * for [SelectDwellMs] (OK selects at once), so channels D-pad only passes over build no feed;
 * focus leaving the strip first selects nothing. Focus coming into the strip lands on the
 * selection, so moving up from the feed never picks another channel by accident. All stays put
 * while the avatars scroll whole steps ([RowFit]).
 */
@Composable
internal fun ChannelStrip(
    channels: List<StripChannel>,
    selectedUrl: String?,
    onSelect: (channelUrl: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val all = remember { FocusRequester() }
    val selectedAvatar = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val dwell = remember(scope) { SelectDwell(scope) }
    val density = LocalDensity.current
    val avatarsWidth = screenWidth() - KeLayout.ContentStart - AllWidth
    val fit = remember(density, avatarsWidth) {
        with(density) {
            RowFit(
                start = StripGap.toPx(),
                end = KeLayout.SafeHorizontal.toPx(),
                itemWidth = AvatarSize.toPx(),
                step = (AvatarSize + StripGap).toPx(),
            )
        }
    }
    val endRoom = with(density) { fit.endRoom(avatarsWidth.toPx()).toDp() }
    Row(
        modifier = modifier
            .padding(start = KeLayout.ContentStart)
            .height(StripHeight)
            // An avatar scrolled out of composition can't take focus: the default entry then runs.
            .focusProperties { onEnter = { if (selectedUrl == null) all.requestFocus() else selectedAvatar.requestFocus() } }
            .onFocusChanged { if (!it.hasFocus) dwell.cancel() }
            .focusGroup(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AllPill(
            selected = selectedUrl == null,
            // Also on the selection itself: that replaces a pending selection with no change.
            onFocused = { dwell.rest { onSelect(null) } },
            onClick = { dwell.now { onSelect(null) } },
            modifier = Modifier.focusRequester(all),
        )
        ProvideBringIntoView(fit) {
            LazyRow(
                // A strip coming back (after a refresh) starts at the selection, so it is composed
                // and focus entering the strip can land on it.
                state = rememberLazyListState(
                    initialFirstVisibleItemIndex = channels.indexOfFirst { it.subscription.channelUrl == selectedUrl }.coerceAtLeast(0),
                ),
                // Room for the focused avatar's drop and its name below, which the row would clip.
                contentPadding = PaddingValues(start = StripGap, end = endRoom, top = LightRoom, bottom = LightRoom),
                horizontalArrangement = Arrangement.spacedBy(StripGap),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().withoutLightRoom(),
            ) {
                items(channels, key = { it.subscription.channelUrl }) { channel ->
                    val url = channel.subscription.channelUrl
                    val selected = url == selectedUrl
                    StripAvatar(
                        channel = channel,
                        selected = selected,
                        onFocused = { dwell.rest { onSelect(url) } },
                        onClick = { dwell.now { onSelect(url) } },
                        modifier = if (selected) Modifier.focusRequester(selectedAvatar) else Modifier,
                    )
                }
            }
        }
    }
}

@Composable
private fun AllPill(selected: Boolean, onFocused: () -> Unit, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ControlSurface(
        onClick = onClick,
        style = if (selected) AllSelected else ControlStyle.Pill,
        modifier = modifier
            .focusMemory(AllKey)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .size(AllWidth, AllHeight),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            TintedText("All", style = MaterialTheme.typography.labelLarge.copy(fontSize = AllTextSize))
        }
    }
}

/**
 * A channel's avatar: a ring while selected, its name shown below while focused (D8: both
 * animate); a channel whose feed failed rests grey and dim with a warning dot.
 */
@Composable
private fun StripAvatar(
    channel: StripChannel,
    selected: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val reveal = animateFloatAsState(if (focused) 1f else 0f, KeMotion.base(), label = "name")
    val ring = animateFloatAsState(if (selected && !focused) 1f else 0f, KeMotion.fast(), label = "selected")
    val subscription = channel.subscription
    Box(Modifier.size(AvatarSize)) {
        AvatarSurface(
            onClick = onClick,
            modifier = modifier
                .focusMemory(avatarKey(subscription.channelUrl))
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onFocused()
                }
                .semantics { contentDescription = subscription.name }
                .drawBehind {
                    drawInflated(CircleShape, SelectedRingOuter, KeColors.Ink2, ring.value)
                    drawInflated(CircleShape, SelectedRingGap, KeColors.Ground, ring.value)
                }
                .fillMaxSize(),
        ) {
            val dim = if (channel.failed) Modifier.graphicsLayer { alpha = FailedAlpha } else Modifier
            // A subscription can come without a picture (an older backup, an import): its monogram.
            if (subscription.avatarUrl == null) {
                AvatarPicture(subscription.name, url = null, monogramSize = MonogramSize, modifier = dim)
            } else {
                Picture(url = subscription.avatarUrl, modifier = dim, colorFilter = if (channel.failed) Grayscale else null)
            }
        }
        if (channel.failed) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = FailedDotRing + 1.dp, y = FailedDotRing + 1.dp)
                    .size(FailedDot + FailedDotRing * 2)
                    .background(KeColors.Ground, CircleShape)
                    .padding(FailedDotRing)
                    .background(KeColors.Warn, CircleShape),
            )
        }
        Text(
            text = subscription.name,
            style = MaterialTheme.typography.titleSmall.copy(fontSize = NameSize),
            color = KeColors.Ink,
            maxLines = 1,
            // The avatar's content description carries the name; this is drawn under the avatar
            // at any width without changing the strip's layout.
            modifier = Modifier
                .clearAndSetSemantics {}
                .layout { measurable, _ ->
                    val placeable = measurable.measure(Constraints())
                    val avatar = AvatarSize.roundToPx()
                    layout(0, 0) {
                        placeable.placeWithLayer((avatar - placeable.width) / 2, avatar + NameGap.roundToPx()) {
                            alpha = reveal.value
                            translationY = -NameRise.toPx() * (1f - reveal.value)
                        }
                    }
                },
        )
    }
}
