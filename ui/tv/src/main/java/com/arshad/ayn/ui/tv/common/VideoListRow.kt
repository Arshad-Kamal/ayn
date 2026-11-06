package com.arshad.ayn.ui.tv.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.ui.tv.focus.FocusLight
import com.arshad.ayn.ui.tv.focus.NeutralSurface
import com.arshad.ayn.ui.tv.focus.drawInflated
import com.arshad.ayn.ui.tv.focus.drawThumbnailRim
import com.arshad.ayn.ui.tv.focus.focusLights
import com.arshad.ayn.ui.tv.focus.focusMemory
import com.arshad.ayn.ui.tv.focus.rememberFocusLights
import com.arshad.ayn.ui.tv.theme.KeColors
import com.arshad.ayn.ui.tv.theme.KeMotion
import com.arshad.ayn.ui.tv.theme.KeTypography

// Master `.lrow` (px ÷ 2): 7 dp above and below the thumbnail, 9 dp in from the row's ends (the
// row reaches that far past its column), the number column 20 dp wide.
internal val ListRowInset = 9.dp
private val RowPadding = 7.dp
internal val ListIndexWidth = 20.dp
private val LineTop = 4.dp
private val BadgeEnd = 5.dp
private val BadgeBottom = 6.dp

// Master `.lrow.is-focused`: a faint band; the thumbnail grows 4% inside the thumbnails' ink rim
// and spills its colour (0 24px 60px -16px, and 0 0 120px 10px at 35%).
private val RowShape = RoundedCornerShape(11.dp)
internal val ListThumbShape = RoundedCornerShape(6.dp)
private const val BandAlpha = 0.07f
private val NumberStyle = KeTypography.titleMedium.copy(fontSize = 13.sp, fontFeatureSettings = "tnum")
private val LineStyle = KeTypography.bodyMedium.copy(fontSize = 12.sp, fontFeatureSettings = "tnum")
private const val ThumbFocusedScale = 1.04f

/** A list row's height around a [thumbWidth]-wide 16:9 thumbnail. */
internal fun listRowHeight(thumbWidth: Dp): Dp = thumbWidth / ThumbAspect + RowPadding * 2

/** A list row's frame: [height] tall, its items [gap] apart, centred on the thumbnail's line. */
@Composable
internal fun ListRowFrame(height: Dp, gap: Dp, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = modifier.fillMaxWidth().height(height).padding(horizontal = ListRowInset, vertical = RowPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(gap),
        content = content,
    )
}

/**
 * One video in a list (master `.lrow`), content focus family: the whole row takes focus, its
 * thumbnail lifts and spills the picture's colour. The Surface clips its content to the row, so
 * that light is drawn behind the row at the thumbnail's place. [number] leads the row (a
 * playlist's order), [line] sits under the title, [trailing] at the row's end (when it was
 * watched), and [progress] (0..1) draws how far the profile got. Picture and title are the
 * video's face ([rememberCardFace]), waited for as a card's are. While focused the row reports
 * its light and centre ([onFocused], root px), sampled on first focus as cards do. Holding OK
 * calls [onLongClick] (the card menu).
 */
@Composable
internal fun VideoListRow(
    video: VideoItem,
    line: String?,
    focusKey: String,
    onClick: () -> Unit,
    onFocused: (light: State<Color>, centerX: Float) -> Unit,
    modifier: Modifier = Modifier,
    thumbWidth: Dp,
    gap: Dp,
    number: Int? = null,
    trailing: String? = null,
    progress: Float? = null,
    onLongClick: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val focus = animateFloatAsState(if (focused) 1f else 0f, KeMotion.base(), label = "row")
    // Compose the light's own layout only while the row is lit.
    val lit by remember { derivedStateOf { focus.value > 0f } }
    var sample by remember { mutableStateOf(false) }
    val face = rememberCardFace(video)
    val arrival = rememberFaceArrival(face != null)
    val sheen = if (face == null) rememberSheen() else null
    val lightState = rememberPictureLight(face?.thumbnailUrl.takeIf { sample })
    val placement = remember { Placement() }
    val height = listRowHeight(thumbWidth)
    val thumbHeight = thumbWidth / ThumbAspect

    Box(modifier.fillMaxWidth().height(height).onPlaced { placement.coordinates = it }) {
        if (lit) ThumbnailLight(lightState.value, focus, height, gap, thumbWidth, numbered = number != null)
        NeutralSurface(
            onClick = onClick,
            shape = RowShape,
            onLongClick = onLongClick,
            modifier = Modifier
                .focusMemory(focusKey)
                .fillMaxSize()
                .drawBehind { drawInflated(RowShape, 0.dp, Color.White, BandAlpha * focus.value) }
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) {
                        sample = true
                        placement.report { centerX -> onFocused(lightState, centerX) }
                    }
                },
        ) {
            ListRowFrame(height, gap) {
                number?.let {
                    RowText(it.toString(), NumberStyle, rest = KeColors.Ink3, lit = KeColors.Ink2, focused = focused, Modifier.width(ListIndexWidth), TextAlign.End)
                }
                Box(
                    Modifier
                        .size(thumbWidth, thumbHeight)
                        .thumbScale(focus)
                        .drawBehind { drawThumbnailRim(ListThumbShape, focus.value) }
                        .clip(ListThumbShape)
                        .background(KeColors.Surface2),
                ) {
                    FacePicture(face, arrival, sheen)
                    progress?.let { WatchBar(it, WatchBarSize.Row, Modifier.align(Alignment.BottomCenter)) }
                    VideoBadge(
                        video,
                        Modifier.align(Alignment.BottomEnd).padding(end = BadgeEnd, bottom = if (progress != null) WatchBarSize.Row.badgeBottom else BadgeBottom),
                    )
                }
                Column(Modifier.weight(1f)) {
                    val titleStyle = MaterialTheme.typography.titleMedium
                    if (face != null) {
                        RowText(
                            face.title,
                            titleStyle,
                            rest = KeColors.Ink2,
                            lit = KeColors.Ink,
                            focused = focused,
                            Modifier.graphicsLayer { alpha = arrival.value },
                            maxLines = 2,
                        )
                    } else if (sheen != null) {
                        TitleSkeleton(titleStyle, sheen)
                    }
                    line?.let {
                        RowText(it, LineStyle, rest = KeColors.Ink3, lit = KeColors.Ink2, focused = focused, Modifier.padding(top = LineTop))
                    }
                }
                // `.when` keeps its tone while the row is focused.
                trailing?.let { Text(it, style = LineStyle, color = KeColors.Ink3, maxLines = 1) }
            }
        }
    }
}

/**
 * The focused thumbnail's light, laid out where [ListRowFrame] puts the thumbnail and drawn
 * behind the row: the row's Surface clips its content to the row, and the light spills past it.
 */
@Composable
private fun ThumbnailLight(light: Color, focus: State<Float>, height: Dp, gap: Dp, thumbWidth: Dp, numbered: Boolean) {
    val lights = remember(light) {
        listOf(
            FocusLight(light, alpha = 0.35f, blur = 60.dp, spread = 5.dp),
            FocusLight(light, alpha = 1f, blur = 30.dp, spread = (-8).dp, offsetY = 12.dp),
        )
    }
    val focusLights = rememberFocusLights(ListThumbShape, lights)
    ListRowFrame(height, gap) {
        if (numbered) Spacer(Modifier.width(ListIndexWidth))
        Spacer(
            Modifier
                .size(thumbWidth, thumbWidth / ThumbAspect)
                .thumbScale(focus)
                .focusLights(focusLights, focus),
        )
    }
}

/** A row's text, brightening while the row holds focus (D8, as card text does). */
@Composable
private fun RowText(
    text: String,
    style: TextStyle,
    rest: Color,
    lit: Color,
    focused: Boolean,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
    maxLines: Int = 1,
) {
    val color by animateColorAsState(if (focused) lit else rest, KeMotion.fast(), label = "rowText")
    Text(text, style = style, color = color, textAlign = textAlign, maxLines = maxLines, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

private fun Modifier.thumbScale(progress: State<Float>): Modifier = graphicsLayer {
    val scale = 1f + (ThumbFocusedScale - 1f) * progress.value
    scaleX = scale
    scaleY = scale
}
