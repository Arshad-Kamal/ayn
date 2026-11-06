package com.arshad.ayn.ui.tv.search

import com.arshad.ayn.core.access.AccessFailure
import com.arshad.ayn.core.access.ListingItem
import com.arshad.ayn.core.access.PlaylistItem
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.filter.ContentFilter
import com.arshad.ayn.core.filter.shows
import com.arshad.ayn.feature.listingKey
import com.arshad.ayn.feature.search.SearchState
import com.arshad.ayn.feature.search.SearchUiState

internal const val QueryKey = "query"
internal const val EditKey = "edit-search"
internal const val RetryKey = "retry"

/** A result card's focus key. */
internal fun resultKey(item: ListingItem): String = "result:${item.listingKey}"

/** A shelf Short's focus key. */
internal fun shortKey(short: VideoId): String = "short:${short.value}"

/** What Search draws (`b3-search` states), derived from [SearchUiState]. */
internal sealed interface SearchLayout {
    /** [com.arshad.ayn.ui.tv.focus.ScreenFocus]'s entry key: each kind of content takes
     *  focus as it lands; `null` while searching (focus waits for the answer). */
    val entryKey: String?
    val initialFocusKey: String?

    /** States 1–3: the query being typed (keyboard up), suggestions while there are some. */
    data object Typing : SearchLayout {
        override val entryKey: String = "typing"
        override val initialFocusKey: String = QueryKey
    }

    /** State 8: the submitted query heads the page, skeleton cards wait. */
    data class Searching(val query: String) : SearchLayout {
        override val entryKey: String? = null
        override val initialFocusKey: String? = null
    }

    /** States 4–5: the answer, a channel first when YouTube puts one there; M7.m shorts: the
     *  page's Shorts shelf while Show Shorts is on (one of the two is never empty). */
    data class Results(val query: String, val items: List<ListingItem>, val shorts: List<VideoItem>) : SearchLayout {
        override val entryKey: String = "results"
        override val initialFocusKey: String = items.firstOrNull()?.let(::resultKey) ?: shortKey(shorts.first().id)
    }

    /** State 6. */
    data class NoResults(val query: String) : SearchLayout {
        override val entryKey: String = "none"
        override val initialFocusKey: String = EditKey
    }

    /** States 7 and 9: the query is kept for another try. */
    data class Failed(val query: String, val failure: AccessFailure) : SearchLayout {
        override val entryKey: String = "failed"
        override val initialFocusKey: String = RetryKey
    }
}

/** Without what [filter] now hides: a block from a card's menu takes its cards out at once (KP-D19). */
internal fun searchLayout(state: SearchUiState, filter: ContentFilter? = null): SearchLayout {
    val query = state.submittedQuery
    if (state.editing || query == null) return SearchLayout.Typing
    return when (val answer = state.state) {
        SearchState.Idle -> SearchLayout.Typing
        SearchState.Loading -> SearchLayout.Searching(query)
        // The seam lists no playlists in search; nothing on this page could open one.
        is SearchState.Ready -> {
            val items = answer.items.filter { it !is PlaylistItem && filter.shows(it) }
            val shorts = answer.shorts.filter { filter.shows(it) }
            if (items.isEmpty() && shorts.isEmpty()) SearchLayout.NoResults(query) else SearchLayout.Results(query, items, shorts)
        }
        SearchState.Empty -> SearchLayout.NoResults(query)
        is SearchState.Failed -> SearchLayout.Failed(query, answer.failure)
    }
}
