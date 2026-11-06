package com.arshad.ayn.ui.tv.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.feature.library.WatchMark
import com.arshad.ayn.ui.tv.R
import com.arshad.ayn.ui.tv.common.CardRow
import com.arshad.ayn.ui.tv.common.CardRoomLight
import com.arshad.ayn.ui.tv.common.FocusedCardLight
import com.arshad.ayn.ui.tv.common.GridCardTitleSize
import com.arshad.ayn.ui.tv.common.Picture
import com.arshad.ayn.ui.tv.common.PillButton
import com.arshad.ayn.ui.tv.common.PrefetchFaces
import com.arshad.ayn.ui.tv.common.ThumbAspect
import com.arshad.ayn.ui.tv.common.VideoBadge
import com.arshad.ayn.ui.tv.common.VideoCard
import com.arshad.ayn.feature.format.ageText
import com.arshad.ayn.ui.tv.common.hairlineBorder
import com.arshad.ayn.feature.format.lengthText
import com.arshad.ayn.feature.format.metaLine
import com.arshad.ayn.ui.tv.common.rememberCardFace
import com.arshad.ayn.ui.tv.common.rememberFaceArrival
import com.arshad.ayn.ui.tv.common.rememberRise
import com.arshad.ayn.ui.tv.common.rising
import com.arshad.ayn.feature.format.viewsText
import com.arshad.ayn.feature.format.watchingText
import com.arshad.ayn.ui.tv.focus.ControlStyle
import com.arshad.ayn.ui.tv.focus.FocusHeld
import com.arshad.ayn.ui.tv.focus.FocusMemory
import com.arshad.ayn.ui.tv.focus.LocalFocusMemory
import com.arshad.ayn.ui.tv.focus.LocalOverlayHoldsFocus
import com.arshad.ayn.feature.format.barFraction
import com.arshad.ayn.ui.tv.theme.KeColors
import com.arshad.ayn.ui.tv.theme.KeLayout
import com.arshad.ayn.ui.tv.theme.KeMotion
import kotlin.time.Clock

// `b3-player-panels` geometry (px ÷ 2): the Related row's cards start 9 dp in from the safe line,
// where its heading sits; the peek shows the same cards' top edge.
private val RelatedCardWidth = 190.dp
private val RelatedCardGap = 14.dp
private val RelatedStart = KeLayout.SafeHorizontal + 9.dp
private val RelatedThumbShape = RoundedCornerShape(7.dp)

/** How far the chrome's bottom rises: one band over the peek, above the open row. */
internal val PeekLift = 52.dp
internal val RowLift = 165.dp

private val PeekHeight = 60.dp
private const val PEEK_PICTURES = 5
private const val PEEK_PICTURE_ALPHA = 0.55f

/**
 * Under the chrome of a video with related videos: "↓ Related" and the row's first pictures cut
 * off at the screen's bottom edge (`.relpeek`), so DOWN has somewhere to go. Not focusable.
 */
@Composable
internal fun RelatedPeek(videos: List<VideoItem>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().height(PeekHeight).clipToBounds().padding(start = KeLayout.SafeHorizontal)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DownKeyCap(size = 15.dp, fontSize = 9.5.sp)
            Text("Related", style = MaterialTheme.typography.titleSmall.copy(fontSize = 12.sp), color = KeColors.Ink2)
        }
        Row(
            modifier = Modifier
                .padding(start = RelatedStart - KeLayout.SafeHorizontal, top = 7.dp)
                .wrapContentWidth(Alignment.Start, unbounded = true)
                .graphicsLayer { alpha = PEEK_PICTURE_ALPHA },
            horizontalArrangement = Arrangement.spacedBy(RelatedCardGap),
        ) {
            videos.take(PEEK_PICTURES).forEach { video ->
                Box(Modifier.width(RelatedCardWidth).aspectRatio(ThumbAspect).clip(RelatedThumbShape).background(KeColors.Surface2)) {
                    // The row's cards show these faces; nothing until DeArrow answers.
                    Picture(rememberCardFace(video)?.thumbnailUrl)
                }
            }
        }
    }
}

/** The remote's DOWN drawn as a keycap (`kbd`). */
@Composable
internal fun DownKeyCap(size: Dp, fontSize: TextUnit, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size).hairlineBorder(Color.White.copy(alpha = 0.4f), 4.5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("↓", style = MaterialTheme.typography.labelMedium.copy(fontSize = fontSize))
    }
}

/** The resumed toast's action (`b3-player` 13): what DOWN does, drawn as a resting button. */
@Composable
internal fun StartOverHint() {
    Row(
        modifier = Modifier
            .height(28.dp)
            .background(KeColors.ControlRest, CircleShape)
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        DownKeyCap(size = 17.dp, fontSize = 11.sp)
        Text("Start over", style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.sp))
    }
}

/**
 * The card menu closing hands focus back to the row it was opened from (VideoMenuHost). When the
 * surface under it was replaced meanwhile (the video ended, Play all moved on), nothing here holds
 * focus then, so [fallback] takes it, a frame after the menu's own hand-back.
 */
@Composable
internal fun Modifier.refocusAfterMenu(menuOpen: Boolean, fallback: () -> Unit): Modifier {
    val held = remember { FocusHeld() }
    val currentFallback by rememberUpdatedState(fallback)
    LaunchedEffect(menuOpen) {
        if (menuOpen) return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        if (!held.now) currentFallback()
    }
    return onFocusChanged { held.now = it.hasFocus }
}

/**
 * The Related row (`.relrow`): YouTube's related [videos] (ids unique: they key the row) as
 * cards, each with its resume point's bar ([marks], read per card). OK plays one ([onPlay]), holding OK opens
 * the card menu ([onOpenMenu]). Entering the row returns to the card that last held focus, else
 * the first: the cards' [FocusMemory], the row's one restoration mechanism (CMP-FOCUS-03;
 * `focusRestorer` lost the card the menu was held on); a card a block took out while its menu
 * held focus hands it to the next. Cards bleed past the safe line as every row's do: a cut
 * there, as the mockup's lane has, cut the focused first card's light.
 */
@Composable
internal fun RelatedRow(
    videos: List<VideoItem>,
    marks: State<Map<VideoId, WatchMark>>,
    onPlay: (VideoItem) -> Unit,
    onOpenMenu: (VideoItem) -> Unit,
    onCardFocused: (light: State<Color>, centerX: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val memory = remember { FocusMemory(mutableStateOf(null)) }
    val overlayHoldsFocus by rememberUpdatedState(LocalOverlayHoldsFocus.current)
    val titleStyle = MaterialTheme.typography.titleSmall.copy(fontSize = GridCardTitleSize)
    val now = remember { Clock.System.now() }
    PrefetchFaces(videos) { videos }
    CompositionLocalProvider(LocalFocusMemory provides memory) {
        CardRow(
            title = "Related",
            itemWidth = RelatedCardWidth,
            itemSpacing = RelatedCardGap,
            start = RelatedStart,
            headingStart = KeLayout.SafeHorizontal,
            modifier = modifier
                .onFocusChanged { memory.regionFocusChanged(it.hasFocus, overlayHoldsFocus) }
                .focusProperties { onEnter = { memory.restore(initialKey = null) } }
                .focusGroup(),
        ) { rowFocused ->
            items(videos, key = { it.id.value }) { video ->
                VideoCard(
                    video = video,
                    meta = if (video.isLive) metaLine(video.uploaderName, watchingText(video.viewCount)) else video.uploaderName,
                    secondMeta = if (video.isLive) null else metaLine(viewsText(video.viewCount), video.ageText(now)).ifEmpty { null },
                    focusKey = "related:${video.id.value}",
                    onClick = { onPlay(video) },
                    width = RelatedCardWidth,
                    titleStyle = titleStyle,
                    progress = marks.value[video.id].barFraction(),
                    rowFocused = rowFocused,
                    onFocused = onCardFocused,
                    onLongClick = { onOpenMenu(video) },
                )
            }
        }
    }
}

/** The open row's floor (`.relscrim`): the picture plays on behind it, at [alpha]. */
internal fun Modifier.relatedScrim(alpha: () -> Float): Modifier = drawWithCache {
    val floor = Brush.verticalGradient(
        0.3f to PlayerShade.copy(alpha = 0f),
        0.48f to PlayerShade.copy(alpha = 0.55f),
        0.66f to PlayerShade.copy(alpha = 0.9f),
        1f to PlayerShade.copy(alpha = 0.97f),
    )
    onDrawBehind {
        val a = alpha()
        if (a > 0f) drawRect(floor, alpha = a)
    }
}

// `.unext` (px ÷ 2): 20 dp below the top safe line, the copy beside a 360 dp picture.
private val UpNextTop = KeLayout.SafeVertical + 20.dp
private val UpNextGap = 32.dp
private val UpNextPictureWidth = 360.dp
private val UpNextPictureShape = RoundedCornerShape(9.dp)
private const val ROW_DELAY_MS = 40

private val EndScrim = Brush.verticalGradient(
    0f to PlayerShade.copy(alpha = 0.78f),
    0.6f to PlayerShade.copy(alpha = 0.9f),
    1f to PlayerShade.copy(alpha = 0.96f),
)

/**
 * The end screen (`b3-player-panels` upnext, M5-D12): [finished] has ended and Up next offers the
 * first of [related] with Play (focused) and Cancel; nothing starts on its own. The rest of the
 * row waits below, the card menu on its cards ([panels]). Titles and the picture are the videos'
 * faces, faded in after a wait for DeArrow. The ended video's last frame holds behind a deep
 * scrim (no blur on API 30). The row sits at the screen's foot; where that leaves under
 * [UpNextGap] below Play and Cancel (a large Interface size), it follows them past the foot
 * instead, and everything lifts by that overflow while the row holds focus (or its card menu is open).
 */
@Composable
internal fun UpNext(
    finished: VideoItem,
    related: List<VideoItem>,
    panels: RelatedPanels,
    onCancel: () -> Unit,
) {
    val next = related.first()
    val finishedFace = rememberCardFace(finished)
    val finishedArrival = rememberFaceArrival(finishedFace != null)
    val nextFace = rememberCardFace(next)
    val nextArrival = rememberFaceArrival(nextFace != null)
    val play = remember { FocusRequester() }
    val rowFocus = remember { FocusRequester() }
    // Spatial DOWN from Play/Cancel didn't reach the row (Robolectric, Compose UI 1.12.1; cause not
    // isolated), so DOWN and UP move between them here, as the Stage's row does.
    var actionsFocused by remember { mutableStateOf(false) }
    val cardLight = remember { FocusedCardLight() }
    var rowFocused by remember { mutableStateOf(false) }
    val rise = rememberRise()
    val riseRow = rememberRise(ROW_DELAY_MS)
    // Held while a card's menu is open: the menu opens from the row and returns focus to it.
    val lift = animateFloatAsState(if (rowFocused || panels.menuOpen) 1f else 0f, KeMotion.move(), label = "upNextLift")
    LaunchedEffect(Unit) {
        withFrameNanos { }
        play.requestFocus()
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(EndScrim)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || related.size < 2) return@onPreviewKeyEvent false
                when {
                    event.key == Key.DirectionDown && actionsFocused -> rowFocus.requestFocus()
                    event.key == Key.DirectionUp && rowFocused -> play.requestFocus()
                    else -> return@onPreviewKeyEvent false
                }
                true
            }
            .refocusAfterMenu(panels.menuOpen) { play.requestFocus() },
    ) {
        CardRoomLight(cardLight, lit = rowFocused)
        Layout(
            modifier = Modifier.fillMaxSize(),
            content = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = KeLayout.SafeHorizontal, end = KeLayout.SafeHorizontal, top = UpNextTop)
                        .rising(rise),
                    horizontalArrangement = Arrangement.spacedBy(UpNextGap),
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(
                            modifier = Modifier.graphicsLayer { alpha = finishedArrival.value },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = KeColors.Ink3, modifier = Modifier.size(13.dp))
                            Text(
                                text = "Finished · ${finishedFace?.title.orEmpty()}",
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                                color = KeColors.Ink3,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text("Up next", style = MaterialTheme.typography.titleLarge.copy(fontSize = 15.sp), modifier = Modifier.padding(top = 22.dp))
                        Text(
                            text = nextFace?.title.orEmpty(),
                            style = MaterialTheme.typography.headlineMedium.copy(fontSize = 32.sp, lineHeight = 0.98.em),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 7.dp).graphicsLayer { alpha = nextArrival.value },
                        )
                        Text(
                            text = metaLine(next.uploaderName, viewsText(next.viewCount), next.ageText(Clock.System.now()), lengthText(next.durationSeconds)),
                            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                            color = KeColors.Ink2,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        Row(
                            modifier = Modifier.padding(top = 18.dp).onFocusChanged { actionsFocused = it.hasFocus },
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            PillButton(R.drawable.ic_play, "Play", "upnext-play", onClick = { panels.onPlay(next) }, modifier = Modifier.focusRequester(play))
                            PillButton(null, "Cancel", "upnext-cancel", onClick = onCancel, style = ControlStyle.Secondary)
                        }
                    }
                    Box(
                        Modifier
                            .width(UpNextPictureWidth)
                            .aspectRatio(ThumbAspect)
                            .dropShadow(UpNextPictureShape) {
                                radius = 50.dp.toPx()
                                spread = (-12).dp.toPx()
                                offset = Offset(0f, 20.dp.toPx())
                                color = Color.Black
                                alpha = 0.9f
                            }
                            .clip(UpNextPictureShape)
                            .background(KeColors.Surface2),
                    ) {
                        Picture(nextFace?.thumbnailUrl, Modifier.graphicsLayer { alpha = nextArrival.value })
                        Box(Modifier.fillMaxSize().hairlineBorder(Color.White.copy(alpha = 0.1f), 9.dp))
                        VideoBadge(next, Modifier.align(Alignment.BottomEnd).padding(end = 7.dp, bottom = 7.dp))
                    }
                }
                val rest = related.drop(1)
                if (rest.isNotEmpty()) {
                    RelatedRow(
                        videos = rest,
                        marks = panels.marks,
                        onPlay = panels.onPlay,
                        onOpenMenu = panels.onOpenMenu,
                        onCardFocused = cardLight::onCardFocused,
                        modifier = panels.rowModifier
                            .focusRequester(rowFocus)
                            .padding(bottom = KeLayout.SafeVertical)
                            .rising(riseRow)
                            .onFocusChanged { rowFocused = it.hasFocus },
                    )
                }
            },
        ) { measurables, constraints ->
            val loose = constraints.copy(minWidth = 0, minHeight = 0)
            val top = measurables[0].measure(loose)
            val row = measurables.getOrNull(1)?.measure(loose)
            val foot = constraints.maxHeight - (row?.height ?: 0)
            val overflow = (top.height + UpNextGap.roundToPx() - foot).coerceAtLeast(0)
            layout(constraints.maxWidth, constraints.maxHeight) {
                top.placeWithLayer(0, 0) { translationY = -overflow * lift.value }
                row?.placeWithLayer(0, foot + overflow) { translationY = -overflow * lift.value }
            }
        }
    }
}
