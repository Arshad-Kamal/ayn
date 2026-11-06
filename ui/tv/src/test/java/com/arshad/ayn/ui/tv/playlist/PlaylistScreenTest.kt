package com.arshad.ayn.ui.tv.playlist

import com.arshad.ayn.ui.tv.TestShell
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.arshad.ayn.core.access.AccessException
import com.arshad.ayn.core.access.ChannelTab
import com.arshad.ayn.core.access.ItemsPage
import com.arshad.ayn.core.access.PlaylistItem
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.access.dearrow.RecordedBranding
import com.arshad.ayn.core.access.newpipe.PAGED_PLAYLIST_URL
import com.arshad.ayn.core.access.newpipe.PLAYLIST_URL
import com.arshad.ayn.core.access.newpipe.RecordedAlbumPlaylist
import com.arshad.ayn.core.access.newpipe.RecordedPlaylist
import com.arshad.ayn.feature.CardBranding
import com.arshad.ayn.feature.nav.HomeRoute
import com.arshad.ayn.feature.nav.PlayerRoute
import com.arshad.ayn.feature.nav.PlaylistRoute
import com.arshad.ayn.core.access.ChannelExtractor
import com.arshad.ayn.ui.tv.dpadSelect
import com.arshad.ayn.ui.tv.installOfflineImageLoader
import com.arshad.ayn.ui.tv.pressBack
import com.arshad.ayn.ui.tv.testBranding
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A playlist page through the real shell and [com.arshad.ayn.feature.playlist.PlaylistViewModel],
 * opened from the recorded channel's cards: "Rick Astley: Complete" (132 videos in one page, one
 * of them twice), its Releases album "Raindrops" and its uploads (438 videos, 100 per page). Each
 * `b8p-playlist` state's content and where focus lands, the list's scroll and paging, and BACK.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp")
class PlaylistScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val extractor = ChannelExtractor()
    private lateinit var navController: NavHostController
    private lateinit var focusManager: FocusManager
    private val brandingScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var branding: CardBranding? = null
    private val pictures: MutableList<String> = CopyOnWriteArrayList()

    @Before
    fun setUp() {
        installOfflineImageLoader(compose.activity, pictures)
    }

    @After
    fun tearDown() {
        brandingScope.cancel()
    }

    /** Launches the shell and opens [card]'s page, as a channel's card does. */
    private fun open(card: PlaylistItem = playlistCard) {
        compose.setContent {
            navController = rememberNavController()
            focusManager = LocalFocusManager.current
            TestShell(extractor, branding = branding, navController = navController)
        }
        compose.waitForIdle()
        compose.runOnUiThread { navController.navigate(PlaylistRoute(card)) }
        compose.waitForIdle()
    }

    private fun button(label: String): SemanticsMatcher = hasText(label) and hasClickAction()

    /** The listed rows in screen order: the clickable nodes a row wide (buttons are narrower). */
    private fun rows(): List<SemanticsNode> = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        .filter { it.boundsInRoot.width >= RowWidthDp }
        .sortedBy { it.boundsInRoot.top }

    /** A row's texts: its number, the length on its thumbnail, the title, the line. */
    private fun texts(node: SemanticsNode): List<String> =
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

    private fun titleOf(node: SemanticsNode): String = texts(node)[2]

    /** The row numbered [number] among those composed. */
    private fun row(number: Int): SemanticsMatcher =
        SemanticsMatcher("row $number") { it.boundsInRoot.width >= RowWidthDp && texts(it).firstOrNull() == "$number" }

    private fun focusRow(number: Int) {
        compose.onNode(row(number)).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
    }

    private fun focused(): SemanticsNode = compose.onNode(isFocused()).fetchSemanticsNode()

    private fun move(direction: FocusDirection) {
        compose.runOnUiThread { focusManager.moveFocus(direction) }
        compose.waitForIdle()
    }

    private fun openedVideoId(): String? = navController.currentBackStackEntry?.toRoute<PlayerRoute>()?.videoId

    private fun openedPlaylistUrl(): String? = navController.currentBackStackEntry?.toRoute<PlayerRoute>()?.playlistUrl

    private fun recordedVideos(url: String): List<VideoItem> =
        ChannelExtractor.RecordedPlaylists.getValue(url).videos.items.filterIsInstance<VideoItem>()

    @Test
    fun thePageShowsTheHeaderAndTheVideosInOrderWithPlayAllFocused() {
        open()

        compose.onNodeWithText(RecordedPlaylist.TITLE).assertExists()
        compose.onNodeWithText("${RecordedPlaylist.UPLOADER_NAME} · ${RecordedPlaylist.VIDEO_COUNT} videos").assertExists()
        val first = recordedVideos(PLAYLIST_URL).take(3)
        assertEquals(RecordedPlaylist.FIRST_IDS, first.map { it.id.value })
        val listed = rows().take(3).map(::texts)
        assertEquals(listOf("1", "2", "3"), listed.map { it[0] })
        assertEquals("3:36", listed[0][1])
        assertEquals(first.map { it.title }, listed.map { it[2] })
        // The uploader, views abbreviated without rounding up, YouTube's age.
        assertEquals("Rick Astley · 133K views · ${RecordedPlaylist.FIRST_AGE}", listed[0][3])
        compose.onNode(button("Play all")).assertIsFocused()
    }

    @Test
    fun playAllPlaysTheListFromItsFirstVideo() {
        open()
        compose.onNode(button("Play all")).dpadSelect()
        compose.waitForIdle()

        assertEquals(RecordedPlaylist.FIRST_IDS.first(), openedVideoId())
        assertEquals(PLAYLIST_URL, openedPlaylistUrl())
    }

    @Test
    fun aRowPlaysTheListFromItsVideoAndBackReturnsFocusToIt() {
        open()
        compose.onNode(row(3)).dpadSelect()
        compose.waitForIdle()
        assertEquals(RecordedPlaylist.FIRST_IDS[2], openedVideoId())
        assertEquals("a row continues through the list", PLAYLIST_URL, openedPlaylistUrl())

        compose.pressBack()

        compose.onNode(row(3)).assertIsFocused()
    }

    /** M7-D11: the recorded page's last video has a locked DeArrow title in its recorded bucket
     *  (the real seam); the rows around it, in buckets never recorded, keep YouTube's. */
    @Test
    fun aRowShowsDeArrowsTitleWhereDeArrowHasOne() {
        branding = testBranding(brandingScope)
        open()
        val videos = recordedVideos(PLAYLIST_URL)
        val rick = videos.last()
        compose.onNode(hasScrollAction()).performScrollToIndex(videos.lastIndex)
        compose.waitForIdle()

        assertEquals(RecordedPlaylist.LAST_ID, rick.id.value)
        assertNotEquals(rick.title, RecordedBranding.VIDEO_TITLE)
        assertEquals(RecordedBranding.VIDEO_TITLE, titleOf(compose.onNode(row(videos.size)).fetchSemanticsNode()))
        assertEquals(videos[videos.lastIndex - 1].title, titleOf(compose.onNode(row(videos.size - 1)).fetchSemanticsNode()))
        // Its picture is DeArrow's recorded frame, falling back to YouTube's own.
        compose.waitUntil(PICTURE_WAIT_MS) { pictures.any { it.isDeArrowFrameFor(rick) } }
    }

    private fun String.isDeArrowFrameFor(video: VideoItem): Boolean {
        val url = Uri.parse(this)
        return url.host == "dearrow-thumb.ajay.app" &&
            url.getQueryParameter("videoID") == video.id.value &&
            url.getQueryParameter("time") == RecordedBranding.VIDEO_FRAME_SECONDS &&
            url.getQueryParameter("redirectUrl") == video.thumbnailUrl
    }

    @Test
    fun aVideoThePlaylistHoldsTwiceIsListedAtBothPlaces() {
        open()
        val (first, second) = RecordedPlaylist.REPEATED_POSITIONS
        compose.onNode(hasScrollAction()).performScrollToIndex(second)
        compose.waitForIdle()

        val title = recordedVideos(PLAYLIST_URL)[first].title
        listOf(first, second).forEach { position ->
            assertEquals(title, titleOf(compose.onNode(row(position + 1)).fetchSemanticsNode()))
        }
        // Focus comes back to the copy that was opened, not the first one.
        compose.onNode(row(second + 1)).dpadSelect()
        compose.waitForIdle()
        compose.pressBack()
        compose.onNode(row(second + 1)).assertIsFocused()
    }

    @Test
    fun movingDownTheListKeepsTheFocusedRowOneRowDownAndLeftReturnsToPlayAll() {
        open()
        focusRow(1)
        assertEquals(FirstRowTop, focused().boundsInRoot.top, 0.5f)

        repeat(4) { move(FocusDirection.Down) }

        assertEquals("5", texts(focused()).first())
        assertEquals(FirstRowTop + RowHeightDp, focused().boundsInRoot.top, 0.5f)
        move(FocusDirection.Left)
        compose.onNode(button("Play all")).assertIsFocused()
        move(FocusDirection.Right)
        assertTrue("right from Play all enters the list", rows().any { it.id == focused().id })
    }

    @Test
    fun focusNearTheEndOfWhatIsListedLoadsTheNextPage() {
        val details = ChannelExtractor.RecordedPlaylists.getValue(PAGED_PLAYLIST_URL).details
        open(PlaylistItem(details.title, PAGED_PLAYLIST_URL, details.thumbnailUrl, details.videoCount, details.uploaderName))
        focusRow(1)
        assertEquals("nothing more is asked for at the top", 0, extractor.playlistPageCalls)

        compose.onNode(hasScrollAction()).performScrollToIndex(RecordedPlaylist.PAGE_SIZE - 1)
        compose.waitForIdle()
        focusRow(RecordedPlaylist.PAGE_SIZE - 1)
        assertEquals(1, extractor.playlistPageCalls)

        compose.onNode(hasScrollAction()).performScrollToIndex(RecordedPlaylist.PAGE_SIZE)
        compose.waitForIdle()
        val nextFirst = ChannelExtractor.RecordedPlaylistPages.getValue(PAGED_PLAYLIST_URL).items.filterIsInstance<VideoItem>().first()
        assertEquals(RecordedPlaylist.PAGE_2_FIRST_ID, nextFirst.id.value)
        assertEquals(nextFirst.title, titleOf(compose.onNode(row(RecordedPlaylist.PAGE_SIZE + 1)).fetchSemanticsNode()))
    }

    @Test
    fun anAlbumStatesItsCountWithoutAnOwnerEvenWhenItsCardNamesOne() {
        val gate = CompletableDeferred<Unit>()
        extractor.playlistGate = gate
        open(albumCard.copy(uploaderName = RecordedPlaylist.UPLOADER_NAME))
        compose.onNodeWithText("${RecordedAlbumPlaylist.VIDEO_COUNT} videos").assertExists()

        gate.complete(Unit)
        compose.waitForIdle()

        compose.onNodeWithText(RecordedAlbumPlaylist.TITLE).assertExists()
        compose.onNodeWithText("${RecordedAlbumPlaylist.VIDEO_COUNT} videos").assertExists()
        assertEquals(RecordedAlbumPlaylist.IDS.size, rows().size)
        compose.onNode(button("Play all")).assertIsFocused()
    }

    @Test
    fun whileTheVideosLoadTheHeaderShowsFromTheCardAndNothingTakesFocus() {
        val gate = CompletableDeferred<Unit>()
        extractor.playlistGate = gate
        open()

        compose.onNodeWithText(playlistCard.title).assertExists()
        // Not focusable until there is a first video to play.
        compose.onNode(button("Play all")).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        compose.onNode(button("Play all")).assertIsNotFocused()
        assertTrue("no rows while loading", rows().isEmpty())

        gate.complete(Unit)
        compose.waitForIdle()
        compose.onNode(button("Play all")).assertIsFocused()
    }

    @Test
    fun offlineSaysNoConnectionAndBackReturnsWhereTheUserWas() {
        extractor.playlistFailure = AccessException("offline", UnknownHostException("www.youtube.com"))
        open()

        compose.onNodeWithText("No connection to YouTube").assertExists()
        compose.onNodeWithText("This playlist couldn’t load").assertExists()
        compose.onNode(button("Try again")).assertIsFocused()

        compose.onNode(button("Back")).dpadSelect()
        compose.waitForIdle()
        assertTrue(navController.currentBackStackEntry?.destination?.route?.contains(HomeRoute::class.qualifiedName!!) == true)
    }

    @Test
    fun anUnreadableReplySaysYouTubeChangedSomethingAndTryAgainLoadsThePage() {
        extractor.playlistFailure = AccessException("unreadable", IllegalStateException())
        open()

        compose.onNodeWithText("YouTube changed something").assertExists()
        extractor.playlistFailure = null
        compose.onNode(button("Try again")).dpadSelect()
        compose.waitForIdle()

        assertEquals(recordedVideos(PLAYLIST_URL).first().title, titleOf(rows().first()))
        compose.onNode(button("Play all")).assertIsFocused()
    }

    @Test
    fun aPlaylistYouTubeSaysDoesNotExistOffersOnlyBack() {
        extractor.playlistFailure = ChannelExtractor.RecordedUnavailable
        open()

        compose.onNodeWithText("This playlist isn’t available").assertExists()
        compose.onNodeWithText("It may be private or deleted.").assertExists()
        compose.onNodeWithText("YouTube changed something").assertDoesNotExist()
        compose.onNode(button("Try again")).assertDoesNotExist()
        compose.onNode(button("Back")).assertIsFocused()
    }

    @Test
    fun anEmptyPlaylistSaysSoWithBackInPlaceOfPlayAll() {
        extractor.playlists[PLAYLIST_URL] = ChannelExtractor.RecordedPlaylists.getValue(PLAYLIST_URL)
            .copy(videos = ItemsPage(items = emptyList(), next = null))
        open()

        compose.onNodeWithText("This playlist is empty").assertExists()
        compose.onNodeWithText("${RecordedPlaylist.UPLOADER_NAME} · No videos").assertExists()
        compose.onNode(button("Play all")).assertDoesNotExist()
        compose.onNode(button("Back")).assertIsFocused()
    }

    private companion object {
        val playlistCard: PlaylistItem by lazy {
            ChannelExtractor.RecordedTabs.getValue(ChannelTab.PLAYLISTS).items.filterIsInstance<PlaylistItem>().single { it.url == PLAYLIST_URL }
        }
        val albumCard: PlaylistItem by lazy {
            ChannelExtractor.RecordedTabs.getValue(ChannelTab.RELEASES).items.filterIsInstance<PlaylistItem>().first()
        }

        // mdpi (1 dp = 1 px). A row's band spans the list column (486 → 902) plus 9 each side;
        // the first row's top sits at the page's 48, a focused row one 86 dp row lower.
        const val RowWidthDp = 434f - 0.5f
        const val RowHeightDp = 86f
        const val FirstRowTop = 48f

        /** Coil asks for pictures off the main thread. */
        const val PICTURE_WAIT_MS = 5_000L
    }
}
