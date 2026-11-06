package com.arshad.ayn.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arshad.ayn.core.store.WatchStore
import java.time.ZoneId
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** History by day (`b3-library` history, empty). */
sealed interface HistoryState {
    data object Loading : HistoryState

    /** No [days]: the empty statement. */
    data class Ready(val days: List<HistoryDay>, val count: Int) : HistoryState

    data object Failed : HistoryState
}

data class HistoryUiState(
    val history: HistoryState = HistoryState.Loading,
    /** The clear-history confirm shows (`b3-library` confirm). */
    val confirmingClear: Boolean = false,
)

/**
 * The History page (`b3-library`): the current profile's history by day, and Clear history with
 * its confirm. Removing one video is the card menu's ([VideoMenuViewModel]). Days are named as
 * seen in [zone] when the history is read, with [clock] (injected for tests). The read and its
 * grouping run on [compute]: every position save re-emits the whole history.
 */
class HistoryViewModel(
    private val watchStore: WatchStore,
    private val clock: Clock = Clock.System,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    private var load: Job? = null

    init {
        load()
    }

    fun retry() {
        if (_uiState.value.history == HistoryState.Failed) load()
    }

    /** Only when there is history to clear. */
    fun onClearHistory() {
        val history = _uiState.value.history as? HistoryState.Ready ?: return
        if (history.count > 0) _uiState.update { it.copy(confirmingClear = true) }
    }

    fun onKeepHistory() {
        _uiState.update { it.copy(confirmingClear = false) }
    }

    /** Clears every entry, resume point and watched mark; the confirm closes either way (a
     *  failed clear leaves the list as it was). */
    fun onConfirmClear() {
        if (!_uiState.value.confirmingClear) return
        _uiState.update { it.copy(confirmingClear = false) }
        viewModelScope.launch { attempt("history clear") { watchStore.clear() } }
    }

    private fun load() {
        load?.cancel()
        _uiState.update { it.copy(history = HistoryState.Loading) }
        load = viewModelScope.launch {
            watchStore.history()
                .map { entries ->
                    val videos = entries.historyVideos()
                    HistoryState.Ready(historyDays(videos, clock.now(), zone), videos.size) as HistoryState
                }
                .flowOn(compute)
                .orFailed("history read", HistoryState.Failed)
                .collect { history ->
                    _uiState.update { state ->
                        // Nothing left to clear: a confirm still open has nothing to ask about.
                        val empty = (history as? HistoryState.Ready)?.count == 0
                        state.copy(history = history, confirmingClear = state.confirmingClear && !empty)
                    }
                }
        }
    }
}
