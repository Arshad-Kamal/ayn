package com.arshad.ayn.feature.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arshad.ayn.core.access.AccessException
import com.arshad.ayn.core.access.AccessFailure
import com.arshad.ayn.core.access.Extractor
import com.arshad.ayn.core.access.ListingItem
import com.arshad.ayn.core.access.PageToken
import com.arshad.ayn.core.access.PlaylistDetails
import com.arshad.ayn.core.access.PlaylistItem
import com.arshad.ayn.core.access.VideoItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The playlist page's videos (mockup `b8p-playlist`). */
sealed interface PlaylistVideosState {
    data object Loading : PlaylistVideosState

    data class Ready(
        /** Playlist order, repeats kept: a playlist can hold one video twice, so the list keys
         *  rows by position, never by video id (CMP-LAZY-01). */
        val videos: List<VideoItem>,
        /** More videos follow; [PlaylistViewModel.loadMore] appends them. */
        val hasMore: Boolean,
    ) : PlaylistVideosState

    data class Failed(val failure: AccessFailure) : PlaylistVideosState
}

data class PlaylistUiState(
    /** Seeded from the card that opened the page, so the header shows while the videos load;
     *  YouTube's own details (owner avatar and link included) replace it once they arrive. */
    val details: PlaylistDetails,
    val videos: PlaylistVideosState = PlaylistVideosState.Loading,
    /** An album (a channel's Releases tab): its art is square, and it names no owner. */
    val isAlbum: Boolean = false,
)

/**
 * A YouTube playlist page (ui-rebuild B8p, B-D10). One ViewModel per destination (ARCH-3); the
 * seam is constructor-injected (ARCH-4) and main-safe (KT-CONC-01); only [AccessException] is
 * caught, per the seam contract.
 */
class PlaylistViewModel(
    private val opening: PlaylistItem,
    private val extractor: Extractor,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlaylistUiState(details = opening.seedDetails(), isAlbum = isAlbumUrl(opening.url)))
    val uiState: StateFlow<PlaylistUiState> = _uiState.asStateFlow()

    private var load: Job? = null
    private var loadMoreJob: Job? = null
    private var next: PageToken? = null

    init {
        refresh()
    }

    /** Initial load and Retry. A superseded load (and any page it was continuing) is cancelled. */
    fun refresh() {
        load?.cancel()
        loadMoreJob?.cancel()
        next = null
        _uiState.update { it.copy(videos = PlaylistVideosState.Loading) }
        load = viewModelScope.launch {
            try {
                val page = extractor.playlist(opening.url)
                next = page.videos.next
                _uiState.update {
                    it.copy(
                        details = page.details,
                        videos = PlaylistVideosState.Ready(page.videos.items.videos(), hasMore = next != null),
                    )
                }
            } catch (e: AccessException) {
                _uiState.update { it.copy(videos = PlaylistVideosState.Failed(e.failure)) }
            }
        }
    }

    /**
     * Appends the next page (the list asks as focus nears its end). One page at a time; a failed
     * page keeps what is listed and is asked for again on the next call (the mockup has no
     * partial-list error).
     */
    fun loadMore() {
        val token = next ?: return
        if (loadMoreJob?.isActive == true) return
        loadMoreJob = viewModelScope.launch {
            val page = try {
                extractor.playlistVideos(opening.url, token)
            } catch (e: AccessException) {
                return@launch
            }
            next = page.next
            _uiState.update { state ->
                val ready = state.videos as? PlaylistVideosState.Ready ?: return@update state
                state.copy(videos = PlaylistVideosState.Ready(ready.videos + page.items.videos(), hasMore = page.next != null))
            }
        }
    }
}

private fun PlaylistItem.seedDetails(): PlaylistDetails = PlaylistDetails(
    title = title,
    uploaderName = uploaderName,
    uploaderUrl = null,
    uploaderAvatarUrl = null,
    videoCount = videoCount,
    thumbnailUrl = thumbnailUrl,
)

private fun List<ListingItem>.videos(): List<VideoItem> = filterIsInstance<VideoItem>()

/** YouTube Music's album lists (a channel's Releases tab: 30 of 30 recorded) carry the `OLAK5uy_`
 *  list id prefix, so the page knows an album before its details arrive. */
private fun isAlbumUrl(url: String): Boolean = url.substringAfter("list=", "").startsWith("OLAK5uy_")
