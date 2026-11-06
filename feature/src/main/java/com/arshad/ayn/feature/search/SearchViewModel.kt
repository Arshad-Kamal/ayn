package com.arshad.ayn.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arshad.ayn.core.access.AccessException
import com.arshad.ayn.core.access.AccessFailure
import com.arshad.ayn.core.access.Extractor
import com.arshad.ayn.core.access.ItemsPage
import com.arshad.ayn.core.access.ListingItem
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.store.ProfileSettingsStore
import com.arshad.ayn.feature.ShortsList
import com.arshad.ayn.feature.listingKey
import com.arshad.ayn.feature.showsShorts
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Search state (ARCH-1/3). Results are submit-driven; while typing, YouTube's suggestions
 * follow the query after a short debounce (ui-rebuild B-D6: the query is always visible,
 * suggestions sit above the keyboard). Model discipline: val-only, collections frozen at
 * construction.
 */
sealed interface SearchState {
    data object Idle : SearchState

    data object Loading : SearchState

    data class Ready(
        val items: List<ListingItem>,
        /** The page's Shorts shelf (Q43); empty while the profile's Show Shorts is off. */
        val shorts: List<VideoItem> = emptyList(),
    ) : SearchState

    /** Nothing to show: no result, and no Short while Show Shorts is on. */
    data object Empty : SearchState

    data class Failed(val failure: AccessFailure) : SearchState
}

data class SearchUiState(
    val query: String = "",
    /** Completions of the latest answered query, without that query itself; kept while the
     *  next answer is on its way, empty once results are asked for. */
    val suggestions: List<String> = emptyList(),
    val submittedQuery: String? = null,
    val state: SearchState = SearchState.Idle,
    /** The query is being typed (keyboard up, suggestions); false while a submitted search's
     *  answer shows. Edit search returns to typing with that answer kept. */
    val editing: Boolean = true,
    /** The submitted query's answer is a kids profile's local match over what the owner approved
     *  ([ApprovedSearch]), not YouTube's results. */
    val approvedOnly: Boolean = false,
)

/**
 * Search (see [SearchState]). A kids profile's query is answered by [approvedSearch], a local
 * match; without one, its YouTube search answers empty at the seam (KP-D10).
 */
class SearchViewModel(
    private val extractor: Extractor,
    settingsStore: ProfileSettingsStore,
    private val approvedSearch: ApprovedSearch? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var suggestionsJob: Job? = null
    private var searchJob: Job? = null

    /** The submitted query's results with its Shorts, whatever Show Shorts is (M7-D13). */
    private var answer: ItemsPage? = null
    private var showShorts = false
    private var handedShorts: ShortsList? = null

    init {
        viewModelScope.launch {
            settingsStore.showsShorts().collect { on ->
                showShorts = on
                answer?.let { answered -> _uiState.update { it.copy(state = shown(answered)) } }
            }
        }
    }

    fun onQueryChange(query: String) {
        // A field re-emitting its text (focus or selection changes) is no new input: asking
        // again would bring suggestions back over submitted results.
        if (query == _uiState.value.query) return
        _uiState.update { it.copy(query = query) }
        suggestionsJob?.cancel()
        if (query.isBlank()) {
            _uiState.update { it.copy(suggestions = emptyList()) }
            return
        }
        suggestionsJob = viewModelScope.launch {
            delay(SUGGESTIONS_DEBOUNCE)
            // Stale completions of an older prefix would mislead, so a failure clears them;
            // suggestions are an aid, never an error state.
            val typed = query.trim()
            val suggestions = try {
                extractor.suggestions(query).filterNot { it.equals(typed, ignoreCase = true) }
            } catch (e: AccessException) {
                emptyList()
            }
            // The blocking extractor call ignores cancellation and its failure outranks it.
            ensureActive()
            _uiState.update { it.copy(suggestions = suggestions) }
        }
    }

    fun submit() {
        val current = _uiState.value
        val query = current.query.trim()
        if (query.isEmpty() || current.state is SearchState.Loading) return
        suggestionsJob?.cancel()
        // The kept answer to the same query shows again without a refetch; a failure retries.
        if (query == current.submittedQuery && (current.state is SearchState.Ready || current.state == SearchState.Empty)) {
            _uiState.update { it.copy(suggestions = emptyList(), editing = false) }
            return
        }
        _uiState.update {
            it.copy(submittedQuery = query, suggestions = emptyList(), state = SearchState.Loading, editing = false)
        }
        answer = null
        searchJob = viewModelScope.launch {
            var approvedOnly = false
            val newState = try {
                val approved = approvedSearch?.search(query)
                approvedOnly = approved != null
                val page = if (approved != null) ItemsPage(approved, next = null) else extractor.search(query)
                // A dropped search (editSearch) must not write: its blocking call can return, or
                // fail (below), after the cancel.
                ensureActive()
                ItemsPage(page.items.distinctBy { it.listingKey }, next = null, shorts = page.shorts)
                    .also { answer = it }
                    .let(::shown)
            } catch (e: AccessException) {
                SearchState.Failed(e.failure)
            }
            ensureActive() // a dropped search failing late
            _uiState.update { it.copy(state = newState, approvedOnly = approvedOnly) }
        }
    }

    /** The list the shelf's Short is opened from (Q36): the shelf as it shows now, kept for
     *  [shortsReturn]. */
    fun openShorts(): ShortsList =
        ShortsList((_uiState.value.state as? SearchState.Ready)?.shorts.orEmpty()).also { handedShorts = it }

    /** The Short BACK from the player returns focus to (Q37): the last one played from the list
     *  handed over, until [shortsReturned]. */
    fun shortsReturn(): VideoId? = handedShorts?.lastPlayed?.value

    fun shortsReturned() {
        handedShorts = null
    }

    private fun shown(page: ItemsPage): SearchState {
        val shorts = if (showShorts) page.shorts else emptyList()
        return if (page.items.isEmpty() && shorts.isEmpty()) SearchState.Empty else SearchState.Ready(page.items, shorts)
    }

    /** Back to typing the query (Edit search, or BACK on an answer); a blank page is typing
     *  already. A search still running is dropped, so the next submit starts afresh. */
    fun editSearch() {
        if (_uiState.value.state is SearchState.Loading) {
            searchJob?.cancel()
            _uiState.update { it.copy(state = SearchState.Idle, editing = true) }
        } else {
            _uiState.update { it.copy(editing = true) }
        }
    }

    private companion object {
        /** NewPipe's suggestion debounce (`SearchFragment.SUGGESTIONS_DEBOUNCE`, checked
         *  2026-10-03); voice input lands whole, so this mostly spares fast typists. */
        val SUGGESTIONS_DEBOUNCE = 120.milliseconds
    }
}
