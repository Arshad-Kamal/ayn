package com.arshad.ayn.feature.player

import com.arshad.ayn.core.access.AccessException
import com.arshad.ayn.core.access.AccessFailure
import com.arshad.ayn.core.access.Comment
import com.arshad.ayn.core.access.CommentsPage
import com.arshad.ayn.core.access.Extractor
import com.arshad.ayn.core.access.PageToken
import com.arshad.ayn.core.access.VideoId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/** The turn's comments (CMT-D3): what the comments panel shows, on a video and on a Short. */
data class PlayerComments(
    /** The video's comments in YouTube's order (CMT-D4), as far as paged. */
    val list: CommentsThread,
    /** The video's comment count as YouTube states it; `null` before page 1 or when unreadable. */
    val totalCount: Long? = null,
    /** Comments are turned off or not available for this video (CMT-D1): one statement. */
    val off: Boolean = false,
    /** [off] because the profile is under Restricted Mode, not by YouTube (PAF-D8). */
    val offInRestrictedMode: Boolean = false,
    /** Replies opened this turn, by their comment's id; kept when the list shows again. */
    val replies: Map<String, CommentsThread> = emptyMap(),
    /** The comment whose replies show under its head (Q3), its entry in [replies]; `null` on the
     *  list. */
    val repliesOf: Comment? = null,
)

/** One list the panel pages through: the video's comments, or one comment's replies. */
data class CommentsThread(val entries: List<Comment>, val tail: CommentsTail)

/** A list before its first page. */
val LoadingThread = CommentsThread(emptyList(), CommentsTail.Loading)

/** The list a comments panel shows: the replies opened (loading until their first page lands),
 *  else the video's comments. */
val PlayerComments.shownThread: CommentsThread
    get() = repliesOf?.let { replies[it.id] ?: LoadingThread } ?: list

/** What a comments panel shows of its [shownThread]. */
sealed interface CommentsBody {
    /** Comments are off for the video (on its list, not on replies). */
    data object Off : CommentsBody

    /** Ayn turned the video's comments off: the profile is under Restricted Mode (PAF-D8). */
    data object RestrictedOff : CommentsBody

    /** Rows to list; also a page that listed nothing new while more follow: the empty list is at
     *  its end, so it asks for the next. */
    data class Rows(val thread: CommentsThread) : CommentsBody

    /** The list's first page failed. */
    data class Failed(val failure: AccessFailure) : CommentsBody

    data object Loading : CommentsBody

    /** The video has no comments. */
    data object None : CommentsBody

    /** The replies opened came back empty: nothing under their head. */
    data object NoReplies : CommentsBody
}

val PlayerComments.body: CommentsBody
    get() {
        val thread = shownThread
        val tail = thread.tail
        return when {
            off && repliesOf == null -> if (offInRestrictedMode) CommentsBody.RestrictedOff else CommentsBody.Off
            thread.entries.isNotEmpty() || tail == CommentsTail.More -> CommentsBody.Rows(thread)
            tail is CommentsTail.Failed -> CommentsBody.Failed(tail.failure)
            tail == CommentsTail.Loading -> CommentsBody.Loading
            repliesOf == null -> CommentsBody.None
            else -> CommentsBody.NoReplies
        }
    }

/**
 * Whether a list showing [lastShownIndex] of [totalItems] rows is near its end, where the next page
 * is asked for (the last four rows in view). A list not laid out yet ([totalItems] 0 while it has
 * [entries]) is not: it would otherwise ask as soon as it first shows.
 */
fun nearCommentsEnd(lastShownIndex: Int, totalItems: Int, entries: Int): Boolean =
    !(totalItems == 0 && entries > 0) && lastShownIndex >= totalItems - NEAR_END_ROWS

private const val NEAR_END_ROWS = 4

/** What follows a [CommentsThread]'s last entry. */
sealed interface CommentsTail {
    /** A page is on its way: the first (the loading state) or a later one (loading more). */
    data object Loading : CommentsTail

    /** More follow; [TurnComments.more] asks for them. */
    data object More : CommentsTail

    data object End : CommentsTail

    /** The last ask failed; [TurnComments.retry] asks again. */
    data class Failed(val failure: AccessFailure) : CommentsTail
}

/**
 * One turn's comments (CMT-D3): page 1 is asked for at construction (the panel's first open), later
 * pages and replies when asked, one ask per list at a time; every page and opened replies are kept
 * for the turn, nothing stored. A comment already listed (YouTube's live order can move one onto a
 * later page) is not listed twice: the panel keys its entries by id. Asks run in a child scope of
 * [parent]; [close] cancels them with the turn, so no late page reaches the next video.
 *
 * A list's key is the replied-to comment's id, `null` for the video's own list.
 */
internal class TurnComments(
    private val videoId: VideoId,
    private val extractor: Extractor,
    parent: CoroutineScope,
    private val publish: (PlayerComments) -> Unit,
) {
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext.job))

    /** The token each list asks with next; read only while that list's tail is More or Failed. */
    private val asks = mutableMapOf<String?, PageToken?>(null to null)
    private var state = PlayerComments(CommentsThread(emptyList(), CommentsTail.Loading))

    init {
        ask(null, state)
    }

    /** The shown list's next page, when more follow and none is on its way. */
    fun more() {
        val key = state.repliesOf?.id
        if (thread(key).tail == CommentsTail.More) ask(key, state)
    }

    /** Try again on the shown list's failed ask. */
    fun retry() {
        val key = state.repliesOf?.id
        if (thread(key).tail is CommentsTail.Failed) ask(key, state)
    }

    /** Shows [comment]'s replies; their first page is asked for on the turn's first open. A comment
     *  without a replies token has none to show (a count alone opens nothing). */
    fun openReplies(comment: Comment) {
        val token = comment.repliesToken ?: return
        val opened = state.copy(repliesOf = comment)
        if (comment.id in state.replies) return update(opened)
        asks[comment.id] = token
        ask(comment.id, opened)
    }

    /** Back to the video's list; the replies stay for the turn. */
    fun closeReplies() {
        update(state.copy(repliesOf = null))
    }

    fun close() {
        scope.cancel()
    }

    /** Publishes [base] with [key]'s list loading, then asks for its next page. */
    private fun ask(key: String?, base: PlayerComments) {
        val token = asks[key]
        update(base.withThread(key, thread(key).copy(tail = CommentsTail.Loading)))
        scope.launch {
            val answer = try {
                extractor.comments(videoId, token)
            } catch (e: AccessException) {
                // The ask may not have noticed a cancel by [close].
                ensureActive()
                return@launch update(state.withThread(key, thread(key).copy(tail = CommentsTail.Failed(e.failure))))
            }
            ensureActive()
            receive(key, answer)
        }
    }

    private fun receive(key: String?, page: CommentsPage) {
        asks[key] = page.next
        val listed = thread(key).entries
        val ids = listed.mapTo(HashSet()) { it.id }
        val tail = if (page.next != null) CommentsTail.More else CommentsTail.End
        val received = state.withThread(key, CommentsThread(listed + page.comments.filter { ids.add(it.id) }, tail))
        update(
            if (key == null) received.copy(totalCount = page.totalCount ?: received.totalCount, off = page.commentsOff, offInRestrictedMode = page.offInRestrictedMode) else received,
        )
    }

    private fun thread(key: String?): CommentsThread =
        if (key == null) state.list else state.replies[key] ?: CommentsThread(emptyList(), CommentsTail.Loading)

    private fun PlayerComments.withThread(key: String?, thread: CommentsThread): PlayerComments =
        if (key == null) copy(list = thread) else copy(replies = replies + (key to thread))

    private fun update(comments: PlayerComments) {
        state = comments
        publish(comments)
    }
}
