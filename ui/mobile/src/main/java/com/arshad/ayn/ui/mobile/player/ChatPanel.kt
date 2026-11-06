package com.arshad.ayn.ui.mobile.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import com.arshad.ayn.core.access.AccessFailure
import com.arshad.ayn.core.access.ChatBadge
import com.arshad.ayn.core.access.ChatKind
import com.arshad.ayn.core.access.ChatMessage
import com.arshad.ayn.core.access.ChatRun
import com.arshad.ayn.core.access.LiveChatSeed
import com.arshad.ayn.feature.player.Arrivals
import com.arshad.ayn.feature.player.ChatLine
import com.arshad.ayn.feature.player.ChatPinned
import com.arshad.ayn.feature.player.ChatStatus
import com.arshad.ayn.feature.player.PlaybackDressing
import com.arshad.ayn.feature.player.PlayerChat
import com.arshad.ayn.feature.player.chatPicture
import com.arshad.ayn.feature.player.chatTailText
import com.arshad.ayn.feature.player.chatTitle
import com.arshad.ayn.feature.player.newChatLinesText
import com.arshad.ayn.feature.player.timeText
import com.arshad.ayn.ui.mobile.R
import com.arshad.ayn.ui.mobile.common.ChannelAvatar
import com.arshad.ayn.ui.mobile.common.PillButton
import com.arshad.ayn.ui.mobile.common.Picture
import com.arshad.ayn.ui.mobile.common.pressScale
import com.arshad.ayn.ui.mobile.home.SkeletonBar
import com.arshad.ayn.ui.mobile.home.Statement
import com.arshad.ayn.ui.mobile.home.UnreadableReply
import com.arshad.ayn.ui.mobile.home.rememberPulse
import com.arshad.ayn.ui.mobile.theme.AynColors
import com.arshad.ayn.ui.mobile.theme.AynMotion
import com.arshad.ayn.ui.mobile.theme.atWeight
import com.arshad.ayn.ui.mobile.theme.weightSpan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** What the chat panel asks of the player's logic (LIVE-D6, D7, D14); [tick] is a replay's
 *  position tick. */
internal class ChatCalls(
    val open: (turn: Int, seed: LiveChatSeed?) -> Unit,
    val shown: (Boolean) -> Unit,
    val chooseView: (live: Boolean) -> Unit,
    val retry: () -> Unit,
    val tick: (turn: Int, positionMs: Long, playing: Boolean) -> Unit,
)

/**
 * Opens [turn]'s chat once its page has resolved ([dressing]: its seed, or none) while it is
 * [open] on screen, and tells it whether it shows: open with the app started, the only time it
 * polls (LIVE-D6). The stop is the lifecycle's own callback: recomposition pauses with the app, so
 * a started state read in composition would never see it. Leaving composition hides it too.
 */
@Composable
internal fun ChatShownEffect(open: Boolean, turn: Int, dressing: PlaybackDressing?, calls: ChatCalls) {
    val resolved = dressing != null
    // Keyed on whether there is a seed, not on the seed: a refreshed page's new seed would
    // restart a showing chat from empty, and openChat keeps the chat it has anyway.
    val seeded = dressing?.liveChat != null
    val seed by rememberUpdatedState(dressing?.liveChat)
    LifecycleStartEffect(open, resolved, seeded, turn, calls) {
        if (open && resolved) calls.open(turn, seed)
        calls.shown(open)
        onStopOrDispose { calls.shown(false) }
    }
}

// `.lsegrow` / `.lseg`: the switch and View on their own row under the head, a hairline under it;
// `.lview` 38 dp, 8 dp after the switch.
private val SwitchShape = CircleShape
private val SwitchHeight = 30.dp
private val ViewHeight = 38.dp

// `.lbanner`
private val BannerShape = RoundedCornerShape(14.dp)
private const val PINNED_MAX_LINES = 4

// `.lbody`: 16 dp sides, the newest line 30 dp off the foot (14 beside the picture), a 60 dp fade at the top.
private val ListSide = 16.dp
private val ListFoot = 30.dp
private val BesideListFoot = 14.dp
private val ListTopFade = 60.dp
private val PillBottom = 36.dp

private val LineAvatar = 24.dp
private val LineGap = 10.dp
private val LineStyle = TextStyle(fontSize = 14.sp, lineHeight = 1.4.em)

/** A line arriving while the list follows rises this far into place (`msgin`). */
internal val ArrivalRise = 7.dp

// `.lmsg` author inks: a moderator blue, a member green, the channel on a yellow highlight (as the TV's).
private val ModeratorInk = Color(0xFF79B6F4)
private val MemberInk = Color(0xFF67D283)
private val OwnerFill = Color(0xFFF2CD64)
private val OwnerInk = Color(0xFF1B150B)
private val RemovedRing = Color.White.copy(alpha = 0.2f)

// `.lpaid`: a Super Chat's header in its tier colour, its body lighter; a membership in greens.
private val PaidShape = RoundedCornerShape(12.dp)
private val MembershipHead = Color(0xFF2D8949)
private val MembershipBody = Color(0xFF266739)
private val PaidDarkInk = Color(0xFF141207)
private const val PAID_BODY_LIGHTEN = 0.25f
private const val DARK_INK_ABOVE = 0.4f
internal val StickerSize = 40.dp

private const val BADGE_ID = "badge"
private const val TAIL_KEY = "chat-tail"
private const val LINE_TYPE = "line"
private const val PAID_TYPE = "paid"
private const val SKELETON_ROWS = 8

// A jump's skeleton: fewer rows, the finding line under them (`live-tv-player` replay-seek).
private const val FINDING_ROWS = 6

// `.lmsg .at`: 11.5 px against the line's 14.
private val LineTimeSize = 11.5.sp

// `.lsk` line widths, per row (live-phone-player 4).
private val SkeletonFirst = listOf(0.64f, 0.48f, 0.80f, 0.40f, 0.72f, 0.56f, 0.68f, 0.50f)

private val LoadingChat = PlayerChat()

/**
 * A live stream's chat (`live-phone-player` 4–9, 14; LIVE-D15), under the picture or, [beside],
 * beside the sideways picture: "Live chat" under [kicker] (how many watch), or an ended stream's
 * kept chat ([replay], 12, 16; LIVE-D28): "Chat replay" under the channel and when it streamed,
 * each line with its time, a jump's skeleton with "Finding the chat at …" under it. Then a row
 * with the Top chat / Live chat switch once YouTube's answer names both views (LIVE-D14) and View
 * (the Chat sheet, [onView]), the channel's pinned line, then the lines, newest at the foot,
 * following each arrival. A drag up reads back: the list holds still
 * and the pill counts what arrives; a tap on the pill, or a drag back to the foot, follows again.
 * Statements stand in for the list when chat is off or didn't load (Try again). ✕, a drag down
 * on the head (a swipe right beside) or Back closes it ([onClose]), as [PanelFrame]'s do.
 */
@Composable
internal fun ChatPanel(
    chat: PlayerChat?,
    kicker: String?,
    calls: ChatCalls,
    beside: Boolean,
    onView: () -> Unit,
    onClose: () -> Unit,
    replay: Boolean = false,
) {
    val shown = chat ?: LoadingChat
    val status = shown.status
    val switchShown = shown.switchShown
    val title = chatTitle(replay)
    PanelFrame(
        title = title,
        onClose = onClose,
        beside = beside,
        head = { PanelHead(title, kicker = kicker) },
        under = {
            Row(Modifier.padding(start = PanelSide, end = PanelSide, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                // View slides along as the switch comes and goes.
                AnimatedVisibility(
                    switchShown,
                    enter = fadeIn(AynMotion.base()) + expandHorizontally(AynMotion.base()),
                    exit = fadeOut(AynMotion.base()) + shrinkHorizontally(AynMotion.base()),
                ) {
                    ChatSwitch(shown.liveView, calls.chooseView, Modifier.padding(end = 8.dp))
                }
                ViewButton(onView)
            }
        },
    ) {
        // One body per kind of status, cross-fading as it changes; the list stays through flowing,
        // reconnecting and ended.
        Crossfade(
            targetState = if (status.listed) ChatStatus.Flowing else status,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            animationSpec = AynMotion.base(),
            label = "chatBody",
        ) { shownStatus ->
            Box(Modifier.fillMaxSize()) {
                when (shownStatus) {
                    ChatStatus.Loading -> ChatSkeleton(beside)
                    // At the list's foot, where the moment's lines will show.
                    is ChatStatus.Finding -> ChatSkeleton(beside, rows = FINDING_ROWS, tail = chatTailText(shownStatus))
                    ChatStatus.Off -> ChatStatement(OffStatement)
                    is ChatStatus.Failed -> ChatStatement(failedStatement(shownStatus.failure), onRetry = calls.retry)
                    // The live chat, not the status the fade began with.
                    ChatStatus.Flowing, ChatStatus.Reconnecting, ChatStatus.Ended -> ChatBody(shown, beside)
                }
            }
        }
    }
}

/** The Top chat / Live chat switch shows: once an answer names both views, while lines show or
 *  the first loads, a replay's jump's too (not over an off or failed chat's statement). */
internal val PlayerChat.switchShown: Boolean
    get() = views != null && (status.listed || status.loading)

/** Top chat / Live chat (`.lseg`): the view showing filled in ink; a tap on the other reloads it. */
@Composable
internal fun ChatSwitch(live: Boolean, onPick: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.background(AynColors.Surface2, SwitchShape).padding(4.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SwitchOption("Top chat", on = !live) { onPick(false) }
        SwitchOption("Live chat", on = live) { onPick(true) }
    }
}

@Composable
private fun SwitchOption(label: String, on: Boolean, onClick: () -> Unit) {
    val lit by animateFloatAsState(if (on) 1f else 0f, AynMotion.base(), label = "chatView")
    val interaction = remember { MutableInteractionSource() }
    // 30 dp as drawn: Compose widens a pointer target under 48 dp without growing the well.
    Box(
        Modifier
            .height(SwitchHeight)
            .pressScale(interaction)
            .drawBehind { drawRoundRect(AynColors.Ink, cornerRadius = CornerRadius(size.height / 2), alpha = lit) }
            .clip(SwitchShape)
            .selectable(selected = on, interactionSource = interaction, indication = null, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp).atWeight(620), color = lerp(AynColors.Ink2, AynColors.OnInk, lit))
    }
}

/** View (`.lview`): opens the Chat sheet, where chat's view is picked for this stream. */
@Composable
private fun ViewButton(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .height(ViewHeight)
            .pressScale(interaction)
            .clip(SwitchShape)
            .background(AynColors.Surface2)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(start = 10.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(painterResource(R.drawable.ic_chat_view), contentDescription = null, tint = AynColors.Ink2, modifier = Modifier.size(16.dp))
        Text("View", style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp).atWeight(620), color = AynColors.Ink2)
    }
}

/**
 * Reading back up a chat's reversed [list] (the newest at index 0): any move off the foot reads back
 * (a drag, a fling, a screen reader's scroll), except the list's own: an arrival's follow and the
 * pill's return are fenced off ([Follow]) until they land. While reading, the list holds and
 * [unseen] counts what arrives; back at the foot it follows again.
 */
internal class ChatReading(private val list: LazyListState) {
    /** The newest line when the reader went up the list; null while it follows the newest. */
    var readFrom by mutableStateOf<String?>(null)
        private set
    internal val follow = Follow()

    /** The newest line as last composed: a cache written by [composed], not state. */
    internal var newest: String? = null

    /** [lines] and a [tail] row composed: the newest noted and an arrival's follow fenced. */
    internal fun composed(lines: List<ChatLine>, tail: Boolean) {
        newest = lines.lastOrNull()?.message?.id
        follow.arrived(lines, tail, following = readFrom == null)
    }

    /** How many of [lines] arrived since the reader went up the list. */
    fun unseen(lines: List<ChatLine>): Int = readFrom?.let { from -> lines.size - 1 - lines.indexOfLast { it.message.id == from } } ?: 0

    /** The pill's tap: back to the newest, following again. */
    fun followNewest(scope: CoroutineScope) {
        readFrom = null
        follow.pending = true
        scope.launch {
            try {
                list.animateScrollToItem(0)
            } finally {
                dropFence()
            }
        }
    }

    /** The list left (or reached) its foot: reading back, unless it is the list's own move. */
    internal fun moved(foot: Boolean) {
        when {
            foot -> readFrom = null
            !follow.pending -> readFrom = readFrom ?: newest
        }
    }

    /** A move off the foot while fenced (a drag cutting the pill's return short) still reads back. */
    internal fun dropFence() {
        follow.pending = false
        if (!list.atFoot()) readFrom = readFrom ?: newest
    }
}

/** [ChatReading] over [list] showing [lines] with a [tail] row (reconnecting, ended) under them. */
@Composable
internal fun rememberChatReading(list: LazyListState, lines: List<ChatLine>, tail: Boolean): ChatReading {
    val reading = remember(list) { ChatReading(list) }
    // Applied with this composition, ahead of the layout that keeps the old lines in place (and so
    // off the foot).
    SideEffect { reading.composed(lines, tail) }
    LaunchedEffect(reading) { snapshotFlow { list.atFoot() }.collect(reading::moved) }
    // The tail row comes in under the newest line: it is followed too.
    LaunchedEffect(reading, lines, tail) {
        // An effect may start ahead of the SideEffect above (a first composition can): the fence is
        // raised here too, once per arrival either way.
        reading.composed(lines, tail)
        if (!reading.follow.pending) return@LaunchedEffect
        if (lines.isNotEmpty() || tail) list.requestScrollToItem(0)
        withFrameNanos { } // the request lands in this frame's layout
        reading.dropFence()
    }
    return reading
}

/** The pin over the lines (a reversed list keyed by line id) with the reconnecting or ended row
 *  under the newest, and the new-messages pill while reading back ([ChatReading]). */
@Composable
private fun ChatBody(chat: PlayerChat, beside: Boolean) {
    val lines = chat.lines
    val list = rememberLazyListState()
    val reading = rememberChatReading(list, lines, tail = chat.status.hasTail)
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        // Held while it leaves, so an unpin fades the banner out instead of dropping it.
        val pin = remember { Held<ChatPinned?>(null) }
        chat.pinned?.let { pin.value = it }
        AnimatedVisibility(chat.pinned != null, enter = fadeIn(AynMotion.base()), exit = fadeOut(AynMotion.base())) {
            pin.value?.let { PinnedBanner(it) }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            ChatList(lines, chat.status, list, beside)
            NewLinesPill(
                count = reading.unseen(lines),
                onClick = { reading.followNewest(scope) },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = PillBottom - PanelPill.touch), // its touch target reaches below the pill
            )
        }
    }
}

private fun LazyListState.atFoot(): Boolean = firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0

/** A reconnecting or ended row shows under the newest line. */
internal val ChatStatus.hasTail: Boolean get() = this == ChatStatus.Reconnecting || this == ChatStatus.Ended

/** The list is moving to its foot by itself (an arrival followed, the pill's return): leaving the
 *  foot meanwhile isn't reading back. Written by [ChatReading]'s effects and the pill's tap, never
 *  in composition; not state. */
internal class Follow {
    var pending = false
    private var lines: List<ChatLine>? = null
    private var tail = false

    /** New [lines] or a [tail] row while [following] fence the follow they bring. */
    fun arrived(lines: List<ChatLine>, tail: Boolean, following: Boolean) {
        if (following && (lines !== this.lines || tail != this.tail)) pending = true
        this.lines = lines
        this.tail = tail
    }
}

/** The last value shown, kept while its view fades out: a cache written in composition, not state. */
private class Held<T>(var value: T)

/** The channel's pinned line (`.lbanner`): YouTube's "Pinned by …", then its author and words. */
@Composable
private fun PinnedBanner(pinned: ChatPinned) {
    val message = pinned.message
    Column(
        Modifier
            .padding(start = 14.dp, end = 14.dp, top = 10.dp)
            .fillMaxWidth()
            .background(AynColors.Surface2, BannerShape)
            .padding(start = 12.dp, end = 12.dp, top = 9.dp, bottom = 10.dp)
            .semantics(mergeDescendants = true) {},
    ) {
        Row(Modifier.padding(bottom = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(painterResource(R.drawable.ic_pin), contentDescription = null, tint = AynColors.Ink3, modifier = Modifier.size(13.dp))
            Text(pinned.title, style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp).atWeight(620), color = AynColors.Ink3, maxLines = 1)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(LineGap)) {
            Avatar(message)
            val (text, inline) = remember(message) { runsText(message.runs) }
            Text(
                text,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp, lineHeight = 1.38.em),
                color = AynColors.Ink2,
                maxLines = PINNED_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
                inlineContent = inline,
            )
        }
    }
}

/** The lines, newest at the foot, with the tail row under them; lines after the list first showed
 *  rise in. */
@Composable
private fun ChatList(lines: List<ChatLine>, status: ChatStatus, list: LazyListState, beside: Boolean) {
    // Afresh after each load: a body fading out as a replay's jump loads may be the one its answer
    // returns to, and that answer arrives with the list, not into it.
    val arrivals = remember(status.loading) { Arrivals() }
    val fresh = remember(lines) { arrivals.next(lines) }
    LazyColumn(
        state = list,
        reverseLayout = true,
        modifier = Modifier.fillMaxSize().topFade(ListTopFade),
        contentPadding = PaddingValues(start = ListSide, end = ListSide, top = 12.dp, bottom = if (beside) BesideListFoot else ListFoot),
    ) {
        when (status) {
            ChatStatus.Reconnecting, ChatStatus.Ended -> chatTailText(status)?.let { words ->
                item(key = TAIL_KEY, contentType = TAIL_KEY) { ChatTail(words, spinner = status == ChatStatus.Reconnecting) }
            }
            ChatStatus.Loading, is ChatStatus.Finding, ChatStatus.Flowing, is ChatStatus.Failed, ChatStatus.Off -> Unit
        }
        items(lines.asReversed(), key = { it.message.id }, contentType = { if (it.paidCard) PAID_TYPE else LINE_TYPE }) { line ->
            ChatEntry(line, fresh = line.message.id in fresh)
        }
    }
}

/** The list's top fades out (`.lbody`'s mask) over [height]. */
private fun Modifier.topFade(height: Dp): Modifier = graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(Brush.verticalGradient(0f to Color.Transparent, height.toPx() / size.height to Color.Black), blendMode = BlendMode.DstIn)
    }

/** One line, rising in when it arrives after the list first showed; a paid card a moderator
 *  removes fades into its marked place. */
@Composable
private fun ChatEntry(line: ChatLine, fresh: Boolean) {
    val arrival = remember { Animatable(if (fresh) 0f else 1f) }
    LaunchedEffect(Unit) { if (arrival.value < 1f) arrival.animateTo(1f, tween(AynMotion.MOVE_MS, easing = AynMotion.EaseOut)) }
    Box(
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = arrival.value
                translationY = ArrivalRise.toPx() * (1f - arrival.value)
            }
            .semantics(mergeDescendants = true) {},
    ) {
        Crossfade(line.paidCard, animationSpec = AynMotion.base(), label = "chatRemoved") { paid ->
            if (paid) PaidCard(line.message) else TextLine(line)
        }
    }
}

@Composable
private fun Avatar(message: ChatMessage) {
    Box(Modifier.padding(top = 1.dp).size(LineAvatar)) { ChannelAvatar(message.author.orEmpty(), chatPicture(message.authorPhotoUrl), monogramSize = 11.5f) }
}

/** A text line (`.lmsg`): the author's photo, then name, badge and words; a removed line keeps its
 *  place, marked (`.lmsg.gone`); a replayed line has its time after its words. */
@Composable
private fun TextLine(line: ChatLine) {
    val message = line.message
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(LineGap)) {
        when {
            line.removed -> Box(Modifier.padding(top = 1.dp).size(LineAvatar).drawBehind { drawCircle(RemovedRing, style = Stroke(1.5.dp.toPx())) })
            message.kind == ChatKind.SYSTEM -> Box(Modifier.size(LineAvatar))
            else -> Avatar(message)
        }
        val (text, inline) = remember(line) { lineText(line, PanelInks, timed = true) }
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium.merge(LineStyle),
            color = if (line.removed) AynColors.Ink3 else AynColors.Ink,
            inlineContent = inline,
            modifier = Modifier.weight(1f),
        )
    }
}

/** How a line inks its author, by role (the channel's own on its highlight either way). */
internal class LineInks(val author: Color, val moderator: Color, val member: Color)

private val PanelInks = LineInks(author = AynColors.Ink3, moderator = ModeratorInk, member = MemberInk)

/** The line's words with its author ahead of them (inked by role, [inks]) and the role's badge; a
 *  notice (no author) in the quieter ink with its detail, a removed line YouTube's place-holder;
 *  [timed], a replayed line's time after its words (`.lmsg .at`). */
internal fun lineText(line: ChatLine, inks: LineInks, timed: Boolean = false): Pair<AnnotatedString, Map<String, InlineTextContent>> {
    val message = line.message
    if (line.removed) {
        val removed = buildAnnotatedString {
            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append("Message removed by a moderator") }
            if (timed) appendTime(line.atMs)
        }
        return removed to emptyMap()
    }
    val inline = mutableMapOf<String, InlineTextContent>()
    val text = buildAnnotatedString {
        message.author?.let { author ->
            when {
                ChatBadge.OWNER in message.badges -> withStyle(weightSpan(640, OwnerInk).copy(background = OwnerFill)) { append("\u2009$author\u2009") }
                else -> withStyle(weightSpan(640, authorInk(message.badges, inks))) { append(author) }
            }
            badgeOf(message.badges, inks)?.let { (icon, ink, role) ->
                append(' ')
                appendInlineContent(BADGE_ID, role)
                inline[BADGE_ID] = InlineTextContent(Placeholder(1.em, 1.em, PlaceholderVerticalAlign.TextCenter)) {
                    Icon(painterResource(icon), contentDescription = null, tint = ink, modifier = Modifier.fillMaxSize())
                }
            }
            append("  ")
        }
        val (words, emoji) = runsText(message.runs, if (message.kind == ChatKind.SYSTEM) AynColors.Ink3 else null)
        append(words)
        inline += emoji
        // A gift's "sent Star", a notice's detail.
        message.headline?.let { headline ->
            if (words.isNotEmpty()) append(if (message.kind == ChatKind.SYSTEM) "\n" else " ")
            withStyle(SpanStyle(color = AynColors.Ink3)) { append(headline) }
        }
        if (timed) appendTime(line.atMs)
    }
    return text to inline
}

/** A replayed line's time after its words (`.lmsg .at`); nothing for a live line. */
private fun AnnotatedString.Builder.appendTime(atMs: Long?) {
    if (atMs == null) return
    append("  ")
    withStyle(SpanStyle(color = AynColors.Ink3, fontSize = LineTimeSize, fontFeatureSettings = "tnum")) { append(timeText(atMs)) }
}

/** A moderator in blue, a member in green; the rest (a verified channel too) in the author ink. */
private fun authorInk(badges: Set<ChatBadge>, inks: LineInks): Color = when {
    ChatBadge.MODERATOR in badges -> inks.moderator
    ChatBadge.MEMBER in badges -> inks.member
    else -> inks.author
}

/** The wrench, the tick or the star after the name, in its role's ink, and the role a screen
 *  reader says in its place. */
private fun badgeOf(badges: Set<ChatBadge>, inks: LineInks): Triple<Int, Color, String>? = when {
    ChatBadge.MODERATOR in badges -> Triple(R.drawable.ic_wrench, inks.moderator, "Moderator")
    ChatBadge.VERIFIED in badges -> Triple(R.drawable.ic_verified, AynColors.Ink2, "Verified")
    ChatBadge.MEMBER in badges -> Triple(R.drawable.ic_star, inks.member, "Member")
    else -> null
}

/** Text runs as written, a channel's own emoji as its picture (https only) in the line's flow, its
 *  shortcut where it has none to show; [ink] colours the words when given. */
internal fun runsText(runs: List<ChatRun>, ink: Color? = null): Pair<AnnotatedString, Map<String, InlineTextContent>> {
    val inline = mutableMapOf<String, InlineTextContent>()
    val text = buildAnnotatedString {
        if (ink != null) pushStyle(SpanStyle(color = ink))
        runs.forEachIndexed { index, run ->
            when (run) {
                is ChatRun.Text -> append(run.text)
                is ChatRun.CustomEmoji -> {
                    val url = chatPicture(run.imageUrl)
                    if (url == null) {
                        append(run.label)
                    } else {
                        val id = "emoji$index"
                        appendInlineContent(id, run.label)
                        inline[id] = InlineTextContent(Placeholder(1.3.em, 1.3.em, PlaceholderVerticalAlign.TextCenter)) {
                            Box(Modifier.fillMaxSize()) { Picture(url) }
                        }
                    }
                }
            }
        }
        if (ink != null) pop()
    }
    return text to inline
}

/** A Super Chat, Super Sticker or membership line (`.lpaid`): a header in the tier's colour (a
 *  membership's green) with the author and the amount or YouTube's headline, the sticker at its
 *  end, and the words below on a lighter ground. */
@Composable
private fun PaidCard(message: ChatMessage) {
    val membership = message.kind == ChatKind.MEMBERSHIP
    val head = message.paidColor()
    val body = if (membership) MembershipBody else lerp(head, Color.White, PAID_BODY_LIGHTEN)
    val headInk = inkOn(head)
    val second = if (membership) message.headline else message.amount
    Column(Modifier.padding(vertical = 5.dp).fillMaxWidth().clip(PaidShape).background(body)) {
        Row(
            Modifier.fillMaxWidth().background(head).padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LineGap),
        ) {
            Avatar(message)
            Column(Modifier.weight(1f)) {
                message.author?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.5.sp).atWeight(640), color = headInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                second?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.5.sp, fontFeatureSettings = "tnum").atWeight(720), color = headInk)
                }
            }
            chatPicture(message.stickerUrl)?.let { url -> Box(Modifier.size(StickerSize)) { Picture(url) } }
        }
        if (message.runs.isNotEmpty()) {
            val (text, inline) = remember(message) { runsText(message.runs) }
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium.merge(LineStyle),
                color = inkOn(body),
                inlineContent = inline,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 7.dp, bottom = 9.dp),
            )
        }
    }
}

/** A paid line's colour: a Super Chat's tier, a membership's (or a tierless line's) green. */
internal fun ChatMessage.paidColor(): Color =
    if (kind == ChatKind.MEMBERSHIP) MembershipHead else tierColor?.let(::Color) ?: MembershipHead

/** Dark words on a light tier colour, light ones on a dark tier. */
internal fun inkOn(ground: Color): Color = if (ground.luminance() > DARK_INK_ABOVE) PaidDarkInk else Color.White

/** The connection dropped (it tries again by itself) or the chat ended, under the newest line
 *  (`.ltail`); a replay's jump finding the chat, under its skeleton. */
@Composable
private fun ChatTail(text: String, spinner: Boolean) {
    val shown = remember { Animatable(0f) }
    LaunchedEffect(Unit) { shown.animateTo(1f, AynMotion.base()) }
    Row(
        Modifier
            .padding(top = 8.dp)
            .graphicsLayer { alpha = shown.value }
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (spinner) Spinner(18.dp) else Box(Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp), color = AynColors.Ink3)
    }
}

/** The pill's measures: the panel's (`.lmore`) or the one over the picture (`.povm`); [touch] is how
 *  far its touch target reaches above and below it, to 48 dp. */
internal class PillSize(
    val height: Dp,
    val shadow: Dp,
    val start: Dp,
    val end: Dp,
    val gap: Dp,
    val icon: Dp,
    val text: TextUnit,
) {
    val touch: Dp get() = (48.dp - height) / 2
}

internal val PanelPill = PillSize(height = 36.dp, shadow = 14.dp, start = 11.dp, end = 14.dp, gap = 6.dp, icon = 16.dp, text = 13.sp)
internal val OverPill = PillSize(height = 30.dp, shadow = 10.dp, start = 9.dp, end = 12.dp, gap = 5.dp, icon = 14.dp, text = 12.sp)

/** How many lines arrived since the reader went up the list, in [size]; a tap goes back to them.
 *  Its touch target reaches [PillSize.touch] past it above and below (the list under it would
 *  otherwise take a near miss as a scroll). */
@Composable
internal fun NewLinesPill(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier, size: PillSize = PanelPill) {
    // Held while it leaves, so the count doesn't drop to zero on its way out.
    val held = remember { Held(0) }
    if (count > 0) held.value = count
    AnimatedVisibility(
        visible = count > 0,
        modifier = modifier,
        enter = fadeIn(tween(AynMotion.MOVE_MS, easing = AynMotion.EaseOut)) + slideInVertically(tween(AynMotion.MOVE_MS, easing = AynMotion.EaseOut)) { it / 2 },
        exit = fadeOut(AynMotion.base()),
    ) {
        val interaction = remember { MutableInteractionSource() }
        Box(
            Modifier
                .pressScale(interaction)
                .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClickLabel = "Go to the newest messages", onClick = onClick)
                .padding(vertical = size.touch),
        ) {
            Row(
                Modifier
                    .shadow(size.shadow, CircleShape)
                    .height(size.height)
                    .clip(CircleShape)
                    .background(AynColors.Ink)
                    .padding(start = size.start, end = size.end),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(size.gap),
            ) {
                Icon(painterResource(R.drawable.ic_chevron_down), contentDescription = null, tint = AynColors.OnInk, modifier = Modifier.size(size.icon))
                Text(newChatLinesText(held.value), style = MaterialTheme.typography.labelMedium.copy(fontSize = size.text).atWeight(650), color = AynColors.OnInk)
            }
        }
    }
}

/** The first lines on their way (`.lsk`), breathing, held at the foot where they will arrive, with
 *  [tail]'s words under them when given. */
@Composable
private fun ChatSkeleton(beside: Boolean, rows: Int = SKELETON_ROWS, tail: String? = null) {
    val pulse = rememberPulse()
    Column(
        Modifier.fillMaxSize().topFade(ListTopFade).padding(start = ListSide, end = ListSide, bottom = if (beside) BesideListFoot else ListFoot),
        verticalArrangement = Arrangement.Bottom,
    ) {
        repeat(rows) { row ->
            Row(Modifier.padding(vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(LineGap)) {
                SkeletonBar(pulse, Modifier.size(LineAvatar), CircleShape)
                Column(Modifier.weight(1f).padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SkeletonBar(pulse, Modifier.fillMaxWidth(SkeletonFirst[row % SkeletonFirst.size]).height(11.dp))
                    if (row % 3 == 1) SkeletonBar(pulse, Modifier.fillMaxWidth(0.4f).height(11.dp))
                }
            }
        }
        tail?.let { ChatTail(it, spinner = true) }
    }
}

/** A statement in the list's place (`.lchat .cstate`). */
private class ChatStatementCopy(val icon: Int, val kicker: String, val title: String, val body: String)

private val OffStatement = ChatStatementCopy(
    R.drawable.ic_chat_off,
    "Live chat",
    "Chat is off for this stream",
    "The channel turned chat off, or keeps it for its members. The stream plays on.",
)

/** The chat didn't load: worded as the comments' statement is (D8); the stream plays on. */
private fun failedStatement(failure: AccessFailure): ChatStatementCopy {
    val offline = failure == AccessFailure.OFFLINE
    return ChatStatementCopy(
        if (offline) R.drawable.ic_offline else R.drawable.ic_alert,
        if (offline) "No connection to YouTube" else "YouTube changed something",
        "Chat didn’t load",
        if (offline) "Check that the phone is online, then try again. The stream plays on." else UnreadableReply,
    )
}

@Composable
private fun ChatStatement(statement: ChatStatementCopy, onRetry: (() -> Unit)? = null) {
    Statement(
        icon = statement.icon,
        kicker = statement.kicker,
        title = statement.title,
        body = statement.body,
        compact = true,
        modifier = Modifier.padding(start = PanelSide, end = PanelSide, top = 22.dp).semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        onRetry?.let { PillButton("Try again", it, icon = R.drawable.ic_retry) }
    }
}
