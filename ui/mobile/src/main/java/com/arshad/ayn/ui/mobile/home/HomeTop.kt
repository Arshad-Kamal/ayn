package com.arshad.ayn.ui.mobile.home

import androidx.annotation.DrawableRes
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arshad.ayn.core.access.AccessFailure
import com.arshad.ayn.feature.format.MetaSeparator
import com.arshad.ayn.feature.format.ageText
import com.arshad.ayn.feature.format.lengthText
import com.arshad.ayn.feature.format.viewsText
import com.arshad.ayn.feature.home.HeroPage
import com.arshad.ayn.feature.home.HomeTop
import com.arshad.ayn.feature.home.VideoFormat
import com.arshad.ayn.ui.mobile.R
import com.arshad.ayn.ui.mobile.common.ButtonKind
import com.arshad.ayn.ui.mobile.common.ChannelAvatar
import com.arshad.ayn.ui.mobile.common.PillButton
import com.arshad.ayn.ui.mobile.common.Picture
import com.arshad.ayn.ui.mobile.common.rememberCardFace
import com.arshad.ayn.ui.mobile.common.rememberFaceArrival
import com.arshad.ayn.ui.mobile.common.rememberPictureLight
import com.arshad.ayn.ui.mobile.theme.AynColors
import com.arshad.ayn.ui.mobile.theme.atWeight

/** The hero's height (`m6-home-and-menus` `.heroA`, the status and top bars included). */
internal val HeroHeight = 488.dp
private val ChipShape = RoundedCornerShape(6.dp)

/**
 * The hero (`m6-home-and-menus` state 1): its picture under the see-through top bar, the channel
 * with "For you" (For you's pick) or "New" (a new subscription upload), the title (its face,
 * DeArrow's when on; placeholder bars while waited for), the meta line, then Play (its shadow in the
 * picture's colour), Watch later ("In Watch later" once saved) and ⋮ ([onMore], the card menu).
 */
@Composable
internal fun HomeHero(top: HomeTop.HeroTop, onPlay: () -> Unit, onWatchLater: () -> Unit, onMore: () -> Unit, modifier: Modifier = Modifier) {
    val video = top.hero.video
    val face = rememberCardFace(video)
    val arrival = rememberFaceArrival(face != null)
    // Sampled once the picture has loaded, so the sample reads the cache.
    var loaded by remember(face?.thumbnailUrl) { mutableStateOf(false) }
    val light by rememberPictureLight(face?.thumbnailUrl?.takeIf { loaded })
    val pulse = rememberPulse()
    Box(modifier.fillMaxWidth().height(HeroHeight)) {
        if (face != null) {
            Picture(face.thumbnailUrl, Modifier.graphicsLayer { alpha = arrival.value }, onLoaded = { loaded = true })
        } else {
            WarmPool(pulse)
        }
        Box(Modifier.fillMaxSize().background(HeroScrim))
        Column(Modifier.align(Alignment.BottomStart).padding(start = 20.dp, end = 20.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // A channel not followed has no avatar on hand (For you's picks): its monogram.
                Box(Modifier.size(24.dp)) { ChannelAvatar(video.uploaderName.orEmpty(), top.avatarUrl, monogramSize = 9f) }
                Text(video.uploaderName.orEmpty(), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 8.dp))
                val chip = if (top.reason != null) "For you" else "New".takeIf { top.hero.isNew }
                if (chip != null) {
                    Text(
                        chip,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp).atWeight(700),
                        modifier = Modifier.padding(start = 10.dp).background(AynColors.Indicator, ChipShape).padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
            }
            if (face != null) {
                Text(
                    face.title,
                    style = MaterialTheme.typography.displaySmall.copy(fontSize = 38.sp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 12.dp).graphicsLayer { alpha = arrival.value },
                )
            } else {
                Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    SkeletonBar(pulse, Modifier.fillMaxWidth(0.94f).height(30.dp))
                    SkeletonBar(pulse, Modifier.fillMaxWidth(0.58f).height(30.dp))
                }
            }
            HeroMeta(top, Modifier.padding(top = 10.dp))
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                PillButton("Play", onPlay, icon = R.drawable.ic_play, glow = light)
                Crossfade(top.inWatchLater, label = "watchLater") { saved ->
                    if (saved) {
                        PillButton("In Watch later", onWatchLater, kind = ButtonKind.Tonal, icon = R.drawable.ic_check)
                    } else {
                        PillButton("Watch later", onWatchLater, kind = ButtonKind.Tonal, icon = R.drawable.ic_later)
                    }
                }
                Box(Modifier.size(44.dp).background(AynColors.Tonal, CircleShape), contentAlignment = Alignment.Center) {
                    MoreButton(onMore, tint = AynColors.Ink)
                }
            }
        }
    }
}

/** "26:24 · 1.4M views · 1 month ago · 2160p · 25 fps"; a live stream shows LIVE, not a length (D11). */
@Composable
private fun HeroMeta(top: HomeTop.HeroTop, modifier: Modifier = Modifier) {
    val video = top.hero.video
    val format = (top.hero.page as? HeroPage.Ready)?.format?.let(::formatText)
    val rest = listOfNotNull(viewsText(video.viewCount), video.ageText(top.now), format)
    val length = lengthText(video.durationSeconds).takeUnless { video.isLive }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (video.isLive) LiveBadge(Modifier.padding(end = 7.dp))
        Text(
            buildAnnotatedString {
                length?.let { withStyle(SpanStyle(color = AynColors.Ink)) { append(it) } }
                rest.forEachIndexed { i, part ->
                    if (i > 0 || length != null) withStyle(SpanStyle(color = AynColors.Ink3)) { append(MetaSeparator) }
                    append(part)
                }
            },
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.5.sp),
            color = AynColors.Ink2,
            maxLines = 1,
        )
    }
}

private fun formatText(format: VideoFormat): String =
    listOfNotNull("${format.height}p", format.fps?.let { "$it fps" }).joinToString(MetaSeparator)

// `.heroA::after`: dark under the status and top bars, the ground rising under the copy.
private val HeroScrim = Brush.verticalGradient(
    0f to AynColors.Ground.copy(alpha = 0.72f),
    0.2f to AynColors.Ground.copy(alpha = 0f),
    0.26f to AynColors.Ground.copy(alpha = 0f),
    0.42f to AynColors.Ground.copy(alpha = 0.3f),
    0.7f to AynColors.Ground.copy(alpha = 0.9f),
    0.99f to AynColors.Ground,
)

/** Warm where the picture will land, breathing (alpha only) while it is on its way. */
@Composable
private fun WarmPool(pulse: State<Float>) {
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = pulse.value }.background(PoolBrush))
}

private val PoolBrush = Brush.radialGradient(0f to AynColors.Pool, 0.72f to AynColors.Ground.copy(alpha = 0f))

/** Loading (state 5): the hero's shape under the breathing pool, then a shelf's. */
@Composable
internal fun HomeSkeleton() {
    val pulse = rememberPulse()
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(HeroHeight)) {
            WarmPool(pulse)
            Column(Modifier.align(Alignment.BottomStart).padding(start = 20.dp, end = 20.dp, bottom = 6.dp)) {
                SkeletonBar(pulse, Modifier.size(130.dp, 16.dp))
                SkeletonBar(pulse, Modifier.padding(top = 16.dp).fillMaxWidth(0.92f).height(30.dp))
                SkeletonBar(pulse, Modifier.padding(top = 9.dp).fillMaxWidth(0.56f).height(30.dp))
                SkeletonBar(pulse, Modifier.padding(top = 14.dp).size(220.dp, 12.dp))
                SkeletonBar(pulse, Modifier.padding(top = 18.dp).size(96.dp, 44.dp), CircleShape)
            }
        }
        ShelfSkeleton(pulse)
    }
}

/**
 * A statement in place of content (`m6-home-and-menus` states 7–8, set 1 state 11): a kicker with
 * its icon, the headline, what happened and what to do, then [actions] and when it was tried.
 */
@Composable
internal fun Statement(
    @DrawableRes icon: Int,
    kicker: String,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    stamp: String? = null,
    actions: @Composable () -> Unit = {},
) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(painterResource(icon), contentDescription = null, tint = AynColors.Ink2, modifier = Modifier.size(20.dp))
            Text(kicker, style = MaterialTheme.typography.titleSmall, color = AynColors.Ink2)
        }
        Text(
            title,
            style = MaterialTheme.typography.headlineLarge.let { if (compact) it.copy(fontSize = 30.sp) else it },
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(body, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp), color = AynColors.Ink2, modifier = Modifier.padding(top = 12.dp))
        Row(Modifier.padding(top = if (compact) 14.dp else 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) { actions() }
        if (stamp != null) Text(stamp, style = MaterialTheme.typography.bodySmall, color = AynColors.Ink3, modifier = Modifier.padding(top = 12.dp))
    }
}

/** Home's error (D8, set 1 state 11); a kids profile has no Library, so no [onOpenLibrary] and no word of it. */
@Composable
internal fun HomeFailed(failure: AccessFailure, stamp: String, onRetry: () -> Unit, onOpenLibrary: (() -> Unit)?, top: Dp) {
    val offline = failure == AccessFailure.OFFLINE
    Statement(
        icon = if (offline) R.drawable.ic_offline else R.drawable.ic_alert,
        kicker = if (offline) "No connection to YouTube" else "YouTube changed something",
        title = "Home couldn’t load",
        body = listOfNotNull(
            if (offline) "None of your rows came back. Check that the phone is online, then try again." else UnreadableReply,
            onOpenLibrary?.let { if (offline) "Your library and settings still work offline." else "Your library and settings still work." },
        ).joinToString(" "),
        stamp = stamp,
        // Scrollable, so a pull at the top reaches Home's pull to refresh (M6-D11).
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 28.dp, end = 28.dp, top = top + 122.dp),
    ) {
        PillButton("Try again", onRetry, icon = R.drawable.ic_retry)
        onOpenLibrary?.let { PillButton("Open Library", it, kind = ButtonKind.Tonal, icon = R.drawable.ic_nav_library) }
    }
}

/** What an unreadable reply means for the user (D8). */
internal const val UnreadableReply = "The phone is online, but Ayn couldn’t read YouTube’s reply. This usually needs an Ayn update."
