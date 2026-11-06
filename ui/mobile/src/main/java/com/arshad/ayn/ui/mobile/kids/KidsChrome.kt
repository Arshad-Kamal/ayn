package com.arshad.ayn.ui.mobile.kids

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arshad.ayn.core.kids.KidsClock
import com.arshad.ayn.core.kids.KidsTime
import com.arshad.ayn.feature.kids.timeLeftText
import com.arshad.ayn.feature.kids.warningText
import com.arshad.ayn.ui.mobile.R
import com.arshad.ayn.ui.mobile.theme.AynColors
import com.arshad.ayn.ui.mobile.theme.AynMotion
import com.arshad.ayn.ui.mobile.theme.atWeight
import com.arshad.ayn.ui.mobile.theme.weightSpan
import com.arshad.ayn.ui.mobile.common.rememberTimeout
import kotlin.time.Duration
import kotlinx.coroutines.delay

/** How long a time note stays (`m6-kids` 7–8: 6 s, as the TV's toast). */
internal const val WARNING_MS = 6_000L

/** The time-left chip's height (`.tleft`), and its line's under the pill with the gap below. */
internal val TimeLeftChipHeight = 32.dp
internal val TimeLeftLineHeight = 40.dp

private val KidsShape = RoundedCornerShape(16.dp)
private val ChipFill = AynColors.Ink.copy(alpha = 0.09f)

// `.kwarn`: a dark ground leaning to the kid's colour, ringed in it.
private val WarningGround = Color(0xFF211B2A).copy(alpha = 0.96f)
private const val WarningRingAlpha = 0.35f
private val RiseBy = 24.dp

/** The kids profile's time left today ([clock]'s), `null` without a limit or a clock. */
@Composable
internal fun rememberTimeLeft(clock: KidsClock?): Duration? {
    clock ?: return null
    val time by clock.time.collectAsStateWithLifecycle()
    return (time as? KidsTime.Left)?.left
}

/**
 * The time left today under the kids pill (`m6-kids` 2, 6; Q55): a clock in the kid's [tint], the
 * time in bold, "left today" after it.
 */
@Composable
internal fun TimeLeftChip(left: Duration, tint: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .heightIn(min = TimeLeftChipHeight)
            .background(ChipFill, KidsShape)
            .padding(start = 10.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Icon(painterResource(R.drawable.ic_later), contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Text(
            text = buildAnnotatedString {
                withStyle(weightSpan(680, AynColors.Ink)) { append(timeLeftText(left)) }
                append(" left today")
            },
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, fontFeatureSettings = "tnum"),
            color = AynColors.Ink2,
            maxLines = 1,
        )
    }
}

/**
 * The 5- and 1-minute notes (`m6-kids` 7–8): over whatever shows, the watch page included, for
 * [WARNING_MS] (longer when the accessibility settings ask), then [KidsClock.consumeWarning]; it takes no tap. It rises in and sinks away with
 * its last line (alpha and transform, D8), and is announced.
 */
@Composable
internal fun KidsWarning(clock: KidsClock, tint: Color, modifier: Modifier = Modifier) {
    val minutes by clock.warning.collectAsStateWithLifecycle()
    val consume by rememberUpdatedState(clock::consumeWarning)
    val showFor = rememberTimeout(WARNING_MS, containsIcons = true, containsText = true)
    val last = remember { LastWarning() }
    minutes?.let { last.minutes = it }
    val live = minutes != null
    val rise = remember { Animatable(0f) }
    LaunchedEffect(live) { rise.animateTo(if (live) 1f else 0f, AynMotion.base()) }
    LaunchedEffect(minutes) {
        if (minutes == null) return@LaunchedEffect
        delay(showFor)
        consume()
    }
    val shown = minutes ?: last.minutes?.takeIf { rise.value > 0f } ?: return
    Row(
        modifier = modifier
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 16.dp, end = 16.dp, bottom = 24.dp)
            .fillMaxWidth()
            .graphicsLayer {
                alpha = rise.value
                translationY = (1f - rise.value) * RiseBy.toPx()
            }
            .background(WarningGround, KidsShape)
            .border(1.dp, tint.copy(alpha = WarningRingAlpha), KidsShape)
            .heightIn(min = 52.dp)
            .padding(horizontal = 16.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(painterResource(R.drawable.ic_later), contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Text(warningText(shown), style = MaterialTheme.typography.bodyLarge.atWeight(680).copy(fontSize = 15.sp), color = AynColors.Ink)
    }
}

// Written while the note shows so it can sink with its last line; no composition reads it for invalidation.
private class LastWarning {
    var minutes: Int? = null
}
