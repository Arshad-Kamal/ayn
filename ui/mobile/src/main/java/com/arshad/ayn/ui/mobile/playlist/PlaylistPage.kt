package com.arshad.ayn.ui.mobile.playlist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arshad.ayn.core.access.AccessFailure
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.feature.format.MetaSeparator
import com.arshad.ayn.feature.format.barFraction
import com.arshad.ayn.feature.format.videoCountText
import com.arshad.ayn.feature.library.VideoMenuViewModel
import com.arshad.ayn.feature.library.VideoSource
import com.arshad.ayn.feature.library.WatchMark
import com.arshad.ayn.feature.playlist.PlaylistLayout
import com.arshad.ayn.feature.playlist.PlaylistVideosState
import com.arshad.ayn.feature.playlist.PlaylistViewModel
import com.arshad.ayn.feature.playlist.playlistLayout
import com.arshad.ayn.ui.mobile.R
import com.arshad.ayn.ui.mobile.common.ButtonKind
import com.arshad.ayn.ui.mobile.common.ChannelAvatar
import com.arshad.ayn.ui.mobile.common.LocalContentFilter
import com.arshad.ayn.ui.mobile.common.PageBar
import com.arshad.ayn.ui.mobile.common.PillButton
import com.arshad.ayn.ui.mobile.common.listItemMotion
import com.arshad.ayn.ui.mobile.common.pageBarBottom
import com.arshad.ayn.ui.mobile.home.Statement
import com.arshad.ayn.ui.mobile.home.UnreadableReply
import com.arshad.ayn.ui.mobile.home.rememberPulse
import com.arshad.ayn.ui.mobile.menu.VideoMenuHost
import com.arshad.ayn.ui.mobile.search.ListPictureWidth
import com.arshad.ayn.ui.mobile.search.ResultRow
import com.arshad.ayn.ui.mobile.search.ResultSkeleton
import com.arshad.ayn.ui.mobile.shell.ShellChrome
import com.arshad.ayn.ui.mobile.theme.AynColors
import com.arshad.ayn.ui.mobile.theme.weightSpan
import kotlinx.coroutines.flow.Flow

private val RowGap = 14.dp
private const val SkeletonRows = 4

/** Rows this close to the end of what is listed ask for the next page. */
private const val LoadMoreAhead = 10

/**
 * A YouTube playlist (`m6-library-and-playlist` 16–21; M6-D15) on [PlaylistViewModel], opened from
 * its card: the head (known from the card at once; YouTube's owner line once its details arrive;
 * an album's art square and no owner) with Play all, then the videos numbered in playlist order,
 * the next page asked for as the list nears its end. Play all and every row play the list from
 * that video ([onPlay]); a long press or ⋮ opens the card menu on [menu]; a block takes rows out
 * at once. Empty: Back only; private or deleted: Back; offline or unreadable: Try again and Back.
 */
@Composable
internal fun PlaylistPage(
    viewModel: PlaylistViewModel,
    menu: VideoMenuViewModel,
    chrome: ShellChrome,
    marks: Flow<Map<VideoId, WatchMark>>,
    onPlay: (from: VideoItem, startOver: Boolean) -> Unit,
    onOpenChannel: (channelUrl: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val layout = playlistLayout(state, LocalContentFilter.current)
    val watched by marks.collectAsStateWithLifecycle(emptyMap())
    val list = rememberLazyListState()
    val barBottom = pageBarBottom()
    val passed by rememberHeadPassed(list, barBottom)
    LoadsMore(list, state.videos as? PlaylistVideosState.Ready, viewModel::loadMore)
    val menuOpen = menu.menu.collectAsStateWithLifecycle().value != null
    var heldRow by remember { mutableStateOf<Int?>(null) }
    VideoMenuHost(menu, onPlay, onOpenChannel, modifier) { openMenu ->
        Box(Modifier.fillMaxSize().background(AynColors.Ground)) {
            when (layout) {
                is PlaylistLayout.WithHeader -> LazyColumn(state = list, contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
                    item(key = HeadKey, contentType = HeadKey) { YouTubeHead(layout, top = barBottom + 8.dp, onPlay = { onPlay(it, false) }) }
                    when (layout) {
                        is PlaylistLayout.Loading -> item(key = "skeleton", contentType = "skeleton") {
                            val pulse = rememberPulse()
                            Column(Modifier.padding(top = 22.dp), verticalArrangement = Arrangement.spacedBy(RowGap)) {
                                repeat(SkeletonRows) { ResultSkeleton(pulse, number = it + 1, pictureWidth = ListPictureWidth) }
                            }
                        }
                        is PlaylistLayout.Empty -> item(key = "empty", contentType = "statement") { EmptyPlaylist(chrome.nav::back) }
                        // A playlist can hold one video twice: rows key by position.
                        is PlaylistLayout.Videos -> itemsIndexed(layout.videos, key = { position, _ -> position }, contentType = { _, _ -> "row" }) { position, video ->
                            ResultRow(
                                video = video,
                                progress = watched[video.id].barFraction(),
                                onClick = { onPlay(video, false) },
                                onOpenMenu = {
                                    heldRow = position
                                    openMenu(video, VideoSource.Elsewhere)
                                },
                                held = menuOpen && heldRow == position,
                                number = position + 1,
                                pictureWidth = ListPictureWidth,
                                modifier = listItemMotion().padding(top = if (position == 0) 22.dp else 0.dp, bottom = RowGap),
                            )
                        }
                    }
                }
                is PlaylistLayout.Failed -> PlaylistFailed(layout.failure, onRetry = viewModel::refresh, onBack = chrome.nav::back, top = barBottom)
            }
            val title = (layout as? PlaylistLayout.WithHeader)?.details?.title?.takeIf { passed }
            PageBar(title = title, solid = passed || layout is PlaylistLayout.Failed, onBack = chrome.nav::back, lifted = passed)
        }
    }
}

/** Asks [loadMore] for the next page while the last rows show and more follow. Keyed on what
 *  YouTube listed, not what the filter shows, so a page the filter empties still asks again; a
 *  page that failed is asked for again once the end scrolls away and back. */
@Composable
private fun LoadsMore(list: LazyListState, videos: PlaylistVideosState.Ready?, loadMore: () -> Unit) {
    val nearEnd by remember(list) {
        derivedStateOf {
            val info = list.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            last >= info.totalItemsCount - LoadMoreAhead
        }
    }
    val hasMore = videos?.hasMore == true
    LaunchedEffect(nearEnd, hasMore, videos?.videos?.size) {
        if (nearEnd && hasMore) loadMore()
    }
}

/** The head: cover, "Playlist" or "Album", the title, the owner line, then Play all (dimmed until
 *  the videos arrive; none when YouTube lists none). */
@Composable
private fun YouTubeHead(layout: PlaylistLayout.WithHeader, top: Dp, onPlay: (VideoItem) -> Unit) {
    val details = layout.details
    val empty = layout is PlaylistLayout.Empty
    val first = (layout as? PlaylistLayout.Videos)?.videos?.first()
    PlaylistHead(
        coverUrl = details.thumbnailUrl,
        kicker = if (layout.isAlbum) "Album" else "Playlist",
        title = details.title,
        top = top,
        square = layout.isAlbum,
        meta = {
            OwnerLine(
                owner = details.uploaderName.takeUnless { layout.isAlbum },
                avatarUrl = details.uploaderAvatarUrl,
                count = if (empty) "No videos" else videoCountText(details.videoCount),
            )
        },
        actions = if (empty) {
            null
        } else {
            { PillButton("Play all", { first?.let(onPlay) }, Modifier.height(52.dp), icon = R.drawable.ic_play, enabled = first != null) }
        },
    )
}

/** "Veritasium · 22 videos" after the owner's avatar (once YouTube's details bring it); the count
 *  alone, in ink, without an owner. */
@Composable
private fun OwnerLine(owner: String?, avatarUrl: String?, count: String?) {
    if (owner == null && count == null) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (owner != null && avatarUrl != null) ChannelAvatar(owner, avatarUrl, Modifier.size(26.dp), monogramSize = 11f)
        HeadMeta(
            buildAnnotatedString {
                if (owner != null) {
                    append(owner)
                    count?.let {
                        withStyle(SpanStyle(color = AynColors.Ink3)) { append(MetaSeparator) }
                        append(it)
                    }
                } else {
                    count?.let { withStyle(weightSpan(650, AynColors.Ink)) { append(it) } }
                }
            },
        )
    }
}

/** State 19: YouTube lists no videos, so there is nothing to play; Back only. */
@Composable
private fun EmptyPlaylist(onBack: () -> Unit) {
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp)) {
        HorizontalDivider(color = AynColors.Line)
        Statement(
            icon = R.drawable.ic_play,
            kicker = "Nothing to play",
            title = "This playlist is empty",
            body = "YouTube lists no videos in it. They may have been made private or deleted.",
            compact = true,
            modifier = Modifier.padding(top = 34.dp),
        ) { PillButton("Back", onBack, Modifier.height(48.dp), kind = ButtonKind.Tonal, icon = R.drawable.ic_back) }
    }
}

/** States 20–21: private or deleted (Back only, retrying can't change it); offline or unreadable (Try again, Back). */
@Composable
private fun PlaylistFailed(failure: AccessFailure, onRetry: () -> Unit, onBack: () -> Unit, top: Dp) {
    val modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = top + 60.dp).semantics { liveRegion = LiveRegionMode.Polite }
    if (failure == AccessFailure.UNAVAILABLE) {
        Statement(
            icon = R.drawable.ic_list,
            kicker = "Playlist",
            title = "This playlist isn’t available",
            body = "It may be private or deleted.",
            modifier = modifier,
        ) { PillButton("Back", onBack, Modifier.height(48.dp), icon = R.drawable.ic_back) }
        return
    }
    val offline = failure == AccessFailure.OFFLINE
    Statement(
        icon = if (offline) R.drawable.ic_offline else R.drawable.ic_alert,
        kicker = if (offline) "No connection to YouTube" else "YouTube changed something",
        title = "This playlist couldn’t load",
        body = if (offline) "Check that the phone is online, then try again. Back returns you to the channel." else UnreadableReply,
        modifier = modifier,
    ) {
        PillButton("Try again", onRetry, Modifier.height(48.dp), icon = R.drawable.ic_retry)
        PillButton("Back", onBack, Modifier.height(48.dp), kind = ButtonKind.Tonal, icon = R.drawable.ic_back)
    }
}
