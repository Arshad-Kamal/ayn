package com.arshad.ayn.ui.mobile.player

import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import com.arshad.ayn.core.access.AccessFailure
import com.arshad.ayn.core.access.Comment
import com.arshad.ayn.core.access.PageToken
import com.arshad.ayn.core.access.RecordedCommentsSession
import com.arshad.ayn.core.access.VoteLookup
import com.arshad.ayn.core.access.VoteSource
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.ProfileTint
import com.arshad.ayn.feature.format.compactCount
import com.arshad.ayn.feature.nav.PlayerRoute
import com.arshad.ayn.feature.player.EnigmaCard
import com.arshad.ayn.feature.player.FakePlaybackService
import com.arshad.ayn.feature.player.RecordedEnigmaPage
import com.arshad.ayn.feature.player.commentCountText
import com.arshad.ayn.feature.player.percentText
import com.arshad.ayn.feature.player.repliesText
import com.arshad.ayn.feature.player.timeText
import com.arshad.ayn.ui.mobile.ProfileShell
import com.arshad.ayn.ui.mobile.installOfflineImageLoader
import com.arshad.ayn.ui.mobile.library.TestStores
import com.arshad.ayn.ui.mobile.shell.ShellOpening
import com.arshad.ayn.ui.mobile.theme.AynTheme
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * The panels under the picture (`m6-player` 9–10, 21–25) through the shell's real wiring, a real
 * `MediaController` on an in-process session dressed with the recorded Enigma page (its chapters,
 * likes and description), the recorded comments session read through the real seam
 * ([RecordedCommentsSession]: pages 1 and 2 and a replies page) and a scripted vote lookup.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class WatchPanelsTest {
    private companion object {
        val ARSHAD = Profile(1, "Arshad", ProfileTint.Amber, isOwner = true, lock = null)
        const val DISLIKES = 1_300L
    }

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var service: FakePlaybackService
    private var votes: VoteLookup = VoteLookup.Found(DISLIKES)
    private var voteAsks = 0
    private val stores by lazy {
        TestStores(
            session = { service.session.token },
            votes = VoteSource {
                voteAsks++
                votes
            },
        )
    }
    private val seam get() = stores.comments
    private lateinit var slot: PlayerSlot

    private val first get() = RecordedCommentsSession.first
    private val pinned: Comment get() = first.comments[0]
    private val chapters get() = RecordedEnigmaPage.chapters

    @Before
    fun setUp() {
        installOfflineImageLoader(compose.activity)
        service = FakePlaybackService(compose.activity)
    }

    @After
    fun tearDown() {
        service.release()
    }

    /** Opens the Enigma video and lets the stream land [atMs] in. */
    private fun play(atMs: Long = 60_000) {
        val models = stores.models()
        compose.setContent {
            AynTheme {
                slot = rememberPlayerSlot()
                ProfileShell(ShellOpening(ARSHAD, onSwitchProfile = {}), models, slot = slot)
            }
        }
        compose.waitForIdle()
        compose.runOnUiThread { slot.open(PlayerRoute(EnigmaCard)) }
        waitFor { service.player.requested.isNotEmpty() && service.player.isPlayWhenReady }
        compose.runOnUiThread { service.player.ready(atMs) }
        settle()
    }

    private fun waitFor(what: () -> Boolean) {
        repeat(20) {
            if (what()) return
            compose.waitForIdle()
        }
        assertTrue("condition never held", what())
    }

    private fun settle() {
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
    }

    private fun shows(text: String, substring: Boolean = false): Boolean =
        compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    private fun button(label: String): SemanticsMatcher = (hasText(label) or hasContentDescription(label)) and hasClickAction()

    private fun tap(label: String) {
        compose.onNode(button(label)).performClick()
        settle()
    }

    /** A comment's entry by its author. */
    private fun entry(comment: Comment): SemanticsMatcher = hasText(comment.authorName, substring = true)

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        settle()
    }

    /** The meta line's "more", read out as opening Video details. */
    private fun openDetailsFromMore() {
        compose.onNode(
            hasText("more", substring = true) and SemanticsMatcher("opens video details") {
                it.config.getOrElseNullable(SemanticsActions.OnClick) { null }?.label == "Open video details"
            },
        ).performClick()
        settle()
    }

    private fun scrollPanelTo(index: Int) {
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(index)
        settle()
    }

    @Test
    fun theChipsAreSaveCommentsAndChaptersAndNothingIsAskedBeforeCommentsOpens() {
        play()

        compose.onNode(button("Save")).assertExists()
        compose.onNode(button("Comments")).assertExists()
        compose.onNode(button("Chapters")).assert(hasText(chapters.size.toString()))
        assertEquals(emptyList<PageToken?>(), seam.asked)
        assertEquals(0, voteAsks)
    }

    @Test
    fun commentsOpenUnderThePlayingPictureWithTheirShapeThenPageOneInOrderWithTheCount() {
        play()
        seam.holding = true

        tap("Comments")
        assertTrue(shows("Top comments"))
        compose.onNode(entry(pinned)).assertDoesNotExist()
        assertEquals(listOf<PageToken?>(null), seam.asked)

        seam.release()
        settle()
        compose.onNode(entry(pinned)).assertExists()
        val count = commentCountText(first.totalCount!!, Locale.US)
        assertTrue(shows(count))
        val shown = compose.onAllNodes(hasText(first.comments[1].authorName, substring = true)).fetchSemanticsNodes().single().boundsInRoot
        assertTrue("YouTube's order", compose.onNode(entry(pinned)).fetchSemanticsNode().boundsInRoot.top < shown.top)
        compose.onNodeWithTag(PICTURE_TAG).assertExists()
        assertTrue("the video plays on", service.player.isPlayWhenReady)

        tap("Close")
        compose.onNode(button("Comments")).assert(hasText(count))
    }

    @Test
    fun thePinnedCommentNamesTheChannelAndCarriesItsHeart() {
        play()
        tap("Comments")

        compose.onNode(entry(pinned)).assert(hasText("Pinned by ${EnigmaCard.uploaderName}"))
        compose.onNode(entry(pinned) and hasClickAction()).assert(hasContentDescription("Hearted by the channel"))
        compose.onNode(entry(pinned)).assert(hasText(repliesText(pinned.replyCount, Locale.US)))
    }

    @Test
    fun nearTheEndTheNextPageIsAskedAndJoinsBelow() {
        play()
        tap("Comments")

        scrollPanelTo(first.comments.lastIndex)
        assertEquals(listOf(null, first.next), seam.asked)
        scrollPanelTo(first.comments.size)
        compose.onNode(entry(RecordedCommentsSession.second.comments.first { c -> first.comments.none { it.id == c.id } })).assertExists()
    }

    @Test
    fun aLaterPageThatFailsLeavesTryAgainUnderTheListAndScrollingNeverRetries() {
        play()
        tap("Comments")
        seam.failures += AccessFailure.OFFLINE

        scrollPanelTo(first.comments.lastIndex)
        scrollPanelTo(first.comments.size)
        assertTrue(shows("More comments didn’t load"))
        scrollPanelTo(0)
        scrollPanelTo(first.comments.size)
        assertEquals("no retry from scrolling", 2, seam.asked.size)

        tap("Try again")
        assertEquals(listOf(null, first.next, first.next), seam.asked)
        assertFalse(shows("More comments didn’t load"))
    }

    @Test
    fun aCommentWithRepliesOpensThemUnderItsHeadAndBackReturnsToTheList() {
        play()
        tap("Comments")

        compose.onNode(entry(pinned) and hasClickAction()).performClick()
        settle()
        assertTrue(shows("Replies"))
        assertTrue(shows(repliesText(pinned.replyCount, Locale.US)))
        compose.onNode(entry(RecordedCommentsSession.replies.comments.first())).assertExists()
        assertFalse("the head opens nothing", compose.onAllNodes(entry(pinned) and hasClickAction()).fetchSemanticsNodes().isNotEmpty())

        back()
        assertTrue(shows("Top comments"))
        compose.onNode(entry(pinned) and hasClickAction()).assertExists()
        assertTrue("the panel stays", slot.expanded)

        compose.onNode(entry(pinned) and hasClickAction()).performClick()
        settle()
        tap("Back to comments")
        assertTrue(shows("Top comments"))
        assertEquals("replies asked once in the turn", 1, seam.asked.count { it == pinned.repliesToken })
    }

    @Test
    fun repliesOpenedFurtherDownReturnToTheListWhereItWas() {
        play()
        tap("Comments")
        val index = first.comments.indexOfLast { it.repliesToken != null && it.authorName != pinned.authorName }
        val later = first.comments[index]
        scrollPanelTo(index)

        compose.onNode(entry(later) and hasClickAction()).performClick()
        settle()
        assertTrue(shows("Replies"))
        back()
        compose.onNode(entry(later) and hasClickAction()).assertIsDisplayed()
        compose.onNode(entry(pinned) and hasClickAction()).assertIsNotDisplayed()
    }

    @Test
    fun closingThePanelOnRepliesReopensCommentsOnTheList() {
        play()
        tap("Comments")
        compose.onNode(entry(pinned) and hasClickAction()).performClick()
        settle()

        tap("Close")
        tap("Comments")
        assertTrue(shows("Top comments"))
        compose.onNode(entry(pinned) and hasClickAction()).assertExists()
    }

    @Test
    fun repliesThatFailKeepTheirHeadAboveTryAgain() {
        play()
        tap("Comments")
        seam.failures += AccessFailure.OFFLINE

        compose.onNode(entry(pinned) and hasClickAction()).performClick()
        settle()
        assertTrue(shows("Comments didn’t load"))
        compose.onNode(entry(pinned)).assertIsDisplayed()
        tap("Try again")
        compose.onNode(entry(RecordedCommentsSession.replies.comments.first())).assertExists()
    }

    /** Q38: a cut reply (it has no replies of its own to open) shows its whole text on a tap, in place. */
    @Test
    fun aCutReplyShowsItsWholeTextOnATap() {
        play()
        tap("Comments")
        compose.onNode(entry(pinned) and hasClickAction()).performClick()
        settle()
        val long = RecordedCommentsSession.replies.comments.maxBy { it.text.lines().size }
        assertTrue("a reply longer than the cut in the recording", long.text.lines().size > COMMENT_MAX_LINES)
        scrollPanelTo(RecordedCommentsSession.replies.comments.indexOf(long) + 1)
        val showWhole = hasText(long.text) and SemanticsMatcher("shows the whole comment") {
            it.config.getOrElseNullable(SemanticsActions.OnClick) { null }?.label == "Show the whole comment"
        }
        assertEquals(COMMENT_MAX_LINES, lineCount(long))

        compose.onNode(showWhole).performClick()
        settle()
        assertWhole(long)
        compose.onNode(showWhole).assertDoesNotExist()

        // Scrolled out of composition and back, it stays whole.
        scrollPanelTo(0)
        scrollPanelTo(RecordedCommentsSession.replies.comments.size)
        scrollPanelTo(RecordedCommentsSession.replies.comments.indexOf(long) + 1)
        assertWhole(long)
    }

    /** Q38: the replies head opened whole while its replies load stays whole once they land (a narrow
     *  phone, where the recorded longest head is cut at its 6 lines; native graphics, so text wraps). */
    @Test
    @Config(qualifiers = "w320dp-h800dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun aRepliesHeadShownWholeWhileItsRepliesLoadStaysWholeWhenTheyLand() {
        play()
        tap("Comments")
        val long = first.comments.filter { it.repliesToken != null }.maxBy { it.text.length }
        scrollPanelTo(first.comments.indexOf(long))
        seam.holding = true
        compose.onNode(entry(long) and hasClickAction()).performClick()
        settle()
        compose.onNode(hasText(long.text) and SemanticsMatcher.keyIsDefined(SemanticsActions.Expand)).performSemanticsAction(SemanticsActions.Expand)
        settle()
        assertWhole(long)

        seam.release()
        settle()

        compose.onNode(entry(RecordedCommentsSession.replies.comments.first())).assertExists()
        assertWhole(long)
    }

    private fun assertWhole(comment: Comment) {
        val layout = textLayout(comment)
        assertTrue("more than the cut shows", layout.lineCount > COMMENT_MAX_LINES)
        assertFalse("nothing is cut", layout.hasVisualOverflow)
    }

    private fun lineCount(comment: Comment): Int = textLayout(comment).lineCount

    private fun textLayout(comment: Comment): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNode(hasText(comment.text), useUnmergedTree = true).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!(results)
        return results.single()
    }

    /** PAF-D8: under Restricted Mode the statement names the setting, not YouTube. */
    @Test
    fun commentsOffInRestrictedModeSaySoAndWhereTheOwnerTurnsItOff() {
        seam.offInRestrictedMode = true
        play()
        tap("Comments")

        assertTrue(shows("Comments are off in Restricted Mode"))
        assertTrue(shows("The owner can turn Restricted Mode off in Settings › Filters."))
        assertFalse(shows("Comments are off for this video"))
    }

    @Test
    fun commentsOffIsOneStatementWithoutACount() {
        seam.off = true
        play()
        tap("Comments")

        assertTrue(shows("Comments are off for this video"))
        assertFalse(shows("Top comments"))
        tap("Close")
        compose.onNode(button("Comments")).assert(SemanticsMatcher.expectValue(SemanticsProperties.Text, listOf(AnnotatedString("Comments"))))
    }

    @Test
    fun noCommentsSaysSo() {
        seam.none = true
        play()
        tap("Comments")

        assertTrue(shows("No comments yet"))
    }

    @Test
    fun aFirstPageThatFailsOffersTryAgainAndThePlayerPlaysOn() {
        seam.failures += AccessFailure.OFFLINE
        play()
        tap("Comments")

        assertTrue(shows("Comments didn’t load"))
        assertTrue(shows("Check that the phone is online, then try again. The video plays on."))
        tap("Try again")
        compose.onNode(entry(pinned)).assertExists()
    }

    @Test
    fun closeBackAndASwipeDownCloseThePanelWithoutDocking() {
        play()

        tap("Comments")
        tap("Close")
        assertFalse(shows("Top comments"))

        tap("Comments")
        back()
        assertFalse(shows("Top comments"))
        assertTrue("Back closed the panel, not the page", slot.expanded)

        tap("Chapters")
        compose.onNode(hasText("Chapters") and isHeading()).performTouchInput { swipeDown(startY = centerY, endY = centerY + 60f, durationMillis = 600) }
        settle()
        assertTrue("a short slow drag springs back", shows("${chapters.size} chapters"))
        compose.onNode(hasText("Chapters") and isHeading()).performTouchInput { swipeDown(startY = centerY, endY = centerY + 700f, durationMillis = 150) }
        settle()
        assertFalse(shows("${chapters.size} chapters"))
        assertTrue(slot.expanded)
    }

    @Test
    fun moreOpensDetailsWhichAsksForTheVotesOnce() {
        service.player.frameRate = 24f
        play()
        assertEquals(0, voteAsks)

        openDetailsFromMore()
        assertTrue(shows("Video details"))
        assertEquals(1, voteAsks)
        val likes = RecordedEnigmaPage.likeCount!!
        assertTrue(shows("15M views · 12 days ago · 2160p · 24 fps"))
        assertTrue(shows(compactCount(likes)))
        assertTrue(shows(compactCount(DISLIKES)))
        assertTrue(shows(percentText(likes * 100.0 / (likes + DISLIKES), Locale.US)))
        assertTrue(shows("Dislikes are an estimate from Return YouTube Dislike."))
        assertTrue(shows(RecordedEnigmaPage.description!!.lines().first { it.isNotBlank() }.trim(), substring = true))

        tap("Close")
        openDetailsFromMore()
        assertEquals("asked once per turn", 1, voteAsks)
    }

    @Test
    fun detailsSaysWhenReturnYouTubeDislikeHasNoData() {
        votes = VoteLookup.None
        play()

        openDetailsFromMore()
        assertTrue(shows("No dislike data for this video"))
        assertFalse(shows("Dislikes are an estimate from Return YouTube Dislike."))
    }

    @Test
    fun chaptersMarkTheOnePlayingAndATapPlaysFromAnotherAndCloses() {
        play(atMs = chapters[1].startSeconds * 1000 + 5_000)

        tap("Chapters")
        assertTrue(shows("${chapters.size} chapters"))
        compose.onNode(hasText(chapters[1].title) and hasClickAction())
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Playing"))
        compose.onNode(hasText(chapters[2].title) and hasClickAction()).assert(hasText(timeText(chapters[2].startSeconds * 1000)))

        compose.onNode(hasText(chapters[2].title) and hasClickAction()).performClick()
        settle()
        assertEquals(chapters[2].startSeconds * 1000, service.player.seeks.last())
        assertFalse(shows("${chapters.size} chapters"))
    }

    @Test
    fun chaptersOpenOnTheOnePlayingWhenItIsFarDown() {
        val last = chapters.last()
        play(atMs = last.startSeconds * 1000 + 5_000)

        tap("Chapters")
        compose.onNode(hasText(last.title) and hasClickAction())
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Playing"))
    }

    @Test
    fun aChapterTappedInDetailsPlaysFromThereAndCloses() {
        play()
        openDetailsFromMore()

        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(4 + 3) // title, votes, description, heading, then the chapters
        settle()
        compose.onNode(hasText(chapters[3].title) and hasClickAction()).performClick()
        settle()
        assertEquals(chapters[3].startSeconds * 1000, service.player.seeks.last())
        assertFalse(compose.onAllNodes(hasText("Video details") and isHeading()).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun playbacksVideoDetailsRowClosesTheSheetAndOpensDetails() {
        play()
        compose.onNodeWithTag(PICTURE_TAG).performTouchInput { click(center) }
        settle()
        tap("Playback settings")

        compose.onNode(button("Video details")).performClick()
        settle()
        settle()
        assertTrue(ShadowDialog.getShownDialogs().none { (it as? ComponentDialog)?.isShowing == true })
        assertTrue(shows("Video details"))
        assertTrue(compose.onAllNodes(hasText("Video details") and isHeading()).fetchSemanticsNodes().isNotEmpty())
    }
}
