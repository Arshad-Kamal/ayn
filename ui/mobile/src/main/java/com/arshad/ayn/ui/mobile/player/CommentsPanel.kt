package com.arshad.ayn.ui.mobile.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.arshad.ayn.core.access.AccessFailure
import com.arshad.ayn.core.access.Comment
import com.arshad.ayn.feature.format.MetaSeparator
import com.arshad.ayn.feature.player.CommentsBody
import com.arshad.ayn.feature.player.CommentsTail
import com.arshad.ayn.feature.player.CommentsThread
import com.arshad.ayn.feature.player.LoadingThread
import com.arshad.ayn.feature.player.PlayerComments
import com.arshad.ayn.feature.player.body
import com.arshad.ayn.feature.player.commentCountText
import com.arshad.ayn.feature.player.nearCommentsEnd
import com.arshad.ayn.feature.player.repliesText
import com.arshad.ayn.ui.mobile.R
import com.arshad.ayn.ui.mobile.common.ButtonKind
import com.arshad.ayn.ui.mobile.common.ChannelAvatar
import com.arshad.ayn.ui.mobile.common.PillButton
import com.arshad.ayn.ui.mobile.home.SkeletonBar
import com.arshad.ayn.ui.mobile.home.Statement
import com.arshad.ayn.ui.mobile.home.UnreadableReply
import com.arshad.ayn.ui.mobile.home.rememberPulse
import com.arshad.ayn.ui.mobile.theme.AynColors
import com.arshad.ayn.ui.mobile.theme.AynMotion
import com.arshad.ayn.ui.mobile.theme.atWeight
import java.util.Locale

/** What the comments panel asks of the player's logic (CMT-D3). */
internal class CommentsCalls(
    val more: () -> Unit,
    val retry: () -> Unit,
    val openReplies: (Comment) -> Unit,
    val closeReplies: () -> Unit,
)

/** The channel whose marks pinned and hearted comments carry: the video's. */
internal data class CommentsChannel(val name: String?, val avatarUrl: String?)

private const val SKELETON_ROWS = 5
private const val REPLIES_SKELETON_ROWS = 4
/** Where the list cuts a comment or reply. */
internal const val COMMENT_MAX_LINES = 4
private const val HEAD_MAX_LINES = 6
private const val TAIL_KEY = "comments-tail"

private val Avatar = 34.dp
private val ReplyAvatar = 26.dp
private val RepliesIndent = 46.dp

/**
 * Comments (`m6-player` 21–25; CMT-D3): YouTube's top comments as far as paged ([comments]; `null`
 * reads as loading), the next page asked for as the list nears its end, a comment with replies
 * opening them under it as their head; ← or Back returns to the list where it was ([list], kept
 * for the turn). Statements stand in for the list when comments are off, there are none, or page 1
 * failed (Try again). [backEnabled] gates the replies' Back as the page gates its own. [beside]: it
 * stands beside the sideways picture (`m6-player` 31), as [PanelFrame]'s does.
 */
@Composable
internal fun CommentsPanel(
    comments: PlayerComments?,
    channel: CommentsChannel,
    list: LazyListState,
    calls: CommentsCalls,
    backEnabled: Boolean,
    beside: Boolean,
    onClose: () -> Unit,
) {
    val shown = comments ?: LoadingComments
    val parent = shown.repliesOf
    val locale = LocalConfiguration.current.locales[0]
    BackHandler(enabled = backEnabled && parent != null, onBack = calls.closeReplies)
    // One for the head wherever it stands (above the loading list, then its first row): it stays open as replies land.
    val headWhole = rememberSaveable(parent?.id) { mutableStateOf(false) }
    PanelFrame(
        title = if (parent != null) "Replies" else "Comments",
        onClose = onClose,
        beside = beside,
        head = {
            when {
                parent != null -> {
                    PanelIconButton(R.drawable.ic_back, "Back to comments", calls.closeReplies, Modifier.offset(x = (-10).dp))
                    PanelHead("Replies", kicker = repliesText(parent.replyCount, locale))
                }
                shown.off -> PanelHead("Comments")
                else -> PanelHead("Comments", kicker = "Top comments", count = shown.totalCount?.let { commentCountText(it, locale) })
            }
        },
    ) {
        val body = shown.body
        Column(Modifier.weight(1f).fillMaxWidth()) {
            // Listed rows scroll their replies' head with them; anything else keeps it above.
            if (parent != null && body !is CommentsBody.Rows) RepliesHeadEntry(parent, channel, locale, headWhole, Modifier.padding(top = 4.dp))
            when (body) {
                CommentsBody.Off -> PanelStatement(OffStatement)
                CommentsBody.RestrictedOff -> PanelStatement(RestrictedOffStatement)
                is CommentsBody.Rows -> {
                    val state = if (parent == null) list else remember(parent.id) { LazyListState() }
                    CommentList(body.thread, parent, headWhole, state, channel, calls, locale)
                }
                is CommentsBody.Failed -> PanelStatement(failedStatement(body.failure), onRetry = calls.retry)
                CommentsBody.Loading -> CommentsSkeleton(
                    rows = if (parent == null) SKELETON_ROWS else REPLIES_SKELETON_ROWS,
                    indent = if (parent == null) 0.dp else RepliesIndent,
                )
                CommentsBody.None -> PanelStatement(NoneStatement)
                CommentsBody.NoReplies -> Unit
            }
        }
    }
}

private val LoadingComments = PlayerComments(LoadingThread)

/**
 * A list's entries keyed by comment id (CMP-LAZY-01), under their [parent]'s head when they are its
 * replies, then the tail: loading more, or a later page that failed with Try again (scrolling never
 * retries; [CommentsCalls.more] asks only while more follow).
 */
@Composable
private fun CommentList(
    thread: CommentsThread,
    parent: Comment?,
    headWhole: MutableState<Boolean>,
    state: LazyListState,
    channel: CommentsChannel,
    calls: CommentsCalls,
    locale: Locale,
) {
    val entries = thread.entries.size
    val nearEnd by remember(state, entries) {
        derivedStateOf {
            val info = state.layoutInfo
            nearCommentsEnd(info.visibleItemsInfo.lastOrNull()?.index ?: -1, info.totalItemsCount, entries)
        }
    }
    val tail = thread.tail
    LaunchedEffect(nearEnd, tail) { if (nearEnd && tail == CommentsTail.More) calls.more() }
    val reply = parent != null
    LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(top = 4.dp, bottom = PanelBottom)) {
        if (parent != null) {
            item(key = "head", contentType = "head") { RepliesHeadEntry(parent, channel, locale, headWhole) }
        }
        items(thread.entries, key = { it.id }, contentType = { "comment" }) { comment ->
            CommentEntry(
                comment = comment,
                kind = if (reply) EntryKind.Reply else EntryKind.Comment,
                channel = channel,
                locale = locale,
                onClick = if (!reply && comment.repliesToken != null) ({ calls.openReplies(comment) }) else null,
                modifier = Modifier.animateItem(fadeInSpec = AynMotion.base(), placementSpec = null, fadeOutSpec = null),
            )
        }
        when (tail) {
            CommentsTail.Loading -> item(key = TAIL_KEY, contentType = "tail") { LoadingMore(Modifier.animateItem(fadeInSpec = AynMotion.base(), placementSpec = null)) }
            is CommentsTail.Failed -> item(key = TAIL_KEY, contentType = "tail") {
                FailedMore(tail.failure, calls.retry, Modifier.animateItem(fadeInSpec = AynMotion.base(), placementSpec = null))
            }
            CommentsTail.More, CommentsTail.End -> Unit
        }
    }
}

/** How an entry is drawn: a comment, a reply (indented, smaller avatar), or the comment heading
 *  its replies (`.rhead`, more of its text; opens nothing, a tap shows the rest when cut). */
private enum class EntryKind { Comment, Reply, Head }

@Composable
private fun RepliesHeadEntry(parent: Comment, channel: CommentsChannel, locale: Locale, whole: MutableState<Boolean>, modifier: Modifier = Modifier) {
    Column(modifier) {
        CommentEntry(parent, EntryKind.Head, channel, locale, onClick = null, whole = whole)
        HorizontalDivider(color = AynColors.Line, modifier = Modifier.padding(start = PanelSide, end = PanelSide, bottom = 4.dp))
    }
}

private val HeartColor = Color(0xFFF2545B)

/**
 * A comment (`.cmt`): its author's picture, the pinned line, the by-line (the channel's own name
 * in a pill), age and "edited", the text cut at a few lines, then likes, the channel's heart and,
 * with [onClick], how many replies it opens. Without [onClick], a tap on cut text shows it whole,
 * in place, [whole] keeping that across scrolling (Q38; read out as collapsed, with an expand
 * action). A tappable entry lights while pressed.
 */
@Composable
private fun CommentEntry(
    comment: Comment,
    kind: EntryKind,
    channel: CommentsChannel,
    locale: Locale,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    whole: MutableState<Boolean> = rememberSaveable(comment.id) { mutableStateOf(false) },
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 1f else 0f, AynMotion.press(), label = "commentPress")
    val reply = kind == EntryKind.Reply
    var cut by remember { mutableStateOf(false) }
    val tap = when {
        onClick != null -> Modifier.clickable(interactionSource = interaction, indication = null, role = Role.Button, onClickLabel = "Open replies", onClick = onClick)
        cut && !whole.value -> Modifier
            .clickable(interactionSource = interaction, indication = null, onClickLabel = "Show the whole comment") { whole.value = true }
            .semantics {
                stateDescription = "Collapsed"
                expand {
                    whole.value = true
                    true
                }
            }
        else -> Modifier.semantics(mergeDescendants = true) { }
    }
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = if (reply) RepliesIndent + PanelSide - 8.dp else PanelSide - 8.dp, end = PanelSide - 8.dp)
            .drawBehind { if (press > 0f) drawRoundRect(AynColors.Pressed, cornerRadius = CornerRadius(12.dp.toPx()), alpha = press) }
            .then(tap)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val size = if (reply) ReplyAvatar else Avatar
        Box(Modifier.padding(top = if (comment.pinned) 17.dp else 1.dp).size(size)) {
            ChannelAvatar(comment.authorName.removePrefix("@"), comment.authorAvatarUrl, monogramSize = if (reply) 10f else 13f)
        }
        Column(Modifier.weight(1f)) {
            if (comment.pinned) PinnedLine(channel.name)
            Byline(comment)
            Text(
                comment.text,
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 1.42.em),
                maxLines = when {
                    whole.value -> Int.MAX_VALUE
                    kind == EntryKind.Head -> HEAD_MAX_LINES
                    else -> COMMENT_MAX_LINES
                },
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (!whole.value) cut = it.hasVisualOverflow },
                modifier = Modifier.padding(top = 3.dp).animateContentSize(AynMotion.base()),
            )
            Footer(comment, channel, repliesLink = onClick != null, locale)
        }
    }
}

@Composable
private fun PinnedLine(channelName: String?) {
    Row(Modifier.padding(bottom = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Icon(painterResource(R.drawable.ic_pin), contentDescription = null, tint = AynColors.Ink3, modifier = Modifier.size(13.dp))
        Text(
            channelName?.let { "Pinned by $it" } ?: "Pinned",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp).atWeight(620),
            color = AynColors.Ink3,
        )
    }
}

/** The author (in a pill when it is the video's channel), YouTube's age and "edited". */
@Composable
private fun Byline(comment: Comment) {
    val style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp, fontFeatureSettings = "tnum")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            comment.authorName,
            style = style.atWeight(650),
            color = if (comment.byChannelOwner) AynColors.Ink else AynColors.Ink2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f, fill = false)
                .then(if (comment.byChannelOwner) Modifier.background(AynColors.Indicator, CircleShape).padding(horizontal = 7.dp, vertical = 1.dp) else Modifier),
        )
        listOfNotNull(comment.age, "edited".takeIf { comment.edited }).takeIf { it.isNotEmpty() }?.let { parts ->
            Text(parts.joinToString(MetaSeparator, prefix = MetaSeparator), style = style, color = AynColors.Ink3, maxLines = 1)
        }
    }
}

/** Likes, the channel's heart (its picture marked), and the replies to open ([repliesLink]). */
@Composable
private fun Footer(comment: Comment, channel: CommentsChannel, repliesLink: Boolean, locale: Locale) {
    if (comment.likes == null && !comment.hearted && !repliesLink) return
    val style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp, fontFeatureSettings = "tnum")
    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        comment.likes?.let { likes ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Icon(painterResource(R.drawable.ic_thumb_up), contentDescription = "Likes", tint = AynColors.Ink3, modifier = Modifier.size(14.dp))
                Text(likes, style = style, color = AynColors.Ink3)
            }
        }
        if (comment.hearted) {
            Box(Modifier.size(18.dp)) {
                ChannelAvatar(channel.name.orEmpty(), channel.avatarUrl, monogramSize = 8f)
                Icon(
                    painterResource(R.drawable.ic_heart),
                    contentDescription = "Hearted by the channel",
                    tint = HeartColor,
                    modifier = Modifier.align(Alignment.BottomEnd).offset(x = 5.dp, y = 3.dp).size(11.dp),
                )
            }
        }
        if (repliesLink) Text(repliesText(comment.replyCount, locale), style = style.atWeight(680), color = AynColors.Ink)
    }
}

/** Loading (`.cskel`): the list's shape, breathing. */
@Composable
private fun CommentsSkeleton(rows: Int, indent: Dp) {
    val pulse = rememberPulse()
    Column(Modifier.fillMaxSize().padding(top = 4.dp)) {
        repeat(rows) { row ->
            Row(Modifier.padding(start = PanelSide + indent, end = PanelSide, top = 12.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SkeletonBar(pulse, Modifier.size(Avatar), CircleShape)
                Column(Modifier.weight(1f).padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SkeletonBar(pulse, Modifier.fillMaxWidth(SkeletonFirst[row % SkeletonFirst.size]).height(11.dp))
                    SkeletonBar(pulse, Modifier.fillMaxWidth(SkeletonSecond[row % SkeletonSecond.size]).height(11.dp))
                    if (row % 2 == 0) SkeletonBar(pulse, Modifier.fillMaxWidth(0.58f).height(11.dp))
                }
            }
        }
    }
}

private val SkeletonFirst = listOf(0.38f, 0.30f, 0.44f, 0.34f, 0.40f)
private val SkeletonSecond = listOf(0.92f, 0.76f, 0.88f, 0.60f, 0.84f)

/** The next page on its way, under the last entry (`.ctail`). */
@Composable
private fun LoadingMore(modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)) {
        Spinner(18.dp)
        Text("Loading more comments", style = MaterialTheme.typography.bodySmall, color = AynColors.Ink2)
    }
}

/** A later page that failed: the loaded entries stay, Try again asks once more. */
@Composable
private fun FailedMore(failure: AccessFailure, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(vertical = 16.dp).semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(painterResource(failureIcon(failure)), contentDescription = null, tint = AynColors.Ink2, modifier = Modifier.size(16.dp))
            Text("More comments didn’t load", style = MaterialTheme.typography.bodySmall, color = AynColors.Ink2)
        }
        PillButton("Try again", onRetry, kind = ButtonKind.Tonal, icon = R.drawable.ic_retry)
    }
}

private fun failureIcon(failure: AccessFailure): Int = if (failure == AccessFailure.OFFLINE) R.drawable.ic_offline else R.drawable.ic_alert

/** A statement in the list's place (`.cstate`). */
private class CommentsStatement(val icon: Int, val kicker: String, val title: String, val body: String)

private val OffStatement = CommentsStatement(
    R.drawable.ic_comments_off,
    "Comments",
    "Comments are off for this video",
    "YouTube turns comments off for videos made for kids, and doesn’t show them on some others.",
)

private val RestrictedOffStatement = CommentsStatement(
    R.drawable.ic_comments_off,
    "Comments",
    "Comments are off in Restricted Mode",
    "The owner can turn Restricted Mode off in Settings › Filters.",
)

private val NoneStatement = CommentsStatement(R.drawable.ic_comment, "Comments", "No comments yet", "Nobody has commented on this video so far.")

/** Page 1 failed: worded as the phone's other surfaces word it (D8); the video plays on. */
private fun failedStatement(failure: AccessFailure): CommentsStatement {
    val offline = failure == AccessFailure.OFFLINE
    return CommentsStatement(
        failureIcon(failure),
        if (offline) "No connection to YouTube" else "YouTube changed something",
        "Comments didn’t load",
        if (offline) "Check that the phone is online, then try again. The video plays on." else UnreadableReply,
    )
}

@Composable
private fun PanelStatement(statement: CommentsStatement, onRetry: (() -> Unit)? = null) {
    Statement(
        icon = statement.icon,
        kicker = statement.kicker,
        title = statement.title,
        body = statement.body,
        compact = true,
        modifier = Modifier.padding(start = PanelSide + 4.dp, end = PanelSide + 4.dp, top = 28.dp).semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        onRetry?.let { PillButton("Try again", it, icon = R.drawable.ic_retry) }
    }
}
