package com.arshad.ayn.ui.mobile.player

import android.os.Bundle
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.movableContentWithReceiverOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.modifiers.resizeWithContentScale
import androidx.media3.ui.compose.state.rememberPresentationState
import androidx.media3.ui.compose.state.rememberProgressStateWithTickInterval
import com.arshad.ayn.core.access.SegmentLookup
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.filter.shows
import com.arshad.ayn.core.store.ChatPlacement
import com.arshad.ayn.core.store.RestrictedMode
import com.arshad.ayn.feature.format.liveWatchingText
import com.arshad.ayn.feature.format.metaLine
import com.arshad.ayn.feature.format.subscribersText
import com.arshad.ayn.feature.library.VideoSource
import com.arshad.ayn.feature.library.WatchMark
import com.arshad.ayn.feature.nav.ChannelRoute
import com.arshad.ayn.feature.player.BehindLiveLatch
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
import com.arshad.ayn.feature.player.SegmentSkips
import com.arshad.ayn.feature.player.awaitStarted
import com.arshad.ayn.feature.player.chapterAt
import com.arshad.ayn.feature.player.chapterIndexAt
import com.arshad.ayn.feature.player.chatReplayKicker
import com.arshad.ayn.feature.player.followChat
import com.arshad.ayn.feature.player.commentCountText
import com.arshad.ayn.feature.player.formatBadgeText
import com.arshad.ayn.feature.player.liveChatOffered
import com.arshad.ayn.feature.player.overPicture
import com.arshad.ayn.feature.player.pauseWhileAway
import com.arshad.ayn.feature.player.requestTurn
import com.arshad.ayn.feature.player.retryFrom
import com.arshad.ayn.feature.player.retryLine
import com.arshad.ayn.feature.player.stopFrom
import com.arshad.ayn.feature.player.timeText
import com.arshad.ayn.feature.settings.captionsLabel
import com.arshad.ayn.feature.settings.speedLabel
import com.arshad.ayn.ui.mobile.common.LocalContentFilter
import com.arshad.ayn.ui.mobile.common.LocalDeviceSettings
import com.arshad.ayn.ui.mobile.common.rememberTimeout
import com.arshad.ayn.ui.mobile.home.rememberPulse
import com.arshad.ayn.ui.mobile.menu.VideoMenuHost
import com.arshad.ayn.ui.mobile.search.ResultSkeleton
import com.arshad.ayn.ui.mobile.shell.ChannelKey
import com.arshad.ayn.ui.mobile.shell.ShellChrome
import com.arshad.ayn.ui.mobile.theme.AynColors
import com.arshad.ayn.ui.mobile.theme.AynMotion
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The video picture's node. */
internal const val PICTURE_TAG = "watch-picture"

private const val DOCK_MS = 280
private const val BESIDE_MS = 200
private const val DOCK_AT = 0.25f
private const val DOCK_FLING = 1_200f
private const val RIPPLE_MS = 700L
private const val NOTE_MS = 5_000L
private val UpNextPicture = 160.dp

/**
 * The watch page (`m6-player` 1–8, 11–12, 26–35; M6-D16) over the shell while [slot] is open.
 * Docked it moves off screen but stays composed (its surface and its turn's state, M3-UI-01) while
 * the mini player shows. Shown, it turns with the phone ([WatchTurning]; full screen and exit hold
 * a way, Q39): sideways the picture fills the screen. A related video picked in Up next or its card
 * menu plays in place ([PlayerViewModel.play]); a channel opens on the shown tab with the page
 * docked. A kids profile's page has no Subscribe, no Comments and no card menus (KP-D11), and its
 * live card stops before anything is asked (LIVE-D13). A live stream plays under the live chrome
 * (`live-phone-player`, LIVE-D23), its ages on [clock].
 */
@Composable
internal fun WatchPage(open: OpenPlayer, slot: PlayerSlot, chrome: ShellChrome, clock: Clock, onClose: () -> Unit) {
    val viewModel = open.viewModel
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Read only by the rows: a position save re-emits it every 10 s.
    val marks = viewModel.marks.collectAsStateWithLifecycle()
    val menuOpen = open.menu.menu.collectAsStateWithLifecycle().value != null
    var hold by rememberSaveable { mutableStateOf<Hold?>(null) }
    LaunchedEffect(slot.expanded) { if (!slot.expanded) hold = null }
    WatchTurning(slot.expanded, hold) { from -> if (hold == from) hold = null }
    val page = remember(slot, chrome, onClose) {
        WatchCalls(
            dock = slot::dock,
            close = onClose,
            openChannel = { url ->
                slot.dock()
                chrome.nav.open(ChannelKey(ChannelRoute(url)))
            },
            kids = chrome.profile.isKids,
            fullScreen = { hold = Hold.Landscape },
            exit = { hold = Hold.Portrait },
        )
    }
    val wide = isLandscape()
    // "Play from the start", held above the connection (a reconnect mustn't re-arm it) and saved
    // until the video has played.
    var startingOver by rememberSaveable(state.video.id.value, state.turn) { mutableStateOf(state.startOver) }
    DockFrame(slot) { handle ->
        VideoMenuHost(open.menu, onPlay = viewModel::play, onOpenChannel = page.openChannel) { openMenu ->
            val backEnabled = slot.expanded && !menuOpen
            val connection = open.connection
            val controller = connection.controller
            when {
                page.kids && state.video.isLive -> Stopped(state, liveStopCopy(LiveStop.Kids, state.video), onRetry = null, page, backEnabled, wide)
                connection.isDown -> Stopped(state, PlayerDownCopy, onRetry = open.reconnect, page, backEnabled, wide)
                controller == null -> {
                    BackHandler(enabled = backEnabled, onBack = if (wide) page.exit else page.dock)
                    WatchColumn(handle, wide, picture = { OpeningCover(state.video.thumbnailUrl) }) { openingInfo(state, upNext = true) }
                }
                else -> Playback(
                    controller = controller,
                    sessionExtras = connection.sessionExtras,
                    state = state,
                    viewModel = viewModel,
                    marks = marks,
                    page = page,
                    handle = handle,
                    expanded = slot.expanded,
                    wide = wide,
                    backEnabled = backEnabled,
                    startingOver = startingOver,
                    onStarted = { startingOver = false },
                    onSave = { open.menu.openSaveTo(state.video) },
                    // M6-D5, D9: a phone plays on away, unless turned off or a kids profile's.
                    playsAway = LocalDeviceSettings.current.backgroundAudio && !page.kids,
                    onOpenMenu = if (page.kids) null else { video -> openMenu(video, VideoSource.Elsewhere) },
                    clock = clock,
                )
            }
        }
    }
}

/** What the page's parts do beyond the player: dock, close, a channel, whose page it is, and full
 *  screen and exit (holding landscape or portrait). */
private class WatchCalls(
    val dock: () -> Unit,
    val close: () -> Unit,
    val openChannel: (String) -> Unit,
    val kids: Boolean,
    val fullScreen: () -> Unit,
    val exit: () -> Unit,
)

/**
 * The page's place (`m6-player` 35): full screen while [slot] is expanded, off screen below while
 * docked, moving between with the shell dimmed behind it. [content]'s `handle` (the picture) drags
 * it down: let go past a quarter, or flung, it docks; else it springs back.
 */
@Composable
private fun DockFrame(slot: PlayerSlot, content: @Composable (handle: Modifier) -> Unit) {
    val expanded = slot.expanded
    // 0 = the page fills the screen, 1 = docked off screen; a new opening rises from below.
    val docked = remember { Animatable(1f) }
    var height by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(expanded) { docked.animateTo(if (expanded) 0f else 1f, tween(DOCK_MS, easing = AynMotion.Ease)) }
    val hidden by remember { derivedStateOf { !slot.expanded && docked.value >= 1f } }
    val settled by remember { derivedStateOf { docked.value <= 0f } }
    val drag = rememberDraggableState { delta ->
        if (height > 0) scope.launch { docked.snapTo((docked.value + delta / height).coerceIn(0f, 1f)) }
    }
    val handle = Modifier.draggable(
        drag,
        Orientation.Vertical,
        enabled = expanded,
        onDragStopped = { velocity ->
            // Queued behind the drag's pending snaps, so it decides on where the page really is.
            scope.launch {
                if (docked.value > DOCK_AT || velocity > DOCK_FLING) slot.dock() else docked.animateTo(0f, tween(DOCK_MS, easing = AynMotion.Ease))
            }
        },
    )
    if (!hidden && !settled) Box(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - docked.value }.background(Shade.copy(alpha = 0.55f)))
    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { height = it.height }
            .graphicsLayer {
                val p = docked.value
                translationY = p * size.height
                val scale = 1f - 0.06f * p
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0.5f, 0f)
                shape = RoundedCornerShape(26.dp)
                clip = p > 0f
            }
            .then(if (hidden) Modifier.clearAndSetSemantics { } else Modifier)
            // Its empty parts still take touches: the shell under it gets none.
            .pointerInput(Unit) {}
            .background(AynColors.Ground),
    ) { content(handle) }
}

/**
 * The page's column: black under the status bar, the picture's box ([handle] drags it), the info
 * list, and [over] it whatever covers the info (a panel; [listCovered] keeps the list from screen
 * readers meanwhile). Sideways ([wide], `m6-player` 29–34) the picture fills the screen and [over]
 * opens beside it: while that is open ([besideOpen]) the picture scales into the free side, whole.
 * A turn moves [picture] and [over] rather than rebuilding them, so the surface keeps playing and
 * their state (the chrome, a note showing, an open panel's scroll) carries over.
 */
@Composable
private fun WatchColumn(
    handle: Modifier?,
    wide: Boolean,
    picture: @Composable BoxScope.() -> Unit,
    listState: LazyListState = rememberLazyListState(),
    listCovered: Boolean = false,
    besideOpen: Boolean = false,
    over: @Composable BoxScope.() -> Unit = {},
    list: LazyListScope.() -> Unit,
) {
    val currentPicture by rememberUpdatedState(picture)
    val currentOver by rememberUpdatedState(over)
    val movingPicture = remember { movableContentWithReceiverOf<BoxScope> { currentPicture() } }
    val movingOver = remember { movableContentWithReceiverOf<BoxScope> { currentOver() } }
    if (wide) {
        val aside by animateFloatAsState(if (besideOpen) 1f else 0f, tween(BESIDE_MS, easing = AynMotion.Ease), label = "aside")
        val besidePx = with(LocalDensity.current) { BesideWidth.toPx() }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    val scale = 1f - aside * (besidePx / size.width).coerceAtMost(1f)
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0f, 0.5f)
                },
            ) { movingPicture() }
            movingOver()
        }
        return
    }
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp
    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.fillMaxWidth().background(Color.Black).windowInsetsTopHeight(WindowInsets.statusBars))
        PictureBox(handle ?: Modifier) { movingPicture() }
        Box(Modifier.weight(1f)) {
            LazyColumn(
                Modifier.fillMaxSize().then(if (listCovered) Modifier.clearAndSetSemantics { } else Modifier),
                state = listState,
                contentPadding = PaddingValues(bottom = bottom),
                content = list,
            )
            movingOver()
        }
    }
}

/** The info while nothing plays yet, or a statement shows: the title and the channel's name (a
 *  stream's audience too), with Up next's shape while the video starts ([upNext]). */
private fun LazyListScope.openingInfo(state: PlayerUiState, upNext: Boolean) {
    val video = state.video
    val meta = metaLine(video.uploaderName, video.liveWatchingText()).ifEmpty { null }
    item(key = "header") { WatchHeader(video, state.playlist?.let { Lead.Playlist(it) }, meta, channel = null, chips = null) }
    if (upNext) {
        item(key = "upnext") { UpNextHeading() }
        item(key = "skeleton") {
            val pulse = rememberPulse()
            Column { repeat(5) { ResultSkeleton(pulse, Modifier.padding(bottom = 12.dp), pictureWidth = UpNextPicture) } }
        }
    }
}

/** A statement in the picture's box (`m6-player` 26–28): ⌄ or Back closes the player. */
@Composable
private fun Stopped(state: PlayerUiState, copy: StopCopy, onRetry: (() -> Unit)?, page: WatchCalls, backEnabled: Boolean, wide: Boolean) {
    BackHandler(enabled = backEnabled, onBack = page.close)
    WatchColumn(handle = null, wide = wide, picture = { PictureStatement(copy, state.video.thumbnailUrl, onRetry, page.close) }) { openingInfo(state, upNext = false) }
}

/**
 * The video playing, asked for and paused while away by the rules both players share
 * ([requestTurn], [pauseWhileAway]). Until it plays the card shows; a stop in the session extras
 * becomes its statement; an end goes to [PlayerViewModel.ended] (Play all moves on, a single video
 * gets the end card). The turn's sheets close when the stage goes or the page docks; its panel
 * (Details, Chapters, Comments; sideways Up next too) when the stage goes. Opening Details asks for
 * the votes, opening Comments for page 1, once per turn. Sideways ([wide]) the panels and the
 * sheets' stages open beside the picture and Back returns to portrait. A live stream (the page's
 * word, the card's until then) has no Comments, chapters or SponsorBlock; Chat opens in Comments'
 * place, and sideways the stream's view puts chat over the picture instead, its chat polling only
 * while either shows on the shown page (LIVE-D27); the Chat sheet (the chrome's Chat over that view,
 * or the panel's View) moves or hides it for this stream (LIVE-D26); its end is a statement over the
 * Up next rows, which Back closes (Q21). An ended stream whose chat YouTube kept plays as a video
 * with Chat right after Comments, its chat a replay that follows the play position (LIVE-D7, D28).
 */
@OptIn(UnstableApi::class)
@Composable
private fun Playback(
    controller: MediaController,
    sessionExtras: Bundle,
    state: PlayerUiState,
    viewModel: PlayerViewModel,
    marks: State<Map<VideoId, WatchMark>>,
    page: WatchCalls,
    handle: Modifier,
    expanded: Boolean,
    wide: Boolean,
    backEnabled: Boolean,
    startingOver: Boolean,
    onStarted: () -> Unit,
    onSave: () -> Unit,
    playsAway: Boolean,
    onOpenMenu: ((VideoItem) -> Unit)?,
    clock: Clock,
) {
    val videoId = state.video.id.value
    val turn = state.turn
    val view = rememberPlaybackView(controller, videoId)
    var attempt by remember { mutableIntStateOf(0) }
    // Try again's count when this turn began: only the turn's first request shows the note.
    val turnAttempt = remember(videoId, turn) { attempt }
    var started by remember(videoId, turn, attempt) { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val ours = view.carries(videoId)
    val dressing = remember(view.dressing, ours) { view.dressing.takeIf { ours }?.forScreen() }
    // The resolved page's word, the card's until then.
    val live = dressing?.live ?: state.video.isLive
    val commentsOn = !page.kids && !live
    // An ended stream whose chat YouTube kept (LIVE-D28); a page without one has no Chat.
    val replay = dressing?.liveChat?.replay == true
    // A kids profile's live stream stops before it plays (LIVE-D13); chat stays off for one all the same.
    val chatOn = !page.kids && (live || replay)
    val chatOffered = liveChatOffered(dressing)
    val chatCalls = remember(viewModel) {
        ChatCalls(viewModel::openChat, viewModel::chatShown, viewModel::chooseChatView, viewModel::retryChat, viewModel::chatTick)
    }
    // The resolve names the channel (a search card may not) and lists the caption tracks.
    LaunchedEffect(dressing, turn) {
        dressing?.let {
            viewModel.channelResolved(turn, it.uploaderName, it.uploaderUrl)
            viewModel.captionsResolved(turn, it.captionTracks)
        }
    }
    // Lazy keys must be unique and YouTube's list can repeat a video; a block takes cards out at once (KP-D19).
    val contentFilter = LocalContentFilter.current
    val related = remember(dressing, contentFilter) { dressing?.relatedVideos.orEmpty().distinctBy { it.id }.filter { contentFilter.shows(it) } }
    // YouTube's refusal can't be told from Restricted Mode's, so under Strict the statement names both.
    val restricted = contentFilter?.restrictedMode == RestrictedMode.Strict
    var resumedAtMs by remember(videoId, turn) { mutableStateOf<Long?>(null) }
    var endDismissed by remember(videoId, turn) { mutableStateOf(false) }
    val sheets = remember(videoId, turn) { SheetStack() }
    // Per turn, so a stage back after a stop's Try again doesn't bring back a note already gone.
    var captionsNoteGone by remember(videoId, turn) { mutableStateOf<CaptionCues.Failed?>(null) }

    val asked = remember { AskedTurn() }
    LaunchedEffect(controller, videoId, turn, attempt) {
        // Asked for while Ayn was away: live as it was left there, playing, paused or ended.
        if (attempt == turnAttempt && asked.isAsked(controller, turn)) return@LaunchedEffect
        asked.mark(controller, turn)
        controller.requestTurn(
            lifecycle,
            state.video.id,
            short = state.shorts != null,
            startingOver = startingOver,
            afresh = attempt > 0,
            resumePointMs = viewModel::resumePointMs,
            startSpeedPercent = viewModel::startSpeedPercent,
        ) { resumeMs ->
            resumedAtMs = resumeMs?.takeIf { attempt == turnAttempt && it > 0 }
        }
        controller.play()
    }
    LaunchedEffect(view, videoId, turn, attempt) {
        view.awaitStarted(videoId)
        started = true
        viewModel.playbackStarted(turn)
        onStarted()
    }
    val currentStarted by rememberUpdatedState(started)
    val currentMediaId by rememberUpdatedState(view.mediaId)
    val currentPlaysAway by rememberUpdatedState(playsAway)
    DisposableEffect(lifecycle, controller, videoId) {
        val observer = pauseWhileAway(
            controller::pause,
            controller::play,
            startPending = { !currentStarted && currentMediaId == videoId },
            playsAway = { currentPlaysAway },
        )
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    // Not keyed by the turn: away nothing recomposes, so it carries Play all on by itself.
    LaunchedEffect(lifecycle, controller, view, viewModel, playsAway) {
        if (playsAway) playAway(lifecycle, controller, view, viewModel, asked)
    }
    // Gated on this turn's start: right after a new turn the previous turn's item is still ended.
    val ended = started && ours && view.playbackState == Player.STATE_ENDED
    LaunchedEffect(ended, videoId, turn) { if (ended) viewModel.ended(turn) else endDismissed = false }
    // An ended live stream says so; it never offers the end card (Q21).
    val liveEnded = ended && live
    val endedAt = remember(liveEnded) { if (liveEnded) clock.now() else null }
    val upNext = ended && !live && state.atEnd && related.isNotEmpty() && !endDismissed
    val localView = LocalView.current
    DisposableEffect(view.isPlaying) {
        localView.keepScreenOn = view.isPlaying
        onDispose { localView.keepScreenOn = false }
    }
    val stop = stopFrom(sessionExtras)
    val retry = retryFrom(sessionExtras)
    // A statement holds the picture: Back closes the player and the picture drags nothing.
    val stopped = stop != null || liveEnded
    BackHandler(
        enabled = backEnabled,
        onBack = when {
            stopped -> page.close
            wide -> page.exit
            else -> page.dock
        },
    )
    var panel by rememberSaveable(videoId, turn) { mutableStateOf<WatchPanel?>(null) }
    // Set while a Chat panel is away for chat over the sideways picture (below).
    var chatGaveWay by rememberSaveable(videoId, turn) { mutableStateOf(false) }
    // The Chat panel opened, or closed for good: either way no panel waits to come back upright.
    fun openChatPanel() {
        panel = WatchPanel.Chat
        chatGaveWay = false
    }
    fun dropChatPanel() {
        if (panel == WatchPanel.Chat) panel = null
        chatGaveWay = false
    }
    LaunchedEffect(stop != null) {
        if (stop != null) {
            panel = null
            chatGaveWay = false
        }
    }
    // Up next is a panel only beside the sideways picture (upright its rows are the page's), and
    // only while there is something next.
    LaunchedEffect(wide, related.isEmpty()) { if ((!wide || related.isEmpty()) && panel == WatchPanel.UpNext) panel = null }
    // A resolved page that turns out to have no chat (not live, no kept replay) closes it; before it
    // resolves (a recreated activity's restored panel) a replay can't be told yet.
    val resolved = dressing != null
    LaunchedEffect(chatOn, resolved) {
        if (resolved && !chatOn) {
            if (panel == WatchPanel.Chat) panel = null
            if (sheets.top == PlayerSheet.Chat) sheets.closeAll()
        }
    }
    // The stream's view over the picture applies sideways (LIVE-D27): an open Chat panel gives way
    // to it as the phone turns, and comes back under the picture as it turns upright.
    val chatView = state.chatPlace.overlay.takeIf { chatOn }
    val chatOver = wide && overPicture(chatView, state.chat) != null
    LaunchedEffect(chatOver, wide) {
        if (chatOver && panel == WatchPanel.Chat) {
            panel = null
            chatGaveWay = true
        } else if (!wide && chatGaveWay) {
            if (chatOn) panel = WatchPanel.Chat
            chatGaveWay = false
        }
    }
    // Chat in the chrome or the Chat chip (LIVE-D26): over a view on the sideways picture it opens
    // the Chat sheet; otherwise chat shows in the stream's view (a hidden one again), the panel
    // under the picture upright and beside it for Beside.
    val openChat: () -> Unit = {
        if (wide && chatView != null) {
            sheets.open(PlayerSheet.Chat)
        } else {
            viewModel.showChat()
            if (!wide || state.chatPlace.placement == ChatPlacement.Beside) openChatPanel()
        }
    }
    val commentsList = remember(videoId, turn) { LazyListState() }

    val scope = rememberCoroutineScope()
    val progress = rememberProgressStateWithTickInterval(controller, PlayerChromePolicy.POSITION_TICK_MS, scope)
    // A turn's pending seek never lands on the next turn's video.
    val seeks = remember(controller, videoId, turn) { SeekGathering(controller, scope) }
    DisposableEffect(seeks) { onDispose { seeks.cancel() } }
    val times = remember(progress, seeks) {
        PlayTimes({ seeks.targetMs ?: progress.currentPositionMs }, { progress.bufferedPositionMs }, { progress.durationMs })
    }
    val presentation = rememberPresentationState(controller)
    val listState = rememberSaveable(turn, saver = LazyListState.Saver) { LazyListState() }
    val showing = !stopped && started
    LaunchedEffect(showing, expanded) { if (!showing || !expanded) sheets.closeAll() }
    // It polls while its panel is open on the shown page (not docked, nor under a statement), a
    // sheet beside the sideways picture covering it included (as on TV, so the list is kept), and
    // sideways while the stream's view is over the picture, stepped aside too (and while the panel
    // that gave way comes back, so turning upright doesn't restart it).
    val chatShown = panel == WatchPanel.Chat || chatGaveWay || (wide && chatView != null)
    val chatOnScreen = chatOn && showing && expanded && chatShown
    ChatShownEffect(chatOnScreen, turn, dressing, chatCalls)
    if (replay && chatOnScreen) {
        LaunchedEffect(controller, turn, chatCalls) { controller.followChat { at, playing -> chatCalls.tick(turn, at, playing) } }
    }
    val chapters = dressing?.chapters.orEmpty()
    // The chapter line and the lit row change per chapter, not per position tick.
    val chapterPlaying by remember(chapters, times) { derivedStateOf { chapterIndexAt(chapters, times.positionMs()) } }
    val chapterCalls = remember(chapters, times, seeks) {
        ChapterCalls(chapters, playing = { chapterPlaying }) { chapter ->
            seeks.seekTo(chapter.startSeconds * 1000)
            panel = null
        }
    }
    val channelName = state.video.uploaderName ?: dressing?.uploaderName
    val locale = LocalConfiguration.current.locales[0]
    val chips = WatchChips(
        onSave = onSave,
        comments = state.comments?.takeUnless { it.off }?.totalCount?.let { commentCountText(it, locale) },
        onComments = if (commentsOn) { { panel = WatchPanel.Comments } } else null,
        onChat = if (chatOn) openChat else null,
        chatOffered = chatOffered,
        chapters = chapters.size,
        onChapters = { panel = WatchPanel.Chapters },
    )
    val besideOpen = wide && showing && (panel != null || sheets.top != null)
    val upNextCalls = UpNextCalls(related, marks, viewModel::play, onOpenMenu)
    val chatSheet = if (chatOn) {
        ChatSheetCalls(
            kicker = if (replay) chatReplayKicker(state.video) else metaLine(channelName, state.video.liveWatchingText()),
            chat = state.chat,
            placement = state.chatPlace.placement,
            chooseView = viewModel::chooseChatView,
            choosePlacement = { placement ->
                viewModel.chooseChatPlacement(placement)
                if (wide && placement == ChatPlacement.Beside) openChatPanel()
            },
            hide = {
                viewModel.hideChat()
                dropChatPanel()
            },
        )
    } else {
        null
    }
    val sheetCalls = SheetCalls(
        controller,
        view,
        state.captions,
        dressing?.segments,
        live,
        onChooseCaptions = viewModel::chooseCaptions,
        chat = chatSheet,
        onOpenDetails = { panel = WatchPanel.Details },
    )
    WatchColumn(
        handle = handle.takeIf { !stopped && !wide },
        wide = wide,
        listState = listState,
        listCovered = showing && panel != null,
        besideOpen = besideOpen,
        over = {
            if (showing) {
                TurnPanels(
                    panel = panel,
                    beside = wide,
                    sheet = sheets.top,
                    sheetStage = { SheetBeside(it, sheets, sheetCalls) },
                    upNext = upNextCalls,
                    viewModel = viewModel,
                    state = state,
                    dressing = dressing,
                    view = view,
                    chapters = chapterCalls,
                    channel = CommentsChannel(channelName, dressing?.uploaderAvatarUrl),
                    commentsList = commentsList,
                    chatCalls = chatCalls,
                    replay = replay,
                    onChatView = { sheets.open(PlayerSheet.Chat) },
                    backEnabled = backEnabled,
                    onClose = { panel = null },
                )
            }
        },
        picture = {
            // M3-UI-01: the surface stays composed under every layer.
            PlayerSurface(
                player = controller,
                modifier = Modifier.align(Alignment.Center).resizeWithContentScale(ContentScale.Fit, presentation.videoSizeDp).testTag(PICTURE_TAG),
            )
            when {
                stop != null -> PictureStatement(
                    copy = if (stop.reason == PlayerPolicy.TERMINAL_LIVE) {
                        liveRefusalCopy(page.kids, state.video)
                    } else {
                        stopCopy(stop.reason, stop.positionMs, stop.detail, restricted)
                    },
                    picture = state.video.thumbnailUrl,
                    onRetry = { attempt++ },
                    onClose = page.close,
                )
                !started -> OpeningCover(state.video.thumbnailUrl)
                liveEnded -> PictureStatement(liveStopCopy(LiveStop.Ended, state.video), state.video.thumbnailUrl, onRetry = null, onClose = page.close)
                else -> Stage(
                    controller = controller,
                    expanded = expanded,
                    view = view,
                    state = state,
                    viewModel = viewModel,
                    dressing = dressing,
                    times = times,
                    seeks = seeks,
                    sheets = sheets,
                    ended = ended,
                    buffering = view.playbackState == Player.STATE_BUFFERING,
                    retry = retry,
                    captionsNoteGone = captionsNoteGone,
                    onCaptionsNoteGone = { captionsNoteGone = it },
                    resumedAtMs = resumedAtMs,
                    onResumedShown = { resumedAtMs = null },
                    endCard = related.firstOrNull()?.takeIf { upNext },
                    onPlayNext = viewModel::play,
                    onCancelNext = { endDismissed = true },
                    onDock = page.dock,
                    onFullScreen = page.fullScreen,
                    sideways = if (wide) {
                        val detail = if (live) state.video.liveWatchingText() else dressing?.subscriberCount?.let(::subscribersText)
                        Sideways(
                            besideOpen = besideOpen,
                            channel = LandChannel(channelName, dressing?.uploaderAvatarUrl, detail),
                            onPanel = { panel = it },
                            onChat = openChat,
                            hasUpNext = related.isNotEmpty(),
                            hasComments = commentsOn,
                            hasChat = chatOn,
                            chatReplay = replay,
                            chatOffered = chatOffered,
                            chatView = chatView,
                            onExit = page.exit,
                        )
                    } else {
                        null
                    },
                    live = live,
                    clock = clock,
                )
            }
        },
    ) {
        when {
            liveEnded -> endedInfo(state, endedMeta(channelName, dressing?.liveStartedAt, endedAt), related, upNextCalls)
            !showing -> openingInfo(state, upNext = stop == null)
            else -> playingInfo(state, dressing, live, clock, channelName, { chapterPlaying }, related.drop(if (upNext) 1 else 0), upNextCalls, viewModel, page, chips) {
                panel = WatchPanel.Details
            }
        }
    }
    if (!wide) PlayerSheets(sheets, sheetCalls)
}

/** The info once the video plays (`m6-player` 2, 11, 12): lead (Play all's place, else LIVE on a
 *  [live] stream, else the [chapterPlaying]), title, meta ([onMore]: Video details; a live stream's
 *  ages on [clock]), channel, [chips], Up next. */
private fun LazyListScope.playingInfo(
    state: PlayerUiState,
    dressing: PlaybackDressing?,
    live: Boolean,
    clock: Clock,
    channelName: String?,
    chapterPlaying: () -> Int,
    rows: List<VideoItem>,
    upNext: UpNextCalls,
    viewModel: PlayerViewModel,
    page: WatchCalls,
    chips: WatchChips,
    onMore: () -> Unit,
) {
    val video = state.video
    item(key = "header") {
        val chapter = chapterPlaying()
        val lead = state.playlist?.let { Lead.Playlist(it) }
            ?: Lead.Live.takeIf { live }
            ?: dressing?.chapters?.getOrNull(chapter)?.let { Lead.Chapter(chapter + 1, it.title) }
        val url = video.uploaderUrl
        val channel = ChannelLine(
            name = channelName.orEmpty(),
            avatarUrl = dressing?.uploaderAvatarUrl,
            subscribers = dressing?.subscriberCount,
            subscribed = state.isSubscribed,
            onOpen = url?.let { { page.openChannel(it) } },
            onToggleSubscribe = if (page.kids || url == null) null else { { viewModel.toggleSubscribe(dressing?.uploaderAvatarUrl) } },
        )
        val meta = if (live) liveMeta(video, dressing?.liveStartedAt, rememberMinuteNow(clock)) else watchMeta(video, dressing?.likeCount)
        WatchHeader(video, lead, meta, channel, chips, onMore)
    }
    upNextSection(rows, upNext)
}

/** An ended live stream's info under its statement (`live-phone-player` 10): the title, [meta]
 *  (the channel and how long it streamed), and Up next. */
private fun LazyListScope.endedInfo(state: PlayerUiState, meta: String?, rows: List<VideoItem>, upNext: UpNextCalls) {
    item(key = "header") { WatchHeader(state.video, lead = null, meta, channel = null, chips = null) }
    upNextSection(rows, upNext)
}

/** "Up next" over [rows], when there are any. */
private fun LazyListScope.upNextSection(rows: List<VideoItem>, upNext: UpNextCalls) {
    if (rows.isEmpty()) return
    item(key = "upnext") { UpNextHeading() }
    upNextRows(rows, upNext, UpNextPicture)
}

/** What the picture needs sideways: whether something stands beside it, the channel the chrome
 *  names, opening a panel, the chrome's Chat ([onChat]), whether Up next has rows, Comments shows
 *  and Chat shows (an ended stream's kept chat when [chatReplay]; and whether it leads anywhere),
 *  the stream's chat view over the picture, and exit. */
private class Sideways(
    val besideOpen: Boolean,
    val channel: LandChannel,
    val onPanel: (WatchPanel) -> Unit,
    val onChat: () -> Unit,
    val hasUpNext: Boolean,
    val hasComments: Boolean,
    val hasChat: Boolean,
    val chatReplay: Boolean,
    val chatOffered: Boolean,
    val chatView: ChatPlacement?,
    val onExit: () -> Unit,
)

/** One side's double-tap seeking: which way, how far so far, and whether the gathered seek rejoins a
 *  live edge (a new object per tap restarts its time). */
private class Ripple(val forward: Boolean, val seconds: Int, val rejoinsLive: Boolean)

private fun signedStep(forward: Boolean): Long = if (forward) PlayerChromePolicy.SEEK_STEP_MS else -PlayerChromePolicy.SEEK_STEP_MS

/**
 * The playing video's picture (`m6-player` 2–8, 11, 13, 17–20): a tap shows or hides the chrome,
 * which recedes after [PlayerChromePolicy.HIDE_AFTER_IDLE_MS] while playing and stays while paused,
 * ended or under a sheet. Double-tap a side seeks 10 s that way, each further tap on it while its
 * ripple shows another 10 (gathered into one seek, Q28). Dragging the bar scrubs with a preview.
 * The chrome's CC and ⚙ open the [sheets]. The chosen captions' cues show over the picture, risen
 * above the chrome or whatever holds the bottom corner ([Corner]): a retry under way, the resumed
 * note (Start over), SponsorBlock's skip note (Undo) or prompt (Skip to, M7-D10; its skips run on
 * their own tick), then the note for captions that didn't load (Try again). The notes go after
 * 5 s (longer when accessibility settings ask), counted only while the corner is in view (the
 * page [expanded], nothing over the corner; a screen reader's chrome, which stays until hidden,
 * has the corner shown over it, Q34); a skip note still held back 8 s (scaled the same way) past
 * its span's end goes (Q37). A single video's end shows [endCard].
 * Upright the chrome's full screen holds landscape ([onFullScreen]); [sideways] (29–30, 34) it is
 * the TV's ([LandscapeChrome]), its pills opening what stands beside the picture: while that is
 * open the chrome stays away and a tap on the picture does nothing. A [live] stream's chrome
 * (`live-phone-player` 2–3, 13, 15; LIVE-D23) has LIVE where the times sit, and once behind live
 * (30 s, clearing under 15 s) how far and Go live; no Speed; seeks stop at the edge, and a
 * double-tap's ripple says Live where its seek rejoins it. Sideways, a stream's chat shows over the
 * picture in its view ([ChatOverlay]), stepping aside under the chrome, anything beside the picture
 * or a note in its corner, the cues rising above a bottom corner's box.
 */
@Composable
private fun BoxScope.Stage(
    controller: MediaController,
    expanded: Boolean,
    view: PlaybackView,
    state: PlayerUiState,
    viewModel: PlayerViewModel,
    dressing: PlaybackDressing?,
    times: PlayTimes,
    seeks: SeekGathering,
    sheets: SheetStack,
    ended: Boolean,
    buffering: Boolean,
    retry: PlayerErrorUiState,
    captionsNoteGone: CaptionCues.Failed?,
    onCaptionsNoteGone: (CaptionCues.Failed) -> Unit,
    resumedAtMs: Long?,
    onResumedShown: () -> Unit,
    endCard: VideoItem?,
    onPlayNext: (VideoItem) -> Unit,
    onCancelNext: () -> Unit,
    onDock: () -> Unit,
    onFullScreen: () -> Unit,
    sideways: Sideways?,
    live: Boolean,
    clock: Clock,
) {
    val wide = sideways != null
    val besideOpen = sideways?.besideOpen == true
    val paused = !view.playWhenReady
    val loading = buffering || retry != PlayerErrorUiState.None
    val sheetOpen = sheets.top != null
    var chromeShown by remember { mutableStateOf(false) }
    val chromeUp = chromeShown && !besideOpen
    val tapsToggle by rememberUpdatedState(!besideOpen)
    var touches by remember { mutableIntStateOf(0) }
    var scrubMs by remember { mutableStateOf<Long?>(null) }
    var ripple by remember { mutableStateOf<Ripple?>(null) }
    // A screen reader's user keeps the chrome until they hide it; others get the time their settings ask for.
    val context = LocalContext.current
    val exploring = remember(context) { context.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true }
    val hideAfter = rememberTimeout(PlayerChromePolicy.HIDE_AFTER_IDLE_MS, containsIcons = true, containsControls = true)
    val noteFor = rememberTimeout(NOTE_MS, containsText = true, containsControls = true)
    val promptFor = rememberTimeout(SegmentSkips.PROMPT_MS, containsText = true, containsControls = true)
    val noteKept = rememberTimeout(SegmentSkips.NOTE_KEPT_MS, containsText = true, containsControls = true)
    SideEffect { viewModel.skipTimes(promptFor, noteKept) }
    val resumedAt = remember { resumedAtMs }
    var noteUp by remember { mutableStateOf(resumedAt != null) }
    LaunchedEffect(Unit) { if (resumedAt != null) onResumedShown() }
    LaunchedEffect(noteUp) {
        if (noteUp) {
            delay(noteFor)
            noteUp = false
        }
    }
    LaunchedEffect(paused, ended) { if (paused || ended) chromeShown = true }
    val scrubbing = scrubMs != null
    LaunchedEffect(chromeShown, paused, ended, touches, scrubbing, sheetOpen) {
        if (chromeShown && !paused && !ended && !scrubbing && !sheetOpen && !exploring) {
            delay(hideAfter)
            chromeShown = false
        }
    }
    LaunchedEffect(ripple) {
        if (ripple != null) {
            delay(RIPPLE_MS)
            ripple = null
        }
    }
    val segments = dressing?.segments
    val captions = state.captions
    val failure = (captions?.cues as? CaptionCues.Failed)?.takeIf { it != captionsNoteGone }
    // Beside the sideways picture a sheet leaves the corner in view; docked, the page is off screen.
    // A screen reader's chrome stays until hidden, so the corner shows over it then (Q34).
    val cornerHidden = !expanded || (chromeUp && !exploring) || (sheetOpen && !wide) || endCard != null
    val corner = cornerOf(
        hidden = cornerHidden,
        retry = retryLine(retry),
        resumedAtMs = resumedAt?.takeIf { noteUp },
        skipNote = state.skipNote,
        skipPrompt = state.skipPrompt,
        failure = failure,
        failedLanguage = captions?.chosen?.let { captionsLabel(it.languageTag) },
    )
    CornerEffects(controller, viewModel, state.turn, segments, corner, promptInView(cornerHidden, corner), noteFor, onCaptionsNoteGone)
    // Chat over the sideways picture steps aside under the chrome, anything beside the picture, or a
    // note in its corner ([Corner.atEnd]).
    val chatAt = overPicture(sideways?.chatView, state.chat)
    val chatAside = chromeUp || besideOpen || when (corner?.atEnd) {
        null -> false
        true -> chatAt == ChatPlacement.BottomRight || chatAt == ChatPlacement.Side
        false -> chatAt == ChatPlacement.BottomLeft
    }
    val chatUnderCues = !chatAside && (chatAt == ChatPlacement.BottomLeft || chatAt == ChatPlacement.BottomRight)
    fun toggleChrome() {
        chromeShown = !chromeShown
        touches++
    }
    fun step(forward: Boolean) {
        val target = seeks.stepBy(signedStep(forward))
        val last = ripple?.takeIf { it.forward == forward }
        // As the gathered seek will land (its net direction).
        ripple = Ripple(forward, (last?.seconds ?: 0) + (PlayerChromePolicy.SEEK_STEP_MS / 1000).toInt(), seeks.rejoinsLive(target))
    }
    Box(
        Modifier
            .matchParentSize()
            .semantics {
                contentDescription = "Video"
                if (!besideOpen) {
                    onClick(label = if (chromeShown) "Hide the controls" else "Show the controls") {
                        toggleChrome()
                        true
                    }
                }
            }
            .pointerInput(Unit) {
                // A tap on a side while its ripple shows steps at once, so a run of taps gathers.
                var rippleTap = false
                detectTapGestures(
                    onPress = { at ->
                        val forward = at.x > size.width / 2
                        rippleTap = ripple?.forward == forward
                        if (rippleTap) step(forward)
                    },
                    onTap = { if (!rippleTap && tapsToggle) toggleChrome() },
                    onDoubleTap = { at -> if (!rippleTap) step(at.x > size.width / 2) },
                )
            },
    ) {
        // Inside the picture's gestures: a drag scrolls the chat, taps and double-taps still reach the picture.
        if (sideways?.hasChat == true) ChatOverlay(chatAt, state.chat, aside = chatAside, replay = sideways.chatReplay)
    }
    SeekRipple(ripple?.forward, ripple?.seconds ?: 0, ripple?.rejoinsLive == true)
    if (buffering && retry == PlayerErrorUiState.None && !chromeUp) Spinner(44.dp, Modifier.align(Alignment.Center))
    if (endCard != null) EndCard(endCard, onPlay = { onPlayNext(endCard) }, onCancel = onCancelNext, large = wide)
    // Each holder's height under its key: one coming back mid-fade reports nothing new.
    val cornerHeights = remember { mutableStateMapOf<Any, Int>() }
    val cornerTop = with(LocalDensity.current) { (corner?.let { cornerHeights[it.key()] } ?: 0).toDp() }
    // A corner over the chrome is a screen reader's (Q34). Upright nothing clears the transport above the
    // foot row (Q58), so the corner keeps its rest and the foot row gives way; sideways it rises over the foot block.
    // The corner alone decides: tied to the chrome too, the row would fade back in over it as the chrome fades out.
    val footAway = !wide && corner != null
    // The chrome's lift: the cues', and sideways the screen reader's corner's (only while exploring: a holder
    // fading out as anyone else's chrome comes up stays where it was).
    val overChrome = if (!chromeUp || footAway) 0.dp else if (wide) OverLandChrome else OverChrome
    val cornerLift = animateDpAsState(if (exploring && wide) overChrome else 0.dp, AynMotion.base(), label = "cornerLift")
    val noteLift = if (corner != null) (cornerTop - cuesRest(wide) + CuesOverCorner).coerceAtLeast(0.dp) else 0.dp
    val chatLift = if (chatUnderCues) ChatCornerTop - cuesRest(wide) + CuesOverCorner else 0.dp
    val cuesLift = animateDpAsState(
        targetValue = overChrome + maxOf(noteLift, chatLift),
        animationSpec = AynMotion.base(),
        label = "cuesLift",
    )
    CaptionCuesLayer(controller, captions?.cues.takeIf { endCard == null }, lift = { cuesLift.value }, large = wide)
    val chapters = dressing?.chapters.orEmpty()
    val hub = when {
        ended -> Hub.Replay
        loading -> Hub.Loading
        paused -> Hub.Play
        else -> Hub.Pause
    }
    val cc = when {
        captions == null || captions.tracks.isEmpty() -> Cc.None
        captions.chosen != null && captions.cues !is CaptionCues.Failed -> Cc.On
        else -> Cc.Off
    }
    val onHub: () -> Unit = {
        touches++
        when {
            ended -> {
                seeks.seekTo(0)
                controller.play()
            }
            controller.playWhenReady -> controller.pause()
            else -> controller.play()
        }
    }
    val onSeekBy: (Boolean) -> Unit = { forward ->
        touches++
        seeks.stepBy(signedStep(forward))
    }
    val chromeOn = chromeUp && endCard == null
    // Read on every tick; the chrome changes only as it flips. Once shown, behind holds down to the
    // lower bound (the hysteresis in isBehindLive).
    val latch = remember(live) { BehindLiveLatch() }
    val behind by remember(times, latch, live) { derivedStateOf { live && latch.behind(times.positionMs(), times.durationMs()) } }
    val onGoLive: () -> Unit = {
        touches++
        seeks.goLive()
    }
    val found = (segments as? SegmentLookup.Found)?.segments.orEmpty()
    val bar: @Composable (Boolean, Modifier) -> Unit = { interactive, modifier ->
        ScrubLine(
            times = times,
            chapters = chapters,
            segments = found,
            interactive = interactive,
            scrubMs = scrubMs,
            onScrub = { scrubMs = it },
            onSeekTo = {
                touches++
                seeks.seekTo(it)
            },
            live = live,
            behindLive = behind,
            modifier = modifier,
        )
    }
    val skippable = state.skippable?.categories?.first()?.takeUnless { ended }
    val onSkip: () -> Unit = {
        viewModel.skipFromControls()?.let {
            seeks.seekTo(it)
            // The note shows only with the chrome away (the corner's rule); a screen
            // reader's chrome stays, its corner showing over it (Q34).
            if (!exploring) chromeShown = false
        }
    }
    if (sideways != null) {
        LandscapeChrome(
            shown = chromeOn,
            video = state.video,
            channel = sideways.channel,
            badge = formatBadgeText(view.videoSize.width, view.videoSize.height, view.frameRate),
            hub = hub,
            cc = cc,
            speed = speedLabel((view.speed * 100).roundToInt()).takeUnless { live },
            scrubbing = scrubbing,
            skippable = skippable,
            times = {
                if (live) {
                    LiveTimes(times, behind, window = true, onGoLive = onGoLive)
                } else {
                    LandTimes(times.positionMs(), times.durationMs().coerceAtLeast(0), chapters)
                }
            },
            bar = { bar(chromeOn, Modifier) },
            calls = LandCalls(
                onDock = onDock,
                onHub = onHub,
                onSeekBy = onSeekBy,
                onComments = if (sideways.hasComments) { { sideways.onPanel(WatchPanel.Comments) } } else null,
                onChat = if (sideways.hasChat) sideways.onChat else null,
                chatOffered = sideways.chatOffered,
                onCaptions = { sheets.open(PlayerSheet.Captions) },
                onSpeed = if (live) null else { { sheets.open(PlayerSheet.Speed) } },
                onUpNext = if (sideways.hasUpNext) { { sideways.onPanel(WatchPanel.UpNext) } } else null,
                onPlayback = { sheets.open(PlayerSheet.Playback) },
                onExit = sideways.onExit,
                onSkip = onSkip,
            ),
            clock = clock,
        )
    } else {
        PictureChrome(
            shown = chromeOn,
            hub = hub,
            cc = cc,
            footShown = !footAway,
            scrubbing = scrubbing,
            times = {
                if (live) {
                    LiveTimes(times, behind, window = false, onGoLive = onGoLive)
                } else {
                    val position = times.positionMs()
                    ChromeTimes(timeText(position), timeText(times.durationMs().coerceAtLeast(0)), chapterAt(chapters, position)?.title)
                }
            },
            onDock = onDock,
            onOpenCaptions = { sheets.open(PlayerSheet.Captions) },
            onOpenPlayback = { sheets.open(PlayerSheet.Playback) },
            onHub = onHub,
            onSeekBy = onSeekBy,
            onFullScreen = onFullScreen,
            skippable = skippable,
            onSkip = onSkip,
        )
    }
    // Under the corner, so a screen reader's note at rest over the chrome takes the touches where it covers the strip.
    if (!wide) bar(chromeOn, Modifier.align(Alignment.BottomCenter))
    PictureCorner(
        corner = corner,
        lift = { cornerLift.value },
        positionMs = times.positionMs,
        calls = CornerCalls(
            startOver = {
                noteUp = false
                seeks.seekTo(0)
            },
            undo = { viewModel.undoSkip()?.let(seeks::seekTo) },
            skip = { viewModel.skipPrompted()?.let(seeks::seekTo) },
            retryCaptions = {
                failure?.let(onCaptionsNoteGone)
                viewModel.retryCaptions()
            },
        ),
        onHeight = { key, px -> cornerHeights[key] = px },
    )
    scrubMs?.let { at ->
        val duration = times.durationMs().coerceAtLeast(1)
        val place = if (wide) Modifier.align(Alignment.BottomStart).then(landSides()).padding(bottom = LandPreviewBottom) else Modifier.align(Alignment.BottomStart).padding(bottom = 22.dp)
        val behindLiveMs = PlayerChromePolicy.liveBehindMs(at, duration).takeIf { live }
        ScrubPreview(at, at.toFloat() / duration, chapters, dressing?.storyboard, behindLiveMs, place)
    }
    if (wide) {
        // Sideways the chrome carries the bar; without it a thin one runs along the foot (30).
        AnimatedVisibility(!chromeOn, Modifier.align(Alignment.BottomCenter), enter = fadeIn(AynMotion.base()), exit = fadeOut(AynMotion.base())) {
            bar(false, Modifier)
        }
    }
}
