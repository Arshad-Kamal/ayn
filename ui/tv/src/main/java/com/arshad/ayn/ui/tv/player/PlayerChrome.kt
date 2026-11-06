package com.arshad.ayn.ui.tv.player

import android.text.format.DateFormat
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.arshad.ayn.core.access.Chapter
import com.arshad.ayn.core.access.Segment
import com.arshad.ayn.core.access.SegmentCategory
import com.arshad.ayn.core.access.Storyboard
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.feature.format.MetaSeparator
import com.arshad.ayn.feature.format.ageText
import com.arshad.ayn.feature.format.liveWatchingText
import com.arshad.ayn.feature.format.metaLine
import com.arshad.ayn.feature.format.startedStreamingText
import com.arshad.ayn.feature.format.viewsText
import com.arshad.ayn.feature.player.BehindLiveLatch
import com.arshad.ayn.feature.player.PlayerChromePolicy
import com.arshad.ayn.feature.player.PlaylistPosition
import com.arshad.ayn.feature.player.chapterAt
import com.arshad.ayn.feature.player.liveWindowText
import com.arshad.ayn.feature.player.playlistPlaceText
import com.arshad.ayn.feature.player.remainingText
import com.arshad.ayn.feature.player.skipControlText
import com.arshad.ayn.feature.player.timeText
import com.arshad.ayn.ui.tv.R
import com.arshad.ayn.ui.tv.common.AvatarPicture
import com.arshad.ayn.ui.tv.common.LocalDeviceSettings
import com.arshad.ayn.ui.tv.common.hairlineBorder
import com.arshad.ayn.ui.tv.common.leadingRule
import com.arshad.ayn.ui.tv.common.rememberCardFace
import com.arshad.ayn.ui.tv.common.rememberFaceArrival
import com.arshad.ayn.ui.tv.common.rememberMinuteNow
import com.arshad.ayn.ui.tv.focus.ControlStyle
import com.arshad.ayn.ui.tv.focus.ControlSurface
import com.arshad.ayn.ui.tv.focus.TintedIcon
import com.arshad.ayn.ui.tv.focus.TintedText
import com.arshad.ayn.ui.tv.focus.contentTint
import com.arshad.ayn.ui.tv.theme.KeColors
import com.arshad.ayn.ui.tv.theme.KeLayout
import com.arshad.ayn.ui.tv.theme.KeMotion
import com.arshad.ayn.ui.tv.theme.atWeight
import com.arshad.ayn.ui.tv.theme.color
import com.arshad.ayn.ui.tv.theme.weightSpan
import java.util.Date
import kotlin.time.Clock
import kotlin.time.Instant

/** The player's dark (`b3-player` scrims: oklch 8% 0.006 65). */
internal val PlayerShade = Color(0xFF020201)

// `b3-player` chrome geometry (px ÷ 2): the bottom block sits 4 dp above the safe line.
private val BottomInset = KeLayout.SafeVertical + 4.dp
private val TitleWidth = 590.dp
private val ChannelAvatar = 20.dp
private val ChipHeight = 30.dp
private val ChipAvatar = 22.dp
private val ChipIcon = 13.dp
private val RoundControl = 34.dp
private val BigRoundControl = 46.dp

// `.ctl[data-on]::after` (px ÷ 2): the on mark under a control's icon.
private val OnMarkWidth = 10.dp
private val OnMarkHeight = 1.5.dp
private val OnMarkBottom = 3.5.dp

/** A control or row with nothing to offer on this video (`.ctl.gap`, `.srow.gap`). */
internal const val GapAlpha = 0.42f

internal val ChipStyle = ControlStyle(restFill = Color.White.copy(alpha = 0.1f), restContent = KeColors.Ink2, focusedScale = 1.06f, ring = false)
private val RoundStyle = ControlStyle(restFill = KeColors.Ink.copy(alpha = 0f), restContent = KeColors.Ink2, focusedScale = 1.1f, ring = false)
private val BigRoundStyle = ControlStyle(restFill = Color.White.copy(alpha = 0.14f), restContent = KeColors.Ink, focusedScale = 1.1f, ring = false)

// `.cgroup.right > .f`: the right group grows away from the safe line.
private val EndRoundStyle = RoundStyle.copy(growFrom = TransformOrigin(1f, 0.5f))

/** The chrome's darkening, top and bottom, at [shown] (0 hidden, 1 shown); deeper and higher at
 *  the bottom over the Related [peek] (`b3-player-panels` `.peek .pscrim`). */
internal fun Modifier.chromeScrims(shown: State<Float>, peek: Boolean): Modifier = drawWithCache {
    val top = Brush.verticalGradient(
        0f to PlayerShade.copy(alpha = 0.78f),
        0.16f to PlayerShade.copy(alpha = 0.35f),
        0.3f to PlayerShade.copy(alpha = 0f),
    )
    val bottom = if (peek) {
        Brush.verticalGradient(
            0.38f to PlayerShade.copy(alpha = 0f),
            0.58f to PlayerShade.copy(alpha = 0.55f),
            0.76f to PlayerShade.copy(alpha = 0.9f),
            1f to PlayerShade.copy(alpha = 0.97f),
        )
    } else {
        Brush.verticalGradient(
            0.44f to PlayerShade.copy(alpha = 0f),
            0.66f to PlayerShade.copy(alpha = 0.5f),
            0.82f to PlayerShade.copy(alpha = 0.86f),
            1f to PlayerShade.copy(alpha = 0.96f),
        )
    }
    onDrawBehind {
        val alpha = shown.value
        if (alpha > 0f) {
            drawRect(top, alpha = alpha)
            drawRect(bottom, alpha = alpha)
        }
    }
}

/**
 * The chrome's top: in Play all the playlist and the place in it, then the channel line (avatar
 * once the resolve brings it, views · age, or a [live] stream's audience and how long it has run,
 * [liveStartedAt], both measured on [clock]) and the title (the card's face, faded in after a wait
 * for DeArrow); the format badge and the clock (unless the TV's Clock in the player is off) at the
 * right. The opening state shows the left half alone ([status] false).
 */
@Composable
internal fun ChromeTop(
    card: VideoItem,
    playlist: PlaylistPosition?,
    avatarUrl: String?,
    badge: String?,
    modifier: Modifier = Modifier,
    status: Boolean = true,
    live: Boolean = card.isLive,
    liveStartedAt: Instant? = null,
    clock: Clock = Clock.System,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = KeLayout.SafeHorizontal, end = KeLayout.SafeHorizontal, top = KeLayout.SafeVertical),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            playlist?.let { PlaylistLine(it) }
            ChannelLine(card, avatarUrl, live, liveStartedAt, clock)
            val face = rememberCardFace(card)
            val arrival = rememberFaceArrival(face != null)
            Text(
                text = face?.title.orEmpty(),
                style = MaterialTheme.typography.headlineMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp).widthIn(max = TitleWidth).graphicsLayer { alpha = arrival.value },
            )
        }
        if (status) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                badge?.let { FormatBadge(it) }
                if (LocalDeviceSettings.current.clockInPlayer) {
                    Text(
                        rememberClockText(clock),
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp, fontFeatureSettings = "tnum"),
                        modifier = Modifier.testTag(CLOCK_TAG),
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaylistLine(position: PlaylistPosition) {
    Row(
        modifier = Modifier.padding(bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(painterResource(R.drawable.ic_list), contentDescription = null, tint = KeColors.Ink2, modifier = Modifier.size(14.dp))
        Text(position.title, style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp), color = KeColors.Ink2, maxLines = 1)
        Text(
            text = playlistPlaceText(position),
            style = MaterialTheme.typography.titleLarge.atWeight(700).copy(fontSize = 12.sp, fontFeatureSettings = "tnum"),
            modifier = Modifier.leadingRule(6.dp),
        )
    }
}

/** The channel's avatar (once the resolve brings it) and name, then views · age; for a [live]
 *  stream how many are watching, which only a card that says live counts (another card's count is
 *  its total views), and how long it has run once the resolve says ([liveStartedAt], LIVE-D24). Ages
 *  follow [clock] minute by minute: the chrome stays composed through a long stream. */
@Composable
internal fun ChannelLine(
    card: VideoItem,
    avatarUrl: String?,
    live: Boolean = card.isLive,
    liveStartedAt: Instant? = null,
    clock: Clock = Clock.System,
) {
    val name = card.uploaderName ?: return
    val now = rememberMinuteNow(clock)
    val meta = if (live) {
        metaLine(card.liveWatchingText(), liveStartedAt?.let { startedStreamingText(it, now) })
    } else {
        metaLine(viewsText(card.viewCount), card.ageText(now))
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(Modifier.size(ChannelAvatar)) { AvatarPicture(name, avatarUrl, 9.sp) }
        Text(
            text = buildAnnotatedString {
                withStyle(weightSpan(600, KeColors.Ink)) { append(name) }
                if (meta.isNotEmpty()) {
                    append("  ")
                    append(meta)
                }
            },
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
            color = KeColors.Ink2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun FormatBadge(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier
            .hairlineBorder(Color.White.copy(alpha = 0.32f), 4.dp)
            .padding(start = 7.dp, end = 7.dp, top = 3.dp, bottom = 3.5.dp),
    )
}

/** The wall clock at [clock] ("8:47 PM", the device's 12/24-hour setting), turning over each minute. */
@Composable
private fun rememberClockText(clock: Clock): String {
    val context = LocalContext.current
    val now = rememberMinuteNow(clock)
    return DateFormat.getTimeFormat(context).format(Date(now.toEpochMilliseconds()))
}

internal const val CLOCK_TAG = "player-clock"

/** What the chrome's controls do; the screen routes play and pause through the AFR hold. No
 *  [onToggleSubscribe] and no [onOpenComments] in a kids profile, which has no Subscribe (KP-D11)
 *  and no Comments (KP-D11 turns comments off, so the panel would only say they're off); no
 *  [onOpenComments] on a live stream either, which has none; [onOpenChat] on a live stream or an
 *  ended stream whose chat YouTube kept (LIVE-D28), right after Comments there.
 *  [onSeekBy] answers where the gathered seek goes. */
internal class ChromeActions(
    val onPlayPause: () -> Unit,
    val onReplay: () -> Unit,
    val onSeekBy: (Long) -> Long,
    val onSeekTo: (Long) -> Unit,
    val onGoLive: () -> Unit,
    val onToggleSubscribe: (() -> Unit)?,
    val onOpenChannel: () -> Unit,
    val onOpenCaptions: () -> Unit,
    val onOpenSpeed: () -> Unit,
    val onOpenQuality: () -> Unit,
    val onOpenPlayback: () -> Unit,
    val onOpenComments: (() -> Unit)?,
    val onOpenChat: (() -> Unit)? = null,
)

/**
 * The chrome's focus targets: [primary] (play/pause, or Replay once ended), the bar, and the
 * controls that open panels, with the one a panel opened from ([opener]).
 */
internal class ChromeFocus(val primary: FocusRequester = FocusRequester(), val scrub: FocusRequester = FocusRequester()) {
    val comments: FocusRequester = FocusRequester()
    val chat: FocusRequester = FocusRequester()
    val captions: FocusRequester = FocusRequester()
    val speed: FocusRequester = FocusRequester()
    val quality: FocusRequester = FocusRequester()
    val more: FocusRequester = FocusRequester()

    /** The controls row holds focus (DOWN from there opens the Related row). Written by focus
     *  changes, read by key handling: no composition reads it. */
    var controlsHeld: Boolean = false

    /** The control the open panel came from, set as it opens and taken as the chrome comes back
     *  on it; non-null after a close means a panel just closed. Written by clicks, read by
     *  effects: no composition reads it. */
    var opener: FocusRequester? = null

    /** Where focus lands as the chrome appears: the panel's opener once, else [primary]. */
    fun takeLanding(): FocusRequester = (opener ?: primary).also { opener = null }
}

/**
 * The chrome's bottom: the scrub bar (with SponsorBlock's [segments]), the times (elapsed ·
 * chapter, remaining), and the controls:
 * the channel and Subscribe at the left, −10 s, play/pause (Replay once [ended]) and +10 s in the
 * middle, Comments (CMT Q1), CC (the Captions sheet), Speed, HD (the Quality sheet) and ⋯ (the
 * Playback sheet) at the right, at
 * [controlsAlpha] (they step away while the Related row is open). CC is dimmed and focus passes
 * over it while the video has no caption tracks ([captionsAvailable], Q53), and it carries a mark
 * while captions show ([captionsOn]). Nothing in it takes focus while it is hidden or the row is
 * open ([canFocus]).
 * A [live] stream (`live-tv-player` live-edge, live-behind; LIVE-D23) has the LIVE tag and the
 * window's length where the times are; behind live the tag goes to an outline with how far behind,
 * and Go live leads the right group; when it leaves under focus (a click, or the service rejoining
 * the edge by itself), focus moves to Pause. Forward stays (at the edge a press rejoins it, Q20).
 * There is no Speed; Chat (`live-tv-player` live-chat-button) sits where Comments does, dimmed and
 * passed over while the stream has no chat for this profile ([chatAvailable]). An ended stream
 * whose chat YouTube kept has Chat right after Comments (LIVE-D28).
 * While playback is in a span the skip prompt offers ([skippable], its first category), Skip leads
 * the right group with that category's mark (PAF-D12); OK runs [onSkip].
 */
@Composable
internal fun ChromeBottom(
    card: VideoItem,
    avatarUrl: String?,
    chapters: List<Chapter>,
    segments: List<Segment>,
    storyboard: Storyboard?,
    times: ScrubTimes,
    scrub: ScrubState,
    paused: Boolean,
    ended: Boolean,
    isSubscribed: Boolean,
    captionsAvailable: Boolean,
    captionsOn: Boolean,
    canFocus: Boolean,
    focus: ChromeFocus,
    actions: ChromeActions,
    modifier: Modifier = Modifier,
    controlsAlpha: () -> Float = { 1f },
    live: Boolean = false,
    chatAvailable: Boolean = true,
    skippable: SegmentCategory? = null,
    onSkip: () -> Unit = {},
) {
    // Read on every tick while shown; the controls change only as these flip. Once shown, behind
    // holds down to the lower bound (the hysteresis in isBehindLive), across the chrome's hiding.
    val latch = remember(live) { BehindLiveLatch() }
    val behind by remember(times, latch) {
        derivedStateOf { live && latch.behind(times.positionMs(), times.durationMs()) }
    }
    val currentCanFocus by rememberUpdatedState(canFocus)
    // A control that leaves the right group (Go live at the edge, Skip past its span) takes focus with
    // it: once the recomposition has taken it out, and after the chrome's own landing (a frame after it
    // shows, so a landing on a panel's opener stands), focus that went with it comes to Pause.
    suspend fun refocusIfLost() {
        withFrameNanos { }
        withFrameNanos { }
        if (currentCanFocus && !scrub.focused && !focus.controlsHeld) focus.primary.requestFocus()
    }
    LaunchedEffect(behind) { if (!behind) refocusIfLost() }
    LaunchedEffect(skippable == null) { if (skippable == null) refocusIfLost() }
    Column(modifier.fillMaxWidth().padding(start = KeLayout.SafeHorizontal, end = KeLayout.SafeHorizontal, bottom = BottomInset)) {
        ScrubBar(
            times = times,
            chapters = chapters,
            segments = segments,
            storyboard = storyboard,
            scrub = scrub,
            focusRequester = focus.scrub,
            canFocus = canFocus,
            onSeekTo = actions.onSeekTo,
            live = live,
        )
        if (live) {
            LiveTimesRow(times, behind, Modifier.padding(top = 4.dp))
        } else {
            TimesRow(times, chapters, hideElapsed = scrub.focused, modifier = Modifier.padding(top = 4.dp))
        }
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 13.dp)
                .graphicsLayer { alpha = controlsAlpha() }
                .onFocusChanged { focus.controlsHeld = it.hasFocus },
        ) {
            val name = card.uploaderName
            if (card.uploaderUrl != null && name != null) {
                Row(Modifier.align(Alignment.CenterStart), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    ChannelChip(name, avatarUrl, canFocus, actions.onOpenChannel)
                    actions.onToggleSubscribe?.let { SubscribeChip(isSubscribed, canFocus, it) }
                }
            }
            Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                SkipControl(R.drawable.ic_back10, "Back 10 seconds", canFocus) { actions.onSeekBy(-PlayerChromePolicy.SEEK_STEP_MS) }
                RoundControl(
                    icon = when {
                        ended -> R.drawable.ic_retry
                        paused -> R.drawable.ic_play
                        else -> R.drawable.ic_pause
                    },
                    label = when {
                        ended -> "Replay"
                        paused -> "Play"
                        else -> "Pause"
                    },
                    size = BigRoundControl,
                    iconSize = 22.dp,
                    style = BigRoundStyle,
                    canFocus = canFocus,
                    onClick = if (ended) actions.onReplay else actions.onPlayPause,
                    modifier = Modifier.focusRequester(focus.primary),
                )
                SkipControl(R.drawable.ic_fwd10, "Forward 10 seconds", canFocus) { actions.onSeekBy(PlayerChromePolicy.SEEK_STEP_MS) }
            }
            Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                if (live) {
                    // The group grows leftward from the safe line: nothing beside Go live moves.
                    AnimatedVisibility(
                        visible = behind,
                        enter = fadeIn(KeMotion.base()) + scaleIn(KeMotion.base(), initialScale = 0.9f),
                        exit = fadeOut(KeMotion.fast()) + scaleOut(KeMotion.fast(), targetScale = 0.9f),
                    ) {
                        GoLiveChip(canFocus && behind) {
                            actions.onGoLive()
                            focus.primary.requestFocus()
                        }
                    }
                }
                // The group grows leftward from the safe line: nothing right of Skip moves.
                AnimatedVisibility(
                    visible = skippable != null,
                    enter = fadeIn(KeMotion.base()) + scaleIn(KeMotion.base(), initialScale = 0.9f),
                    exit = fadeOut(KeMotion.fast()) + scaleOut(KeMotion.fast(), targetScale = 0.9f),
                ) {
                    // Held through the exit, so the mark keeps its colour while it fades.
                    val category = remember { mutableStateOf(skippable) }
                    skippable?.let { category.value = it }
                    RoundControl(
                        icon = R.drawable.ic_skip,
                        label = category.value?.let(::skipControlText).orEmpty(),
                        size = RoundControl,
                        iconSize = 19.dp,
                        style = EndRoundStyle,
                        canFocus = canFocus && skippable != null,
                        onClick = onSkip,
                    ) { category.value?.let { CategoryMark(it) } }
                }
                val captionsAlpha = animateFloatAsState(if (captionsAvailable) 1f else GapAlpha, KeMotion.base(), label = "captionsGap")
                actions.onOpenComments?.let { PanelControl(R.drawable.ic_comment, "Comments", focus.comments, focus, canFocus, it) }
                actions.onOpenChat?.let {
                    val chatAlpha = animateFloatAsState(if (chatAvailable) 1f else GapAlpha, KeMotion.base(), label = "chatGap")
                    PanelControl(
                        icon = R.drawable.ic_chat,
                        label = "Chat",
                        requester = focus.chat,
                        focus = focus,
                        canFocus = canFocus && chatAvailable,
                        onOpen = it,
                        modifier = Modifier.graphicsLayer { alpha = chatAlpha.value },
                    )
                }
                PanelControl(
                    icon = R.drawable.ic_cc,
                    label = "Captions",
                    requester = focus.captions,
                    focus = focus,
                    canFocus = canFocus && captionsAvailable,
                    onOpen = actions.onOpenCaptions,
                    modifier = Modifier.graphicsLayer { alpha = captionsAlpha.value },
                ) { OnMark(captionsOn) }
                if (!live) PanelControl(R.drawable.ic_speed, "Speed", focus.speed, focus, canFocus, actions.onOpenSpeed)
                PanelControl(R.drawable.ic_hq, "Quality", focus.quality, focus, canFocus, actions.onOpenQuality)
                PanelControl(R.drawable.ic_more, "More", focus.more, focus, canFocus, actions.onOpenPlayback)
            }
        }
    }
}

/** A control that opens a panel ([onOpen]); focus comes back to it ([requester]) as the panel closes. */
@Composable
private fun PanelControl(
    @DrawableRes icon: Int,
    label: String,
    requester: FocusRequester,
    focus: ChromeFocus,
    canFocus: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    overlay: @Composable () -> Unit = {},
) {
    RoundControl(
        icon = icon,
        label = label,
        size = RoundControl,
        iconSize = 19.dp,
        style = EndRoundStyle,
        canFocus = canFocus,
        onClick = {
            focus.opener = requester
            onOpen()
        },
        modifier = modifier.focusRequester(requester),
        overlay = overlay,
    )
}

/** The short bar under the skip control's icon in [category]'s colour (`b3-player-panels` sbicon-right). */
@Composable
private fun CategoryMark(category: SegmentCategory) {
    UnderMark(color = { category.color }, alpha = { 1f })
}

/** The short bar under a control's icon while what it controls is [on], in the control's ink. */
@Composable
private fun OnMark(on: Boolean) {
    val shown = animateFloatAsState(if (on) 1f else 0f, KeMotion.fast(), label = "onMark")
    val ink = contentTint()
    UnderMark(color = ink, alpha = { shown.value })
}

/** A control's under-icon bar (`.ctl[data-on]::after`), drawn in [color] at [alpha]. */
@Composable
private fun UnderMark(color: ColorProducer, alpha: () -> Float) {
    Box(
        Modifier.fillMaxSize().drawBehind {
            val width = OnMarkWidth.toPx()
            val height = OnMarkHeight.toPx()
            drawRoundRect(
                color = color(),
                topLeft = Offset((size.width - width) / 2, size.height - OnMarkBottom.toPx() - height),
                size = Size(width, height),
                cornerRadius = CornerRadius(height / 2),
                alpha = alpha(),
            )
        },
    )
}


/** "34:51 · Breaking Enigma" and "−12:51"; the left half gives way to the scrub preview. */
@Composable
internal fun TimesRow(times: ScrubTimes, chapters: List<Chapter>, hideElapsed: Boolean, modifier: Modifier = Modifier) {
    val elapsedAlpha by animateFloatAsState(if (hideElapsed) 0f else 1f, KeMotion.base(), label = "elapsed")
    val position = times.positionMs()
    val style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, fontFeatureSettings = "tnum")
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            text = buildAnnotatedString {
                withStyle(weightSpan(640, KeColors.Ink)) { append(timeText(position)) }
                chapterAt(chapters, position)?.let {
                    withStyle(SpanStyle(color = KeColors.Ink3)) { append(MetaSeparator) }
                    append(it.title)
                }
            },
            style = style,
            color = KeColors.Ink2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).graphicsLayer { alpha = elapsedAlpha },
        )
        Text(remainingText(position, times.durationMs()), style = style, color = KeColors.Ink2)
    }
}

/** A live stream's times row (`.ltimes`): the LIVE tag, how far [behind] live the picture is while
 *  it is, and the window's length at the right. */
@Composable
internal fun LiveTimesRow(times: ScrubTimes, behind: Boolean, modifier: Modifier = Modifier) {
    val style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, fontFeatureSettings = "tnum")
    val duration = times.durationMs()
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        LiveTag(lit = !behind)
        AnimatedVisibility(behind, enter = fadeIn(KeMotion.base()), exit = fadeOut(KeMotion.fast())) {
            Text(
                text = buildAnnotatedString {
                    withStyle(weightSpan(640, KeColors.Ink)) { append(timeText(PlayerChromePolicy.liveBehindMs(times.positionMs(), duration))) }
                    append(" behind live")
                },
                style = style,
                color = KeColors.Ink2,
                modifier = Modifier.padding(start = 9.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        liveWindowText(duration)?.let { Text(it, style = style, color = KeColors.Ink3) }
    }
}

/** Go live (`.chip.golive`): the red live mark and the words. */
@Composable
private fun GoLiveChip(canFocus: Boolean, onClick: () -> Unit) {
    Chip(onClick = onClick, canFocus = canFocus, start = 9.dp, style = ChipStyle, modifier = Modifier) {
        Icon(painterResource(R.drawable.ic_live), contentDescription = null, tint = KeColors.Live, modifier = Modifier.size(ChipIcon))
        TintedText("Go live", style = MaterialTheme.typography.titleSmall.copy(fontSize = 12.sp))
    }
}

/** The channel's chip (`.chan-chip`): its avatar once known, and its name. */
@Composable
internal fun ChannelChip(
    name: String,
    avatarUrl: String?,
    canFocus: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ControlStyle = ChipStyle,
) {
    Chip(onClick = onClick, canFocus = canFocus, start = 4.dp, style = style, modifier = modifier) {
        Box(Modifier.size(ChipAvatar)) { AvatarPicture(name, avatarUrl, 10.sp) }
        TintedText(name, style = MaterialTheme.typography.titleSmall.copy(fontSize = 12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Subscribe, or Subscribed with its check, crossfading as it flips. */
@Composable
internal fun SubscribeChip(
    isSubscribed: Boolean,
    canFocus: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ControlStyle = ChipStyle,
) {
    Chip(onClick = onClick, canFocus = canFocus, start = 9.dp, style = style, modifier = modifier) {
        Crossfade(isSubscribed, animationSpec = KeMotion.fast(), label = "subscribed") { on ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TintedIcon(painterResource(if (on) R.drawable.ic_check else R.drawable.ic_plus), contentDescription = null, modifier = Modifier.size(ChipIcon))
                TintedText(if (on) "Subscribed" else "Subscribe", style = MaterialTheme.typography.titleSmall.copy(fontSize = 12.sp))
            }
        }
    }
}

/** An icon and [label] (the Shorts player's Save). */
@Composable
internal fun IconChip(
    @DrawableRes icon: Int,
    label: String,
    canFocus: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ControlStyle = ChipStyle,
) {
    Chip(onClick = onClick, canFocus = canFocus, start = 9.dp, style = style, modifier = modifier) {
        TintedIcon(painterResource(icon), contentDescription = null, modifier = Modifier.size(ChipIcon))
        TintedText(label, style = MaterialTheme.typography.titleSmall.copy(fontSize = 12.sp))
    }
}


@Composable
private fun Chip(
    onClick: () -> Unit,
    canFocus: Boolean,
    start: Dp,
    style: ControlStyle,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    ControlSurface(
        onClick = onClick,
        style = style,
        modifier = modifier.height(ChipHeight).focusProperties { this.canFocus = canFocus },
    ) {
        Row(
            modifier = Modifier.fillMaxHeight().padding(start = start, end = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) { content() }
    }
}

@Composable
private fun RoundControl(
    @DrawableRes icon: Int,
    label: String,
    size: Dp,
    iconSize: Dp,
    style: ControlStyle,
    canFocus: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    overlay: @Composable () -> Unit = {},
) {
    ControlSurface(
        onClick = onClick,
        style = style,
        modifier = modifier
            .size(size)
            .focusProperties { this.canFocus = canFocus }
            .semantics { contentDescription = label },
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            TintedIcon(painterResource(icon), contentDescription = null, modifier = Modifier.size(iconSize))
            overlay()
        }
    }
}

/** −10 s / +10 s: the arc with its "10" (the master's SVG text). */
@Composable
private fun SkipControl(@DrawableRes icon: Int, label: String, canFocus: Boolean, onClick: () -> Unit) {
    RoundControl(icon, label, RoundControl, 19.dp, RoundStyle, canFocus, onClick) {
        TintedText(
            text = "10",
            style = MaterialTheme.typography.labelSmall.atWeight(700).copy(fontSize = 5.5.sp),
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
