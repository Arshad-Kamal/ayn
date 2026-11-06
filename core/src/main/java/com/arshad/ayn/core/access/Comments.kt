package com.arshad.ayn.core.access

/**
 * One YouTube comment or reply as the comments panel shows it (CMT-D2): YouTube's words and
 * marks, read-only.
 *
 * @property id YouTube's comment id: unique within a video, a stable list key.
 * @property authorName the author's `@handle` as YouTube shows it.
 * @property authorUrl the author's channel URL (the seam's channel key); `null` when unreadable.
 * @property authorAvatarUrl YouTube sends one small avatar (88×88).
 * @property text plain text: link and @mention text kept, line breaks kept, entities decoded.
 * @property age YouTube's own wording ("2 weeks ago"), without the "(edited)" note.
 * @property edited YouTube marks the comment edited.
 * @property likes YouTube's short form ("324k", "145"); `null` with no likes.
 * @property replyCount how many replies YouTube reports; 0 for a reply or when YouTube gives no
 *   count. Whether replies can be opened is [repliesToken] != null, never this count.
 * @property repliesToken the token [Extractor.comments] takes for this comment's replies; `null`
 *   when it has none or it is unreadable, and always for a reply (YouTube nests one level).
 * @property pinned pinned by the channel.
 * @property hearted hearted by the channel.
 * @property byChannelOwner written by the video's channel.
 */
data class Comment(
    val id: String,
    val authorName: String,
    val authorUrl: String?,
    val authorAvatarUrl: String?,
    val text: String,
    val age: String?,
    val edited: Boolean,
    val likes: String?,
    val replyCount: Int,
    val repliesToken: PageToken?,
    val pinned: Boolean,
    val hearted: Boolean,
    val byChannelOwner: Boolean,
)

/**
 * One page of a video's comments, or of one comment's replies, in YouTube's order (CMT-D4).
 *
 * @property next the following page's token; `null` at the end.
 * @property totalCount the video's comment count as YouTube states it, on a video's first page
 *   only; `null` elsewhere or when unreadable.
 * @property commentsOff the video's comments are turned off or not available (made for kids, age
 *   gated: YouTube doesn't say which); the page is then empty.
 * @property offInRestrictedMode Ayn turned them off: the profile is under Restricted Mode (PAF-D8);
 *   [commentsOff] is set too.
 */
data class CommentsPage(
    val comments: List<Comment>,
    val next: PageToken?,
    val totalCount: Long? = null,
    val commentsOff: Boolean = false,
    val offInRestrictedMode: Boolean = false,
)
