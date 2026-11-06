package com.arshad.ayn.ui.tv.player

import androidx.annotation.DrawableRes
import androidx.annotation.OptIn
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.state.rememberProgressStateWithTickInterval
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.arshad.ayn.core.access.Chapter
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.feature.format.MetaSeparator
import com.arshad.ayn.feature.format.blurb
import com.arshad.ayn.feature.format.compactCount
import com.arshad.ayn.feature.format.metaLine
import com.arshad.ayn.feature.format.subscribersText
import com.arshad.ayn.feature.format.viewsText
import com.arshad.ayn.feature.player.DetailsVotes
import com.arshad.ayn.feature.player.PlaybackDressing
import com.arshad.ayn.feature.player.PlayerChromePolicy
import com.arshad.ayn.feature.player.VoteRow
import com.arshad.ayn.feature.player.chapterIndexAt
import com.arshad.ayn.feature.player.likedText
import com.arshad.ayn.feature.player.timeText
import com.arshad.ayn.feature.player.uploadedText
import com.arshad.ayn.feature.player.voteRow
import com.arshad.ayn.ui.tv.R
import com.arshad.ayn.ui.tv.common.AvatarPicture
import com.arshad.ayn.ui.tv.common.PagePill
import com.arshad.ayn.ui.tv.common.rememberCardFace
import com.arshad.ayn.ui.tv.common.rememberFaceArrival
import com.arshad.ayn.ui.tv.common.rememberRise
import com.arshad.ayn.ui.tv.common.rising
import com.arshad.ayn.ui.tv.focus.ControlStyle
import com.arshad.ayn.ui.tv.focus.ControlSurface
import com.arshad.ayn.ui.tv.focus.TintedIcon
import com.arshad.ayn.ui.tv.focus.TintedText
import com.arshad.ayn.ui.tv.focus.drawInflated
import com.arshad.ayn.ui.tv.focus.rememberFadeTint
import com.arshad.ayn.ui.tv.settings.widenStart
import com.arshad.ayn.ui.tv.theme.KeColors
import com.arshad.ayn.ui.tv.theme.KeLayout
import com.arshad.ayn.ui.tv.theme.KeMotion
import com.arshad.ayn.ui.tv.theme.atWeight
import com.arshad.ayn.ui.tv.theme.weightSpan
import java.util.Locale

// `.frame.mini` (px ÷ 2): the 1920 × 1080 picture at 36%, its 40 px corners and 4 px ring with it.
private val FrameSize = DpSize(345.6.dp, 194.4.dp)
private val FrameCorner = 7.2.dp
private val FrameRing = 0.72.dp
private val FrameRingColor = Color.White.copy(alpha = 0.14f)

// `.dscrim`: oklch(30% 0.03 260 / .45), an ellipse 60% × 50% of the screen at 82% / 18%, clear at 70%.
private val DetailsTint = Color(0xFF252E3D).copy(alpha = 0.45f)

// `.details`, `.chapters`, `.chrow` (px ÷ 2).
private val ColumnWidth = 500.dp
private val ChaptersTop = 244.dp
private val ChaptersWidth = 345.5.dp
private val ChapterHeight = 25.dp
private val ChapterInset = 9.dp
private val ChapterShape = RoundedCornerShape(7.dp)
private const val CHAPTERS_DELAY_MS = 40

// Room left of a chapter row for its focus lift, inside the list's clip.
private val LiftRoom = 4.dp
private val ChapterStyle = ControlStyle.Row.copy(focusedScale = 1.01f)
private val NowShape = RoundedCornerShape(3.dp)
private val NowFill = Color.White.copy(alpha = 0.14f)

private val RatioSize = DpSize(110.dp, 4.dp)
private val RatioTrack = Color.White.copy(alpha = 0.2f)

/**
 * Where the picture plays while Details shows (`.frame.mini`): a frame at the safe lines' top
 * right, aligned top end by the caller. The picture's SurfaceView can't be scaled or faded, so it
 * moves there at once and Details' ground ([detailsGround]) rounds its corners.
 */
internal fun Modifier.detailsFrame(): Modifier =
    padding(end = KeLayout.SafeHorizontal, top = KeLayout.SafeVertical).size(FrameSize)

/**
 * Details (`b3-player-panels` details, novotes) over the picture in its corner: the title (the
 * card's face; a DeArrow title is credited with YouTube's beside it, Q28), the channel with
 * Subscribe (none without [onToggleSubscribe]: a kids profile, KP-D11), views and the upload day, YouTube's likes with Return YouTube Dislike's [votes]
 * (Q27), the description's first paragraph, and the chapters, the one playing marked Now.
 * Focus opens on that chapter (else Subscribe); OK on a chapter plays from there ([onChapter]).
 */
@OptIn(UnstableApi::class)
@Composable
internal fun PlayerDetails(
    video: VideoItem,
    dressing: PlaybackDressing?,
    votes: DetailsVotes?,
    isSubscribed: Boolean,
    player: Player,
    onToggleSubscribe: (() -> Unit)?,
    onChapter: (Chapter) -> Unit,
) {
    val chapters = dressing?.chapters.orEmpty()
    val locale = LocalConfiguration.current.locales[0]
    val scope = rememberCoroutineScope()
    val progress = rememberProgressStateWithTickInterval(player, PlayerChromePolicy.POSITION_TICK_MS, scope)
    val playing by remember(chapters) { derivedStateOf { chapterIndexAt(chapters, progress.currentPositionMs) } }
    // Where focus opens: the chapter playing as Details opens (it doesn't follow playback after).
    val landingChapter = remember(chapters) { playing.coerceAtLeast(0) }
    val canSubscribe = video.uploaderUrl != null && video.uploaderName != null && onToggleSubscribe != null
    val chapterFocus = remember { FocusRequester() }
    val subscribeFocus = remember { FocusRequester() }
    val pageFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        when {
            chapters.isNotEmpty() -> chapterFocus
            canSubscribe -> subscribeFocus
            else -> pageFocus
        }.requestFocus()
    }
    Box(
        Modifier
            .fillMaxSize()
            .detailsGround()
            .focusRequester(pageFocus)
            // The page holds focus only when nothing on it can.
            .focusProperties { canFocus = chapters.isEmpty() && !canSubscribe }
            .focusable(),
    ) {
        Column(
            Modifier
                .padding(start = KeLayout.SafeHorizontal, top = KeLayout.SafeVertical)
                .width(ColumnWidth)
                .rising(rememberRise()),
        ) {
            Text("Details", style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp), color = KeColors.Ink3)
            val face = rememberCardFace(video)
            val arrival = rememberFaceArrival(face != null)
            Text(
                text = face?.title.orEmpty(),
                style = MaterialTheme.typography.headlineMedium.copy(fontSize = 32.sp, lineHeight = 0.98.em, lineBreak = LineBreak.Heading),
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp).graphicsLayer { alpha = arrival.value },
            )
            if (face?.titleFromDeArrow == true) DeArrowCredit(video.title, Modifier.graphicsLayer { alpha = arrival.value })
            if (video.uploaderName != null) {
                ChannelLine(video, dressing, isSubscribed, onToggleSubscribe.takeIf { canSubscribe }, subscribeFocus)
            }
            metaLine(viewsText(video.viewCount), uploadedText(video, locale)).takeIf { it.isNotEmpty() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                    color = KeColors.Ink2,
                    modifier = Modifier.padding(top = 13.dp),
                )
            }
            voteRow(dressing?.likeCount, votes ?: DetailsVotes.Loading)?.let { VotesLine(it, locale) }
            dressing?.description?.let(::blurb)?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 13.sp, lineHeight = 1.45.em),
                    color = KeColors.Ink2,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 13.dp).widthIn(max = 470.dp),
                )
            }
        }
        if (chapters.isNotEmpty()) {
            Column(
                Modifier
                    .align(Alignment.TopEnd)
                    .fillMaxHeight()
                    .padding(top = ChaptersTop, end = KeLayout.SafeHorizontal, bottom = KeLayout.SafeVertical)
                    .width(ChaptersWidth)
                    .rising(rememberRise(CHAPTERS_DELAY_MS)),
            ) {
                Text(
                    text = buildAnnotatedString {
                        append("Chapters")
                        withStyle(weightSpan(450, KeColors.Ink3).copy(fontSize = 12.sp)) { append("  ${chapters.size}") }
                    },
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 15.sp),
                    modifier = Modifier.padding(bottom = 5.dp),
                )
                Column(Modifier.widenStart(ChapterInset + LiftRoom).verticalScroll(rememberScrollState()).padding(start = LiftRoom)) {
                    chapters.forEachIndexed { index, chapter ->
                        ChapterRow(
                            chapter = chapter,
                            place = when {
                                index < playing -> ChapterPlace.Past
                                index == playing -> ChapterPlace.Playing
                                else -> ChapterPlace.Ahead
                            },
                            onClick = { onChapter(chapter) },
                            modifier = if (index == landingChapter) Modifier.focusRequester(chapterFocus) else Modifier,
                        )
                    }
                }
            }
        }
    }
}

/** The avatar, name and subscribers, then Subscribe (`.drow`) when there is [onToggleSubscribe]. */
@Composable
private fun ChannelLine(
    video: VideoItem,
    dressing: PlaybackDressing?,
    isSubscribed: Boolean,
    onToggleSubscribe: (() -> Unit)?,
    subscribeFocus: FocusRequester,
) {
    val name = video.uploaderName ?: return
    Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Box(Modifier.size(26.dp)) { AvatarPicture(name, dressing?.uploaderAvatarUrl, 11.sp) }
            Text(name, style = MaterialTheme.typography.labelLarge.atWeight(700).copy(fontSize = 13.5.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            dressing?.subscriberCount?.let(::subscribersText)?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp), color = KeColors.Ink2, modifier = Modifier.padding(start = 2.dp))
            }
        }
        if (onToggleSubscribe != null) {
            PagePill(onClick = onToggleSubscribe, modifier = Modifier.focusRequester(subscribeFocus)) {
                Crossfade(isSubscribed, animationSpec = KeMotion.fast(), label = "subscribed") { on ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TintedIcon(painterResource(if (on) R.drawable.ic_check else R.drawable.ic_plus), contentDescription = null, modifier = Modifier.size(13.dp))
                        TintedText(if (on) "Subscribed" else "Subscribe", style = MaterialTheme.typography.labelLarge.atWeight(600).copy(fontSize = 13.sp))
                    }
                }
            }
        }
    }
}

/** The likes, the dislikes, the ratio and RYD's credit, or that RYD has no data (`.votes`, `.novotes`). */
@Composable
private fun VotesLine(row: VoteRow, locale: Locale) {
    val note = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp)
    Row(Modifier.padding(top = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
        row.likes?.let { VoteCount(R.drawable.ic_thumb_up, "Likes", it) }
        row.dislikes?.let { VoteCount(R.drawable.ic_thumb_down, "Dislikes", it) }
        row.likedPercent?.let { percent ->
            RatioBar(percent)
            Text(likedText(percent, locale), style = note, color = KeColors.Ink3)
        }
        if (row.noDislikeData) Text("No dislike data for this video", style = note, color = KeColors.Ink3)
    }
    if (row.dislikes != null) {
        Text(
            text = "Dislikes are an estimate from Return YouTube Dislike.",
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
            color = KeColors.Ink3,
            modifier = Modifier.padding(top = 5.dp),
        )
    }
}

/** `.dearrow`: the title shown is DeArrow's; YouTube's [original] beside the credit (Q28). */
@Composable
private fun DeArrowCredit(original: String, modifier: Modifier = Modifier) {
    Row(modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(
            painterResource(R.drawable.ic_edit),
            contentDescription = null,
            tint = KeColors.Ink3,
            modifier = Modifier.padding(top = 0.5.dp).size(13.dp),
        )
        Text(
            text = "Title from DeArrow${MetaSeparator}Original: \u201C$original\u201D",
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.5.sp, lineHeight = 1.35.em),
            color = KeColors.Ink3,
        )
    }
}

@Composable
private fun VoteCount(@DrawableRes icon: Int, label: String, count: Long) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Icon(painterResource(icon), contentDescription = label, tint = KeColors.Ink2, modifier = Modifier.size(15.dp))
        Text(compactCount(count), style = MaterialTheme.typography.labelLarge.atWeight(700).copy(fontFeatureSettings = "tnum"))
    }
}

@Composable
private fun RatioBar(likedPercent: Double) {
    Box(Modifier.size(RatioSize).clip(RoundedCornerShape(RatioSize.height / 2)).background(RatioTrack)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth((likedPercent / 100).toFloat()).background(KeColors.Ink, RoundedCornerShape(RatioSize.height / 2)))
    }
}

private enum class ChapterPlace { Past, Playing, Ahead }

/** A chapter (`.chrow`): its start and title, the one playing in bold with its Now badge, past
 *  ones fainter; focus fills it with ink (control family). */
@Composable
private fun ChapterRow(chapter: Chapter, place: ChapterPlace, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val focus = animateFloatAsState(if (focused) 1f else 0f, KeMotion.fast(), label = "chapterFocus")
    val playing = place == ChapterPlace.Playing
    val style = MaterialTheme.typography.bodyMedium.let { if (playing) it.atWeight(600) else it }
    val nameColor = when (place) {
        ChapterPlace.Past -> KeColors.Ink3
        ChapterPlace.Playing -> KeColors.Ink
        ChapterPlace.Ahead -> KeColors.Ink2
    }
    ControlSurface(
        onClick = onClick,
        style = ChapterStyle,
        shape = ChapterShape,
        modifier = modifier.fillMaxWidth().height(ChapterHeight).onFocusChanged { focused = it.isFocused },
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = ChapterInset),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TintedText(
                text = timeText(chapter.startSeconds * 1000),
                style = style.copy(fontFeatureSettings = "tnum"),
                tint = rememberFadeTint(KeColors.Ink3, KeColors.OnInk, focus),
                modifier = Modifier.width(42.dp),
            )
            TintedText(
                text = chapter.title,
                style = style,
                tint = rememberFadeTint(nameColor, KeColors.OnInk, focus),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (playing) {
                TintedText(
                    text = "Now",
                    style = MaterialTheme.typography.labelSmall.atWeight(700),
                    tint = rememberFadeTint(KeColors.Ink, KeColors.OnInk, focus),
                    modifier = Modifier
                        .drawBehind {
                            drawInflated(NowShape, 0.dp, lerp(NowFill, KeColors.OnInk.copy(alpha = 0.14f), focus.value), 1f)
                        }
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
    }
}

/**
 * Details' ground (`.dscrim` over the ground) everywhere but the picture's frame ([detailsFrame]),
 * whose corners it rounds, and the frame's faint ring.
 */
private fun Modifier.detailsGround(): Modifier = drawWithCache {
    val frame = Rect(
        offset = Offset(size.width - KeLayout.SafeHorizontal.toPx() - FrameSize.width.toPx(), KeLayout.SafeVertical.toPx()),
        size = Size(FrameSize.width.toPx(), FrameSize.height.toPx()),
    )
    val corner = FrameCorner.toPx()
    val outside = Path().apply {
        fillType = PathFillType.EvenOdd
        addRect(Rect(Offset.Zero, size))
        addRoundRect(RoundRect(frame, CornerRadius(corner)))
    }
    val tintCenter = Offset(size.width * 0.82f, size.height * 0.18f)
    val tintRadius = size.height * 0.5f
    val tint = Brush.radialGradient(0f to DetailsTint, 0.7f to DetailsTint.copy(alpha = 0f), center = tintCenter, radius = tintRadius)
    val ring = FrameRing.toPx()
    onDrawBehind {
        clipPath(outside) {
            drawRect(KeColors.Ground)
            // The gradient is a circle; stretched across, it is the mockup's ellipse.
            scale(scaleX = size.width * 0.6f / tintRadius, scaleY = 1f, pivot = tintCenter) { drawRect(tint) }
        }
        drawRoundRect(
            color = FrameRingColor,
            topLeft = frame.topLeft - Offset(ring / 2, ring / 2),
            size = Size(frame.width + ring, frame.height + ring),
            cornerRadius = CornerRadius(corner + ring / 2),
            style = Stroke(ring),
        )
    }
}
