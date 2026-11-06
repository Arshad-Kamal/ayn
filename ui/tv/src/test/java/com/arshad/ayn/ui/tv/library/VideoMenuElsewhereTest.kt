package com.arshad.ayn.ui.tv.library

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.arshad.ayn.core.access.BrowseKiosk
import com.arshad.ayn.core.access.ChannelTab
import com.arshad.ayn.core.access.Extractor
import com.arshad.ayn.core.access.PlaylistItem
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.access.newpipe.FEED_NEWEST_VIDEO_FLOW
import com.arshad.ayn.core.access.newpipe.FIXED_SEARCH_QUERY
import com.arshad.ayn.core.access.newpipe.KIOSK_FLOWS
import com.arshad.ayn.core.access.newpipe.PLAYLIST_URL
import com.arshad.ayn.core.access.newpipe.RECORDED_CHANNEL_URL
import com.arshad.ayn.core.access.newpipe.RecordedChannel
import com.arshad.ayn.core.access.newpipe.RecordedPlaylist
import com.arshad.ayn.core.store.FakePlaylistStore
import com.arshad.ayn.core.store.FakeWatchStore
import com.arshad.ayn.feature.nav.ChannelRoute
import com.arshad.ayn.feature.nav.PlayerRoute
import com.arshad.ayn.feature.nav.PlaylistRoute
import com.arshad.ayn.feature.nav.SearchRoute
import com.arshad.ayn.feature.nav.SubscriptionsRoute
import com.arshad.ayn.feature.subscriptions.SubscriptionsFeed
import com.arshad.ayn.core.access.ChannelExtractor
import com.arshad.ayn.core.store.FakeSubscriptionStore
import com.arshad.ayn.core.FixedClock
import com.arshad.ayn.core.access.HomeExtractor
import com.arshad.ayn.core.access.SearchExtractor
import com.arshad.ayn.core.access.SubscriptionsExtractor
import com.arshad.ayn.ui.tv.TestNow
import com.arshad.ayn.ui.tv.TestShell
import com.arshad.ayn.feature.format.lengthText
import com.arshad.ayn.ui.tv.dpadLongPress
import com.arshad.ayn.ui.tv.dpadSelect
import com.arshad.ayn.ui.tv.installOfflineImageLoader
import com.arshad.ayn.ui.tv.RailEdgeDp
import com.arshad.ayn.ui.tv.playerRoute
import com.arshad.ayn.ui.tv.pressBack
import com.arshad.ayn.core.access.recorded
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The card menu outside Library (Q14), opened by holding OK through the real shell on recorded
 * payloads: Home's rows, Search's answer, the Subscriptions feed, a channel's tab and a YouTube
 * playlist's rows. None offers a remove row; each plays, saves and leaves as its host does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp")
class VideoMenuElsewhereTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val watch = FakeWatchStore()
    private val playlists = FakePlaylistStore()
    private lateinit var navController: NavHostController

    @Before
    fun setUp() {
        installOfflineImageLoader(compose.activity)
    }

    private fun button(label: String): SemanticsMatcher = hasText(label) and hasClickAction()

    private fun clickable(label: String): SemanticsNodeInteraction = compose.onNode(button(label))

    private fun node(found: SemanticsNode): SemanticsNodeInteraction = compose.onNode(SemanticsMatcher("node ${found.id}") { it.id == found.id })

    /** Clickable nodes right of the rail and below [top] (dp at mdpi), in reading order. */
    private fun cardsBelow(top: Float, minWidth: Float = 0f): List<SemanticsNode> =
        compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
            .filter { it.boundsInRoot.top > top && it.boundsInRoot.left > RailEdgeDp && it.boundsInRoot.width >= minWidth }
            .sortedWith(compareBy({ it.boundsInRoot.top }, { it.boundsInRoot.left }))

    private fun hold(card: SemanticsNodeInteraction) {
        card.dpadLongPress(compose::waitForIdle)
    }

    private fun show(extractor: Extractor, subscriptions: FakeSubscriptionStore = FakeSubscriptionStore(), routes: List<Any> = emptyList()) {
        compose.setContent {
            navController = rememberNavController()
            TestShell(
                extractor = extractor,
                subscriptionsFeed = SubscriptionsFeed(extractor, FixedClock(RecordedChannel.FEED_CLOCK)),
                subscriptionStore = subscriptions,
                watchStore = watch,
                playlistStore = playlists,
                navController = navController,
            )
        }
        compose.waitForIdle()
        compose.runOnUiThread { routes.forEach { navController.navigate(it) } }
        compose.waitForIdle()
    }

    private fun playerRoute(): PlayerRoute? = navController.playerRoute()

    private fun assertNoRemoveRow() {
        compose.onNode(hasText("Remove", substring = true)).assertDoesNotExist()
    }

    private fun recordedHome(): HomeExtractor = HomeExtractor().apply {
        feed = recorded("channel-feed") { channelFeed(RECORDED_CHANNEL_URL) }
        heroPage = recorded(FEED_NEWEST_VIDEO_FLOW) { video(VideoId(RecordedChannel.FEED_ENTRIES.first().first)) }
        live = recorded(KIOSK_FLOWS.getValue(BrowseKiosk.LIVE)) { browse(BrowseKiosk.LIVE) }
    }

    /** A Live now card that isn't the row's first. */
    private fun liveCard(): SemanticsNode = compose.onAllNodes(hasClickAction() and hasText("LIVE")).fetchSemanticsNodes()[1]

    // The current destination's item: the closed rail lets only it take focus.
    private fun railItem(): SemanticsNodeInteraction = compose.onAllNodes(button("Home")).onLast()

    @Test
    fun aHomeRowsCardOpensItsMenuWithoutARemoveRowAndTheReleaseClicksNothing() {
        show(recordedHome(), FakeSubscriptionStore(RECORDED_CHANNEL_URL))

        hold(node(liveCard()))

        // The hero's Play matches too; the sheet composes after the page.
        compose.onAllNodes(button("Play")).onLast().assertIsFocused()
        clickable("Save to playlist").assertExists()
        clickable("Go to channel").assertExists()
        assertNoRemoveRow()
        assertNull(playerRoute())
    }

    @Test
    fun theMenuOnHomeKeepsTheRailFromFocusAndBackReturnsToTheCardWithTheRailLive() {
        show(recordedHome(), FakeSubscriptionStore(RECORDED_CHANNEL_URL))
        val card = liveCard()
        hold(node(card))
        railItem().performSemanticsAction(SemanticsActions.RequestFocus)
        railItem().assertIsNotFocused()

        compose.pressBack()

        clickable("Save to playlist").assertDoesNotExist()
        node(card).assertIsFocused()
        railItem().performSemanticsAction(SemanticsActions.RequestFocus)
        railItem().assertIsFocused()
    }

    /** A long result with a resume point a minute in; its length badge, shared by no other result, finds its card. */
    private fun searchWithAResumePoint(): VideoItem {
        val answer = recorded("search") { search(FIXED_SEARCH_QUERY) }
        val videos = answer.items.filterIsInstance<VideoItem>()
        val video = videos.first { v -> !v.isLive && v.durationSeconds >= 600 && videos.count { it.durationSeconds == v.durationSeconds } == 1 }
        watch.entries.value = listOf(watched(video.id.value, video.title, "Lofi", TestNow, 60_000, video.durationSeconds * 1000))
        show(SearchExtractor().apply { answers[FIXED_SEARCH_QUERY] = answer }, routes = listOf(SearchRoute))
        compose.onNode(hasSetTextAction()).performTextInput(FIXED_SEARCH_QUERY)
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.waitForIdle()
        return video
    }

    private fun searchCard(video: VideoItem): SemanticsNodeInteraction = compose.onNode(button(lengthText(video.durationSeconds)!!))

    @Test
    fun backOnASearchResultsMenuClosesTheMenuNotTheAnswerAndFocusReturnsToTheCard() {
        val video = searchWithAResumePoint()
        hold(searchCard(video))
        clickable("Resume from 1:00").assertIsFocused()
        clickable("Go to channel").assertExists()
        assertNoRemoveRow()

        compose.pressBack()

        clickable("Resume from 1:00").assertDoesNotExist()
        clickable("Edit search").assertExists()
        searchCard(video).assertIsFocused()
    }

    @Test
    fun resumeOnASearchResultPlaysItFromItsPlace() {
        val video = searchWithAResumePoint()
        hold(searchCard(video))

        clickable("Resume from 1:00").dpadSelect()
        compose.waitForIdle()

        val played = playerRoute()!!
        assertEquals(video.id.value, played.videoId)
        assertFalse(played.startOver)
    }

    @Test
    fun playFromTheStartOnASearchResultStartsItOver() {
        val video = searchWithAResumePoint()
        hold(searchCard(video))

        clickable("Play from the start").dpadSelect()
        compose.waitForIdle()

        val played = playerRoute()!!
        assertEquals(video.id.value, played.videoId)
        assertTrue(played.startOver)
    }

    private fun showSubscriptions() {
        val feed = SubscriptionsExtractor().apply { feeds[RECORDED_CHANNEL_URL] = recorded("channel-feed") { channelFeed(RECORDED_CHANNEL_URL) } }
        show(feed, FakeSubscriptionStore(RECORDED_CHANNEL_URL), listOf(SubscriptionsRoute))
    }

    @Test
    fun aSubscriptionsFeedCardSavesToWatchLater() {
        showSubscriptions()
        // The feed's first card is its newest upload.
        val newest = cardsBelow(FeedTopDp).first()

        hold(node(newest))
        assertNoRemoveRow()
        clickable("Save to playlist").dpadSelect()
        compose.waitForIdle()
        clickable("Watch later").dpadSelect()
        compose.waitForIdle()

        val later = runBlocking { playlists.watchLater().first() }.map { it.video.id.value }
        assertEquals(listOf(RecordedChannel.FEED_ENTRIES.first().first), later)
    }

    @Test
    fun backFromASubscriptionsCardsMenuReturnsToTheCardNotTheChannelStrip() {
        showSubscriptions()
        // Not the feed's first card, which focus would land on anyway.
        val card = cardsBelow(FeedTopDp)[1]
        hold(node(card))

        compose.pressBack()

        clickable("Save to playlist").assertDoesNotExist()
        node(card).assertIsFocused()
    }

    private fun showChannel(): VideoItem {
        val first = ChannelExtractor.RecordedTabs.getValue(ChannelTab.VIDEOS).items.filterIsInstance<VideoItem>().first()
        // The channel row would show for this video anywhere else.
        assertNotNull(first.uploaderUrl)
        show(ChannelExtractor(), routes = listOf(ChannelRoute(RECORDED_CHANNEL_URL)))
        return first
    }

    private fun channelCard(index: Int): SemanticsNode = cardsBelow(top = 0f, minWidth = CardWidthDp)[index]

    @Test
    fun aChannelsCardMenuHasNoGoToChannelAndPlaysTheVideo() {
        val first = showChannel()
        hold(node(channelCard(0)))
        clickable("Play").assertIsFocused()
        clickable("Save to playlist").assertExists()
        clickable("Go to channel").assertDoesNotExist()
        assertNoRemoveRow()

        clickable("Play").dpadSelect()
        compose.waitForIdle()

        val played = playerRoute()!!
        assertEquals(first.id.value, played.videoId)
        assertNull(played.playlistUrl)
        assertFalse(played.startOver)
    }

    @Test
    fun backFromAChannelCardsMenuReturnsToTheCardWithThePageStillFolded() {
        showChannel()
        // Not the grid's first card, which a page unfolding back to its start would focus.
        val card = channelCard(1)
        node(card).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        hold(node(card))

        compose.pressBack()

        clickable("Save to playlist").assertDoesNotExist()
        node(card).assertIsFocused()
    }

    @Test
    fun aYouTubePlaylistRowsMenuPlaysOnThroughThePlaylistAndBackFromThePlayerReturnsToTheRow() {
        show(ChannelExtractor(), routes = listOf(PlaylistRoute(playlistCard)))

        hold(row(2))
        assertNoRemoveRow()
        clickable("Play").dpadSelect()
        compose.waitForIdle()

        val played = playerRoute()!!
        assertEquals(RecordedPlaylist.FIRST_IDS[1], played.videoId)
        assertEquals(PLAYLIST_URL, played.playlistUrl)
        assertFalse(played.startOver)

        compose.pressBack()
        row(2).assertIsFocused()
    }

    @Test
    fun aYouTubePlaylistRowWithAResumePointPlaysFromTheStartThroughThePlaylist() {
        val second = playlistVideos()[1]
        watch.entries.value = listOf(watched(second.id.value, second.title, "Rick Astley", TestNow, 60_000, second.durationSeconds * 1000))
        show(ChannelExtractor(), routes = listOf(PlaylistRoute(playlistCard)))
        hold(row(2))

        clickable("Play from the start").dpadSelect()
        compose.waitForIdle()

        val played = playerRoute()!!
        assertEquals(second.id.value, played.videoId)
        assertEquals(PLAYLIST_URL, played.playlistUrl)
        assertTrue(played.startOver)
    }

    @Test
    fun goToChannelFromAYouTubePlaylistRowOpensTheVideosChannel() {
        val channelUrl = checkNotNull(playlistVideos()[1].uploaderUrl) { "the row's video carries its channel" }
        show(ChannelExtractor(), routes = listOf(PlaylistRoute(playlistCard)))
        hold(row(2))

        clickable("Go to channel").dpadSelect()
        compose.waitForIdle()

        assertEquals(channelUrl, navController.currentBackStackEntry!!.toRoute<ChannelRoute>().channelUrl)
    }

    /** The playlist row numbered [number]: a row-wide clickable whose first text is its number. */
    private fun row(number: Int): SemanticsNodeInteraction = compose.onNode(
        SemanticsMatcher("row $number") { node ->
            node.boundsInRoot.width >= RowWidthDp &&
                node.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text == "$number"
        },
    )

    private fun playlistVideos(): List<VideoItem> =
        ChannelExtractor.RecordedPlaylists.getValue(PLAYLIST_URL).videos.items.filterIsInstance<VideoItem>()

    private companion object {
        val playlistCard: PlaylistItem by lazy {
            ChannelExtractor.RecordedTabs.getValue(ChannelTab.PLAYLISTS).items.filterIsInstance<PlaylistItem>().single { it.url == PLAYLIST_URL }
        }

        // mdpi (1 dp = 1 px): the feed's top, a grid card's and a playlist row's width.
        const val FeedTopDp = 145f
        const val CardWidthDp = 180f - 0.5f
        const val RowWidthDp = 434f - 0.5f
    }
}
