package com.arshad.ayn.feature.search

import com.arshad.ayn.core.access.AccessException
import com.arshad.ayn.core.access.AccessFailure
import com.arshad.ayn.core.access.ItemsPage
import com.arshad.ayn.core.access.PageToken
import com.arshad.ayn.core.store.FakeProfileSettingsStore
import com.arshad.ayn.core.store.ProfileSettings
import com.arshad.ayn.feature.UnsupportedExtractor
import com.arshad.ayn.feature.listingKey
import com.arshad.ayn.feature.testChannel
import com.arshad.ayn.feature.testVideo
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * SearchViewModel tests against a fake extractor (KT-TEST-01): submit → Ready with the
 * mixed listing kinds deduped by key, empty → Empty, AccessException → Failed with its kind,
 * typing vs showing an answer (editing), the submit guards (blank ignored, double-submit while loading ignored, query trimmed), and the
 * debounced suggestions while typing.
 */
class SearchViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val extractor = FakeSearchExtractor()
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
    fun submitSearchesAndDedupesByListingKey() = runTest(dispatcher) {
        extractor.result = ItemsPage(
            items = listOf(
                testVideo("aaaaaaaaaaa"),
                testVideo("aaaaaaaaaaa"), // duplicate video id — one grid key
                testChannel(),
            ),
            next = null,
        )
        val viewModel = SearchViewModel(extractor, settings)

        viewModel.onQueryChange("  marantz 2270  ")
        viewModel.submit()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("marantz 2270", state.submittedQuery) // trimmed at submit
        val ready = state.state as SearchState.Ready
        assertEquals(2, ready.items.size)
    }

    private fun showShorts(on: Boolean) {
        settings.byProfile.value = mapOf(1L to ProfileSettings(showShorts = on))
    }

    private val shortsPage = ItemsPage(
        items = listOf(testVideo("aaaaaaaaaaa")),
        next = null,
        shorts = listOf(testVideo("sssssssss01"), testVideo("sssssssss02")),
    )

    private fun SearchViewModel.answer(query: String = "veritasium"): SearchState {
        onQueryChange(query)
        submit()
        return uiState.value.state
    }

    @Test
    fun theShelfHoldsThePagesShortsWhileShowShortsIsOn() = runTest(dispatcher) {
        showShorts(true)
        extractor.result = shortsPage
        val viewModel = SearchViewModel(extractor, settings)

        viewModel.answer()
        advanceUntilIdle()

        val ready = viewModel.uiState.value.state as SearchState.Ready
        assertEquals(listOf("sssssssss01", "sssssssss02"), ready.shorts.map { it.id.value })
        assertEquals(listOf("aaaaaaaaaaa"), ready.items.map { it.listingKey })
    }

    @Test
    fun withShowShortsOffTheAnswerShowsNoShort() = runTest(dispatcher) {
        extractor.result = shortsPage
        val viewModel = SearchViewModel(extractor, settings)

        viewModel.answer()
        advanceUntilIdle()

        assertEquals(SearchState.Ready(listOf(testVideo("aaaaaaaaaaa"))), viewModel.uiState.value.state)
    }

    @Test
    fun anAnswerOfOnlyShortsIsEmptyWhileShowShortsIsOff() = runTest(dispatcher) {
        extractor.result = shortsPage.copy(items = emptyList())
        val viewModel = SearchViewModel(extractor, settings)

        viewModel.answer()
        advanceUntilIdle()

        assertEquals(SearchState.Empty, viewModel.uiState.value.state)
    }

    @Test
    fun turningShowShortsOnShowsTheKeptAnswersShelfWithoutAskingAgain() = runTest(dispatcher) {
        extractor.result = shortsPage.copy(items = emptyList())
        val viewModel = SearchViewModel(extractor, settings)
        viewModel.answer()
        advanceUntilIdle()

        showShorts(true)
        advanceUntilIdle()

        assertEquals(SearchState.Ready(emptyList(), shortsPage.shorts), viewModel.uiState.value.state)
        assertEquals(1, extractor.calls)

        showShorts(false)
        advanceUntilIdle()

        assertEquals(SearchState.Empty, viewModel.uiState.value.state)
    }

    @Test
    fun aShortOpensWithTheShelfAsItsList() = runTest(dispatcher) {
        showShorts(true)
        extractor.result = shortsPage
        val viewModel = SearchViewModel(extractor, settings)
        viewModel.answer()
        advanceUntilIdle()

        val list = viewModel.openShorts()

        assertEquals(shortsPage.shorts, list.shorts.value)
        assertFalse(list.hasMore)
    }

    @Test
    fun backReturnsToTheLastShortPlayedFromTheHandedListOnce() = runTest(dispatcher) {
        showShorts(true)
        extractor.result = shortsPage
        val viewModel = SearchViewModel(extractor, settings)
        viewModel.answer()
        advanceUntilIdle()
        assertNull(viewModel.shortsReturn())

        viewModel.openShorts().played(shortsPage.shorts.last().id)

        assertEquals(shortsPage.shorts.last().id, viewModel.shortsReturn())
        viewModel.shortsReturned()
        assertNull(viewModel.shortsReturn())
    }

    @Test
    fun emptyResultsSurfaceTheEmptyState() = runTest(dispatcher) {
        extractor.result = ItemsPage(items = emptyList(), next = null)
        val viewModel = SearchViewModel(extractor, settings)

        viewModel.onQueryChange("xyzzy quartz")
        viewModel.submit()
        advanceUntilIdle()

        assertEquals(SearchState.Empty, viewModel.uiState.value.state)
    }

    @Test
    fun anUnreadableReplySurfacesAsUnreadable() = runTest(dispatcher) {
        extractor.failure = RuntimeException("parse")
        val viewModel = SearchViewModel(extractor, settings)

        viewModel.onQueryChange("anything")
        viewModel.submit()
        advanceUntilIdle()

        assertEquals(SearchState.Failed(AccessFailure.UNREADABLE), viewModel.uiState.value.state)
    }

    @Test
    fun noConnectionSurfacesAsOfflineAndKeepsTheQuery() = runTest(dispatcher) {
        extractor.failure = IOException("no route")
        val viewModel = SearchViewModel(extractor, settings)

        viewModel.onQueryChange("anything")
        viewModel.submit()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(SearchState.Failed(AccessFailure.OFFLINE), state.state)
        assertEquals("anything", state.query)
    }

    @Test
    fun submittingLeavesTypingAndEditSearchReturnsWithTheAnswerKept() = runTest(dispatcher) {
        extractor.result = ItemsPage(items = listOf(testVideo("aaaaaaaaaaa")), next = null)
        val viewModel = SearchViewModel(extractor, settings)
        assertTrue(viewModel.uiState.value.editing)

        viewModel.onQueryChange("marantz")
        viewModel.submit()
        assertFalse(viewModel.uiState.value.editing)
        advanceUntilIdle()
        viewModel.editSearch()

        val state = viewModel.uiState.value
        assertTrue(state.editing)
        assertEquals("marantz", state.query)
        assertTrue(state.state is SearchState.Ready)
    }

    @Test
    fun editingWhileASearchRunsDropsItAndTheNextSubmitSearchesTheNewQuery() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        extractor.searchGate = gate
        extractor.result = ItemsPage(items = listOf(testVideo("aaaaaaaaaaa")), next = null)
        val viewModel = SearchViewModel(extractor, settings)
        viewModel.onQueryChange("marantz")
        viewModel.submit()
        advanceUntilIdle() // held at the gate, Loading

        viewModel.editSearch()
        assertEquals(SearchState.Idle, viewModel.uiState.value.state)
        extractor.searchGate = null
        viewModel.onQueryChange("marantz 2270")
        viewModel.submit()
        gate.complete(Unit)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("marantz", "marantz 2270"), extractor.queries)
        assertEquals("marantz 2270", state.submittedQuery)
        assertFalse(state.editing)
        assertTrue(state.state is SearchState.Ready)
    }

    /** The real seam's blocking call runs on past a cancel and its failure outranks it. */
    @Test
    fun aDroppedSearchAnsweringLateNeverReplacesTheKeptAnswer() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        extractor.searchGate = gate
        extractor.ignoresCancellation = true
        val viewModel = SearchViewModel(extractor, settings)
        viewModel.onQueryChange("dropped")
        viewModel.submit()
        advanceUntilIdle() // held at the gate, Loading

        viewModel.editSearch()
        extractor.searchGate = null
        extractor.result = shortsPage
        viewModel.onQueryChange("kept")
        viewModel.submit()
        advanceUntilIdle()
        extractor.result = ItemsPage(items = listOf(testVideo("ddddddddddd")), next = null, shorts = listOf(testVideo("sssssssssdd")))
        gate.complete(Unit) // the dropped search now answers
        advanceUntilIdle()
        showShorts(true) // a re-show reads the kept answer
        advanceUntilIdle()

        assertEquals(SearchState.Ready(shortsPage.items, shortsPage.shorts), viewModel.uiState.value.state)
    }

    @Test
    fun aDroppedSearchFailingLateNeverOverwritesTheNextAnswer() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        extractor.searchGate = gate
        extractor.ignoresCancellation = true
        extractor.failure = IOException("timeout")
        extractor.result = ItemsPage(items = listOf(testVideo("aaaaaaaaaaa")), next = null)
        val viewModel = SearchViewModel(extractor, settings)
        viewModel.onQueryChange("marantz")
        viewModel.submit()
        advanceUntilIdle() // held at the gate, Loading

        viewModel.editSearch()
        extractor.searchGate = null
        extractor.failure = null
        viewModel.onQueryChange("marantz 2270")
        viewModel.submit()
        advanceUntilIdle()
        extractor.failure = IOException("timeout")
        gate.complete(Unit) // the dropped search now fails
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("marantz 2270", state.submittedQuery)
        assertTrue(state.state is SearchState.Ready)
    }

    @Test
    fun resubmittingTheAnsweredQueryShowsTheKeptAnswerWithoutAsking() = runTest(dispatcher) {
        extractor.result = ItemsPage(items = listOf(testVideo("aaaaaaaaaaa")), next = null)
        val viewModel = SearchViewModel(extractor, settings)
        viewModel.onQueryChange("marantz")
        viewModel.submit()
        advanceUntilIdle()

        viewModel.editSearch()
        viewModel.submit()
        advanceUntilIdle()

        assertEquals(1, extractor.calls)
        assertFalse(viewModel.uiState.value.editing)
        assertTrue(viewModel.uiState.value.state is SearchState.Ready)
    }

    @Test
    fun aFailedQueryIsAskedAgain() = runTest(dispatcher) {
        extractor.result = ItemsPage(items = listOf(testVideo("aaaaaaaaaaa")), next = null)
        extractor.failure = IOException("no route")
        val viewModel = SearchViewModel(extractor, settings)
        viewModel.onQueryChange("marantz")
        viewModel.submit()
        advanceUntilIdle()

        extractor.failure = null
        viewModel.submit()
        advanceUntilIdle()

        assertEquals(2, extractor.calls)
        assertTrue(viewModel.uiState.value.state is SearchState.Ready)
    }

    @Test
    fun anIgnoredSubmitStaysTyping() = runTest(dispatcher) {
        val viewModel = SearchViewModel(extractor, settings)

        viewModel.onQueryChange("   ")
        viewModel.submit()

        assertTrue(viewModel.uiState.value.editing)
    }

    @Test
    fun doubleSubmitWhileLoadingIsIgnored() = runTest(dispatcher) {
        val viewModel = SearchViewModel(extractor, settings)

        viewModel.onQueryChange("marantz")
        viewModel.submit() // coroutine queued, still Loading
        viewModel.submit()
        advanceUntilIdle()

        assertEquals(1, extractor.calls)
    }

    @Test
    fun blankQueriesAreIgnored() = runTest(dispatcher) {
        val viewModel = SearchViewModel(extractor, settings)

        viewModel.onQueryChange("   ")
        viewModel.submit()
        advanceUntilIdle()

        assertEquals(SearchState.Idle, viewModel.uiState.value.state)
    }

    @Test
    fun typingShowsSuggestionsAfterTheDebounceWithoutTheQueryItself() = runTest(dispatcher) {
        extractor.suggestionAnswers["enigma mac "] = listOf("enigma machine", "Enigma Mac", "enigma machine explained")
        val viewModel = SearchViewModel(extractor, settings)

        viewModel.onQueryChange("enigma mac ")
        advanceTimeBy(119)
        runCurrent()
        assertEquals(emptyList<String>(), extractor.suggestionQueries)
        advanceTimeBy(1)
        runCurrent()

        val state = viewModel.uiState.value
        assertEquals(listOf("enigma mac "), extractor.suggestionQueries)
        assertEquals("enigma mac ", state.query)
        assertEquals(listOf("enigma machine", "enigma machine explained"), state.suggestions)
    }

    @Test
    fun aBurstOfKeystrokesAsksOnceForTheLatestQuery() = runTest(dispatcher) {
        val viewModel = SearchViewModel(extractor, settings)

        listOf("e", "en", "eni").forEach {
            viewModel.onQueryChange(it)
            advanceTimeBy(50)
        }
        advanceUntilIdle()

        assertEquals(listOf("eni"), extractor.suggestionQueries)
    }

    @Test
    fun anAnswerForASupersededQueryNeverShows() = runTest(dispatcher) {
        val staleGate = CompletableDeferred<Unit>()
        extractor.suggestionGates["en"] = staleGate
        extractor.suggestionAnswers["en"] = listOf("enigma")
        extractor.suggestionAnswers["eni"] = listOf("enid blyton")
        val viewModel = SearchViewModel(extractor, settings)
        viewModel.onQueryChange("en")
        advanceUntilIdle() // the "en" ask is in flight, held at its gate

        viewModel.onQueryChange("eni")
        advanceUntilIdle()
        staleGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("en", "eni"), extractor.suggestionQueries)
        assertEquals(listOf("enid blyton"), viewModel.uiState.value.suggestions)
    }

    @Test
    fun aSupersededAskFailingLateNeverClearsNewerSuggestions() = runTest(dispatcher) {
        val staleGate = CompletableDeferred<Unit>()
        extractor.ignoresCancellation = true
        extractor.suggestionGates["en"] = staleGate
        extractor.suggestionAnswers["eni"] = listOf("enid blyton")
        val viewModel = SearchViewModel(extractor, settings)
        viewModel.onQueryChange("en")
        advanceUntilIdle() // the "en" ask is in flight, held through its cancel

        viewModel.onQueryChange("eni")
        advanceUntilIdle()
        extractor.failSuggestions = true
        staleGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("enid blyton"), viewModel.uiState.value.suggestions)
    }

    @Test
    fun clearingTheQueryClearsSuggestionsWithoutAsking() = runTest(dispatcher) {
        val viewModel = typedWithSuggestions()

        viewModel.onQueryChange("  ")
        advanceUntilIdle()

        assertEquals(emptyList<String>(), viewModel.uiState.value.suggestions)
        assertEquals(listOf("en"), extractor.suggestionQueries)
    }

    @Test
    fun aSuggestionFailureClearsSuggestionsAndLeavesSearchIdle() = runTest(dispatcher) {
        val viewModel = typedWithSuggestions()

        extractor.failSuggestions = true
        viewModel.onQueryChange("eni")
        advanceUntilIdle()

        assertEquals(emptyList<String>(), viewModel.uiState.value.suggestions)
        assertEquals(SearchState.Idle, viewModel.uiState.value.state)
    }

    @Test
    fun submittingClearsSuggestionsAndCancelsAPendingAsk() = runTest(dispatcher) {
        val viewModel = typedWithSuggestions()

        viewModel.onQueryChange("enigma")
        viewModel.submit()
        advanceUntilIdle()

        assertEquals(emptyList<String>(), viewModel.uiState.value.suggestions)
        assertEquals(listOf("en"), extractor.suggestionQueries)
        assertEquals("enigma", viewModel.uiState.value.query)
    }

    @Test
    fun theSameTextAgainAfterSubmitAsksForNoSuggestions() = runTest(dispatcher) {
        val viewModel = typedWithSuggestions()
        viewModel.submit()
        advanceUntilIdle()

        viewModel.onQueryChange("en")
        advanceUntilIdle()

        assertEquals(listOf("en"), extractor.suggestionQueries)
        assertEquals(emptyList<String>(), viewModel.uiState.value.suggestions)
    }

    /** "en" typed and answered with ["enigma"], so a clearing test starts from shown suggestions. */
    private fun TestScope.typedWithSuggestions(): SearchViewModel {
        extractor.suggestionAnswers["en"] = listOf("enigma")
        val viewModel = SearchViewModel(extractor, settings)
        viewModel.onQueryChange("en")
        advanceUntilIdle()
        assertEquals(listOf("enigma"), viewModel.uiState.value.suggestions)
        return viewModel
    }
}

private class FakeSearchExtractor : UnsupportedExtractor("search-only") {
    var result: ItemsPage = ItemsPage(items = emptyList(), next = null)
    /** Thrown as the search failure's cause (an IOException reads as offline). */
    var failure: Throwable? = null
    var calls = 0
    val queries = mutableListOf<String>()

    /** Searches suspend here until completed (in-flight pins). */
    var searchGate: CompletableDeferred<Unit>? = null

    /** The gate holds through a cancel, like the seam's blocking extractor call. */
    var ignoresCancellation = false
    val suggestionAnswers = mutableMapOf<String, List<String>>()
    val suggestionQueries = mutableListOf<String>()
    var failSuggestions = false

    /** A query whose ask suspends here until completed (in-flight pins). */
    val suggestionGates = mutableMapOf<String, CompletableDeferred<Unit>>()

    override suspend fun search(query: String, page: PageToken?): ItemsPage {
        calls++
        queries += query
        searchGate?.let { gate ->
            if (ignoresCancellation) withContext(NonCancellable) { gate.await() } else gate.await()
        }
        failure?.let { throw AccessException("search down", it) }
        return result
    }
    override suspend fun suggestions(query: String): List<String> {
        suggestionQueries += query
        suggestionGates[query]?.let { gate ->
            if (ignoresCancellation) withContext(NonCancellable) { gate.await() } else gate.await()
        }
        if (failSuggestions) throw AccessException("suggest down", RuntimeException())
        return suggestionAnswers.getOrDefault(query, emptyList())
    }
}
