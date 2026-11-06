package com.arshad.ayn.feature.player

import com.arshad.ayn.core.access.AccessException
import com.arshad.ayn.core.access.AccessFailure
import com.arshad.ayn.core.access.Comment
import com.arshad.ayn.core.access.CommentsPage
import com.arshad.ayn.core.access.FakeLiveChat
import com.arshad.ayn.core.access.PageToken
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.captions.RecordedCaptions
import com.arshad.ayn.core.access.newpipe.COMMENTS_FLOW
import com.arshad.ayn.core.access.newpipe.COMMENTS_OFF_FLOW
import com.arshad.ayn.core.access.newpipe.COMMENTS_OFF_VIDEO_ID
import com.arshad.ayn.core.access.newpipe.FIXED_VIDEO_ID
import com.arshad.ayn.core.access.newpipe.FixtureDownloader
import com.arshad.ayn.core.access.newpipe.NewPipeAccess
import com.arshad.ayn.core.access.newpipe.RecordedComments
import com.arshad.ayn.core.access.newpipe.SHORT_COMMENTS_FLOW
import com.arshad.ayn.core.access.newpipe.SHORT_COMMENTS_VIDEO_ID
import com.arshad.ayn.core.access.recorded
import com.arshad.ayn.core.access.ryd.RecordedVotes
import com.arshad.ayn.core.store.FakePlaylistStore
import com.arshad.ayn.core.store.FakeProfileSettingsStore
import com.arshad.ayn.core.store.FakeWatchStore
import com.arshad.ayn.feature.FakeSubscriptionStore
import com.arshad.ayn.feature.ShortsList
import com.arshad.ayn.feature.UnsupportedExtractor
import com.arshad.ayn.feature.testVideo
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The player's comments (CMT-D3, Q3) on the recorded comments session (the fixed video's pages 1
 * and 2 and its pinned comment's replies, read through the real seam): nothing asked until the
 * panel opens, YouTube's order paged to the end, replies opened and closed, comments off, a failed
 * page and Try again, a Short's comments, and a new turn starting empty.
 */
class PlayerViewModelCommentsTest {

    private val dispatcher = StandardTestDispatcher()
    private val seam = RecordedCommentsSeam()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun player(shorts: ShortsList? = null, opening: String = FIXED_VIDEO_ID): PlayerViewModel = PlayerViewModel(
        opening = testVideo(opening),
        playAll = null,
        extractor = seam,
        store = FakeSubscriptionStore(),
        watchStore = FakeWatchStore(),
        playlistStore = FakePlaylistStore(),
        settingsStore = FakeProfileSettingsStore(selectedId = PROFILE),
        voteSource = RecordedVotes.source {},
        captionSource = RecordedCaptions.source(),
        liveChat = FakeLiveChat(),
        shorts = shorts,
        compute = Dispatchers.Main,
    )

    private fun PlayerViewModel.comments(): PlayerComments = uiState.value.comments!!

    private fun PlayerViewModel.listedIds(): List<String> = comments().list.entries.map { it.id }

    @Test
    fun nothingIsAskedUntilThePanelOpens() = runTest(dispatcher) {
        val viewModel = player()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.comments)
        assertEquals(emptyList<Pair<VideoId, PageToken?>>(), seam.asked)
    }

    @Test
    fun firstOpen_showsLoadingThenYouTubesFirstPageWithTheCount() = runTest(dispatcher) {
        val viewModel = player()
        advanceUntilIdle()

        viewModel.openComments()

        assertEquals(PlayerComments(CommentsThread(emptyList(), CommentsTail.Loading)), viewModel.comments())
        advanceUntilIdle()
        assertEquals(RecordedComments.PAGE_1_IDS, viewModel.listedIds())
        assertEquals(CommentsTail.More, viewModel.comments().list.tail)
        assertEquals(RecordedComments.TOTAL_COUNT, viewModel.comments().totalCount)
        assertFalse(viewModel.comments().off)
    }

    @Test
    fun reopeningThePanel_keepsTheTurnsCommentsAndAsksNothing() = runTest(dispatcher) {
        val viewModel = player()
        viewModel.openComments()
        advanceUntilIdle()
        viewModel.moreComments()
        advanceUntilIdle()
        val shown = viewModel.comments()

        viewModel.openComments()
        advanceUntilIdle()

        assertEquals(shown, viewModel.comments())
        assertEquals(2, seam.asked.size)
    }

    @Test
    fun moreComments_pagesOnInYouTubesOrderToTheEnd() = runTest(dispatcher) {
        val viewModel = player()
        viewModel.openComments()
        advanceUntilIdle()

        viewModel.moreComments()
        assertEquals(CommentsTail.Loading, viewModel.comments().list.tail)
        advanceUntilIdle()
        viewModel.moreComments()
        advanceUntilIdle()

        val expected = seam.first.comments + seam.second.comments + seam.last.comments
        assertEquals(expected.map { it.id }, viewModel.listedIds())
        assertEquals(CommentsTail.End, viewModel.comments().list.tail)
        assertEquals(listOf(null, seam.first.next, seam.second.next), seam.asked.map { it.second })
        assertEquals("only page 1 carries the count", RecordedComments.TOTAL_COUNT, viewModel.comments().totalCount)
    }

    @Test
    fun moreComments_atTheEndOrWhileAPageIsOnItsWay_asksNothing() = runTest(dispatcher) {
        val viewModel = player()
        viewModel.openComments()
        viewModel.moreComments()
        advanceUntilIdle()
        assertEquals(1, seam.asked.size)

        viewModel.moreComments()
        viewModel.moreComments()
        advanceUntilIdle()
        viewModel.moreComments()
        advanceUntilIdle()
        viewModel.moreComments()
        advanceUntilIdle()

        assertEquals(3, seam.asked.size)
        assertEquals(CommentsTail.End, viewModel.comments().list.tail)
    }

    @Test
    fun aCommentAlreadyListed_isNotListedAgainFromALaterPage() = runTest(dispatcher) {
        seam.second = seam.second.copy(comments = listOf(seam.first.comments.first()) + seam.second.comments)
        val viewModel = player()
        viewModel.openComments()
        advanceUntilIdle()

        viewModel.moreComments()
        advanceUntilIdle()

        assertEquals((seam.first.comments + seam.second.comments.drop(1)).map { it.id }, viewModel.listedIds())
    }

    @Test
    fun openReplies_showsThatCommentsRepliesAndBackReturnsToTheList() = runTest(dispatcher) {
        val viewModel = player()
        viewModel.openComments()
        advanceUntilIdle()
        val pinned = viewModel.comments().list.entries.first()

        viewModel.openReplies(pinned)
        assertEquals(pinned, viewModel.comments().repliesOf)
        assertEquals(CommentsTail.Loading, viewModel.comments().replies.getValue(pinned.id).tail)
        advanceUntilIdle()

        assertEquals(RecordedComments.REPLY_IDS, viewModel.comments().replies.getValue(pinned.id).entries.map { it.id })
        assertEquals(CommentsTail.More, viewModel.comments().replies.getValue(pinned.id).tail)
        assertEquals(pinned.repliesToken, seam.asked.last().second)
        viewModel.closeReplies()
        assertNull(viewModel.comments().repliesOf)
        assertEquals(RecordedComments.PAGE_1_IDS, viewModel.listedIds())
    }

    @Test
    fun repliesOpenedAgain_showWhatTheTurnHasAndAskNothing() = runTest(dispatcher) {
        val viewModel = player()
        viewModel.openComments()
        advanceUntilIdle()
        val pinned = viewModel.comments().list.entries.first()
        viewModel.openReplies(pinned)
        advanceUntilIdle()
        viewModel.closeReplies()

        viewModel.openReplies(pinned)
        advanceUntilIdle()

        assertEquals(pinned, viewModel.comments().repliesOf)
        assertEquals(RecordedComments.REPLY_IDS, viewModel.comments().replies.getValue(pinned.id).entries.map { it.id })
        assertEquals(2, seam.asked.size)
    }

    @Test
    fun moreAndTryAgainWhileRepliesShow_askForTheReplies_andTheirFailureLeavesTheListAlone() = runTest(dispatcher) {
        val viewModel = player()
        viewModel.openComments()
        advanceUntilIdle()
        val pinned = viewModel.comments().list.entries.first()
        viewModel.openReplies(pinned)
        advanceUntilIdle()

        viewModel.moreComments() // the replies' page 2 isn't recorded: the seam fails it
        advanceUntilIdle()

        assertEquals(seam.replies.next, seam.asked.last().second)
        assertEquals(CommentsTail.Failed(AccessFailure.UNREADABLE), viewModel.comments().replies.getValue(pinned.id).tail)
        assertEquals(RecordedComments.REPLY_IDS, viewModel.comments().replies.getValue(pinned.id).entries.map { it.id })
        assertEquals(CommentsTail.More, viewModel.comments().list.tail)
        viewModel.retryComments()
        advanceUntilIdle()
        assertEquals("Try again asks for the replies' page", listOf(seam.replies.next, seam.replies.next), seam.asked.takeLast(2).map { it.second })
        assertEquals(CommentsTail.More, viewModel.comments().list.tail)
    }

    @Test
    fun aCommentWithoutARepliesToken_opensNothingEvenWithACount() = runTest(dispatcher) {
        val viewModel = player()
        viewModel.openComments()
        advanceUntilIdle()
        val noReplies = viewModel.comments().list.entries[RecordedComments.NO_REPLIES_INDEX]

        val countWithoutToken = viewModel.comments().list.entries.first().copy(repliesToken = null)

        viewModel.openReplies(noReplies)
        viewModel.openReplies(countWithoutToken)
        advanceUntilIdle()

        assertNull(viewModel.comments().repliesOf)
        assertEquals(1, seam.asked.size)
    }

    @Test
    fun commentsOff_showsTheStatement() = runTest(dispatcher) {
        val viewModel = player(opening = COMMENTS_OFF_VIDEO_ID)
        viewModel.openComments()
        advanceUntilIdle()

        assertEquals(PlayerComments(CommentsThread(emptyList(), CommentsTail.End), off = true), viewModel.comments())
    }

    @Test
    fun aFailedFirstPage_saysWhyAndTryAgainAsksAgain() = runTest(dispatcher) {
        seam.failures += AccessFailure.OFFLINE
        val viewModel = player()
        viewModel.openComments()
        advanceUntilIdle()
        assertEquals(PlayerComments(CommentsThread(emptyList(), CommentsTail.Failed(AccessFailure.OFFLINE))), viewModel.comments())

        viewModel.retryComments()
        advanceUntilIdle()

        assertEquals(RecordedComments.PAGE_1_IDS, viewModel.listedIds())
        assertEquals(listOf(null, null), seam.asked.map { it.second })
    }

    @Test
    fun aFailedLaterPage_keepsTheListAndTryAgainContinuesIt() = runTest(dispatcher) {
        val viewModel = player()
        viewModel.openComments()
        advanceUntilIdle()
        seam.failures += AccessFailure.UNREADABLE

        viewModel.moreComments()
        advanceUntilIdle()
        assertEquals(RecordedComments.PAGE_1_IDS, viewModel.listedIds())
        assertEquals(CommentsTail.Failed(AccessFailure.UNREADABLE), viewModel.comments().list.tail)
        viewModel.moreComments()
        advanceUntilIdle()
        assertEquals("more doesn't retry a failure", 2, seam.asked.size)

        viewModel.retryComments()
        advanceUntilIdle()

        assertEquals((seam.first.comments + seam.second.comments).map { it.id }, viewModel.listedIds())
        assertEquals(listOf(null, seam.first.next, seam.first.next), seam.asked.map { it.second })
    }

    @Test
    fun aShortsComments_areAskedForThatShort() = runTest(dispatcher) {
        val viewModel = player(shorts = ShortsList(listOf(testVideo(SHORT_COMMENTS_VIDEO_ID))), opening = SHORT_COMMENTS_VIDEO_ID)
        viewModel.openComments()
        advanceUntilIdle()

        assertEquals(seam.short.comments.map { it.id }, viewModel.listedIds())
        assertEquals(RecordedComments.SHORT_TOTAL_COUNT, viewModel.comments().totalCount)
        assertEquals(VideoId(SHORT_COMMENTS_VIDEO_ID), seam.asked.single().first)
    }

    @Test
    fun aNewTurn_startsEmptyAndALatePageOfTheLastIsDropped() = runTest(dispatcher) {
        val viewModel = lateAnswerOfTheLastTurn()

        assertNull(viewModel.uiState.value.comments)
        viewModel.openComments()
        advanceUntilIdle()
        assertEquals(seam.short.comments.map { it.id }, viewModel.listedIds())
        assertEquals(listOf(VideoId(FIXED_VIDEO_ID), VideoId(SHORT_COMMENTS_VIDEO_ID)), seam.asked.map { it.first })
    }

    @Test
    fun aLateFailureOfTheLastTurn_isDropped() = runTest(dispatcher) {
        seam.failures += AccessFailure.OFFLINE

        val viewModel = lateAnswerOfTheLastTurn()

        assertNull(viewModel.uiState.value.comments)
    }

    /** The panel opened on the fixed video, a new turn begun while its page 1 is held, then the
     *  page answered after the turn closed its comments. */
    private fun TestScope.lateAnswerOfTheLastTurn(): PlayerViewModel {
        val gate = CompletableDeferred<Unit>()
        seam.gate = gate
        val viewModel = player()
        viewModel.openComments()
        advanceUntilIdle()
        viewModel.play(testVideo(SHORT_COMMENTS_VIDEO_ID))
        gate.complete(Unit)
        advanceUntilIdle()
        return viewModel
    }

    /**
     * The seam answering from the recordings: the fixed video's session (page 1, page 2, the pinned
     * comment's replies), a last page after page 2 (the Short's page, its continuation dropped), the
     * Short's and the kids video's first pages; anything else fails as unreadable. [failures] fail
     * the next asks in turn; a [gate] holds every answer until it opens, past a cancel (a late answer).
     */
    private class RecordedCommentsSeam : UnsupportedExtractor("comments") {
        private val session: List<CommentsPage> = runBlocking {
            val access = NewPipeAccess(downloader = FixtureDownloader(COMMENTS_FLOW))
            val first = access.comments(VideoId(FIXED_VIDEO_ID))
            val second = access.comments(VideoId(FIXED_VIDEO_ID), first.next!!)
            listOf(first, second, access.comments(VideoId(FIXED_VIDEO_ID), first.repliedTo().repliesToken!!))
        }
        val first = session[0]
        var second = session[1]
        val replies = session[2]
        val short = recorded(SHORT_COMMENTS_FLOW) { comments(VideoId(SHORT_COMMENTS_VIDEO_ID)) }
        val last = short.copy(next = null, totalCount = null)
        private val off = recorded(COMMENTS_OFF_FLOW) { comments(VideoId(COMMENTS_OFF_VIDEO_ID)) }

        val asked = mutableListOf<Pair<VideoId, PageToken?>>()
        val failures = ArrayDeque<AccessFailure>()
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun comments(videoId: VideoId, page: PageToken?): CommentsPage {
            asked += videoId to page
            gate?.let { withContext(NonCancellable) { it.await() } }
            failures.removeFirstOrNull()?.let { throw AccessException("comments down", IOException(), it) }
            return when {
                videoId.value == SHORT_COMMENTS_VIDEO_ID && page == null -> short
                videoId.value == COMMENTS_OFF_VIDEO_ID && page == null -> off
                videoId.value != FIXED_VIDEO_ID -> null
                page == null -> first
                page == first.next -> second
                page == second.next -> last
                page == first.repliedTo().repliesToken -> replies
                else -> null
            } ?: throw AccessException("no recorded page", IllegalStateException(), AccessFailure.UNREADABLE)
        }
    }

    private companion object {
        const val PROFILE = 1L

        /** The comment whose replies the session recorded: page 1's first with replies. */
        fun CommentsPage.repliedTo(): Comment = comments.first { it.repliesToken != null }
    }
}
