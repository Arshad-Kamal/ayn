package com.arshad.ayn.ui.mobile.subscriptions

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arshad.ayn.feature.subscriptions.StripChannel
import com.arshad.ayn.ui.mobile.R
import com.arshad.ayn.ui.mobile.common.ButtonKind
import com.arshad.ayn.ui.mobile.common.ChannelAvatar
import com.arshad.ayn.ui.mobile.common.PillButton
import com.arshad.ayn.ui.mobile.common.SelectChip
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import com.arshad.ayn.ui.mobile.common.pressScale
import com.arshad.ayn.ui.mobile.home.SkeletonBar
import com.arshad.ayn.ui.mobile.theme.AynColors
import com.arshad.ayn.ui.mobile.theme.AynMotion
import com.arshad.ayn.ui.mobile.theme.atWeight
import com.arshad.ayn.ui.mobile.theme.weightSpan

private val AvatarSize = 52.dp
private val AvatarItemWidth = 64.dp
private val RingGap = 2.5.dp
private val RingWidth = 2.dp
private val MarkSize = 22.dp
private const val FailedAlpha = 0.4f
private val NameSize = 11.5.sp

/**
 * The channel strip (`m6-subscriptions-and-channel` 1, 3, 4): All, then each channel's avatar with
 * its name; the pick is ringed (All filled while none is). A tap picks a channel; All, or the pick
 * again, goes back to every channel. A channel whose feed didn't answer is dimmed with a mark.
 */
@Composable
internal fun ChannelStrip(channels: List<StripChannel>, selectedUrl: String?, onSelect: (String?) -> Unit, modifier: Modifier = Modifier) {
    LazyRow(
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier.fillMaxWidth().selectableGroup(),
    ) {
        item(key = "all") { SelectChip("All", selectedUrl == null, { onSelect(null) }, Modifier.padding(top = 6.dp).height(40.dp), weight = 650) }
        items(channels, key = { it.subscription.channelUrl }) { channel ->
            val url = channel.subscription.channelUrl
            val picked = url == selectedUrl
            StripAvatar(channel, picked, onClick = { onSelect(if (picked) null else url) })
        }
    }
}

/** A channel's avatar (`.chav`): ringed when [picked], dimmed and marked when its feed failed. */
@Composable
private fun StripAvatar(channel: StripChannel, picked: Boolean, onClick: () -> Unit) {
    val name = channel.subscription.name
    val ring by animateFloatAsState(if (picked) 1f else 0f, AynMotion.base(), label = "ring")
    val interaction = remember { MutableInteractionSource() }
    Column(
        Modifier
            .width(AvatarItemWidth)
            .clickable(interactionSource = interaction, indication = null, role = Role.Tab) { onClick() }
            .semantics(mergeDescendants = true) {
                contentDescription = if (channel.failed) "$name, didn’t refresh" else name
                selected = picked
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(AvatarSize)
                .pressScale(interaction)
                .drawBehind {
                    if (ring > 0f) {
                        val inset = RingGap.toPx() + RingWidth.toPx() / 2
                        drawCircle(AynColors.Ink.copy(alpha = ring), radius = size.minDimension / 2 + inset, style = Stroke(RingWidth.toPx()))
                    }
                },
        ) {
            ChannelAvatar(name, channel.subscription.avatarUrl, Modifier.graphicsLayer { alpha = if (channel.failed) FailedAlpha else 1f })
            if (channel.failed) FailedMark(Modifier.align(Alignment.BottomEnd).offset(x = 2.dp))
        }
        Text(
            name,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = NameSize, lineHeight = 14.sp).let { if (picked) it.atWeight(650) else it },
            color = if (picked) AynColors.Ink else AynColors.Ink3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The ⚠ on a failed channel's avatar (`.chav em`). */
@Composable
private fun FailedMark(modifier: Modifier = Modifier) {
    Box(
        modifier.size(MarkSize).border(2.dp, AynColors.Ground, CircleShape).padding(2.dp).background(AynColors.Surface3, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(R.drawable.ic_alert), contentDescription = null, tint = AynColors.Warn, modifier = Modifier.size(12.dp))
    }
}

/** State 4 (`.pnote2`): which channels didn't refresh, and Try again. */
@Composable
internal fun PartialNote(names: List<String>, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val count = names.size
    Row(
        modifier
            .padding(start = 12.dp, end = 12.dp, top = 8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(AynColors.Warn.copy(alpha = 0.1f))
            .border(0.5.dp, AynColors.Warn.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
            .padding(start = 14.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(painterResource(R.drawable.ic_alert), contentDescription = null, tint = AynColors.Warn, modifier = Modifier.padding(top = 1.dp).size(18.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                buildAnnotatedString {
                    withStyle(weightSpan(650, AynColors.Ink)) { append(if (count == 1) "1 channel didn’t refresh:" else "$count channels didn’t refresh:") }
                    append(" ${names.take(NamedFailures).joinToString(", ")}")
                    if (count > NamedFailures) append(" and ${count - NamedFailures} more")
                    append(". ")
                    append(if (count == 1) "Its videos are missing until it answers." else "Their videos are missing until they answer.")
                },
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp, lineHeight = 19.sp),
                color = AynColors.Ink2,
            )
            PillButton("Try again", onRetry, Modifier.height(40.dp), kind = ButtonKind.Tonal, icon = R.drawable.ic_retry)
        }
    }
}

/** A pick's Open channel at the end of its line (`.opench`). */
@Composable
internal fun OpenChannelChip(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .height(36.dp)
            .pressScale(interaction)
            .clip(CircleShape)
            .background(AynColors.Surface2)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(start = 12.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text("Open channel", style = MaterialTheme.typography.labelMedium.atWeight(650).copy(fontSize = 13.5.sp))
        Icon(painterResource(R.drawable.ic_chevron), contentDescription = null, tint = AynColors.Ink, modifier = Modifier.size(16.dp))
    }
}

/** State 5's strip: All and as many avatars as the list holds (up to a strip's width), pulsing. */
@Composable
internal fun StripSkeleton(channelCount: Int, pulse: State<Float>) {
    Row(Modifier.padding(start = 12.dp, top = 6.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        SkeletonBar(pulse, Modifier.padding(top = 6.dp).size(52.dp, 40.dp), CircleShape)
        repeat(channelCount.coerceIn(0, SkeletonAvatars)) {
            Column(Modifier.width(AvatarItemWidth), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SkeletonBar(pulse, Modifier.size(AvatarSize), CircleShape)
                SkeletonBar(pulse, Modifier.size(40.dp, 9.dp))
            }
        }
    }
}

private const val SkeletonAvatars = 6
private const val NamedFailures = 3
