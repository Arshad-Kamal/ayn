package com.arshad.ayn.ui.tv.search

import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.access.AccessFailure
import com.arshad.ayn.core.access.ChannelItem
import com.arshad.ayn.core.access.PlaylistItem
import com.arshad.ayn.feature.search.SearchState
import com.arshad.ayn.feature.search.SearchUiState
import com.arshad.ayn.core.filter.blockingVideos
import com.arshad.ayn.ui.tv.common.TopAlign
import org.junit.Assert.assertEquals
import org.junit.Test

/** Which `b3-search` state a [SearchUiState] draws, and the grid's top-aligned scroll. */
class SearchLayoutTest {

    private val channel = ChannelItem("Veritasium", "https://www.youtube.com/channel/UC1", null, subscriberCount = 1, description = null, verified = true)
    private val playlist = PlaylistItem("Biology", "https://www.youtube.com/playlist?list=PL1", null, videoCount = 22, uploaderName = null)

    private fun answered(state: SearchState, editing: Boolean = false) =
        SearchUiState(query = "q", submittedQuery = "q", state = state, editing = editing)

    @Test
    fun editingShowsTypingWhateverTheLastAnswer() {
        assertEquals(SearchLayout.Typing, searchLayout(answered(SearchState.Ready(listOf(channel)), editing = true)))
        assertEquals(SearchLayout.Typing, searchLayout(SearchUiState()))
    }

    @Test
    fun eachAnswerHasItsStateAndItsFirstFocus() {
        assertEquals(SearchLayout.Searching("q"), searchLayout(answered(SearchState.Loading)))
        assertEquals(null, searchLayout(answered(SearchState.Loading)).entryKey)
        val results = searchLayout(answered(SearchState.Ready(listOf(channel))))
        assertEquals("result:${channel.url}", results.initialFocusKey)
        assertEquals(EditKey, searchLayout(answered(SearchState.Empty)).initialFocusKey)
        assertEquals(SearchLayout.Failed("q", AccessFailure.OFFLINE), searchLayout(answered(SearchState.Failed(AccessFailure.OFFLINE))))
    }

    @Test
    fun anAnswerOfOnlyPlaylistsIsNoResults() {
        assertEquals(SearchLayout.NoResults("q"), searchLayout(answered(SearchState.Ready(listOf(playlist)))))
    }

    @Test
    fun anAnswerOfOnlyShortsIsResultsFocusingTheFirstShort() {
        val short = VideoItem(
            id = VideoId("sssssssssss"), title = "s", uploaderName = null, uploaderUrl = null, durationSeconds = -1,
            viewCount = 1, textualUploadDate = null, uploadDate = null, thumbnailUrl = null, isLive = false,
        )

        val results = searchLayout(answered(SearchState.Ready(listOf(playlist), shorts = listOf(short))))

        assertEquals(SearchLayout.Results("q", emptyList(), listOf(short)), results)
        assertEquals(shortKey(short.id), results.initialFocusKey)
    }

    @Test
    fun aBlockedResultOrShortLeavesTheAnswerAndABlockOfEverythingLeavesNoResults() {
        val video = VideoItem(
            id = VideoId("vvvvvvvvvvv"), title = "v", uploaderName = null, uploaderUrl = null, durationSeconds = 60,
            viewCount = 1, textualUploadDate = null, uploadDate = null, thumbnailUrl = null, isLive = false,
        )
        val short = video.copy(id = VideoId("sssssssssss"), durationSeconds = -1)
        val answer = answered(SearchState.Ready(listOf(channel, video), shorts = listOf(short)))

        assertEquals(SearchLayout.Results("q", listOf(channel), emptyList()), searchLayout(answer, blockingVideos(video.id.value, short.id.value)))
        val onlyVideo = answered(SearchState.Ready(listOf(video)))
        assertEquals(SearchLayout.NoResults("q"), searchLayout(onlyVideo, blockingVideos(video.id.value)))
        val playlistAndShort = answered(SearchState.Ready(listOf(playlist), shorts = listOf(short)))
        assertEquals(SearchLayout.NoResults("q"), searchLayout(playlistAndShort, blockingVideos(short.id.value)))
    }

    @Test
    fun aFocusedRowMovesToTheGridsTopWhereverItIs() {
        val align = TopAlign(top = 15f)
        assertEquals("the first row is already there", 0f, align.calculateScrollDistance(15f, 160f, 465f), 0.01f)
        assertEquals("a lower row rises", 182f, align.calculateScrollDistance(197f, 160f, 465f), 0.01f)
        assertEquals("a row above comes down", -182f, align.calculateScrollDistance(-167f, 160f, 465f), 0.01f)
    }
}
