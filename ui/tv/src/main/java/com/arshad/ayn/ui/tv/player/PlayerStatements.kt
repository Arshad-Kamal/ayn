package com.arshad.ayn.ui.tv.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Precision
import com.arshad.ayn.core.access.AccessFailure
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.feature.player.LiveStop
import com.arshad.ayn.feature.player.PlayerPolicy
import com.arshad.ayn.feature.player.liveStopText
import com.arshad.ayn.feature.player.timeText
import com.arshad.ayn.ui.tv.R
import com.arshad.ayn.ui.tv.common.GuidedButton
import com.arshad.ayn.ui.tv.common.Kicker
import com.arshad.ayn.ui.tv.common.UnreadableReply
import com.arshad.ayn.ui.tv.common.failureKicker
import com.arshad.ayn.ui.tv.common.rememberRise
import com.arshad.ayn.ui.tv.common.rising
import com.arshad.ayn.ui.tv.focus.ControlStyle
import com.arshad.ayn.ui.tv.focus.ControlSurface
import com.arshad.ayn.ui.tv.theme.KeColors
import com.arshad.ayn.ui.tv.theme.KeLayout
import com.arshad.ayn.ui.tv.theme.KeMotion
import com.arshad.ayn.ui.tv.theme.atWeight

/** What a stopped player says (`b3-player` states 9–11 and the v8 gaps); no [retryLabel] = Back only. */
internal data class StopCopy(
    val kicker: Kicker,
    val title: String,
    val body: String,
    val retryLabel: String?,
)

/**
 * The statement for a stop [reason] (`PlayerPolicy.TERMINAL_*`). A kept place ([positionMs] > 0)
 * is named and Try again resumes there (the service seeks to it). A refusal (YouTube said no:
 * private, removed, age-restricted, or the stream refused mid-play) offers Back only; the
 * extractor names two kinds, made-for-kids and not started (whose [detail] is YouTube's own
 * sentence), and can't tell the rest apart (stack-notes "Refused video pages"). The profile's own
 * refusals offer Back only too: its filters, or a kids profile's time (Time's up takes over at
 * the timer's next tick, KP-D26). Under Restricted Mode Strict ([restricted], the profile's own or
 * the owner's) YouTube's refusal names that as a cause too: it can't be told apart either.
 */
internal fun stopCopy(reason: String, positionMs: Long, detail: String? = null, restricted: Boolean = false): StopCopy {
    val place = positionMs.takeIf { it > 0 }?.let(::timeText)
    val saved = place?.let { " Your place is saved at $it." }.orEmpty()
    val retry = place?.let { "Try again from $it" } ?: "Try again"
    return when (reason) {
        PlayerPolicy.TERMINAL_EXHAUSTED -> StopCopy(
            Kicker(R.drawable.ic_alert, "Stopped after ${PlayerPolicy.RECOVERABLE_RETRY_BUDGET} tries"),
            "This video stopped playing",
            "YouTube kept dropping the connection.$saved",
            retry,
        )
        PlayerPolicy.TERMINAL_ATTESTATION -> StopCopy(
            Kicker(R.drawable.ic_alert, "Stopped after ${PlayerPolicy.ATTESTATION_RETRY_BUDGET} refreshes"),
            "This video stopped playing",
            "YouTube didn’t accept Ayn’s playback session.$saved",
            retry,
        )
        PlayerPolicy.TERMINAL_UNAVAILABLE, PlayerPolicy.TERMINAL_PROTOCOL -> StopCopy(
            Kicker(R.drawable.ic_alert, "YouTube refused to play it"),
            "This video can’t play in Ayn",
            refusedBody(restricted),
            retryLabel = null,
        )
        PlayerPolicy.TERMINAL_MADE_FOR_KIDS -> StopCopy(
            Kicker(R.drawable.ic_alert, "YouTube refused to play it"),
            "This made-for-kids video can’t play in Ayn",
            "YouTube wouldn’t send it to Ayn, through either of the players Ayn uses.",
            retryLabel = null,
        )
        PlayerPolicy.TERMINAL_NOT_STARTED -> StopCopy(
            Kicker(R.drawable.ic_later, "Not started yet"),
            "This video hasn’t started yet",
            detail ?: "It’s a premiere or a scheduled stream. Come back when it begins.",
            retryLabel = null,
        )
        PlayerPolicy.TERMINAL_FILTERED -> StopCopy(
            Kicker(R.drawable.ic_block, "Hidden in this profile"),
            "This video can’t play here",
            "This profile’s filters keep it from playing.",
            retryLabel = null,
        )
        PlayerPolicy.TERMINAL_TIME_UP -> StopCopy(
            Kicker(R.drawable.ic_later, "Watch time"),
            "Time’s up for now",
            "This profile’s watch time has run out.",
            retryLabel = null,
        )
        PlayerPolicy.TERMINAL_RESOLVE_OFFLINE -> StopCopy(
            failureKicker(AccessFailure.OFFLINE),
            "This video couldn’t start",
            "Check that the Shield is online, then try again.",
            retry,
        )
        // TERMINAL_RESOLVE_UNREADABLE, and any reason this screen predates.
        else -> StopCopy(
            failureKicker(AccessFailure.UNREADABLE),
            "This video couldn’t start",
            UnreadableReply,
            retry,
        )
    }
}

private fun refusedBody(restricted: Boolean): String =
    if (restricted) {
        "It may be private, removed, age-restricted, members-only, blocked in your region, or hidden by Restricted Mode."
    } else {
        "It may be private, removed, age-restricted, members-only, or blocked in your region."
    }

/** v8 player-down: the playback service didn't answer; Try again reconnects. */
internal val PlayerDownCopy = StopCopy(
    Kicker(R.drawable.ic_alert, "The player didn’t answer"),
    "This video couldn’t start",
    "Ayn’s player stopped responding. Try again; if it keeps happening, restart Ayn.",
    "Try again",
)

@Composable
internal fun StopStatement(copy: StopCopy, picture: String?, onRetry: () -> Unit, onBack: () -> Unit) {
    GuidedStatement(
        picture = picture,
        kicker = { KickerLine(copy.kicker) },
        title = copy.title,
        body = copy.body,
        retryLabel = copy.retryLabel,
        onRetry = onRetry,
        onBack = onBack,
    )
}

/**
 * A live stream's statement (`live-tv-player` live-unavailable, live-kids, live-ended; the words
 * [liveStopText]'s). Try again ([onRetry], asking afresh) shows only when given; else Back is the
 * only action.
 */
@Composable
internal fun LiveStatement(stop: LiveStop, card: VideoItem, onBack: () -> Unit, onRetry: (() -> Unit)? = null) {
    val text = liveStopText(stop, card)
    GuidedStatement(
        picture = card.thumbnailUrl,
        kicker = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LiveTag(lit = stop != LiveStop.Ended)
                Text(text.kicker, style = kickerStyle(), color = KeColors.Ink2)
            }
        },
        title = text.title,
        body = text.body,
        retryLabel = "Try again".takeIf { onRetry != null },
        onRetry = onRetry ?: {},
        onBack = onBack,
    )
}

// `.terminal .guided` (px ÷ 2): copy on the left, a 280 dp action column on the right.
private val ActionsWidth = 280.dp
private val ActionsGap = 60.dp
private val ActionsEndInset = 13.dp
private val BodyWidth = 430.dp
private const val ActionsDelayMs = 40

/**
 * The guided statement over the video's own picture (blurred and dark): what happened, a headline,
 * one paragraph, and the actions in a column, the first focused (TVF-7). Without [retryLabel],
 * Back is the only action. Both halves rise in (D8).
 */
@Composable
private fun GuidedStatement(
    picture: String?,
    kicker: @Composable () -> Unit,
    title: String,
    body: String,
    retryLabel: String?,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    val first = remember { FocusRequester() }
    val rise = rememberRise()
    val riseActions = rememberRise(ActionsDelayMs)
    LaunchedEffect(Unit) {
        withFrameNanos { }
        first.requestFocus()
    }

    Box(Modifier.fillMaxSize()) {
        BlurredPicture(picture, brightness = 0.42f)
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to PlayerShade.copy(alpha = 0.82f),
                        0.6f to PlayerShade.copy(alpha = 0.5f),
                        1f to PlayerShade.copy(alpha = 0.35f),
                    ),
                ),
        )
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = KeLayout.SafeHorizontal, end = KeLayout.SafeHorizontal, bottom = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).rising(rise)) {
                kicker()
                Text(
                    text = title,
                    style = MaterialTheme.typography.displaySmall,
                    modifier = Modifier.padding(top = 11.dp),
                )
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 1.4.em),
                    color = KeColors.Ink2,
                    modifier = Modifier.padding(top = 13.dp).widthIn(max = BodyWidth),
                )
            }
            Spacer(Modifier.width(ActionsGap))
            Column(
                modifier = Modifier.width(ActionsWidth).padding(end = ActionsEndInset).rising(riseActions),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (retryLabel != null) {
                    GuidedButton(R.drawable.ic_retry, retryLabel, ControlStyle.Primary, onRetry, Modifier.focusRequester(first))
                    GuidedButton(R.drawable.ic_back, "Back", ControlStyle.Secondary, onBack)
                } else {
                    GuidedButton(R.drawable.ic_back, "Back", ControlStyle.Primary, onBack, Modifier.focusRequester(first))
                }
            }
        }
    }
}

@Composable
private fun KickerLine(kicker: Kicker) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(painterResource(kicker.icon), contentDescription = null, tint = KeColors.Ink2, modifier = Modifier.size(15.dp))
        Text(kicker.text, style = kickerStyle(), color = KeColors.Ink2)
    }
}

@Composable
private fun kickerStyle() = MaterialTheme.typography.labelMedium.copy(fontSize = 13.5.sp)

/** The LIVE tag (`.lvtag`): lit red at the live edge, an outline behind it or once the stream
 *  has ended, crossfading as it turns. */
@Composable
internal fun LiveTag(lit: Boolean, modifier: Modifier = Modifier) {
    val fill by animateFloatAsState(if (lit) 1f else 0f, KeMotion.base(), label = "liveTag")
    val ink = lerp(KeColors.Ink2, KeColors.Ink, fill)
    Row(
        modifier = modifier
            .drawBehind {
                val radius = CornerRadius(TagRadius.toPx())
                drawRoundRect(KeColors.Live, cornerRadius = radius, alpha = fill)
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
            .padding(start = 4.5.dp, end = 5.dp, top = 1.5.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.5.dp),
    ) {
        Icon(painterResource(R.drawable.ic_live), contentDescription = null, tint = ink, modifier = Modifier.size(11.dp))
        Text(
            "LIVE",
            style = MaterialTheme.typography.labelSmall.atWeight(700).copy(fontSize = 11.sp, letterSpacing = 0.04.em),
            color = ink,
        )
    }
}

private val TagRadius = 3.5.dp
private val TagRing = 1.dp
private val TagOutline = Color.White.copy(alpha = 0.3f)

// A small decode, upscaled: the blur Modifier.blur can't give on API 30 (the Shield), at a fraction
// of a full-size picture's memory (TVU-3).
private const val BlurDecodeHeight = 24
private const val BlurScale = 1.06f

/** A picture at [brightness] and [saturation] of its own. */
internal fun dimFilter(brightness: Float, saturation: Float): ColorFilter {
    val matrix = ColorMatrix().apply { setToSaturation(saturation) }
    matrix.timesAssign(ColorMatrix().apply { setToScale(brightness, brightness, brightness, 1f) })
    return ColorFilter.colorMatrix(matrix)
}

/**
 * [url] full-bleed, blurred by drawing a [decodeHeight] px decode at screen size, at [brightness]
 * and [saturation] of its own.
 */
@Composable
internal fun BlurredPicture(
    url: String?,
    brightness: Float,
    modifier: Modifier = Modifier,
    saturation: Float = 0.85f,
    decodeHeight: Int = BlurDecodeHeight,
) {
    if (url == null) return
    val filter = remember(brightness, saturation) { dimFilter(brightness, saturation) }
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(url)
            .size(decodeHeight)
            .precision(Precision.EXACT)
            .crossfade(KeMotion.BASE_MS)
            .build(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        colorFilter = filter,
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = BlurScale
                scaleY = BlurScale
            },
    )
}
