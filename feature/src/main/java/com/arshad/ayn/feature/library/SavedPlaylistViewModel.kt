package com.arshad.ayn.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.store.PlaylistStore
import com.arshad.ayn.core.store.PlaylistSummary
import com.arshad.ayn.core.store.WatchStore
import com.arshad.ayn.feature.nav.PlayerRoute
import com.arshad.ayn.feature.player.PlayAll
import com.arshad.ayn.feature.player.playOrder
import kotlin.random.Random
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

/** A saved playlist's page (`b3-library` playlist, plempty). */
sealed interface SavedPlaylistState {
    data object Loading : SavedPlaylistState

    data class Ready(
        val summary: PlaylistSummary,
        /** In saved order; a video is in a playlist once, so rows key by video id. */
        val videos: List<VideoItem>,
        val marks: Map<VideoId, WatchMark>,
    ) : SavedPlaylistState

    /** Deleted, or not the current profile's: the page leaves. */
    data object Gone : SavedPlaylistState

    data object Failed : SavedPlaylistState
}

/** The playlist's own ⋯ sheet and what it leads to (plmenu, plrename, pldelete). */
sealed interface PlaylistSheet {
    data object Options : PlaylistSheet

    /** The name row edited in place. */
    data class Renaming(val naming: PlaylistNaming) : PlaylistSheet

    data object ConfirmDelete : PlaylistSheet
}

data class SavedPlaylistUiState(
    val playlist: SavedPlaylistState = SavedPlaylistState.Loading,
    val sheet: PlaylistSheet? = null,
)

/**
 * One of the profile's own playlists (`b3-library`, M5-D13/D17): its videos with their watch
 * marks, Play all, Shuffle (a fresh seed each time, [random] injected for tests), playing on from
 * a row, renaming and deleting. Removing a video is the card menu's ([VideoMenuViewModel]). The
 * reads and their mapping run on [compute]: every position save re-emits the whole history.
 */
class SavedPlaylistViewModel(
    private val playlistId: Long,
    private val playlistStore: PlaylistStore,
    private val watchStore: WatchStore,
    private val random: Random = Random.Default,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SavedPlaylistUiState())
    val uiState: StateFlow<SavedPlaylistUiState> = _uiState.asStateFlow()

    private var load: Job? = null

    init {
        load()
    }

    fun retry() {
        if (_uiState.value.playlist == SavedPlaylistState.Failed) load()
    }

    /** Play all from the first video, in order; `null` for an empty playlist. */
    fun playAll(): PlayerRoute? {
        val ready = ready() ?: return null
        val first = ready.videos.firstOrNull() ?: return null
        return PlayerRoute(first, ready.playAll(seed = null))
    }

    /** Play all shuffled: a new deal each time, opening on its first video. */
    fun shuffle(): PlayerRoute? {
        val ready = ready()?.takeIf { it.videos.isNotEmpty() } ?: return null
        val seed = random.nextLong()
        return PlayerRoute(playOrder(ready.videos, seed).first(), ready.playAll(seed))
    }

    /** A row's OK: that video, then on through the playlist in order. */
    fun playFrom(video: VideoItem, startOver: Boolean = false): PlayerRoute? =
        ready()?.let { PlayerRoute(video, it.playAll(seed = null), startOver) }

    fun onOptions() {
        if (ready() != null && _uiState.value.sheet == null) show(PlaylistSheet.Options)
    }

    fun onRename() {
        val ready = ready() ?: return
        if (_uiState.value.sheet == PlaylistSheet.Options) show(PlaylistSheet.Renaming(PlaylistNaming(ready.summary.name)))
    }

    fun onRenameChange(name: String) {
        val renaming = _uiState.value.sheet as? PlaylistSheet.Renaming ?: return
        show(PlaylistSheet.Renaming(renaming.naming.edited(name)))
    }

    /** Saves the name and returns to the sheet; Done does nothing while the name is blank, and an
     *  unchanged name writes nothing (a write would stamp the playlist as updated). */
    fun onRenameDone() {
        val renaming = _uiState.value.sheet as? PlaylistSheet.Renaming ?: return
        if (!renaming.naming.canSave) return
        if (renaming.naming.name.trim() == ready()?.summary?.name) return show(PlaylistSheet.Options)
        show(PlaylistSheet.Renaming(renaming.naming.copy(saving = true)))
        viewModelScope.launch {
            val renamed = attempt("playlist rename") { playlistStore.rename(playlistId, renaming.naming.name) } == true
            _uiState.update { state ->
                val current = state.sheet as? PlaylistSheet.Renaming ?: return@update state
                state.copy(sheet = if (renamed) PlaylistSheet.Options else current.copy(naming = current.naming.copy(saving = false)))
            }
        }
    }

    fun onDelete() {
        if (_uiState.value.sheet == PlaylistSheet.Options) show(PlaylistSheet.ConfirmDelete)
    }

    /** The confirm's safe choice: back to the page. */
    fun onKeepPlaylist() {
        if (_uiState.value.sheet == PlaylistSheet.ConfirmDelete) show(null)
    }

    /** Deletes the playlist; the store's flow then turns the page [SavedPlaylistState.Gone]. */
    fun onConfirmDelete() {
        if (_uiState.value.sheet != PlaylistSheet.ConfirmDelete) return
        show(null)
        viewModelScope.launch { attempt("playlist delete") { playlistStore.delete(playlistId) } }
    }

    /** The sheet swiped away or its scrim tapped (the phone, M6-D15): it closes from any stage; a
     *  rename already saving still lands. */
    fun onCloseSheet() {
        show(null)
    }

    /** BACK on the sheet: renaming and the confirm return to the options, the options close. */
    fun onSheetBack() {
        when (val sheet = _uiState.value.sheet) {
            is PlaylistSheet.Renaming -> if (!sheet.naming.saving) show(PlaylistSheet.Options)
            PlaylistSheet.ConfirmDelete -> show(PlaylistSheet.Options)
            PlaylistSheet.Options -> show(null)
            null -> Unit
        }
    }

    private fun load() {
        load?.cancel()
        _uiState.update { it.copy(playlist = SavedPlaylistState.Loading) }
        load = viewModelScope.launch {
            combine(playlistStore.playlists(), playlistStore.videos(playlistId), watchStore.history()) { playlists, saved, history ->
                val summary = playlists.firstOrNull { it.id == playlistId }
                if (summary == null) {
                    SavedPlaylistState.Gone
                } else {
                    SavedPlaylistState.Ready(summary, saved.map { it.video }, history.marks())
                }
            }
                .flowOn(compute)
                .orFailed("playlist read", SavedPlaylistState.Failed)
                .collect { playlist ->
                    _uiState.update { state ->
                        state.copy(playlist = playlist, sheet = state.sheet.takeIf { playlist is SavedPlaylistState.Ready })
                    }
                }
        }
    }

    private fun ready(): SavedPlaylistState.Ready? = _uiState.value.playlist as? SavedPlaylistState.Ready

    private fun show(sheet: PlaylistSheet?) {
        _uiState.update { it.copy(sheet = sheet) }
    }

    private fun SavedPlaylistState.Ready.playAll(seed: Long?): PlayAll.Saved =
        PlayAll.Saved(playlistId, summary.name, videos.size.toLong(), seed)
}
