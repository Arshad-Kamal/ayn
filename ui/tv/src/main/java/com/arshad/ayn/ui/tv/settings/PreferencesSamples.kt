package com.arshad.ayn.ui.tv.settings

import androidx.annotation.DrawableRes
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.arshad.ayn.feature.format.lengthText
import com.arshad.ayn.feature.settings.SettingsSample
import com.arshad.ayn.ui.tv.R
import com.arshad.ayn.ui.tv.common.LengthBadge
import com.arshad.ayn.ui.tv.focus.drawKeyline
import com.arshad.ayn.ui.tv.theme.KeColors
import com.arshad.ayn.ui.tv.theme.KeMotion
import com.arshad.ayn.ui.tv.theme.atWeight
import com.arshad.ayn.ui.tv.theme.color

private val BarRest = Color.White.copy(alpha = 0.2f)
private val LegendSwatch = RoundedCornerShape(3.dp)

/**
 * SponsorBlock's sample (`.sbdemo`): the sample video's seek bar, played to where it was, with
 * its segments in their categories' colours, and a legend naming them and the video.
 */
@Composable
internal fun SegmentsSample(modifier: Modifier = Modifier) {
    Column(modifier.padding(start = RowInset, top = 17.dp, bottom = 5.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(2.5.dp))
                .drawBehind {
                    drawRect(BarRest)
                    drawRect(KeColors.Ink2, size = Size(size.width * SettingsSample.PLAYED_FRACTION, size.height))
                    SettingsSample.SEGMENTS.forEach { segment ->
                        drawRect(segment.category.color, Offset(size.width * segment.startFraction, 0f), Size(size.width * segment.widthFraction, size.height))
                    }
                },
        )
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            val style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp)
            SettingsSample.SEGMENTS.forEach { segment ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.size(9.dp).background(segment.category.color, LegendSwatch))
                    Text(segment.legend, style = style, color = KeColors.Ink2, maxLines = 1)
                }
            }
            Text("on “${SettingsSample.TITLE}”", style = style, color = KeColors.Ink3, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
    }
}

private val ThumbShape = RoundedCornerShape(7.dp)

/**
 * DeArrow's sample (`.compare`): the sample video as YouTube shows it, fainter, beside the same
 * video with DeArrow's title where [titles] is on and its thumbnail where [thumbnails] is (Q15);
 * with both off that side says so (Q22). A change crossfades (D8).
 */
@Composable
internal fun DeArrowSample(titles: Boolean, thumbnails: Boolean) {
    Row(Modifier.padding(start = RowInset, top = 10.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        SampleFigure("Original", SettingsSample.ORIGINAL_PICTURE, SettingsSample.ORIGINAL_TITLE, withDeArrow = false, Modifier.weight(1f))
        SampleFigure(
            label = if (titles || thumbnails) "With DeArrow" else "With DeArrow · off",
            picture = if (thumbnails) SettingsSample.DEARROW_PICTURE else SettingsSample.ORIGINAL_PICTURE,
            title = if (titles) SettingsSample.TITLE else SettingsSample.ORIGINAL_TITLE,
            withDeArrow = true,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SampleFigure(label: String, @DrawableRes picture: Int, title: String, withDeArrow: Boolean, modifier: Modifier) {
    Column(modifier.alpha(if (withDeArrow) 1f else 0.62f)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall.atWeight(600),
            color = if (withDeArrow) KeColors.Ink else KeColors.Ink3,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        val edge = Color.White.copy(alpha = if (withDeArrow) 0.14f else 0.08f)
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(ThumbShape)
                .drawWithContent {
                    drawContent()
                    drawKeyline(ThumbShape, 0.5.dp, edge, 1f)
                },
        ) {
            Crossfade(picture, Modifier.fillMaxSize(), animationSpec = KeMotion.base(), label = "samplePicture") { shown ->
                Image(painterResource(shown), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            LengthBadge(lengthText(SettingsSample.LENGTH_S.toLong()).orEmpty(), Modifier.align(Alignment.BottomEnd).padding(end = 6.dp, bottom = 7.dp))
        }
        Crossfade(title, animationSpec = KeMotion.base(), label = "sampleTitle") { shown ->
            Text(
                text = shown,
                style = MaterialTheme.typography.titleSmall.copy(lineHeight = 1.25.em),
                modifier = Modifier.padding(top = 7.dp),
            )
        }
    }
}

private val RatioTrack = Color.White.copy(alpha = 0.2f)

/** Dislikes' sample (`.vdemo`): the sample video's likes, dislikes and the share liked. */
@Composable
internal fun VotesSample(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(start = RowInset, top = 10.dp, bottom = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Count(R.drawable.ic_thumb_up, SettingsSample.LIKES)
        Count(R.drawable.ic_thumb_down, SettingsSample.DISLIKES)
        Box(
            Modifier
                .width(130.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .drawBehind {
                    drawRect(RatioTrack)
                    drawRect(KeColors.Ink, size = Size(size.width * SettingsSample.LIKED, size.height))
                },
        )
        Text("${SettingsSample.LIKED_TEXT} liked", style = MaterialTheme.typography.bodyLarge.copy(fontSize = 12.sp), color = KeColors.Ink3)
    }
}

@Composable
private fun Count(@DrawableRes icon: Int, count: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Icon(painterResource(icon), contentDescription = null, tint = KeColors.Ink2, modifier = Modifier.size(16.dp))
        Text(count, style = MaterialTheme.typography.labelMedium.atWeight(700).copy(fontSize = 15.sp))
    }
}
