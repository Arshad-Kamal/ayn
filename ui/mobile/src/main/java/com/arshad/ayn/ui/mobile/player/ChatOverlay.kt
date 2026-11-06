package com.arshad.ayn.ui.mobile.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.arshad.ayn.core.access.ChatKind
import com.arshad.ayn.core.access.ChatMessage
import com.arshad.ayn.core.store.ChatPlacement
import com.arshad.ayn.feature.player.Arrivals
import com.arshad.ayn.feature.player.ChatLine
import com.arshad.ayn.feature.player.PlayerChat
import com.arshad.ayn.feature.player.chatPicture
import com.arshad.ayn.feature.player.chatTailText
import com.arshad.ayn.feature.player.chatTitle
import com.arshad.ayn.feature.player.chatViewName
import com.arshad.ayn.feature.player.overPicture
import com.arshad.ayn.ui.mobile.R
import com.arshad.ayn.ui.mobile.common.ChannelAvatar
import com.arshad.ayn.ui.mobile.common.Picture
import com.arshad.ayn.ui.mobile.theme.AynColors
import com.arshad.ayn.ui.mobile.theme.AynMotion
import com.arshad.ayn.ui.mobile.theme.atWeight
import com.arshad.ayn.ui.mobile.theme.weightSpan

// `live-phone-player` `.povr`: the side column 290 dp wide (14 / 28 / 16 / 10 around its words), a
// corner box 270 × 170 dp, 34 dp in from the left, 28 from the right, 14 from the top, 16 from the foot.
private val SideWidth = 290.dp
private val SideTop = 14.dp
private val SideEnd = 28.dp
private val SideBottom = 16.dp
private val SideStart = 10.dp
private val CornerWidth = 270.dp
private val CornerHeight = 170.dp
private val CornerLeft = 34.dp
private val CornerRight = 28.dp
private val CornerTopGap = 14.dp
private val CornerFoot = 16.dp

/** Where a bottom corner's box ends, up from the picture's foot: the cues rise above it. */
internal val ChatCornerTop: Dp = CornerHeight + CornerFoot

/** The halo reaches past the words: the clipped list reaches this far past them on every side but
 *  the faded top (out of the frame's padding), the words keeping the mockup's place. */
private val HaloRoom = 8.dp

/** The list's room under the head (`.ol`'s max-height leaves 22 px for both). */
private val HeadGap = 6.dp
private val TopFade = 30.dp

/** While reading back the pill sits at the foot (`.povm`, 30 dp + 8 above it) under a 24 dp fade. */
private val PillRoom = 38.dp
private val FootFade = 24.dp

private val AvatarSize = 20.dp
private val AvatarGap = 7.dp
private val LineGap = 3.dp
private val PaidHeadGap = 3.dp
private val PillPadding = PaddingValues(start = 7.dp, end = 7.dp, bottom = 1.dp)

// `.povr` inks: a name at 86 %, a moderator and a member brighter than the panel's, so they hold over a picture.
private val NameInk = AynColors.Ink.copy(alpha = 0.86f)
private val ViewInk = AynColors.Ink.copy(alpha = 0.85f)
private val OverInks = LineInks(author = NameInk, moderator = Color(0xFF9DC3F2), member = Color(0xFF86E09A))
private val RemovedRing = Color.White.copy(alpha = 0.18f)

private const val TAIL_KEY = "chat-tail"
private const val LINE_TYPE = "line"
private const val PAID_TYPE = "paid"

/**
 * A live stream's chat over the sideways picture in [placement] (`live-phone-player` 17–18, 21–22;
 * LIVE-D21, D22, D27): along the right side or a corner box, the newest at the foot, with no
 * backdrop: the head and every line carry their own shadow so the picture stays in view. A vertical
 * drag scrolls it back ([ChatReading]: the list holds and the pill counts what arrives; a tap on it
 * or a drag back to the foot follows again); taps pass through to the picture. It steps aside while
 * [aside] (the chrome, anything beside the picture, a note in its corner) with a fade, taking no
 * touches meanwhile. While the first answer loads only its head shows; a chat that is off, or not
 * yet opened, shows nothing. An ended stream's kept chat ([replay], LIVE-D28) shows the same way,
 * its lines without times.
 */
@Composable
internal fun BoxScope.ChatOverlay(placement: ChatPlacement?, chat: PlayerChat?, aside: Boolean, replay: Boolean = false) {
    val at = overPicture(placement, chat)
    // Held while it fades out, so it leaves from where it was.
    val held = remember { HeldOverlay() }
    if (at != null) {
        held.placement = at
        held.chat = chat
    }
    val shown = animateFloatAsState(if (aside) 0f else 1f, AynMotion.base(), label = "chatAside")
    AnimatedVisibility(
        visible = at != null,
        modifier = Modifier.matchParentSize(),
        enter = fadeIn(AynMotion.base()),
        exit = fadeOut(AynMotion.base()),
    ) {
        val shownAt = held.placement ?: return@AnimatedVisibility
        val shownChat = held.chat ?: return@AnimatedVisibility
        // A new view fades in where the old one fades out, its list starting afresh.
        Crossfade(shownAt, animationSpec = AynMotion.base(), label = "chatView") { view ->
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))) {
                Overlay(view, shownChat, shown, aside, replay)
            }
        }
    }
}

/** The last overlay shown: a cache written by [ChatOverlay]'s composition, not state. */
private class HeldOverlay {
    var placement: ChatPlacement? = null
    var chat: PlayerChat? = null
}

@Composable
private fun BoxScope.Overlay(placement: ChatPlacement, chat: PlayerChat, shown: State<Float>, aside: Boolean, replay: Boolean) {
    val frame = if (placement == ChatPlacement.Side) {
        Modifier
            .align(Alignment.TopEnd)
            .fillMaxHeight()
            .width(SideWidth)
            .padding(start = SideStart - HaloRoom, end = SideEnd - HaloRoom, top = SideTop, bottom = SideBottom - HaloRoom)
    } else {
        val left = placement == ChatPlacement.TopLeft || placement == ChatPlacement.BottomLeft
        val top = placement == ChatPlacement.TopLeft || placement == ChatPlacement.TopRight
        Modifier
            .align(placement.corner())
            .padding(
                start = if (left) CornerLeft - HaloRoom else 0.dp,
                end = if (left) 0.dp else CornerRight - HaloRoom,
                top = if (top) CornerTopGap else 0.dp,
                bottom = if (top) 0.dp else CornerFoot - HaloRoom,
            )
            .size(CornerWidth + HaloRoom * 2, CornerHeight + HaloRoom)
    }
    val style = MaterialTheme.typography.bodyMedium.atWeight(580).copy(fontSize = 12.5.sp, lineHeight = 1.35.em, color = AynColors.Ink)
    val list = rememberLazyListState()
    val loading = chat.status.loading
    val lines = if (loading) emptyList() else chat.lines
    val tail = chatTailText(chat.status)
    val reading = rememberChatReading(list, lines, tail = tail != null)
    val unseen = reading.unseen(lines)
    val readingShown = animateFloatAsState(if (reading.readFrom != null) 1f else 0f, AynMotion.base(), label = "chatReading")
    val scope = rememberCoroutineScope()
    // The stepping aside on the chat's own frame: its offscreen layer no bigger than the chat.
    Column(
        frame
            .graphicsLayer { alpha = shown.value }
            .then(if (aside) Modifier.clearAndSetSemantics { } else Modifier),
    ) {
        OverlayHead(chat.liveView, replay, Modifier.padding(horizontal = HaloRoom))
        Box(Modifier.weight(1f).fillMaxWidth().padding(top = HeadGap)) {
            // Afresh after each load (a view switched, a retry, a replay's jump): the first answer
            // arrives with the overlay, not into it.
            val arrivals = remember(loading) { Arrivals() }
            val fresh = remember(lines) { if (loading) emptySet() else arrivals.next(lines) }
            LazyColumn(
                state = list,
                reverseLayout = true,
                userScrollEnabled = !aside,
                modifier = Modifier.fillMaxSize().overlayFades { readingShown.value },
                contentPadding = PaddingValues(start = HaloRoom, end = HaloRoom, bottom = HaloRoom),
            ) {
                tail?.let { words ->
                    item(key = TAIL_KEY, contentType = TAIL_KEY) {
                        HaloText(
                            AnnotatedString(words),
                            MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, color = ViewInk),
                            Modifier.padding(top = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                }
                items(lines.asReversed(), key = { it.message.id }, contentType = { if (it.paidCard) PAID_TYPE else LINE_TYPE }) { line ->
                    OverlayLine(line, fresh = line.message.id in fresh, style = style)
                }
            }
            NewLinesPill(
                count = if (aside) 0 else unseen,
                onClick = { reading.followNewest(scope) },
                // Its touch target reaches past the list's foot, the pill itself at the box's foot.
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = (HaloRoom - OverPill.touch).coerceAtLeast(0.dp)),
                size = OverPill,
            )
        }
    }
}

private fun ChatPlacement.corner(): Alignment = when (this) {
    ChatPlacement.TopLeft -> Alignment.TopStart
    ChatPlacement.TopRight, ChatPlacement.Side, ChatPlacement.Beside -> Alignment.TopEnd
    ChatPlacement.BottomLeft -> Alignment.BottomStart
    ChatPlacement.BottomRight -> Alignment.BottomEnd
}

/**
 * The list's top fades out over [TopFade] (`.ol`'s mask); while reading back ([reading], 0–1) its
 * foot fades too, the pill's room under it clear (`.povr.up .ol`). Drawn, not laid out: the lines
 * don't move under the finger as reading starts.
 */
private fun Modifier.overlayFades(reading: () -> Float): Modifier = graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val height = size.height
        if (height <= 0f) return@drawWithContent
        val foot = Color.Black.copy(alpha = 1f - reading())
        val top = (TopFade.toPx() / height).coerceIn(0f, 1f)
        val clear = (1f - PillRoom.toPx() / height).coerceIn(top, 1f)
        val fade = (clear - FootFade.toPx() / height).coerceIn(top, clear)
        drawRect(
            Brush.verticalGradient(0f to Color.Transparent, top to Color.Black, fade to Color.Black, clear to foot, 1f to foot),
            blendMode = BlendMode.DstIn,
        )
    }

/** "Live chat · Top chat" (`.oh`), "Chat replay · Top chat" over an ended stream: the view
 *  showing after the title ([chatViewName]). */
@Composable
private fun OverlayHead(liveView: Boolean, replay: Boolean, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Icon(painterResource(R.drawable.ic_chat), contentDescription = null, tint = AynColors.Ink, modifier = Modifier.size(13.dp).lifted(CircleShape, 0.6f))
        val head = remember(liveView, replay) {
            buildAnnotatedString {
                withStyle(weightSpan(650, AynColors.Ink)) { append(chatTitle(replay)) }
                chatViewName(replay = replay, liveView = liveView)?.let { withStyle(weightSpan(550, ViewInk)) { append("  · $it") } }
            }
        }
        HaloText(head, MaterialTheme.typography.labelMedium.copy(fontSize = 11.5.sp))
    }
}

/** One line, rising in when it arrives after the overlay first showed. */
@Composable
private fun OverlayLine(line: ChatLine, fresh: Boolean, style: TextStyle) {
    val arrival = remember { Animatable(if (fresh) 0f else 1f) }
    LaunchedEffect(Unit) { if (arrival.value < 1f) arrival.animateTo(1f, tween(AynMotion.MOVE_MS, easing = AynMotion.EaseOut)) }
    Box(
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = arrival.value
                translationY = ArrivalRise.toPx() * (1f - arrival.value)
            }
            .padding(vertical = LineGap)
            .semantics(mergeDescendants = true) {},
    ) {
        if (line.paidCard) OverPaid(line.message, style) else OverText(line, style)
    }
}

/** A text line over the picture (`.povr .lmsg`): the shadowed photo, then the haloed name and words. */
@Composable
private fun OverText(line: ChatLine, style: TextStyle) {
    val message = line.message
    Row {
        Box(Modifier.padding(top = 0.5.dp).size(AvatarSize)) {
            when {
                line.removed -> Box(Modifier.fillMaxSize().drawBehind { drawCircle(RemovedRing, style = Stroke(1.dp.toPx())) })
                message.kind != ChatKind.SYSTEM -> OverAvatar(message)
            }
        }
        val (text, inline) = remember(line) { lineText(line, OverInks) }
        HaloText(text, style, Modifier.padding(start = AvatarGap).weight(1f), inline)
    }
}

/**
 * A Super Chat, Super Sticker or membership over the picture (`.povr .lpaid`): the tier's colour on
 * its amount pill alone (a membership's green on its headline), the name and words haloed like any
 * line, the sticker after the pill.
 */
@Composable
private fun OverPaid(message: ChatMessage, style: TextStyle) {
    val pill = message.paidColor()
    val label = if (message.kind == ChatKind.MEMBERSHIP) message.headline else message.amount
    Column(Modifier.padding(top = PaidHeadGap)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(AvatarSize)) { OverAvatar(message) }
            Column(Modifier.padding(start = AvatarGap).weight(1f)) {
                message.author?.let { HaloText(AnnotatedString(it), style.atWeight(640).copy(fontSize = 11.5.sp, color = NameInk)) }
                label?.let {
                    BasicText(
                        text = it,
                        style = style.atWeight(720).copy(fontSize = 12.sp, color = inkOn(pill), fontFeatureSettings = "tnum"),
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .lifted(CircleShape, 0.6f)
                            .background(pill, CircleShape)
                            .padding(PillPadding),
                    )
                }
            }
            chatPicture(message.stickerUrl)?.let { url -> Box(Modifier.padding(start = AvatarGap).size(StickerSize)) { Picture(url) } }
        }
        if (message.runs.isNotEmpty()) {
            val (text, inline) = remember(message) { runsText(message.runs) }
            HaloText(text, style, Modifier.padding(start = AvatarSize + AvatarGap, top = 2.dp, bottom = 2.dp), inline)
        }
    }
}

/** An author's photo lifted off the picture by a shadow (`.povr .lav`). */
@Composable
private fun OverAvatar(message: ChatMessage) {
    Box(Modifier.fillMaxSize().lifted(CircleShape, 0.75f)) {
        ChannelAvatar(message.author.orEmpty(), chatPicture(message.authorPhotoUrl), monogramSize = 10f)
    }
}

/** A soft dark shadow under [shape] at [alpha], lifting it off the picture (`.povr` box shadows). */
private fun Modifier.lifted(shape: Shape, alpha: Float): Modifier = dropShadow(shape) {
    radius = 5.dp.toPx()
    offset = Offset(0f, 1.dp.toPx())
    color = Color.Black
    this.alpha = alpha
}

/**
 * Words haloed so they read over any picture (`--halo`): a wide soft shadow drawn under the same
 * words with a tight dark edge (one [Shadow] a text can carry, so two layers). The under layer
 * keeps [inline]'s places empty (the pictures draw once, on top) and is silent to accessibility.
 */
@Composable
private fun HaloText(text: AnnotatedString, style: TextStyle, modifier: Modifier = Modifier, inline: Map<String, InlineTextContent> = emptyMap()) {
    val density = LocalDensity.current
    val (wide, tight) = remember(density) {
        with(density) {
            Shadow(Color.Black.copy(alpha = 0.75f), Offset(0f, 1.dp.toPx()), blurRadius = 6.dp.toPx()) to
                Shadow(Color.Black.copy(alpha = 0.95f), Offset.Zero, blurRadius = 1.5.dp.toPx())
        }
    }
    val places = remember(inline) { inline.mapValues { (_, content) -> InlineTextContent(content.placeholder) {} } }
    Box(modifier) {
        BasicText(text, style = style.copy(shadow = wide), inlineContent = places, modifier = Modifier.clearAndSetSemantics {})
        BasicText(text, style = style.copy(shadow = tight), inlineContent = inline)
    }
}
