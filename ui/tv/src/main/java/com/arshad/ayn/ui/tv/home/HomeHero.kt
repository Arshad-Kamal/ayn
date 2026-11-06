package com.arshad.ayn.ui.tv.home

import com.arshad.ayn.feature.home.HomeTop
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.feature.CardFace
import com.arshad.ayn.feature.home.HeroPage
import com.arshad.ayn.feature.home.VideoFormat
import com.arshad.ayn.ui.tv.R
import com.arshad.ayn.ui.tv.common.AvatarPicture
import com.arshad.ayn.ui.tv.common.LineShape
import com.arshad.ayn.ui.tv.common.LiveBadge
import com.arshad.ayn.feature.format.MetaSeparator
import com.arshad.ayn.ui.tv.common.Picture
import com.arshad.ayn.ui.tv.common.PillButton
import com.arshad.ayn.ui.tv.common.PillButtonContent
import com.arshad.ayn.ui.tv.common.PillButtonHeight
import com.arshad.ayn.ui.tv.common.RoundButton
import com.arshad.ayn.ui.tv.common.VideoCardWidth
import com.arshad.ayn.feature.format.ageText
import com.arshad.ayn.ui.tv.common.backdropScrims
import com.arshad.ayn.feature.format.blurb
import com.arshad.ayn.feature.format.lengthText
import com.arshad.ayn.ui.tv.common.rememberFaceArrival
import com.arshad.ayn.ui.tv.common.rememberPictureLight
import com.arshad.ayn.ui.tv.common.rememberSheen
import com.arshad.ayn.ui.tv.common.skeleton
import com.arshad.ayn.feature.format.viewsText
import com.arshad.ayn.ui.tv.focus.ControlStyle
import com.arshad.ayn.ui.tv.focus.ControlSurface
import com.arshad.ayn.ui.tv.focus.focusMemory
import com.arshad.ayn.ui.tv.theme.KeColors
import com.arshad.ayn.ui.tv.theme.KeLayout
import com.arshad.ayn.ui.tv.theme.KeMotion
import com.arshad.ayn.ui.tv.theme.weightSpan

// Master `.hero` copy geometry (px ÷ 2).
private val CopyWidth = 500.dp
private val TitleWidth = 490.dp
private val BlurbWidth = 410.dp
private val BylineAvatar = 22.dp

// The comments' monogram-to-avatar ratio (~0.38).
private val BylineMonogram = 8.5.sp
private val NewBadgeShape = RoundedCornerShape(4.dp)
private const val BackdropRestScale = 1.1f
private const val BackdropAwayScale = 1.16f
private const val BackdropAwayAlpha = 0.38f

/** States 1–4: the hero over its own picture, its chip "For you" for For you's pick (`b3-home`
 *  fy-rest), "New" for a new subscription upload: Play, Watch later and ⋯ (the card menu). Its
 *  title is the video's [face]; `null` while DeArrow is waited for, the title held as skeleton
 *  bars (M7-D11). */
@Composable
internal fun HeroSection(
    top: HomeTop.HeroTop,
    face: CardFace?,
    away: State<Float>,
    onPlay: (VideoItem) -> Unit,
    onWatchLater: () -> Unit,
    onMore: (VideoItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val video = top.hero.video
    val light by rememberPictureLight(face?.thumbnailUrl)
    val arrival = rememberFaceArrival(face != null)
    Box(modifier.fillMaxWidth().height(HeroHeight)) {
        Column(
            Modifier
                .padding(start = KeLayout.ContentStart, top = HeroCopyTop)
                .width(CopyWidth)
                .fadeAway(away),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // A channel not followed has no avatar on hand (For you's picks): its monogram.
                Box(Modifier.size(BylineAvatar)) { AvatarPicture(video.uploaderName.orEmpty(), top.avatarUrl, BylineMonogram) }
                Text(
                    text = video.uploaderName.orEmpty(),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(start = 7.dp),
                )
                val chip = if (top.reason != null) "For you" else "New".takeIf { top.hero.isNew }
                if (chip != null) {
                    Text(
                        text = chip,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .padding(start = 10.dp)
                            .background(KeColors.Ink.copy(alpha = 0.14f), NewBadgeShape)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            if (face != null) {
                Text(
                    text = face.title,
                    style = MaterialTheme.typography.displayMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 13.dp).widthIn(max = TitleWidth).graphicsLayer { alpha = arrival.value },
                )
            } else {
                HeroTitleSkeleton(rememberSheen(), Modifier.padding(top = 13.dp))
            }
            HeroMeta(top, Modifier.padding(top = 14.dp))
            (top.hero.page as? HeroPage.Ready)?.description?.let(::blurb)?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyLarge,
                    color = KeColors.Ink2,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 9.dp).widthIn(max = BlurbWidth),
                )
            }
            Row(
                modifier = Modifier.padding(top = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PillButton(icon = R.drawable.ic_play, label = "Play", focusKey = PlayKey, onClick = { onPlay(video) }, light = light)
                WatchLaterButton(top.inWatchLater, light, onWatchLater)
                RoundButton(R.drawable.ic_more, "More", MoreKey, onClick = { onMore(video) })
            }
        }
    }
}

/** Watch later at rest with the clock (Q20), "In Watch later" with a check once saved; the label crossfades when
 *  it flips (D8), as Subscribe does. */
@Composable
private fun WatchLaterButton(saved: Boolean, light: Color, onClick: () -> Unit) {
    ControlSurface(
        onClick = onClick,
        style = ControlStyle.Secondary,
        light = light,
        modifier = Modifier.focusMemory(WatchLaterKey).height(PillButtonHeight),
    ) {
        Crossfade(saved, animationSpec = KeMotion.fast(), label = "watchLater") { on ->
            if (on) PillButtonContent(R.drawable.ic_check, "In Watch later") else PillButtonContent(R.drawable.ic_later, "Watch later")
        }
    }
}

/** "56:09 · 110K views · 2 days ago · 1080p · 25 fps"; a live stream shows LIVE, not a length (D11). */
@Composable
private fun HeroMeta(top: HomeTop.HeroTop, modifier: Modifier = Modifier) {
    val video = top.hero.video
    val format = (top.hero.page as? HeroPage.Ready)?.format?.let(::formatText)
    val rest = listOfNotNull(viewsText(video.viewCount), video.ageText(top.now), format)
    val length = lengthText(video.durationSeconds).takeUnless { video.isLive }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (video.isLive) LiveBadge(Modifier.padding(end = 7.dp))
        Text(
            text = buildAnnotatedString {
                length?.let { withStyle(weightSpan(600, KeColors.Ink)) { append(it) } }
                rest.forEachIndexed { i, part ->
                    if (i > 0 || length != null) withStyle(SpanStyle(color = KeColors.Ink3)) { append(MetaSeparator) }
                    append(part)
                }
            },
            style = MaterialTheme.typography.bodyMedium,
            color = KeColors.Ink2,
            maxLines = 1,
        )
    }
}

private fun formatText(format: VideoFormat): String =
    listOfNotNull("${format.height}p", format.fps?.let { "$it fps" }).joinToString(MetaSeparator)

/** The hero's picture full-bleed behind everything; it dims and grows while rows hold focus. */
@Composable
internal fun HeroBackdrop(url: String?, away: State<Float>) {
    Box(Modifier.fillMaxSize().backdropScrims()) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val scale = BackdropRestScale + (BackdropAwayScale - BackdropRestScale) * away.value
                    scaleX = scale
                    scaleY = scale
                    alpha = 1f - (1f - BackdropAwayAlpha) * away.value
                    transformOrigin = TransformOrigin(0.88f, 0.72f)
                },
        ) {
            Picture(url)
        }
    }
}

/** State 5: the final layout's shape under one sheen, the screen warm where the picture lands;
 *  nothing focusable until content arrives (TVF-7). */
@Composable
internal fun HomeSkeleton() {
    val sheen = rememberSheen()
    val breath = rememberBreath()
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = breath.value }
                .drawWithCache {
                    val warm = Brush.radialGradient(
                        0f to KeColors.Warm.copy(alpha = 0.9f),
                        0.7f to KeColors.Warm.copy(alpha = 0f),
                        center = androidx.compose.ui.geometry.Offset(size.width * 0.8f, size.height * 0.3f),
                        radius = size.width * 0.58f,
                    )
                    onDrawBehind { drawRect(warm) }
                },
        )
        Column(Modifier.padding(start = KeLayout.ContentStart, top = StatementTop)) {
            Box(Modifier.size(130.dp, 17.dp).skeleton(sheen, LineShape))
            HeroTitleSkeleton(sheen, Modifier.padding(top = 15.dp))
            Box(Modifier.padding(top = 17.dp).size(260.dp, 13.dp).skeleton(sheen, LineShape))
            Box(Modifier.padding(top = 22.dp).size(85.dp, 36.dp).skeleton(sheen, CircleShape))
        }
        Column(Modifier.padding(start = KeLayout.ContentStart, top = HeroHeight)) {
            Box(Modifier.size(150.dp, 16.dp).skeleton(sheen, LineShape))
            Row(
                Modifier.padding(top = 11.dp).wrapContentWidth(Alignment.Start, unbounded = true),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                repeat(5) {
                    Column {
                        Box(Modifier.size(VideoCardWidth, 112.5.dp).skeleton(sheen))
                        Box(Modifier.padding(top = 9.dp).size(165.dp, 13.dp).skeleton(sheen, LineShape))
                        Box(Modifier.padding(top = 6.dp).size(105.dp, 13.dp).skeleton(sheen, LineShape))
                    }
                }
            }
        }
    }
}

/** The hero title's two lines as skeleton blocks (state 5's geometry). */
@Composable
private fun HeroTitleSkeleton(sheen: State<Float>, modifier: Modifier = Modifier) {
    Column(modifier) {
        Box(Modifier.size(450.dp, 42.dp).skeleton(sheen))
        Box(Modifier.padding(top = 7.dp).size(310.dp, 42.dp).skeleton(sheen))
    }
}
