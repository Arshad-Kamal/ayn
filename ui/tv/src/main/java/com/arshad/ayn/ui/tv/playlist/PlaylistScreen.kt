package com.arshad.ayn.ui.tv.playlist

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arshad.ayn.core.access.AccessFailure
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.feature.library.VideoMenuViewModel
import com.arshad.ayn.feature.playlist.PlaylistLayout
import com.arshad.ayn.feature.playlist.PlaylistViewModel
import com.arshad.ayn.feature.playlist.playlistLayout
import com.arshad.ayn.ui.tv.R
import com.arshad.ayn.ui.tv.common.Appearing
import com.arshad.ayn.ui.tv.common.CardRoomLight
import com.arshad.ayn.ui.tv.common.FocusedCardLight
import com.arshad.ayn.ui.tv.common.Kicker
import com.arshad.ayn.ui.tv.common.LocalContentFilter
import com.arshad.ayn.ui.tv.common.PillButton
import com.arshad.ayn.ui.tv.common.Statement
import com.arshad.ayn.ui.tv.common.UnreadableReply
import com.arshad.ayn.ui.tv.common.failureKicker
import com.arshad.ayn.ui.tv.common.restingRoomLight
import com.arshad.ayn.feature.format.videoCountText
import com.arshad.ayn.ui.tv.focus.ControlStyle
import com.arshad.ayn.ui.tv.focus.ScreenFocus
import com.arshad.ayn.ui.tv.library.VideoMenuHost
import com.arshad.ayn.ui.tv.shell.RailShade
import com.arshad.ayn.ui.tv.theme.KeLayout

// b8p-playlist statements (px ÷ 2): the page's at the master's 118 dp; an empty playlist's in the
// list column, 125 dp down the page and at most 410 dp wide.
private val StatementTop = 118.dp
private val EmptyTop = PageTop + 125.dp
private val EmptyWidth = 410.dp

/**
 * A YouTube playlist page (`b8p-playlist` states 1–6 and the v4 gaps) on [PlaylistViewModel]:
 * the header beside the videos in playlist order. Play all and every row play the list ([onPlay]
 * from the first video or the row's; the player moves on through it). Holding
 * OK on a row opens its card menu on [menu] ([onRailShade] while it shows), whose play rows play
 * the list from that video too.
 */
@Composable
internal fun PlaylistScreen(
    viewModel: PlaylistViewModel,
    menu: VideoMenuViewModel,
    onNothingToFocus: () -> Unit,
    focusHeldElsewhere: () -> Boolean,
    onPlay: (from: VideoItem, startOver: Boolean) -> Unit,
    onOpenChannel: (channelUrl: String) -> Unit,
    onBack: () -> Unit,
    onRailShade: (RailShade) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val layout = playlistLayout(state, LocalContentFilter.current)
    VideoMenuHost(menu, onPlay, onOpenChannel, onRailShade, modifier) { pageModifier, openMenu ->
        ScreenFocus(
            onNothingToFocus = onNothingToFocus,
            modifier = pageModifier,
            initialFocusKey = layout.initialFocusKey,
            entryKey = layout.entryKey,
            focusHeldElsewhere = focusHeldElsewhere,
        ) {
            when (layout) {
                is PlaylistLayout.WithHeader -> PlaylistPage(
                    layout = layout,
                    onPlay = { from -> onPlay(from, false) },
                    onOpenMenu = openMenu,
                    onLoadMore = viewModel::loadMore,
                    onBack = onBack,
                )
                is PlaylistLayout.Failed -> PlaylistFailed(layout.failure, onRetry = viewModel::refresh, onBack = onBack)
            }
        }
    }
}

/** States 1–3, 5 and v4 empty: the cover lights the backdrop, a focused row the room. */
@Composable
private fun PlaylistPage(
    layout: PlaylistLayout.WithHeader,
    onPlay: (from: VideoItem) -> Unit,
    onOpenMenu: (VideoItem) -> Unit,
    onLoadMore: () -> Unit,
    onBack: () -> Unit,
) {
    val details = layout.details
    // The backdrop asks for a cover once it has landed, so the two share one download; it keeps
    // the card's cover until YouTube's own (often another URL) lands.
    var backdropUrl by remember { mutableStateOf<String?>(null) }
    val rowLight = remember { FocusedCardLight() }
    var rowsFocused by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        PlaylistBackdrop(backdropUrl)
        Box(Modifier.fillMaxSize().restingRoomLight()) {
            CardRoomLight(rowLight, lit = rowsFocused)
        }
        YouTubePlaylistHeader(
            details = details,
            isAlbum = layout.isAlbum,
            countText = if (layout is PlaylistLayout.Empty) "No videos" else videoCountText(details.videoCount),
            onCoverLoaded = { backdropUrl = details.thumbnailUrl },
            modifier = Modifier.padding(start = KeLayout.ContentStart, top = PageTop),
            action = if (layout is PlaylistLayout.Empty) null else {
                { PlayAll(first = (layout as? PlaylistLayout.Videos)?.videos?.first(), onPlay = onPlay) }
            },
        )
        key(layout::class) {
            Appearing {
                when (layout) {
                    is PlaylistLayout.Loading -> SkeletonRows()
                    is PlaylistLayout.Videos -> PlaylistRows(
                        videos = layout.videos,
                        hasMore = layout.hasMore,
                        onPlay = onPlay,
                        onLoadMore = onLoadMore,
                        onRowFocused = rowLight::onCardFocused,
                        modifier = Modifier.onFocusChanged { rowsFocused = it.hasFocus },
                        onOpenMenu = onOpenMenu,
                    )
                    is PlaylistLayout.Empty -> Statement(
                        kicker = Kicker(R.drawable.ic_play, "Nothing to play"),
                        title = "This playlist is empty",
                        body = "YouTube lists no videos in it. They may have been made private or deleted.",
                        compact = true,
                        modifier = Modifier.padding(start = ListStart, top = EmptyTop).widthIn(max = EmptyWidth),
                    ) {
                        PillButton(R.drawable.ic_back, "Back", BackKey, onBack)
                    }
                }
            }
        }
    }
}

/** Play all starts at [first]; shown while the videos load, but not focusable until there is one (state 3). */
@Composable
private fun PlayAll(first: VideoItem?, onPlay: (VideoItem) -> Unit) {
    PillButton(
        icon = R.drawable.ic_play,
        label = "Play all",
        focusKey = PlayAllKey,
        onClick = { first?.let(onPlay) },
        modifier = Modifier.focusProperties { canFocus = first != null },
    )
}

/**
 * State 4 and v4 error-yt: offline, or YouTube's reply unreadable (Try again, Back). State 6: the
 * playlist doesn't exist (private or deleted), which retrying can't change, so Back only.
 */
@Composable
private fun PlaylistFailed(failure: AccessFailure, onRetry: () -> Unit, onBack: () -> Unit) {
    val modifier = Modifier.padding(start = KeLayout.ContentStart, top = StatementTop)
    Box(Modifier.fillMaxSize().restingRoomLight()) {
        if (failure == AccessFailure.UNAVAILABLE) {
            Statement(
                kicker = null,
                title = "This playlist isn’t available",
                body = "It may be private or deleted.",
                modifier = modifier,
            ) {
                PillButton(R.drawable.ic_back, "Back", BackKey, onBack)
            }
        } else {
            Statement(
                kicker = failureKicker(failure),
                title = "This playlist couldn’t load",
                body = if (failure == AccessFailure.OFFLINE) {
                    "Check that the Shield is online, then try again. Back returns you to the channel."
                } else {
                    UnreadableReply
                },
                modifier = modifier,
            ) {
                PillButton(R.drawable.ic_retry, "Try again", RetryKey, onRetry)
                PillButton(R.drawable.ic_back, "Back", BackKey, onBack, style = ControlStyle.Secondary)
            }
        }
    }
}
