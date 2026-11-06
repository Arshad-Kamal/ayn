package com.arshad.ayn.core.access

import com.arshad.ayn.core.access.newpipe.COMMENTS_FLOW
import com.arshad.ayn.core.access.newpipe.COMMENTS_OFF_FLOW
import com.arshad.ayn.core.access.newpipe.COMMENTS_OFF_VIDEO_ID
import com.arshad.ayn.core.access.newpipe.FIXED_VIDEO_ID
import com.arshad.ayn.core.filter.ContentFilter
import com.arshad.ayn.core.filter.FilteringExtractor
import com.arshad.ayn.core.store.ProfileRules
import com.arshad.ayn.core.store.ProfileSettings
import com.arshad.ayn.core.store.RestrictedMode
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred

/**
 * [base]'s seam with comments answered from the recorded session ([RecordedCommentsSession]), whatever
 * the video: page 1, page 2, then an empty last page; a replies token gets the recorded replies,
 * or [repliesPages] in turn while it holds any. [asked] lists each ask's token; [failures] fail the
 * next asks in turn; [off] and [none] answer comments off or no comments, [offInRestrictedMode] answers
 * through the real [FilteringExtractor] under a Strict profile; [holding] keeps asks
 * waiting until [release].
 */
class CommentsSeam(base: Extractor) : Extractor by base {
    val asked = mutableListOf<PageToken?>()
    var repliesPages: List<CommentsPage> = emptyList()
    val failures = ArrayDeque<AccessFailure>()
    var off = false
    var offInRestrictedMode = false
    var none = false
    var holding = false
    private var held: CompletableDeferred<Unit>? = null

    fun release() {
        holding = false
        held?.complete(Unit)
    }

    override suspend fun comments(videoId: VideoId, page: PageToken?): CommentsPage {
        asked += page
        if (holding) CompletableDeferred<Unit>().also { held = it }.await()
        failures.removeFirstOrNull()?.let { throw AccessException("comments down", IOException(), it) }
        return when {
            off -> RecordedCommentsSession.off
            offInRestrictedMode -> FilteringExtractor(this) { STRICT }.comments(videoId, page)
            none -> CommentsPage(emptyList(), next = null)
            page == null -> RecordedCommentsSession.first
            page == RecordedCommentsSession.first.next -> RecordedCommentsSession.second
            page == RecordedCommentsSession.second.next -> RecordedCommentsSession.second.copy(comments = emptyList(), next = null)
            else -> repliesPages.firstOrNull()?.also { repliesPages = repliesPages.drop(1) } ?: RecordedCommentsSession.replies
        }
    }
}

/** The recorded comments session, read once through the real seam: the fixed video's pages 1 and
 *  2, the replies of page 1's first comment with any, and a made-for-kids video's comments off.
 *  The values the seam's own test expects of these pages are `newpipe.RecordedComments`. */
object RecordedCommentsSession {
    private val session: List<CommentsPage> = recorded(COMMENTS_FLOW) {
        val first = comments(VideoId(FIXED_VIDEO_ID))
        val second = comments(VideoId(FIXED_VIDEO_ID), first.next!!)
        listOf(first, second, comments(VideoId(FIXED_VIDEO_ID), first.comments.first { it.repliesToken != null }.repliesToken!!))
    }
    val first: CommentsPage = session[0]
    val second: CommentsPage = session[1]
    val replies: CommentsPage = session[2]
    val off: CommentsPage = recorded(COMMENTS_OFF_FLOW) { comments(VideoId(COMMENTS_OFF_VIDEO_ID)) }
}

private val STRICT: ContentFilter = ContentFilter.of(ProfileSettings(restrictedMode = RestrictedMode.Strict), ProfileRules())
