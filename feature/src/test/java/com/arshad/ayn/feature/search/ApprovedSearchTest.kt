package com.arshad.ayn.feature.search

import com.arshad.ayn.core.access.ItemsPage
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.filter.ContentFilter
import com.arshad.ayn.core.filter.packs.IslamicOnly
import com.arshad.ayn.core.store.ApprovedVideo
import com.arshad.ayn.core.store.BlockedVideo
import com.arshad.ayn.core.store.FakeProfileSettingsStore
import com.arshad.ayn.core.store.FilterPacks
import com.arshad.ayn.core.store.ProfileRestrictions
import com.arshad.ayn.core.store.ProfileRules
import com.arshad.ayn.core.store.ProfileSettings
import com.arshad.ayn.core.store.StoredRule
import com.arshad.ayn.feature.FakeFeedExtractor
import com.arshad.ayn.feature.FakeSubscriptionStore
import com.arshad.ayn.feature.UnsupportedExtractor
import com.arshad.ayn.feature.subscriptions.SubscriptionsFeed
import com.arshad.ayn.feature.testSubscription
import com.arshad.ayn.feature.testVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A kids profile's Search (KP-D10) over the real [SubscriptionsFeed] on a fake channel feed: a
 * local match over the approved channels' uploads and the approved videos, never YouTube's search.
 */
class ApprovedSearchTest {
    private val dispatcher = StandardTestDispatcher()
    private val feedSource = FakeFeedExtractor().apply {
        feeds[DINO_CHANNEL] = ItemsPage(items = listOf(DINO_SONG, TRUCK_SONG), next = null)
    }
    private val subscriptions = FakeSubscriptionStore().apply { rows.value = listOf(testSubscription(DINO_CHANNEL)) }
    private var filter: ContentFilter = kidsFilter()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun aKidsQueryMatchesTheApprovedVideosThenTheApprovedChannelsUploads() = runTest(dispatcher) {
        val found = search().search("dino")

        assertEquals(listOf(APPROVED.videoId, DINO_SONG.id.value), found?.map { it.id.value })
    }

    /** FPK-D13: the channels a kid's packs approve are searched with its approved ones. */
    @Test
    fun aKidsPackChannelsUploadsAreSearchedToo() = runTest(dispatcher) {
        val channel = IslamicOnly.channels.first { it.onForKids && it.onlyWhen == null }
        val nasheed = testVideo("nasheed0001", title = "Dino Nasheed").copy(uploaderUrl = channel.url)
        feedSource.feeds[channel.url] = ItemsPage(items = listOf(nasheed), next = null)
        filter = kidsFilter(packs = FilterPacks(islamicOnly = true))

        val found = search().search("dino")?.map { it.id.value }.orEmpty()

        assertTrue(found.toString(), nasheed.id.value in found && DINO_SONG.id.value in found)
    }

    @Test
    fun aProfileWithoutAnAllowListSearchesYouTubeInstead() = runTest(dispatcher) {
        filter = ContentFilter.of(ProfileSettings(), ProfileRules())

        assertNull(search().search("dino"))
        assertEquals(0, feedSource.calls)
    }

    /** A block still wins over an approval inside the local match. */
    @Test
    fun aVideoTheFilterHidesStaysOutOfTheMatch() = runTest(dispatcher) {
        filter = kidsFilter(blocked = DINO_SONG.id.value)

        assertEquals(listOf(APPROVED.videoId), search().search("dino")?.map { it.id.value })
    }

    @Test
    fun theSearchScreenShowsTheLocalMatchWithoutAskingYouTube() = runTest(dispatcher) {
        val viewModel = SearchViewModel(NoSearch(), FakeProfileSettingsStore(selectedId = 1L), search())

        viewModel.onQueryChange("dino")
        viewModel.submit()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.approvedOnly)
        assertEquals(listOf(APPROVED.videoId, DINO_SONG.id.value), (state.state as SearchState.Ready).items.map { (it as VideoItem).id.value })
    }

    private fun search(): ApprovedSearch =
        ApprovedSearch(SubscriptionsFeed(feedSource), subscriptions, { filter }) { listOf(APPROVED) }

    private fun kidsFilter(blocked: String? = null, packs: FilterPacks = FilterPacks()): ContentFilter = ContentFilter.of(
        ProfileSettings(),
        ProfileRules(
            restrictions = ProfileRestrictions(filterPacks = packs),
            rules = listOfNotNull(
                StoredRule(APPROVED, byOwner = true, addedAtMillis = 0),
                blocked?.let { StoredRule(BlockedVideo(it, null, null), byOwner = true, addedAtMillis = 0) },
            ),
            kids = true,
            approvedChannels = setOf(DINO_CHANNEL),
        ),
    )

    /** Suggestions answer none; a remote search would fail the test. */
    private class NoSearch : UnsupportedExtractor("no remote search") {
        override suspend fun suggestions(query: String): List<String> = emptyList()
    }
}

private val DINO_SONG = testVideo("dinosong001", title = "The Dinosaur Song")
private val TRUCK_SONG = testVideo("trucksong01", title = "Wheels on the Truck").copy(uploaderUrl = DINO_SONG.uploaderUrl)
private val DINO_CHANNEL: String = DINO_SONG.uploaderUrl!!

/** From a channel the kid hasn't got: approved on its own. */
private val APPROVED = ApprovedVideo(
    videoId = "approved001",
    title = "Dinosaurs Live at the Museum",
    channelName = "Museum",
    channelUrl = "https://www.youtube.com/channel/UCmuseummuseummuseummuse",
    thumbnailUrl = null,
    durationSeconds = 300,
)
