package com.arshad.ayn.feature.search

import com.arshad.ayn.core.access.ChannelItem
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.access.newpipe.CHANNEL_SEARCH_FLOW
import com.arshad.ayn.core.access.newpipe.CHANNEL_SEARCH_QUERY
import com.arshad.ayn.core.access.newpipe.FIXED_SEARCH_QUERY
import com.arshad.ayn.core.access.newpipe.FixtureDownloader
import com.arshad.ayn.core.access.newpipe.NewPipeAccess
import com.arshad.ayn.core.access.newpipe.RecordedChannelSearch
import com.arshad.ayn.core.access.newpipe.RecordedSearch
import com.arshad.ayn.core.access.newpipe.RecordedSuggestions
import com.arshad.ayn.core.access.newpipe.SUGGESTIONS_FLOW
import com.arshad.ayn.core.access.newpipe.SUGGESTION_QUERIES
import com.arshad.ayn.core.store.FakeProfileSettingsStore
import com.arshad.ayn.core.store.ProfileSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Cross-layer: the recorded search replayed through the real seam into [SearchViewModel]. */
class SearchViewModelFixtureTest {

    private val dispatcher = StandardTestDispatcher()
    private val settings = FakeProfileSettingsStore(selectedId = 1L)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun recordedSearchSettlesReadyWithItsVideos() = runTest(dispatcher) {
        val access = NewPipeAccess(downloader = FixtureDownloader("search"), io = dispatcher)
        val viewModel = SearchViewModel(access, settings)

        viewModel.onQueryChange(FIXED_SEARCH_QUERY)
        viewModel.submit()
        advanceUntilIdle()

        val items = (viewModel.uiState.value.state as SearchState.Ready).items
        // This answer holds no channel card: videos only.
        assertTrue(items.all { it is VideoItem })
        assertEquals(RecordedSearch.PAGE_1_VIDEOS, items.size)
        assertEquals(RecordedSearch.PAGE_1_FIRST_ID, (items.first() as VideoItem).id.value)
    }

    @Test
    fun recordedSearchShowsItsShortsShelfOnlyWhileShowShortsIsOn() = runTest(dispatcher) {
        val access = NewPipeAccess(downloader = FixtureDownloader("search"), io = dispatcher)
        val viewModel = SearchViewModel(access, settings)

        viewModel.onQueryChange(FIXED_SEARCH_QUERY)
        viewModel.submit()
        advanceUntilIdle()
        assertEquals(emptyList<VideoItem>(), (viewModel.uiState.value.state as SearchState.Ready).shorts)

        settings.byProfile.value = mapOf(1L to ProfileSettings(showShorts = true))
        advanceUntilIdle()

        val shorts = (viewModel.uiState.value.state as SearchState.Ready).shorts
        assertEquals(RecordedSearch.PAGE_1_SHORTS, shorts.size)
        assertEquals(RecordedSearch.PAGE_1_FIRST_SHORT_ID, shorts.first().id.value)
        assertEquals(RecordedSearch.PAGE_1_LAST_SHORT_ID, shorts.last().id.value)
        // The shelf carries no channel line: YouTube sends none (Q43).
        assertTrue(shorts.all { it.uploaderName == null })
    }

    @Test
    fun recordedChannelSearchLeadsWithTheChannel() = runTest(dispatcher) {
        val access = NewPipeAccess(downloader = FixtureDownloader(CHANNEL_SEARCH_FLOW), io = dispatcher)
        val viewModel = SearchViewModel(access, settings)

        viewModel.onQueryChange(CHANNEL_SEARCH_QUERY)
        viewModel.submit()
        advanceUntilIdle()

        val items = (viewModel.uiState.value.state as SearchState.Ready).items
        assertEquals(RecordedChannelSearch.CHANNEL_URL, (items.first() as ChannelItem).url)
        assertEquals(1 + RecordedChannelSearch.VIDEOS, items.size)
    }

    /** The recorded answers in query order (one replay session); YouTube's echo of the typed
     *  query never shows as a suggestion, and referenced characters arrive decoded. */
    @Test
    fun recordedSuggestionsFollowTheTypedQuery() = runTest(dispatcher) {
        val access = NewPipeAccess(downloader = FixtureDownloader(SUGGESTIONS_FLOW), io = dispatcher)
        val viewModel = SearchViewModel(access, settings)
        val (latin, tokyo, nonsense) = SUGGESTION_QUERIES

        viewModel.onQueryChange(latin)
        advanceUntilIdle()
        assertEquals(RecordedSuggestions.LOFI_HIP - latin, viewModel.uiState.value.suggestions)

        viewModel.onQueryChange(tokyo)
        advanceUntilIdle()
        assertEquals(RecordedSuggestions.TOKYO - tokyo, viewModel.uiState.value.suggestions)

        viewModel.onQueryChange(nonsense)
        advanceUntilIdle()
        assertEquals(emptyList<String>(), viewModel.uiState.value.suggestions)
    }
}
