package com.arshad.ayn.ui.tv.channel

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.arshad.ayn.core.access.ChannelItem
import com.arshad.ayn.core.access.ChannelTab
import com.arshad.ayn.feature.channel.tabLabel
import com.arshad.ayn.ui.tv.R
import com.arshad.ayn.ui.tv.common.AvatarPicture
import com.arshad.ayn.ui.tv.common.LineShape
import com.arshad.ayn.feature.format.MetaSeparator
import com.arshad.ayn.ui.tv.common.Picture
import com.arshad.ayn.ui.tv.common.PillButtonContent
import com.arshad.ayn.ui.tv.common.PillButtonHeight
import com.arshad.ayn.ui.tv.common.backdropScrims
import com.arshad.ayn.feature.format.blurb
import com.arshad.ayn.feature.format.latestVideosText
import com.arshad.ayn.ui.tv.common.leadingRule
import com.arshad.ayn.ui.tv.common.skeleton
import com.arshad.ayn.feature.format.subscribersText
import com.arshad.ayn.ui.tv.focus.ControlStyle
import com.arshad.ayn.ui.tv.focus.ControlSurface
import com.arshad.ayn.ui.tv.focus.drawInflated
import com.arshad.ayn.ui.tv.focus.focusMemory
import com.arshad.ayn.ui.tv.theme.KeColors
import com.arshad.ayn.ui.tv.theme.KeLayout
import com.arshad.ayn.ui.tv.theme.KeMotion
import com.arshad.ayn.ui.tv.theme.atWeight
import com.arshad.ayn.ui.tv.theme.weightSpan

// b3-channel header geometry (px ÷ 2): the header is 360 dp tall with its avatar 95 dp down; the
// banner sits 280 dp right of centre at 112%, faded in from its left edge over 30% of its width.
internal val HeaderHeight = 360.dp
private val HeaderTop = 95.dp
private val AvatarSize = 56.dp
private val AvatarRing = 1.5.dp
private val NameTop = 12.dp
private val MetaTop = 11.dp
private val BlurbTop = 7.dp
private val BlurbWidth = 380.dp
private val ActionsTop = 18.dp
private val BannerShift = 280.dp
private const val BannerScale = 1.12f
private const val BannerFoldedScale = 1.2f
private const val BannerFoldedAlpha = 0.1f
private const val BannerFadeEnd = 0.3f
private val NoBannerHeight = 310.dp
private val MiniAvatar = 28.dp
private val MiniRise = 5.dp
private val MonogramSize = 22.sp
private val MiniMonogramSize = 11.sp

// b3-channel `.nobanner` (oklch 22% 0.025 40 → 17% 0.012 50 at 60% → ground).
private val NoBannerTop = Color(0xFF251712)
private val NoBannerMid = Color(0xFF140E0B)
private val AvatarDrop = Color.Black.copy(alpha = 0.8f)

/**
 * The channel's banner full-bleed behind the page (uncropped 16:9 art, `ChannelItem.bannerUrl`),
 * set right and faded in from its left so the header reads over the ground; as the header
 * [fold]s away it dims and grows. Without a banner, a warm wash at the top.
 */
@Composable
internal fun ChannelBackdrop(bannerUrl: String?, fold: State<Float>, onBannerLoaded: () -> Unit) {
    if (bannerUrl == null) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(NoBannerHeight)
                .background(Brush.verticalGradient(0f to NoBannerTop, 0.6f to NoBannerMid, 1f to KeColors.Ground)),
        )
        return
    }
    Box(Modifier.fillMaxSize().backdropScrims()) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val scale = BannerScale + (BannerFoldedScale - BannerScale) * fold.value
                    scaleX = scale
                    scaleY = scale
                    translationX = BannerShift.toPx()
                    alpha = 1f - (1f - BannerFoldedAlpha) * fold.value
                    transformOrigin = TransformOrigin(0.5f, 0.4f)
                }
                // The mockup masks the picture's left edge to transparent; over the ground,
                // drawing the ground across it is the same picture without an offscreen layer.
                .drawWithCache {
                    val fade = Brush.horizontalGradient(0f to KeColors.Ground, BannerFadeEnd to KeColors.Ground.copy(alpha = 0f))
                    onDrawWithContent {
                        drawContent()
                        drawRect(fade)
                    }
                },
        ) {
            Picture(bannerUrl, onLoaded = onBannerLoaded)
        }
    }
}

/**
 * The header's copy: avatar, name, subscribers with the count of [latestVideos], the description's
 * first paragraph, and Subscribe (the page's one action, lit by the banner's [light]; none
 * without [onToggleSubscribe]).
 */
@Composable
internal fun HeaderCopy(
    channel: ChannelItem,
    subscribed: Boolean,
    latestVideos: Int?,
    light: Color,
    onToggleSubscribe: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(start = KeLayout.ContentStart, top = HeaderTop, end = KeLayout.SafeHorizontal)) {
        ChannelAvatar(channel.name, channel.avatarUrl)
        Text(
            text = channel.name,
            style = MaterialTheme.typography.displayLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = NameTop),
        )
        HeaderMeta(channel, latestVideos, Modifier.padding(top = MetaTop))
        channel.description?.let(::blurb)?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyLarge,
                color = KeColors.Ink2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = BlurbTop).widthIn(max = BlurbWidth),
            )
        }
        onToggleSubscribe?.let { SubscribeButton(subscribed, light, it, Modifier.padding(top = ActionsTop)) }
    }
}

/** "4.5M subscribers · 30 latest videos": the count in ink, the rest dimmer. */
@Composable
private fun HeaderMeta(channel: ChannelItem, latestVideos: Int?, modifier: Modifier = Modifier) {
    val subscribers = subscribersText(channel.subscriberCount)
    val latest = latestVideos?.let(::latestVideosText)
    if (subscribers == null && latest == null) return
    Text(
        text = buildAnnotatedString {
            subscribers?.let { withStyle(weightSpan(600, KeColors.Ink)) { append(it) } }
            latest?.let {
                if (subscribers != null) withStyle(SpanStyle(color = KeColors.Ink3)) { append(MetaSeparator) }
                append(it)
            }
        },
        style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
        color = KeColors.Ink2,
        maxLines = 1,
        modifier = modifier,
    )
}

/**
 * Subscribed rests as a soft fill with a check, Subscribe as the primary keyline with a plus;
 * the label crossfades when it flips (D8).
 */
@Composable
private fun SubscribeButton(subscribed: Boolean, light: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ControlSurface(
        onClick = onClick,
        style = if (subscribed) ControlStyle.Secondary else ControlStyle.Primary,
        light = light,
        modifier = modifier.focusMemory(SubscribeKey).height(PillButtonHeight),
    ) {
        Crossfade(subscribed, animationSpec = KeMotion.fast(), label = "subscribed") { on ->
            if (on) PillButtonContent(R.drawable.ic_check, "Subscribed") else PillButtonContent(R.drawable.ic_plus, "Subscribe")
        }
    }
}

/** The channel's avatar in a faint ring over a dark drop. */
@Composable
private fun ChannelAvatar(name: String, url: String?, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(AvatarSize)
            .dropShadow(CircleShape) {
                radius = 25.dp.toPx()
                spread = (-7).dp.toPx()
                offset = Offset(0f, 10.dp.toPx())
                color = AvatarDrop
            }
            .drawBehind { drawInflated(CircleShape, AvatarRing, Color.White, 0.14f) },
    ) {
        AvatarPicture(name, url, MonogramSize)
    }
}

/**
 * The folded header (v8 uploads): the avatar, name and selected tab where the page title sits,
 * appearing as the header [fold]s away. Decorative: the header's semantics carry the name.
 */
@Composable
internal fun MiniTitle(channel: ChannelItem, tab: ChannelTab, fold: State<Float>) {
    Row(
        modifier = Modifier
            .padding(start = KeLayout.ContentStart, top = KeLayout.SafeVertical)
            .graphicsLayer {
                alpha = fold.value
                translationY = MiniRise.toPx() * (1f - fold.value)
            }
            .clearAndSetSemantics {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(MiniAvatar)) { AvatarPicture(channel.name, channel.avatarUrl, MiniMonogramSize) }
        Text(channel.name, style = MaterialTheme.typography.headlineSmall.copy(fontSize = 22.sp), maxLines = 1)
        Text(
            text = tabLabel(tab),
            style = MaterialTheme.typography.titleLarge.atWeight(600).copy(fontSize = 15.sp),
            color = KeColors.Ink2,
            modifier = Modifier
                .padding(start = 9.dp)
                .leadingRule(10.dp),
        )
    }
}

/** The fold's top scrim: the ground over whatever scrolls up behind the mini title. */
@Composable
internal fun TopScrim(fold: State<Float>) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(TopScrimHeight)
            .graphicsLayer { alpha = fold.value }
            .background(Brush.verticalGradient(0f to KeColors.Ground, TopScrimSolid to KeColors.Ground, 1f to KeColors.Ground.copy(alpha = 0f))),
    )
}

// b3-channel `.topscrim`: solid ground to 95 dp, gone by 130 dp.
private val TopScrimHeight = 130.dp
private const val TopScrimSolid = 95f / 130f

/** v8 loading: the header in its final shape under the screen's [sheen] (nothing focusable). */
@Composable
internal fun HeaderSkeleton(sheen: State<Float>, modifier: Modifier = Modifier) {
    Column(modifier.padding(start = KeLayout.ContentStart, top = HeaderTop)) {
        Spacer(Modifier.size(AvatarSize).skeleton(sheen, CircleShape))
        Spacer(Modifier.padding(top = 15.dp).size(260.dp, 42.dp).skeleton(sheen, RoundedCornerShape(8.dp)))
        Spacer(Modifier.padding(top = 15.dp).size(210.dp, 13.dp).skeleton(sheen, LineShape))
        Spacer(Modifier.padding(top = 11.dp).size(320.dp, 13.dp).skeleton(sheen, LineShape))
        Spacer(Modifier.padding(top = 7.dp).size(235.dp, 13.dp).skeleton(sheen, LineShape))
        Spacer(Modifier.padding(top = 20.dp).size(115.dp, PillButtonHeight).skeleton(sheen, CircleShape))
    }
}
