package com.arshad.ayn.ui.mobile.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.arshad.ayn.feature.player.PlayerChromePolicy
import com.arshad.ayn.feature.player.liveWindowText
import com.arshad.ayn.feature.player.timeText
import com.arshad.ayn.ui.mobile.R
import com.arshad.ayn.ui.mobile.common.pressScale
import com.arshad.ayn.ui.mobile.theme.AynColors
import com.arshad.ayn.ui.mobile.theme.AynMotion
import com.arshad.ayn.ui.mobile.theme.atWeight
import com.arshad.ayn.ui.mobile.theme.weightSpan

private val TagRadius = 5.dp
private val TagRing = 1.5.dp
private val TagOutline = Color.White.copy(alpha = 0.32f)

/** The LIVE tag (`.ltag`): lit red at the live edge, an outline behind it or once the stream has
 *  ended, crossfading as it turns. */
@Composable
internal fun LiveTag(lit: Boolean, modifier: Modifier = Modifier) {
    val fill by animateFloatAsState(if (lit) 1f else 0f, AynMotion.base(), label = "liveTag")
    val ink = lerp(AynColors.Ink2, Color.White, fill)
    Row(
        modifier
            .height(20.dp)
            .drawBehind {
                val radius = CornerRadius(TagRadius.toPx())
                drawRoundRect(AynColors.Live, cornerRadius = radius, alpha = fill)
                val ring = TagRing.toPx()
                drawRoundRect(
                    TagOutline,
                    topLeft = Offset(ring / 2, ring / 2),
                    size = Size(size.width - ring, size.height - ring),
                    cornerRadius = radius,
                    style = Stroke(ring),
                    alpha = 1f - fill,
                )
            }
            .padding(start = 6.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(painterResource(R.drawable.ic_live), contentDescription = null, tint = ink, modifier = Modifier.size(13.dp))
        Text("LIVE", style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, letterSpacing = 0.05.em).atWeight(750), color = ink)
    }
}

/** Go live (`.golive`): the red live mark on a light pill; it sinks while pressed. */
@Composable
internal fun GoLiveButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .minimumInteractiveComponentSize()
            .height(30.dp)
            .pressScale(interaction)
            .clip(CircleShape)
            .background(AynColors.Ink)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(start = 9.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(painterResource(R.drawable.ic_live), contentDescription = null, tint = AynColors.Live, modifier = Modifier.size(15.dp))
        Text("Go live", style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp).atWeight(650), color = AynColors.OnInk)
    }
}

/** "**24:10** behind live" (`.lbh`), read where it is drawn: the position tick recomposes it alone. */
@Composable
private fun BehindLive(times: PlayTimes, modifier: Modifier = Modifier) {
    Text(
        buildAnnotatedString {
            withStyle(weightSpan(650, AynColors.Ink)) { append(timeText(PlayerChromePolicy.liveBehindMs(times.positionMs(), times.durationMs()))) }
            append(" behind live")
        },
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.5.sp, fontFeatureSettings = "tnum"),
        color = AynColors.Ink2,
        modifier = modifier,
    )
}

/**
 * Where a live stream's times sit (`live-phone-player` 2–3, 13, 15): the LIVE tag, lit at the edge;
 * [behind] it, the outline, how far behind and Go live ([onGoLive]) at the right. Sideways
 * ([window]) the window's length takes the right while at the edge. The row keeps Go live's touch
 * height, so nothing moves as it comes and goes; the tag tells a screen reader when it flips.
 */
@Composable
internal fun LiveTimes(times: PlayTimes, behind: Boolean, window: Boolean, onGoLive: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        LiveTag(
            lit = !behind,
            Modifier.semantics {
                liveRegion = LiveRegionMode.Polite
                stateDescription = if (behind) "Behind live" else "At the live edge"
            },
        )
        AnimatedVisibility(behind, enter = fadeIn(AynMotion.base()), exit = fadeOut(AynMotion.base())) {
            BehindLive(times, Modifier.padding(start = 8.dp))
        }
        Spacer(Modifier.weight(1f))
        if (window && !behind) {
            liveWindowText(times.durationMs())?.let {
                Text(it, style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.5.sp), color = AynColors.Ink2)
            }
        }
        AnimatedVisibility(behind, enter = fadeIn(AynMotion.base()), exit = fadeOut(AynMotion.base())) {
            GoLiveButton(onGoLive, Modifier.padding(end = 4.dp))
        }
    }
}
