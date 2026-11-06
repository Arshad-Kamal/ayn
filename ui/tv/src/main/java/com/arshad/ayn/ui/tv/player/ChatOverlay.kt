package com.arshad.ayn.ui.tv.player

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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import com.arshad.ayn.core.access.ChatKind
import com.arshad.ayn.core.access.ChatMessage
import com.arshad.ayn.core.store.ChatPlacement
import com.arshad.ayn.feature.player.Arrivals
import com.arshad.ayn.feature.player.ChatLine
import com.arshad.ayn.feature.player.ChatStatus
import com.arshad.ayn.feature.player.PlayerChat
import com.arshad.ayn.feature.player.chatPicture
import com.arshad.ayn.feature.player.chatTailText
import com.arshad.ayn.feature.player.chatTitle
import com.arshad.ayn.feature.player.chatViewName
import com.arshad.ayn.feature.player.overPicture
import com.arshad.ayn.ui.tv.R
import com.arshad.ayn.ui.tv.common.AvatarPicture
import com.arshad.ayn.ui.tv.common.Picture
import com.arshad.ayn.ui.tv.common.hairlineBorder
import com.arshad.ayn.ui.tv.common.rememberRise
import com.arshad.ayn.ui.tv.common.rising
import com.arshad.ayn.ui.tv.common.topFade
import com.arshad.ayn.ui.tv.theme.KeColors
import com.arshad.ayn.ui.tv.theme.KeLayout
import com.arshad.ayn.ui.tv.theme.KeMotion
import com.arshad.ayn.ui.tv.theme.atWeight
import com.arshad.ayn.ui.tv.theme.weightSpan

// `live-tv-player` `.ovr` (px ÷ 2): the side column 320 dp wide with 32 dp before its words, a
// corner box 300 × 220 dp; both inside the safe lines.
private val SideWidth = 320.dp
private val SideStart = 32.dp
private val CornerWidth = 300.dp

/** The corner box's height: the cues rise above a bottom corner by it ([chatCornerTop]). */
private val CornerHeight = 220.dp

/** Where a bottom corner's box ends, up from the screen's foot. */
internal val chatCornerTop: Dp = CornerHeight + KeLayout.SafeVertical

private val ListFade = 40.dp

/** The list's room under the head (`.ol`'s max-height leaves 44 px for both). */
private val HeadGap = 7.dp

/** The halo reaches past the words: the clipped list reaches this far past the words on either
 *  side (out of the frame's padding), the words keeping the mockup's place. */
private val HaloRoom = 8.dp

private val AvatarSize = 20.dp
private val SideLineGap = 4.5.dp
private val CornerLineGap = 3.dp

/** Lines laid out at most: the side holds ~18 of the shortest, a corner ~8; older ones are clipped
 *  under the top fade anyway. */
private const val SIDE_LINES = 20
private const val CORNER_LINES = 8

private val SideText = 12.5.sp
private val CornerText = 11.5.sp

// `.ovr` inks: a name at 86 %, a moderator and a member brighter than the panel's, so they hold over a picture.
private val NameInk = KeColors.Ink.copy(alpha = 0.86f)
private val ViewInk = KeColors.Ink.copy(alpha = 0.85f)
private val OverInks = LineInks(author = NameInk, moderator = Color(0xFF9DC3F2), member = Color(0xFF86E09A))
private val RemovedRing = Color.White.copy(alpha = 0.18f)
private val AvatarRing = Color.Black.copy(alpha = 0.35f)
private val PillPadding = PaddingValues(start = 6.dp, end = 6.dp, top = 0.5.dp, bottom = 1.5.dp)

private val IconShape = RoundedCornerShape(3.dp)

/**
 * A live stream's chat over the picture in [placement] (`live-tv-player` ov-side, ov-br, ov-tl;
 * LIVE-D21, D22): along the right side (the newest rising from the foot, as many as fit) or a
 * corner box inside the safe lines (the last few), with no backdrop: the head and every line carry
 * their own shadow so the picture stays in view. Read-only: nothing here takes focus. It steps
 * aside while [aside] (the chrome, a sheet, a note in its corner) with a 200 ms fade. While the
 * first answer loads only its head shows; a chat that is off, or not yet opened, shows nothing.
 * An ended stream's kept chat ([replay], LIVE-D28) shows the same way, its lines without times.
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
    val shown = animateFloatAsState(if (aside) 0f else 1f, KeMotion.move(), label = "chatAside")
    AnimatedVisibility(
        visible = at != null,
        modifier = Modifier.matchParentSize(),
        enter = fadeIn(KeMotion.move()),
        exit = fadeOut(KeMotion.move()),
    ) {
        val shownAt = held.placement ?: return@AnimatedVisibility
        val shownChat = held.chat ?: return@AnimatedVisibility
        // A new view fades in where the old one fades out, its list starting afresh.
        Crossfade(shownAt, animationSpec = KeMotion.move(), label = "chatView") { view ->
            Box(Modifier.fillMaxSize()) { Overlay(view, shownChat, shown, replay) }
        }
    }
}

/** The last overlay shown: a cache written by [ChatOverlay]'s composition, not state. */
private class HeldOverlay {
    var placement: ChatPlacement? = null
    var chat: PlayerChat? = null
}

@Composable
private fun BoxScope.Overlay(placement: ChatPlacement, chat: PlayerChat, shown: State<Float>, replay: Boolean) {
    val side = placement == ChatPlacement.Side
    val frame = if (side) {
        Modifier
            .align(Alignment.TopEnd)
            .fillMaxHeight()
            .width(SideWidth)
            .padding(start = SideStart - HaloRoom, end = KeLayout.SafeHorizontal - HaloRoom, top = KeLayout.SafeVertical, bottom = KeLayout.SafeVertical)
    } else {
        Modifier
            .align(placement.corner())
            .padding(horizontal = KeLayout.SafeHorizontal - HaloRoom, vertical = KeLayout.SafeVertical)
            .size(CornerWidth + HaloRoom * 2, CornerHeight)
    }
    val textSize = if (side) SideText else CornerText
    val style = MaterialTheme.typography.bodyLarge.atWeight(580).copy(fontSize = textSize, lineHeight = 1.36.em, color = KeColors.Ink)
    // The stepping aside on the chat's own frame: its offscreen layer no bigger than the chat.
    Column(frame.graphicsLayer { alpha = shown.value }) {
        OverlayHead(chat.liveView, replay, Modifier.padding(horizontal = HaloRoom))
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(top = HeadGap)
                .clipToBounds()
                .topFade(ListFade),
        ) {
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .wrapContentHeight(Alignment.Bottom, unbounded = true)
                    .padding(horizontal = HaloRoom),
            ) {
                val loading = chat.status.loading
                val lines = if (loading) emptyList() else chat.lines.takeLast(if (side) SIDE_LINES else CORNER_LINES)
                // Afresh after each load (a view switched, a retry, a replay's jump): the first answer
                // arrives with the overlay, not into it.
                val arrivals = remember(loading) { Arrivals() }
                val fresh = remember(lines) { if (loading) emptySet() else arrivals.next(lines) }
                lines.forEach { line ->
                    key(line.message.id) {
                        OverlayLine(line, fresh = line.message.id in fresh, style = style, gap = if (side) SideLineGap else CornerLineGap)
                    }
                }
                chatTailText(chat.status)?.let { tail ->
                    key(tail) {
                        HaloText(
                            AnnotatedString(tail),
                            MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp, color = ViewInk),
                            Modifier.padding(top = 4.dp).rising(rememberRise()),
                        )
                    }
                }
            }
        }
    }
}

private fun ChatPlacement.corner(): Alignment = when (this) {
    ChatPlacement.TopLeft -> Alignment.TopStart
    ChatPlacement.TopRight, ChatPlacement.Side, ChatPlacement.Beside -> Alignment.TopEnd
    ChatPlacement.BottomLeft -> Alignment.BottomStart
    ChatPlacement.BottomRight -> Alignment.BottomEnd
}

/** "Live chat · Top chat" (`.oh`), "Chat replay · Top chat" over an ended stream: the view
 *  showing after the title ([chatViewName]). */
@Composable
private fun OverlayHead(liveView: Boolean, replay: Boolean, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(painterResource(R.drawable.ic_chat), contentDescription = null, tint = KeColors.Ink, modifier = Modifier.size(12.dp).lifted(IconShape, 0.6f))
        val head = remember(liveView, replay) {
            buildAnnotatedString {
                withStyle(weightSpan(620, KeColors.Ink)) { append(chatTitle(replay)) }
                chatViewName(replay = replay, liveView = liveView)?.let { withStyle(weightSpan(520, ViewInk)) { append("  · $it") } }
            }
        }
        HaloText(head, MaterialTheme.typography.labelLarge.copy(fontSize = 11.sp))
    }
}

/** One line, rising in when it arrives after the overlay first showed. */
@Composable
private fun OverlayLine(line: ChatLine, fresh: Boolean, style: TextStyle, gap: Dp) {
    val arrival = remember { Animatable(if (fresh) 0f else 1f) }
    LaunchedEffect(Unit) { if (arrival.value < 1f) arrival.animateTo(1f, tween(KeMotion.MOVE_MS, easing = KeMotion.EaseOut)) }
    Box(
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = arrival.value
                translationY = ArrivalRise.toPx() * (1f - arrival.value)
            }
            .padding(vertical = gap),
    ) {
        if (line.paidCard) OverPaid(line.message, style) else OverText(line, style)
    }
}

/** A text line over the picture (`.ovr .lmsg`): the shadowed photo, then the haloed name and words. */
@Composable
private fun OverText(line: ChatLine, style: TextStyle) {
    val message = line.message
    Row {
        Box(Modifier.padding(top = 0.5.dp).size(AvatarSize)) {
            when {
                line.removed -> Box(Modifier.fillMaxSize().hairlineBorder(RemovedRing, AvatarSize / 2))
                message.kind != ChatKind.SYSTEM -> OverAvatar(message)
            }
        }
        val (text, inline) = remember(line) { lineText(line, OverInks) }
        HaloText(text, style, Modifier.padding(start = 8.dp).weight(1f), inline)
    }
}

/**
 * A Super Chat, Super Sticker or membership over the picture (`.ovr .lpaid`): the tier's colour on
 * its amount pill alone (a membership's green on its headline), the name and words haloed like
 * any line, the sticker after the pill.
 */
@Composable
private fun OverPaid(message: ChatMessage, style: TextStyle) {
    val pill = message.paidColor()
    val label = if (message.kind == ChatKind.MEMBERSHIP) message.headline else message.amount
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(AvatarSize)) { OverAvatar(message) }
            Column(Modifier.padding(start = 8.dp).weight(1f)) {
                message.author?.let { HaloText(AnnotatedString(it), style.atWeight(640).copy(color = NameInk)) }
                label?.let {
                    BasicText(
                        text = it,
                        style = style.atWeight(720).copy(color = inkOn(pill), fontFeatureSettings = "tnum"),
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .lifted(CircleShape, 0.6f)
                            .background(pill, CircleShape)
                            .padding(PillPadding),
                    )
                }
            }
            chatPicture(message.stickerUrl)?.let { url -> Box(Modifier.padding(start = 8.dp).size(StickerSize)) { Picture(url) } }
        }
        if (message.runs.isNotEmpty()) {
            val (text, inline) = remember(message) { runsText(message.runs) }
            HaloText(text, style, Modifier.padding(start = AvatarSize + 8.dp, top = 2.dp), inline)
        }
    }
}

/** An author's photo lifted off the picture by a ring and a shadow (`.ovr .lav`). */
@Composable
private fun OverAvatar(message: ChatMessage) {
    Box(
        Modifier
            .fillMaxSize()
            .lifted(CircleShape, 0.7f)
            .hairlineBorder(AvatarRing, AvatarSize / 2),
    ) {
        AvatarPicture(message.author.orEmpty(), chatPicture(message.authorPhotoUrl), 8.sp)
    }
}

/** A soft dark shadow under [shape] at [alpha], lifting it off the picture (`.ovr` box shadows). */
private fun Modifier.lifted(shape: Shape, alpha: Float): Modifier = dropShadow(shape) {
    radius = 4.dp.toPx()
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
            Shadow(Color.Black.copy(alpha = 0.75f), Offset(0f, 1.dp.toPx()), blurRadius = 7.dp.toPx()) to
                Shadow(Color.Black.copy(alpha = 0.95f), Offset.Zero, blurRadius = 1.5.dp.toPx())
        }
    }
    val places = remember(inline) { inline.mapValues { (_, content) -> InlineTextContent(content.placeholder) {} } }
    Box(modifier) {
        BasicText(text, style = style.copy(shadow = wide), inlineContent = places, modifier = Modifier.clearAndSetSemantics {})
        BasicText(text, style = style.copy(shadow = tight), inlineContent = inline)
    }
}
