package com.arshad.ayn.ui.tv.player

import android.content.Context
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.modifiers.resizeWithContentScale
import androidx.media3.ui.compose.state.rememberPresentationState
import androidx.media3.ui.compose.state.rememberProgressStateWithTickInterval
import com.arshad.ayn.core.access.SegmentLookup
import com.arshad.ayn.core.store.ChatPlacement
import com.arshad.ayn.feature.player.ChatPlace
import com.arshad.ayn.feature.player.liveChatOffered
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.filter.shows
import com.arshad.ayn.core.store.RestrictedMode
import com.arshad.ayn.feature.library.VideoMenuViewModel
import com.arshad.ayn.feature.library.WatchMark
import com.arshad.ayn.feature.player.CaptionCues
import com.arshad.ayn.feature.player.LiveStop
import com.arshad.ayn.feature.player.PlaybackDressing
import com.arshad.ayn.feature.player.PlaybackView
import com.arshad.ayn.feature.player.PlayerChromePolicy
import com.arshad.ayn.feature.player.PlayerErrorUiState
import com.arshad.ayn.feature.player.PlayerPolicy
import com.arshad.ayn.feature.player.PlayerUiState
import com.arshad.ayn.feature.player.PlayerViewModel
import com.arshad.ayn.feature.player.SeekGathering
import com.arshad.ayn.feature.player.awaitStarted
import com.arshad.ayn.feature.player.formatBadgeText
import com.arshad.ayn.feature.player.overPicture
import com.arshad.ayn.feature.player.pauseWhileAway
import com.arshad.ayn.feature.player.requestTurn
import com.arshad.ayn.feature.player.retryFrom
import com.arshad.ayn.feature.player.stopFrom
import com.arshad.ayn.feature.player.timeText
import com.arshad.ayn.ui.tv.R
import com.arshad.ayn.ui.tv.common.ACTION_TOAST_MS
import com.arshad.ayn.ui.tv.common.CardRoomLight
import com.arshad.ayn.ui.tv.common.FocusedCardLight
import com.arshad.ayn.ui.tv.common.LocalContentFilter
import com.arshad.ayn.ui.tv.common.RiseFrom
import com.arshad.ayn.ui.tv.common.Toast
import com.arshad.ayn.ui.tv.focus.EnterKeys
import com.arshad.ayn.ui.tv.library.VideoMenuHost
import com.arshad.ayn.ui.tv.theme.KeColors
import com.arshad.ayn.ui.tv.theme.KeLayout
import com.arshad.ayn.ui.tv.theme.KeMotion
import kotlin.time.Clock
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The player destination (`b3-player`, `b3-player-panels`), full-bleed with the rail hidden. It
 * binds a controller to the playback service ([sessionToken]) and asks for [PlayerViewModel]'s
 * current video (the service resolves it, from its resume point when it has one). A live stream
 * plays with the live chrome (`live-tv-player`, LIVE-D23); a kids profile's live card stops before
 * anything is asked (LIVE-D13). Until the video plays it shows the card (v8 opening); a stop in the
 * session extras becomes its guided statement (D8); an ended video goes to [PlayerViewModel.ended]
 * (Play all moves on to the next, else Up next; an ended live stream says so instead). A related
 * video picked in the Related row, Up next or the card menu on [menu] plays in place
 * ([PlayerViewModel.play]). A Short plays in the Shorts player ([ShortStage]); its Save opens the
 * Save to playlist sheet alone on [menu]. Both players open the comments panel ([CommentsPanel]).
 * A [kids] profile's player has no Subscribe and no Comments (KP-D11); a live stream has no Comments
 * but its chat ([ChatPanel], LIVE-D15).
 * The chrome's ages are measured on [clock].
 */
@Composable
internal fun PlayerScreen(
    viewModel: PlayerViewModel,
    menu: VideoMenuViewModel,
    sessionToken: (Context) -> SessionToken,
    onOpenChannel: (String) -> Unit,
    onBack: () -> Unit,
    clock: Clock,
    kids: Boolean = false,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // The player's BackHandlers register after VideoMenuHost's and would win; they stand down
    // while the menu shows.
    val menuOpen = menu.menu.collectAsStateWithLifecycle().value != null
    // Read only by the cards that show bars: a position save re-emits it every 10 s.
    val marks = viewModel.marks.collectAsStateWithLifecycle()
    VideoMenuHost(menu, onPlay = viewModel::play, onOpenChannel = onOpenChannel, onRailShade = {}) { pageModifier, openMenu ->
        val panels = remember(viewModel, menu, openMenu, pageModifier, menuOpen, marks) {
            RelatedPanels(
                onPlay = { video -> viewModel.play(video) },
                onOpenMenu = openMenu,
                onSaveShort = { short -> menu.openSaveTo(short, short = true) },
                rowModifier = pageModifier,
                menuOpen = menuOpen,
                marks = marks,
            )
        }
        PlayerPage(viewModel, state, panels, sessionToken, onOpenChannel, onBack, kids, clock)
    }
}

/** What the Related row, Up next and the Shorts player's Save need: what a video does there, and
 *  the card menu's part. */
internal class RelatedPanels(
    val onPlay: (VideoItem) -> Unit,
    /** Holding OK on a card: the card menu. */
    val onOpenMenu: (VideoItem) -> Unit,
    /** Save on a Short: the Save to playlist sheet alone. */
    val onSaveShort: (VideoItem) -> Unit,
    /** On the row whose card opened the menu (or the Short's Save): focus returns there as it closes. */
    val rowModifier: Modifier,
    /** The card menu shows: the player's BACK handling yields to it. */
    val menuOpen: Boolean,
    /** The profile's resume points, the cards' bars; read where a card draws one. */
    val marks: State<Map<VideoId, WatchMark>>,
)

@Composable
private fun PlayerPage(
    viewModel: PlayerViewModel,
    state: PlayerUiState,
    panels: RelatedPanels,
    sessionToken: (Context) -> SessionToken,
    onOpenChannel: (String) -> Unit,
    onBack: () -> Unit,
    kids: Boolean,
    clock: Clock,
) {
    val skips = remember(viewModel) {
        SkipCalls(
            tick = viewModel::segmentTick,
            skip = viewModel::skipPrompted,
            dismissPrompt = viewModel::dismissSkipPrompt,
            undo = viewModel::undoSkip,
            dismissNote = viewModel::dismissSkipNote,
            promptInView = viewModel::skipPromptInView,
            skipFromControls = viewModel::skipFromControls,
        )
    }
    val captionCalls = remember(viewModel) { CaptionCalls(viewModel::captionsResolved, viewModel::chooseCaptions, viewModel::retryCaptions) }
    val commentsCalls = remember(viewModel) {
        CommentsCalls(viewModel::openComments, viewModel::moreComments, viewModel::retryComments, viewModel::openReplies, viewModel::closeReplies)
    }
    val chatCalls = remember(viewModel) {
        ChatCalls(
            open = viewModel::openChat,
            shown = viewModel::chatShown,
            chooseView = viewModel::chooseChatView,
            retry = viewModel::retryChat,
            choosePlacement = viewModel::chooseChatPlacement,
            hide = viewModel::hideChat,
            show = viewModel::showChat,
            scroll = viewModel::scrollChat,
            endScroll = viewModel::endChatScroll,
            tick = viewModel::chatTick,
        )
    }
    Box(Modifier.fillMaxSize().background(KeColors.Ground)) {
        if (kids && state.video.isLive) {
            LiveStatement(LiveStop.Kids, state.video, onBack = onBack)
            return@Box
        }
        // "Play from the start": the video is asked for afresh from 0, past its resume point and a
        // paused same-video item, until it has played. Held above the connection (a reconnect
        // mustn't re-arm it) and saved (a later return resumes as usual).
        var startingOver by rememberSaveable(state.video.id.value, state.turn) { mutableStateOf(state.startOver) }
        var reconnect by remember { mutableIntStateOf(0) }
        val connection = rememberPlayerConnection(sessionToken, reconnect)
        val controller = connection.controller
        val run = state.shorts
        val shortActions = { play: ShortPlay, onRetry: () -> Unit, avatarUrl: String?, onOpenComments: () -> Unit ->
            ShortActions(
                onPrevious = viewModel::previousShort,
                onNext = viewModel::nextShort,
                play = play,
                onRetry = onRetry,
                onOpenChannel = { state.video.uploaderUrl?.let(onOpenChannel) },
                onToggleSubscribe = { viewModel.toggleSubscribe(avatarUrl) },
                onSave = { panels.onSaveShort(state.video) },
                onOpenComments = onOpenComments,
            )
        }
        when {
            run != null && (connection.isDown || controller == null) -> ShortStage(
                card = state.video,
                position = run,
                phase = if (connection.isDown) ShortPhase.Stopped(PlayerDownCopy.forShort()) else ShortPhase.Starting(PlayerErrorUiState.None),
                turn = state.turn,
                videoSize = VideoSize.UNKNOWN,
                avatarUrl = null,
                isSubscribed = state.isSubscribed,
                menuOpen = panels.menuOpen,
                player = null,
                actions = shortActions(ShortPlay.NONE, { reconnect++ }, null, {}),
                saveModifier = panels.rowModifier,
            )
            connection.isDown -> StopStatement(PlayerDownCopy, state.video.thumbnailUrl, onRetry = { reconnect++ }, onBack = onBack)
            controller == null -> Opening(state.video, state.playlist, avatarUrl = null, retry = PlayerErrorUiState.None)
            else -> Playback(
                controller = controller,
                sessionExtras = connection.sessionExtras,
                state = state,
                panels = panels,
                skips = skips,
                startingOver = startingOver,
                onStarted = { turn ->
                    startingOver = false
                    viewModel.playbackStarted(turn)
                },
                resumePointMs = viewModel::resumePointMs,
                startSpeedPercent = viewModel::startSpeedPercent,
                onEnded = viewModel::ended,
                onToggleSubscribe = if (kids) null else viewModel::toggleSubscribe,
                kids = kids,
                onOpenDetails = viewModel::openDetails,
                onChannelResolved = viewModel::channelResolved,
                captionCalls = captionCalls,
                commentsCalls = commentsCalls,
                chatCalls = chatCalls,
                shortActions = shortActions,
                onOpenChannel = onOpenChannel,
                onBack = onBack,
                clock = clock,
            )
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun Playback(
    controller: MediaController,
    sessionExtras: Bundle,
    state: PlayerUiState,
    panels: RelatedPanels,
    skips: SkipCalls,
    startingOver: Boolean,
    /** [turn]'s video has started playing (History records it then). */
    onStarted: (turn: Int) -> Unit,
    resumePointMs: suspend (VideoId) -> Long?,
    startSpeedPercent: suspend () -> Int,
    onEnded: (turn: Int) -> Unit,
    onToggleSubscribe: ((avatarUrl: String?) -> Unit)?,
    kids: Boolean,
    onOpenDetails: () -> Unit,
    onChannelResolved: (turn: Int, name: String?, url: String?) -> Unit,
    captionCalls: CaptionCalls,
    commentsCalls: CommentsCalls,
    chatCalls: ChatCalls,
    shortActions: (play: ShortPlay, onRetry: () -> Unit, avatarUrl: String?, onOpenComments: () -> Unit) -> ShortActions,
    onOpenChannel: (String) -> Unit,
    onBack: () -> Unit,
    clock: Clock,
) {
    val videoId = state.video.id.value
    // Play all's turn too: a playlist can list one video twice in a row.
    val turn = state.turn
    val view = rememberPlaybackView(controller, videoId)
    val hold = rememberDisplayModeHold(controller)
    var attempt by remember { mutableIntStateOf(0) }
    // Try again's count when this turn began: only the turn's first request shows the toast.
    val turnAttempt = remember(videoId, turn) { attempt }
    // The current video has played (or ended) since it was asked for; until then, the opening.
    var started by remember(videoId, turn, attempt) { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val ours = view.carries(videoId)
    val dressing = remember(view.dressing, ours) { view.dressing.takeIf { ours }?.forScreen() }
    // The resolve names a search Short's channel (its card doesn't) and lists the caption tracks.
    LaunchedEffect(dressing, turn) {
        dressing?.let {
            onChannelResolved(turn, it.uploaderName, it.uploaderUrl)
            captionCalls.resolved(turn, it.captionTracks)
        }
    }
    // The resolved page's word, the card's until then.
    val live = dressing?.live ?: state.video.isLive
    // Once, for the peek, the row and Up next alike; lazy keys must be unique and YouTube's list
    // can repeat a video. A block from a card's menu takes its cards out at once (KP-D19).
    val contentFilter = LocalContentFilter.current
    val related = remember(dressing, contentFilter) {
        dressing?.relatedVideos.orEmpty().distinctBy { it.id }.filter { contentFilter.shows(it) }
    }
    // YouTube's refusal can't be told from Restricted Mode's, so under Strict the statement names both.
    val restricted = contentFilter?.restrictedMode == RestrictedMode.Strict
    // Where this turn's first request resumed: the toast's one-shot, consumed once the stage shows it.
    var resumedAtMs by remember(videoId, turn) { mutableStateOf<Long?>(null) }
    // Cancel or BACK on Up next holds the ended player; replaying ends that.
    var endDismissed by remember(videoId, turn) { mutableStateOf(false) }
    val panelStack = remember(videoId, turn) { PanelStack() }
    val failedCaptionsNote = remember(videoId, turn) { FailedCaptionsNote(captionCalls.retry) }
    val commentsPlace = remember(videoId, turn) { CommentsPlace() }

    // M5-D8's request rules, shared with the phone's player (requestTurn).
    LaunchedEffect(controller, videoId, turn, attempt) {
        controller.requestTurn(
            lifecycle,
            state.video.id,
            short = state.shorts != null,
            startingOver = startingOver,
            afresh = attempt > 0,
            resumePointMs = resumePointMs,
            startSpeedPercent = startSpeedPercent,
        ) { resumeMs ->
            resumedAtMs = resumeMs?.takeIf { attempt == turnAttempt && it > 0 }
        }
        hold.play()
    }
    LaunchedEffect(view, videoId, turn, attempt) {
        view.awaitStarted(videoId)
        started = true
        onStarted(turn)
    }
    val currentStarted by rememberUpdatedState(started)
    val currentMediaId by rememberUpdatedState(view.mediaId)
    DisposableEffect(lifecycle, hold, videoId) {
        val observer = pauseWhileAway(hold::pause, hold::play, startPending = { !currentStarted && currentMediaId == videoId })
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    // Gated on this turn's start: right after a new turn (Play all's next, a pick) the previous
    // turn's item is still ended.
    val ended = started && ours && view.playbackState == Player.STATE_ENDED
    LaunchedEffect(ended, videoId, turn) {
        if (ended) onEnded(turn) else endDismissed = false
    }
    // An ended live stream says so; it never offers Up next (Q21).
    val liveEnded = ended && live
    val upNext = ended && !live && state.atEnd && related.isNotEmpty() && !endDismissed
    // TVU-6: keep-screen-on is derived from playback, always cleared on the way out.
    val localView = LocalView.current
    DisposableEffect(view.isPlaying) {
        localView.keepScreenOn = view.isPlaying
        onDispose { localView.keepScreenOn = false }
    }
    DisplayRateEffect(view.frameRate, hold)

    // A Short's Up/Down leaves a stop or retry behind: the service clears it only once this turn's
    // request lands, so the extras this turn began with are the last Short's.
    val turnExtras = remember(videoId, turn) { sessionExtras }
    val extras = if (state.shorts != null && sessionExtras === turnExtras) Bundle.EMPTY else sessionExtras
    val stop = stopFrom(extras)
    val retry = retryFrom(extras)
    val presentation = rememberPresentationState(controller)
    // The panels show over the stage alone; whatever replaces it closes them.
    val stageShowing = stop == null && started && !upNext && !liveEnded
    LaunchedEffect(stageShowing) { if (!stageShowing) panelStack.closeAll() }
    val avatarUrl = dressing?.uploaderAvatarUrl
    val currentAvatarUrl by rememberUpdatedState(avatarUrl)
    val currentUploaderUrl by rememberUpdatedState(state.video.uploaderUrl)
    val currentOnOpenChannel by rememberUpdatedState(onOpenChannel)
    val currentOnToggleSubscribe by rememberUpdatedState(onToggleSubscribe)
    val canSubscribe = onToggleSubscribe != null
    val hasComments = !kids && !live
    // An ended stream whose chat YouTube kept (LIVE-D28); a page without one has no Chat.
    val replay = dressing?.liveChat?.replay == true
    // A kids profile's live stream stops before it plays (LIVE-D13); chat stays off for one all the same.
    val hasChat = !kids && (live || replay)
    val chatAvailable = liveChatOffered(dressing)
    val currentChatPlace by rememberUpdatedState(state.chatPlace)
    val seekScope = rememberCoroutineScope()
    // A turn's pending seek never lands on the next turn's video.
    val seeks = remember(controller, videoId, turn) { SeekGathering(controller, seekScope) }
    DisposableEffect(seeks) { onDispose { seeks.cancel() } }
    // One instance per turn, so the chrome and the panels can skip what didn't change.
    val actions = remember(videoId, turn, controller, hold, panelStack, commentsCalls, chatCalls, canSubscribe, hasComments, hasChat, seeks) {
        ChromeActions(
            onPlayPause = { if (controller.playWhenReady) hold.pause() else hold.play() },
            onReplay = {
                seeks.seekTo(0)
                hold.play()
            },
            onSeekBy = { delta -> seeks.stepBy(delta) },
            onSeekTo = seeks::seekTo,
            onGoLive = seeks::goLive,
            onToggleSubscribe = if (canSubscribe) { { currentOnToggleSubscribe?.invoke(currentAvatarUrl) } } else null,
            onOpenChannel = { currentUploaderUrl?.let(currentOnOpenChannel) },
            onOpenCaptions = { panelStack.open(PlayerPanel.Captions) },
            onOpenSpeed = { panelStack.open(PlayerPanel.Speed) },
            onOpenQuality = { panelStack.open(PlayerPanel.Quality) },
            onOpenPlayback = { panelStack.open(PlayerPanel.Playback) },
            onOpenComments = if (hasComments) {
                {
                    panelStack.open(PlayerPanel.Comments)
                    commentsCalls.open()
                }
            } else {
                null
            },
            onOpenChat = if (hasChat) { { openChat(currentChatPlace, panelStack, chatCalls) } } else null,
        )
    }
    val run = state.shorts
    val commentsOpen = panelStack.top == PlayerPanel.Comments
    val chatOpen = panelStack.top == PlayerPanel.Chat
    // Letterboxed on black: a 4:3, square or portrait video keeps its shape.
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // M3-UI-01: the surface stays composed under every layer; Details plays it in its corner,
        // the comments and chat panels beside it (a Short moves left with its stage).
        val surfaceModifier = when {
            panelStack.top == PlayerPanel.Details -> Modifier.align(Alignment.TopEnd).detailsFrame()
            run != null -> Modifier.align(Alignment.Center)
                .offset(x = if (commentsOpen) -ShortCommentsShift else 0.dp)
                .fillMaxHeight()
                .aspectRatio(ShortAspect, matchHeightConstraintsFirst = true)
            commentsOpen || chatOpen -> Modifier.align(Alignment.TopStart).commentsFrame()
            else -> Modifier.fillMaxSize()
        }
        Box(surfaceModifier) {
            PlayerSurface(
                player = controller,
                modifier = Modifier.resizeWithContentScale(ContentScale.Fit, presentation.videoSizeDp).testTag(PICTURE_TAG),
            )
        }
        when {
            stop?.reason == PlayerPolicy.TERMINAL_LIVE && kids -> LiveStatement(LiveStop.Kids, state.video, onBack = onBack)
            stop?.reason == PlayerPolicy.TERMINAL_LIVE -> LiveStatement(LiveStop.Unavailable, state.video, onBack = onBack, onRetry = { attempt++ })
            run != null -> {
                ShortStage(
                    card = state.video,
                    position = run,
                    phase = when {
                        stop != null -> ShortPhase.Stopped(
                            stopCopy(stop.reason, positionMs = 0, stop.detail, restricted).let {
                                // Its title already names the cause, which the Shorts title would hide.
                                if (stop.reason == PlayerPolicy.TERMINAL_MADE_FOR_KIDS) it else it.forShort()
                            },
                        )
                        !started -> ShortPhase.Starting(retry)
                        else -> ShortPhase.Playing(paused = !view.playWhenReady, buffering = view.playbackState == Player.STATE_BUFFERING, retry = retry)
                    },
                    turn = turn,
                    videoSize = view.videoSize,
                    avatarUrl = avatarUrl,
                    isSubscribed = state.isSubscribed,
                    menuOpen = panels.menuOpen,
                    player = controller,
                    // A kids profile never plays a Short (KP-D11), so only it lacks onOpenComments.
                    actions = remember(shortActions, actions, avatarUrl, hold, seeks) {
                        val play = ShortPlay(toggle = actions.onPlayPause, pause = hold::pause, play = hold::play, stepBy = seeks::stepBy, pending = { seeks.targetMs }, seekTo = seeks::seekTo)
                        shortActions(play, { attempt++ }, avatarUrl, actions.onOpenComments ?: {})
                    },
                    saveModifier = panels.rowModifier,
                    commentsOpen = commentsOpen,
                )
                // Up and Down stay in the panel while it shows (Q8): it is the stage's sibling.
                BackHandler(enabled = commentsOpen && !panels.menuOpen) { panelStack.back() }
                CommentsPanel(
                    open = commentsOpen,
                    comments = state.comments,
                    channel = CommentsChannel(state.video.uploaderName, avatarUrl),
                    place = commentsPlace,
                    calls = commentsCalls,
                )
            }
            stop != null -> StopStatement(stopCopy(stop.reason, stop.positionMs, stop.detail, restricted), state.video.thumbnailUrl, onRetry = { attempt++ }, onBack = onBack)
            !started -> Opening(state.video, state.playlist, avatarUrl, retry)
            liveEnded -> LiveStatement(LiveStop.Ended, state.video, onBack = onBack)
            upNext -> {
                BackHandler(enabled = !panels.menuOpen) { endDismissed = true }
                UpNext(
                    finished = state.video,
                    related = related,
                    panels = panels,
                    onCancel = { endDismissed = true },
                )
            }
            else -> {
                Stage(
                    controller = controller,
                    view = view,
                    dressing = dressing,
                    related = related,
                    state = state,
                    panels = panels,
                    panel = panelStack.top,
                    skips = skips,
                    resumedAtMs = resumedAtMs,
                    onResumedShown = { resumedAtMs = null },
                    retry = retry,
                    ended = ended,
                    actions = actions,
                    seeks = seeks,
                    failedCaptionsNote = failedCaptionsNote,
                    live = live,
                    replay = replay,
                    chatAvailable = chatAvailable,
                    clock = clock,
                )
                PlayerPanels(
                    stack = panelStack,
                    player = controller,
                    view = view,
                    state = state,
                    dressing = dressing,
                    actions = actions,
                    commentsPlace = commentsPlace,
                    commentsCalls = commentsCalls,
                    chatCalls = chatCalls,
                    onOpenDetails = onOpenDetails,
                    onChooseCaptions = captionCalls.choose,
                    live = live,
                    replay = replay,
                )
            }
        }
    }
}

/** Chat in the chrome (LIVE-D26): over a showing view on the picture it opens the Chat sheet;
 *  otherwise chat shows in the stream's view, beside the picture or over it (a hidden chat shown
 *  again, a Scroll chat whose panel closed without BACK, by a stop, put back over the picture). */
internal fun openChat(place: ChatPlace, stack: PanelStack, calls: ChatCalls) {
    when {
        place.overlay != null -> stack.open(PlayerPanel.ChatSheet)
        place.scrolling -> calls.endScroll()
        place.placement == ChatPlacement.Beside -> {
            calls.show()
            stack.open(PlayerPanel.Chat)
        }
        else -> calls.show()
    }
}

/** The video picture's node, for its fitted bounds. */
internal const val PICTURE_TAG = "player-picture"

private val NudgeBottom = 48.dp
private val ChromeDrop = 12.dp
private const val NudgeMs = 1_000L
private val DimColor = Color.Black

/** The chrome's top steps up this far as the Related row opens (`.rel-open .ptop`). */
private val TopStep = 8.dp

/** The chrome hidden: which way a seek went and its target, for [NudgeMs] (a new seek restarts it);
 *  on a live window, whether the gathered seek [rejoins] the edge. */
private class Nudge(val forward: Boolean, val positionMs: Long, val rejoins: Boolean)

/** What holds the stage's bottom-right corner while the chrome and every panel are away, first
 *  come first: the resumed toast, then a skip prompt or note (M7-D10), then the ccfail note (Q56). */
private enum class Corner { Toast, Skip, FailedCaptions }

/**
 * The playing video under its chrome. Hidden: Left/Right step ±10 s with a nudge, gathered into
 * one seek ([seeks], whose target the nudge and the times show until it lands), OK pauses,
 * other keys show the chrome (TVU-8). Shown: it holds focus on its controls and recedes after
 * [PlayerChromePolicy.HIDE_AFTER_IDLE_MS] of playing without a key; BACK hides it first
 * (Leanback's playback rule). Paused or ended, or holding a scrub target, it stays. Buffering
 * and D8 retries dim the picture.
 * With related videos the chrome sits over their peek, and DOWN from its controls opens the
 * Related row (`b3-player-panels`): the bar rides above it, the rest steps away, and it stays until
 * UP, BACK or a pick (Q24). A start from a resume point ([resumedAtMs]) shows its toast over the
 * hidden chrome for [ACTION_TOAST_MS]: DOWN starts over, any other key drops it and acts as usual.
 * SponsorBlock's skips ([skips], M7-D10) run on their own position tick; the skip prompt or note
 * shows bottom right while neither the chrome nor the toast does: OK skips or undoes, BACK hides
 * it, and any other key drops the note and acts as usual. The note goes after [ACTION_TOAST_MS].
 * While a [panel] shows, the chrome and the skip slot hide and nothing here takes focus; Details,
 * Comments and Chat also drop the dim, the cues and the notes over the picture. As the panel closes, the chrome
 * comes back on the control that opened it. Captions (M7-D14) show the chosen track's cue over
 * the picture, risen above the chrome or whatever holds the bottom-right corner ([Corner]); when
 * its cues fail, the ccfail note ([failedCaptionsNote]) takes the corner once no skip prompt or
 * note holds it (Q56) for 5 s: OK is Try again, BACK hides it, and any other key drops it and
 * acts as usual. A [live] stream's chrome is the live one ([ChromeBottom]); its seeks, nudge and
 * buffering note speak of the live edge ("Catching up to live" there), and its Chat is dimmed
 * unless [chatAvailable]. Its chat, or an ended stream's kept [replay] (LIVE-D28), over the
 * picture ([ChatOverlay], in the stream's view) steps aside under the chrome, a panel, or a note
 * in its bottom-right corner; the cues rise above it in a bottom corner.
 */
@OptIn(UnstableApi::class)
@Composable
private fun Stage(
    controller: MediaController,
    view: PlaybackView,
    dressing: PlaybackDressing?,
    related: List<VideoItem>,
    state: PlayerUiState,
    panels: RelatedPanels,
    panel: PlayerPanel?,
    skips: SkipCalls,
    resumedAtMs: Long?,
    onResumedShown: () -> Unit,
    retry: PlayerErrorUiState,
    ended: Boolean,
    actions: ChromeActions,
    seeks: SeekGathering,
    failedCaptionsNote: FailedCaptionsNote,
    live: Boolean,
    replay: Boolean,
    chatAvailable: Boolean,
    clock: Clock,
) {
    val scope = rememberCoroutineScope()
    val progress = rememberProgressStateWithTickInterval(controller, PlayerChromePolicy.POSITION_TICK_MS, scope)
    val scrub = remember { ScrubState() }
    val focus = remember { ChromeFocus() }
    val ghost = remember { FocusRequester() }
    val rowFocus = remember { FocusRequester() }
    val cardLight = remember { FocusedCardLight() }
    // Held while the toast sinks away after the one-shot is consumed.
    val resumedAt = remember { resumedAtMs }
    var toastUp by remember { mutableStateOf(resumedAt != null) }
    val chrome = remember { ChromeVisibility(shown = !toastUp) }
    // The hidden chrome's bar and times hold still: no position tick recomposes them while unseen.
    val chromeShown = chrome.shown
    val times = remember(progress, seeks, chromeShown) {
        // A pending gathered seek shows as the position until it lands.
        val live = ScrubTimes({ seeks.targetMs ?: progress.currentPositionMs }, { progress.bufferedPositionMs }, { progress.durationMs })
        if (chromeShown) live else live.held()
    }
    var rowOpen by remember { mutableStateOf(false) }
    var nudge by remember { mutableStateOf<Nudge?>(null) }
    var swallowUp by remember { mutableStateOf<Key?>(null) }
    val paused = !view.playWhenReady
    val buffering = view.playbackState == Player.STATE_BUFFERING
    val peek = related.isNotEmpty()
    val rowRisePx = with(LocalDensity.current) { RiseFrom.roundToPx() }
    val segments = dressing?.segments
    val panelOpen = panel != null
    val currentPanelOpen by rememberUpdatedState(panelOpen)
    // Details, Comments and Chat frame the picture: nothing is drawn over it then.
    val pictureFramed = panel?.framesPicture == true
    val captions = state.captions
    val captionsFailure = captions?.cues as? CaptionCues.Failed
    val cornerHidden = chrome.shown || panelOpen
    val corner = when {
        cornerHidden -> null
        toastUp -> Corner.Toast
        state.skipNote != null || state.skipPrompt != null -> Corner.Skip
        captionsFailure != null && !failedCaptionsNote.isDismissed(captionsFailure) -> Corner.FailedCaptions
        else -> null
    }
    val skipShowing = corner == Corner.Skip
    val chatPage = live || replay
    val chatOverlay = overPicture(state.chatPlace.overlay.takeIf { chatPage }, state.chat)
    // Chat over the picture steps aside under the chrome or a panel, and gives the bottom-right
    // corner to a note there (LIVE-D22).
    val chatAside = cornerHidden || (corner != null && chatOverlay == ChatPlacement.BottomRight)
    val chatUnderCues = !chatAside && (chatOverlay == ChatPlacement.BottomLeft || chatOverlay == ChatPlacement.BottomRight)
    // The skip slot's place, the prompt's or the note's (the note outranks the prompt and takes it).
    val promptInView = !cornerHidden && !toastUp
    val failedNoteShowing = corner == Corner.FailedCaptions
    var chromeHeightPx by remember { mutableIntStateOf(0) }

    // Event-driven focus moves land once the recomposition has made their target focusable.
    fun focusAfterFrame(target: FocusRequester) {
        scope.launch {
            withFrameNanos { }
            target.requestFocus()
        }
    }
    fun openRow() {
        rowOpen = true
        focusAfterFrame(rowFocus)
    }
    fun closeRow() {
        rowOpen = false
        focusAfterFrame(focus.primary)
    }
    // A block from a card's menu can empty the open row: it closes, and once the menu has gone
    // refocusAfterMenu puts focus on the chrome (the menu's notice keeps it until then).
    LaunchedEffect(peek) {
        if (!peek) rowOpen = false
    }
    // The hidden chrome's keys (TVU-8); false for a key it leaves alone.
    fun onHiddenKey(key: Key): Boolean {
        when (key) {
            Key.DirectionLeft, Key.DirectionRight -> {
                val forward = key == Key.DirectionRight
                val target = seeks.stepBy(if (forward) PlayerChromePolicy.SEEK_STEP_MS else -PlayerChromePolicy.SEEK_STEP_MS)
                // As the gathered seek will land (its net direction).
                nudge = Nudge(forward, target, seeks.rejoinsLive(target))
            }
            in EnterKeys -> {
                actions.onPlayPause()
                chrome.show()
            }
            Key.DirectionUp, Key.DirectionDown -> chrome.show()
            else -> return false
        }
        return true
    }
    // A key press, by what shows: the skip prompt or note, the ccfail note, the toast, the open row,
    // the chrome, else the hidden chrome; false leaves it to focus. BACK is the BackHandlers'
    // (below), on its key-up: dropping the toast on its key-down would hand the corner to what it hid.
    fun onKeyDown(key: Key): Boolean {
        if (skipShowing && skips.onKey(key, state.skipNote, actions.onSeekTo)) return true
        if (failedNoteShowing && captionsFailure != null && failedCaptionsNote.onKey(key, captionsFailure)) return true
        if (toastUp && key != Key.Back) {
            toastUp = false
            if (key == Key.DirectionDown) {
                actions.onSeekTo(0)
                return true
            }
        }
        when {
            rowOpen -> if (key == Key.DirectionUp) closeRow() else return false
            chrome.shown -> if (key == Key.DirectionDown && peek && focus.controlsHeld) openRow() else return false
            else -> return onHiddenKey(key)
        }
        return true
    }

    LaunchedEffect(Unit) { if (resumedAt != null) onResumedShown() }
    LaunchedEffect(toastUp) {
        if (toastUp) {
            delay(ACTION_TOAST_MS)
            toastUp = false
        }
    }
    SkipEffects(controller, skips, state.turn, segments, state.skipNote?.serial, promptInView)
    FailedCaptionsNoteEffects(failedCaptionsNote, captionsFailure, failedNoteShowing)
    val scrubPending = remember(scrub) { { scrub.targetMs != null } }
    ChromeVisibilityEffects(chrome, paused, ended, panelOpen, rowOpen, reopensOnClose = { focus.opener != null }, scrubPending = scrubPending)
    LaunchedEffect(chrome.shown) {
        withFrameNanos { } // focusability follows the recomposition
        if (!chrome.shown) scrub.drop()
        if (currentPanelOpen) return@LaunchedEffect // the panel holds focus
        // The opener can have stopped taking focus meanwhile (Chat, once the page says no chat).
        if (chrome.shown) {
            if (!focus.takeLanding().requestFocus(FocusDirection.Enter)) focus.primary.requestFocus()
        } else {
            ghost.requestFocus()
        }
    }
    LaunchedEffect(nudge) {
        if (nudge != null) {
            delay(NudgeMs)
            nudge = null
        }
    }
    // Registered after VideoMenuHost's, so each stands down while the card menu shows; the row's
    // after the chrome's, so it closes first.
    BackHandler(enabled = chrome.shown && !panels.menuOpen) { chrome.hide() }
    BackHandler(enabled = rowOpen && !panels.menuOpen) { closeRow() }
    BackHandler(enabled = skipShowing && !panels.menuOpen) {
        if (state.skipNote != null) skips.dismissNote() else skips.dismissPrompt()
    }
    BackHandler(enabled = failedNoteShowing && !panels.menuOpen) { captionsFailure?.let(failedCaptionsNote::dismiss) }

    val shown = animateFloatAsState(if (chrome.shown) 1f else 0f, KeMotion.base(), label = "chrome")
    val open = animateFloatAsState(if (rowOpen) 1f else 0f, KeMotion.move(), label = "related")
    val liftTarget = when {
        rowOpen -> RowLift
        peek -> PeekLift
        else -> 0.dp
    }
    val lift by animateDpAsState(liftTarget, KeMotion.move(), label = "lift")
    val chromeHeight = with(LocalDensity.current) { chromeHeightPx.toDp() }
    // Moves with the chrome's own lift (same 200 ms), so the lines keep their gap above it.
    val cuesLift = animateDpAsState(
        targetValue = when {
            chrome.shown -> cuesOverChrome(chromeHeight, liftTarget)
            // Above the chat's box, as above the chrome.
            chatUnderCues -> cuesOverChrome(chatCornerTop, 0.dp)
            corner != null -> CuesOverSlot
            else -> 0.dp
        },
        animationSpec = KeMotion.move(),
        label = "cuesLift",
    )
    val dim by animateFloatAsState(
        targetValue = when {
            pictureFramed -> 0f
            retry != PlayerErrorUiState.None || buffering || ended -> 0.45f
            paused -> 0.18f
            else -> 0f
        },
        animationSpec = KeMotion.base(),
        label = "dim",
    )
    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp && event.key == swallowUp) {
                    swallowUp = null
                    return@onPreviewKeyEvent true
                }
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                chrome.interacted()
                if (!onKeyDown(event.key)) return@onPreviewKeyEvent false
                // Its key-up would reach the control the chrome just focused.
                swallowUp = event.key
                true
            }
            .refocusAfterMenu(panels.menuOpen) { if (chrome.shown) focus.primary.requestFocus() else ghost.requestFocus() },
    ) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = dim }.background(DimColor))
        Box(Modifier.fillMaxSize().focusRequester(ghost).focusProperties { canFocus = !panelOpen }.focusable())
        if (chatPage) ChatOverlay(chatOverlay, state.chat, aside = chatAside, replay = replay)
        Box(Modifier.fillMaxSize().chromeScrims(shown, peek))
        Box(Modifier.fillMaxSize().relatedScrim { open.value })
        ChromeTop(
            card = state.video,
            playlist = state.playlist,
            avatarUrl = dressing?.uploaderAvatarUrl,
            badge = formatBadgeText(view.videoSize.width, view.videoSize.height, view.frameRate),
            live = live,
            liveStartedAt = dressing?.liveStartedAt,
            clock = clock,
            modifier = Modifier.graphicsLayer {
                alpha = shown.value * (1f - open.value)
                translationY = -TopStep.toPx() * open.value
            },
        )
        if (peek) {
            RelatedPeek(related, Modifier.align(Alignment.BottomStart).graphicsLayer { alpha = shown.value * (1f - open.value) })
        }
        ChromeBottom(
            card = state.video,
            avatarUrl = dressing?.uploaderAvatarUrl,
            chapters = dressing?.chapters.orEmpty(),
            segments = (segments as? SegmentLookup.Found)?.segments.orEmpty(),
            storyboard = dressing?.storyboard,
            times = times,
            scrub = scrub,
            paused = paused,
            ended = ended,
            isSubscribed = state.isSubscribed,
            captionsAvailable = !captions?.tracks.isNullOrEmpty(),
            captionsOn = captions?.chosen != null && captionsFailure == null,
            canFocus = chrome.shown && !rowOpen,
            focus = focus,
            actions = actions,
            controlsAlpha = { 1f - open.value },
            live = live,
            chatAvailable = chatAvailable,
            skippable = state.skippable?.categories?.first()?.takeUnless { ended },
            onSkip = {
                skips.skipFromControls()?.let {
                    actions.onSeekTo(it)
                    // The note shows only with the chrome away (the corner's rule).
                    chrome.hide()
                }
            },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .onSizeChanged { chromeHeightPx = it.height }
                .graphicsLayer {
                    alpha = shown.value
                    translationY = ChromeDrop.toPx() * (1f - shown.value) - lift.toPx()
                },
        )
        if (peek) {
            CardRoomLight(cardLight, lit = rowOpen)
            AnimatedVisibility(
                visible = rowOpen,
                modifier = Modifier.align(Alignment.BottomStart),
                enter = fadeIn(tween(KeMotion.MOVE_MS, easing = KeMotion.EaseOut)) +
                    slideInVertically(tween(KeMotion.MOVE_MS, easing = KeMotion.EaseOut)) { rowRisePx },
                exit = fadeOut(KeMotion.fast()),
            ) {
                RelatedRow(
                    videos = related,
                    marks = panels.marks,
                    onPlay = panels.onPlay,
                    onOpenMenu = panels.onOpenMenu,
                    onCardFocused = cardLight::onCardFocused,
                    modifier = panels.rowModifier.focusRequester(rowFocus).padding(bottom = KeLayout.SafeVertical),
                )
            }
        }
        CaptionCuesLayer(
            player = controller,
            cues = captions?.cues.takeIf { !pictureFramed },
            lift = { cuesLift.value },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
        when {
            pictureFramed -> Unit
            retry != PlayerErrorUiState.None -> RetryPill(retry, Modifier.align(Alignment.Center))
            buffering && live -> LiveBufferingNote(view, { progress.currentPositionMs }, { progress.durationMs }, Modifier.align(Alignment.Center))
            buffering -> BufferingNote(bufferingLabel(view), Modifier.align(Alignment.Center))
        }
        nudge?.let {
            SeekNudge(
                forward = it.forward,
                positionMs = it.positionMs,
                durationMs = controller.duration,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = NudgeBottom),
                live = live,
                rejoinsLive = it.rejoins,
            )
        }
        resumedAt?.let { at ->
            Toast(
                visible = toastUp,
                icon = R.drawable.ic_later,
                text = "Resumed at ${timeText(at)}",
                muted = null,
                modifier = Modifier.align(Alignment.BottomEnd),
            ) { StartOverHint() }
        }
        SkipSlot(
            prompt = state.skipPrompt.takeIf { skipShowing },
            note = state.skipNote.takeIf { skipShowing },
            positionMs = { progress.currentPositionMs },
            modifier = Modifier.align(Alignment.BottomEnd),
        )
        captions?.chosen?.let { track ->
            FailedCaptionsToast(visible = failedNoteShowing, track = track, modifier = Modifier.align(Alignment.BottomEnd))
        }
    }
}

/** A live stream buffering: "Catching up to live" at the edge (`live-tv-player` live-buffering),
 *  else as a video's. Its own scope, so the position it reads recomposes it alone. */
@Composable
private fun LiveBufferingNote(view: PlaybackView, positionMs: () -> Long, durationMs: () -> Long, modifier: Modifier = Modifier) {
    val behind = PlayerChromePolicy.isBehindLive(positionMs(), durationMs())
    BufferingNote(if (behind) bufferingLabel(view) else "Catching up to live", modifier)
}

/** "Loading 2160p" once the picture's size is known, else "Loading". */
private fun bufferingLabel(view: PlaybackView): String =
    formatBadgeText(view.videoSize.width, view.videoSize.height, frameRate = null)?.let { "Loading $it" } ?: "Loading"
