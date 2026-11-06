package com.arshad.ayn.ui.mobile.player

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.arshad.ayn.core.access.Chapter
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.feature.format.MetaSeparator
import com.arshad.ayn.feature.format.barFraction
import com.arshad.ayn.feature.format.compactCount
import com.arshad.ayn.feature.format.liveWatchingText
import com.arshad.ayn.feature.format.viewsText
import com.arshad.ayn.feature.library.WatchMark
import com.arshad.ayn.feature.player.DetailsVotes
import com.arshad.ayn.feature.player.PlaybackDressing
import com.arshad.ayn.feature.player.PlaybackView
import com.arshad.ayn.feature.player.PlayerUiState
import com.arshad.ayn.feature.player.PlayerViewModel
import com.arshad.ayn.feature.player.VoteRow
import com.arshad.ayn.feature.player.chatReplayKicker
import com.arshad.ayn.feature.player.formatBadgeText
import com.arshad.ayn.feature.player.percentText
import com.arshad.ayn.feature.player.timeText
import com.arshad.ayn.feature.player.uploadedText
import com.arshad.ayn.feature.player.voteRow
import com.arshad.ayn.ui.mobile.R
import com.arshad.ayn.ui.mobile.common.rememberCardFace
import com.arshad.ayn.ui.mobile.search.ResultRow
import com.arshad.ayn.ui.mobile.theme.AynColors
import com.arshad.ayn.ui.mobile.theme.AynMotion
import com.arshad.ayn.ui.mobile.theme.atWeight
import com.arshad.ayn.ui.mobile.theme.weightSpan
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What stands over the info under the picture, or beside the sideways picture: a panel or, beside
 *  it only, a sheet's stage. */
internal sealed interface Pane

/** What opens under the picture (`m6-player` 9–10, 21–25; Q29; a live stream's Chat,
 *  `live-phone-player` 4), the picture playing on above it; sideways beside it (31–33, 14), where
 *  Up next is a panel too. */
internal enum class WatchPanel : Pane { Details, Chapters, Comments, Chat, UpNext }

private const val CLOSE_AT = 0.25f
private const val CLOSE_FLING = 1_200f
private val PanelShape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
internal val PanelSide = 18.dp
internal val PanelBottom = 40.dp

/** The width of what opens beside the sideways picture (`.lside`). */
internal val BesideWidth = 372.dp

/**
 * What shows over the info under the picture ([shown]: a panel): it rises from below as it opens
 * and sinks as it closes. Sideways ([beside]) it stands at the screen's end, a panel or a sheet's
 * stage, sliding in from the side. One replacing another cross-fades.
 */
@Composable
private fun BoxScope.WatchPanels(shown: Pane?, beside: Boolean, content: @Composable (Pane) -> Unit) {
    AnimatedContent(
        targetState = shown,
        modifier = if (beside) Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(BesideWidth) else Modifier.matchParentSize(),
        transitionSpec = {
            val move = tween<IntOffset>(AynMotion.MOVE_MS, easing = AynMotion.EaseOut)
            val leave = tween<IntOffset>(AynMotion.MOVE_MS, easing = AynMotion.Ease)
            when {
                initialState == null && beside -> slideInHorizontally(move) { it } togetherWith ExitTransition.None
                targetState == null && beside -> EnterTransition.None togetherWith slideOutHorizontally(leave) { it }
                initialState == null -> slideInVertically(move) { it } togetherWith ExitTransition.None
                targetState == null -> EnterTransition.None togetherWith slideOutVertically(leave) { it }
                else -> fadeIn(AynMotion.base()) togetherWith fadeOut(AynMotion.base())
            }
        },
        label = "watchPanel",
    ) { open -> if (open != null) content(open) }
}

/** Up next's rows: the related videos, how far the profile got into each, and what a tap does. */
internal class UpNextCalls(
    val related: List<VideoItem>,
    val marks: State<Map<VideoId, WatchMark>>,
    val onPlay: (VideoItem) -> Unit,
    val onOpenMenu: ((VideoItem) -> Unit)?,
)

/**
 * The turn's open [panel] under the picture (only while the video shows): Back closes it, as ✕
 * ([onClose]) does; opening Details asks for the votes and opening Comments for page 1 (once per
 * turn, in the ViewModel, so a restored panel asks again only if it hadn't). Replies left open
 * close once the panel has gone, so Comments reopens on the list. Sideways ([beside]) it opens
 * beside the picture, where the [sheet] on top (its [sheetStage]) shows over any panel. Chat
 * shows the turn's chat, an ended stream's kept chat when [replay] (LIVE-D28); [chatCalls] are its
 * asks, its View opens the Chat sheet ([onChatView]).
 */
@Composable
internal fun BoxScope.TurnPanels(
    panel: WatchPanel?,
    beside: Boolean,
    sheet: PlayerSheet?,
    sheetStage: @Composable (PlayerSheet) -> Unit,
    upNext: UpNextCalls,
    viewModel: PlayerViewModel,
    state: PlayerUiState,
    dressing: PlaybackDressing?,
    view: PlaybackView,
    chapters: ChapterCalls,
    channel: CommentsChannel,
    commentsList: LazyListState,
    chatCalls: ChatCalls,
    replay: Boolean,
    onChatView: () -> Unit,
    backEnabled: Boolean,
    onClose: () -> Unit,
) {
    BackHandler(enabled = backEnabled && panel != null, onBack = onClose)
    LaunchedEffect(panel) {
        when (panel) {
            WatchPanel.Details -> viewModel.openDetails()
            WatchPanel.Comments -> viewModel.openComments()
            WatchPanel.Chapters, WatchPanel.Chat, WatchPanel.UpNext, null -> {
                delay(AynMotion.MOVE_MS.toLong())
                viewModel.closeReplies()
            }
        }
    }
    val commentsCalls = remember(viewModel) {
        CommentsCalls(viewModel::moreComments, viewModel::retryComments, viewModel::openReplies, viewModel::closeReplies)
    }
    WatchPanels(if (beside) sheet ?: panel else panel, beside) { shown ->
        when (shown) {
            is PlayerSheet -> sheetStage(shown)
            WatchPanel.Details -> DetailsPanel(
                video = state.video,
                likes = dressing?.likeCount,
                votes = state.votes,
                description = dressing?.description,
                format = formatBadgeText(view.videoSize.width, view.videoSize.height, view.frameRate),
                chapters = chapters,
                beside = beside,
                onClose = onClose,
            )
            WatchPanel.Chapters -> ChaptersPanel(chapters, beside, onClose = onClose)
            // Back in its replies only while it is the panel shown, not as it leaves.
            WatchPanel.Comments -> CommentsPanel(
                state.comments,
                channel,
                commentsList,
                commentsCalls,
                backEnabled && panel == WatchPanel.Comments && (!beside || sheet == null),
                beside,
                onClose,
            )
            WatchPanel.Chat -> ChatPanel(
                state.chat,
                if (replay) chatReplayKicker(state.video) else state.video.liveWatchingText(),
                chatCalls,
                beside,
                onView = onChatView,
                onClose = onClose,
                replay = replay,
            )
            WatchPanel.UpNext -> UpNextPanel(upNext, beside, onClose)
        }
    }
}

/** Up next beside the sideways picture (`m6-player` 32): the related videos; a tap plays one. */
@Composable
private fun UpNextPanel(calls: UpNextCalls, beside: Boolean, onClose: () -> Unit) {
    PanelFrame("Up next", onClose, beside, head = { PanelHead("Up next") }) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(top = 12.dp, bottom = PanelBottom)) {
            upNextRows(calls.related, calls, UpNextBesidePicture)
        }
    }
}

/** Up next's [rows] (the page's under the picture, the panel's beside it), a tap playing one. */
internal fun LazyListScope.upNextRows(rows: List<VideoItem>, calls: UpNextCalls, pictureWidth: Dp) {
    items(rows, key = { it.id.value }, contentType = { "row" }) { next ->
        ResultRow(
            video = next,
            progress = calls.marks.value[next.id].barFraction(),
            onClick = { calls.onPlay(next) },
            onOpenMenu = calls.onOpenMenu?.let { open -> { open(next) } },
            pictureWidth = pictureWidth,
            modifier = Modifier.padding(bottom = 12.dp).animateItem(fadeInSpec = AynMotion.base(), placementSpec = AynMotion.base(), fadeOutSpec = AynMotion.base()),
        )
    }
}

private val UpNextBesidePicture = 132.dp

/**
 * A panel's frame (`.psheet`), announced as [title]: the grab, [head] and what stands [under] it
 * (Chat's switch row) over a hairline, then [body]. A drag down on any of those follows the
 * finger; let go past a quarter, or flung, it
 * closes ([onClose]), else it springs back. ✕ closes it too. Its empty parts take touches, so the
 * info under it gets none. [beside] the sideways picture (`.lside`) it has no grab and a swipe
 * right anywhere on it closes it the same way.
 */
@Composable
internal fun PanelFrame(
    title: String,
    onClose: () -> Unit,
    beside: Boolean = false,
    head: @Composable RowScope.() -> Unit,
    under: @Composable ColumnScope.() -> Unit = {},
    body: @Composable ColumnScope.() -> Unit,
) {
    val drag = remember { Animatable(0f) }
    var reach by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val dragState = rememberDraggableState { delta -> scope.launch { drag.snapTo((drag.value + delta).coerceAtLeast(0f)) } }
    val swipe = Modifier.draggable(
        dragState,
        if (beside) Orientation.Horizontal else Orientation.Vertical,
        onDragStopped = { velocity ->
            // Queued behind the drag's pending snaps, so it decides on where the panel really is.
            scope.launch {
                if (drag.value > reach * CLOSE_AT || velocity > CLOSE_FLING) onClose() else drag.animateTo(0f, AynMotion.base())
            }
        },
    )
    val shape = if (beside) RectangleShape else PanelShape
    Column(
        Modifier
            .fillMaxSize()
            .onSizeChanged { reach = if (beside) it.width else it.height }
            .graphicsLayer { if (beside) translationX = drag.value else translationY = drag.value }
            .shadow(18.dp, shape)
            .clip(shape)
            .background(AynColors.Surface1)
            .then(if (beside) swipe.windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.End)) else Modifier)
            .semantics { paneTitle = title }
            .pointerInput(Unit) {},
    ) {
        Column(if (beside) Modifier.padding(top = 10.dp) else swipe) {
            if (!beside) Box(Modifier.padding(top = 8.dp, bottom = 4.dp).align(Alignment.CenterHorizontally).size(36.dp, 4.dp).background(AynColors.Ink3, RoundedCornerShape(2.dp)))
            Row(
                Modifier.fillMaxWidth().padding(start = PanelSide, end = 8.dp, top = 2.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                head()
                Spacer(Modifier.weight(1f))
                PanelIconButton(R.drawable.ic_close, "Close", onClose)
            }
            under()
        }
        HorizontalDivider(color = AynColors.Line)
        body()
    }
}

@Composable
internal fun PanelIconButton(@DrawableRes icon: Int, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(painterResource(icon), contentDescription = label, tint = AynColors.Ink2, modifier = Modifier.size(22.dp))
    }
}

/** A panel's name (`.phd`): [kicker] over [title], then [count] beside it. */
@Composable
internal fun PanelHead(title: String, kicker: String? = null, count: String? = null) {
    Column(Modifier.semantics(mergeDescendants = true) { heading() }) {
        kicker?.let { Text(it, style = MaterialTheme.typography.labelSmall.atWeight(620), color = AynColors.Ink3) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = PanelTitle, modifier = Modifier.alignByBaseline())
            count?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium.atWeight(560).copy(fontFeatureSettings = "tnum"),
                    color = AynColors.Ink3,
                    modifier = Modifier.alignByBaseline(),
                )
            }
        }
    }
}

private val PanelTitle @Composable get() = MaterialTheme.typography.headlineMedium.copy(fontSize = 22.sp, lineHeight = 1.05.em)

/** What a chapter row does and where playback is: the chapter playing ([playing], -1 for none). */
internal class ChapterCalls(val chapters: List<Chapter>, val playing: () -> Int, val onChapter: (Chapter) -> Unit)

/**
 * Video details (`m6-player` 9): the title (its DeArrow face, credited with YouTube's beside it),
 * views · the day · the picture's format ([format]), YouTube's likes with Return YouTube Dislike's
 * [votes] (asked as it opens), the whole description, then the chapters.
 */
@Composable
internal fun DetailsPanel(
    video: VideoItem,
    likes: Long?,
    votes: DetailsVotes?,
    description: String?,
    format: String?,
    chapters: ChapterCalls,
    beside: Boolean,
    onClose: () -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    PanelFrame("Video details", onClose, beside, head = { PanelHead("Video details") }) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(top = 12.dp, bottom = PanelBottom)) {
            item(key = "title") {
                val face = rememberCardFace(video)
                Column(Modifier.padding(horizontal = PanelSide)) {
                    Text(face?.title ?: video.title, style = MaterialTheme.typography.headlineMedium.copy(fontSize = 20.sp, lineHeight = 1.08.em))
                    if (face?.titleFromDeArrow == true) DeArrowCredit(video.title)
                    DetailsMeta(viewsText(video.viewCount), uploadedText(video, locale), format)
                }
            }
            voteRow(likes, votes ?: DetailsVotes.Loading)?.let { row ->
                // RYD's answer lands after the panel opens: the tiles cross-fade to it.
                item(key = "votes") { Crossfade(row, animationSpec = AynMotion.base(), label = "votes") { Votes(it, locale) } }
            }
            description?.takeIf { it.isNotBlank() }?.let { text ->
                item(key = "description") {
                    Text(
                        text.trim(),
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp, lineHeight = 1.5.em),
                        color = AynColors.Ink2,
                        modifier = Modifier.padding(start = PanelSide, end = PanelSide, top = 14.dp),
                    )
                }
            }
            if (chapters.chapters.isNotEmpty()) {
                item(key = "chaptersHeading") {
                    Text(
                        "Chapters",
                        style = MaterialTheme.typography.titleMedium.atWeight(680),
                        modifier = Modifier.padding(start = PanelSide, end = PanelSide, top = 18.dp, bottom = 6.dp).semantics { heading() },
                    )
                }
                chapterRows(chapters)
            }
        }
    }
}

/** `.dearrow`: the title shown is DeArrow's; YouTube's [original] beside the credit. */
@Composable
private fun DeArrowCredit(original: String) {
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Icon(painterResource(R.drawable.ic_info), contentDescription = null, tint = AynColors.Ink3, modifier = Modifier.padding(top = 1.dp).size(15.dp))
        Text(
            "Title from DeArrow${MetaSeparator}Original: \u201C$original\u201D",
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp, lineHeight = 1.38.em),
            color = AynColors.Ink3,
        )
    }
}

/** `.dmeta`: the views in ink, then the day and the format. */
@Composable
private fun DetailsMeta(views: String?, day: String?, format: String?) {
    val rest = listOfNotNull(day, format)
    if (views == null && rest.isEmpty()) return
    Text(
        buildAnnotatedString {
            views?.let { withStyle(weightSpan(650, AynColors.Ink)) { append(it) } }
            rest.forEachIndexed { index, part ->
                if (views != null || index > 0) withStyle(SpanStyle(color = AynColors.Ink3)) { append(MetaSeparator) }
                append(part)
            }
        },
        style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
        color = AynColors.Ink2,
        modifier = Modifier.padding(top = 10.dp),
    )
}

/** `.votes`: a tile per count and the share liked, the ratio under them and RYD's credit; no RYD
 *  data says so in a wider tile. */
@Composable
private fun Votes(row: VoteRow, locale: Locale) {
    Column(Modifier.padding(start = PanelSide, end = PanelSide, top = 14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.likes?.let { VoteTile(compactCount(it), "Likes") }
            row.dislikes?.let { VoteTile(compactCount(it), "Dislikes") }
            row.likedPercent?.let { VoteTile(percentText(it, locale), "liked") }
            if (row.noDislikeData) {
                Box(Modifier.weight(2f).heightIn(min = 58.dp).background(AynColors.Surface2, TileShape).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                    Text("No dislike data for this video", style = MaterialTheme.typography.bodySmall, color = AynColors.Ink2)
                }
            }
        }
        row.likedPercent?.let { percent ->
            Box(Modifier.padding(top = 10.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(RatioTrack)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth((percent / 100).toFloat()).background(AynColors.Ink))
            }
        }
        if (row.dislikes != null) {
            Text(
                "Dislikes are an estimate from Return YouTube Dislike.",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                color = AynColors.Ink3,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

private val TileShape = RoundedCornerShape(14.dp)
private val RatioTrack = Color.White.copy(alpha = 0.18f)

@Composable
private fun RowScope.VoteTile(value: String, label: String) {
    Column(Modifier.weight(1f).background(AynColors.Surface2, TileShape).padding(horizontal = 12.dp, vertical = 10.dp).semantics(mergeDescendants = true) {}) {
        Text(value, style = MaterialTheme.typography.headlineMedium.copy(fontSize = 19.sp, lineHeight = 1.15.em, fontFeatureSettings = "tnum"))
        Text(label, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = AynColors.Ink3)
    }
}

/**
 * Chapters (`m6-player` 10): the list alone, opened on the chapter playing, which is marked; a tap
 * plays from that chapter and closes the panel.
 */
@Composable
internal fun ChaptersPanel(chapters: ChapterCalls, beside: Boolean, onClose: () -> Unit) {
    val count = chapters.chapters.size
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (chapters.playing() - 2).coerceIn(0, (count - 1).coerceAtLeast(0)))
    PanelFrame("Chapters", onClose, beside, head = { PanelHead("Chapters", kicker = if (count == 1) "1 chapter" else "$count chapters") }) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(top = 10.dp, bottom = PanelBottom)) {
            chapterRows(chapters)
        }
    }
}

private fun LazyListScope.chapterRows(calls: ChapterCalls) {
    itemsIndexed(calls.chapters, key = { index, _ -> "chapter:$index" }, contentType = { _, _ -> "chapter" }) { index, chapter ->
        ChapterRow(chapter, playing = { calls.playing() == index }, onClick = { calls.onChapter(chapter) })
    }
}

private val ChapterShape = RoundedCornerShape(12.dp)

/** A chapter (`.chr`): its start and title; the one [playing] is lit, bolder, and says so. */
@Composable
private fun ChapterRow(chapter: Chapter, playing: () -> Boolean, onClick: () -> Unit) {
    val on = playing()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val lit by animateFloatAsState(if (on) 1f else 0f, AynMotion.base(), label = "chapterOn")
    val press by animateFloatAsState(if (pressed) 1f else 0f, AynMotion.press(), label = "chapterPress")
    val style = MaterialTheme.typography.bodyMedium.atWeight(if (on) 680 else 560)
    Row(
        Modifier
            .padding(horizontal = PanelSide - 10.dp)
            .fillMaxWidth()
            .heightIn(min = 46.dp)
            .drawBehind {
                val radius = CornerRadius(12.dp.toPx())
                if (lit > 0f) drawRoundRect(AynColors.RowLit, cornerRadius = radius, alpha = lit)
                if (press > 0f) drawRoundRect(AynColors.Pressed, cornerRadius = radius, alpha = press)
            }
            .clip(ChapterShape)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClickLabel = "Play from here", onClick = onClick)
            .then(if (on) Modifier.semantics { stateDescription = "Playing" } else Modifier)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            timeText(chapter.startSeconds * 1000),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.5.sp, fontFeatureSettings = "tnum").atWeight(650),
            color = if (on) AynColors.Ink else AynColors.Ink3,
            modifier = Modifier.width(44.dp),
        )
        Text(chapter.title, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        // Its state is read out once, as the row's state description.
        AnimatedVisibility(on, enter = fadeIn(AynMotion.base()), exit = fadeOut(AynMotion.base()), modifier = Modifier.clearAndSetSemantics { }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(painterResource(R.drawable.ic_play), contentDescription = null, tint = AynColors.Ink2, modifier = Modifier.size(12.dp))
                Text("Playing", style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp).atWeight(700), color = AynColors.Ink2)
            }
        }
    }
}
