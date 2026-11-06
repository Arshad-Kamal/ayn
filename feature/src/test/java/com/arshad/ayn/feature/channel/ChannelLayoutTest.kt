package com.arshad.ayn.feature.channel

import com.arshad.ayn.core.access.ChannelItem
import com.arshad.ayn.core.access.ChannelTab
import com.arshad.ayn.core.access.ListingItem
import com.arshad.ayn.core.access.PlaylistItem
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.filter.ContentFilter
import com.arshad.ayn.core.filter.blockingVideos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What a ready channel page draws below its header, and the latest-videos count. */
class ChannelLayoutTest {

    private val channel = ChannelItem(name = "C", url = URL, avatarUrl = null, subscriberCount = 1, description = null, verified = false)
    private val playlist = PlaylistItem(title = "P", url = "$URL/p", thumbnailUrl = null, videoCount = 2, uploaderName = null)

    private fun page(
        selected: ChannelTab?,
        items: Map<ChannelTab, List<ListingItem>>,
        shortsOnly: Boolean = false,
        filter: ContentFilter? = null,
    ) =
        channelLayout(
            ChannelUiState(
                ChannelPageState.Ready(
                    channel = channel,
                    pageTabs = if (shortsOnly) listOf(ChannelTab.SHORTS) else listOfNotNull(selected),
                    showShorts = false,
                    selectedTab = selected,
                    tabItems = items,
                ),
            ),
            filter,
        ) as ChannelLayout.Page

    @Test
    fun aSelectedTabNotYetLoadedIsLoading() {
        assertEquals(TabContent.Loading(ChannelTab.PLAYLISTS), page(ChannelTab.PLAYLISTS, emptyMap()).body)
    }

    @Test
    fun aTabAnsweringOnlyWithChannelCardsIsEmpty() {
        assertEquals(TabContent.Empty(ChannelTab.VIDEOS), page(ChannelTab.VIDEOS, mapOf(ChannelTab.VIDEOS to listOf(channel))).body)
    }

    @Test
    fun aBlockedCardLeavesTheTabAndABlockOfEveryCardLeavesItEmpty() {
        val video = VideoItem(VideoId("vvvvvvvvvvv"), "v", "C", URL, 60, 1, null, null, null, isLive = false)
        val tab = mapOf(ChannelTab.VIDEOS to listOf(video, playlist))

        assertEquals(TabContent.Cards(ChannelTab.VIDEOS, listOf(playlist)), page(ChannelTab.VIDEOS, tab, filter = blockingVideos(video.id.value)).body)
        val onlyVideo = mapOf(ChannelTab.VIDEOS to listOf(video))
        assertEquals(TabContent.Empty(ChannelTab.VIDEOS), page(ChannelTab.VIDEOS, onlyVideo, filter = blockingVideos(video.id.value)).body)
    }

    @Test
    fun aShortsOnlyChannelHasNoTabContent() {
        assertEquals(TabBody.ShortsOnly, page(null, emptyMap(), shortsOnly = true).body)
    }

    @Test
    fun theLatestCountLeavesOutChannelCardsLikeTheTabDoes() {
        assertEquals(0, page(ChannelTab.VIDEOS, mapOf(ChannelTab.VIDEOS to listOf(channel))).latestVideos)
    }

    @Test
    fun theLatestCountIsTheVideosTabsFirstPageOnceLoaded() {
        assertNull(page(ChannelTab.PLAYLISTS, mapOf(ChannelTab.PLAYLISTS to listOf(playlist))).latestVideos)
        assertEquals(1, page(ChannelTab.PLAYLISTS, mapOf(ChannelTab.PLAYLISTS to listOf(playlist), ChannelTab.VIDEOS to listOf(playlist))).latestVideos)
    }

    private companion object {
        const val URL = "https://www.youtube.com/channel/UCc"
    }
}
