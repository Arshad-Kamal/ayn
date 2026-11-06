package com.arshad.ayn.ui.tv.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.arshad.ayn.core.access.AccessFailure
import com.arshad.ayn.core.access.ChatBadge
import com.arshad.ayn.core.access.ChatKind
import com.arshad.ayn.core.access.ChatMessage
import com.arshad.ayn.core.access.ChatRun
import com.arshad.ayn.core.access.LiveChatSeed
import com.arshad.ayn.core.store.ChatPlacement
import com.arshad.ayn.feature.player.Arrivals
import com.arshad.ayn.feature.player.ChatLine
import com.arshad.ayn.feature.player.ChatPinned
import com.arshad.ayn.feature.player.ChatStatus
import com.arshad.ayn.feature.player.PlaybackDressing
import com.arshad.ayn.feature.player.PlayerChat
import com.arshad.ayn.feature.player.chatPicture
import com.arshad.ayn.feature.player.chatTailText
import com.arshad.ayn.feature.player.chatTitle
import com.arshad.ayn.feature.player.timeText
import com.arshad.ayn.feature.player.newChatLinesText
import com.arshad.ayn.ui.tv.R
import com.arshad.ayn.ui.tv.common.AvatarPicture
import com.arshad.ayn.ui.tv.common.Picture
import com.arshad.ayn.ui.tv.common.SkeletonLine
import com.arshad.ayn.ui.tv.common.UnreadableReply
import com.arshad.ayn.ui.tv.common.failureKicker
import com.arshad.ayn.ui.tv.common.hairlineBorder
import com.arshad.ayn.ui.tv.common.rememberRise
import com.arshad.ayn.ui.tv.common.rememberSheen
import com.arshad.ayn.ui.tv.common.rising
import com.arshad.ayn.ui.tv.common.skeleton
import com.arshad.ayn.ui.tv.common.topFade
import com.arshad.ayn.ui.tv.focus.ControlStyle
import com.arshad.ayn.ui.tv.focus.ControlSurface
import com.arshad.ayn.ui.tv.focus.FocusHeld
import com.arshad.ayn.ui.tv.focus.FocusMemory
import com.arshad.ayn.ui.tv.focus.LocalFocusMemory
import com.arshad.ayn.ui.tv.focus.NeutralSurface
import com.arshad.ayn.ui.tv.focus.TintedIcon
import com.arshad.ayn.ui.tv.focus.TintedText
import com.arshad.ayn.ui.tv.focus.focusMemory
import com.arshad.ayn.ui.tv.focus.rememberFadeTint
import com.arshad.ayn.ui.tv.settings.SheetHead
import com.arshad.ayn.ui.tv.theme.KeColors
import com.arshad.ayn.ui.tv.theme.KeLayout
import com.arshad.ayn.ui.tv.theme.KeMotion
import com.arshad.ayn.ui.tv.theme.atWeight
import com.arshad.ayn.ui.tv.theme.weightSpan

/** What the chat's panel, view over the picture and sheet ask of the player's logic (LIVE-D6,
 *  D7, D14, D21, D22, D26); [tick] is a replay's position tick. */
internal class ChatCalls(
    val open: (turn: Int, seed: LiveChatSeed?) -> Unit,
    val shown: (Boolean) -> Unit,
    val chooseView: (live: Boolean) -> Unit,
    val retry: () -> Unit,
    val choosePlacement: (ChatPlacement) -> Unit,
    val hide: () -> Unit,
    val show: () -> Unit,
    val scroll: () -> Unit,
    val endScroll: () -> Unit,
    val tick: (turn: Int, positionMs: Long, playing: Boolean) -> Unit,
)

/**
 * Opens [turn]'s chat once its page has resolved ([dressing]: its seed, or none) while it is
 * [open] (its panel, or its view over the picture), and tells it whether it shows: open with the
 * app started, the only time it polls (LIVE-D6). The stop is the lifecycle's own callback: recomposition pauses with the app, so a
 * started state read in composition would never see it. Leaving composition hides it too.
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

// `.lchat` (px ÷ 2): the head, the switch 11 dp under it, the pin 10 dp under that, the list 7 dp under.
private val SwitchTop = 11.dp
private val SwitchShape = CircleShape
private val SwitchWell = Color.White.copy(alpha = 0.07f)
private val SwitchOn = Color.White.copy(alpha = 0.16f)
private val SwitchHeight = 26.dp
private val SwitchStyle = ControlStyle(restFill = KeColors.Ink.copy(alpha = 0f), restContent = KeColors.Ink2, focusedScale = 1.06f, ring = false)

/** The pinned banner's and a paid card's corner. */
private val CardCorner = 9.dp
private val BannerShape = RoundedCornerShape(CardCorner)
private val BannerFill = Color.White.copy(alpha = 0.06f)
private val BannerRing = Color.White.copy(alpha = 0.08f)
private const val PINNED_MAX_LINES = 4

private val ListTop = 7.dp
private val ListTopFade = 60.dp
// The mockup's 20 dp foot is under the safe line: the newest line and the pill keep above it.
private val ListPadding = PaddingValues(start = 2.dp, top = 15.dp, bottom = KeLayout.SafeVertical)
private val PillBottom = KeLayout.SafeVertical

private val LineAvatar = 20.dp
private val LineCorner = 8.dp
private val LineShape = RoundedCornerShape(LineCorner)
private val LineFocusFill = Color.White.copy(alpha = 0.1f)
private val RemovedRing = Color.White.copy(alpha = 0.18f)
private const val RestingLineAlpha = 0.7f
private const val PaidFocusScale = 1.03f

/** A line arriving while the list follows rises this far into place (`msgin`). */
internal val ArrivalRise = 7.dp

// `.lmsg` author inks: a moderator blue, a member green, the channel on a yellow pill.
private val PanelInks = LineInks(author = KeColors.Ink3, moderator = Color(0xFF79B6F4), member = Color(0xFF67D283))
private val OwnerFill = Color(0xFFF2CD64)
private val OwnerInk = Color(0xFF1B150B)

// `.lpaid`: a Super Chat's header in its tier colour, its body lighter; a membership in greens.
private val PaidShape = RoundedCornerShape(CardCorner)
private val MembershipHead = Color(0xFF2D8949)
private val MembershipBody = Color(0xFF266739)
private val PaidDarkInk = Color(0xFF141207)
private const val PAID_BODY_LIGHTEN = 0.25f
private const val DARK_INK_ABOVE = 0.4f
internal val StickerSize = 36.dp

private val LineStyle = TextStyle(fontSize = 12.5.sp, lineHeight = 1.36.em)
private const val BADGE_ID = "badge"

private const val SKELETON_ROWS = 9

// `replay-seek`: fewer rows, the finding line under them.
private const val FINDING_ROWS = 6

// `.lmsg .at`: 20 px against the line's 25.
private val LineTimeSize = 10.sp

// `.lskel` line widths, per row.
private val SkeletonFirst = listOf(0.64f, 0.48f, 0.80f, 0.40f, 0.72f, 0.56f, 0.68f)
private val SkeletonSecond = listOf(0.40f, 0.30f, 0.52f)
private const val TAIL_KEY = "chat-tail"
private const val LINE_TYPE = "line"
private const val PAID_TYPE = "paid"

private val LoadingChat = PlayerChat()

/** What holds focus in the panel while no line does: the view switch, Try again, or View. */
private enum class Rest { Switch, Retry, View }

// `.lviewbtn` (px ÷ 2): 26 dp tall, 7 dp right of the switch; `.ltemp`: 20 dp, 9 dp under it.
private val ViewStyle = ControlStyle(restFill = Color.White.copy(alpha = 0.07f), restContent = KeColors.Ink2, focusedScale = 1.06f, ring = false)
private val ForNowFill = Color.White.copy(alpha = 0.06f)

/**
 * A live stream's chat beside the picture (`live-tv-player` chat-*, LIVE-D15), sliding in at the
 * right while [open]: "Live chat" under [kicker] (how many watch), or an ended stream's kept chat
 * ([replay], `replay`, `replay-seek`): "Chat replay" under the channel and when it streamed, each
 * line with its time, a jump's skeleton with "Finding the chat at …" under it. Then the Top chat /
 * Live chat switch once YouTube's first answer names both views (LIVE-D14), the channel's pinned line, and the lines, newest at
 * the foot; View beside the switch opens the Chat sheet ([onView], `chat-viewbtn`). Focus rests on
 * the switch (else Try again, else View) and the list follows the newest line; DOWN enters the
 * list at the newest, UP goes back through older lines while the list holds still and the pill
 * counts what arrives; OK, or DOWN on the newest line, returns to following. Opened by Scroll
 * chat ([scrolling], `chat-scroll-temp`), it says it's for now and opens on the newest line; back
 * from the sheet View opened ([fromSheet]), focus returns to View.
 * Statements stand in for the list when chat is off or didn't load. Closing it is the caller's BACK.
 */
@Composable
internal fun ChatPanel(
    open: Boolean,
    chat: PlayerChat?,
    kicker: String?,
    calls: ChatCalls,
    onView: () -> Unit,
    replay: Boolean = false,
    scrolling: Boolean = false,
    fromSheet: Boolean = false,
) {
    SlidingPanel(open) { Panel(open, chat ?: LoadingChat, kicker, replay, calls, onView, scrolling, fromSheet) }
}

@Composable
private fun Panel(
    active: Boolean,
    chat: PlayerChat,
    kicker: String?,
    replay: Boolean,
    calls: ChatCalls,
    onView: () -> Unit,
    scrolling: Boolean,
    fromSheet: Boolean,
) {
    val status = chat.status
    val listed = status.listed
    val switchShown = chat.views != null && (listed || status.loading)
    val rest = when {
        status is ChatStatus.Failed -> Rest.Retry
        switchShown -> Rest.Switch
        else -> Rest.View
    }
    val viewFocus = remember { FocusRequester() }
    val switchFocus = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }
    val panelHeld = remember { FocusHeld() }
    val list = remember { LazyListState() }
    val memory = remember { FocusMemory(mutableStateOf(null)) }
    val lines = chat.lines
    val newest = lines.lastOrNull()?.message?.id
    // The newest line when the reader went up the list; null while it follows the newest.
    var readFrom by remember { mutableStateOf<String?>(null) }
    var focusedLine by remember { mutableStateOf<String?>(null) }
    val reading = readFrom != null
    val currentActive by rememberUpdatedState(active)
    val currentRest by rememberUpdatedState(rest)
    val currentListed by rememberUpdatedState(listed)
    val currentNewest by rememberUpdatedState(newest)

    fun focusRest() {
        when (currentRest) {
            Rest.Switch -> switchFocus.requestFocus()
            Rest.Retry -> retryFocus.requestFocus()
            Rest.View -> viewFocus.requestFocus()
        }
    }

    // Scroll chat opens the panel on the newest line, and the sheet View opened gives focus back to
    // View; each once, as the panel opens.
    var enterAtNewest by remember { mutableStateOf(scrolling) }
    var backToView by remember { mutableStateOf(fromSheet) }
    // Gated on [active]: a panel fading out never takes focus back from what its close focused.
    LaunchedEffect(active, rest) {
        if (!active) return@LaunchedEffect
        withFrameNanos { } // focusability follows the recomposition
        // Read after the frame: a batch may have landed meanwhile.
        val newestNow = currentNewest
        val entered = when {
            backToView -> viewFocus.requestFocus()
            enterAtNewest && currentListed && newestNow != null -> memory.focus(newestNow)
            else -> false
        }
        enterAtNewest = false
        backToView = false
        if (!entered) focusRest()
    }
    // A statement replacing the list took the line that held focus with it.
    LaunchedEffect(listed) { if (!listed) readFrom = null }
    // The list let go of focus: back to following, and focus to its rest should the line it was on
    // have gone (the oldest leave past the cap).
    LaunchedEffect(reading) {
        if (reading || !currentActive) return@LaunchedEffect
        withFrameNanos { }
        if (currentActive && !panelHeld.now) focusRest()
    }
    // The tail row (reconnecting, ended) comes in under the newest line: it is followed too.
    val tail = status == ChatStatus.Reconnecting || status == ChatStatus.Ended
    LaunchedEffect(lines, tail, reading) {
        if (!reading && (lines.isNotEmpty() || tail)) list.requestScrollToItem(0)
    }
    Column(
        Modifier
            .fillMaxHeight()
            .width(PanelWidth)
            .panelGround()
            .onFocusChanged { panelHeld.now = it.hasFocus }
            .onPreviewKeyEvent { event ->
                val down = event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown && listed && newest != null
                when {
                    !down -> false
                    reading && focusedLine == newest -> {
                        focusRest()
                        true
                    }
                    !reading -> memory.focus(newest)
                    else -> false
                }
            }
            .padding(start = PanelInset, end = KeLayout.SafeHorizontal, top = KeLayout.SafeVertical),
    ) {
        SheetHead(kicker = kicker.orEmpty(), title = { Text(chatTitle(replay), style = MaterialTheme.typography.headlineSmall) })
        Row(Modifier.padding(top = SwitchTop), verticalAlignment = Alignment.CenterVertically) {
            AnimatedVisibility(switchShown, enter = fadeIn(KeMotion.base()), exit = fadeOut(KeMotion.fast())) {
                ChatSwitch(live = chat.liveView, onPick = calls.chooseView, focus = switchFocus, modifier = Modifier.padding(end = 7.dp))
            }
            ViewButton(onClick = onView, modifier = Modifier.focusRequester(viewFocus))
        }
        AnimatedVisibility(scrolling, enter = fadeIn(KeMotion.base()), exit = fadeOut(KeMotion.fast())) { ForNowLine() }
        // Held while it leaves, so an unpin fades the banner out instead of dropping it.
        val pin = remember { HeldPin() }
        chat.pinned?.let { pin.value = it }
        AnimatedVisibility(listed && chat.pinned != null, enter = fadeIn(KeMotion.base()), exit = fadeOut(KeMotion.fast())) {
            pin.value?.let { PinnedBanner(it) }
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(top = ListTop)) {
            when (status) {
                ChatStatus.Loading -> ChatSkeleton()
                // At the list's foot, where the moment's lines will show (`replay-seek`).
                is ChatStatus.Finding -> Column(Modifier.align(Alignment.BottomStart).padding(bottom = KeLayout.SafeVertical)) {
                    ChatSkeleton(FINDING_ROWS)
                    chatTailText(status)?.let { ChatTail(it, spinner = true) }
                }
                ChatStatus.Off -> PanelStatement(OffCopy)
                is ChatStatus.Failed -> PanelStatement(failedCopy(status.failure), onRetry = calls.retry, retryFocus = retryFocus)
                ChatStatus.Flowing, ChatStatus.Reconnecting, ChatStatus.Ended -> {
                    CompositionLocalProvider(LocalFocusMemory provides memory) {
                        ChatList(
                            lines = lines,
                            status = status,
                            list = list,
                            reading = reading,
                            onListFocus = { held ->
                                readFrom = if (held) readFrom ?: newest else null
                            },
                            onLineFocused = { focusedLine = it },
                            onFollow = ::focusRest,
                        )
                    }
                    val unseen = readFrom?.let { from -> lines.size - 1 - lines.indexOfLast { it.message.id == from } } ?: 0
                    NewLinesPill(count = unseen, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = PillBottom))
                }
            }
        }
    }
}

/** View (`.lviewbtn`): opens the Chat sheet, where chat can move over the picture. */
@Composable
private fun ViewButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    ControlSurface(onClick = onClick, style = ViewStyle, shape = SwitchShape, modifier = modifier.height(SwitchHeight)) {
        Row(
            Modifier.align(Alignment.Center).padding(start = 8.dp, end = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            TintedIcon(painterResource(R.drawable.ic_scale), contentDescription = null, modifier = Modifier.size(13.dp))
            TintedText("View", style = MaterialTheme.typography.labelLarge.atWeight(600).copy(fontSize = 12.sp))
        }
    }
}

/** Scroll chat's panel is for now (`.ltemp`): BACK puts chat back over the picture. */
@Composable
private fun ForNowLine() {
    Row(
        Modifier
            .padding(top = 9.dp)
            .height(20.dp)
            .background(ForNowFill, CircleShape)
            .padding(start = 6.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(painterResource(R.drawable.ic_back), contentDescription = null, tint = KeColors.Ink3, modifier = Modifier.size(11.dp))
        Text("For now · BACK puts chat back over the picture", style = MaterialTheme.typography.labelMedium.atWeight(560).copy(fontSize = 10.5.sp), color = KeColors.Ink2)
    }
}

/** Top chat / Live chat (`.lseg`): the view showing marked, OK picks the other (it reloads, so
 *  focus alone doesn't pick). Focus lands on the view showing ([focus]). */
@Composable
internal fun ChatSwitch(live: Boolean, onPick: (Boolean) -> Unit, modifier: Modifier = Modifier, focus: FocusRequester? = null) {
    Row(
        modifier
            .background(SwitchWell, SwitchShape)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        SwitchOption("Top chat", on = !live, onClick = { onPick(false) }, modifier = if (!live && focus != null) Modifier.focusRequester(focus) else Modifier)
        SwitchOption("Live chat", on = live, onClick = { onPick(true) }, modifier = if (live && focus != null) Modifier.focusRequester(focus) else Modifier)
    }
}

@Composable
private fun SwitchOption(label: String, on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shownView = animateFloatAsState(if (on) 1f else 0f, KeMotion.fast(), label = "chatView")
    val restInk = rememberFadeTint(KeColors.Ink2, KeColors.Ink, shownView)
    ControlSurface(
        onClick = onClick,
        style = SwitchStyle,
        restContent = restInk,
        modifier = modifier
            .height(SwitchHeight)
            .semantics { selected = on }
            .drawBehind { drawRoundRect(SwitchOn, cornerRadius = CornerRadius(size.height / 2), alpha = shownView.value) },
    ) {
        TintedText(
            text = label,
            style = MaterialTheme.typography.labelLarge.atWeight(600).copy(fontSize = 12.sp),
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 12.dp),
        )
    }
}

/** The channel's pinned line (`.lbanner`): YouTube's "Pinned by …", then its author and words. */
@Composable
private fun PinnedBanner(pinned: ChatPinned) {
    val message = pinned.message
    Column(
        Modifier
            .padding(top = 10.dp)
            .fillMaxWidth()
            .background(BannerFill, BannerShape)
            .hairlineBorder(BannerRing, CardCorner)
            .padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 9.dp),
    ) {
        Row(
            modifier = Modifier.padding(bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(painterResource(R.drawable.ic_pin), contentDescription = null, tint = KeColors.Ink3, modifier = Modifier.size(10.5.dp))
            Text(pinned.title, style = MaterialTheme.typography.labelMedium.atWeight(600).copy(fontSize = 10.sp), color = KeColors.Ink3, maxLines = 1)
        }
        Row {
            Box(Modifier.size(LineAvatar)) { AvatarPicture(message.author.orEmpty(), chatPicture(message.authorPhotoUrl), 8.sp) }
            val (text, inline) = remember(message) { runsText(message.runs) }
            BasicText(
                text = text,
                style = LineStyle.copy(fontSize = 11.5.sp),
                color = { KeColors.Ink2 },
                maxLines = PINNED_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
                inlineContent = inline,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/**
 * The lines, newest at the foot (a reversed list keyed by line id, CMP-LAZY-01), with the
 * reconnecting or ended row under them. Lines after the list first showed rise in; while the
 * list holds focus ([reading]) the others rest dimmer.
 */
@Composable
private fun ChatList(
    lines: List<ChatLine>,
    status: ChatStatus,
    list: LazyListState,
    reading: Boolean,
    onListFocus: (Boolean) -> Unit,
    onLineFocused: (String) -> Unit,
    onFollow: () -> Unit,
) {
    val arrivals = remember { Arrivals() }
    val fresh = remember(lines) { arrivals.next(lines) }
    LazyColumn(
        state = list,
        reverseLayout = true,
        modifier = Modifier
            .fillMaxSize()
            .topFade(ListTopFade)
            .onFocusChanged { onListFocus(it.hasFocus) },
        contentPadding = ListPadding,
    ) {
        when (status) {
            ChatStatus.Reconnecting, ChatStatus.Ended -> chatTailText(status)?.let { words ->
                item(key = TAIL_KEY) { ChatTail(words, spinner = status == ChatStatus.Reconnecting) }
            }
            ChatStatus.Loading, is ChatStatus.Finding, ChatStatus.Flowing, is ChatStatus.Failed, ChatStatus.Off -> Unit
        }
        items(lines.asReversed(), key = { it.message.id }, contentType = { if (it.paidCard) PAID_TYPE else LINE_TYPE }) { line ->
            ChatEntry(
                line = line,
                fresh = line.message.id in fresh,
                listFocused = reading,
                onFocused = { onLineFocused(line.message.id) },
                onClick = onFollow,
                modifier = Modifier.focusMemory(line.message.id),
            )
        }
    }
}

/** One line: focusable so the reader can go back through them, filled while focused; OK on it
 *  ([onClick]) returns to following. */
@Composable
private fun ChatEntry(
    line: ChatLine,
    fresh: Boolean,
    listFocused: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val focus = animateFloatAsState(if (focused) 1f else 0f, KeMotion.fast(), label = "chatLine")
    val rest = animateFloatAsState(if (listFocused && !focused) RestingLineAlpha else 1f, KeMotion.fast(), label = "chatLineRest")
    val arrival = remember { Animatable(if (fresh) 0f else 1f) }
    LaunchedEffect(Unit) { if (arrival.value < 1f) arrival.animateTo(1f, tween(KeMotion.MOVE_MS, easing = KeMotion.EaseOut)) }
    val paid = line.paidCard
    NeutralSurface(
        onClick = onClick,
        shape = if (paid) PaidShape else LineShape,
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = rest.value * arrival.value
                translationY = ArrivalRise.toPx() * (1f - arrival.value)
                // Outside the surface's clip, so a focused card's ring isn't cut.
                if (paid) {
                    val scale = 1f + (PaidFocusScale - 1f) * focus.value
                    scaleX = scale
                    scaleY = scale
                }
            }
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            },
    ) {
        if (paid) PaidCard(line.message, focus) else TextLine(line, focus)
    }
}

/** A text line (`.lmsg`): the author's photo, then name, badge and words; a removed line keeps its
 *  place, marked (`chat-removed`). */
@Composable
private fun TextLine(line: ChatLine, focus: State<Float>) {
    val message = line.message
    val tint = if (line.removed) ColorProducer { KeColors.Ink3 } else rememberFadeTint(KeColors.Ink2, KeColors.Ink, focus)
    Row(
        Modifier
            .fillMaxWidth()
            .drawBehind { drawRoundRect(LineFocusFill, cornerRadius = CornerRadius(LineCorner.toPx()), alpha = focus.value) }
            .padding(start = 8.dp, end = 8.dp, top = 4.5.dp, bottom = 5.dp),
    ) {
        Box(Modifier.padding(top = 0.5.dp).size(LineAvatar)) {
            when {
                line.removed -> Box(Modifier.fillMaxSize().hairlineBorder(RemovedRing, LineAvatar / 2))
                message.kind != ChatKind.SYSTEM -> AvatarPicture(message.author.orEmpty(), chatPicture(message.authorPhotoUrl), 8.sp)
            }
        }
        val (text, inline) = remember(line) { lineText(line, timed = true) }
        BasicText(
            text = text,
            style = MaterialTheme.typography.bodyLarge.merge(LineStyle),
            color = tint,
            inlineContent = inline,
            modifier = Modifier.padding(start = 8.dp).weight(1f),
        )
    }
}

/** How a line inks its author, by role; the words take the text's own colour. */
internal class LineInks(val author: Color, val moderator: Color, val member: Color)

/** The line's words with its author ahead of them (inked by role, [inks]) and the role's badge; a
 *  notice (no author) in the quieter ink with its detail, a removed line YouTube's place-holder;
 *  [timed], a replayed line's time after its words (`.lmsg .at`). */
internal fun lineText(line: ChatLine, inks: LineInks = PanelInks, timed: Boolean = false): Pair<AnnotatedString, Map<String, InlineTextContent>> {
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
            badgeOf(message.badges, inks)?.let { (icon, ink) ->
                append(' ')
                appendInlineContent(BADGE_ID)
                inline[BADGE_ID] = InlineTextContent(Placeholder(1.1.em, 1.em, PlaceholderVerticalAlign.TextCenter)) {
                    Icon(painterResource(icon), contentDescription = null, tint = ink, modifier = Modifier.fillMaxSize())
                }
            }
            append("  ")
        }
        val (words, emoji) = runsText(message.runs, if (message.kind == ChatKind.SYSTEM) KeColors.Ink3 else null)
        append(words)
        inline += emoji
        // A gift's "sent Star", a notice's detail.
        message.headline?.let { headline ->
            if (words.isNotEmpty()) append(if (message.kind == ChatKind.SYSTEM) "\n" else " ")
            withStyle(SpanStyle(color = KeColors.Ink3)) { append(headline) }
        }
        if (timed) appendTime(line.atMs)
    }
    return text to inline
}

/** A replayed line's time after its words (`.lmsg .at`); nothing for a live line. */
private fun AnnotatedString.Builder.appendTime(atMs: Long?) {
    if (atMs == null) return
    append("  ")
    withStyle(SpanStyle(color = KeColors.Ink3, fontSize = LineTimeSize, fontFeatureSettings = "tnum")) { append(timeText(atMs)) }
}

/** A moderator in blue, a member in green; the rest (a verified channel too) in the author ink. */
private fun authorInk(badges: Set<ChatBadge>, inks: LineInks): Color = when {
    ChatBadge.MODERATOR in badges -> inks.moderator
    ChatBadge.MEMBER in badges -> inks.member
    else -> inks.author
}

/** The wrench, the tick or the star after the name, in its role's ink. */
private fun badgeOf(badges: Set<ChatBadge>, inks: LineInks): Pair<Int, Color>? = when {
    ChatBadge.MODERATOR in badges -> R.drawable.ic_wrench to inks.moderator
    ChatBadge.VERIFIED in badges -> R.drawable.ic_verified to KeColors.Ink2
    ChatBadge.MEMBER in badges -> R.drawable.ic_star to inks.member
    else -> null
}

/** Text runs as written, a channel's own emoji as its picture (https only) in the line's flow,
 *  its shortcut where it has none to show; [ink] colours the words when given. */
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
 *  end, and the words below on a lighter ground. Focus rings it ([ChatEntry] lifts it). */
@Composable
private fun PaidCard(message: ChatMessage, focus: State<Float>) {
    val membership = message.kind == ChatKind.MEMBERSHIP
    val head = message.paidColor()
    val body = if (membership) MembershipBody else lerp(head, Color.White, PAID_BODY_LIGHTEN)
    val headInk = inkOn(head)
    val bodyInk = inkOn(body)
    val second = if (membership) message.headline else message.amount
    Column(
        Modifier
            .padding(vertical = 4.dp)
            .fillMaxWidth()
            .clip(PaidShape)
            .background(body)
            .drawWithContent {
                drawContent()
                val ring = 1.5.dp.toPx()
                drawRoundRect(
                    KeColors.Ink,
                    topLeft = Offset(ring / 2, ring / 2),
                    size = size.copy(width = size.width - ring, height = size.height - ring),
                    cornerRadius = CornerRadius(CardCorner.toPx() - ring / 2),
                    style = Stroke(ring),
                    alpha = focus.value,
                )
            },
    ) {
        Row(
            Modifier.fillMaxWidth().background(head).padding(horizontal = 9.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(LineAvatar)) { AvatarPicture(message.author.orEmpty(), chatPicture(message.authorPhotoUrl), 8.sp) }
            Column(Modifier.padding(start = 8.dp).weight(1f)) {
                message.author?.let {
                    Text(it, style = MaterialTheme.typography.labelLarge.atWeight(640).copy(fontSize = 11.5.sp), color = headInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                second?.let {
                    Text(it, style = MaterialTheme.typography.labelLarge.atWeight(720).copy(fontSize = 12.5.sp, fontFeatureSettings = "tnum"), color = headInk)
                }
            }
            chatPicture(message.stickerUrl)?.let { url -> Box(Modifier.padding(start = 8.dp).size(StickerSize)) { Picture(url) } }
        }
        if (message.runs.isNotEmpty()) {
            val (text, inline) = remember(message) { runsText(message.runs) }
            BasicText(
                text = text,
                style = MaterialTheme.typography.bodyLarge.merge(LineStyle),
                color = { bodyInk },
                inlineContent = inline,
                modifier = Modifier.padding(start = 9.dp, end = 9.dp, top = 6.dp, bottom = 8.dp),
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
 *  (`.ltail`); a replay's jump finding the chat, under its skeleton (`replay-seek`). */
@Composable
private fun ChatTail(text: String, spinner: Boolean) {
    Row(
        modifier = Modifier.rising(rememberRise()).padding(start = 8.dp, top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (spinner) Spinner(18.dp, 2.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp), color = KeColors.Ink3)
    }
}

/** How many lines arrived since the reader went up the list (`.lmore`); OK goes back to them. */
@Composable
private fun NewLinesPill(count: Int, modifier: Modifier = Modifier) {
    // Held while it leaves, so the count doesn't drop to zero on its way out.
    val held = remember { HeldCount() }
    if (count > 0) held.value = count
    AnimatedVisibility(
        visible = count > 0,
        modifier = modifier,
        enter = fadeIn(tween(KeMotion.MOVE_MS, easing = KeMotion.EaseOut)) + slideInVertically(tween(KeMotion.MOVE_MS, easing = KeMotion.EaseOut)) { it / 2 },
        exit = fadeOut(KeMotion.fast()),
    ) {
        Row(
            Modifier
                .dropShadow(CircleShape) {
                    radius = 20.dp.toPx()
                    offset = Offset(0f, 9.dp.toPx())
                    spread = (-6).dp.toPx()
                    color = Color.Black
                    alpha = 0.8f
                }
                .height(28.dp)
                .background(KeColors.Ink, CircleShape)
                .padding(start = 10.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            TintedIcon(
                painterResource(R.drawable.ic_chevron),
                contentDescription = null,
                tint = { KeColors.OnInk },
                modifier = Modifier.size(12.dp).graphicsLayer { rotationZ = 90f },
            )
            Text(newChatLinesText(held.value), style = MaterialTheme.typography.labelLarge.atWeight(620).copy(fontSize = 11.5.sp), color = KeColors.OnInk)
        }
    }
}

/** The pill's last count: a cache written by [NewLinesPill]'s composition, not state. */
private class HeldCount {
    var value: Int = 0
}

/** The last pin shown: a cache written by the panel's composition, not state. */
private class HeldPin {
    var value: ChatPinned? = null
}

/** The first lines on their way (`.lskel`), one slow sheen. */
@Composable
private fun ChatSkeleton(rows: Int = SKELETON_ROWS) {
    val sheen = rememberSheen()
    Column(Modifier.padding(start = 10.dp, top = 8.dp)) {
        repeat(rows) { row ->
            Row(Modifier.padding(vertical = 6.dp)) {
                Box(Modifier.size(LineAvatar).skeleton(sheen, CircleShape))
                Column(Modifier.padding(start = 8.dp).weight(1f)) {
                    SkeletonLine(SkeletonFirst[row % SkeletonFirst.size], top = 5.dp, sheen = sheen, height = 10.dp)
                    if (row % 3 == 1) SkeletonLine(SkeletonSecond[row % SkeletonSecond.size], top = 5.dp, sheen = sheen, height = 10.dp)
                }
            }
        }
    }
}

private val OffCopy = StatementCopy(
    kicker = null,
    title = "Chat is off for this stream",
    body = "The channel turned chat off, or keeps it for its members. The stream plays on.",
)

/** The chat didn't load: worded as the comments' statement is (D8); the stream plays on. */
private fun failedCopy(failure: AccessFailure): StatementCopy = StatementCopy(
    kicker = failureKicker(failure),
    title = "Chat didn’t load",
    body = if (failure == AccessFailure.OFFLINE) "Check that the Shield is online, then try again. The stream plays on." else UnreadableReply,
)
