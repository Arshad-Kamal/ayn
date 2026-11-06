package com.arshad.ayn.ui.mobile.subscriptions

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.arshad.ayn.core.FixedClock
import com.arshad.ayn.core.access.SubscriptionsExtractor
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.access.newpipe.RECORDED_CHANNEL_URL
import com.arshad.ayn.core.access.newpipe.RecordedChannel
import com.arshad.ayn.core.access.recorded
import com.arshad.ayn.core.filter.ContentFilter
import com.arshad.ayn.core.filter.blockingVideos
import com.arshad.ayn.core.lock.FakeProfileLocks
import com.arshad.ayn.core.lock.LockKind
import com.arshad.ayn.core.store.FakeFilterStore
import com.arshad.ayn.core.store.FakePlaylistStore
import com.arshad.ayn.core.store.FakeProfileSettingsStore
import com.arshad.ayn.core.store.FakeProfileStore
import com.arshad.ayn.core.store.FakeSubscriptionStore
import com.arshad.ayn.core.store.FakeWatchStore
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.ProfileSettings
import com.arshad.ayn.core.store.ProfileTint
import com.arshad.ayn.feature.ShortsList
import com.arshad.ayn.feature.library.MenuBlocking
import com.arshad.ayn.feature.library.VideoMenuViewModel
import com.arshad.ayn.feature.library.marks
import com.arshad.ayn.feature.subscriptions.SubscriptionsFeed
import com.arshad.ayn.feature.subscriptions.SubscriptionsViewModel
import com.arshad.ayn.ui.mobile.common.LocalContentFilter
import com.arshad.ayn.ui.mobile.installOfflineImageLoader
import com.arshad.ayn.ui.mobile.shell.ChannelKey
import com.arshad.ayn.ui.mobile.shell.MobileShell
import com.arshad.ayn.ui.mobile.shell.SearchKey
import com.arshad.ayn.ui.mobile.shell.SettingsKey
import com.arshad.ayn.ui.mobile.shell.ShellOpening
import com.arshad.ayn.ui.mobile.shell.Tab
import com.arshad.ayn.ui.mobile.shell.TabKey
import com.arshad.ayn.ui.mobile.theme.AynTheme
import java.net.UnknownHostException
import java.util.TimeZone
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The phone's Subscriptions (`m6-subscriptions-and-channel` 1–10, M6-D14) through the shell and the
 * real [SubscriptionsViewModel] on the recorded Rick Astley feed (published 06-26, -24, -22, -19;
 * the clock at 2026-06-28 16:00 UTC, a Sunday; Robolectric's en-US weeks start on Sunday, so the
 * feed reads Last week (three) then June (one)) beside failing and empty channels.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class SubscriptionsPageTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val extractor = SubscriptionsExtractor()
    private val feedPage = recorded("channel-feed") { channelFeed(RECORDED_CHANNEL_URL) }
    private val settings = FakeProfileSettingsStore(selectedId = ARSHAD.id)
    private val filter = MutableStateFlow<ContentFilter?>(null)
    private val zone = TimeZone.getDefault()

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        installOfflineImageLoader(compose.activity)
        extractor.feeds[RECORDED_CHANNEL_URL] = feedPage
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(zone)
    }

    private val feedVideos: List<VideoItem> get() = feedPage.items.filterIsInstance<VideoItem>()

    /** Each Short the page played, with the run it handed over. */
    private val playedShorts = mutableListOf<Pair<VideoItem, ShortsList>>()

    /** Whether the shell's Shorts player shows. */
    private var shortOpen by mutableStateOf(false)

    private var pulls = 0

    /** Opens the Subscriptions tab subscribed to [channels] ("Channel 1", "Channel 2" …). */
    private fun open(vararg channels: String, showShorts: Boolean = false) {
        settings.byProfile.value = mapOf(ARSHAD.id to ProfileSettings(showShorts = showShorts))
        val store = FakeSubscriptionStore(*channels)
        val feed = SubscriptionsFeed(extractor, FixedClock(RecordedChannel.FEED_CLOCK))
        val watch = FakeWatchStore()
        compose.setContent {
            val shownFilter by filter.collectAsState()
            AynTheme {
                CompositionLocalProvider(LocalContentFilter provides shownFilter) {
                    MobileShell(ShellOpening(ARSHAD, onSwitchProfile = {}), shortOpen = { shortOpen }) { chrome ->
                        entry<TabKey> { key ->
                            if (key.tab == Tab.Subscriptions) {
                                SubscriptionsPage(
                                    viewModel = viewModel { SubscriptionsViewModel(feed, store, settings) },
                                    menu = viewModel {
                                        VideoMenuViewModel(watch, FakePlaylistStore(), settings, MenuBlocking(FakeFilterStore(), FakeProfileStore(), FakeProfileLocks(), TimeSource.Monotonic))
                                    },
                                    chrome = chrome,
                                    marks = remember { watch.history().map { it.marks() } },
                                    onPlay = { _, _ -> },
                                    onPlayShort = { short, run -> playedShorts += short to run },
                                    onOpenChannel = { chrome.nav.open(ChannelKey(com.arshad.ayn.feature.nav.ChannelRoute(it))) },
                                    consumeArrival = { chrome.nav.consumeArrival(key) },
                                    onPull = { pulls++ },
                                )
                            } else {
                                Text("${key.tab.label} page")
                            }
                        }
                        entry<SettingsKey> {}
                        entry<SearchKey> { Text("Search page") }
                        entry<ChannelKey> { key -> Column { Text("Channel page ${key.route.channelUrl}") } }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNode(hasText("Subscriptions") and hasClickAction()).performClick()
        compose.waitForIdle()
    }

    private fun click(label: String) {
        compose.onNode(hasText(label) and hasClickAction()).performClick()
        compose.waitForIdle()
    }

    private fun pick(name: String) {
        compose.onNode(hasContentDescription(name, substring = true) and hasClickAction()).performClick()
        compose.waitForIdle()
    }

    private fun top(text: String): Float = compose.onNodeWithText(text, substring = true).fetchSemanticsNode().boundsInRoot.top

    @Test
    fun theFeedGroupsByUploadDayInTheFeedsOrderUnderTheStripsLine() {
        open(RECORDED_CHANNEL_URL)

        compose.onNodeWithText("1 channel · updated", substring = true).assertExists()
        assertTrue("Last week heads the feed", top("Last week") < top(feedVideos[0].title))
        val tops = feedVideos.take(3).map { top(it.title) }
        assertEquals("the rows keep the feed's order", tops.sorted(), tops)
        compose.onNodeWithText("1 day ago", substring = true).assertExists() // 06-26 16:00:07, 7 s short of two days
        compose.onNodeWithText("From your channels").assertDoesNotExist() // Show Shorts is off
    }

    /** M8-D8: the pull that refreshes the feed also asks for a sync. */
    @Test
    fun pullingDownAlsoSyncs() {
        open(RECORDED_CHANNEL_URL)

        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).performTouchInput { swipeDown() }
        compose.waitForIdle()

        assertEquals(1, pulls)
    }

    @Test
    fun withShowShortsOnTheChannelsShortsFollowTheFirstDay() {
        open(RECORDED_CHANNEL_URL, showShorts = true)

        assertTrue(top("From your channels") > top(feedVideos[2].title))
    }

    @Test
    fun aShelfShortPlaysWithTheShelfAsItsRun() {
        open(RECORDED_CHANNEL_URL, showShorts = true)
        val shelf = feedPage.shorts

        compose.onNode(hasText(shelf[0].title) and hasClickAction()).performClick()

        val (short, run) = playedShorts.single()
        assertEquals(shelf[0], short)
        assertEquals(shelf.map { it.id }, run.shorts.value.map { it.id })
    }

    @Test
    fun aShortMenusPlayPlaysItAsAShortWithTheShelfsRun() {
        open(RECORDED_CHANNEL_URL, showShorts = true)
        val shelf = feedPage.shorts

        compose.onNode(hasText(shelf[0].title) and hasClickAction()).performTouchInput { longClick() }
        compose.waitForIdle()
        click("Play")

        val (short, run) = playedShorts.single()
        assertEquals(shelf[0], short)
        assertEquals(shelf.map { it.id }, run.shorts.value.map { it.id })
    }

    @Test
    fun comingBackFromTheShortsPlayerBringsTheLastPlayedShortIntoView() {
        open(RECORDED_CHANNEL_URL, showShorts = true)
        val shelf = feedPage.shorts
        val last = shelf.last()
        assertTrue("the shelf runs past the screen", compose.onAllNodesWithText(last.title).fetchSemanticsNodes().isEmpty())

        compose.onNode(hasText(shelf[0].title) and hasClickAction()).performClick()
        shortOpen = true
        compose.waitForIdle()
        playedShorts.single().second.played(last.id)
        shortOpen = false
        compose.waitForIdle()

        compose.onNodeWithText(last.title).assertIsDisplayed()
    }

    @Test
    fun pickingAChannelNarrowsToItsLatestAndOpenChannelOpensItsPage() {
        open(RECORDED_CHANNEL_URL, OTHER_URL)

        pick("Channel 1")

        compose.onNodeWithText("Latest from Channel 1").assertExists()
        compose.onNodeWithText("4 latest videos", substring = true).assertExists()
        click("Open channel")
        compose.onNodeWithText("Channel page $RECORDED_CHANNEL_URL").assertExists()
    }

    @Test
    fun aPicksShortsFollowItsFirstVideo() {
        open(RECORDED_CHANNEL_URL, OTHER_URL, showShorts = true)

        pick("Channel 1")

        assertTrue(top(feedVideos[0].title) < top("From Channel 1"))
        assertTrue(top("From Channel 1") < top(feedVideos[1].title))
    }

    @Test
    fun aPickThatDidntRefreshSaysSoAndTryAgainBringsItBack() {
        extractor.failures[OTHER_URL] = UnknownHostException("offline")
        open(RECORDED_CHANNEL_URL, OTHER_URL)
        pick("Channel 2")

        compose.onNodeWithText("Nothing to show from Channel 2").assertExists()
        extractor.failures.clear()
        click("Try again")

        compose.onNodeWithText("Nothing from Channel 2 yet").assertExists()
    }

    @Test
    fun pickingThePickAgainShowsEveryChannel() {
        open(RECORDED_CHANNEL_URL, OTHER_URL)
        pick("Channel 1")

        pick("Channel 1")

        compose.onNodeWithText("Latest from Channel 1").assertDoesNotExist()
        compose.onNodeWithText("2 channels · updated", substring = true).assertExists()
    }

    @Test
    fun thePartialNoteNamesTheChannelThatDidntAnswerAndTryAgainBringsItBack() {
        extractor.failures[OTHER_URL] = UnknownHostException("offline")
        open(RECORDED_CHANNEL_URL, OTHER_URL)

        compose.onNodeWithText("1 channel didn’t refresh: Channel 2.", substring = true).assertExists()
        compose.onNode(hasContentDescription("Channel 2, didn’t refresh")).assertExists()
        extractor.failures.clear()
        click("Try again")

        compose.onNodeWithText("didn’t refresh", substring = true).assertDoesNotExist()
    }

    @Test
    fun aPickedChannelWithNothingToListOpensItsPageFromTheStatement() {
        open(RECORDED_CHANNEL_URL, OTHER_URL)

        pick("Channel 2")

        compose.onNodeWithText("Nothing from Channel 2 yet").assertExists()
        assertEquals("the line has no Open channel of its own", 1, compose.onAllNodesWithText("Open channel").fetchSemanticsNodes().size)
        click("Open channel")
        compose.onNodeWithText("Channel page $OTHER_URL").assertExists()
    }

    @Test
    fun noChannelAnsweringOfflineKeepsTheStripAndSaysSo() {
        extractor.failures[RECORDED_CHANNEL_URL] = UnknownHostException("offline")
        open(RECORDED_CHANNEL_URL)

        compose.onNodeWithText("No connection to YouTube").assertExists()
        compose.onNodeWithText("Subscriptions couldn’t refresh").assertExists()
        compose.onNode(hasContentDescription("Channel 1")).assertExists()
    }

    @Test
    fun noSubscriptionsOffersFindAChannelWhichOpensSearch() {
        open()

        compose.onNodeWithText("Follow channels without an account").assertExists()
        click("Find a channel")
        compose.onNodeWithText("Search page").assertExists()
    }

    @Test
    fun aBlockFromAMenuTakesItsRowOutAtOnce() {
        open(RECORDED_CHANNEL_URL)

        filter.value = blockingVideos(feedVideos[0].id.value)
        compose.waitForIdle()

        compose.onNodeWithText(feedVideos[0].title).assertDoesNotExist()
        compose.onNodeWithText(feedVideos[1].title).assertExists()
    }

    private companion object {
        const val OTHER_URL = "https://www.youtube.com/channel/UCother0000000000000000"
        val ARSHAD = Profile(1, "Arshad", ProfileTint.Amber, isOwner = true, lock = LockKind.Sequence)
    }
}
