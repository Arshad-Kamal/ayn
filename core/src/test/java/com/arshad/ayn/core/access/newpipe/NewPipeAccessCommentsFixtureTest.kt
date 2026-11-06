package com.arshad.ayn.core.access.newpipe

import com.arshad.ayn.core.access.AccessException
import com.arshad.ayn.core.access.AccessFailure
import com.arshad.ayn.core.access.CommentsPage
import com.arshad.ayn.core.access.VideoId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.schabi.newpipe.extractor.comments.CommentsInfoItem
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.stream.Description

/**
 * The comments seam (CMT-D1/D2) on the recorded comments flows: a video's first two pages and one
 * comment's replies, a Short's first page, comments off, and a failed fetch. Goldens are read
 * from the raw `/next` answers into `RecordedCorpus.kt` (TEST-ORACLE-01).
 */
class NewPipeAccessCommentsFixtureTest {

    @Test
    fun firstPage_listsYouTubesTopCommentsInOrderWithTheCount() {
        val page = firstPage()

        assertEquals(RecordedComments.PAGE_1_IDS, page.comments.map { it.id })
        assertEquals(RecordedComments.TOTAL_COUNT, page.totalCount)
        assertNotNull(page.next)
        assertFalse(page.commentsOff)
    }

    @Test
    fun pinnedComment_mapsEveryFieldYouTubeShows() {
        val pinned = firstPage().comments.first()

        assertEquals(RecordedComments.PINNED, pinned.copy(repliesToken = null))
        assertNotNull(pinned.repliesToken)
    }

    @Test
    fun editedComment_carriesTheFlagAndItsAgeWithoutTheNote() {
        val edited = firstPage().comments[RecordedComments.EDITED_INDEX]

        assertTrue(edited.edited)
        assertEquals(RecordedComments.EDITED_AGE, edited.age)
    }

    @Test
    fun commentText_isPlainTextWithItsLineBreaks() {
        assertEquals(RecordedComments.TWO_LINES_TEXT, firstPage().comments[RecordedComments.TWO_LINES_INDEX].text)
    }

    @Test
    fun commentWithoutReplies_hasNoRepliesToken() {
        val comment = firstPage().comments[RecordedComments.NO_REPLIES_INDEX]

        assertEquals(0, comment.replyCount)
        assertNull(comment.repliesToken)
    }

    @Test
    fun nextPage_continuesInYouTubesOrder() {
        val (_, second, _) = recordedSession()

        assertEquals(RecordedComments.PAGE_2_SIZE, second.comments.size)
        assertEquals(RecordedComments.PAGE_2_FIRST_IDS, second.comments.take(3).map { it.id })
        assertNull("only a video's first page carries the count", second.totalCount)
        assertNotNull(second.next)
    }

    @Test
    fun repliesToken_opensThatCommentsRepliesAndPagesOn() {
        val (_, _, replies) = recordedSession()

        assertEquals(RecordedComments.REPLY_IDS, replies.comments.map { it.id })
        assertTrue("a reply has no replies of its own", replies.comments.all { it.repliesToken == null && it.replyCount == 0 })
        assertNotNull(replies.next)
    }

    @Test
    fun shortComments_listLikeAVideos() {
        val page = comments(FixtureDownloader(SHORT_COMMENTS_FLOW), SHORT_COMMENTS_VIDEO_ID)

        assertEquals(RecordedComments.SHORT_PAGE_SIZE, page.comments.size)
        assertEquals(RecordedComments.SHORT_FIRST_IDS, page.comments.take(3).map { it.id })
        assertEquals(RecordedComments.SHORT_TOTAL_COUNT, page.totalCount)
    }

    @Test
    fun commentsOff_isAnEmptyPageFlaggedOff() {
        val page = comments(FixtureDownloader(COMMENTS_OFF_FLOW), COMMENTS_OFF_VIDEO_ID)

        assertEquals(CommentsPage(comments = emptyList(), next = null, commentsOff = true), page)
    }

    @Test
    fun privateVideo_failsAsUnreadable() {
        assertFailsUnreadable(FixtureDownloader(PRIVATE_COMMENTS_FLOW), PRIVATE_VIDEO_ID)
    }

    /** YouTube reshaping its comment payloads must not read as a video without comments. */
    @Test
    fun pageWithNoReadableComment_failsAsUnreadable() {
        val reshaped = CorruptingDownloader(FixtureDownloader(COMMENTS_FLOW), "\"commentEntityPayload\":" to "\"commentEntityPayloadGone\":")

        assertFailsUnreadable(reshaped, FIXED_VIDEO_ID)
        assertEquals(emptyList<Pair<String, String>>(), reshaped.unusedReplacements())
    }

    @Test
    fun unreadableComment_isDroppedAndTheRestOfThePageStays() {
        val dropped = RecordedComments.PAGE_1_IDS[1]
        val withoutId = CorruptingDownloader(FixtureDownloader(COMMENTS_FLOW), "\"commentId\":\"$dropped\"" to "\"commentId\":\"\"")

        val page = comments(withoutId, FIXED_VIDEO_ID)

        assertEquals(emptyList<Pair<String, String>>(), withoutId.unusedReplacements())
        assertEquals(RecordedComments.PAGE_1_IDS - dropped, page.comments.map { it.id })
    }

    /** A later page the vendored reader skips (an error body, a reshaped wrapper) must not end the
     *  list silently. */
    @Test
    fun laterPageWithNothingAndNoNextToken_failsAsUnreadable() {
        val skipped = CorruptingDownloader(FixtureDownloader(COMMENTS_FLOW), "\"appendContinuationItemsAction\"" to "\"appendContinuationItemsActionGone\"")
        val access = NewPipeAccess(downloader = skipped)
        val first = runBlocking { access.comments(VideoId(FIXED_VIDEO_ID)) }

        try {
            runBlocking { access.comments(VideoId(FIXED_VIDEO_ID), first.next!!) }
            fail("an empty continuation must fail")
        } catch (e: AccessException) {
            assertEquals(AccessFailure.UNREADABLE, e.failure)
        }
        assertEquals(emptyList<Pair<String, String>>(), skipped.unusedReplacements())
    }

    @Test
    fun ownersCommentWithoutLikesOrAKnownReplyCount_mapsToNoLikesAndNoReplies() {
        val item = commentItem().apply {
            isChannelOwner = true
            textualLikeCount = " "
            replyCount = CommentsInfoItem.UNKNOWN_REPLY_COUNT
        }

        val comment = item.toCommentOrNull()!!

        assertTrue(comment.byChannelOwner)
        assertNull(comment.likes)
        assertEquals(0, comment.replyCount)
    }

    @Test
    fun commentWithoutAnIdAnAuthorOrText_isDropped() {
        assertNull(commentItem().apply { commentId = "" }.toCommentOrNull())
        assertNull(commentItem().apply { uploaderName = null }.toCommentOrNull())
        assertNull(commentItem().apply { commentText = Description("<br> &nbsp;", Description.Type.HTML) }.toCommentOrNull())
    }

    private fun commentItem(): CommentsInfoItem = CommentsInfoItem(0, "https://www.youtube.com/watch?v=$FIXED_VIDEO_ID", "@someone").apply {
        commentId = "id"
        uploaderName = "@someone"
        commentText = Description("hello", Description.Type.PLAIN_TEXT)
    }

    private fun firstPage(): CommentsPage = comments(FixtureDownloader(COMMENTS_FLOW), FIXED_VIDEO_ID)

    /** The `comments` flow replayed in capture order: page 1, page 2, then the replies of page 1's
     *  first comment with replies (as recorded). */
    private fun recordedSession(): Triple<CommentsPage, CommentsPage, CommentsPage> = runBlocking {
        val access = NewPipeAccess(downloader = FixtureDownloader(COMMENTS_FLOW))
        val video = VideoId(FIXED_VIDEO_ID)
        val first = access.comments(video)
        val second = access.comments(video, first.next!!)
        val replies = access.comments(video, first.comments.first { it.repliesToken != null }.repliesToken!!)
        Triple(first, second, replies)
    }

    private fun comments(downloader: Downloader, videoId: String): CommentsPage = runBlocking {
        NewPipeAccess(downloader = downloader).comments(VideoId(videoId))
    }

    private fun assertFailsUnreadable(downloader: Downloader, videoId: String) {
        try {
            comments(downloader, videoId)
            fail("$videoId's comments must fail")
        } catch (e: AccessException) {
            assertEquals(AccessFailure.UNREADABLE, e.failure)
        }
    }
}
