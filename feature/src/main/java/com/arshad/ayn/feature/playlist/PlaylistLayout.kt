package com.arshad.ayn.feature.playlist

import com.arshad.ayn.core.access.AccessFailure
import com.arshad.ayn.core.access.PlaylistDetails
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.filter.ContentFilter
import com.arshad.ayn.core.filter.shows

/** What a playlist page draws (`b8p-playlist`, `m6-library-and-playlist` 16–21), derived from [PlaylistUiState]. */
sealed interface PlaylistLayout {
    /** The header (from the opening card, then YouTube's details) beside the rows' place. */
    sealed interface WithHeader : PlaylistLayout {
        val details: PlaylistDetails
        val isAlbum: Boolean
    }

    /** The header known from the card that opened the page, the rows waiting. */
    data class Loading(override val details: PlaylistDetails, override val isAlbum: Boolean) : WithHeader

    /** The videos in playlist order; Play all starts them. */
    data class Videos(
        override val details: PlaylistDetails,
        override val isAlbum: Boolean,
        val videos: List<VideoItem>,
        val hasMore: Boolean,
    ) : WithHeader

    /** YouTube lists no videos, so there is nothing to play; Back only. */
    data class Empty(override val details: PlaylistDetails, override val isAlbum: Boolean) : WithHeader

    /** Offline or a reply unreadable (Try again); [AccessFailure.UNAVAILABLE] (private or deleted): Back only. */
    data class Failed(val failure: AccessFailure) : PlaylistLayout
}

/** The rows without what [filter] now hides: a block from a row's menu takes its rows out at once
 *  (KP-D19), the rows after it moving up into its place. */
fun playlistLayout(state: PlaylistUiState, filter: ContentFilter? = null): PlaylistLayout = when (val videos = state.videos) {
    PlaylistVideosState.Loading -> PlaylistLayout.Loading(state.details, state.isAlbum)
    is PlaylistVideosState.Failed -> PlaylistLayout.Failed(videos.failure)
    is PlaylistVideosState.Ready -> {
        val shown = videos.videos.filter { filter.shows(it) }
        if (shown.isEmpty()) {
            PlaylistLayout.Empty(state.details, state.isAlbum)
        } else {
            PlaylistLayout.Videos(state.details, state.isAlbum, shown, videos.hasMore)
        }
    }
}
