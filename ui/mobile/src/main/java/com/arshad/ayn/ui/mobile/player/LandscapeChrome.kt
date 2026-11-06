package com.arshad.ayn.ui.mobile.player

import android.text.format.DateFormat
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.arshad.ayn.core.access.Chapter
import com.arshad.ayn.core.access.SegmentCategory
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.feature.player.chapterAt
import com.arshad.ayn.feature.player.remainingText
import com.arshad.ayn.feature.player.skipControlText
import com.arshad.ayn.feature.player.timeText
import com.arshad.ayn.ui.mobile.R
import com.arshad.ayn.ui.mobile.common.ChannelAvatar
import com.arshad.ayn.ui.mobile.common.LocalDeviceSettings
import com.arshad.ayn.ui.mobile.common.pressScale
import com.arshad.ayn.ui.mobile.common.rememberCardFace
import com.arshad.ayn.ui.mobile.theme.AynColors
import com.arshad.ayn.ui.mobile.theme.AynMotion
import com.arshad.ayn.ui.mobile.theme.atWeight
import com.arshad.ayn.ui.mobile.theme.weightSpan
import java.util.Date
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.delay

/** The sideways chrome's inset past any display cutout (`.ltop`, `.lbot`). */
private val LandSide = 28.dp
private val LandFoot = 12.dp

/** Where the scrub preview rests sideways: over the bar, which sits above the pill row. */
internal val LandPreviewBottom = 82.dp

/** How far the cues rise while the sideways chrome shows: above its foot block; a screen reader's corner rises as far (Q34). */
internal val OverLandChrome = 104.dp

/** The channel the sideways chrome names (`.lch`), with its [detail]: subscribers, or a live
 *  stream's audience. */
internal class LandChannel(val name: String?, val avatarUrl: String?, val detail: String?)

/** What the sideways chrome's controls do: \u2304 docks, the transport, the pills and \u22ef open
 *  what sits beside the picture (no Up next without related videos, no Comments in a kids
 *  profile or on a live stream, no Speed on a live stream; a live stream's Chat in Comments'
 *  place, dimmed and inert unless [chatOffered], an ended stream's kept chat right after
 *  Comments, LIVE-D28), exit turns back to portrait; Skip jumps past the span playback is in
 *  ([LandscapeChrome]'s `skippable`). */
internal class LandCalls(
    val onDock: () -> Unit,
    val onHub: () -> Unit,
    val onSeekBy: (forward: Boolean) -> Unit,
    val onComments: (() -> Unit)?,
    val onChat: (() -> Unit)?,
    val chatOffered: Boolean,
    val onCaptions: () -> Unit,
    val onSpeed: (() -> Unit)?,
    val onUpNext: (() -> Unit)?,
    val onPlayback: () -> Unit,
    val onExit: () -> Unit,
    val onSkip: () -> Unit,
)

/** The sideways screen's insets: past the cutout, then [LandSide]. */
@Composable
internal fun landSides(): Modifier = Modifier.windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)).padding(horizontal = LandSide)

/**
 * The chrome over the sideways picture (`m6-player` 29), the TV's arrangement on touch, shown
 * while [shown]: \u2304 docks; the channel and its detail over the condensed title; the format
 * [badge] and the clock on [clock] (unless this phone's Clock in the player is off); back 10 \u00b7 the
 * [hub] \u00b7 forward 10; at the foot the [times] over the [bar], then Comments (a live stream's Chat), Captions ([cc]), the
 * [speed] (none on a live stream), Up next, \u22ef Playback and exit. While a scrub runs
 * ([scrubbing]) the transport and the times fade so the preview reads alone. While playback is in
 * a span the skip prompt offers ([skippable], its first category), a Skip pill with that category's
 * mark leads the foot row (`m6-player` 29b, PAF-D12).
 */
@Composable
internal fun BoxScope.LandscapeChrome(
    shown: Boolean,
    video: VideoItem,
    channel: LandChannel,
    badge: String?,
    hub: Hub,
    cc: Cc,
    speed: String?,
    scrubbing: Boolean,
    skippable: SegmentCategory?,
    times: @Composable () -> Unit,
    bar: @Composable () -> Unit,
    calls: LandCalls,
    clock: Clock,
) {
    AnimatedVisibility(shown, Modifier.matchParentSize(), enter = fadeIn(AynMotion.base()), exit = fadeOut(AynMotion.base())) {
        Box(Modifier.fillMaxSize().drawBehind { drawRect(LandScrimTop); drawRect(LandScrimFoot); drawRect(Shade.copy(alpha = 0.2f)) }) {
            Row(Modifier.fillMaxWidth().then(landSides()).padding(top = 14.dp), verticalAlignment = Alignment.Top) {
                PictureButton(R.drawable.ic_chevron_down, "Minimize", calls.onDock, Modifier.padding(end = 6.dp))
                LandHead(video, channel, Modifier.weight(1f).padding(top = 8.dp))
                Row(Modifier.padding(top = 18.dp, start = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    badge?.let { FormatBadge(it) }
                    if (LocalDeviceSettings.current.clockInPlayer) {
                        Text(rememberClockText(clock), style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp, fontFeatureSettings = "tnum").atWeight(580))
                    }
                }
            }
            val transportAlpha = if (scrubbing) 0f else 1f
            Row(
                Modifier.align(Alignment.Center).graphicsLayer { alpha = transportAlpha },
                horizontalArrangement = Arrangement.spacedBy(64.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SkipButton(R.drawable.ic_back10, "Back 10 seconds", size = 52.dp, iconSize = 30.dp) { calls.onSeekBy(false) }
                HubButton(hub, size = 72.dp, iconSize = 34.dp, onClick = calls.onHub)
                SkipButton(R.drawable.ic_fwd10, "Forward 10 seconds", size = 52.dp, iconSize = 30.dp) { calls.onSeekBy(true) }
            }
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().then(landSides()).padding(bottom = LandFoot)) {
                Box(Modifier.graphicsLayer { alpha = transportAlpha }) { times() }
                bar()
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SkipPill(skippable, calls.onSkip)
                    calls.onComments?.let { ChromePill(R.drawable.ic_comment, "Comments", it) }
                    calls.onChat?.let { ChromePill(R.drawable.ic_chat, "Chat", it, enabled = calls.chatOffered) }
                    CaptionsPill(cc, calls.onCaptions)
                    if (speed != null && calls.onSpeed != null) ChromePill(R.drawable.ic_speed, speed, calls.onSpeed, spoken = "Speed")
                    calls.onUpNext?.let { ChromePill(R.drawable.ic_list, "Up next", it) }
                    Spacer(Modifier.weight(1f))
                    PictureButton(R.drawable.ic_more_horiz, "Playback settings", calls.onPlayback, size = 36.dp, iconSize = 20.dp)
                    PictureButton(R.drawable.ic_fullscreen_exit, "Exit full screen", calls.onExit, size = 36.dp, iconSize = 20.dp)
                }
            }
        }
    }
}

private val LandScrimTop = Brush.verticalGradient(
    0f to Shade.copy(alpha = 0.82f),
    0.3f to Shade.copy(alpha = 0.35f),
    0.44f to Color.Transparent,
)
private val LandScrimFoot = Brush.verticalGradient(
    0.46f to Color.Transparent,
    0.7f to Shade.copy(alpha = 0.55f),
    1f to Shade.copy(alpha = 0.94f),
)

/** The channel's avatar, name and detail over the title (its DeArrow face), two lines at most. */
@Composable
private fun LandHead(video: VideoItem, channel: LandChannel, modifier: Modifier) {
    Column(modifier) {
        channel.name?.let { name ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(22.dp)) { ChannelAvatar(name, channel.avatarUrl, monogramSize = 9f) }
                Text(
                    buildAnnotatedString {
                        withStyle(weightSpan(620, AynColors.Ink)) { append(name) }
                        channel.detail?.let {
                            append("  ")
                            append(it)
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                    color = AynColors.Ink2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        val face = rememberCardFace(video)
        Text(
            face?.title ?: video.title,
            style = MaterialTheme.typography.headlineMedium.copy(fontSize = 30.sp, lineHeight = 0.96.em),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp).widthIn(max = 560.dp),
        )
    }
}

@Composable
private fun FormatBadge(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp).atWeight(650),
        modifier = Modifier
            .border(1.5.dp, Color.White.copy(alpha = 0.34f), RoundedCornerShape(6.dp))
            .padding(start = 7.dp, end = 7.dp, top = 3.dp, bottom = 4.dp),
    )
}

/** The wall clock at [clock] ("8:47 PM", the phone's 12/24-hour setting), turning over each minute. */
@Composable
private fun rememberClockText(clock: Clock): String {
    val format = DateFormat.getTimeFormat(LocalContext.current)
    return format.format(Date(rememberMinuteNow(clock).toEpochMilliseconds()))
}

/** [clock]'s now, turning over on each minute: what stays composed through a long stream reads
 *  its ages and the clock from it. */
@Composable
internal fun rememberMinuteNow(clock: Clock): Instant {
    val now by produceState(clock.now(), clock) {
        while (true) {
            delay(MINUTE_MS - clock.now().toEpochMilliseconds() % MINUTE_MS)
            value = clock.now()
        }
    }
    return now
}

private const val MINUTE_MS = 60_000L

/** "**Breaking Enigma - The Bombe**" at the left, "**34:51**  \u221212:51" at the right (`.lline`). */
@Composable
internal fun LandTimes(positionMs: Long, durationMs: Long, chapters: List<Chapter>) {
    val style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, fontFeatureSettings = "tnum")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            chapterAt(chapters, positionMs)?.title.orEmpty(),
            style = style.atWeight(650),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            buildAnnotatedString {
                withStyle(weightSpan(650, AynColors.Ink)) { append(timeText(positionMs)) }
                withStyle(SpanStyle(color = AynColors.Ink2)) { append("  " + remainingText(positionMs, durationMs)) }
            },
            style = style,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

private val PillFill = AynColors.Ink.copy(alpha = 0.14f)
private val PillHeight = 34.dp

/** A pill on the chrome's foot (`.pill`): an icon and a word, read out as [spoken] when the word is
 *  a value (the speed). It sinks while pressed, and dims while not [enabled]. */
@Composable
private fun ChromePill(
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
    spoken: String? = null,
    enabled: Boolean = true,
    state: String? = null,
    mark: @Composable BoxScope.() -> Unit = {},
) {
    val interaction = remember { MutableInteractionSource() }
    val dim by animateFloatAsState(if (enabled) 1f else 0.4f, AynMotion.base(), label = "pillDim")
    Row(
        Modifier
            .minimumInteractiveComponentSize()
            .height(PillHeight)
            .graphicsLayer { alpha = dim }
            .pressScale(interaction)
            .clip(CircleShape)
            .background(PillFill)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                spoken?.let { contentDescription = it }
                (state ?: label.takeIf { spoken != null })?.let { stateDescription = it }
            }
            .padding(start = 10.dp, end = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = null, tint = AynColors.Ink, modifier = Modifier.size(17.dp))
            mark()
        }
        Text(label, style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp).atWeight(620))
    }
}

/** Skip [skippable]'s span ([SkipReveal]), inert on the way out; its category's mark sits at the
 *  pill's foot, under the label. */
@Composable
private fun SkipPill(skippable: SegmentCategory?, onClick: () -> Unit) {
    SkipReveal(skippable) { category ->
        Box(contentAlignment = Alignment.Center) {
            ChromePill(R.drawable.ic_skip, skipControlText(category), onClick, enabled = skippable != null)
            Box(
                Modifier
                    // From the pill's centre to 3dp inside its bottom edge (`.pill.skipi .sbm`).
                    .offset(y = PillHeight / 2 - 3.dp)
                    .size(width = 18.dp, height = 2.dp)
                    .background(category.color, RoundedCornerShape(1.dp)),
            )
        }
    }
}

/** Captions: dimmed and inert while the video has no tracks; a short underline under its icon while
 *  captions show. */
@Composable
private fun CaptionsPill(cc: Cc, onClick: () -> Unit) {
    val lit by animateFloatAsState(if (cc == Cc.On) 1f else 0f, AynMotion.base(), label = "ccPillLit")
    ChromePill(
        R.drawable.ic_cc,
        "Captions",
        onClick,
        enabled = cc != Cc.None,
        state = when (cc) {
            Cc.On -> "On"
            Cc.Off -> "Off"
            Cc.None -> null
        },
    ) {
        Box(
            Modifier
                .offset(y = 11.dp)
                .size(width = 14.dp, height = 2.dp)
                .graphicsLayer {
                    alpha = lit
                    scaleX = lit
                }
                .background(AynColors.Ink, RoundedCornerShape(1.dp)),
        )
    }
}
