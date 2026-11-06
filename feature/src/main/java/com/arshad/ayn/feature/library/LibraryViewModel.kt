package com.arshad.ayn.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.store.PlaylistStore
import com.arshad.ayn.core.store.PlaylistSummary
import com.arshad.ayn.core.store.WatchStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the Library hub shows (`b3-library` hub, hubpartial, hubempty). */
sealed interface LibraryState {
    data object Loading : LibraryState

    data class Ready(
        /** The History row: the most recently watched first, at most [HUB_HISTORY_CARDS]. */
        val recent: List<HistoryVideo>,
        /** Everything in history (the row's "214 videos"). */
        val historyCount: Int,
        /** The most recently updated first. */
        val playlists: List<PlaylistSummary>,
        /** In saved order. */
        val watchLater: List<VideoItem>,
        /** Every video's mark, for the Watch later cards. */
        val marks: Map<VideoId, WatchMark>,
    ) : LibraryState {
        /** Nothing watched or saved: the hubempty statement instead of rows. */
        val isEmpty: Boolean get() = historyCount == 0 && playlists.isEmpty() && watchLater.isEmpty()
    }

    /** The store couldn't be read. */
    data object Failed : LibraryState
}

data class LibraryUiState(
    val library: LibraryState = LibraryState.Loading,
    /** A new playlist being named (the New playlist tile or button). */
    val naming: PlaylistNaming? = null,
    /** A playlist just created, for the screen to open; cleared by [LibraryViewModel.onPlaylistOpened]. */
    val createdPlaylistId: Long? = null,
)

/**
 * The Library hub (`b3-library`, M5-D17): History, the profile's own playlists and Watch later,
 * all from the stores' flows for the current profile, and naming a new playlist. The reads and
 * their mapping run on [compute]: every position save re-emits the whole history.
 */
class LibraryViewModel(
    private val watchStore: WatchStore,
    private val playlistStore: PlaylistStore,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    private var load: Job? = null

    init {
        load()
    }

    /** Reads the stores again after a failure. */
    fun retry() {
        if (_uiState.value.library == LibraryState.Failed) load()
    }

    fun onNewPlaylist() {
        _uiState.update { it.copy(naming = PlaylistNaming()) }
    }

    fun onNamingChange(name: String) {
        _uiState.update { state -> state.copy(naming = state.naming?.edited(name)) }
    }

    fun onNamingCancel() {
        _uiState.update { state -> if (state.naming?.saving == true) state else state.copy(naming = null) }
    }

    /** Creates the playlist, then hands its id to the screen ([LibraryUiState.createdPlaylistId]). */
    fun onNamingDone() {
        val naming = _uiState.value.naming?.takeIf { it.canSave } ?: return
        _uiState.update { it.copy(naming = naming.copy(saving = true)) }
        viewModelScope.launch {
            val created = attempt("playlist create") { playlistStore.create(naming.name) }
            _uiState.update { state ->
                if (created != null) {
                    state.copy(naming = null, createdPlaylistId = created)
                } else {
                    state.copy(naming = state.naming?.copy(saving = false))
                }
            }
        }
    }

    fun onPlaylistOpened() {
        _uiState.update { it.copy(createdPlaylistId = null) }
    }

    private fun load() {
        load?.cancel()
        _uiState.update { it.copy(library = LibraryState.Loading) }
        load = viewModelScope.launch {
            combine(watchStore.history(), playlistStore.playlists(), playlistStore.watchLater()) { history, playlists, later ->
                val videos = history.historyVideos()
                LibraryState.Ready(
                    recent = videos.take(HUB_HISTORY_CARDS),
                    historyCount = videos.size,
                    playlists = playlists,
                    watchLater = later.map { it.video },
                    marks = history.marks(),
                ) as LibraryState
            }
                .flowOn(compute)
                .orFailed("library read", LibraryState.Failed)
                .collect { library -> _uiState.update { it.copy(library = library) } }
        }
    }

    companion object {
        /** The hub's History row; All history has the rest. */
        const val HUB_HISTORY_CARDS = 12
    }
}
