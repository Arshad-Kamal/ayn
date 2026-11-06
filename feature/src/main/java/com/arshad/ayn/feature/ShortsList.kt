package com.arshad.ayn.feature

import com.arshad.ayn.core.access.AccessException
import com.arshad.ayn.core.access.ItemsPage
import com.arshad.ayn.core.access.PageToken
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The Shorts a Short was opened from (M7-D13): shared by the screen that lists them and the
 * player's run through them (Q36), so a page the run fetches shows on that screen too, and
 * [lastPlayed] is the card BACK returns to (Q37). It pages on only when given [fetchPage] (a
 * channel's Shorts tab); a failed page ends paging at the Shorts loaded so far. Main-confined,
 * as its users are (`viewModelScope`).
 */
class ShortsList(
    initial: List<VideoItem>,
    private var next: PageToken? = null,
    private val fetchPage: (suspend (PageToken) -> ItemsPage)? = null,
) {
    private val _shorts = MutableStateFlow(initial.distinctBy { it.id })
    val shorts: StateFlow<List<VideoItem>> = _shorts.asStateFlow()

    private val _lastPlayed = MutableStateFlow<VideoId?>(null)
    val lastPlayed: StateFlow<VideoId?> = _lastPlayed.asStateFlow()

    private val paging = Mutex()

    /** Another page may follow the loaded Shorts. */
    val hasMore: Boolean get() = next != null && fetchPage != null

    /** Fetches the next page and appends its Shorts not already listed; `true` when the list
     *  grew. A call made while a page is on its way waits for it rather than asking again. */
    suspend fun loadMore(): Boolean {
        val sizeBefore = _shorts.value.size
        paging.withLock {
            if (_shorts.value.size != sizeBefore) return true
            val token = next ?: return false
            val fetch = fetchPage ?: return false
            val page = try {
                fetch(token)
            } catch (_: AccessException) {
                next = null
                return false
            }
            next = page.next
            val listed = _shorts.value.mapTo(HashSet()) { it.id }
            _shorts.value += page.shorts.filter { listed.add(it.id) }
        }
        return _shorts.value.size > sizeBefore
    }

    fun played(id: VideoId) {
        _lastPlayed.value = id
    }
}
