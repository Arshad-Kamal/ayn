package com.arshad.ayn.ui.mobile.settings

import androidx.annotation.DrawableRes
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arshad.ayn.feature.settings.SettingsSample
import com.arshad.ayn.ui.mobile.R
import com.arshad.ayn.ui.mobile.player.color
import com.arshad.ayn.ui.mobile.theme.AynColors
import com.arshad.ayn.ui.mobile.theme.AynMotion
import com.arshad.ayn.ui.mobile.theme.atWeight

private val CardShape = RoundedCornerShape(16.dp)
private val BarRest = Color.White.copy(alpha = 0.18f)

/** SponsorBlock's sample (`.sbsample`): the sample video's seek bar, played to where it was, its
 *  segments in their categories' colours, and a legend naming them and the video. */
@Composable
internal fun SegmentsSample() {
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 2.dp).fillMaxWidth().background(AynColors.Surface1, CardShape).padding(14.dp)) {
        Box(
            Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).drawBehind {
                drawRect(BarRest)
                drawRect(AynColors.Ink.copy(alpha = 0.9f), size = Size(size.width * SettingsSample.PLAYED_FRACTION, size.height))
                SettingsSample.SEGMENTS.forEach { segment ->
                    drawRect(segment.category.color, Offset(size.width * segment.startFraction, 0f), Size(size.width * segment.widthFraction, size.height))
                }
            },
        )
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            SettingsSample.SEGMENTS.forEach { segment ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Swatch(segment.category.color, size = 10)
                    Text(segment.legend, style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.5.sp), color = AynColors.Ink2, maxLines = 1)
                }
            }
        }
        Text("on “${SettingsSample.TITLE}”", style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = AynColors.Ink3, modifier = Modifier.padding(top = 6.dp))
    }
}

/** DeArrow's sample (`.dasample`): the video as YouTube shows it beside it with DeArrow's title
 *  where [titles] is on and its thumbnail where [thumbnails] is; with both off that side says so.
 *  A change cross-fades (190 ms). */
@Composable
internal fun DeArrowSample(titles: Boolean, thumbnails: Boolean) {
    Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Figure("Original", SettingsSample.ORIGINAL_PICTURE, SettingsSample.ORIGINAL_TITLE, Modifier.weight(1f))
        Figure(
            label = if (titles || thumbnails) "With DeArrow" else "With DeArrow · off",
            picture = if (thumbnails) SettingsSample.DEARROW_PICTURE else SettingsSample.ORIGINAL_PICTURE,
            title = if (titles) SettingsSample.TITLE else SettingsSample.ORIGINAL_TITLE,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun Figure(label: String, @DrawableRes picture: Int, title: String, modifier: Modifier) {
    Column(modifier.background(AynColors.Surface1, CardShape).padding(10.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall.atWeight(680), color = AynColors.Ink3, modifier = Modifier.padding(bottom = 8.dp))
        Crossfade(picture, animationSpec = AynMotion.base(), label = "samplePicture") { shown ->
            Image(
                painterResource(shown),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)),
            )
        }
        Crossfade(title, animationSpec = AynMotion.base(), label = "sampleTitle") { shown ->
            Text(
                shown,
                style = MaterialTheme.typography.titleSmall.atWeight(620).copy(fontSize = 13.sp, lineHeight = 17.sp),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** Dislikes' sample (`.votes2`): the sample video's likes, dislikes and the share liked. */
@Composable
internal fun VotesSample() {
    Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Count(R.drawable.ic_thumb_up, SettingsSample.LIKES, "likes", Modifier.weight(1f))
        Count(R.drawable.ic_thumb_down, SettingsSample.DISLIKES, "dislikes", Modifier.weight(1f))
        Column(Modifier.weight(1f).background(AynColors.Surface1, CardShape).padding(12.dp)) {
            Text(SettingsSample.LIKED_TEXT, style = CountStyle)
            Text("liked", style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp), color = AynColors.Ink3)
            Box(
                Modifier.padding(top = 6.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).drawBehind {
                    drawRect(BarRest)
                    drawRect(AynColors.Ink, size = Size(size.width * SettingsSample.LIKED, size.height))
                },
            )
        }
    }
}

private val CountStyle
    @Composable get() = MaterialTheme.typography.headlineMedium.copy(fontSize = 20.sp, lineHeight = 24.sp)

@Composable
private fun Count(@DrawableRes icon: Int, count: String, label: String, modifier: Modifier) {
    Column(modifier.background(AynColors.Surface1, CardShape).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(painterResource(icon), contentDescription = null, tint = AynColors.Ink, modifier = Modifier.size(18.dp))
            Text(count, style = CountStyle)
        }
        Text(label, style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp), color = AynColors.Ink3)
    }
}
