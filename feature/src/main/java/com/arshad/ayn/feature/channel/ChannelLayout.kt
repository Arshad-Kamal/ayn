package com.arshad.ayn.feature.channel

import com.arshad.ayn.core.access.AccessFailure
import com.arshad.ayn.core.access.ChannelItem
import com.arshad.ayn.core.access.ChannelTab
import com.arshad.ayn.core.access.ListingItem
import com.arshad.ayn.core.filter.ContentFilter
import com.arshad.ayn.core.filter.shows

/** What a channel page draws (`b3-channel`, `m6-subscriptions-and-channel` states), derived from
 *  [ChannelUiState]. Both UIs draw it (M6-D12). */
sealed interface ChannelLayout {
    /** Only the channel's address is known. */
    data object Loading : ChannelLayout

    /** Offline or YouTube's reply unreadable. */
    data class Failed(val failure: AccessFailure) : ChannelLayout

    /** The header over the channel's banner, then its tabs and the selected tab's [body].
     *  [latestVideos] counts the Videos tab's first page once loaded. */
    data class Page(
        val channel: ChannelItem,
        val subscribed: Boolean,
        val tabs: List<ChannelTab>,
        val body: TabBody,
        val latestVideos: Int?,
    ) : ChannelLayout
}

sealed interface TabBody {
    /** The channel's only tab is Shorts, hidden while the profile's Show Shorts is off (M7-D13),
     *  so no tab is selected; Turn on Shorts shows it (Q44). */
    data object ShortsOnly : TabBody
}

/** The selected [tab]'s part of the page. */
sealed interface TabContent : TabBody {
    val tab: ChannelTab

    /** A tab picked for the first time. */
    data class Loading(override val tab: ChannelTab) : TabContent

    data class Empty(override val tab: ChannelTab) : TabContent

    data class Cards(override val tab: ChannelTab, val items: List<ListingItem>) : TabContent
}

/** The selected tab without what [filter] now hides: a block from a card's menu takes its cards
 *  out at once (KP-D19). */
fun channelLayout(state: ChannelUiState, filter: ContentFilter? = null): ChannelLayout = when (val page = state.pageState) {
    ChannelPageState.Loading -> ChannelLayout.Loading
    is ChannelPageState.Failed -> ChannelLayout.Failed(page.failure)
    is ChannelPageState.Ready -> ChannelLayout.Page(
        channel = page.channel,
        subscribed = state.isSubscribed,
        tabs = page.tabs,
        body = tabBody(page, filter),
        latestVideos = page.tabItems[ChannelTab.VIDEOS]?.count { it !is ChannelItem },
    )
}

private fun tabBody(page: ChannelPageState.Ready, filter: ContentFilter?): TabBody {
    val tab = page.selectedTab
    if (page.shortsOnly || tab == null) return TabBody.ShortsOnly
    // A channel's tabs list videos and playlists; a channel card has no place among them.
    val items = page.selectedItems?.filter { it !is ChannelItem && filter.shows(it) } ?: return TabContent.Loading(tab)
    return if (items.isEmpty()) TabContent.Empty(tab) else TabContent.Cards(tab, items)
}

/** The tab's name as the page shows it. */
fun tabLabel(tab: ChannelTab): String = when (tab) {
    ChannelTab.VIDEOS -> "Videos"
    ChannelTab.SHORTS -> "Shorts"
    ChannelTab.LIVE -> "Live"
    ChannelTab.RELEASES -> "Releases"
    ChannelTab.PODCASTS -> "Podcasts"
    ChannelTab.COURSES -> "Courses"
    ChannelTab.PLAYLISTS -> "Playlists"
}
