package com.arshad.ayn.ui.mobile.library

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.hasSetTextAction
import com.arshad.ayn.core.access.ChannelTab
import com.arshad.ayn.core.access.ChannelExtractor
import com.arshad.ayn.core.access.PlaylistItem
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.access.newpipe.ALBUM_PLAYLIST_URL
import com.arshad.ayn.core.access.newpipe.PAGED_PLAYLIST_URL
import com.arshad.ayn.core.access.newpipe.PLAYLIST_URL
import com.arshad.ayn.core.access.newpipe.RECORDED_CHANNEL_URL
import com.arshad.ayn.core.access.newpipe.RecordedAlbumPlaylist
import com.arshad.ayn.core.access.newpipe.RecordedPlaylist
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.ProfileTint
import com.arshad.ayn.feature.nav.ChannelRoute
import com.arshad.ayn.feature.nav.PlayerRoute
import com.arshad.ayn.feature.nav.PlaylistRoute
import com.arshad.ayn.ui.mobile.installOfflineImageLoader
import com.arshad.ayn.ui.mobile.shell.ChannelKey
import com.arshad.ayn.ui.mobile.shell.HistoryKey
import com.arshad.ayn.ui.mobile.shell.MobileShell
import com.arshad.ayn.ui.mobile.shell.PlaylistKey
import com.arshad.ayn.ui.mobile.shell.ShellNav
import com.arshad.ayn.ui.mobile.shell.ShellOpening
import com.arshad.ayn.ui.mobile.shell.Tab
import com.arshad.ayn.ui.mobile.shellPages
import com.arshad.ayn.ui.mobile.theme.AynTheme
import java.net.UnknownHostException
import com.arshad.ayn.core.access.AccessException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Library tab, All history, a saved playlist and a YouTube playlist on the phone
 * (`m6-library-and-playlist` 1–21, M6-D15), through the shell's real page wiring ([shellPages]) on
 * the stores' fakes and the recorded playlist payloads.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class LibraryPagesTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val stores = TestStores()
    private val played = mutableListOf<PlayerRoute>()
    private lateinit var nav: ShellNav

    @Before
    fun setUp() {
        installOfflineImageLoader(compose.activity)
    }

    private fun launch() {
        val models = stores.models()
        compose.setContent {
            AynTheme {
                MobileShell(ShellOpening(ARSHAD, onSwitchProfile = {}), onPlay = { played += it }) { chrome ->
                    nav = chrome.nav
                    shellPages(chrome, models)
                }
            }
        }
        compose.waitForIdle()
    }

    private fun openLibrary() {
        launch()
        compose.onNode(hasText("Library") and isSelectable()).performClick()
        compose.waitForIdle()
    }

    private fun click(label: String) {
        compose.onNode(hasText(label) and hasClickAction()).performClick()
        compose.waitForIdle()
    }

    private fun top(text: String, substring: Boolean = false): Float =
        compose.onNodeWithText(text, substring = substring).fetchSemanticsNode().boundsInRoot.top

    private fun left(text: String): Float = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.left

    private fun openPlaylist(card: PlaylistItem) {
        launch()
        compose.runOnUiThread { nav.open(PlaylistKey(PlaylistRoute(card))) }
        compose.waitForIdle()
    }

    @Test
    fun theHubShowsHistoryYourPlaylistsAndWatchLaterInTheirOrders() {
        stores.watch.entries.value = RecordedHistory
        stores.playlists.seed("Space and science", savedVideo("rocketsAAAA", "Rockets", "SpaceX", 600))
        stores.playlists.seed("Engineering deep dives", savedVideo("bridgesBBBB", "Bridges", "The B1M", 1200))
        runBlocking { stores.playlists.addToWatchLater(savedVideo("wildcatsCCC", "Wild cats", "BBC Earth", 3369)) }
        openLibrary()

        compose.onNodeWithText("Saved on this phone").assertExists()
        assertTrue(top("History  4 videos") < top("Your playlists", substring = true))
        assertTrue(top("Your playlists", substring = true) < top("Watch later", substring = true))
        compose.onNodeWithText("History  4 videos").assertExists()
        compose.onNodeWithText("Your playlists  2 playlists").assertExists()
        assertTrue("newest first", left(Enigma.title!!) < left(PoleLap.title!!))
        assertTrue("the most recently changed playlist first", left("Engineering deep dives") < left("Space and science"))
        compose.onNodeWithText("Veritasium · 12:51 left").assertExists()
        compose.onNodeWithText("Wild cats").assertExists()
    }

    @Test
    fun anEmptyRowGoesButYourPlaylistsStaysWithNoneYet() {
        runBlocking { stores.playlists.addToWatchLater(savedVideo("wildcatsCCC", "Wild cats", "BBC Earth", 3369)) }
        openLibrary()

        compose.onNodeWithText("History", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Your playlists  None yet").assertExists()
        compose.onNodeWithText("Watch later  1 video").assertExists()
        compose.onNodeWithText("New playlist").assertExists()
    }

    @Test
    fun nothingYetIsTheStatementAndGoToHomeSwitchesTab() {
        openLibrary()

        compose.onNodeWithText("Your library fills as you watch").assertExists()
        click("Go to Home")
        compose.onNode(hasText("Home") and isSelectable()).assertIsSelected()
    }

    @Test
    fun aFailedReadSaysSoAndTryAgainReadsAgain() {
        stores.playlists.failReads = true
        openLibrary()
        awaitText("Library couldn’t load")

        stores.playlists.failReads = false
        click("Try again")

        awaitText("Your library fills as you watch")
    }

    // The library's reads combine on Dispatchers.Default, which waitForIdle doesn't wait for.
    private fun awaitText(text: String) {
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun newPlaylistNamesOneThenOpensIt() {
        openLibrary()
        click("New playlist")

        compose.onNode(hasSetTextAction()).performTextReplacement("Poker and heists")
        click("Create playlist")

        compose.onNodeWithText("Poker and heists").assertExists()
        compose.onNodeWithText("Long-press any video to save it here").assertExists()
        compose.onNode(hasText("Play all") and hasClickAction()).assertDoesNotExist()
        compose.onNode(hasContentDescription("Shuffle")).assertDoesNotExist()
        compose.onNode(hasContentDescription("Playlist options")).assertExists()
    }

    @Test
    fun allHistoryOpensFromTheEndOfTheHistoryRow() {
        stores.watch.entries.value = listOf(Enigma)
        openLibrary()
        click("All history")

        compose.onNodeWithText("Veritasium\n34:51 of 47:42 · 8:12 PM").assertExists()
    }

    @Test
    fun allHistoryListsTheDaysNewestFirstWithWhereEachWasLeft() {
        stores.watch.entries.value = RecordedHistory
        openLibrary()
        compose.runOnUiThread { nav.open(HistoryKey) }
        compose.waitForIdle()

        val days = listOf("Today", "Yesterday", "Thursday").map { top(it, substring = true) }
        assertEquals(days.sorted(), days)
        assertTrue(top("Today", substring = true) < top(Enigma.title!!))
        assertTrue(top(Enigma.title!!) < top(PoleLap.title!!))
        compose.onNodeWithText("Veritasium\n34:51 of 47:42 · 8:12 PM").assertExists()
        compose.onNodeWithText("FORMULA 1\nWatched · 7:40 PM").assertExists()
    }

    @Test
    fun removingFromHistoryTakesTheRowOutWithItsPlace() {
        stores.watch.entries.value = RecordedHistory
        openLibrary()
        compose.runOnUiThread { nav.open(HistoryKey) }
        compose.waitForIdle()

        compose.onNode(hasText(Civilizations.title!!, substring = true) and hasClickAction()).performTouchInput { longClick() }
        compose.waitForIdle()
        click("Remove from history")

        compose.onNodeWithText(Civilizations.title!!).assertDoesNotExist()
        assertTrue(stores.watch.entries.value.none { it.videoId == Civilizations.videoId })
        compose.onNodeWithText(Enigma.title!!).assertExists()
    }

    @Test
    fun clearHistoryAsksFirstAndClearingEmptiesThePage() {
        stores.watch.entries.value = RecordedHistory
        openLibrary()
        compose.runOnUiThread { nav.open(HistoryKey) }
        compose.waitForIdle()

        click("Clear history")
        compose.onNodeWithText("Clear all watch history?").assertExists()
        click("Keep history")
        compose.onNodeWithText("Clear all watch history?").assertDoesNotExist()
        assertEquals(4, stores.watch.entries.value.size)

        click("Clear history")
        compose.onAllNodes(hasText("Clear history") and hasClickAction()).fetchSemanticsNodes().let { assertEquals(2, it.size) }
        compose.onAllNodes(hasText("Clear history") and hasClickAction())[1].performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Your history starts with the next video").assertExists()
        compose.onNode(hasText("Clear history") and hasClickAction()).assertDoesNotExist()
        assertTrue(stores.watch.entries.value.isEmpty())
    }

    @Test
    fun aSavedPlaylistListsItsVideosNumberedAndPlaysThroughIt() {
        val videos = listOf(savedVideo("rocketsAAAA", "Rockets", "SpaceX", 600), savedVideo("bridgesBBBB", "Bridges", "The B1M", 1200))
        val id = stores.playlists.seed("Engineering deep dives", *videos.toTypedArray())
        openLibrary()
        click("Engineering deep dives")

        compose.onNodeWithText("2 videos", substring = true).assertExists()
        assertTrue(top("Rockets") < top("Bridges"))
        compose.onNodeWithText("1", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("2", useUnmergedTree = true).assertExists()
        click("Bridges")
        click("Play all")

        assertEquals(listOf("bridgesBBBB", "rocketsAAAA"), played.map { it.videoId })
        assertTrue(played.all { it.savedPlaylistId == id })
        compose.onNode(hasContentDescription("Shuffle")).performClick()
        compose.waitForIdle()
        assertEquals(3, played.size)
    }

    @Test
    fun renamingInTheSheetSavesTheName() {
        stores.playlists.seed("Engineering deep dives", savedVideo("rocketsAAAA", "Rockets", "SpaceX", 600))
        openLibrary()
        click("Engineering deep dives")

        compose.onNode(hasContentDescription("Playlist options")).performClick()
        compose.waitForIdle()
        click("Name")
        compose.onNode(hasSetTextAction()).performTextReplacement("Code breakers")
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.waitForIdle()

        assertEquals("Code breakers", runBlocking { stores.playlists.playlists().first() }.single().name)
    }

    @Test
    fun deletingAsksFirstThenThePageLeavesForTheLibrary() {
        stores.playlists.seed("Engineering deep dives", savedVideo("rocketsAAAA", "Rockets", "SpaceX", 600))
        openLibrary()
        click("Engineering deep dives")

        compose.onNode(hasContentDescription("Playlist options")).performClick()
        compose.waitForIdle()
        click("Delete playlist")
        compose.onNodeWithText("Delete “Engineering deep dives”?").assertExists()
        compose.onAllNodes(hasText("Delete playlist") and hasClickAction()).fetchSemanticsNodes().size.let { assertEquals(1, it) }
        click("Delete playlist")

        assertEquals(1, nav.stacks.getValue(Tab.Library).size)
        compose.onNodeWithText("Your library fills as you watch").assertExists()
    }

    @Test
    fun aYouTubePlaylistShowsItsOwnerAndVideosInOrderAndPlaysTheList() {
        openPlaylist(playlistCard)

        compose.onNodeWithText(RecordedPlaylist.TITLE).assertExists()
        compose.onNodeWithText("${RecordedPlaylist.UPLOADER_NAME} · ${RecordedPlaylist.VIDEO_COUNT} videos").assertExists()
        val first = recordedVideos(PLAYLIST_URL).take(2)
        assertEquals(RecordedPlaylist.FIRST_IDS.take(2), first.map { it.id.value })
        assertTrue(top(first[0].title) < top(first[1].title))
        compose.onNodeWithText("Rick Astley\n133K views · ${RecordedPlaylist.FIRST_AGE}").assertExists()
        click("Play all")

        assertEquals(RecordedPlaylist.FIRST_IDS.first(), played.single().videoId)
        assertEquals(PLAYLIST_URL, played.single().playlistUrl)
    }

    @Test
    fun anAlbumIsSquareWithNoOwner() {
        openPlaylist(albumCard)

        compose.onNodeWithText("Album").assertExists()
        compose.onNodeWithText(RecordedAlbumPlaylist.TITLE).assertExists()
        compose.onNodeWithText("${RecordedAlbumPlaylist.VIDEO_COUNT} videos").assertExists()
    }

    @Test
    fun theListAsksForTheNextPageNearItsEnd() {
        val details = ChannelExtractor.RecordedPlaylists.getValue(PAGED_PLAYLIST_URL).details
        openPlaylist(PlaylistItem(details.title, PAGED_PLAYLIST_URL, details.thumbnailUrl, details.videoCount, details.uploaderName))
        assertEquals(0, stores.channels.playlistPageCalls)

        val listed = recordedVideos(PAGED_PLAYLIST_URL).size
        // Item 0 is the head, so row n is item n: the middle asks nothing, a row a few short of the end asks.
        compose.onNode(hasScrollAction()).performScrollToIndex(listed / 2)
        compose.waitForIdle()
        assertEquals(0, stores.channels.playlistPageCalls)
        compose.onNode(hasScrollAction()).performScrollToIndex(listed - 5)
        compose.waitForIdle()

        assertEquals(1, stores.channels.playlistPageCalls)
        compose.onNode(hasScrollAction()).performScrollToIndex(listed + 1)
        compose.waitForIdle()
        compose.onNodeWithText("${listed + 1}").assertExists()
    }

    @Test
    fun offlineSaysSoWithTryAgainAndUnavailableHasOnlyBack() {
        stores.channels.playlistFailure = AccessException("down", UnknownHostException("offline"))
        openPlaylist(playlistCard)
        compose.onNodeWithText("No connection to YouTube").assertExists()
        stores.channels.playlistFailure = null
        click("Try again")
        compose.onNodeWithText(RecordedPlaylist.TITLE).assertExists()

        stores.channels.playlistFailure = ChannelExtractor.RecordedUnavailable
        compose.runOnUiThread { nav.back() }
        compose.waitForIdle()
        compose.runOnUiThread { nav.open(PlaylistKey(PlaylistRoute(playlistCard))) }
        compose.waitForIdle()
        compose.onNodeWithText("This playlist isn’t available").assertExists()
        compose.onNode(hasText("Try again") and hasClickAction()).assertDoesNotExist()
    }

    @Test
    fun aChannelsPlaylistRowOpensThePlaylist() {
        launch()
        compose.runOnUiThread { nav.open(ChannelKey(ChannelRoute(RECORDED_CHANNEL_URL))) }
        compose.waitForIdle()
        click("Playlists")
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(playlistCard.title, substring = true))
        click(playlistCard.title)

        compose.onNodeWithText("${RecordedPlaylist.UPLOADER_NAME} · ${RecordedPlaylist.VIDEO_COUNT} videos").assertExists()
    }


    private companion object {
        val ARSHAD = Profile(1, "Arshad", ProfileTint.Amber, isOwner = true, lock = null)

        val playlistCard: PlaylistItem by lazy {
            ChannelExtractor.RecordedTabs.getValue(ChannelTab.PLAYLISTS).items.filterIsInstance<PlaylistItem>().single { it.url == PLAYLIST_URL }
        }
        val albumCard: PlaylistItem by lazy {
            ChannelExtractor.RecordedTabs.getValue(ChannelTab.RELEASES).items.filterIsInstance<PlaylistItem>().first { it.url == ALBUM_PLAYLIST_URL }
        }

        fun recordedVideos(url: String): List<VideoItem> =
            ChannelExtractor.RecordedPlaylists.getValue(url).videos.items.filterIsInstance<VideoItem>()
    }
}
