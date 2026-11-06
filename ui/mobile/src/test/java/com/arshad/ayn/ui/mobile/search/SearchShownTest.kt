package com.arshad.ayn.ui.mobile.search

import com.arshad.ayn.core.access.AccessFailure
import com.arshad.ayn.core.access.PlaylistItem
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.filter.ContentFilter
import com.arshad.ayn.core.store.BlockedVideo
import com.arshad.ayn.core.store.ProfileRules
import com.arshad.ayn.core.store.ProfileSettings
import com.arshad.ayn.core.store.StoredRule
import com.arshad.ayn.feature.search.SearchState
import com.arshad.ayn.feature.search.SearchUiState
import org.junit.Assert.assertEquals
import org.junit.Test

/** What Search shows for each state of the ViewModel, under the open profile's filter. */
class SearchShownTest {
    private val video = video("vvvvvvvvvv1")
    private val short = video("sssssssssss")
    private val playlist = PlaylistItem("Mix", "https://www.youtube.com/playlist?list=PL1", null, 3, "Someone")

    private fun answered(state: SearchState, editing: Boolean = false) =
        SearchUiState(query = "q", submittedQuery = "q", state = state, editing = editing)

    @Test
    fun typingOrNothingSubmittedShowsTheField() {
        assertEquals(SearchShown.Typing, searchShown(SearchUiState(), null))
        assertEquals(SearchShown.Typing, searchShown(answered(SearchState.Ready(listOf(video)), editing = true), null))
    }

    @Test
    fun searchingEmptyAndFailedShowTheirPages() {
        assertEquals(SearchShown.Searching, searchShown(answered(SearchState.Loading), null))
        assertEquals(SearchShown.NoResults("q"), searchShown(answered(SearchState.Empty), null))
        assertEquals(SearchShown.Failed(AccessFailure.OFFLINE), searchShown(answered(SearchState.Failed(AccessFailure.OFFLINE)), null))
    }

    @Test
    fun playlistsAreLeftOutAndABlockedShortLeavesTheShelf() {
        val shown = searchShown(answered(SearchState.Ready(listOf(video, playlist), shorts = listOf(short))), blocking(short.id.value))

        assertEquals(SearchShown.Results(listOf(video), emptyList()), shown)
    }

    @Test
    fun everyVideoBlockedWithShortsLeftStillShowsTheShelfAndEverythingBlockedShowsNoResults() {
        val ready = answered(SearchState.Ready(listOf(video), shorts = listOf(short)))

        assertEquals(SearchShown.Results(emptyList(), listOf(short)), searchShown(ready, blocking(video.id.value)))
        assertEquals(SearchShown.NoResults("q"), searchShown(ready, blocking(video.id.value, short.id.value)))
    }

    private fun video(id: String) = VideoItem(VideoId(id), "Video $id", "Someone", "https://www.youtube.com/channel/UC1", 60, 1, null, null, null, isLive = false)

    private fun blocking(vararg ids: String): ContentFilter = ContentFilter.of(
        ProfileSettings(),
        ProfileRules(rules = ids.map { StoredRule(BlockedVideo(it, title = null, channelUrl = null), byOwner = false, addedAtMillis = 0) }),
    )
}
