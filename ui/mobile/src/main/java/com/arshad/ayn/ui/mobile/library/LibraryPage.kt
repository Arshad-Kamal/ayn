package com.arshad.ayn.ui.mobile.library

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.store.PlaylistSummary
import com.arshad.ayn.feature.format.barFraction
import com.arshad.ayn.feature.format.historyCardMeta
import com.arshad.ayn.feature.library.LibraryState
import com.arshad.ayn.feature.library.LibraryViewModel
import com.arshad.ayn.feature.library.VideoMenuViewModel
import com.arshad.ayn.feature.library.VideoSource
import com.arshad.ayn.feature.library.playlistCountText
import com.arshad.ayn.feature.library.playlistMeta
import com.arshad.ayn.feature.library.playlistsText
import com.arshad.ayn.feature.library.savedCardMeta
import com.arshad.ayn.feature.library.updatedAt
import com.arshad.ayn.feature.library.videosText
import com.arshad.ayn.feature.nav.PlayerRoute
import com.arshad.ayn.feature.nav.SavedPlaylistRoute
import com.arshad.ayn.ui.mobile.R
import com.arshad.ayn.ui.mobile.common.ButtonKind
import com.arshad.ayn.ui.mobile.common.Picture
import com.arshad.ayn.ui.mobile.common.PillButton
import com.arshad.ayn.ui.mobile.common.pressScale
import com.arshad.ayn.ui.mobile.common.rememberScrolled
import com.arshad.ayn.ui.mobile.home.Shelf
import com.arshad.ayn.ui.mobile.home.Statement
import com.arshad.ayn.ui.mobile.home.VideoCard
import com.arshad.ayn.ui.mobile.menu.FullCover
import com.arshad.ayn.ui.mobile.menu.NamePlaylistPage
import com.arshad.ayn.ui.mobile.menu.VideoMenuHost
import com.arshad.ayn.ui.mobile.playlist.CoverStack
import com.arshad.ayn.ui.mobile.playlist.EmptyCover
import com.arshad.ayn.ui.mobile.shell.HistoryKey
import com.arshad.ayn.ui.mobile.shell.SavedPlaylistKey
import com.arshad.ayn.ui.mobile.shell.ScrollsToTop
import com.arshad.ayn.ui.mobile.shell.ShellChrome
import com.arshad.ayn.ui.mobile.shell.ShellTime
import com.arshad.ayn.ui.mobile.shell.ShellTopBar
import com.arshad.ayn.ui.mobile.shell.Tab
import com.arshad.ayn.ui.mobile.theme.AynColors
import com.arshad.ayn.ui.mobile.theme.AynMotion
import com.arshad.ayn.ui.mobile.theme.atWeight
import kotlin.time.Instant

/** The hub's cards (`.lib .card`). */
private val HubCardWidth = 196.dp
private val TileShape = RoundedCornerShape(12.dp)
private val ShelfTop = 14.dp

/**
 * The Library tab (`m6-library-and-playlist` 1–4, 15; M6-D15) on [LibraryViewModel]: under the
 * pill, History (the latest, then All history), Your playlists (then New playlist) and Watch later
 * as sideways rows; an empty row goes except Your playlists, and nothing at all is a statement.
 * New playlist names one on a page over the shell, then the playlist created opens. A tap on a
 * video plays it ([onPlay]); a long press or ⋮ opens its card menu on [menu] (Remove takes it out
 * of its row). The store is this phone's, so there is no loading screen; a tap on the tab while it
 * shows scrolls up.
 */
@Composable
internal fun LibraryPage(
    viewModel: LibraryViewModel,
    menu: VideoMenuViewModel,
    chrome: ShellChrome,
    onPlay: (PlayerRoute) -> Unit,
    onOpenChannel: (channelUrl: String) -> Unit,
    modifier: Modifier = Modifier,
    time: ShellTime = ShellTime(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val library = state.library
    // The shell's navigation is the page's for its whole life: not a key.
    LaunchedEffect(state.createdPlaylistId) {
        val created = state.createdPlaylistId ?: return@LaunchedEffect
        chrome.nav.open(SavedPlaylistKey(SavedPlaylistRoute(created)))
        viewModel.onPlaylistOpened()
    }
    val list = rememberLazyListState()
    ScrollsToTop(list, chrome.nav.reselected == Tab.Library, chrome.nav::onScrolledToTop)
    val menuOpen = menu.menu.collectAsStateWithLifecycle().value != null
    var heldCard by remember { mutableStateOf<String?>(null) }
    val playVideo = { video: VideoItem, startOver: Boolean -> onPlay(PlayerRoute(video, startOver = startOver)) }
    VideoMenuHost(menu, playVideo, onOpenChannel, modifier) { openMenu ->
        val cards = HubCards(
            heldCard = heldCard.takeIf { menuOpen },
            now = remember(library) { time.clock.now() },
            onPlay = { playVideo(it, false) },
            onOpenMenu = { video, source, card ->
                heldCard = card
                openMenu(video, source)
            },
            onOpenHistory = { chrome.nav.open(HistoryKey) },
            onOpenPlaylist = { id -> chrome.nav.open(SavedPlaylistKey(SavedPlaylistRoute(id))) },
            onNewPlaylist = viewModel::onNewPlaylist,
        )
        Column(Modifier.fillMaxSize()) {
            LibraryTopBar(chrome, list)
            LazyColumn(state = list, contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.weight(1f)) {
                when (library) {
                    LibraryState.Loading -> Unit
                    LibraryState.Failed -> item(key = "statement", contentType = "statement") {
                        Statement(
                            icon = R.drawable.ic_alert,
                            kicker = "Couldn’t read this phone",
                            title = "Library couldn’t load",
                            body = "Your history and playlists are still on this phone; Ayn couldn’t read them just now. Try again.",
                            modifier = StatementPadding.semantics { liveRegion = LiveRegionMode.Polite },
                        ) { PillButton("Try again", viewModel::retry, Modifier.height(48.dp), icon = R.drawable.ic_retry) }
                    }
                    is LibraryState.Ready if library.isEmpty -> item(key = "statement", contentType = "statement") {
                        Statement(
                            icon = R.drawable.ic_nav_library,
                            kicker = "Nothing here yet",
                            title = "Your library fills as you watch",
                            body = "Videos you play land in History. Long-press any video, or tap its ⋮, to save it to Watch later or a playlist of your own.",
                            modifier = StatementPadding,
                        ) {
                            PillButton("Go to Home", { chrome.nav.onTabTap(Tab.Home) }, Modifier.height(48.dp), icon = R.drawable.ic_nav_home)
                            PillButton("New playlist", viewModel::onNewPlaylist, Modifier.height(48.dp), kind = ButtonKind.Tonal, icon = R.drawable.ic_plus)
                        }
                    }
                    is LibraryState.Ready -> {
                        item(key = "line", contentType = "line") {
                            Text(
                                "Saved on this phone",
                                style = MaterialTheme.typography.bodySmall,
                                color = AynColors.Ink3,
                                modifier = Modifier.padding(start = 20.dp, top = 10.dp),
                            )
                        }
                        if (library.recent.isNotEmpty()) item(key = "history", contentType = "shelf") { HistoryShelf(library, cards) }
                        item(key = "playlists", contentType = "shelf") { PlaylistsShelf(library, cards, time) }
                        if (library.watchLater.isNotEmpty()) item(key = "later", contentType = "shelf") { WatchLaterShelf(library, cards) }
                    }
                }
            }
        }
    }
    state.naming?.let { naming ->
        FullCover {
            NamePlaylistPage(
                naming = naming,
                confirm = "Create playlist",
                onNameChange = viewModel::onNamingChange,
                onDone = viewModel::onNamingDone,
                onCancel = viewModel::onNamingCancel,
            )
        }
    }
}

private val StatementPadding = Modifier.padding(start = 24.dp, end = 24.dp, top = 40.dp)

/** What the hub's cards do; [heldCard] is the card whose menu is open. */
private class HubCards(
    val heldCard: String?,
    val now: Instant,
    val onPlay: (VideoItem) -> Unit,
    val onOpenMenu: (video: VideoItem, source: VideoSource, card: String) -> Unit,
    val onOpenHistory: () -> Unit,
    val onOpenPlaylist: (Long) -> Unit,
    val onNewPlaylist: () -> Unit,
)

/** The top bar, lifting once the hub scrolls; reads the scroll here so the page doesn't recompose with it. */
@Composable
private fun LibraryTopBar(chrome: ShellChrome, list: LazyListState) {
    val scrolled = rememberScrolled(list)
    val lift by animateFloatAsState(if (scrolled) 1f else 0f, AynMotion.press(), label = "lift")
    ShellTopBar(chrome, lift = { lift })
}

@Composable
private fun HistoryShelf(library: LibraryState.Ready, cards: HubCards) {
    Shelf("History", aside = videosText(library.historyCount), top = ShelfTop) {
        items(library.recent, key = { "history:${it.video.id.value}" }) { entry ->
            val card = "history:${entry.video.id.value}"
            VideoCard(
                video = entry.video,
                meta = historyCardMeta(entry.video, entry.mark),
                onClick = { cards.onPlay(entry.video) },
                onOpenMenu = { cards.onOpenMenu(entry.video, VideoSource.History, card) },
                progress = entry.mark.barFraction(),
                held = cards.heldCard == card,
                width = HubCardWidth,
                modifier = Modifier.animateItem(),
            )
        }
        item(key = "all-history") { MoreCard(R.drawable.ic_later, "All history", "By day, newest first", cards.onOpenHistory) }
    }
}

@Composable
private fun PlaylistsShelf(library: LibraryState.Ready, cards: HubCards, time: ShellTime) {
    Shelf("Your playlists", aside = playlistsText(library.playlists.size), top = ShelfTop) {
        items(library.playlists, key = { "playlist:${it.id}" }) { playlist ->
            PlaylistTile(playlist, playlistMeta(playlist.totalDurationSeconds, playlist.updatedAt, cards.now, time.zone), { cards.onOpenPlaylist(playlist.id) }, Modifier.animateItem())
        }
        item(key = "new-playlist") { MoreCard(R.drawable.ic_plus, "New playlist", "Name it, then add videos", cards.onNewPlaylist, stacked = true) }
    }
}

@Composable
private fun WatchLaterShelf(library: LibraryState.Ready, cards: HubCards) {
    Shelf("Watch later", aside = videosText(library.watchLater.size), top = ShelfTop) {
        items(library.watchLater, key = { "later:${it.id.value}" }) { video ->
            val card = "later:${video.id.value}"
            val mark = library.marks[video.id]
            VideoCard(
                video = video,
                meta = savedCardMeta(video, mark, cards.now),
                onClick = { cards.onPlay(video) },
                onOpenMenu = { cards.onOpenMenu(video, VideoSource.WatchLater, card) },
                progress = mark.barFraction(),
                held = cards.heldCard == card,
                width = HubCardWidth,
                modifier = Modifier.animateItem(),
            )
        }
    }
}

/** A playlist's tile (`.plc`): its cover over the stack with the count, its name, then its running time and last change. */
@Composable
private fun PlaylistTile(playlist: PlaylistSummary, meta: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier
            .width(HubCardWidth)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClickLabel = "Open the playlist", onClick = onClick),
    ) {
        CoverStack(Modifier.pressScale(interaction), shape = TileShape) {
            if (playlist.coverThumbnailUrl != null) Picture(playlist.coverThumbnailUrl) else EmptyCover()
            CountBadge(playlistCountText(playlist.videoCount), Modifier.align(Alignment.BottomEnd))
        }
        CardText(playlist.name, meta, titleLines = 2)
    }
}

/** A playlist's count on its cover: the stack mark and "8 videos". */
@Composable
private fun CountBadge(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .padding(6.dp)
            .background(AynColors.Ground.copy(alpha = 0.8f), RoundedCornerShape(5.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(painterResource(R.drawable.ic_list), contentDescription = null, tint = AynColors.Ink, modifier = Modifier.size(13.dp))
        Text(text, style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp))
    }
}

/** A row's last tile (`.card.more`): All history or New playlist ([stacked] as a playlist is). */
@Composable
private fun MoreCard(@DrawableRes icon: Int, title: String, meta: String, onClick: () -> Unit, stacked: Boolean = false) {
    val interaction = remember { MutableInteractionSource() }
    Column(Modifier.width(HubCardWidth).clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)) {
        val well: @Composable () -> Unit = {
            Box(Modifier.fillMaxSize().background(AynColors.Surface1).border(1.dp, AynColors.Line, TileShape), contentAlignment = Alignment.Center) {
                Icon(painterResource(icon), contentDescription = null, tint = AynColors.Ink2, modifier = Modifier.size(30.dp))
            }
        }
        if (stacked) {
            CoverStack(Modifier.pressScale(interaction), shape = TileShape) { well() }
        } else {
            Box(Modifier.pressScale(interaction).fillMaxWidth().aspectRatio(16f / 9f).clip(TileShape)) { well() }
        }
        CardText(title, meta, titleLines = 1)
    }
}

/** A tile's name and line under its picture. */
@Composable
private fun CardText(title: String, meta: String?, titleLines: Int) {
    Column(Modifier.padding(top = 9.dp, end = 8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium.atWeight(600).copy(lineHeight = 17.5.sp),
            maxLines = titleLines,
            overflow = TextOverflow.Ellipsis,
        )
        if (meta != null) {
            Text(
                meta,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                color = AynColors.Ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}
