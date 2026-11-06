package com.arshad.ayn.ui.mobile.player

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.modifiers.resizeWithContentScale
import androidx.media3.ui.compose.state.rememberPresentationState
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.store.RestrictedMode
import com.arshad.ayn.feature.format.viewsText
import com.arshad.ayn.feature.nav.ChannelRoute
import com.arshad.ayn.feature.player.PlayerChromePolicy
import com.arshad.ayn.feature.player.PlayerErrorUiState
import com.arshad.ayn.feature.player.PlayerPolicy
import com.arshad.ayn.feature.player.PlayerUiState
import com.arshad.ayn.feature.player.PlayerViewModel
import com.arshad.ayn.feature.player.SeekGathering
import com.arshad.ayn.feature.player.ShortsPosition
import com.arshad.ayn.feature.player.awaitStarted
import com.arshad.ayn.feature.player.pauseWhileAway
import com.arshad.ayn.feature.player.requestTurn
import com.arshad.ayn.feature.player.retryFrom
import com.arshad.ayn.feature.player.retryLine
import com.arshad.ayn.feature.player.stopFrom
import com.arshad.ayn.ui.mobile.R
import com.arshad.ayn.ui.mobile.common.ButtonKind
import com.arshad.ayn.ui.mobile.common.ChannelAvatar
import com.arshad.ayn.ui.mobile.common.LocalContentFilter
import com.arshad.ayn.ui.mobile.common.PillButton
import com.arshad.ayn.ui.mobile.menu.VideoMenuHost
import com.arshad.ayn.ui.mobile.shell.ChannelKey
import com.arshad.ayn.ui.mobile.shell.ShellChrome
import com.arshad.ayn.ui.mobile.theme.AynColors
import com.arshad.ayn.ui.mobile.theme.AynMotion
import com.arshad.ayn.ui.mobile.theme.atWeight
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val SWIPE_MS = 250
private const val SWIPE_AT = 0.2f
private const val SWIPE_FLING = 1_000f

/** How far an edge lets the page follow the finger: past the run's end it only gives a little. */
private const val EDGE_PULL = 0.3f
private const val END_LINE_MS = 1_600L
private const val SHRINK_MS = 200

/** A run with another Short to swipe to. */
private fun ShortsPosition?.canMove(): Boolean = this != null && (hasNext || hasPrevious)

/** The share of the screen the comments panel takes; the Short shrinks into the rest (`m6-shorts` 5). */
private const val COMMENTS_SHARE = 0.62f
private val PauseMarkSize = 64.dp

private const val FOOT_FADE_MS = 150

/** Where the playing Short is (`m6-shorts`): asked for, playing, or stopped with its statement. */
private sealed interface ShortPhase {
    data object Starting : ShortPhase

    data class Playing(val paused: Boolean, val buffering: Boolean) : ShortPhase

    /** [onRetry] only when trying again can help. */
    data class Stopped(val copy: StopCopy, val onRetry: (() -> Unit)?) : ShortPhase
}

/** What the Shorts player does beyond playing: move through the run, the Short beside this one,
 *  Save, its channel, and close. */
private class ShortCalls(
    val previous: () -> Unit,
    val next: () -> Unit,
    val beside: (next: Boolean) -> VideoItem?,
    val save: () -> Unit,
    val openChannel: (String) -> Unit,
    val close: () -> Unit,
)

/** The Short shown beside the page while a swipe moves it: the run's next ([next]) or previous one. */
private data class Beside(val video: VideoItem, val next: Boolean)

/**
 * The Shorts player (`m6-shorts` 1–13): a Short full screen over the shell, upright whatever
 * the phone does (Q45), running through the list it was opened from. A tap pauses; a double-tap on
 * a side steps (SSC-D4) and a sideways drag across it scrubs (SSC-D3); a swipe up or down moves
 * the run, the page following the finger; Save opens Save to playlist alone; Comments opens the
 * comments panel with the Short shrunk above it (Q46). Leaving Ayn pauses it (Q44).
 * Back or ← closes it (no mini player); its channel closes it, then opens the channel page.
 */
@Composable
internal fun ShortsPage(open: OpenPlayer, chrome: ShellChrome, onClose: () -> Unit) {
    val viewModel = open.viewModel
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val menuOpen = open.menu.menu.collectAsStateWithLifecycle().value != null
    val calls = remember(viewModel, open.menu, chrome, onClose) {
        ShortCalls(
            previous = viewModel::previousShort,
            next = viewModel::nextShort,
            beside = viewModel::shortBeside,
            save = { open.menu.openSaveTo(viewModel.uiState.value.video, short = true) },
            openChannel = { url ->
                onClose()
                chrome.nav.open(ChannelKey(ChannelRoute(url)))
            },
            close = onClose,
        )
    }
    // Its Save to sheet has no Play row.
    VideoMenuHost(open.menu, onPlay = { _, _ -> }, onOpenChannel = null) { _ ->
        val connection = open.connection
        val controller = connection.controller
        val stage = ShortStageArgs(state, menuOpen, calls)
        when {
            connection.isDown -> ShortStage(stage, ShortPhase.Stopped(PlayerDownCopy.forShort(state.shorts.canMove()), open.reconnect))
            controller == null -> ShortStage(stage, ShortPhase.Starting)
            else -> ShortPlayback(controller, connection.sessionExtras, viewModel, stage, kids = chrome.profile.isKids)
        }
    }
}

/** What every phase of the stage shows: the Short and its run, whether a menu sheet is up, the calls. */
private class ShortStageArgs(val state: PlayerUiState, val menuOpen: Boolean, val calls: ShortCalls)

/**
 * The Short playing, asked for and paused while away by the rules both players share
 * ([requestTurn], [pauseWhileAway]; a Short never plays away). Until it plays its card shows; a
 * stop in the session extras becomes its statement. The comments panel belongs to the turn.
 */
@OptIn(UnstableApi::class)
@Composable
private fun ShortPlayback(controller: MediaController, sessionExtras: Bundle, viewModel: PlayerViewModel, stage: ShortStageArgs, kids: Boolean) {
    val state = stage.state
    val videoId = state.video.id.value
    val turn = state.turn
    val view = rememberPlaybackView(controller, videoId)
    var attempt by remember { mutableIntStateOf(0) }
    var started by remember(videoId, turn, attempt) { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val dressing = view.dressing.takeIf { view.carries(videoId) }
    // The resolve names a search Short's channel (its card doesn't).
    LaunchedEffect(dressing, turn) { dressing?.let { viewModel.channelResolved(turn, it.uploaderName, it.uploaderUrl) } }
    LaunchedEffect(controller, videoId, turn, attempt) {
        controller.requestTurn(
            lifecycle,
            state.video.id,
            short = true,
            startingOver = false,
            afresh = attempt > 0,
            resumePointMs = viewModel::resumePointMs,
            startSpeedPercent = viewModel::startSpeedPercent,
        ) { }
        controller.play()
    }
    LaunchedEffect(view, videoId, turn, attempt) {
        view.awaitStarted(videoId)
        started = true
        viewModel.playbackStarted(turn)
    }
    val currentStarted by rememberUpdatedState(started)
    val currentMediaId by rememberUpdatedState(view.mediaId)
    DisposableEffect(lifecycle, controller, videoId) {
        val observer = pauseWhileAway(controller::pause, controller::play, startPending = { !currentStarted && currentMediaId == videoId })
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val localView = LocalView.current
    DisposableEffect(view.isPlaying) {
        localView.keepScreenOn = view.isPlaying
        onDispose { localView.keepScreenOn = false }
    }
    // A swipe leaves a stop or retry behind: the service clears it only once this turn's request
    // lands, so the extras this turn began with are the last Short's.
    val turnExtras = remember(videoId, turn) { sessionExtras }
    val extras = if (sessionExtras === turnExtras) Bundle.EMPTY else sessionExtras
    val stop = stopFrom(extras)
    // YouTube's refusal can't be told from Restricted Mode's, so under Strict the statement names both.
    val restricted = LocalContentFilter.current?.restrictedMode == RestrictedMode.Strict
    val phase = when {
        stop != null -> {
            val copy = when (stop.reason) {
                PlayerPolicy.TERMINAL_LIVE -> liveRefusalCopy(kids, state.video)
                // Its title already names the cause, which the Shorts title would hide.
                PlayerPolicy.TERMINAL_MADE_FOR_KIDS -> stopCopy(stop.reason, positionMs = 0)
                else -> stopCopy(stop.reason, positionMs = 0, stop.detail, restricted).forShort(state.shorts.canMove())
            }
            ShortPhase.Stopped(copy, onRetry = if (copy.retryLabel != null) { { attempt++ } } else null)
        }
        !started -> ShortPhase.Starting
        else -> ShortPhase.Playing(paused = !view.playWhenReady, buffering = view.playbackState == Player.STATE_BUFFERING)
    }
    var commentsOpen by rememberSaveable(videoId, turn) { mutableStateOf(false) }
    LaunchedEffect(phase is ShortPhase.Playing) { if (phase !is ShortPhase.Playing) commentsOpen = false }
    LaunchedEffect(commentsOpen) {
        if (commentsOpen) {
            viewModel.openComments()
        } else {
            delay(AynMotion.MOVE_MS.toLong())
            viewModel.closeReplies()
        }
    }
    val commentsList = remember(videoId, turn) { LazyListState() }
    val commentsCalls = remember(viewModel) {
        CommentsCalls(viewModel::moreComments, viewModel::retryComments, viewModel::openReplies, viewModel::closeReplies)
    }
    val presentation = rememberPresentationState(controller)
    val upright = view.videoSize.let { it.width == 0 || it.height >= it.width }
    val avatarUrl = dressing?.uploaderAvatarUrl
    val scope = rememberCoroutineScope()
    // A turn's pending step never lands on the next turn's Short.
    val seeks = remember(controller, videoId, turn) { SeekGathering(controller, scope) }
    DisposableEffect(seeks) { onDispose { seeks.cancel() } }
    ShortStage(
        stage = stage,
        phase = phase,
        retry = retryFrom(extras),
        player = controller,
        seeks = seeks,
        avatarUrl = avatarUrl,
        onPlayPause = { if (controller.playWhenReady) controller.pause() else controller.play() },
        onToggleSubscribe = if (kids) null else { { viewModel.toggleSubscribe(avatarUrl) } },
        commentsOpen = commentsOpen,
        onComments = { commentsOpen = it },
        picture = {
            // M3-UI-01: the surface stays composed under every layer. Upright Shorts fill the screen.
            PlayerSurface(
                player = controller,
                modifier = Modifier
                    .align(Alignment.Center)
                    .resizeWithContentScale(if (upright) ContentScale.Crop else ContentScale.Fit, presentation.videoSizeDp)
                    .testTag(PICTURE_TAG),
            )
        },
        panel = {
            AnimatedVisibility(
                visible = commentsOpen,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(COMMENTS_SHARE),
                enter = slideInVertically(tween(AynMotion.MOVE_MS, easing = AynMotion.EaseOut)) { it },
                exit = slideOutVertically(tween(AynMotion.MOVE_MS, easing = AynMotion.Ease)) { it },
            ) {
                CommentsPanel(
                    comments = state.comments,
                    channel = CommentsChannel(state.video.uploaderName, avatarUrl),
                    list = commentsList,
                    calls = commentsCalls,
                    backEnabled = commentsOpen && !stage.menuOpen,
                    beside = false,
                    onClose = { commentsOpen = false },
                )
            }
        },
    )
}

/**
 * The Short's page (`m6-shorts` 1–12) for [phase]: [picture] (the surface, once there is a
 * controller) under the card while it starts or stops, the foot (channel and Subscribe once it
 * plays, title, views; Save, and Comments once it plays), ← and the progress line. A swipe moves
 * the run: the page follows the finger with the Short beside it coming in, and one let go past a
 * fifth (or flung) settles on it; at the run's end it springs back ("That's the last Short here"
 * going on; a next page still to load moves the run once it lands). While [commentsOpen] the
 * Short shrinks above [panel] and swipes go to the comments. Playing, a double-tap on the left or
 * right third steps by [PlayerChromePolicy.SHORT_STEP_MS] through [seeks], and taps on that side
 * while its mark shows add steps; a sideways drag anywhere on it scrubs ([scrubDrag]).
 */
@Composable
private fun ShortStage(
    stage: ShortStageArgs,
    phase: ShortPhase,
    retry: PlayerErrorUiState = PlayerErrorUiState.None,
    player: Player? = null,
    seeks: SeekGathering? = null,
    avatarUrl: String? = null,
    onPlayPause: () -> Unit = {},
    onToggleSubscribe: (() -> Unit)? = null,
    commentsOpen: Boolean = false,
    onComments: (Boolean) -> Unit = {},
    picture: @Composable BoxScope.() -> Unit = {},
    panel: @Composable BoxScope.() -> Unit = {},
) {
    val state = stage.state
    val calls = stage.calls
    val position = state.shorts ?: ShortsPosition(0, hasPrevious = false, hasNext = false)
    BackHandler(enabled = !stage.menuOpen) { if (commentsOpen) onComments(false) else calls.close() }
    val drag = remember { Animatable(0f) }
    var beside by remember { mutableStateOf<Beside?>(null) }
    var endLine by remember { mutableIntStateOf(0) }
    var height by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    // A swipe that settled waits off screen for the new turn, so the Short beside it holds the screen meanwhile.
    LaunchedEffect(state.turn) {
        drag.snapTo(0f)
        beside = null
    }
    val currentPosition by rememberUpdatedState(position)
    val dragState = rememberDraggableState { delta ->
        // Decided in order against where the page really is, not where earlier deltas will put it.
        scope.launch {
            val next = drag.value + delta < 0
            val shown = beside?.takeIf { it.next == next } ?: calls.beside(next)?.let { Beside(it, next) }
            beside = shown
            drag.snapTo(drag.value + if (shown == null) delta * EDGE_PULL else delta)
        }
    }
    val swipe = Modifier.draggable(
        dragState,
        Orientation.Vertical,
        enabled = !commentsOpen,
        onDragStopped = { velocity ->
            // Queued behind the drag's pending snaps, so it decides on where the page really is.
            scope.launch {
                val at = drag.value
                val toNext = at < 0 && (at < -height * SWIPE_AT || velocity < -SWIPE_FLING)
                val toPrevious = at > 0 && (at > height * SWIPE_AT || velocity > SWIPE_FLING)
                val shown = beside
                when {
                    toNext && shown?.next == true -> {
                        drag.animateTo(-height.toFloat(), tween(SWIPE_MS, easing = AynMotion.Ease))
                        calls.next()
                    }
                    toPrevious && shown?.next == false -> {
                        drag.animateTo(height.toFloat(), tween(SWIPE_MS, easing = AynMotion.Ease))
                        calls.previous()
                    }
                    // A next page still to load: the run moves once it lands.
                    toNext && currentPosition.hasNext -> {
                        calls.next()
                        drag.animateTo(0f, tween(SWIPE_MS, easing = AynMotion.Ease))
                    }
                    toNext -> {
                        endLine++
                        drag.animateTo(0f, tween(SWIPE_MS, easing = AynMotion.Ease))
                    }
                    else -> drag.animateTo(0f, tween(SWIPE_MS, easing = AynMotion.Ease))
                }
            }
        },
    )
    val shrink by animateFloatAsState(if (commentsOpen) 1f else 0f, tween(SHRINK_MS, easing = AynMotion.Ease), label = "shrink")
    val topInset = with(LocalDensity.current) { WindowInsets.statusBars.getTop(this).toFloat() }
    val playing = phase as? ShortPhase.Playing
    var scrubMs by remember(state.turn) { mutableStateOf<Long?>(null) }
    // The last mark stays through its fade, so its side and words hold as it goes.
    var mark by remember(state.turn) { mutableStateOf<StepMark?>(null) }
    var marking by remember(state.turn) { mutableStateOf(false) }
    LaunchedEffect(mark) {
        if (mark != null) {
            delay(STEP_MARK_MS)
            marking = false
        }
    }
    fun step(forward: Boolean) {
        val durationMs = player?.duration ?: return
        if (seeks == null || durationMs <= 0) return
        val stepMs = PlayerChromePolicy.SHORT_STEP_MS
        seeks.stepBy(if (forward) stepMs else -stepMs)
        val last = mark?.takeIf { marking && it.forward == forward }
        mark = StepMark(forward, (last?.ms ?: 0) + stepMs)
        marking = true
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { height = it.height }
            // Its empty parts still take touches: the shell under it gets none.
            .pointerInput(Unit) {},
    ) {
        beside?.let { shown ->
            Box(Modifier.fillMaxSize().graphicsLayer { translationY = drag.value + if (shown.next) size.height else -size.height }) {
                OpeningCover(shown.video.thumbnailUrl, note = false)
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = drag.value + shrink * topInset
                    // Shrunk into the room above the comments panel, whole.
                    val shrunk = ((size.height * (1f - COMMENTS_SHARE) - topInset) / size.height).coerceIn(0.1f, 1f)
                    val scale = lerp(1f, shrunk, shrink)
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0.5f, 0f)
                }
                .clipToBounds()
                .then(swipe)
                .then(if (playing != null && player != null && seeks != null && !commentsOpen) Modifier.scrubDrag(player, seeks) { scrubMs = it } else Modifier)
                .pointerInput(playing != null, commentsOpen, seeks) {
                    if (playing == null || commentsOpen) return@pointerInput
                    // A tap on a side while its mark shows steps as it lifts, so a run of taps adds up
                    // (a swipe starting there doesn't).
                    var markTap = false
                    var side: Boolean? = null
                    var lastSide: Boolean? = null
                    detectTapGestures(
                        onPress = { at ->
                            lastSide = side
                            val pressed = sideAt(at.x, size.width)
                            side = pressed
                            markTap = pressed != null && marking && mark?.forward == pressed
                            if (markTap && tryAwaitRelease() && pressed != null) step(pressed)
                        },
                        // A tap then a drag: the drag holds the Short, the tap's late call doesn't play it.
                        onTap = { if (!markTap && scrubMs == null) onPlayPause() },
                        onDoubleTap = {
                            if (!markTap) {
                                // Both taps on one side step; in the middle or across thirds it is one tap.
                                val pressed = side
                                if (pressed != null && pressed == lastSide) step(pressed) else onPlayPause()
                            }
                        },
                    )
                }
                // Under the comments panel nothing here is read out or acted on.
                .then(if (commentsOpen) Modifier.clearAndSetSemantics { } else Modifier)
                .semantics {
                    contentDescription = "Short"
                    if (playing != null) {
                        onClick(label = if (playing.paused) "Play" else "Pause") {
                            onPlayPause()
                            true
                        }
                    }
                    customActions = listOfNotNull(
                        CustomAccessibilityAction("Next Short") {
                            calls.next()
                            true
                        }.takeIf { position.hasNext },
                        CustomAccessibilityAction("Previous Short") {
                            calls.previous()
                            true
                        }.takeIf { position.hasPrevious },
                    )
                },
        ) {
            picture()
            ShortPicture(state, phase, retry, player, scrubbing = scrubMs != null)
            StepMarkView(mark, lit = marking)
            // Away while the comments show: nothing under the panel takes a tap or is read out.
            AnimatedVisibility(
                visible = !commentsOpen,
                modifier = Modifier.matchParentSize(),
                enter = fadeIn(tween(SHRINK_MS)),
                exit = fadeOut(tween(SHRINK_MS)),
            ) {
                Box(Modifier.fillMaxSize()) {
                    ShortFoot(state, phase, avatarUrl, onToggleSubscribe, calls, scrubbing = scrubMs != null, onComments = { onComments(true) })
                }
            }
            if (phase is ShortPhase.Playing && player != null && seeks != null) {
                ShortProgress(player, seeks, scrubMs, modifier = Modifier.align(Alignment.BottomCenter))
            }
        }
        if (!commentsOpen) {
            PictureButton(
                icon = R.drawable.ic_back,
                label = "Back",
                onClick = calls.close,
                modifier = Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.statusBars).padding(start = 8.dp, top = 6.dp),
                size = 40.dp,
                fill = Shade.copy(alpha = 0.45f),
            )
        }
        EndLine(endLine, Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 10.dp))
        panel()
    }
}

/**
 * What covers the picture: the card dimmed with Starting, the blurred card under a stop's
 * statement, or over the playing Short a dim and a note while it buffers or retries, and the play
 * mark while paused (not while [scrubbing] holds it).
 */
@Composable
private fun BoxScope.ShortPicture(state: PlayerUiState, phase: ShortPhase, retry: PlayerErrorUiState, player: Player?, scrubbing: Boolean) {
    val card = state.video
    val retrying = retryLine(retry)
    when (phase) {
        ShortPhase.Starting -> OpeningCover(card.thumbnailUrl)
        is ShortPhase.Stopped -> {
            Box(Modifier.matchParentSize().background(Color.Black)) { BlurredPicture(card.thumbnailUrl) }
            ShortStatement(phase, canMove = state.shorts.canMove())
        }
        is ShortPhase.Playing -> {
            val dim by animateFloatAsState(if (phase.buffering || retrying != null) 0.45f else 0f, AynMotion.base(), label = "dim")
            Box(Modifier.matchParentSize().graphicsLayer { alpha = dim }.background(Color.Black))
            if (phase.buffering && retrying == null) Spinner(44.dp, Modifier.align(Alignment.Center))
            PauseMark(phase.paused && !scrubbing, Modifier.align(Alignment.Center))
        }
    }
    if (retrying != null && phase !is ShortPhase.Stopped && player != null) {
        PictureNote(
            text = "${retrying.text}  ${retrying.count}",
            spinner = true,
            modifier = Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.statusBars).padding(top = 62.dp, start = 24.dp, end = 24.dp),
        )
    }
}

/** A stop in place of the Short (`m6-shorts` 11–12): what happened, the headline, one paragraph,
 *  Try again when it can help, and the way on when the run has another Short ([canMove]). */
@Composable
private fun BoxScope.ShortStatement(stopped: ShortPhase.Stopped, canMove: Boolean) {
    val copy = stopped.copy
    Column(
        Modifier
            .align(Alignment.CenterStart)
            .padding(start = 24.dp, end = 24.dp, bottom = 120.dp)
            .semantics(mergeDescendants = false) { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            StopMark(copy)
            Text(copy.kicker, style = MaterialTheme.typography.labelSmall.copy(fontSize = 13.sp).atWeight(650), color = AynColors.Ink2)
        }
        Text(copy.title, style = MaterialTheme.typography.headlineMedium.copy(fontSize = 28.sp), modifier = Modifier.padding(top = 10.dp))
        Text(
            copy.body,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
            color = AynColors.Ink2,
            modifier = Modifier.padding(top = 10.dp).widthIn(max = 360.dp),
        )
        val onRetry = stopped.onRetry
        if (onRetry != null && copy.retryLabel != null) {
            Spacer(Modifier.height(18.dp))
            PillButton(copy.retryLabel, onRetry, Modifier.height(40.dp), icon = R.drawable.ic_retry)
        }
        if (canMove) Row(
            Modifier.padding(top = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                painterResource(R.drawable.ic_chevron_down),
                contentDescription = null,
                tint = AynColors.Ink3,
                modifier = Modifier.size(14.dp).graphicsLayer { rotationZ = 180f },
            )
            Text("Swipe for another Short", style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp), color = AynColors.Ink3)
        }
    }
}

/**
 * The Short's foot (`m6-shorts` 1, 4): a shade for the words, then on the left the channel with
 * Subscribe (once it plays: a search Short's channel comes with the resolve), the title and views;
 * on the right Save, and Comments once it plays; both fade out while [scrubbing]. A stop shows none of it.
 */
@Composable
private fun BoxScope.ShortFoot(
    state: PlayerUiState,
    phase: ShortPhase,
    avatarUrl: String?,
    onToggleSubscribe: (() -> Unit)?,
    calls: ShortCalls,
    scrubbing: Boolean,
    onComments: () -> Unit,
) {
    if (phase is ShortPhase.Stopped) return
    val shown by animateFloatAsState(if (scrubbing) 0f else 1f, tween(FOOT_FADE_MS, easing = AynMotion.EaseOut), label = "foot")
    val video = state.video
    val playing = phase is ShortPhase.Playing
    val channelUrl = video.uploaderUrl.takeIf { playing }
    Box(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .fillMaxHeight(0.4f)
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f)))),
    )
    Row(
        Modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 16.dp, end = 8.dp, bottom = 16.dp)
            .graphicsLayer { alpha = shown },
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            if (channelUrl != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val name = video.uploaderName.orEmpty()
                    Row(
                        Modifier
                            .minimumInteractiveComponentSize()
                            .clip(RoundedCornerShape(20.dp))
                            .clickable(role = Role.Button, onClickLabel = "Open the channel") { calls.openChannel(channelUrl) }
                            .padding(end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        ChannelAvatar(name, avatarUrl, Modifier.size(30.dp), monogramSize = 14f)
                        Text(name, style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    onToggleSubscribe?.let { toggle ->
                        if (state.isSubscribed) {
                            PillButton("Subscribed", toggle, Modifier.height(32.dp), kind = ButtonKind.Tonal, icon = R.drawable.ic_check)
                        } else {
                            PillButton("Subscribe", toggle, Modifier.height(32.dp))
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
            Text(
                video.title,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, lineHeight = 21.sp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            viewsText(video.viewCount)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp), color = AynColors.Ink2, modifier = Modifier.padding(top = 3.dp))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            ShortAction(R.drawable.ic_list, "Save", calls.save)
            if (playing) ShortAction(R.drawable.ic_comment, "Comments", onComments)
        }
    }
}

/** A round button over the Short with its name under it (`.sact`). */
@Composable
private fun ShortAction(@DrawableRes icon: Int, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        PictureButton(icon, label, onClick, size = 48.dp, iconSize = 24.dp, fill = Shade.copy(alpha = 0.55f))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp).atWeight(600),
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}

/** Paused: the play mark over the picture, growing in and fading out. */
@Composable
private fun PauseMark(paused: Boolean, modifier: Modifier) {
    val shown by animateFloatAsState(if (paused) 1f else 0f, AynMotion.press(), label = "pause")
    Box(
        modifier
            .size(PauseMarkSize)
            .graphicsLayer {
                alpha = shown
                val scale = 0.9f + 0.1f * shown
                scaleX = scale
                scaleY = scale
            }
            .background(Shade.copy(alpha = 0.62f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (shown > 0f) Icon(painterResource(R.drawable.ic_play), contentDescription = null, tint = AynColors.Ink, modifier = Modifier.size(32.dp))
    }
}

/** "That's the last Short here", for a moment after each swipe past the run's end ([shown] counts them). */
@Composable
private fun EndLine(shown: Int, modifier: Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(shown) {
        if (shown == 0) return@LaunchedEffect
        visible = true
        delay(END_LINE_MS)
        visible = false
    }
    AnimatedVisibility(visible, modifier, enter = fadeIn(AynMotion.base()), exit = fadeOut(AynMotion.base())) {
        Text(
            "That’s the last Short here",
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp).atWeight(600),
            color = AynColors.Ink2,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}
