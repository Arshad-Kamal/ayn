package com.arshad.ayn.ui.mobile.player

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Precision
import com.arshad.ayn.core.access.SegmentCategory
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.feature.format.metaLine
import com.arshad.ayn.feature.format.viewsText
import com.arshad.ayn.feature.player.skipControlText
import com.arshad.ayn.ui.mobile.R
import com.arshad.ayn.ui.mobile.common.ButtonKind
import com.arshad.ayn.ui.mobile.common.Picture
import com.arshad.ayn.ui.mobile.common.PillButton
import com.arshad.ayn.ui.mobile.common.pressScale
import com.arshad.ayn.ui.mobile.common.rememberCardFace
import com.arshad.ayn.ui.mobile.theme.AynColors
import com.arshad.ayn.ui.mobile.theme.AynMotion
import com.arshad.ayn.ui.mobile.theme.atWeight

/** The picture's dark shade (`oklch(8% 0.006 65)`): the chrome's scrim and the end card's dim. */
internal val Shade = Color(0xFF0E0B09)
private val SmallButton = 36.dp

/** The picture's box (`.pl`): 16:9, black, full width. */
@Composable
internal fun PictureBox(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black), content = content)
}

/** The card's picture while the stream starts (`m6-player` 1): dimmed, Starting under a spinner
 *  ([note]; none on a Short coming in beside the one playing). */
@Composable
internal fun BoxScope.OpeningCover(picture: String?, note: Boolean = true) {
    Box(Modifier.matchParentSize()) {
        Picture(picture)
        Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.58f)))
        if (note) Column(
            Modifier.align(Alignment.Center).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Spinner(30.dp)
            Text("Starting", style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.5.sp).atWeight(620), color = AynColors.Ink2)
        }
    }
}

@Composable
internal fun Spinner(size: Dp, modifier: Modifier = Modifier) {
    CircularProgressIndicator(
        modifier = modifier.size(size),
        color = AynColors.Ink,
        trackColor = Color.White.copy(alpha = 0.18f),
        strokeWidth = if (size >= 40.dp) 3.5.dp else 3.dp,
    )
}

/** The hub's state: what its button does, or the spinner while loading. */
internal enum class Hub { Pause, Play, Replay, Loading }

/**
 * The controls' Skip (PAF-D12), shown while playback is in a [skippable] span: it grows in and
 * fades out, and [content] gets the last category through the exit so its label and mark stay
 * while it fades.
 */
@Composable
internal fun SkipReveal(skippable: SegmentCategory?, content: @Composable (SegmentCategory) -> Unit) {
    AnimatedVisibility(
        visible = skippable != null,
        enter = fadeIn(AynMotion.base()) + scaleIn(AynMotion.base(), initialScale = 0.9f),
        exit = fadeOut(AynMotion.base()) + scaleOut(AynMotion.base(), targetScale = 0.9f),
    ) {
        // A plain holder: only this scope reads it, and it changes only with skippable, which recomposes it.
        val last = remember { arrayOfNulls<SegmentCategory>(1) }
        skippable?.let { last[0] = it }
        last[0]?.let { content(it) }
    }
}

/** The CC button's state: dimmed while the video has no tracks, lit while captions show. */
internal enum class Cc { None, Off, On }

/**
 * The chrome over the picture (`m6-player` 3, 6, 7, 13), shown while [shown]: ⌄ docks, CC opens
 * the Captions sheet ([cc]), ⚙ the Playback sheet; back 10 · the [hub] · forward 10, the time,
 * length and chapter at the foot, full screen ([onFullScreen]) beside them, while [footShown] (a
 * screen reader's corner takes their place, Q58). While a scrub runs ([scrubbing]) the transport
 * and the times fade so the preview reads alone. While playback is in a span the skip prompt offers
 * ([skippable], its first category), Skip leads the top-right pair with that category's mark
 * (`m6-player` 18b, PAF-D12); a tap runs [onSkip].
 */
@Composable
internal fun BoxScope.PictureChrome(
    shown: Boolean,
    hub: Hub,
    cc: Cc,
    footShown: Boolean,
    scrubbing: Boolean,
    times: @Composable () -> Unit,
    onDock: () -> Unit,
    onOpenCaptions: () -> Unit,
    onOpenPlayback: () -> Unit,
    onHub: () -> Unit,
    onSeekBy: (forward: Boolean) -> Unit,
    onFullScreen: () -> Unit,
    skippable: SegmentCategory? = null,
    onSkip: () -> Unit = {},
) {
    AnimatedVisibility(shown, Modifier.matchParentSize(), enter = fadeIn(AynMotion.base()), exit = fadeOut(AynMotion.base())) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier.matchParentSize().background(
                    Brush.verticalGradient(
                        0f to Shade.copy(alpha = 0.6f),
                        0.4f to Shade.copy(alpha = 0.3f),
                        0.6f to Shade.copy(alpha = 0.3f),
                        1f to Shade.copy(alpha = 0.7f),
                    ),
                ),
            )
            PictureButton(R.drawable.ic_chevron_down, "Minimize", onDock, Modifier.align(Alignment.TopStart).padding(start = 4.dp, top = 2.dp))
            Row(Modifier.align(Alignment.TopEnd).padding(end = 4.dp, top = 2.dp)) {
                SkipReveal(skippable) { category ->
                    PictureButton(R.drawable.ic_skip, skipControlText(category), onSkip, enabled = skippable != null) {
                        Box(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 7.dp)
                                .size(width = 18.dp, height = 2.dp)
                                .background(category.color, RoundedCornerShape(1.dp)),
                        )
                    }
                }
                CaptionsButton(cc, onOpenCaptions)
                PictureButton(R.drawable.ic_gear, "Playback settings", onOpenPlayback)
            }
            val transportAlpha = if (scrubbing) 0f else 1f
            Row(
                Modifier.align(Alignment.Center).graphicsLayer { alpha = transportAlpha },
                horizontalArrangement = Arrangement.spacedBy(40.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SkipButton(R.drawable.ic_back10, "Back 10 seconds") { onSeekBy(false) }
                HubButton(hub, onClick = onHub)
                SkipButton(R.drawable.ic_fwd10, "Forward 10 seconds") { onSeekBy(true) }
            }
            // Gone, not only faded, so a screen reader can't land on what the corner covers.
            AnimatedVisibility(footShown, Modifier.align(Alignment.BottomStart), enter = fadeIn(AynMotion.base()), exit = fadeOut(AynMotion.base())) {
                // Its 48 dp target centred above the bar's 28 dp strip, which lies over it and would take the tap.
                Row(
                    Modifier.padding(start = 14.dp, end = 4.dp, bottom = 10.dp).graphicsLayer { alpha = transportAlpha },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) { times() }
                    PictureButton(R.drawable.ic_fullscreen, "Full screen", onFullScreen, size = SmallButton, iconSize = 18.dp)
                }
            }
        }
    }
}

/** A 44 dp round button on the picture, read out as [label] (and its [state]); it sinks while pressed. */
@Composable
internal fun PictureButton(
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    iconSize: Dp = 22.dp,
    fill: Color = Color.Transparent,
    enabled: Boolean = true,
    state: String? = null,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .minimumInteractiveComponentSize()
            .size(size)
            .pressScale(interaction)
            .clip(CircleShape)
            .background(fill)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = label
                state?.let { stateDescription = it }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = AynColors.Ink, modifier = Modifier.size(iconSize))
        content()
    }
}

/** CC: dimmed and inert while the video has no tracks; a short underline while captions show (`.ib.lit`). */
@Composable
private fun CaptionsButton(cc: Cc, onClick: () -> Unit) {
    val dim by animateFloatAsState(if (cc == Cc.None) 0.4f else 1f, AynMotion.base(), label = "ccDim")
    val lit by animateFloatAsState(if (cc == Cc.On) 1f else 0f, AynMotion.base(), label = "ccLit")
    PictureButton(
        R.drawable.ic_cc,
        "Captions",
        onClick,
        Modifier.graphicsLayer { alpha = dim },
        enabled = cc != Cc.None,
        state = when (cc) {
            Cc.On -> "On"
            Cc.Off -> "Off"
            Cc.None -> null
        },
    ) {
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 7.dp)
                .size(width = 18.dp, height = 2.dp)
                .graphicsLayer {
                    alpha = lit
                    scaleX = lit
                }
                .background(AynColors.Ink, RoundedCornerShape(1.dp)),
        )
    }
}

/** −10 s / +10 s: the arc with its "10" (the mockups' SVG text). */
@Composable
internal fun SkipButton(@DrawableRes icon: Int, label: String, size: Dp = 48.dp, iconSize: Dp = 28.dp, onClick: () -> Unit) {
    PictureButton(icon, label, onClick, size = size, iconSize = iconSize) {
        Text("10", style = MaterialTheme.typography.labelSmall.atWeight(700).copy(fontSize = 8.sp), modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
internal fun HubButton(hub: Hub, size: Dp = 60.dp, iconSize: Dp = 30.dp, onClick: () -> Unit) {
    if (hub == Hub.Loading) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) { Spinner(44.dp) }
        return
    }
    val (icon, label) = when (hub) {
        Hub.Pause -> R.drawable.ic_pause to "Pause"
        Hub.Play -> R.drawable.ic_play to "Play"
        else -> R.drawable.ic_restart to "Replay"
    }
    PictureButton(icon, label, onClick, size = size, iconSize = iconSize, fill = Shade.copy(alpha = 0.5f))
}

/** "34:51 / 47:42 · Breaking Enigma - The Bombe" at the chrome's foot. */
@Composable
internal fun ChromeTimes(position: String, length: String, chapter: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(position, style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.5.sp, fontFeatureSettings = "tnum").atWeight(650))
        Text(" / $length", style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.5.sp, fontFeatureSettings = "tnum"), color = AynColors.Ink2)
        chapter?.let {
            Text(
                " · $it",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.5.sp),
                color = AynColors.Ink2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 220.dp),
            )
        }
    }
}

/** A double-tap's ripple on one side (`m6-player` 5): the chevrons and how far the taps went, or
 *  Live where the seek rejoins a live edge ([rejoinsLive]). */
@Composable
internal fun BoxScope.SeekRipple(forward: Boolean?, seconds: Int, rejoinsLive: Boolean) {
    AnimatedVisibility(
        visible = forward != null,
        modifier = Modifier.align(if (forward == false) Alignment.CenterStart else Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.44f),
        enter = fadeIn(AynMotion.press()),
        exit = fadeOut(AynMotion.base()),
    ) {
        val back = forward == false
        val shape = if (back) RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50) else RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50)
        Column(
            Modifier.fillMaxSize().background(AynColors.Ink.copy(alpha = 0.2f), shape),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Row(Modifier.graphicsLayer { scaleX = if (back) -1f else 1f }) {
                listOf(0.45f, 0.7f, 1f).forEach { alpha ->
                    Icon(painterResource(R.drawable.ic_chevron), contentDescription = null, tint = AynColors.Ink.copy(alpha = alpha), modifier = Modifier.size(24.dp).padding(horizontal = 0.dp))
                }
            }
            Text(
                when {
                    rejoinsLive -> "Live"
                    back -> "−$seconds seconds"
                    else -> "+$seconds seconds"
                },
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.5.sp, fontFeatureSettings = "tnum").atWeight(680),
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/**
 * A single video's end (`m6-player` 11, 34; Q27): the first related video under "Up next" with
 * Play and Cancel over the dimmed last frame, [large] on a sideways screen; nothing starts by itself.
 */
@Composable
internal fun BoxScope.EndCard(next: VideoItem, onPlay: () -> Unit, onCancel: () -> Unit, large: Boolean = false) {
    Box(Modifier.matchParentSize().background(Shade.copy(alpha = 0.72f)))
    val face = rememberCardFace(next)
    val button = if (large) 44.dp else SmallButton
    Row(
        Modifier
            .align(Alignment.Center)
            .padding(horizontal = if (large) 120.dp else 16.dp)
            .semantics(mergeDescendants = false) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(if (large) 260.dp else 150.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(10.dp))
                .background(AynColors.Surface2),
        ) { Picture(face?.thumbnailUrl ?: next.thumbnailUrl) }
        Column(Modifier.weight(1f)) {
            Text("Up next", style = MaterialTheme.typography.labelSmall.atWeight(650), color = AynColors.Ink2)
            Text(
                face?.title ?: next.title,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = if (large) 20.sp else 14.5.sp, lineHeight = 1.22.em).atWeight(640),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
            Text(
                metaLine(next.uploaderName, viewsText(next.viewCount)),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = AynColors.Ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Play", onPlay, Modifier.height(button), icon = R.drawable.ic_play)
                PillButton("Cancel", onCancel, Modifier.height(button), kind = ButtonKind.Tonal)
            }
        }
    }
}

// A small decode drawn at the box's size: the blur Modifier.blur can't give on API 30.
private const val BlurDecodeHeight = 24
private val Dim = ColorFilter.colorMatrix(
    ColorMatrix().apply {
        setToSaturation(0.85f)
        timesAssign(ColorMatrix().apply { setToScale(0.35f, 0.35f, 0.35f, 1f) })
    },
)

/** [picture] blurred and dark over the box, behind a statement. */
@Composable
internal fun BoxScope.BlurredPicture(picture: String?) {
    if (picture == null) return
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current).data(picture).size(BlurDecodeHeight).precision(Precision.EXACT).build(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        colorFilter = Dim,
        modifier = Modifier.matchParentSize().graphicsLayer {
            scaleX = 1.1f
            scaleY = 1.1f
        },
    )
}

/**
 * Why the video doesn't play (`m6-player` 26–28), in the picture's box over its own picture,
 * blurred and dark: what happened, a headline, one line, and Try again when it can help. ⌄ closes
 * the player.
 */
@Composable
internal fun BoxScope.PictureStatement(copy: StopCopy, picture: String?, onRetry: (() -> Unit)?, onClose: () -> Unit) {
    BlurredPicture(picture)
    Column(
        Modifier
            .matchParentSize()
            .padding(start = 22.dp, end = 22.dp, top = 50.dp)
            .semantics(mergeDescendants = false) { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            StopMark(copy)
            Text(copy.kicker, style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.5.sp).atWeight(650), color = AynColors.Ink2)
        }
        Text(
            copy.title,
            style = MaterialTheme.typography.headlineMedium.copy(fontSize = 26.sp),
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            copy.body,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.5.sp),
            color = AynColors.Ink2,
            modifier = Modifier.padding(top = 8.dp).widthIn(max = 330.dp),
        )
        if (onRetry != null && copy.retryLabel != null) {
            Spacer(Modifier.height(14.dp))
            PillButton(copy.retryLabel, onRetry, Modifier.height(SmallButton), icon = R.drawable.ic_retry)
        }
    }
    PictureButton(R.drawable.ic_chevron_down, "Close", onClose, Modifier.align(Alignment.TopStart).padding(start = 4.dp, top = 2.dp))
}

/** A statement's kicker mark: its icon, or a live stream's LIVE tag (an ended stream's outline is
 *  left unread: its kicker says it ended). */
@Composable
internal fun StopMark(copy: StopCopy, modifier: Modifier = Modifier) {
    when (copy.liveMark) {
        null -> Icon(painterResource(copy.icon), contentDescription = null, tint = AynColors.Ink2, modifier = modifier.size(16.dp))
        LiveMark.Lit -> LiveTag(lit = true, modifier)
        LiveMark.Outlined -> LiveTag(lit = false, modifier.clearAndSetSemantics { })
    }
}
