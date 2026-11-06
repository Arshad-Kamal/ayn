package com.arshad.ayn.core.access.newpipe

import com.arshad.ayn.core.access.ChannelTab
import com.arshad.ayn.core.access.ItemsPage
import com.arshad.ayn.core.access.PlaylistItem
import com.arshad.ayn.core.access.VideoItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The channel page's tabs and each recorded tab page, through the seam (goldens read from the
 *  raw payloads into `RecordedCorpus.kt`). */
class NewPipeAccessChannelTabsFixtureTest {

    @Test
    fun theChannelPageListsItsTabsInPageOrder() {
        val access = NewPipeAccess(downloader = FixtureDownloader(CHANNEL_PAGE_FLOW))

        val channel = runBlocking { access.channel(RECORDED_CHANNEL_URL) }

        assertEquals(RecordedChannel.PAGE_TABS, channel.tabs)
    }

    @Test
    fun theChannelPageBannerIsTheUncroppedArt() {
        val access = NewPipeAccess(downloader = FixtureDownloader(CHANNEL_PAGE_FLOW))

        val channel = runBlocking { access.channel(RECORDED_CHANNEL_URL) }

        assertEquals(RecordedChannel.BANNER_URL, channel.bannerUrl)
    }

    @Test
    fun thePlaylistsTabMapsPlaylistsWithTheirCounts() {
        val page = tabPage(ChannelTab.PLAYLISTS)

        val playlists = page.items.filterIsInstance<PlaylistItem>()
        assertEquals("playlist items only", page.items.size, playlists.size)
        assertEquals(RecordedChannel.PLAYLISTS_TAB_COUNT, playlists.size)
        assertEquals(RecordedChannel.PLAYLISTS_TAB_FIRST, playlists.take(3).map { it.golden() })
        assertEquals(RecordedChannel.PLAYLISTS_TAB_FIRST_THUMBNAIL, playlists.first().thumbnailUrl)
        assertEquals("13 playlists fit one page", null, page.next)
    }

    @Test
    fun theReleasesTabMapsAlbumsAsPlaylists() {
        val page = tabPage(ChannelTab.RELEASES)

        val releases = page.items.filterIsInstance<PlaylistItem>()
        assertEquals("playlist items only", page.items.size, releases.size)
        assertEquals(RecordedChannel.RELEASES_TAB_COUNT, releases.size)
        assertEquals(RecordedChannel.RELEASES_TAB_FIRST, releases.first().golden())
    }

    @Test
    fun theLiveTabMapsPastStreamsAsVideos() {
        val videos = tabPage(ChannelTab.LIVE).items.filterIsInstance<VideoItem>()

        assertEquals(RecordedChannel.LIVE_TAB_IDS, videos.map { it.id.value })
        assertEquals(RecordedChannel.LIVE_TAB_FIRST_DURATION_SECONDS, videos.first().durationSeconds)
        assertTrue("a past stream plays as a VOD", videos.none { it.isLive })
    }

    private fun tabPage(tab: ChannelTab): ItemsPage {
        val access = NewPipeAccess(downloader = FixtureDownloader(CHANNEL_TAB_FLOWS.getValue(tab)))
        return runBlocking { access.channelTab(RECORDED_CHANNEL_URL, tab) }
    }

    private fun PlaylistItem.golden(): Triple<String, String, Long> =
        Triple(url.substringAfter("list="), title, videoCount)
}
