package com.arshad.ayn.ui.tv.player

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.width
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.arshad.ayn.core.access.AccessFailure
import com.arshad.ayn.core.access.ChannelExtractor
import com.arshad.ayn.core.access.ChannelTab
import com.arshad.ayn.core.access.Comment
import com.arshad.ayn.core.access.CommentsPage
import com.arshad.ayn.core.access.CommentsSeam
import com.arshad.ayn.core.access.ItemsPage
import com.arshad.ayn.core.access.PageToken
import com.arshad.ayn.core.access.RecordedCommentsSession
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.access.newpipe.RECORDED_CHANNEL_URL
import com.arshad.ayn.core.access.newpipe.RecordedComments
import com.arshad.ayn.core.access.newpipe.SHORTS_CHANNEL_URL
import com.arshad.ayn.core.access.recorded
import com.arshad.ayn.core.store.FakeProfileSettingsStore
import com.arshad.ayn.core.store.ProfileSettings
import com.arshad.ayn.feature.nav.ChannelRoute
import com.arshad.ayn.feature.nav.PlayerRoute
import com.arshad.ayn.feature.player.EnigmaCard
import com.arshad.ayn.feature.player.FakePlaybackService
import com.arshad.ayn.ui.tv.TestShell
import com.arshad.ayn.ui.tv.common.UnreadableReply
import com.arshad.ayn.ui.tv.dpadSelect
import com.arshad.ayn.ui.tv.installOfflineImageLoader
import com.arshad.ayn.ui.tv.playerRoute
import com.arshad.ayn.ui.tv.press
import com.arshad.ayn.ui.tv.pressBack
import com.arshad.ayn.ui.tv.waitFor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The comments panel (CMT.3; `b3-player-panels` cmt-*, `b3-player` short-cmt, short-comments)
 * through the real shell and a real `MediaController` on the in-process session, over the recorded
 * comments session read through the real seam (the fixed video's pages 1 and 2 and a replies
 * page): Comments in the chrome and on a Short, YouTube's order paged on as focus nears the end,
 * replies under their comment's head (Q3), the statements, Try again, the picture beside the panel
 * (Q7) and Up/Down in the panel over a Short (Q8).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp")
class PlayerCommentsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val channel = ChannelExtractor()
    private val seam = CommentsSeam(channel)
    private lateinit var service: FakePlaybackService
    private lateinit var navController: NavHostController

    private val first: CommentsPage get() = RecordedCommentsSession.first
    private val pinned: Comment get() = first.comments[0]

    @Before
    fun setUp() {
        installOfflineImageLoader(compose.activity)
        service = FakePlaybackService(compose.activity)
    }

    @After
    fun tearDown() {
        service.release()
    }

    @Test
    fun `nothing is asked until Comments opens, then page 1 shows in YouTube's order with the count`() {
        play()
        assertEquals(emptyList<PageToken?>(), seam.asked)
        seam.holding = true

        compose.onNode(commentsControl).dpadSelect()
        compose.waitForIdle()

        compose.onNodeWithText("Top comments").assertExists()
        compose.onNode(entry(pinned)).assertDoesNotExist()
        assertPanelHoldsFocus()
        assertEquals(listOf<PageToken?>(null), seam.asked)

        seam.release()
        compose.waitForIdle()

        compose.onNodeWithText("2,458,514").assertExists()
        compose.onNode(entry(pinned)).assertIsFocused()
            .assertTexts("Pinned by Veritasium", "@YouTube", " · 1 year ago", "324k", "962 replies")
            .assert(hasContentDescription("Hearted by the channel"))
        val tops = first.comments.take(3).map { compose.onNode(entry(it)).getUnclippedBoundsInRoot().top }
        assertEquals(tops.sorted(), tops)
    }

    @Test
    fun `the picture plays whole beside the panel, and BACK closes the panel onto Comments`() {
        play()
        openComments()

        val framed = compose.onNodeWithTag(PICTURE_TAG).getUnclippedBoundsInRoot()
        assertEquals(58f, framed.left.value, 1f)
        assertEquals(28f, framed.top.value, 1f)
        assertEquals(474f, framed.width.value, 1f)
        val beside = compose.onAllNodesWithText(EnigmaCard.title).fetchSemanticsNodes().filter { it.boundsInRoot.top > framed.bottom.value }
        assertEquals("the title under the frame", 1, beside.size)

        compose.pressBack()

        compose.onNodeWithText("Top comments").assertDoesNotExist()
        compose.onNode(commentsControl).assertIsFocused()
        assertEquals(960f, compose.onNodeWithTag(PICTURE_TAG).getUnclippedBoundsInRoot().width.value, 1f)
    }

    @Test
    fun `down moves through the comments and the next page joins as focus nears the end`() {
        play()
        openComments()

        repeat(first.comments.size) { compose.press(AndroidKeyEvent.KEYCODE_DPAD_DOWN) }

        assertEquals(listOf(null, first.next), seam.asked)
        compose.onNode(entry(RecordedCommentsSession.second.comments[0])).assertIsFocused()
    }

    @Test
    fun `a replies chip shows only where replies can open, and OK there opens nothing`() {
        play()
        openComments()
        val without = first.comments[RecordedComments.NO_REPLIES_INDEX]

        repeat(RecordedComments.EDITED_INDEX) { compose.press(AndroidKeyEvent.KEYCODE_DPAD_DOWN) }
        compose.onNode(entry(first.comments[RecordedComments.EDITED_INDEX])).assertIsFocused()
            .assert(hasText(" · ${RecordedComments.EDITED_AGE} · edited"))
        repeat(RecordedComments.NO_REPLIES_INDEX - RecordedComments.EDITED_INDEX) { compose.press(AndroidKeyEvent.KEYCODE_DPAD_DOWN) }
        compose.onNode(entry(without)).assertIsFocused()
        compose.onNode(entry(without) and hasText("replies", substring = true, ignoreCase = true)).assertDoesNotExist()
        val asked = seam.asked.toList()
        compose.press(AndroidKeyEvent.KEYCODE_DPAD_CENTER)

        compose.onNodeWithText("Top comments").assertExists()
        compose.onNode(entry(without)).assertIsFocused()
        assertEquals(asked, seam.asked)
    }

    @Test
    fun `OK opens a comment's replies under its head, and BACK returns to the list on that comment`() {
        play()
        openComments()
        val replied = first.comments.drop(1).first { it.repliesToken != null }

        compose.onNode(entry(replied)).dpadSelect()
        compose.waitForIdle()

        assertEquals(replied.repliesToken, seam.asked.last())
        compose.onNodeWithText("Top comments").assertDoesNotExist()
        compose.onNodeWithText("· 2,458,514").assertExists()
        compose.onNode(entry(RecordedCommentsSession.replies.comments[0])).assertIsFocused()

        compose.pressBack()

        compose.onNodeWithText("Top comments").assertExists()
        compose.onNode(entry(replied)).assertIsFocused()

        compose.pressBack()
        compose.onNode(commentsControl).assertIsFocused()
    }

    @Test
    fun `BACK from deep in replies, past their first page, returns to the list on that comment`() {
        backFromDeepReplies(first.comments.indexOfFirst { it.repliesToken != null && it != pinned })
    }

    @Test
    fun `BACK from deep in replies returns to a comment scrolled down the list`() {
        backFromDeepReplies(first.comments.indexOfLast { it.repliesToken != null })
    }

    /** Opens the replies of page 1's comment at [index], walks down past their first page, BACKs. */
    private fun backFromDeepReplies(index: Int) {
        val replies = RecordedCommentsSession.replies
        val later = replies.copy(comments = replies.comments.map { it.copy(id = it.id + "-2", text = it.text + " (later)") }, next = null)
        seam.repliesPages = listOf(replies.copy(next = PageToken(id = "replies-2")), later)
        play()
        openComments()
        val replied = first.comments[index]
        repeat(index) { compose.press(AndroidKeyEvent.KEYCODE_DPAD_DOWN) }
        compose.onNode(entry(replied)).assertIsFocused()
        compose.press(AndroidKeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitForIdle()

        repeat(replies.comments.size + 2) { compose.press(AndroidKeyEvent.KEYCODE_DPAD_DOWN) }
        compose.onNode(isFocused()).assert(hasText("(later)", substring = true))
        compose.pressBack()

        compose.onNodeWithText("Top comments").assertExists()
        compose.onNode(entry(replied)).assertIsFocused()
    }

    /** PAF-D8: under Restricted Mode the statement names the setting, not YouTube. */
    @Test
    fun `comments off in Restricted Mode say so and where the owner turns it off`() {
        seam.offInRestrictedMode = true
        play()
        openComments()

        compose.onNodeWithText("Comments are off in Restricted Mode").assertExists()
        compose.onNodeWithText("The owner can turn Restricted Mode off in Settings › Filters.").assertExists()
        compose.onNodeWithText("Comments are off for this video").assertDoesNotExist()
        assertPanelHoldsFocus()
    }

    @Test
    fun `comments off show one statement with nothing to focus, and BACK closes`() {
        seam.off = true
        play()
        openComments()

        compose.onNodeWithText("Comments are off for this video").assertExists()
        compose.onNode(button("Try again")).assertDoesNotExist()
        assertPanelHoldsFocus()

        compose.pressBack()
        compose.onNode(commentsControl).assertIsFocused()
    }

    @Test
    fun `a video with no comments says so`() {
        seam.none = true
        play()
        openComments()

        compose.onNodeWithText("No comments yet").assertExists()
        compose.onNodeWithText("Nobody has commented on this video so far.").assertExists()
        assertPanelHoldsFocus()
    }

    @Test
    fun `the panel reopens on the comment it was left on, asking nothing again`() {
        play()
        openComments()
        repeat(3) { compose.press(AndroidKeyEvent.KEYCODE_DPAD_DOWN) }
        val asked = seam.asked.toList()

        compose.pressBack()
        openComments()

        compose.onNode(entry(first.comments[3])).assertIsFocused()
        assertEquals(asked, seam.asked)
    }

    @Test
    fun `page 1 failing offline says so with Try again focused, and Try again loads the list`() {
        seam.failures += AccessFailure.OFFLINE
        play()
        openComments()

        compose.onNodeWithText("No connection to YouTube").assertExists()
        compose.onNodeWithText("Comments didn’t load").assertExists()
        compose.onNode(button("Try again")).assertIsFocused()

        compose.press(AndroidKeyEvent.KEYCODE_DPAD_CENTER)

        assertEquals(listOf<PageToken?>(null, null), seam.asked)
        compose.onNode(entry(pinned)).assertIsFocused()
    }

    @Test
    fun `page 1 that YouTube's reply can't be read for says so with Try again focused`() {
        seam.failures += AccessFailure.UNREADABLE
        play()
        openComments()

        compose.onNodeWithText("YouTube changed something").assertExists()
        compose.onNodeWithText(UnreadableReply).assertExists()
        compose.onNode(button("Try again")).assertIsFocused()
    }

    @Test
    fun `a later page that fails keeps the list, scrolling doesn't retry, and Try again asks once more`() {
        play()
        openComments()
        seam.failures += AccessFailure.UNREADABLE
        val last = first.comments.last()

        repeat(first.comments.size - 1) { compose.press(AndroidKeyEvent.KEYCODE_DPAD_DOWN) }
        compose.onNode(entry(last)).assertIsFocused()
        compose.press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithText("More comments didn’t load").assertExists()
        compose.press(AndroidKeyEvent.KEYCODE_DPAD_UP)
        compose.press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNode(button("Try again")).assertIsFocused()
        assertEquals(listOf(null, first.next), seam.asked)

        compose.press(AndroidKeyEvent.KEYCODE_DPAD_CENTER)

        assertEquals(listOf(null, first.next, first.next), seam.asked)
        compose.onNodeWithText("More comments didn’t load").assertDoesNotExist()
        compose.onNode(entry(last)).assertIsFocused()
    }

    @Test
    fun `on a Short, Comments follows Save and Seek, and the panel takes Up and Down until BACK returns to Comments`() {
        val short = openShortsRun()
        compose.runOnUiThread { service.player.ready() }
        compose.waitForIdle()
        val requested = service.player.requested.toList()

        val order = listOf(short.uploaderName!!, "Subscribe", "Save", "Seek", "Comments")
        order.forEach { label ->
            compose.press(AndroidKeyEvent.KEYCODE_DPAD_RIGHT)
            compose.onNode(button(label)).assertIsFocused()
        }
        order.reversed().drop(1).forEach { label ->
            compose.press(AndroidKeyEvent.KEYCODE_DPAD_LEFT)
            compose.onNode(button(label)).assertIsFocused()
        }
        repeat(order.size - 1) { compose.press(AndroidKeyEvent.KEYCODE_DPAD_RIGHT) }
        compose.onNode(button("Comments")).assertIsFocused()
        compose.press(AndroidKeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitForIdle()

        compose.onNode(entry(pinned)).assertIsFocused()
        assertEquals(280f, compose.onNodeWithTag(PICTURE_TAG).getUnclippedBoundsInRoot().let { it.left + it.width / 2 }.value, 1f)
        compose.press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNode(entry(first.comments[1])).assertIsFocused()
        compose.press(AndroidKeyEvent.KEYCODE_DPAD_UP)
        compose.onNode(entry(pinned)).assertIsFocused()
        compose.press(AndroidKeyEvent.KEYCODE_DPAD_UP) // the first comment: Up stays in the panel
        compose.onNode(entry(pinned)).assertIsFocused()
        assertEquals(requested, service.player.requested)

        compose.pressBack()

        compose.onNodeWithText("Top comments").assertDoesNotExist()
        compose.onNode(button("Comments")).assertIsFocused()
        assertEquals(480f, compose.onNodeWithTag(PICTURE_TAG).getUnclippedBoundsInRoot().let { it.left + it.width / 2 }.value, 1f)
    }

    /** Nothing in the panel can take focus: the panel holds it, not the chrome behind. */
    private fun assertPanelHoldsFocus() {
        compose.onNode(isFocused()).assertExists()
        compose.onNode(commentsControl).assertIsNotFocused()
    }

    private val commentsControl = hasContentDescription("Comments") and hasClickAction()

    private fun button(label: String): SemanticsMatcher = (hasText(label) or hasContentDescription(label)) and hasClickAction()

    /** A comment's entry by its text. */
    private fun entry(comment: Comment): SemanticsMatcher = hasText(comment.text) and hasClickAction()

    private fun SemanticsNodeInteraction.assertTexts(vararg texts: String): SemanticsNodeInteraction = apply {
        texts.forEach { assert(hasText(it)) }
    }

    /** Opens the Enigma video and lets it play 1:00 in, the chrome showing. */
    private fun play() {
        compose.setContent {
            navController = rememberNavController()
            TestShell(extractor = seam, playerSession = { service.session.token }, navController = navController)
        }
        compose.waitForIdle()
        compose.runOnUiThread { navController.navigate(PlayerRoute(EnigmaCard)) }
        compose.waitFor { service.player.requested.isNotEmpty() && service.player.isPlayWhenReady }
        compose.runOnUiThread { service.player.ready(60_000) }
        compose.waitForIdle()
    }

    private fun openComments() {
        compose.onNode(commentsControl).dpadSelect()
        compose.waitForIdle()
    }

    /** The recorded channel page's first Short opened from its Shorts tab, so the player runs
     *  through the tab's Shorts; the Short opened. */
    private fun openShortsRun(): VideoItem {
        val shorts: ItemsPage = recorded("channel-shorts") { channelTab(SHORTS_CHANNEL_URL, ChannelTab.SHORTS) }
        channel.tabs[ChannelTab.SHORTS] = shorts
        val settings = FakeProfileSettingsStore(selectedId = 1).apply { byProfile.value = mapOf(1L to ProfileSettings(showShorts = true)) }
        compose.setContent {
            navController = rememberNavController()
            TestShell(extractor = seam, profileSettings = settings, playerSession = { service.session.token }, navController = navController)
        }
        compose.waitForIdle()
        compose.runOnUiThread { navController.navigate(ChannelRoute(RECORDED_CHANNEL_URL)) }
        compose.waitForIdle()
        compose.onNode(button("Videos")).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNode(button("Shorts")).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        // The tab's first card: a Short card is taller than wide, the top-left one first.
        val card = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
            .filter { it.boundsInRoot.height > it.boundsInRoot.width && it.boundsInRoot.width > SHORT_CARD_MIN_WIDTH_DP }
            .minWith(compareBy({ it.boundsInRoot.top }, { it.boundsInRoot.left }))
        compose.onNode(SemanticsMatcher("the first Short") { it.id == card.id }).dpadSelect()
        compose.waitFor { service.player.requested.isNotEmpty() }
        assertEquals(true, navController.playerRoute()?.short)
        return shorts.shorts[0]
    }

    private companion object {
        const val SHORT_CARD_MIN_WIDTH_DP = 100f
    }
}
