package com.arshad.ayn.feature.player

import com.arshad.ayn.core.access.AccessFailure
import com.arshad.ayn.core.access.RecordedCommentsSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentsBodyTest {
    private val page = RecordedCommentsSession.first.comments
    private val parent = page.first { it.repliesToken != null }
    private val listed = CommentsThread(page, CommentsTail.More)

    @Test
    fun `the video's list shows its rows, a first page failing, loading, none, or comments off`() {
        assertEquals(CommentsBody.Rows(listed), PlayerComments(listed).body)
        assertEquals(CommentsBody.Failed(AccessFailure.OFFLINE), PlayerComments(CommentsThread(emptyList(), CommentsTail.Failed(AccessFailure.OFFLINE))).body)
        assertEquals(CommentsBody.Loading, PlayerComments(LoadingThread).body)
        assertEquals(CommentsBody.None, PlayerComments(CommentsThread(emptyList(), CommentsTail.End)).body)
        assertEquals(CommentsBody.Off, PlayerComments(CommentsThread(emptyList(), CommentsTail.End), off = true).body)
        assertEquals(
            CommentsBody.RestrictedOff,
            PlayerComments(CommentsThread(emptyList(), CommentsTail.End), off = true, offInRestrictedMode = true).body,
        )
    }

    @Test
    fun `an empty page with more to come is listed, so the list asks for the next`() {
        val empty = CommentsThread(emptyList(), CommentsTail.More)
        assertEquals(CommentsBody.Rows(empty), PlayerComments(empty).body)
    }

    @Test
    fun `opened replies show theirs, loading before their first page and nothing when they came back empty`() {
        assertEquals(CommentsBody.Loading, PlayerComments(listed, repliesOf = parent).body)
        assertEquals(LoadingThread, PlayerComments(listed, repliesOf = parent).shownThread)
        val none = CommentsThread(emptyList(), CommentsTail.End)
        assertEquals(CommentsBody.NoReplies, PlayerComments(listed, replies = mapOf(parent.id to none), repliesOf = parent).body)
        val off = PlayerComments(listed, off = true, replies = mapOf(parent.id to listed), repliesOf = parent)
        assertEquals("comments off is the video list's statement", CommentsBody.Rows(listed), off.body)
    }

    @Test
    fun `a list nears its end with its last four rows in view, never before it is laid out`() {
        assertTrue(nearCommentsEnd(lastShownIndex = 16, totalItems = 20, entries = 20))
        assertFalse(nearCommentsEnd(lastShownIndex = 15, totalItems = 20, entries = 20))
        assertFalse("not laid out yet", nearCommentsEnd(lastShownIndex = -1, totalItems = 0, entries = 20))
        assertTrue("an empty list with more to come asks", nearCommentsEnd(lastShownIndex = -1, totalItems = 0, entries = 0))
    }
}
