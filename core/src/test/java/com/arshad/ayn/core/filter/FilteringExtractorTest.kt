package com.arshad.ayn.core.filter

import com.arshad.ayn.core.access.BrowseKiosk
import com.arshad.ayn.core.access.ChannelItem
import com.arshad.ayn.core.access.ChannelTab
import com.arshad.ayn.core.access.CommentsPage
import com.arshad.ayn.core.access.Extractor
import com.arshad.ayn.core.access.ItemsPage
import com.arshad.ayn.core.access.ListingItem
import com.arshad.ayn.core.access.LiveChatSeed
import com.arshad.ayn.core.access.PageToken
import com.arshad.ayn.core.access.PlaylistDetails
import com.arshad.ayn.core.access.PlaylistItem
import com.arshad.ayn.core.access.PlaylistPage
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.access.VideoMetadata
import com.arshad.ayn.core.access.YouTubeRestrict
import com.arshad.ayn.core.access.newpipe.FIXED_SEARCH_QUERY
import com.arshad.ayn.core.access.newpipe.FixtureDownloader
import com.arshad.ayn.core.access.newpipe.NewPipeAccess
import com.arshad.ayn.core.store.BlockedChannel
import com.arshad.ayn.core.store.ContentRule
import com.arshad.ayn.core.store.FilterPacks
import com.arshad.ayn.core.store.Keyword
import com.arshad.ayn.core.store.ProfileRestrictions
import com.arshad.ayn.core.store.ProfileRules
import com.arshad.ayn.core.store.ProfileSettings
import com.arshad.ayn.core.store.RestrictedMode
import com.arshad.ayn.core.store.StoredRule
import com.arshad.ayn.core.store.canonicalChannelUrl
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FilteringExtractorTest {

    /** The recorded search page through the seam: the blocked channel's results go, the rest
     *  keep YouTube's order. */
    @Test
    fun aRecordedSearchPageLosesTheBlockedChannelsResultsInOrder() = runBlocking<Unit> {
        val raw = NewPipeAccess(downloader = FixtureDownloader("search")).search(FIXED_SEARCH_QUERY)
        val rawVideos = raw.items.filterIsInstance<VideoItem>()
        val blocked = rawVideos.first().uploaderUrl!!
        val filter = filterOf(BlockedChannel(blocked, null))

        val page = FilteringExtractor(NewPipeAccess(downloader = FixtureDownloader("search"))) { filter }.search(FIXED_SEARCH_QUERY)

        val expected = rawVideos.filter { canonicalChannelUrl(it.uploaderUrl.orEmpty()) != canonicalChannelUrl(blocked) }.map { it.id }
        assertEquals(expected, page.items.filterIsInstance<VideoItem>().map { it.id })
        assertTrue("the recorded page has other channels' results", expected.isNotEmpty())
    }

    @Test
    fun aPageFilteringEmptiesFetchesAtMostTwoMore() = runBlocking<Unit> {
        val pages = Pages(List(5) { listOf(video(id = "bad${it}bad1234".take(11), title = "prank $it")) })

        val page = FilteringExtractor(pages) { filterOf(Keyword("prank")) }.search("q")

        assertEquals("the first page and two more", 3, pages.fetched)
        assertTrue(page.items.isEmpty())
        assertEquals("the answer continues after the last page fetched", PageToken(id = "3"), page.next)
    }

    @Test
    fun theRefillStopsAtThePageThatKeepsSomething() = runBlocking<Unit> {
        val pages = Pages(listOf(listOf(video(id = "aaaaaaaaaa1", title = "prank")), listOf(video(id = "aaaaaaaaaa2", title = "calm"))))

        val page = FilteringExtractor(pages) { filterOf(Keyword("prank")) }.search("q")

        assertEquals(listOf("aaaaaaaaaa2"), page.items.map { (it as VideoItem).id.value })
        assertEquals(2, pages.fetched)
    }

    @Test
    fun aPageYouTubeSentEmptyIsNotRefilled() = runBlocking<Unit> {
        val pages = Pages(listOf(emptyList(), listOf(video())))

        FilteringExtractor(pages) { filterOf(Keyword("prank")) }.search("q")

        assertEquals(1, pages.fetched)
    }

    @Test
    fun theOwnersHideShortsEmptiesEveryPagesShorts() = runBlocking<Unit> {
        val pages = Pages(listOf(listOf(video())), shorts = listOf(video(id = "shortshort1")))
        val filter = ContentFilter.of(ProfileSettings(showShorts = true), ProfileRules(restrictions = ProfileRestrictions(hideShorts = true)))

        assertTrue(FilteringExtractor(pages) { filter }.search("q").shorts.isEmpty())
    }

    @Test
    fun aBlockedChannelsTabAnswersEmptyWithoutAFetch() = runBlocking<Unit> {
        val pages = Pages(listOf(listOf(video())))
        val url = "https://www.youtube.com/channel/$CHANNEL_ID"

        val page = FilteringExtractor(pages) { filterOf(BlockedChannel(url, null)) }.channelTab(url, ChannelTab.VIDEOS)

        assertTrue(page.items.isEmpty())
        assertEquals(0, pages.fetched)
    }

    @Test
    fun theResolveGateRefusesAFilteredVideoAndFiltersItsRelatedList() = runBlocking<Unit> {
        val pages = Pages(emptyList(), page = metadata(title = "calm", related = listOf(video(title = "prank"), video(id = "bbbbbbbbbbb", title = "calm"))))
        val seam = FilteringExtractor(pages) { filterOf(Keyword("prank")) }

        assertEquals(listOf("bbbbbbbbbbb"), seam.video(VideoId(VIDEO_ID)).relatedVideos.map { it.id.value })
        pages.page = metadata(title = "PRANK")
        assertThrows(VideoFiltered::class.java) { runBlocking { seam.video(VideoId(VIDEO_ID)) } }
    }

    /** FPK-D11: the resolve gate reads the page's channel name for the packs. */
    @Test
    fun theResolveGateRefusesAVideoAPacksChannelNameRuleHides() = runBlocking<Unit> {
        val pages = Pages(emptyList(), page = metadata(title = "calm").copy(uploaderName = "Like Nastya"))
        val filter = ContentFilter.of(ProfileSettings(filterPacks = FilterPacks(harmfulForKids = true)), ProfileRules())

        assertThrows(VideoFiltered::class.java) { runBlocking { FilteringExtractor(pages) { filter }.video(VideoId(VIDEO_ID)) } }
    }

    /** KP-D15: the level read when the call starts rides every request, refills included, even
     *  when the profile's filter changes during the call. */
    @Test
    fun everyRequestOfACallCarriesTheLevelReadAtItsStart() = runBlocking<Unit> {
        val pages = Pages(List(3) { listOf(video(id = "bad${it}bad1234".take(11), title = "prank $it")) })
        var filter = strict(Keyword("prank"))
        val seam = FilteringExtractor(pages) { filter.also { filter = filterOf(Keyword("prank")) } }

        seam.search("q")

        assertEquals(listOf("Strict", "Strict", "Strict"), pages.levels)
    }

    @Test
    fun anUnrestrictedProfilesRequestsCarryNoLevel() = runBlocking<Unit> {
        val pages = Pages(listOf(listOf(video())), page = metadata(title = "calm"))
        val seam = FilteringExtractor(pages) { filterOf() }

        seam.search("q")
        seam.video(VideoId(VIDEO_ID))

        assertEquals(listOf<String?>(null, null), pages.levels)
    }

    @Test
    fun restrictedModeTurnsRemoteSuggestionsOff() = runBlocking<Unit> {
        val pages = Pages(emptyList())

        assertEquals(emptyList<String>(), FilteringExtractor(pages) { strict() }.suggestions("ca"))
        assertEquals(listOf("cats"), FilteringExtractor(pages) { filterOf() }.suggestions("ca"))
        assertEquals(emptyList<String>(), FilteringExtractor(pages) { ContentFilter.REFUSE_ALL }.suggestions("ca"))
        assertEquals("only the unrestricted profile asked YouTube", 1, pages.suggestionCalls)
    }

    /** LIVE-D25: a page under Restricted Mode carries no live chat; one without keeps it. */
    @Test
    fun restrictedModeTakesThePagesLiveChat() = runBlocking<Unit> {
        val seed = LiveChatSeed("seed", replay = false)
        val pages = Pages(emptyList(), page = metadata(title = "calm").copy(liveChat = seed))

        assertEquals(null, FilteringExtractor(pages) { strict() }.video(VideoId(VIDEO_ID)).liveChat)
        assertEquals(seed, FilteringExtractor(pages) { filterOf() }.video(VideoId(VIDEO_ID)).liveChat)
    }

    @Test
    fun restrictedModeTurnsCommentsOffWithoutAsking() = runBlocking<Unit> {
        val pages = Pages(emptyList())

        val page = FilteringExtractor(pages) { strict() }.comments(VideoId(VIDEO_ID))

        assertTrue(page.commentsOff)
        assertTrue("marked as Ayn's, not YouTube's (PAF-D8)", page.offInRestrictedMode)
        assertTrue(page.comments.isEmpty())
        assertEquals(0, pages.commentCalls)
    }

    /** KP-D10: a kids profile never asks YouTube's search; its Search is local. */
    @Test
    fun aKidsProfilesSearchAnswersEmptyWithoutARequest() = runBlocking<Unit> {
        val pages = Pages(listOf(listOf(video())))

        val page = FilteringExtractor(pages) { kidsFilter(setOf("https://www.youtube.com/channel/UCbbbbbbbbbbbbbbbbbbbbbb")) }.search("q")

        assertTrue(page.items.isEmpty())
        assertEquals(0, pages.fetched)
    }

    @Test
    fun theResolveGateRefusesAVideoTheKidsProfileWasntApproved() = runBlocking<Unit> {
        val seam = FilteringExtractor(Pages(emptyList(), page = metadata(title = "calm"))) { kidsFilter(emptySet()) }

        assertThrows(VideoFiltered::class.java) { runBlocking { seam.video(VideoId(VIDEO_ID)) } }
    }

    /** Hide Shorts holds on a channel's Shorts tab too, whatever the profile's Show Shorts says. */
    @Test
    fun underHideShortsAChannelsShortsTabAnswersEmptyWithoutAFetch() = runBlocking<Unit> {
        val pages = Pages(listOf(listOf(video())))
        val filter = ContentFilter.of(ProfileSettings(showShorts = true), ProfileRules(restrictions = ProfileRestrictions(hideShorts = true)))
        val url = "https://www.youtube.com/channel/UCbbbbbbbbbbbbbbbbbbbbbb"

        assertTrue(FilteringExtractor(pages) { filter }.channelTab(url, ChannelTab.SHORTS).items.isEmpty())
        assertEquals(0, pages.fetched)
        assertEquals(1, FilteringExtractor(pages) { filter }.channelTab(url, ChannelTab.VIDEOS).items.size)
    }

    /** KP-D11: a kids profile plays on within the channel; an approved video from another
     *  approved channel leaves the Related row (and so Up next), in YouTube's order otherwise. */
    @Test
    fun aKidsProfilesRelatedVideosAreThePlayingVideosChannelsOnly() = runBlocking<Unit> {
        val own = "https://www.youtube.com/channel/UCbbbbbbbbbbbbbbbbbbbbbb"
        val other = "https://www.youtube.com/channel/UCcccccccccccccccccccccc"
        val related = listOf(
            video(id = "rrrrrrrrrr1", channelUrl = other),
            video(id = "rrrrrrrrrr2", channelUrl = "$own/videos"),
            video(id = "rrrrrrrrrr3", channelUrl = own),
        )
        val pages = Pages(emptyList(), page = metadata(title = "calm", related = related).copy(uploaderUrl = own))

        val answer = FilteringExtractor(pages) { kidsFilter(setOf(own, other)) }.video(VideoId(VIDEO_ID))

        assertEquals(listOf("rrrrrrrrrr2", "rrrrrrrrrr3"), answer.relatedVideos.map { it.id.value })
        assertEquals(3, FilteringExtractor(pages) { filterOf() }.video(VideoId(VIDEO_ID)).relatedVideos.size)
    }

    @Test
    fun aBlockedChannelsFeedAnswersEmptyWithoutAFetchAndAnotherLosesItsFilteredVideos() = runBlocking<Unit> {
        val pages = Pages(listOf(listOf(video(id = "aaaaaaaaaa1", title = "prank", channelUrl = OTHER_URL), video(id = "aaaaaaaaaa2", title = "calm", channelUrl = OTHER_URL))))
        val seam = FilteringExtractor(pages) { filterOf(BlockedChannel(BLOCKED_URL, null), Keyword("prank")) }

        assertTrue(seam.channelFeed(BLOCKED_URL).items.isEmpty())
        assertEquals(0, pages.fetched)
        assertEquals(listOf("aaaaaaaaaa2"), seam.channelFeed(OTHER_URL).items.map { (it as VideoItem).id.value })
    }

    @Test
    fun aPlaylistWhoseFirstPageFiltersEmptyRefillsFromItsNextPage() = runBlocking<Unit> {
        val pages = Pages(listOf(listOf(video(id = "aaaaaaaaaa1", title = "prank")), listOf(video(id = "aaaaaaaaaa2", title = "calm"))))

        val playlist = FilteringExtractor(pages) { filterOf(Keyword("prank")) }.playlist("https://www.youtube.com/playlist?list=PL1")

        assertEquals(listOf("aaaaaaaaaa2"), playlist.videos.items.map { (it as VideoItem).id.value })
        assertEquals(2, pages.fetched)
    }

    @Test
    fun aKidsProfileBrowsesNothingWithoutARequest() = runBlocking<Unit> {
        val pages = Pages(listOf(listOf(video())))

        val page = FilteringExtractor(pages) { kidsFilter(approvedChannels = setOf(OTHER_URL)) }.browse(BrowseKiosk.LIVE)

        assertTrue(page.items.isEmpty())
        assertEquals(0, pages.fetched)
    }

    @Test
    fun relatedListsLoseFilteredVideos() = runBlocking<Unit> {
        val pages = Pages(emptyList(), relatedItems = listOf(video(id = "aaaaaaaaaa1", title = "prank"), video(id = "aaaaaaaaaa2", title = "calm")))

        val related = FilteringExtractor(pages) { filterOf(Keyword("prank")) }.related(VideoId(VIDEO_ID))

        assertEquals(listOf("aaaaaaaaaa2"), related.map { it.id.value })
    }

    @Test
    fun aListingDropsABlockedChannelCardAndUnderAnAllowListPlaylistCards() = runBlocking<Unit> {
        val blockedCard = ChannelItem("Blocked", BLOCKED_URL, null, subscriberCount = 1, description = null, verified = false)
        val otherCard = ChannelItem("Other", OTHER_URL, null, subscriberCount = 1, description = null, verified = false)
        val playlistCard = PlaylistItem("A list", "https://www.youtube.com/playlist?list=PL1", null, videoCount = 3, uploaderName = null)
        val listing = listOf(listOf<ListingItem>(blockedCard, otherCard, playlistCard))

        val blockList = FilteringExtractor(Pages(listing)) { filterOf(BlockedChannel(BLOCKED_URL, null)) }.search("q").items
        val allowList = FilteringExtractor(Pages(listing)) { kidsFilter(approvedChannels = setOf(OTHER_URL)) }
            .channelTab(OTHER_URL, ChannelTab.VIDEOS).items

        assertEquals(listOf<ListingItem>(otherCard, playlistCard), blockList)
        assertEquals(listOf<ListingItem>(otherCard), allowList)
    }

    /** A pinned filter answers every call made under it without a read; outside it, each call reads. */
    @Test
    fun callsUnderAPinnedFilterUseItWithoutReadingTheProfilesFilter() = runBlocking<Unit> {
        val pages = Pages(listOf(listOf(video(id = "aaaaaaaaaa1", title = "prank", channelUrl = OTHER_URL), video(id = "aaaaaaaaaa2", title = "calm", channelUrl = OTHER_URL))))
        var reads = 0
        val seam = FilteringExtractor(pages) { reads++; filterOf() }

        val pinned = withContext(PinnedFilter(strict(Keyword("prank")))) {
            List(3) { seam.channelFeed(OTHER_URL) }
        }

        assertEquals(0, reads)
        pinned.forEach { assertEquals(listOf("aaaaaaaaaa2"), it.items.map { item -> (item as VideoItem).id.value }) }
        assertEquals(2, seam.channelFeed(OTHER_URL).items.size)
        assertEquals(1, reads)
        assertEquals("the pinned filter's Restricted Mode rides its calls", listOf("Strict", "Strict", "Strict", null), pages.levels)
    }

    @Test
    fun beforeAnyProfileOpensNothingPlays() = runBlocking<Unit> {
        val seam = FilteringExtractor(Pages(emptyList(), page = metadata(title = "calm"))) { ContentFilter.REFUSE_ALL }

        assertThrows(VideoFiltered::class.java) { runBlocking { seam.video(VideoId(VIDEO_ID)) } }
        assertFalse(ContentFilter.REFUSE_ALL.allows(video()))
    }
}

private const val BLOCKED_URL = "https://www.youtube.com/channel/UCbbbbbbbbbbbbbbbbbbbbbb"
private const val OTHER_URL = "https://www.youtube.com/channel/UCcccccccccccccccccccccc"

private fun strict(vararg rules: ContentRule): ContentFilter = ContentFilter.of(
    ProfileSettings(),
    ProfileRules(
        restrictions = ProfileRestrictions(restrictedMode = RestrictedMode.Strict),
        rules = rules.map { StoredRule(it, byOwner = true, addedAtMillis = 0) },
    ),
)

private fun metadata(title: String, related: List<VideoItem> = emptyList()): VideoMetadata = VideoMetadata(
    id = VideoId(VIDEO_ID),
    title = title,
    uploaderName = "Someone",
    durationSeconds = 600,
    relatedVideos = related,
    hlsUrl = null,
    dashUrl = null,
    streams = emptyList(),
)

/** Listing pages in order for every listing call (token = the next page's index); [page] is
 *  what [video] answers, [related] what [related] answers. */
private class Pages(
    private val items: List<List<ListingItem>>,
    private val shorts: List<VideoItem> = emptyList(),
    var page: VideoMetadata? = null,
    private val relatedItems: List<VideoItem> = emptyList(),
) : Extractor {
    var fetched = 0

    /** The `YouTube-Restrict` value each page or video request ran under. */
    val levels = mutableListOf<String?>()
    var suggestionCalls = 0
    var commentCalls = 0

    override suspend fun search(query: String, page: PageToken?): ItemsPage = pageAt(page?.id?.toInt() ?: 0)

    override suspend fun channelTab(channelUrl: String, tab: ChannelTab, page: PageToken?): ItemsPage = pageAt(page?.id?.toInt() ?: 0)

    override suspend fun video(id: VideoId): VideoMetadata {
        levels += YouTubeRestrict.current()
        return page!!
    }

    private fun pageAt(index: Int): ItemsPage {
        fetched++
        levels += YouTubeRestrict.current()
        val next = if (index + 1 < items.size) PageToken(id = "${index + 1}") else null
        return ItemsPage(items = items.getOrElse(index) { emptyList() }, next = next, shorts = shorts)
    }

    override suspend fun suggestions(query: String): List<String> {
        suggestionCalls++
        return listOf("cats")
    }
    override suspend fun browse(kiosk: BrowseKiosk, page: PageToken?): ItemsPage = pageAt(page?.id?.toInt() ?: 0)
    override suspend fun channel(channelUrl: String): ChannelItem = error("not used")
    override suspend fun channelFeed(channelUrl: String): ItemsPage = pageAt(0)
    override suspend fun playlist(playlistUrl: String): PlaylistPage =
        PlaylistPage(PlaylistDetails("A list", null, null, null, videoCount = -1, thumbnailUrl = null), pageAt(0))
    override suspend fun playlistVideos(playlistUrl: String, page: PageToken): ItemsPage = pageAt(page.id?.toInt() ?: 0)
    override suspend fun comments(videoId: VideoId, page: PageToken?): CommentsPage {
        commentCalls++
        return CommentsPage(comments = emptyList(), next = null)
    }
    override suspend fun related(videoId: VideoId): List<VideoItem> {
        fetched++
        return relatedItems
    }
}
