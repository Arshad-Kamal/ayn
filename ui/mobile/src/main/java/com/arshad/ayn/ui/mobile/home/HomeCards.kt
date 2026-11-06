package com.arshad.ayn.ui.mobile.home

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.feature.CardFace
import com.arshad.ayn.feature.format.lengthText
import com.arshad.ayn.ui.mobile.R
import com.arshad.ayn.ui.mobile.common.ChannelAvatar
import com.arshad.ayn.ui.mobile.common.Picture
import com.arshad.ayn.ui.mobile.common.PressedScale
import com.arshad.ayn.ui.mobile.common.rememberCardFace
import com.arshad.ayn.ui.mobile.common.rememberFaceArrival
import com.arshad.ayn.ui.mobile.common.rememberPictureLight
import com.arshad.ayn.ui.mobile.theme.AynColors
import com.arshad.ayn.ui.mobile.theme.AynMotion
import com.arshad.ayn.ui.mobile.theme.atWeight

internal val CardWidth = 232.dp
private val CardGap = 12.dp
private val ThumbShape = RoundedCornerShape(12.dp)
internal val ShelfStart = 20.dp
private val BadgeShape = RoundedCornerShape(5.dp)
private val LineShape = RoundedCornerShape(6.dp)
private val PressedLift = 3.dp
private val SpillElevation = 18.dp
private val PressRing = 2.dp

/** A row of Home or the Library (`.shelf`): its heading (a label, M6-D11; [aside] after it, the
 *  Library's counts), then a sideways lane, [top] below what comes before. */
@Composable
internal fun Shelf(
    title: String,
    modifier: Modifier = Modifier,
    aside: String? = null,
    top: Dp = 22.dp,
    lane: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    Column(modifier.padding(top = top)) {
        Text(
            buildAnnotatedString {
                append(title)
                aside?.let { withStyle(MaterialTheme.typography.bodySmall.toSpanStyle().copy(color = AynColors.Ink3)) { append("  $it") } }
            },
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = ShelfStart, bottom = 10.dp).semantics { heading() },
        )
        LazyRow(
            state = lane,
            contentPadding = PaddingValues(horizontal = ShelfStart),
            horizontalArrangement = Arrangement.spacedBy(CardGap),
            content = content,
        )
    }
}

/**
 * A video's card: its face's picture (DeArrow's when on, M7-D11) with the length or LIVE and a
 * watch bar ([progress]), its title on two lines, [meta] and a ⋮. A tap calls [onClick]; a long
 * press or the ⋮ calls [onOpenMenu] (M6-D11). Pressed, or [held] while its menu is open, the picture
 * sinks a little, lifts and spills its own colour (M6-D10, the TV's focus light). While DeArrow is
 * waited for, the picture and title hold placeholders, then fade in.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun VideoCard(
    video: VideoItem,
    meta: String?,
    onClick: () -> Unit,
    onOpenMenu: () -> Unit,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    held: Boolean = false,
    width: Dp = CardWidth,
) {
    val face = rememberCardFace(video)
    val arrival = rememberFaceArrival(face != null)
    val interaction = remember { MutableInteractionSource() }
    val press = rememberCardPress(interaction, held, face?.thumbnailUrl)
    Column(
        modifier
            .width(width)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = "Play",
                onLongClickLabel = "Open the menu",
                onLongClick = onOpenMenu,
                onClick = onClick,
            ),
    ) {
        CardPicture(video, face, arrival, press, progress, ThumbShape, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        Row(Modifier.padding(top = 9.dp)) {
            Column(Modifier.weight(1f)) {
                if (face != null) {
                    Text(
                        face.title,
                        style = MaterialTheme.typography.bodyMedium.atWeight(600).copy(lineHeight = 17.5.sp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.graphicsLayer { alpha = arrival.value },
                    )
                } else {
                    TitleSkeleton()
                }
                if (meta != null) {
                    Text(
                        meta,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                        color = AynColors.Ink3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
            MoreButton(onOpenMenu, Modifier.padding(top = 3.dp).size(width = 32.dp, height = 40.dp))
        }
    }
}

/** A card's press, 0..1 ([amount]), and its picture's colour ([light]) for the spill. */
@Stable
internal class CardPress(val amount: State<Float>, val light: State<Color>)

/** [interaction]'s press, or [held] while the card's menu is open (M6-D10). The light is sampled
 *  from the first press on: a card never pressed decodes nothing. */
@Composable
internal fun rememberCardPress(interaction: MutableInteractionSource, held: Boolean, pictureUrl: String?): CardPress {
    val pressed by interaction.collectIsPressedAsState()
    var lit by remember { mutableStateOf(false) }
    LaunchedEffect(pressed, held) { if (pressed || held) lit = true }
    val light = rememberPictureLight(pictureUrl?.takeIf { lit })
    val amount = animateFloatAsState(if (pressed || held) 1f else 0f, AynMotion.press(), label = "press")
    return remember(amount, light) { CardPress(amount, light) }
}

/**
 * A video's picture (its [face]'s, faded in by [arrival]) with the length or LIVE and a watch bar
 * ([progress]). Pressed, it sinks a little, lifts and spills its own colour.
 */
@Composable
internal fun CardPicture(video: VideoItem, face: CardFace?, arrival: State<Float>, press: CardPress, progress: Float?, shape: Shape, modifier: Modifier) {
    Box(
        modifier
            .graphicsLayer {
                val amount = press.amount.value
                val scale = 1f - (1f - PressedScale) * amount
                scaleX = scale
                scaleY = scale
                translationY = -PressedLift.toPx() * amount
                shadowElevation = SpillElevation.toPx() * amount
                this.shape = shape
                clip = true
                spotShadowColor = press.light.value
                ambientShadowColor = press.light.value
            }
            .background(AynColors.Surface2)
            // Drawn, not laid out: the press animates without recomposing the card.
            .drawWithContent {
                drawContent()
                val outline = shape.createOutline(size, layoutDirection, this)
                drawOutline(outline, AynColors.Ink.copy(alpha = press.amount.value), style = Stroke(PressRing.toPx()))
            },
    ) {
        if (face != null) Picture(face.thumbnailUrl, Modifier.graphicsLayer { alpha = arrival.value })
        LengthBadge(video, Modifier.align(Alignment.BottomEnd).padding(end = 6.dp, bottom = if (progress != null) 9.dp else 6.dp))
        if (progress != null) WatchBar(progress, Modifier.align(Alignment.BottomStart))
    }
}

/** The ⋮ beside a title or the hero's actions: the card menu (M6-D11). */
@Composable
internal fun MoreButton(onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = AynColors.Ink3) {
    IconButton(onClick = onClick, modifier = modifier.semantics { contentDescription = "More" }) {
        Icon(painterResource(R.drawable.ic_more_vert), contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
    }
}

/** The length on the picture, or LIVE for a stream (D11); nothing when the length is unknown. */
@Composable
internal fun LengthBadge(video: VideoItem, modifier: Modifier) {
    if (video.isLive) {
        LiveBadge(modifier)
        return
    }
    val length = lengthText(video.durationSeconds) ?: return
    Text(
        length,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp),
        modifier = modifier.background(AynColors.Ground.copy(alpha = 0.8f), BadgeShape).padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** LIVE in place of a length (D11). */
@Composable
internal fun LiveBadge(modifier: Modifier = Modifier) {
    Row(
        modifier.background(AynColors.Live, BadgeShape).padding(start = 4.dp, end = 5.dp, top = 1.dp, bottom = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(painterResource(R.drawable.ic_live), contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
        Text("LIVE", style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp), color = Color.White)
    }
}

/** How far the profile got: the ink part over a dim track along the picture's foot (read out as progress). */
@Composable
private fun WatchBar(fraction: Float, modifier: Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(3.dp)
            .background(Color.White.copy(alpha = 0.3f))
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(fraction.coerceIn(0f, 1f), 0f..1f) },
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(AynColors.Ink))
    }
}

/** A followed channel in Your channels: its avatar, name and when it last uploaded. */
@Composable
internal fun ChannelCard(name: String, avatarUrl: String?, meta: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.width(76.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // A subscription can come without a picture (an older backup, an import): its monogram.
        Box(Modifier.size(64.dp)) { ChannelAvatar(name, avatarUrl, monogramSize = 24f) }
        Text(
            name,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.5.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (meta != null) Text(meta, style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp), color = AynColors.Ink3, maxLines = 1)
    }
}

/** A title's two lines held as placeholder bars (DeArrow wait). */
@Composable
internal fun TitleSkeleton() {
    val pulse = rememberPulse()
    Column(Modifier.padding(end = 24.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        SkeletonBar(pulse, Modifier.fillMaxWidth(0.92f).height(12.dp))
        SkeletonBar(pulse, Modifier.fillMaxWidth(0.64f).height(12.dp))
    }
}

/** One placeholder bar, pulsing with [pulse] (alpha only; one pulse per placeholder group) while content is on its way. */
@Composable
internal fun SkeletonBar(pulse: State<Float>, modifier: Modifier = Modifier, shape: Shape = LineShape) {
    Box(modifier.graphicsLayer { alpha = pulse.value }.background(AynColors.Surface3, shape))
}

/** Home's loading breath (2.4 s, alpha only). */
@Composable
internal fun rememberPulse(): State<Float> =
    rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(BreathMs), RepeatMode.Reverse),
        label = "pulse",
    )

private const val BreathMs = 2400

/** A shelf's shape while Home loads: a heading bar and two card placeholders. */
@Composable
internal fun ShelfSkeleton(pulse: State<Float>) {
    Column(Modifier.padding(top = 22.dp, start = ShelfStart)) {
        SkeletonBar(pulse, Modifier.size(150.dp, 16.dp))
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(CardGap)) {
            repeat(2) {
                Column(Modifier.width(CardWidth)) {
                    SkeletonBar(pulse, Modifier.fillMaxWidth().aspectRatio(16f / 9f), ThumbShape)
                    SkeletonBar(pulse, Modifier.padding(top = 10.dp).fillMaxWidth(0.9f).height(12.dp))
                    SkeletonBar(pulse, Modifier.padding(top = 7.dp).fillMaxWidth(0.6f).height(12.dp))
                }
            }
        }
    }
}
