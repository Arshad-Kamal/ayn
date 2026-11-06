package com.arshad.ayn.ui.tv.player

import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.state.rememberProgressStateWithTickInterval
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.feature.format.timecode
import com.arshad.ayn.feature.format.viewsText
import com.arshad.ayn.feature.player.PlayerChromePolicy
import com.arshad.ayn.feature.player.PlayerErrorUiState
import com.arshad.ayn.feature.player.ShortsPosition
import com.arshad.ayn.feature.player.skippedLengthText
import com.arshad.ayn.ui.tv.R
import com.arshad.ayn.ui.tv.common.Kicker
import com.arshad.ayn.ui.tv.common.PillButton
import com.arshad.ayn.ui.tv.common.hairlineBorder
import com.arshad.ayn.ui.tv.focus.EnterKeys
import com.arshad.ayn.ui.tv.focus.FocusOnClose
import com.arshad.ayn.ui.tv.theme.KeColors
import com.arshad.ayn.ui.tv.theme.KeMotion
import com.arshad.ayn.ui.tv.theme.atWeight
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first

/** Where the playing Short is: asked for, playing, or stopped with a statement (Q49). */
internal sealed interface ShortPhase {
    /** Not yet playing; a D8 retry may already be under way. */
    data class Starting(val retry: PlayerErrorUiState) : ShortPhase

    data class Playing(val paused: Boolean, val buffering: Boolean, val retry: PlayerErrorUiState) : ShortPhase

    /** Try again shows only when [StopCopy.retryLabel] is set. */
    data class Stopped(val copy: StopCopy) : ShortPhase
}

/** The playing Short's play, pause and seeks; play and pause go through the AFR hold, steps
 *  gather into one seek ([com.arshad.ayn.feature.player.SeekGathering]: [stepBy] answers where it
 *  goes, [pending] where a gathered seek still waits to go), and [seekTo] lands at once. */
internal class ShortPlay(
    val toggle: () -> Unit,
    val pause: () -> Unit,
    val play: () -> Unit,
    val stepBy: (Long) -> Long,
    val pending: () -> Long?,
    val seekTo: (Long) -> Unit,
) {
    companion object {
        /** Before the controller connects: nothing to play. */
        val NONE = ShortPlay({}, {}, {}, { 0L }, { null }, {})
    }
}

/** What the Shorts player's keys and buttons do. */
internal class ShortActions(
    val onPrevious: () -> Unit,
    val onNext: () -> Unit,
    val play: ShortPlay,
    val onRetry: () -> Unit,
    val onOpenChannel: () -> Unit,
    val onToggleSubscribe: () -> Unit,
    val onSave: () -> Unit,
    val onOpenComments: () -> Unit,
)

/** A stop's statement as the Shorts player words it (Q49): about the Short, Up and Down named as
 *  the way on when it can be tried again, and a plain Try again (the copy names no place). */
internal fun StopCopy.forShort(): StopCopy =
    if (retryLabel == null) {
        copy(title = "This Short can’t play in Ayn")
    } else {
        copy(title = "This Short couldn’t play", body = "$body Up and Down move to another Short.", retryLabel = "Try again")
    }

// `b3-player` short (px ÷ 2): the picture at full height, the column 180 dp right of centre, its
// buttons wrapping inside 242 dp (`.sact.wrap`).
private val ColumnFromCentre = 180.dp
private val ColumnWidth = 280.dp
private val ButtonsWidth = 242.dp

/** How far the picture moves left while the comments panel shows (`.svid.moved`): to the middle
 *  of the free side. */
internal val ShortCommentsShift = 200.dp
private val ProgressHeight = 3.dp
private val PauseMark = 64.dp
private val ButtonGap = 6.dp
/** The Short's frame: the picture plays inside it, a wider one with bars above and below (PAF-D10). */
internal const val ShortAspect = 9f / 16f

/** The stage's picture window: the overlays (pause mark, progress) and the backdrop's clear cut-out. */
internal const val SHORT_PICTURE_TAG = "short-picture"
private const val DimmedPicture = 0.45f
private const val BufferingDim = 0.45f
private const val OffHint = 0.32f

/** `.sact.sdim`: the buttons stand back while scrub mode holds the keys. */
private const val ScrubbingButtons = 0.4f
private val ColumnFill = Color(0xFF020201)
private val PauseFill = Color(0xFF0E0C0A).copy(alpha = 0.62f)
private val AlertTint = Color.White.copy(alpha = 0.7f)
private val ProgressTrack = Color.White.copy(alpha = 0.25f)

// `short-scrub` (px ÷ 2): the bar 36 dp above the picture's foot, 20 dp in from its sides.
private val ScrubLift = 36.dp
private val ScrubInset = 20.dp
private val ScrubBarHeight = 6.dp
private val ScrubHead = 13.dp
private val ScrubHeadRingWidth = 3.dp
private val ScrubTimeGap = 17.dp
private val ScrubShade = 140.dp
private val ScrubShadeColor = Color(0xFF060504).copy(alpha = 0.7f)
private val ScrubHeadRing = Color.White.copy(alpha = 0.2f)
private val ScrubTimeFill = Color(0xFF0E0C0A).copy(alpha = 0.78f)
private val ButtonStyle = ChipStyle.copy(restContent = KeColors.Ink)

/**
 * The Shorts player (`b3-player` short, M7.8m states) over the player's surface: the picture in a
 * 9:16 frame at full height in the middle (its own blurred picture around it), the title and views (YouTube's,
 * Q40) on the right with Channel, Subscribe, Save, Seek and Comments under them (wrapping to a
 * second row; Left and Right walk them in that order) and the hints, dimmed where the run has no
 * Short to go to ([position], Q36). Up and Down move the run from anywhere and put focus back on
 * the Short; on the Short OK pauses, Right reaches the buttons and a fresh Left press enters scrub
 * mode a step back ([ShortScrub], SSC-D2; Seek enters it without a step); Left from the first
 * button or BACK returns to the Short (Q48). Starting shows the card's picture dimmed and Save alone (a search Short's
 * channel arrives with the resolve); a stop shows its statement in the column, Try again focused
 * when it can be tried (Q49). While [commentsOpen] the picture moves left by [ShortCommentsShift]
 * (the caller moves the surface with it), the column steps away and nothing here takes focus; as
 * the panel closes, focus returns to Comments. [saveModifier] goes on Save (else on where a stop
 * puts focus): focus returns there as the sheet closes. [player] is `null` before the controller
 * connects.
 */
@Composable
internal fun ShortStage(
    card: VideoItem,
    position: ShortsPosition,
    phase: ShortPhase,
    turn: Int,
    videoSize: VideoSize,
    avatarUrl: String?,
    isSubscribed: Boolean,
    menuOpen: Boolean,
    player: Player?,
    actions: ShortActions,
    saveModifier: Modifier = Modifier,
    commentsOpen: Boolean = false,
) {
    val short = remember { FocusRequester() }
    val firstButton = remember { FocusRequester() }
    val subscribeButton = remember { FocusRequester() }
    val saveButton = remember { FocusRequester() }
    val commentsButton = remember { FocusRequester() }
    val retry = remember { FocusRequester() }
    val seekButton = remember { FocusRequester() }
    val scrub = remember(turn) { ShortScrub() }
    var shortHeld by remember { mutableStateOf(false) }
    var buttonsHeld by remember { mutableStateOf(false) }
    val stopped = phase as? ShortPhase.Stopped
    val retryable = stopped?.copy?.retryLabel != null
    val playing = phase is ShortPhase.Playing
    val channelName = card.uploaderName?.takeIf { card.uploaderUrl != null && playing }
    val showsButtons = stopped == null && !commentsOpen
    val shift = if (commentsOpen) ShortCommentsShift else 0.dp
    // The picture's shape once it plays, never wider than the frame; the card's picture is 9:16.
    val aspect = videoSize.takeIf { phase is ShortPhase.Playing && it.width > 0 && it.height > 0 }
        ?.let { minOf(it.width * it.pixelWidthHeightRatio / it.height, ShortAspect) } ?: ShortAspect

    // A stop takes the buttons away, focus with them: it lands again on the stop's home.
    LaunchedEffect(turn, stopped != null, retryable) {
        withFrameNanos { } // focusability follows the recomposition
        if (retryable) retry.requestFocus() else short.requestFocus()
    }
    BackHandler(enabled = buttonsHeld && !menuOpen && !commentsOpen) { short.requestFocus() }
    BackHandler(enabled = scrub.active && !menuOpen) { scrub.end(actions.play) }
    FocusOnClose(commentsOpen, commentsButton)
    // Scrub mode ends with the Short's play (a stop, the next turn) and when it plays again from
    // elsewhere (the remote's Play reaching the session).
    val paused by rememberUpdatedState((phase as? ShortPhase.Playing)?.paused)
    LaunchedEffect(scrub, scrub.active) {
        if (!scrub.active) return@LaunchedEffect
        snapshotFlow { paused }.dropWhile { it != true }.first { it != true }
        scrub.stop()
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (scrub.active) return@onPreviewKeyEvent scrub.onKey(event, player, actions.play)
                when (event.key) {
                    // A fresh press only: a held Left walking back from the buttons stops on the Short.
                    Key.DirectionLeft -> if (shortHeld && playing && player != null && event.nativeKeyEvent.repeatCount == 0) {
                        scrub.start(actions.play)
                        scrub.step(player, actions.play, forward = false, event.nativeKeyEvent)
                    } else {
                        return@onPreviewKeyEvent false
                    }
                    Key.DirectionUp -> actions.onPrevious()
                    Key.DirectionDown -> actions.onNext()
                    Key.DirectionRight -> if (shortHeld && showsButtons) firstButton.requestFocus() else return@onPreviewKeyEvent false
                    in EnterKeys -> if (shortHeld && phase is ShortPhase.Playing) actions.play.toggle() else return@onPreviewKeyEvent false
                    else -> return@onPreviewKeyEvent false
                }
                true
            },
    ) {
        val pictureHeight = maxHeight
        val pictureWidth = minOf(maxWidth, maxHeight * aspect)
        ShortBackdrop(card.thumbnailUrl, pictureWidth, pictureHeight, shift)
        val shortModifier = if (stopped != null && !retryable) saveModifier else Modifier
        Box(
            shortModifier
                .fillMaxSize()
                .onFocusChanged { shortHeld = it.isFocused }
                .focusRequester(short)
                .focusProperties { canFocus = !commentsOpen }
                .focusable(),
        )
        // Moved at once: the surface under it can't be animated (a SurfaceView).
        Box(Modifier.align(Alignment.Center).offset(x = -shift).size(pictureWidth, pictureHeight).testTag(SHORT_PICTURE_TAG)) {
            ShortPicture(card, phase, player, scrub)
        }
        Box(Modifier.fillMaxHeight().fillMaxWidth(0.5f).align(Alignment.CenterEnd)) {
            AnimatedVisibility(
                visible = !commentsOpen,
                modifier = Modifier.align(Alignment.CenterStart),
                enter = fadeIn(KeMotion.base()),
                exit = fadeOut(KeMotion.fast()),
            ) {
                Column(Modifier.offset(x = ColumnFromCentre).width(ColumnWidth)) {
                    if (stopped != null) {
                        // Try again is its only focusable: the requester finds it there.
                        Box(if (retryable) saveModifier else Modifier) { StoppedCopy(stopped.copy, actions.onRetry, retry) }
                    } else {
                        ShortTitle(card)
                        val buttonsShown by animateFloatAsState(if (scrub.active) ScrubbingButtons else 1f, KeMotion.base(), label = "scrubButtons")
                        FlowRow(
                            modifier = Modifier
                                .graphicsLayer { alpha = buttonsShown }
                                .padding(top = 15.dp)
                                .widthIn(max = ButtonsWidth)
                                .onFocusChanged { buttonsHeld = it.hasFocus },
                            horizontalArrangement = Arrangement.spacedBy(ButtonGap),
                            verticalArrangement = Arrangement.spacedBy(ButtonGap),
                        ) {
                            // Left and Right walk the buttons in order across the wrap.
                            val first = Modifier.focusRequester(firstButton).focusProperties { left = short }
                            if (channelName != null) {
                                ChannelChip(
                                    name = channelName,
                                    avatarUrl = avatarUrl,
                                    canFocus = true,
                                    onClick = actions.onOpenChannel,
                                    modifier = first.focusProperties { right = subscribeButton },
                                    style = ButtonStyle,
                                )
                                SubscribeChip(
                                    isSubscribed = isSubscribed,
                                    canFocus = true,
                                    onClick = actions.onToggleSubscribe,
                                    modifier = Modifier.focusRequester(subscribeButton).focusProperties {
                                        left = firstButton
                                        right = saveButton
                                    },
                                    style = ButtonStyle,
                                )
                            }
                            IconChip(
                                icon = R.drawable.ic_list,
                                label = "Save",
                                canFocus = true,
                                onClick = actions.onSave,
                                modifier = saveModifier
                                    .focusRequester(saveButton)
                                    .then(if (channelName == null) first else Modifier.focusProperties { left = subscribeButton })
                                    .focusProperties { if (playing) right = seekButton },
                                style = ButtonStyle,
                            )
                            if (playing) {
                                IconChip(
                                    icon = R.drawable.ic_seek,
                                    label = "Seek",
                                    canFocus = true,
                                    onClick = {
                                        if (player != null) {
                                            scrub.start(actions.play)
                                            short.requestFocus()
                                        }
                                    },
                                    modifier = Modifier.focusRequester(seekButton).focusProperties {
                                        left = saveButton
                                        right = commentsButton
                                    },
                                    style = ButtonStyle,
                                )
                                IconChip(
                                    icon = R.drawable.ic_comment,
                                    label = "Comments",
                                    canFocus = true,
                                    onClick = actions.onOpenComments,
                                    modifier = Modifier.focusRequester(commentsButton).focusProperties { left = seekButton },
                                    style = ButtonStyle,
                                )
                            }
                        }
                    }
                    Crossfade(scrub.active, animationSpec = KeMotion.fast(), label = "hints") { scrubbing ->
                        if (scrubbing) {
                            ScrubHints()
                        } else {
                            Hints(position, okLabel = (phase as? ShortPhase.Playing)?.let { if (it.paused) "Play" else "Pause" })
                        }
                    }
                }
            }
        }
    }
}

/**
 * The Short's own picture blurred across the screen (`.sbg`: dark, its edges darker), with the
 * picture's shadow; [pictureWidth] × [pictureHeight] in the middle, moved left by [shift], is left
 * clear, for the surface under it. Drawn over the surface, so whatever an earlier item left there
 * never shows around it.
 */
@Composable
private fun ShortBackdrop(url: String?, pictureWidth: Dp, pictureHeight: Dp, shift: Dp) {
    val density = LocalDensity.current
    val clearWidth = with(density) { pictureWidth.toPx() }
    val shiftPx = with(density) { shift.toPx() }
    Box(
        Modifier
            .fillMaxSize()
            .drawWithContent {
                val left = (size.width - clearWidth) / 2 - shiftPx
                clipRect(left, 0f, left + clearWidth, size.height, ClipOp.Difference) { this@drawWithContent.drawContent() }
            },
    ) {
        Box(Modifier.fillMaxSize().background(ColumnFill))
        BlurredPicture(url, brightness = DimmedPicture, saturation = 1f)
        Box(Modifier.fillMaxSize().vignette())
        Box(
            Modifier
                .align(Alignment.Center)
                .offset(x = -shift)
                .size(pictureWidth, pictureHeight)
                .dropShadow(RectangleShape) {
                    radius = 60.dp.toPx()
                    color = Color.Black
                    alpha = 0.6f
                },
        )
    }
}

/** `radial-gradient(60% 80% at 50% 50%, transparent, …/.8)`: an ellipse with radii of 60 % of the
 *  width and 80 % of the height, clear in the middle, the screen's dark at its edge and beyond. */
private fun Modifier.vignette(): Modifier = drawWithCache {
    val radiusY = size.height * 0.8f
    val stretch = size.width * 0.6f / radiusY
    val middle = Offset(size.width / 2, size.height / 2)
    val brush = Brush.radialGradient(listOf(Color.Transparent, ColumnFill.copy(alpha = 0.8f)), middle, radiusY)
    onDrawBehind {
        scale(scaleX = stretch, scaleY = 1f, pivot = center) {
            drawRect(brush, topLeft = Offset(center.x - size.width / (2 * stretch), 0f), size = Size(size.width / stretch, size.height))
        }
    }
}

/** What covers the picture: the card's picture dimmed while starting or stopped (with Starting or
 *  the alert), else the live picture with its progress, the pause mark, and a dim + note while it
 *  buffers or retries. */
@Composable
private fun ShortPicture(card: VideoItem, phase: ShortPhase, player: Player?, scrub: ShortScrub) {
    Box(Modifier.fillMaxSize()) {
        when (phase) {
            is ShortPhase.Starting -> {
                DimmedCard(card)
                StartingNote(phase.retry, Modifier.align(Alignment.Center))
            }
            is ShortPhase.Stopped -> {
                DimmedCard(card)
                Icon(painterResource(R.drawable.ic_alert), contentDescription = null, tint = AlertTint, modifier = Modifier.align(Alignment.Center).size(44.dp))
            }
            is ShortPhase.Playing -> {
                val dim by animateFloatAsState(if (phase.buffering || phase.retry != PlayerErrorUiState.None) BufferingDim else 0f, KeMotion.base(), label = "dim")
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = dim }.background(Color.Black))
                when {
                    phase.retry != PlayerErrorUiState.None -> RetryPill(phase.retry, Modifier.align(Alignment.Center))
                    phase.buffering -> BufferingNote("Loading", Modifier.align(Alignment.Center), color = KeColors.Ink)
                }
                // Scrub mode shows the frame it holds, unmarked (`short-scrub`).
                PauseMark(phase.paused && !scrub.active, Modifier.align(Alignment.Center))
                player?.let { ShortProgress(it, scrub) }
            }
        }
    }
}

@Composable
private fun StartingNote(retry: PlayerErrorUiState, modifier: Modifier) {
    if (retry == PlayerErrorUiState.None) BufferingNote("Starting", modifier, color = KeColors.Ink) else RetryPill(retry, modifier)
}

/** The card's picture (YouTube's, Q40), dimmed: `.svid.dim` (brightness .45, saturation .8). */
@Composable
private fun DimmedCard(card: VideoItem) {
    val filter = remember { dimFilter(DimmedPicture, saturation = 0.8f) }
    Box(Modifier.fillMaxSize().background(KeColors.Surface2)) {
        AsyncImage(
            model = card.thumbnailUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            colorFilter = filter,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** Paused: the play mark over the picture, growing in and fading out (D8). */
@Composable
private fun PauseMark(paused: Boolean, modifier: Modifier) {
    val shown by animateFloatAsState(if (paused) 1f else 0f, KeMotion.fast(), label = "pause")
    Box(
        modifier
            .size(PauseMark)
            .graphicsLayer {
                alpha = shown
                val scale = 0.9f + 0.1f * shown
                scaleX = scale
                scaleY = scale
            }
            .background(PauseFill, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (shown > 0f) Icon(painterResource(R.drawable.ic_play), contentDescription = null, tint = KeColors.Ink, modifier = Modifier.size(32.dp))
    }
}

/**
 * The Short's progress along the picture's foot (`.sprog`), read at draw time. In [scrub] mode
 * (`short-scrub`) it rises into a bar inside the safe area with its head, the time above it and a
 * shade under it, showing where the steps went; transform and draw only.
 */
@OptIn(UnstableApi::class)
@Composable
private fun BoxScope.ShortProgress(player: Player, scrub: ShortScrub) {
    val scope = rememberCoroutineScope()
    val progress = rememberProgressStateWithTickInterval(player, PlayerChromePolicy.POSITION_TICK_MS, scope)
    val rise by animateFloatAsState(if (scrub.active) 1f else 0f, KeMotion.base(), label = "scrubRise")
    val shownMs = { scrub.targetMs ?: progress.currentPositionMs }
    Box(
        Modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth()
            .height(ScrubShade)
            .graphicsLayer { alpha = rise }
            .background(Brush.verticalGradient(listOf(Color.Transparent, ScrubShadeColor))),
    )
    Box(
        Modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth()
            .height(ScrubLift + ScrubHead)
            .drawBehind {
                val inset = ScrubInset.toPx() * rise
                val height = lerp(ProgressHeight.toPx(), ScrubBarHeight.toPx(), rise)
                val top = size.height - ScrubLift.toPx() * rise - height
                val width = size.width - 2 * inset
                val corner = CornerRadius(height / 2 * rise)
                drawRoundRect(ProgressTrack, Offset(inset, top), Size(width, height), corner)
                val duration = progress.durationMs
                if (duration > 0) {
                    val done = (shownMs().toFloat() / duration).coerceIn(0f, 1f)
                    drawRoundRect(KeColors.Ink, Offset(inset, top), Size(width * done, height), corner)
                    if (rise > 0f) {
                        val head = Offset(inset + width * done, top + height / 2)
                        drawCircle(ScrubHeadRing, (ScrubHead / 2 + ScrubHeadRingWidth).toPx() * rise, head)
                        drawCircle(KeColors.Ink, ScrubHead.toPx() / 2 * rise, head)
                    }
                }
            },
    )
    AnimatedVisibility(
        visible = scrub.active,
        modifier = Modifier.align(Alignment.BottomStart),
        enter = fadeIn(KeMotion.base()),
        exit = fadeOut(KeMotion.fast()),
    ) {
        ScrubTime(shownMs(), progress.durationMs)
    }
}

/** `.stime`: where the steps went over the Short's length, centred over the head, kept inside
 *  the bar's ends. */
@Composable
private fun ScrubTime(atMs: Long, durationMs: Long) {
    val fraction = if (durationMs > 0) (atMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Box(Modifier.fillMaxWidth().padding(horizontal = ScrubInset).padding(bottom = ScrubLift + ScrubBarHeight + ScrubTimeGap)) {
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = KeColors.Ink, fontWeight = FontWeight.Bold)) { append(timecode(atMs)) }
                append(" / ${timecode(durationMs.coerceAtLeast(0))}")
            },
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp),
            color = KeColors.Ink2,
            modifier = Modifier
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints.copy(minWidth = 0))
                    layout(constraints.maxWidth, placeable.height) {
                        val x = (constraints.maxWidth * fraction - placeable.width / 2f).toInt()
                        placeable.place(x.coerceIn(0, (constraints.maxWidth - placeable.width).coerceAtLeast(0)), 0)
                    }
                }
                .background(ScrubTimeFill, RoundedCornerShape(8.dp))
                .padding(horizontal = 9.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun ShortTitle(card: VideoItem) {
    Text(
        text = card.title,
        style = MaterialTheme.typography.headlineMedium.copy(fontSize = 26.sp),
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
    viewsText(card.viewCount)?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp), color = KeColors.Ink2, modifier = Modifier.padding(top = 7.dp))
    }
}

/** A stop in the column (`short-failed`, `short-refused`): what happened, the headline, one
 *  paragraph, and Try again when it can be tried (focused through [retryFocus]). */
@Composable
private fun StoppedCopy(copy: StopCopy, onRetry: () -> Unit, retryFocus: FocusRequester) {
    ShortKicker(copy.kicker)
    Text(copy.title, style = MaterialTheme.typography.headlineMedium.copy(fontSize = 26.sp), modifier = Modifier.padding(top = 9.dp))
    Text(
        text = copy.body,
        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 13.5.sp, lineHeight = 1.35.em),
        color = KeColors.Ink2,
        modifier = Modifier.padding(top = 9.dp),
    )
    copy.retryLabel?.let { label ->
        Box(Modifier.padding(top = 16.dp)) {
            PillButton(R.drawable.ic_retry, label, focusKey = RETRY_KEY, onClick = onRetry, modifier = Modifier.focusRequester(retryFocus))
        }
    }
}

private const val RETRY_KEY = "short-retry"

@Composable
private fun ShortKicker(kicker: Kicker) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(painterResource(kicker.icon), contentDescription = null, tint = KeColors.Ink2, modifier = Modifier.size(14.dp))
        Text(kicker.text, style = MaterialTheme.typography.labelMedium.atWeight(600).copy(fontSize = 12.sp), color = KeColors.Ink2)
    }
}

/** The keys (`.shint`): Previous and Next Short, dimmed where the run has none; Left's Seek and
 *  OK's [okLabel] while the Short plays. */
@Composable
private fun Hints(position: ShortsPosition, okLabel: String?) {
    Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Hint(listOf("↑"), "Previous", enabled = position.hasPrevious)
        Hint(listOf("↓"), "Next Short", enabled = position.hasNext)
        okLabel?.let {
            Hint(listOf("←"), "Seek", enabled = true)
            Hint(listOf("OK"), it, enabled = true)
        }
    }
}

/** Scrub mode's keys (`short-scrub`): Left and Right's step, OK plays from there. */
@Composable
private fun ScrubHints() {
    Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Hint(listOf("←", "→"), skippedLengthText(PlayerChromePolicy.SHORT_STEP_MS), enabled = true)
        Hint(listOf("OK"), "Play from here", enabled = true)
    }
}

@Composable
private fun Hint(keys: List<String>, label: String, enabled: Boolean) {
    val alpha by animateFloatAsState(if (enabled) 1f else OffHint, KeMotion.fast(), label = "hint")
    Row(
        modifier = Modifier.graphicsLayer { this.alpha = alpha },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            keys.forEach { key ->
                Box(
                    Modifier.height(17.dp).widthIn(min = 17.dp).hairlineBorder(Color.White.copy(alpha = 0.4f), 4.5.dp).padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(key, style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp))
                }
            }
        }
        Text(label, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp), color = KeColors.Ink2)
    }
}

/**
 * Scrub mode on the playing Short (SSC-D2): [active] while it holds the Short paused, [targetMs]
 * where its last step went, shown until it ends (a still gathering seek has its home in [ShortPlay.pending]). Steps gather into one seek a moment after the
 * last ([ShortPlay.stepBy]): a seek per step flushes the renderer and reseeks SABR each time
 * (PERF-D6 B1; on the emulator 2026-11-06 a step cost ~220 ms of input handling against 51 ms for
 * a key that does nothing), so a held key's picture lands when it is let go. A held key's repeats
 * step at most every [REPEAT_SLOW_MS] for its first [SPEED_UP_AFTER_MS], then every
 * [REPEAT_FAST_MS]; one turn's scrub per instance.
 */
@Stable
internal class ShortScrub {
    var active: Boolean by mutableStateOf(false)
        private set
    var targetMs: Long? by mutableStateOf(null)
        private set
    private var lastStepAt = 0L

    fun start(play: ShortPlay) {
        active = true
        play.pause()
    }

    /** Scrub mode's keys: Left and Right step, Up and Down do nothing, OK and Play/Pause play
     *  from there; `false` for a key it leaves alone. */
    fun onKey(event: KeyEvent, player: Player?, play: ShortPlay): Boolean {
        when (event.key) {
            Key.DirectionLeft, Key.DirectionRight -> player?.let { step(it, play, forward = event.key == Key.DirectionRight, event.nativeKeyEvent) }
            Key.DirectionUp, Key.DirectionDown -> Unit
            in EnterKeys, Key.MediaPlayPause, Key.MediaPlay -> end(play)
            else -> return false
        }
        return true
    }

    /** One step ([PlayerChromePolicy.SHORT_STEP_MS]) on from the last, for [key]; a repeat too soon
     *  after the last step is dropped. With no known length nothing steps. */
    fun step(player: Player, play: ShortPlay, forward: Boolean, key: android.view.KeyEvent) {
        if (key.repeatCount > 0) {
            val gap = if (key.eventTime - key.downTime < SPEED_UP_AFTER_MS) REPEAT_SLOW_MS else REPEAT_FAST_MS
            if (key.eventTime - lastStepAt < gap) return
        }
        lastStepAt = key.eventTime
        if (player.duration <= 0) return
        val step = PlayerChromePolicy.SHORT_STEP_MS
        targetMs = play.stepBy(if (forward) step else -step)
    }

    /** Plays from where the steps went, a still gathering seek landed first. */
    fun end(play: ShortPlay) {
        play.pending()?.let(play.seekTo)
        stop()
        play.play()
    }

    /** Leaves without playing: the Short already plays, or it stopped. */
    fun stop() {
        active = false
        targetMs = null
    }

    internal companion object {
        const val REPEAT_SLOW_MS = 150L
        const val REPEAT_FAST_MS = 60L
        const val SPEED_UP_AFTER_MS = 1_000L
    }
}
