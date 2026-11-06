package com.arshad.ayn.ui.tv.library

import com.arshad.ayn.feature.library.playlistCountText
import com.arshad.ayn.feature.library.playlistMeta
import com.arshad.ayn.feature.library.playlistsText
import com.arshad.ayn.feature.library.savedCardMeta
import com.arshad.ayn.feature.library.updatedAt
import com.arshad.ayn.feature.library.videosText
import com.arshad.ayn.feature.format.barFraction
import com.arshad.ayn.feature.format.historyCardMeta
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.feature.library.LibraryState
import com.arshad.ayn.feature.library.LibraryViewModel
import com.arshad.ayn.feature.library.VideoMenuViewModel
import com.arshad.ayn.feature.library.VideoSource
import com.arshad.ayn.feature.nav.PlayerRoute
import com.arshad.ayn.ui.tv.R
import com.arshad.ayn.ui.tv.common.CardRoomLight
import com.arshad.ayn.ui.tv.common.CardRow
import com.arshad.ayn.ui.tv.common.FocusedCardLight
import com.arshad.ayn.ui.tv.common.Kicker
import com.arshad.ayn.ui.tv.common.MoreCard
import com.arshad.ayn.ui.tv.common.Overlays
import com.arshad.ayn.ui.tv.common.PageHead
import com.arshad.ayn.ui.tv.common.PillButton
import com.arshad.ayn.ui.tv.common.PlaylistCoverCard
import com.arshad.ayn.ui.tv.common.PrefetchFaces
import com.arshad.ayn.ui.tv.common.Statement
import com.arshad.ayn.ui.tv.common.VideoCard
import com.arshad.ayn.ui.tv.common.restingRoomLight
import com.arshad.ayn.ui.tv.common.topFade
import com.arshad.ayn.ui.tv.focus.ControlStyle
import com.arshad.ayn.ui.tv.focus.FocusOnClose
import com.arshad.ayn.ui.tv.focus.ScreenFocus
import com.arshad.ayn.ui.tv.shell.RailShade
import com.arshad.ayn.ui.tv.shell.ShellTime
import com.arshad.ayn.ui.tv.theme.KeLayout
import com.arshad.ayn.ui.tv.theme.KeMotion
import kotlin.time.Instant

// `b3-library` hub geometry (px ÷ 2): the rows start 75 dp down, 12 dp inside a 13 dp top fade; a
// focused row's top settles there. Cards are 188 dp, 14 apart; rows 20 apart, 100 dp of room at
// the end. Statements sit 125 dp down.
internal val HubTop = 75.dp
private val HubPadding = 12.dp
private val HubFade = 13.dp
private val HubEnd = 100.dp
private val HubCardWidth = 188.dp
private val HubCardSpacing = 14.dp
private val HubRowGap = 20.dp
private val PlaylistStackInset = 7.dp
internal val LibraryStatementTop = 125.dp

/** The hub's rows, in order; each is its own focus zone. */
private enum class HubRow { History, Playlists, WatchLater }

internal const val GO_HOME_KEY = "go-home"
internal const val RETRY_KEY = "retry"
private const val ALL_HISTORY_KEY = "all-history"
private const val NEW_PLAYLIST_KEY = "new-playlist"

/**
 * The Library hub (`b3-library` hub, hubpartial, hubempty, newpl) on [LibraryViewModel]: History
 * (the latest cards, then All history), Your playlists (then New playlist) and Watch later. An
 * empty row goes except Your playlists; nothing at all is a statement. A focused row rises to the
 * top (one transform, the mockup's anchor; D8) and a focused picture lights the room. New playlist
 * names one on a screen covering the shell (the rail hides, [onRailShade]); the playlist created
 * opens ([onOpenPlaylist]), a cancel returns focus to what opened it. Holding OK on a History or
 * Watch later card opens its menu on [menu] (removing takes it out of that row).
 */
@Composable
internal fun LibraryScreen(
    viewModel: LibraryViewModel,
    menu: VideoMenuViewModel,
    time: ShellTime,
    onNothingToFocus: () -> Unit,
    focusHeldElsewhere: () -> Boolean,
    onPlay: (PlayerRoute) -> Unit,
    onOpenChannel: (channelUrl: String) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenPlaylist: (playlistId: Long) -> Unit,
    onGoHome: () -> Unit,
    onRailShade: (RailShade) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val library = state.library
    val naming = state.naming
    val videoMenu by menu.menu.collectAsStateWithLifecycle()
    val menuFrom = rememberMenuCameFrom(videoMenu)
    val playVideo = { video: VideoItem, startOver: Boolean -> onPlay(PlayerRoute(video, startOver = startOver)) }
    val currentOnOpenPlaylist by rememberUpdatedState(onOpenPlaylist)
    LaunchedEffect(state.createdPlaylistId) {
        val created = state.createdPlaylistId ?: return@LaunchedEffect
        currentOnOpenPlaylist(created)
        viewModel.onPlaylistOpened()
    }
    BackHandler(enabled = naming != null, onBack = viewModel::onNamingCancel)
    BackHandler(enabled = videoMenu != null, onBack = menu::onBack)
    val page = remember { FocusRequester() }
    FocusOnClose(open = naming != null || menuHoldsFocus(menu, videoMenu), target = page)
    Overlays(
        sheet = videoMenu.asSheet(),
        cover = layerOf(naming, videoMenu.asCover()),
        onRailShade = onRailShade,
        modifier = modifier,
        sheetKey = ::menuIdentity,
        // One cover however the name changes, so typing doesn't re-key it.
        coverKey = { shown -> shown.key { Unit } },
        sheetContent = { shown -> VideoMenuSheet(shown, menuFrom, menu, playVideo, onOpenChannel) },
        coverContent = { shown ->
            when (shown) {
                is Layer.Own -> NamePlaylistScreen(
                    naming = shown.value,
                    confirm = "Create playlist",
                    onNameChange = viewModel::onNamingChange,
                    onDone = viewModel::onNamingDone,
                    onCancel = viewModel::onNamingCancel,
                )
                is Layer.Menu -> VideoMenuCover(shown.menu, menu)
            }
        },
    ) {
        WithMenuNotice(menu) {
            Hub(
                library = library,
                time = time,
                viewModel = viewModel,
                onNothingToFocus = onNothingToFocus,
                focusHeldElsewhere = focusHeldElsewhere,
                onPlay = { video -> playVideo(video, false) },
                onOpenMenu = menu::open,
                onOpenHistory = onOpenHistory,
                onOpenPlaylist = onOpenPlaylist,
                onGoHome = onGoHome,
                modifier = Modifier.focusRequester(page),
            )
        }
    }
}

@Composable
private fun Hub(
    library: LibraryState,
    time: ShellTime,
    viewModel: LibraryViewModel,
    onNothingToFocus: () -> Unit,
    focusHeldElsewhere: () -> Boolean,
    onPlay: (VideoItem) -> Unit,
    onOpenMenu: (VideoItem, VideoSource) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenPlaylist: (playlistId: Long) -> Unit,
    onGoHome: () -> Unit,
    modifier: Modifier,
) {
    val onNewPlaylist = viewModel::onNewPlaylist
    ScreenFocus(
        onNothingToFocus = onNothingToFocus,
        modifier = modifier,
        initialFocusKey = initialFocusKey(library),
        // Focus waits for the first read; a later change of layout enters again.
        entryKey = when (library) {
            LibraryState.Loading -> null
            LibraryState.Failed -> LibraryState.Failed
            is LibraryState.Ready -> library.isEmpty
        },
        focusHeldElsewhere = focusHeldElsewhere,
    ) {
        Box(Modifier.fillMaxSize().restingRoomLight()) {
            when (library) {
                LibraryState.Loading -> Unit
                LibraryState.Failed -> Statement(
                    kicker = Kicker(R.drawable.ic_alert, "Couldn’t read this Shield"),
                    title = "Library couldn’t load",
                    body = "Your history and playlists are still on this Shield; Ayn couldn’t read them just now. Try again.",
                    modifier = Modifier.padding(start = KeLayout.ContentStart, top = LibraryStatementTop),
                ) {
                    PillButton(R.drawable.ic_retry, "Try again", RETRY_KEY, viewModel::retry)
                }
                is LibraryState.Ready if library.isEmpty -> Statement(
                    kicker = Kicker(R.drawable.ic_nav_library, "Nothing here yet"),
                    title = "Your library fills as you watch",
                    body = "Videos you play land in History. Hold OK on any video to save it to Watch later or a playlist of your own.",
                    modifier = Modifier.padding(start = KeLayout.ContentStart, top = LibraryStatementTop),
                ) {
                    PillButton(R.drawable.ic_nav_home, "Go to Home", GO_HOME_KEY, onGoHome)
                    PillButton(R.drawable.ic_plus, "New playlist", NEW_PLAYLIST_KEY, onNewPlaylist, style = ControlStyle.Secondary)
                }
                is LibraryState.Ready -> HubRows(library, time, onPlay, onOpenMenu, onOpenHistory, onOpenPlaylist, onNewPlaylist)
            }
            PageHead("Library", "Saved on this Shield")
        }
    }
}

private fun initialFocusKey(library: LibraryState): String? = when (library) {
    LibraryState.Loading -> null
    LibraryState.Failed -> RETRY_KEY
    is LibraryState.Ready -> when {
        library.isEmpty -> GO_HOME_KEY
        library.recent.isNotEmpty() -> historyKey(library.recent.first().video)
        else -> library.playlists.firstOrNull()?.let { playlistKey(it.id) } ?: NEW_PLAYLIST_KEY
    }
}

/** The focus key of a history video, on the hub and on All history. */
internal fun historyKey(video: VideoItem): String = "history:${video.id.value}"

private fun playlistKey(id: Long): String = "playlist:$id"

private fun laterCardKey(video: VideoItem): String = "later:${video.id.value}"

@Composable
private fun HubRows(
    library: LibraryState.Ready,
    time: ShellTime,
    onPlay: (VideoItem) -> Unit,
    onOpenMenu: (VideoItem, VideoSource) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    onNewPlaylist: () -> Unit,
) {
    val rows = buildList {
        if (library.recent.isNotEmpty()) add(HubRow.History)
        add(HubRow.Playlists)
        if (library.watchLater.isNotEmpty()) add(HubRow.WatchLater)
    }
    var focusedRow by rememberSaveable { mutableStateOf(rows.first()) }
    // A row that went (its last video removed) hands the zone to the first.
    val zone = focusedRow.takeIf { it in rows } ?: rows.first()
    val tops = remember { mutableStateMapOf<HubRow, Int>() }
    var contentHeight by remember { mutableIntStateOf(0) }
    var viewportHeight by remember { mutableIntStateOf(0) }
    val padding = with(LocalDensity.current) { HubPadding.roundToPx() }
    // null while a restored zone waits for its first placement (BACK to the hub).
    val target = tops[zone]?.let { (it - padding).coerceIn(0, (contentHeight - viewportHeight).coerceAtLeast(0)) }
    val shift = remember { Animatable(0f) }
    val placed = remember { PlacedOnce() }
    LaunchedEffect(target) {
        val to = target?.toFloat() ?: return@LaunchedEffect
        // The first known position is where the screen already was: no slide on return.
        if (placed.done) shift.animateTo(to, KeMotion.move()) else shift.snapTo(to)
        placed.done = true
    }
    val cardLight = remember { FocusedCardLight() }
    var pictureFocused by remember { mutableStateOf(false) }
    val now = remember(library) { time.clock.now() }
    val onPictureFocused = { light: State<Color>, centerX: Float ->
        pictureFocused = true
        cardLight.onCardFocused(light, centerX)
    }
    val tileModifier = Modifier.onFocusChanged { if (it.hasFocus) pictureFocused = false }

    CardRoomLight(cardLight, lit = pictureFocused)
    Box(
        Modifier
            .padding(top = HubTop)
            .fillMaxSize()
            .topFade(HubFade)
            .onSizeChanged { viewportHeight = it.height },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .wrapContentHeight(Alignment.Top, unbounded = true)
                .onSizeChanged { contentHeight = it.height }
                .graphicsLayer { translationY = -shift.value },
        ) {
            Spacer(Modifier.height(HubPadding))
            rows.forEach { row ->
                key(row) {
                    val rowModifier = Modifier
                        .zIndex(if (zone == row) 1f else 0f)
                        .onPlaced { tops[row] = it.positionInParent().y.toInt() }
                        .onFocusChanged { if (it.hasFocus) focusedRow = row }
                        .padding(bottom = HubRowGap)
                    when (row) {
                        HubRow.History -> HistoryRow(library, onPlay, onOpenMenu, onOpenHistory, onPictureFocused, tileModifier, rowModifier)
                        HubRow.Playlists -> PlaylistsRow(library, time, now, onOpenPlaylist, onNewPlaylist, onPictureFocused, tileModifier, rowModifier)
                        HubRow.WatchLater -> WatchLaterRow(library, now, onPlay, onOpenMenu, onPictureFocused, rowModifier)
                    }
                }
            }
            Spacer(Modifier.height(HubEnd))
        }
    }
}

@Composable
private fun HistoryRow(
    library: LibraryState.Ready,
    onPlay: (VideoItem) -> Unit,
    onOpenMenu: (VideoItem, VideoSource) -> Unit,
    onOpenHistory: () -> Unit,
    onPictureFocused: (State<Color>, Float) -> Unit,
    tileModifier: Modifier,
    modifier: Modifier = Modifier,
) {
    PrefetchFaces(library.recent) { library.recent.map { it.video } }
    CardRow(title = "History", aside = videosText(library.historyCount), itemWidth = HubCardWidth, itemSpacing = HubCardSpacing, modifier = modifier) { rowFocused ->
        items(library.recent, key = { it.video.id.value }) { entry ->
            VideoCard(
                video = entry.video,
                meta = historyCardMeta(entry.video, entry.mark),
                focusKey = historyKey(entry.video),
                onClick = { onPlay(entry.video) },
                width = HubCardWidth,
                progress = entry.mark.barFraction(),
                rowFocused = rowFocused,
                onFocused = onPictureFocused,
                onLongClick = { onOpenMenu(entry.video, VideoSource.History) },
            )
        }
        item(key = ALL_HISTORY_KEY) {
            MoreCard(
                icon = R.drawable.ic_later,
                title = "All history",
                meta = "By day, newest first",
                focusKey = ALL_HISTORY_KEY,
                onClick = onOpenHistory,
                width = HubCardWidth,
                modifier = tileModifier,
            )
        }
    }
}

@Composable
private fun PlaylistsRow(
    library: LibraryState.Ready,
    time: ShellTime,
    now: Instant,
    onOpenPlaylist: (Long) -> Unit,
    onNewPlaylist: () -> Unit,
    onPictureFocused: (State<Color>, Float) -> Unit,
    tileModifier: Modifier,
    modifier: Modifier = Modifier,
) {
    CardRow(title = "Your playlists", aside = playlistsText(library.playlists.size), itemWidth = HubCardWidth, itemSpacing = HubCardSpacing, modifier = modifier) { _ ->
        items(library.playlists, key = { it.id }) { playlist ->
            PlaylistCoverCard(
                title = playlist.name,
                coverUrl = playlist.coverThumbnailUrl,
                count = playlistCountText(playlist.videoCount),
                meta = playlistMeta(playlist.totalDurationSeconds, playlist.updatedAt, now, time.zone),
                focusKey = playlistKey(playlist.id),
                onClick = { onOpenPlaylist(playlist.id) },
                width = HubCardWidth,
                onFocused = onPictureFocused,
            )
        }
        item(key = NEW_PLAYLIST_KEY) {
            MoreCard(
                icon = R.drawable.ic_plus,
                title = "New playlist",
                meta = "Name it, then add videos",
                focusKey = NEW_PLAYLIST_KEY,
                onClick = onNewPlaylist,
                width = HubCardWidth,
                modifier = tileModifier,
                topInset = PlaylistStackInset,
            )
        }
    }
}

@Composable
private fun WatchLaterRow(
    library: LibraryState.Ready,
    now: Instant,
    onPlay: (VideoItem) -> Unit,
    onOpenMenu: (VideoItem, VideoSource) -> Unit,
    onPictureFocused: (State<Color>, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    PrefetchFaces(library.watchLater) { library.watchLater }
    CardRow(title = "Watch later", aside = videosText(library.watchLater.size), itemWidth = HubCardWidth, itemSpacing = HubCardSpacing, modifier = modifier) { rowFocused ->
        items(library.watchLater, key = { it.id.value }) { video ->
            val mark = library.marks[video.id]
            VideoCard(
                video = video,
                meta = savedCardMeta(video, mark, now),
                focusKey = laterCardKey(video),
                onClick = { onPlay(video) },
                width = HubCardWidth,
                progress = mark.barFraction(),
                rowFocused = rowFocused,
                onFocused = onPictureFocused,
                onLongClick = { onOpenMenu(video, VideoSource.WatchLater) },
            )
        }
    }
}

// Written by the scroll effect only: no composition reads it.
private class PlacedOnce {
    var done: Boolean = false
}
