package com.arshad.ayn.ui.tv.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
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
import com.arshad.ayn.ui.tv.R
import com.arshad.ayn.ui.tv.common.AvatarPicture
import com.arshad.ayn.ui.tv.common.Kicker
import com.arshad.ayn.ui.tv.common.PillButton
import com.arshad.ayn.ui.tv.common.SkeletonLine
import com.arshad.ayn.ui.tv.common.UnreadableReply
import com.arshad.ayn.ui.tv.common.bottomFade
import com.arshad.ayn.ui.tv.common.failureKicker
import com.arshad.ayn.ui.tv.common.hairlineBorder
import com.arshad.ayn.ui.tv.common.rememberRise
import com.arshad.ayn.ui.tv.common.rememberSheen
import com.arshad.ayn.ui.tv.common.rising
import com.arshad.ayn.ui.tv.common.skeleton
import com.arshad.ayn.ui.tv.common.topFade
import com.arshad.ayn.ui.tv.focus.ControlStyle
import com.arshad.ayn.ui.tv.focus.FocusMemory
import com.arshad.ayn.ui.tv.focus.LocalFocusMemory
import com.arshad.ayn.ui.tv.focus.NeutralSurface
import com.arshad.ayn.ui.tv.focus.TintedIcon
import com.arshad.ayn.ui.tv.focus.TintedText
import com.arshad.ayn.ui.tv.focus.drawInflated
import com.arshad.ayn.ui.tv.focus.focusMemory
import com.arshad.ayn.ui.tv.focus.rememberFadeTint
import com.arshad.ayn.ui.tv.settings.SheetHead
import com.arshad.ayn.ui.tv.theme.KeColors
import com.arshad.ayn.ui.tv.theme.KeLayout
import com.arshad.ayn.ui.tv.theme.KeMotion
import com.arshad.ayn.ui.tv.theme.atWeight
import java.util.Locale

/** What the comments panel asks of the player's logic (CMT-D3); [open] runs as the panel opens. */
internal class CommentsCalls(
    val open: () -> Unit,
    val more: () -> Unit,
    val retry: () -> Unit,
    val openReplies: (Comment) -> Unit,
    val closeReplies: () -> Unit,
)

/**
 * Where a list of comments was left: its scroll and the entry that last held focus ([memory], the
 * list's one restoration mechanism, CMP-FOCUS-03). The video's list keeps one for the turn, so the
 * panel reopens, and its replies close, on the same comment; each replies list opened gets its own.
 */
internal class CommentsPlace {
    val list = LazyListState()
    private val lastFocused = mutableStateOf<String?>(null)
    private val returning = mutableStateOf<String?>(null)
    val memory = FocusMemory(lastFocused, returning)

    /** The comment whose replies open: focus returns to it as they close. Held apart because the
     *  list leaving composition moves focus through its other entries, which the memory records. */
    fun leaveFor(commentId: String) {
        returning.value = commentId
    }

    /** The panel closes: the entry left is held, as for [leaveFor], unless replies already hold one. */
    fun leave() {
        if (returning.value == null) returning.value = lastFocused.value
    }

    /** Focuses the comment left for its replies, else the entry last focused, else the first in view. */
    fun focusEntry() {
        val firstShown = list.layoutInfo.visibleItemsInfo.firstOrNull { it.contentType == ENTRY_TYPE }?.key as? String
        memory.restore(initialKey = firstShown)
        returning.value = null
    }
}

/** The channel whose marks pinned and hearted comments carry: the video's. A value: an equal one
 *  lets the panel skip. */
internal data class CommentsChannel(val name: String?, val avatarUrl: String?)

// `.cpanel` (px ÷ 2): 400 dp at the right edge, 28 dp in from its left side, the safe line at its right.
internal val PanelWidth = 400.dp
internal val PanelInset = 28.dp
private val PanelSlide = 40.dp
private val PanelTopFill = Color(0xFF1A1612)
private val PanelEdge = Color.White.copy(alpha = 0.1f)

// `.cbody`: the list fades in under the head and out at the foot.
private val ListTop = 9.dp
private val RepliesListTop = 3.dp
private val ListTopFade = 10.dp
private val ListFootFade = 46.dp
private val ListPadding = PaddingValues(top = 8.dp, bottom = 70.dp)

/** `.creplies .vtrack`: replies start under their comment's text, sliding in from 20 dp right. */
private val RepliesIndent = 36.dp
private val RepliesSlide = 20.dp
private const val REPLIES_SLIDE_DELAY_MS = 40

// `.cmt`: a row whose focus fill reaches 10 dp past the avatar; 2 dp between rows.
private val EntryShape = RoundedCornerShape(11.dp)
private val EntryCorner = 11.dp
private val EntryFocusFill = Color.White.copy(alpha = 0.08f)
private val EntryGap = 2.dp
private val EntryAvatar = 26.dp
private val ReplyAvatar = 20.dp
private val PinnedAvatarTop = 16.5.dp
private const val AvatarFocusScale = 1.08f
private const val AvatarHairline = 0.08f
private const val RestingEntryAlpha = 0.78f

/** A very long comment is cut here, so every entry fits the list and the next stays reachable. */
private const val MAX_COMMENT_LINES = 14

private val RepliesChipRest = Color.White.copy(alpha = 0.08f)
private val InkTint = ColorProducer { KeColors.Ink }
private val OwnerPill = Color.White.copy(alpha = 0.16f)
private val HeartColor = Color(0xFFF8495F)
private val ParentFill = Color.White.copy(alpha = 0.05f)
private val ParentRing = Color.White.copy(alpha = 0.08f)
private val ParentShape = RoundedCornerShape(11.dp)


internal const val ENTRY_TYPE = "comment"
private const val TAIL_TYPE = "tail"
private const val TAIL_KEY = "comments-tail"
private const val RETRY_KEY = "comments-retry"
private const val SKELETON_ROWS = 5
private const val REPLIES_SKELETON_ROWS = 4

/**
 * The comments panel (`b3-player-panels` cmt-*, `b3-player` short-comments): one for both players
 * (CMT Q2), sliding in at the right while [open]. It lists YouTube's top comments as far as paged
 * ([comments]; `null` reads as loading), asks for the next page as the list nears its end, and
 * opens a comment's replies under its head on OK (Q3), BACK returning to the list on that comment.
 * Statements stand in for the list when comments are off, there are none, or page 1 failed (Try
 * again focused). Focus lands on the entry last left ([place]), else the first; while nothing in
 * the panel can take focus, the panel holds it. Closing it is the caller's BACK.
 */
@Composable
internal fun CommentsPanel(
    open: Boolean,
    comments: PlayerComments?,
    channel: CommentsChannel,
    place: CommentsPlace,
    calls: CommentsCalls,
) {
    SlidingPanel(open) { Panel(open, comments ?: LoadingComments, channel, place, calls) }
}

/** A panel at the right edge, sliding in from it while [open] (the comments and chat panels'). */
@Composable
internal fun SlidingPanel(open: Boolean, content: @Composable () -> Unit) {
    val slide = with(LocalDensity.current) { PanelSlide.roundToPx() }
    AnimatedVisibility(
        visible = open,
        modifier = Modifier.fillMaxSize(),
        enter = fadeIn(tween(KeMotion.MOVE_MS, easing = KeMotion.EaseOut)) +
            slideInHorizontally(tween(KeMotion.MOVE_MS, easing = KeMotion.EaseOut)) { slide },
        exit = fadeOut(KeMotion.fast()) + slideOutHorizontally(KeMotion.fast()) { slide },
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) { content() }
    }
}

private val LoadingComments = PlayerComments(LoadingThread)

/** What takes focus in the panel: an entry, page 1's Try again, or the panel itself. */
private enum class Landing { Entry, Retry, Panel }

@Composable
private fun Panel(active: Boolean, comments: PlayerComments, channel: CommentsChannel, place: CommentsPlace, calls: CommentsCalls) {
    val parent = comments.repliesOf
    val body = comments.body
    val shown = if (parent == null) place else remember(parent.id) { CommentsPlace() }
    val landing = when {
        body is CommentsBody.Rows && body.thread.entries.isNotEmpty() -> Landing.Entry
        body is CommentsBody.Failed -> Landing.Retry
        else -> Landing.Panel
    }
    val panelFocus = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }
    val locale = LocalConfiguration.current.locales[0]
    // Gated on [active]: a panel fading out never takes focus back from what its close focused.
    LaunchedEffect(active, landing, parent?.id) {
        if (!active) return@LaunchedEffect
        withFrameNanos { } // focusability follows the recomposition
        when (landing) {
            Landing.Entry -> shown.focusEntry()
            Landing.Retry -> retryFocus.requestFocus()
            Landing.Panel -> panelFocus.requestFocus()
        }
    }
    LaunchedEffect(active) { if (!active) place.leave() }
    BackHandler(enabled = active && parent != null) { calls.closeReplies() }
    Column(
        Modifier
            .fillMaxHeight()
            .width(PanelWidth)
            .panelGround()
            .focusRequester(panelFocus)
            // The panel holds focus only when nothing in it can.
            .focusProperties { canFocus = landing == Landing.Panel }
            .focusable()
            .padding(start = PanelInset, end = KeLayout.SafeHorizontal, top = KeLayout.SafeVertical),
    ) {
        if (parent == null) ListHead(comments.totalCount, locale) else RepliesHead(comments.totalCount, parent, channel, locale)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val indent = if (parent == null) 0.dp else RepliesIndent
            val listTop = if (parent == null) ListTop else RepliesListTop
            when (body) {
                CommentsBody.Off -> PanelStatement(OffCopy)
                CommentsBody.RestrictedOff -> PanelStatement(RestrictedOffCopy)
                // One list per place: a list shared with the replies would reuse the focused reply's
                // item for a comment in its slot, focus and all, and the comment left never takes it.
                is CommentsBody.Rows -> key(shown) {
                    CommentList(body.thread, shown, reply = parent != null, channel, calls, locale, Modifier.padding(top = listTop))
                }
                is CommentsBody.Failed -> PanelStatement(failedCopy(body.failure), onRetry = calls.retry, retryFocus = retryFocus)
                CommentsBody.Loading -> CommentsSkeleton(
                    rows = if (parent == null) SKELETON_ROWS else REPLIES_SKELETON_ROWS,
                    modifier = Modifier.padding(start = indent, top = listTop),
                )
                CommentsBody.None -> PanelStatement(NoneCopy)
                CommentsBody.NoReplies -> Unit
            }
        }
    }
}

/** `.cpanel`'s ground: a warm top fading into the surface, a hairline at its left side, and its
 *  shadow over what plays beside it. */
internal fun Modifier.panelGround(): Modifier = this
    .dropShadow(RectangleShape) {
        radius = 60.dp.toPx()
        spread = (-10).dp.toPx()
        offset = Offset(-20.dp.toPx(), 0f)
        color = Color.Black
        alpha = 0.9f
    }
    .drawWithCache {
        val fill = Brush.verticalGradient(0f to PanelTopFill, 0.4f to KeColors.Surface1)
        onDrawBehind {
            drawRect(fill)
            drawRect(PanelEdge, size = size.copy(width = 1.dp.toPx()))
        }
    }

/** "Top comments", then Comments and YouTube's count once page 1 brings it. */
@Composable
private fun ListHead(totalCount: Long?, locale: Locale) {
    SheetHead(
        kicker = "Top comments",
        title = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Comments", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.alignByBaseline())
                totalCount?.let {
                    Text(
                        text = commentCountText(it, locale),
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 15.sp, fontFeatureSettings = "tnum"),
                        color = KeColors.Ink3,
                        modifier = Modifier.alignByBaseline(),
                    )
                }
            }
        },
    )
}

/** Replies (Q3): back to Comments and its count, the comment they answer, how many there are. */
@Composable
private fun RepliesHead(totalCount: Long?, parent: Comment, channel: CommentsChannel, locale: Locale) {
    val kicker = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, fontFeatureSettings = "tnum")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(painterResource(R.drawable.ic_back), contentDescription = null, tint = KeColors.Ink3, modifier = Modifier.size(13.dp))
        Text("Comments", style = kicker, color = KeColors.Ink3)
        totalCount?.let { Text("· ${commentCountText(it, locale)}", style = kicker, color = KeColors.Ink3) }
    }
    val unfocused = remember { mutableStateOf(0f) }
    CommentLayout(
        comment = parent,
        kind = EntryKind.Head,
        channel = channel,
        focus = unfocused,
        locale = locale,
        modifier = Modifier
            .padding(top = 11.dp)
            .rising(rememberRise())
            .fillMaxWidth()
            .background(ParentFill, ParentShape)
            .hairlineBorder(ParentRing, 11.dp)
            .padding(start = 11.dp, end = 11.dp, top = 10.dp, bottom = 11.dp),
    )
    Text(
        text = repliesText(parent.replyCount, locale),
        style = MaterialTheme.typography.labelLarge.atWeight(640).copy(fontSize = 13.sp, fontFeatureSettings = "tnum"),
        modifier = Modifier.padding(top = 13.dp),
    )
}

/**
 * A list's entries, keyed by comment id (CMP-LAZY-01), and its tail: loading more, or a later page
 * that failed with Try again (scrolling never retries, [CommentsCalls.more] asks only while more
 * follow). Rows other than the focused one rest dimmer while the list holds focus.
 */
@Composable
private fun CommentList(
    thread: CommentsThread,
    place: CommentsPlace,
    reply: Boolean,
    channel: CommentsChannel,
    calls: CommentsCalls,
    locale: Locale,
    modifier: Modifier = Modifier,
) {
    var listFocused by remember { mutableStateOf(false) }
    val entries = thread.entries.size
    val nearEnd by remember(place, entries) {
        derivedStateOf {
            val info = place.list.layoutInfo
            nearCommentsEnd(info.visibleItemsInfo.lastOrNull()?.index ?: -1, info.totalItemsCount, entries)
        }
    }
    val tail = thread.tail
    LaunchedEffect(nearEnd, tail) { if (nearEnd && tail == CommentsTail.More) calls.more() }
    // Replies slide in from the right under their head (`cslide`); the video's list just shows.
    val slide = rememberRise(if (reply) REPLIES_SLIDE_DELAY_MS else 0)
    CompositionLocalProvider(LocalFocusMemory provides place.memory) {
        LazyColumn(
            state = place.list,
            modifier = modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (reply) {
                        alpha = slide.value
                        translationX = RepliesSlide.toPx() * (1f - slide.value)
                    }
                }
                .topFade(ListTopFade)
                .bottomFade(ListFootFade)
                .onFocusChanged { listFocused = it.hasFocus },
            contentPadding = ListPadding,
            verticalArrangement = Arrangement.spacedBy(EntryGap),
        ) {
            items(thread.entries, key = { it.id }, contentType = { ENTRY_TYPE }) { comment ->
                CommentEntry(
                    comment = comment,
                    kind = if (reply) EntryKind.Reply else EntryKind.Comment,
                    channel = channel,
                    listFocused = listFocused,
                    locale = locale,
                    // A reply, or a comment without a replies token, opens nothing.
                    onClick = {
                        if (comment.repliesToken != null) place.leaveFor(comment.id)
                        calls.openReplies(comment)
                    },
                    modifier = Modifier.padding(start = if (reply) RepliesIndent else 0.dp).focusMemory(comment.id),
                )
            }
            when (tail) {
                CommentsTail.Loading -> item(key = TAIL_KEY, contentType = TAIL_TYPE) { LoadingMore(Modifier.padding(start = indentOf(reply))) }
                is CommentsTail.Failed -> item(key = TAIL_KEY, contentType = TAIL_TYPE) {
                    // Not an entry: the list's memory keeps the comment left for the button.
                    CompositionLocalProvider(LocalFocusMemory provides null) {
                        FailedMore(tail.failure, Modifier.padding(start = indentOf(reply))) {
                            // The button leaves as the page is asked for again; focus goes back up.
                            place.memory.restore(initialKey = thread.entries.last().id)
                            calls.retry()
                        }
                    }
                }
                CommentsTail.More, CommentsTail.End -> Unit
            }
        }
    }
}

private fun indentOf(reply: Boolean): Dp = if (reply) RepliesIndent else 0.dp

/** How an entry is drawn: a comment, a reply (smaller, no replies of its own), or the comment
 *  heading its replies (not focusable, its text in full ink). */
private enum class EntryKind { Comment, Reply, Head }

/** A comment (`.cmt`): content focus family, so focus lights the row and rings the avatar. */
@Composable
private fun CommentEntry(
    comment: Comment,
    kind: EntryKind,
    channel: CommentsChannel,
    listFocused: Boolean,
    locale: Locale,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val focus = animateFloatAsState(if (focused) 1f else 0f, KeMotion.fast(), label = "comment")
    val rest = animateFloatAsState(if (listFocused && !focused) RestingEntryAlpha else 1f, KeMotion.fast(), label = "commentRest")
    NeutralSurface(
        onClick = onClick,
        shape = EntryShape,
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = rest.value }
            .drawBehind {
                drawRoundRect(EntryFocusFill, cornerRadius = CornerRadius(EntryCorner.toPx()), alpha = focus.value)
            }
            .onFocusChanged { focused = it.isFocused },
    ) {
        CommentLayout(comment, kind, channel, focus, locale, Modifier.padding(start = 10.dp, end = 10.dp, top = 9.dp, bottom = 10.dp))
    }
}

/** A comment's avatar, then its pinned line, by-line, text and footer, inked by [focus] (0..1,
 *  read when drawn). */
@Composable
private fun CommentLayout(
    comment: Comment,
    kind: EntryKind,
    channel: CommentsChannel,
    focus: State<Float>,
    locale: Locale,
    modifier: Modifier = Modifier,
) {
    val reply = kind == EntryKind.Reply
    // In replies (`.creplies`) the head's avatar and text are a reply's, its avatar column a comment's.
    val small = kind != EntryKind.Comment
    Row(modifier) {
        Box(Modifier.width(if (reply) ReplyAvatar else EntryAvatar)) {
            CommentAvatar(
                comment = comment,
                size = if (small) ReplyAvatar else EntryAvatar,
                focus = focus,
                modifier = Modifier.padding(top = if (comment.pinned) PinnedAvatarTop else 1.dp),
            )
        }
        Column(Modifier.padding(start = if (reply) 9.dp else 10.dp).weight(1f)) {
            if (comment.pinned) PinnedLine(channel.name, rememberFadeTint(KeColors.Ink3, KeColors.Ink2, focus))
            Byline(comment, focus)
            TintedText(
                text = comment.text,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = if (small) 12.5.sp else 13.sp, lineHeight = 1.38.em),
                tint = if (kind == EntryKind.Head) InkTint else rememberFadeTint(KeColors.Ink2, KeColors.Ink, focus),
                maxLines = MAX_COMMENT_LINES,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
            Footer(comment, channel, repliesChip = kind == EntryKind.Comment && comment.repliesToken != null, focus, locale)
        }
    }
}

/** The author's avatar (YouTube's 88 × 88): grown inside a ground gap and an ink ring when focused. */
@Composable
private fun CommentAvatar(comment: Comment, size: Dp, focus: State<Float>, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size)
            .graphicsLayer {
                val scale = 1f + (AvatarFocusScale - 1f) * focus.value
                scaleX = scale
                scaleY = scale
            }
            .drawBehind {
                val f = focus.value
                val radius = this.size.minDimension / 2
                drawCircle(Color.White, radius + 0.5.dp.toPx(), alpha = AvatarHairline * (1f - f))
                if (f > 0f) {
                    drawCircle(KeColors.Ink, radius + 3.dp.toPx(), alpha = f)
                    drawCircle(KeColors.Ground, radius + 1.5.dp.toPx(), alpha = f)
                }
            },
    ) {
        AvatarPicture(comment.authorName.removePrefix("@"), comment.authorAvatarUrl, if (size == ReplyAvatar) 8.sp else 10.sp)
    }
}

@Composable
private fun PinnedLine(channelName: String?, tint: ColorProducer) {
    Row(
        modifier = Modifier.padding(bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TintedIcon(painterResource(R.drawable.ic_pin), contentDescription = null, tint = tint, modifier = Modifier.size(11.dp))
        TintedText(
            text = channelName?.let { "Pinned by $it" } ?: "Pinned",
            style = MaterialTheme.typography.labelMedium.atWeight(600).copy(fontSize = 10.5.sp),
            tint = tint,
        )
    }
}

/** The author (in a pill when it is the video's channel), YouTube's age, and "edited". */
@Composable
private fun Byline(comment: Comment, focus: State<Float>) {
    val style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.5.sp, fontFeatureSettings = "tnum")
    Row(verticalAlignment = Alignment.CenterVertically) {
        TintedText(
            text = comment.authorName,
            style = style.atWeight(640),
            tint = if (comment.byChannelOwner) InkTint else rememberFadeTint(KeColors.Ink2, KeColors.Ink, focus),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f, fill = false)
                .then(
                    if (comment.byChannelOwner) {
                        Modifier.background(OwnerPill, CircleShape).padding(horizontal = 6.dp, vertical = 0.5.dp)
                    } else {
                        Modifier
                    },
                ),
        )
        listOfNotNull(comment.age, "edited".takeIf { comment.edited }).takeIf { it.isNotEmpty() }?.let { parts ->
            TintedText(
                text = parts.joinToString(MetaSeparator, prefix = MetaSeparator),
                style = style,
                tint = rememberFadeTint(KeColors.Ink3, KeColors.Ink2, focus),
                maxLines = 1,
            )
        }
    }
}

/** Likes, the channel's heart (its avatar marked), and the replies to open ([repliesChip]). */
@Composable
private fun Footer(comment: Comment, channel: CommentsChannel, repliesChip: Boolean, focus: State<Float>, locale: Locale) {
    if (comment.likes == null && !comment.hearted && !repliesChip) return
    val style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp, fontFeatureSettings = "tnum")
    val color = rememberFadeTint(KeColors.Ink3, KeColors.Ink2, focus)
    Row(
        modifier = Modifier.padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        comment.likes?.let { likes ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TintedIcon(painterResource(R.drawable.ic_thumb_up), contentDescription = "Likes", tint = color, modifier = Modifier.size(12.dp))
                TintedText(likes, style = style, tint = color)
            }
        }
        if (comment.hearted) {
            Box(Modifier.size(15.dp)) {
                AvatarPicture(channel.name.orEmpty(), channel.avatarUrl, 7.sp)
                Icon(
                    painterResource(R.drawable.ic_heart),
                    contentDescription = "Hearted by the channel",
                    tint = HeartColor,
                    modifier = Modifier.align(Alignment.BottomEnd).offset(x = 3.5.dp, y = 2.5.dp).size(9.dp),
                )
            }
        }
        if (repliesChip) {
            val ink = rememberFadeTint(KeColors.Ink2, KeColors.OnInk, focus)
            Row(
                modifier = Modifier
                    .height(20.dp)
                    .drawBehind { drawInflated(CircleShape, 0.dp, lerp(RepliesChipRest, KeColors.Ink, focus.value), 1f) }
                    .padding(start = 8.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                TintedText(repliesText(comment.replyCount, locale), style = style, tint = ink)
                TintedIcon(painterResource(R.drawable.ic_chevron), contentDescription = null, tint = ink, modifier = Modifier.size(11.dp))
            }
        }
    }
}

/** Loading: the list's shape (`.cskel`), one slow sheen. */
@Composable
private fun CommentsSkeleton(rows: Int, modifier: Modifier = Modifier) {
    val sheen = rememberSheen()
    Column(modifier.padding(start = 10.dp, top = 8.dp)) {
        repeat(rows) { row ->
            Row(Modifier.padding(top = 9.dp, bottom = 11.dp)) {
                Box(Modifier.size(EntryAvatar).skeleton(sheen, CircleShape))
                Column(Modifier.padding(start = 10.dp).weight(1f)) {
                    SkeletonLine(SkeletonFirst[row % SkeletonFirst.size], top = 2.dp, sheen = sheen)
                    SkeletonLine(SkeletonSecond[row % SkeletonSecond.size], top = 7.dp, sheen = sheen)
                    if (row % 2 == 0) {
                        SkeletonThird[row % SkeletonThird.size].takeIf { it > 0f }?.let { SkeletonLine(it, top = 7.dp, sheen = sheen) }
                    }
                }
            }
        }
    }
}

// `.cskel` line widths, per row.
private val SkeletonFirst = listOf(0.38f, 0.30f, 0.44f, 0.34f, 0.40f)
private val SkeletonSecond = listOf(0.92f, 0.76f, 0.88f, 0.60f, 0.84f)
private val SkeletonThird = listOf(0.58f, 0f, 0.40f)

/** The next page on its way, under the last entry (`.ctail`). */
@Composable
private fun LoadingMore(modifier: Modifier = Modifier) {
    val shown = rememberRise()
    Row(
        modifier = modifier
            .graphicsLayer { alpha = shown.value }
            .padding(start = 10.dp, top = 18.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Spinner(20.dp, 2.dp)
        Text("Loading more comments", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.5.sp), color = KeColors.Ink3)
    }
}

private val TailButtonStyle = ControlStyle.Secondary.copy(growFrom = TransformOrigin(0f, 0.5f))

/** A later page that failed (`.ctail.fail`): the loaded entries stay, Try again asks once more. */
@Composable
private fun FailedMore(failure: AccessFailure, modifier: Modifier = Modifier, onRetry: () -> Unit) {
    Column(
        modifier = modifier
            .rising(rememberRise())
            .padding(start = 10.dp, top = 18.dp, bottom = 11.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(painterResource(failureKicker(failure).icon), contentDescription = null, tint = KeColors.Ink2, modifier = Modifier.size(14.dp))
            Text("More comments didn’t load", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.5.sp), color = KeColors.Ink2)
        }
        PillButton(R.drawable.ic_retry, "Try again", focusKey = RETRY_KEY, onClick = onRetry, style = TailButtonStyle)
    }
}

/** A statement in the panel's place of the list (`.cstate`). */
internal class StatementCopy(val kicker: Kicker?, val title: String, val body: String)

private val OffCopy = StatementCopy(
    kicker = null,
    title = "Comments are off for this video",
    body = "YouTube turns comments off for videos made for kids, and doesn’t show them on some others.",
)

private val RestrictedOffCopy = StatementCopy(
    kicker = null,
    title = "Comments are off in Restricted Mode",
    body = "The owner can turn Restricted Mode off in Settings › Filters.",
)

private val NoneCopy = StatementCopy(kicker = null, title = "No comments yet", body = "Nobody has commented on this video so far.")

/** Page 1 failed: worded as the other Ayn surfaces word it (D8); the video plays on. */
private fun failedCopy(failure: AccessFailure): StatementCopy = StatementCopy(
    kicker = failureKicker(failure),
    title = "Comments didn’t load",
    body = if (failure == AccessFailure.OFFLINE) "Check that the Shield is online, then try again. The video plays on." else UnreadableReply,
)

@Composable
internal fun PanelStatement(copy: StatementCopy, onRetry: (() -> Unit)? = null, retryFocus: FocusRequester? = null) {
    Column(Modifier.padding(top = 28.dp).rising(rememberRise())) {
        copy.kicker?.let { kicker ->
            Row(
                modifier = Modifier.padding(bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(painterResource(kicker.icon), contentDescription = null, tint = KeColors.Ink2, modifier = Modifier.size(15.dp))
                Text(kicker.text, style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.5.sp), color = KeColors.Ink2)
            }
        }
        Text(copy.title, style = MaterialTheme.typography.headlineMedium.copy(fontSize = 26.sp, lineHeight = 0.98.em))
        Text(
            text = copy.body,
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 13.5.sp, lineHeight = 1.4.em),
            color = KeColors.Ink2,
            modifier = Modifier.padding(top = 10.dp),
        )
        if (onRetry != null) {
            Box(Modifier.padding(top = 18.dp)) {
                PillButton(
                    icon = R.drawable.ic_retry,
                    label = "Try again",
                    focusKey = RETRY_KEY,
                    onClick = onRetry,
                    modifier = if (retryFocus != null) Modifier.focusRequester(retryFocus) else Modifier,
                )
            }
        }
    }
}
