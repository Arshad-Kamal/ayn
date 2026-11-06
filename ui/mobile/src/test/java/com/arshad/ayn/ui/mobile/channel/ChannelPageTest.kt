package com.arshad.ayn.ui.mobile.channel

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import com.arshad.ayn.core.access.ChannelExtractor
import com.arshad.ayn.core.access.ChannelTab
import com.arshad.ayn.core.access.ItemsPage
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.access.newpipe.RECORDED_CHANNEL_URL
import com.arshad.ayn.core.access.newpipe.RecordedChannel
import com.arshad.ayn.core.access.newpipe.SHORTS_CHANNEL_URL
import com.arshad.ayn.core.access.recorded
import com.arshad.ayn.core.filter.ContentFilter
import com.arshad.ayn.core.filter.blockingVideos
import com.arshad.ayn.core.lock.FakeProfileLocks
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
import com.arshad.ayn.feature.channel.ChannelViewModel
import com.arshad.ayn.feature.library.MenuBlocking
import com.arshad.ayn.feature.library.VideoMenuViewModel
import com.arshad.ayn.feature.library.marks
import com.arshad.ayn.feature.nav.ChannelRoute
import com.arshad.ayn.ui.mobile.common.LocalContentFilter
import com.arshad.ayn.ui.mobile.installOfflineImageLoader
import com.arshad.ayn.ui.mobile.shell.ChannelKey
import com.arshad.ayn.ui.mobile.shell.MobileShell
import com.arshad.ayn.ui.mobile.shell.SearchKey
import com.arshad.ayn.ui.mobile.shell.SettingsKey
import com.arshad.ayn.ui.mobile.shell.ShellOpening
import com.arshad.ayn.ui.mobile.shell.TabKey
import com.arshad.ayn.ui.mobile.theme.AynTheme
import java.net.UnknownHostException
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A channel's page on the phone (`m6-subscriptions-and-channel` 11–21, M6-D14) through the shell and
 * the real [ChannelViewModel] on the recorded Rick Astley channel: opened over Home, its header and
 * Videos rows in the tab's order, Subscribe and the Unsubscribed snackbar's Undo, tabs, the empty
 * tab, a Shorts-only channel, a block, the bar holding the name once scrolled, and failure with Back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class ChannelPageTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val extractor = ChannelExtractor()
    private val store = FakeSubscriptionStore()
    private val settings = FakeProfileSettingsStore(selectedId = ARSHAD.id)
    private val filter = MutableStateFlow<ContentFilter?>(null)
    private var opened = false
    private val name: String get() = ChannelExtractor.RecordedHeader.name
    private val videos: List<VideoItem> get() = ChannelExtractor.RecordedTabs.getValue(ChannelTab.VIDEOS).items.filterIsInstance<VideoItem>()

    @Before
    fun setUp() {
        installOfflineImageLoader(compose.activity)
    }

    /** Each Short the page played, with the run it handed over; whether the Shorts player shows. */
    private val playedShorts = mutableListOf<Pair<VideoItem, ShortsList>>()
    private var shortOpen by mutableStateOf(false)

    private fun open(profile: Profile = ARSHAD, showShorts: Boolean = false) {
        settings.byProfile.value = mapOf(profile.id to ProfileSettings(showShorts = showShorts))
        val watch = FakeWatchStore()
        compose.setContent {
            val shownFilter by filter.collectAsState()
            AynTheme {
                CompositionLocalProvider(LocalContentFilter provides shownFilter) {
                    MobileShell(ShellOpening(profile, onSwitchProfile = {}), shortOpen = { shortOpen }) { chrome ->
                        entry<TabKey> { key ->
                            Text("${key.tab.label} page")
                            LaunchedEffect(Unit) {
                                if (!opened) chrome.nav.open(ChannelKey(ChannelRoute(RECORDED_CHANNEL_URL)))
                                opened = true
                            }
                        }
                        entry<SettingsKey> {}
                        entry<SearchKey> {}
                        entry<ChannelKey> { key ->
                            ChannelPage(
                                viewModel = viewModel { ChannelViewModel(key.route.channelUrl, extractor, store, settings) },
                                menu = viewModel {
                                    VideoMenuViewModel(watch, FakePlaylistStore(), settings, MenuBlocking(FakeFilterStore(), FakeProfileStore(), FakeProfileLocks(), TimeSource.Monotonic))
                                },
                                chrome = chrome,
                                marks = remember { watch.history().map { it.marks() } },
                                onPlay = { _, _ -> },
                                onPlayShort = { short, run -> playedShorts += short to run },
                                onOpenPlaylist = {},
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun click(label: String) {
        compose.onNode(hasText(label) and hasClickAction()).performClick()
        compose.waitForIdle()
    }

    private fun top(text: String): Float = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top

    @Test
    fun theHeaderThenTheVideosTabsRowsInItsOrder() {
        open()

        compose.onNodeWithText(name).assertExists()
        compose.onNode(hasText("Subscribe") and hasClickAction()).assertExists()
        assertTrue(top(videos[0].title) < top(videos[1].title))
    }

    @Test
    fun unsubscribingShowsTheSnackbarWhoseUndoSubscribesAgain() {
        open()
        click("Subscribe")
        assertTrue(store.isSubscribed(RECORDED_CHANNEL_URL))

        click("Subscribed")
        compose.onNodeWithText("Unsubscribed from $name", substring = true).assertExists()
        click("Undo")

        assertTrue(store.isSubscribed(RECORDED_CHANNEL_URL))
        compose.onNodeWithText("Unsubscribed from $name", substring = true).assertDoesNotExist()
    }

    @Test
    fun aKidsProfilesPageHasNoSubscribe() {
        open(ZAINAB)

        compose.onNodeWithText(name).assertExists()
        compose.onNode(hasText("Subscribe") and hasClickAction()).assertDoesNotExist()
    }

    @Test
    fun thePlaylistsTabListsEachPlaylistWithItsCount() {
        open()

        click("Playlists")

        val (_, title, count) = RecordedChannel.PLAYLISTS_TAB_FIRST.first()
        compose.onNodeWithText(title).assertExists()
        compose.onNodeWithText("Playlist · $count videos").assertExists()
    }

    @Test
    fun anEmptyTabSaysSo() {
        extractor.tabs[ChannelTab.LIVE] = ItemsPage(items = emptyList(), next = null)
        open()

        click("Live")

        compose.onNodeWithText("Nothing in Live yet").assertExists()
    }

    @Test
    fun comingBackFromTheShortsPlayerScrollsTheTabToTheLastPlayedShort() {
        val tab = recorded("channel-shorts") { channelTab(SHORTS_CHANNEL_URL, ChannelTab.SHORTS) }
        extractor.header = ChannelExtractor.RecordedHeader.copy(tabs = listOf(ChannelTab.SHORTS))
        extractor.tabs[ChannelTab.SHORTS] = tab
        open(showShorts = true)
        val shorts = tab.shorts
        val far = shorts[30]
        assertTrue("far down the tab", compose.onAllNodesWithText(far.title).fetchSemanticsNodes().isEmpty())

        compose.onNode(hasText(shorts[0].title) and hasClickAction()).performClick()
        shortOpen = true
        compose.waitForIdle()
        playedShorts.single().second.played(far.id)
        shortOpen = false
        compose.waitForIdle()

        compose.onNodeWithText(far.title).assertIsDisplayed()
    }

    @Test
    fun aShortsOnlyChannelOffersTurnOnShortsWhichShowsTheShorts() {
        extractor.header = ChannelExtractor.RecordedHeader.copy(tabs = listOf(ChannelTab.SHORTS))
        open()

        compose.onNodeWithText("This channel only posts Shorts").assertExists()
        click("Turn on Shorts")

        assertEquals(true, settings.byProfile.value.getValue(ARSHAD.id).showShorts)
        compose.onNodeWithText("This channel only posts Shorts").assertDoesNotExist()
    }

    @Test
    fun aBlockFromAMenuTakesItsRowOutAtOnce() {
        open()

        filter.value = blockingVideos(videos[0].id.value)
        compose.waitForIdle()

        compose.onNodeWithText(videos[0].title).assertDoesNotExist()
        compose.onNodeWithText(videos[1].title).assertExists()
    }

    @Test
    fun scrolledPastTheHeaderTheBarHoldsTheNameAndTheTabs() {
        open()

        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).performScrollToIndex(8)
        compose.waitForIdle()

        compose.onNodeWithText(name).assertExists() // the header has left: this is the bar's
        compose.onNode(hasText("Playlists") and hasClickAction()).assertExists()
    }

    @Test
    fun aPageThatCouldntLoadOfflineSaysSoAndBackLeaves() {
        extractor.header = null
        extractor.failure = UnknownHostException("offline")
        open()

        compose.onNodeWithText("No connection to YouTube").assertExists()
        compose.onNodeWithText("This channel couldn’t load").assertExists()
        click("Back")
        compose.onNodeWithText("Home page").assertExists()
    }

    private companion object {
        val ARSHAD = Profile(1, "Arshad", ProfileTint.Amber, isOwner = true, lock = null)
        val ZAINAB = Profile(4, "Zainab", ProfileTint.Violet, isOwner = false, lock = null, isKids = true)
    }
}
