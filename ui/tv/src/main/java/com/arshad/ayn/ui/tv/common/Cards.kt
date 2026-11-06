package com.arshad.ayn.ui.tv.common

import com.arshad.ayn.feature.format.lengthText
import com.arshad.ayn.feature.format.videoCountText
import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.arshad.ayn.core.access.PlaylistItem
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.ui.tv.R
import com.arshad.ayn.ui.tv.focus.AvatarSurface
import com.arshad.ayn.ui.tv.focus.CardShape
import com.arshad.ayn.ui.tv.focus.ContentSurface
import com.arshad.ayn.ui.tv.focus.FocusSlot
import com.arshad.ayn.ui.tv.focus.drawKeyline
import com.arshad.ayn.ui.tv.focus.focusMemory
import com.arshad.ayn.ui.tv.theme.KeColors
import com.arshad.ayn.ui.tv.theme.KeMotion
import com.arshad.ayn.ui.tv.theme.atWeight

// Card grids (b3-search, b3-subscriptions, b3-channel; px ÷ 2): 180 dp cells 15 apart, rows 22
// apart, titles at 12.5 sp.
internal val GridCellWidth = 180.dp
internal val GridColumnGap = 15.dp
internal val GridRowGap = 22.dp
internal val GridCardTitleSize = 12.5.sp

/** Cards in one grid row (Search's and Subscriptions' grids; a Shorts shelf follows the first). */
internal const val GridColumns = 4

// `.card.short` (px ÷ 2): 115 dp wide, its picture 9:16.
internal val ShortCardWidth = 115.dp
private const val ShortAspect = 9f / 16f

// Master mockup card geometry (px ÷ 2).
internal val VideoCardWidth = 200.dp
internal const val ThumbAspect = 16f / 9f
private val TitleTop = 8.dp
private val SecondMetaTop = 1.dp
private val MetaTop = 3.dp
private val FocusedTextShift = 4.dp
private val BadgeInset = 6.dp
private val BadgeBottom = 7.dp
private val BadgeShape = RoundedCornerShape(3.5.dp)
internal val ChannelCardWidth = 84.dp
private val AvatarSize = 74.dp

// The strip's and the Channel header's monogram-to-avatar ratio (~0.38).
private val MonogramSize = 28.sp
private val ChannelNameTop = 9.dp
private val ChannelMetaTop = 2.dp
private val TileAvatarSize = 75.dp
private val TileAvatarRing = 2.dp
private const val TileAvatarFocusedScale = 1.06f
private val TileTagInset = 7.dp

// b3-search channel cell: a deep blue pool behind the avatar (oklch 30% 0.06 260 → 18% 0.02 260).
private val TileGlow = Color(0xFF1F2B48)
private val TileEdge = Color(0xFF14171F)

// Master `.stack` (px ÷ 2): the cover sits 7 dp down; two 10 dp bars peek above it, the back one
// rising 5 dp and the front one 3 dp on focus.
private val StackTop = 7.dp
private val StackBarGap = 3.dp
private val StackBarHeight = 10.dp
private val StackBarRadius = 6.dp
private val StackBackRise = 5.dp
private val StackFrontRise = 3.dp
private const val StackBarAlpha = 0.1f

private val ChannelNameSize = 12.sp
private val LiveBadgeTracking = 0.04.em

/**
 * A video card: thumbnail (content focus family, LIVE replacing the length, D11), title, [meta]
 * and [secondMeta] below; [progress] (0..1) draws how far the profile got. Picture and title are
 * the video's face ([rememberCardFace]): skeletons while DeArrow is waited for, then faded in.
 * While focused it sits above its neighbours (its lights overlap them) and reports its light and
 * centre ([onFocused], root px) so the screen can light the room. Holding OK calls [onLongClick]
 * (the card menu). [focusSlot] is its place in a row whose cards can leave it ([focusMemory]).
 */
@Composable
internal fun VideoCard(
    video: VideoItem,
    meta: String?,
    focusKey: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = VideoCardWidth,
    titleStyle: TextStyle = MaterialTheme.typography.titleSmall,
    secondMeta: String? = null,
    progress: Float? = null,
    rowFocused: () -> Boolean = { false },
    onFocused: (light: State<Color>, centerX: Float) -> Unit = { _, _ -> },
    onLongClick: (() -> Unit)? = null,
    focusSlot: FocusSlot? = null,
) {
    // Sampled once the card first takes focus: only a focused card spills its light. The State
    // goes up whole, so a sample landing after focus still reaches the room.
    var sample by remember { mutableStateOf(false) }
    val face = rememberCardFace(video)
    val arrival = rememberFaceArrival(face != null)
    val sheen = if (face == null) rememberSheen() else null
    val lightState = rememberPictureLight(face?.thumbnailUrl.takeIf { sample })
    val light by lightState
    CardColumn(
        width = width,
        modifier = modifier,
        onFocused = { centerX ->
            sample = true
            onFocused(lightState, centerX)
        },
    ) { focused, textShift ->
        ContentSurface(
            onClick = onClick,
            light = light,
            dimmed = rowFocused,
            onLongClick = onLongClick,
            modifier = Modifier.focusMemory(focusKey, focusSlot).fillMaxWidth().height(width / ThumbAspect),
        ) {
            FacePicture(face, arrival, sheen)
            progress?.let { WatchBar(it, WatchBarSize.Card, Modifier.align(Alignment.BottomCenter)) }
            VideoBadge(
                video,
                Modifier.align(Alignment.BottomEnd).padding(end = BadgeInset, bottom = if (progress != null) WatchBarSize.Card.badgeBottom else BadgeBottom),
            )
        }
        if (face != null) {
            CardText(
                text = face.title,
                style = titleStyle,
                color = if (focused) KeColors.Ink else KeColors.Ink2,
                maxLines = 2,
                modifier = Modifier.padding(top = TitleTop).graphicsLayer {
                    translationY = textShift()
                    alpha = arrival.value
                },
            )
        } else if (sheen != null) {
            TitleSkeleton(titleStyle, sheen, Modifier.padding(top = TitleTop))
        }
        meta?.let {
            CardText(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = KeColors.Ink3,
                maxLines = 1,
                modifier = Modifier.padding(top = MetaTop).graphicsLayer { translationY = textShift() },
            )
        }
        secondMeta?.let {
            CardText(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = KeColors.Ink3,
                maxLines = 1,
                modifier = Modifier.padding(top = SecondMetaTop).graphicsLayer { translationY = textShift() },
            )
        }
    }
}

/**
 * A Short's card (`.card.short`): its vertical picture, title, [meta] and [secondMeta] below. It
 * shows YouTube's title and picture: DeArrow has no Shorts data, so a Short never asks for its
 * face (M7-D13, Q40). Lights the room and opens the card menu ([onLongClick]) as [VideoCard] does.
 */
@Composable
internal fun ShortCard(
    video: VideoItem,
    meta: String?,
    focusKey: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    secondMeta: String? = null,
    rowFocused: () -> Boolean = { false },
    onFocused: (light: State<Color>, centerX: Float) -> Unit = { _, _ -> },
    onLongClick: (() -> Unit)? = null,
) {
    var sample by remember { mutableStateOf(false) }
    val lightState = rememberPictureLight(video.thumbnailUrl.takeIf { sample })
    val light by lightState
    CardColumn(
        width = ShortCardWidth,
        modifier = modifier,
        onFocused = { centerX ->
            sample = true
            onFocused(lightState, centerX)
        },
    ) { focused, textShift ->
        ContentSurface(
            onClick = onClick,
            light = light,
            dimmed = rowFocused,
            onLongClick = onLongClick,
            modifier = Modifier.focusMemory(focusKey).fillMaxWidth().height(ShortCardWidth / ShortAspect),
        ) {
            Picture(video.thumbnailUrl)
        }
        CardText(
            text = video.title,
            style = MaterialTheme.typography.titleSmall.copy(fontSize = GridCardTitleSize),
            color = if (focused) KeColors.Ink else KeColors.Ink2,
            maxLines = 2,
            modifier = Modifier.padding(top = TitleTop).graphicsLayer { translationY = textShift() },
        )
        listOfNotNull(meta, secondMeta).forEachIndexed { index, line ->
            CardText(
                text = line,
                style = MaterialTheme.typography.bodySmall,
                color = KeColors.Ink3,
                maxLines = 1,
                modifier = Modifier.padding(top = if (index == 0) MetaTop else SecondMetaTop).graphicsLayer { translationY = textShift() },
            )
        }
    }
}

/**
 * A channel among video cards (`b3-search` channel cell): its avatar on a 16:9 tile tagged
 * "Channel", the name and [meta] below. Its light is the avatar's, sampled on first focus.
 */
@Composable
internal fun ChannelTileCard(
    name: String,
    avatarUrl: String?,
    meta: String?,
    focusKey: String,
    onClick: () -> Unit,
    width: Dp,
    modifier: Modifier = Modifier,
    titleStyle: TextStyle = MaterialTheme.typography.titleSmall,
    onFocused: (light: State<Color>, centerX: Float) -> Unit = { _, _ -> },
) {
    var sample by remember { mutableStateOf(false) }
    val lightState = rememberPictureLight(avatarUrl.takeIf { sample })
    val light by lightState
    CardColumn(
        width = width,
        modifier = modifier,
        onFocused = { centerX ->
            sample = true
            onFocused(lightState, centerX)
        },
    ) { focused, textShift ->
        val avatarScale by animateFloatAsState(if (focused) TileAvatarFocusedScale else 1f, KeMotion.base(), label = "tileAvatar")
        ContentSurface(
            onClick = onClick,
            light = light,
            modifier = Modifier
                .focusMemory(focusKey)
                .fillMaxWidth()
                .height(width / ThumbAspect),
        ) {
            Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(TileGlow, TileEdge))))
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(TileAvatarSize)
                    .graphicsLayer {
                        scaleX = avatarScale
                        scaleY = avatarScale
                    }
                    .border(TileAvatarRing, KeColors.Ink.copy(alpha = 0.12f), CircleShape)
                    .clip(CircleShape),
            ) {
                Picture(avatarUrl)
            }
            Text(
                text = "Channel",
                style = MaterialTheme.typography.labelSmall,
                color = KeColors.Ink,
                modifier = Modifier
                    .padding(TileTagInset)
                    .background(KeColors.Ground.copy(alpha = 0.6f), BadgeShape)
                    .padding(start = 5.dp, end = 5.dp, top = 1.5.dp, bottom = 2.dp),
            )
        }
        CardText(
            text = name,
            style = titleStyle,
            color = if (focused) KeColors.Ink else KeColors.Ink2,
            maxLines = 1,
            modifier = Modifier.padding(top = TitleTop).graphicsLayer { translationY = textShift() },
        )
        meta?.let {
            CardText(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = KeColors.Ink3,
                maxLines = 1,
                modifier = Modifier.padding(top = MetaTop).graphicsLayer { translationY = textShift() },
            )
        }
    }
}

/** A YouTube playlist among cards: [PlaylistCoverCard] with its video count and "Playlist". */
@Composable
internal fun PlaylistCard(
    playlist: PlaylistItem,
    focusKey: String,
    onClick: () -> Unit,
    width: Dp,
    modifier: Modifier = Modifier,
    titleStyle: TextStyle = MaterialTheme.typography.titleSmall,
) {
    PlaylistCoverCard(
        title = playlist.title,
        coverUrl = playlist.thumbnailUrl,
        count = videoCountText(playlist.videoCount),
        meta = "Playlist",
        focusKey = focusKey,
        onClick = onClick,
        width = width,
        modifier = modifier,
        titleStyle = titleStyle,
    )
}

/**
 * A playlist among cards (master `.card.pl`): its cover over two bars edge-on behind it (the
 * playlist's other videos), which rise a little while it is focused; [count] where a video's
 * length would sit, then the title and [meta]. Its light is the cover's, sampled on first focus;
 * a playlist with no cover shows the list icon.
 */
@Composable
internal fun PlaylistCoverCard(
    title: String,
    coverUrl: String?,
    count: String?,
    meta: String,
    focusKey: String,
    onClick: () -> Unit,
    width: Dp,
    modifier: Modifier = Modifier,
    titleStyle: TextStyle = MaterialTheme.typography.titleSmall,
    onFocused: (light: State<Color>, centerX: Float) -> Unit = { _, _ -> },
) {
    var sample by remember { mutableStateOf(false) }
    val lightState = rememberPictureLight(coverUrl.takeIf { sample })
    val light by lightState
    CardColumn(
        width = width,
        modifier = modifier,
        onFocused = { centerX ->
            sample = true
            onFocused(lightState, centerX)
        },
    ) { focused, textShift ->
        val rise = animateFloatAsState(if (focused) 1f else 0f, KeMotion.base(), label = "stack")
        Box(
            Modifier
                .fillMaxWidth()
                .drawBehind {
                    drawStack(frontTop = StackBarGap, backRise = StackBackRise * rise.value, frontRise = StackFrontRise * rise.value)
                }
                .padding(top = StackTop),
        ) {
            ContentSurface(
                onClick = onClick,
                light = light,
                modifier = Modifier.focusMemory(focusKey).fillMaxWidth().height(width / ThumbAspect),
            ) {
                if (coverUrl != null) {
                    Picture(coverUrl)
                } else {
                    Icon(
                        painterResource(R.drawable.ic_list),
                        contentDescription = null,
                        tint = KeColors.Ink3,
                        modifier = Modifier.align(Alignment.Center).size(EmptyCoverIcon),
                    )
                }
                count?.let {
                    LengthBadge(it, Modifier.align(Alignment.BottomEnd).padding(end = BadgeInset, bottom = BadgeBottom))
                }
            }
        }
        CardText(
            text = title,
            style = titleStyle,
            color = if (focused) KeColors.Ink else KeColors.Ink2,
            maxLines = 2,
            modifier = Modifier.padding(top = TitleTop).graphicsLayer { translationY = textShift() },
        )
        CardText(
            text = meta,
            style = MaterialTheme.typography.bodySmall,
            color = KeColors.Ink3,
            maxLines = 1,
            modifier = Modifier.padding(top = MetaTop).graphicsLayer { translationY = textShift() },
        )
    }
}

private val EmptyCoverIcon = 28.dp

// Master `.card.more` (px ÷ 2): a well with a faint keyline; its 42 dp disc turns ink on focus.
private val MoreDisc = 42.dp
private val MoreIcon = 20.dp
private val MoreKeyline = Color.White.copy(alpha = 0.1f)
private val MoreDiscRest = Color.White.copy(alpha = 0.1f)

/**
 * A tile that leads somewhere rather than a video (master `.card.more`: All history, New
 * playlist): a well with [icon] on a disc, [title] and [meta] below. [topInset] lines its well up
 * with a row of playlist covers (their stack sits above the cover).
 */
@Composable
internal fun MoreCard(
    @DrawableRes icon: Int,
    title: String,
    meta: String,
    focusKey: String,
    onClick: () -> Unit,
    width: Dp,
    modifier: Modifier = Modifier,
    topInset: Dp = 0.dp,
) {
    CardColumn(width = width, modifier = modifier.padding(top = topInset)) { focused, textShift ->
        val disc = animateFloatAsState(if (focused) 1f else 0f, KeMotion.fast(), label = "moreDisc")
        ContentSurface(
            onClick = onClick,
            light = KeColors.ControlLight,
            // The title sits outside the focusable: name the focusable after it.
            modifier = Modifier
                .focusMemory(focusKey)
                .semantics { contentDescription = title }
                .fillMaxWidth()
                .height(width / ThumbAspect),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(KeColors.Well)
                    .drawBehind { drawKeyline(CardShape, 1.dp, MoreKeyline, 1f) },
            )
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(MoreDisc)
                    .drawBehind { drawCircle(lerp(MoreDiscRest, KeColors.Ink, disc.value)) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(icon),
                    contentDescription = null,
                    tint = lerp(KeColors.Ink, KeColors.Ground, disc.value),
                    modifier = Modifier.size(MoreIcon),
                )
            }
        }
        CardText(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = if (focused) KeColors.Ink else KeColors.Ink2,
            maxLines = 1,
            modifier = Modifier.padding(top = TitleTop).graphicsLayer { translationY = textShift() },
        )
        CardText(
            text = meta,
            style = MaterialTheme.typography.bodySmall,
            color = KeColors.Ink3,
            maxLines = 1,
            modifier = Modifier.padding(top = MetaTop).graphicsLayer { translationY = textShift() },
        )
    }
}

/**
 * A playlist cover's stack: two bars edge-on above the cover (the playlist's other videos), the
 * back one at the top, the front one [frontTop] down, each risen by its own amount.
 */
internal fun DrawScope.drawStack(
    frontTop: Dp,
    barHeight: Dp = StackBarHeight,
    backRise: Dp = 0.dp,
    frontRise: Dp = 0.dp,
) {
    drawStackBar(widthFraction = 0.84f, top = 0.dp, alpha = 0.55f, rise = backRise, height = barHeight)
    drawStackBar(widthFraction = 0.92f, top = frontTop, alpha = 0.9f, rise = frontRise, height = barHeight)
}

/** One bar: centred, [widthFraction] of the cover, [rise] higher. */
private fun DrawScope.drawStackBar(widthFraction: Float, top: Dp, alpha: Float, rise: Dp, height: Dp) {
    val barWidth = size.width * widthFraction
    drawRoundRect(
        color = Color.White.copy(alpha = StackBarAlpha * alpha),
        topLeft = Offset((size.width - barWidth) / 2, (top - rise).toPx()),
        size = Size(barWidth, height.toPx()),
        cornerRadius = CornerRadius(StackBarRadius.toPx()),
    )
}

/**
 * A channel in a row: its avatar (content focus family, avatar ring), name and [meta] below. An
 * [icon] takes the avatar's place on a well, turning ink when focused (`kp-kids` Find a channel);
 * [checked] marks the avatar's corner with a check that grows in (`.chan .ck`, D8). [focusSlot] is
 * its place in a row whose channels can leave it ([focusMemory]).
 */
@Composable
internal fun ChannelAvatarCard(
    name: String,
    avatarUrl: String?,
    meta: String?,
    focusKey: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    rowFocused: () -> Boolean = { false },
    @DrawableRes icon: Int? = null,
    checked: Boolean = false,
    focusSlot: FocusSlot? = null,
) {
    CardColumn(
        width = ChannelCardWidth,
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { focused, _ ->
        Box(Modifier.size(AvatarSize)) {
            AvatarSurface(
                onClick = onClick,
                dimmed = rowFocused,
                modifier = Modifier.focusMemory(focusKey, focusSlot).fillMaxSize(),
            ) {
                // A subscription can come without a picture (an older backup, an import): its monogram.
                when {
                    icon != null -> IconFace(icon, focused)
                    avatarUrl == null -> AvatarPicture(name, url = null, monogramSize = MonogramSize)
                    else -> Picture(avatarUrl)
                }
            }
            CheckMark(checked, focused)
        }
        CardText(
            text = name,
            style = MaterialTheme.typography.titleSmall.copy(fontSize = ChannelNameSize),
            color = if (focused) KeColors.Ink else KeColors.Ink2,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = ChannelNameTop),
        )
        meta?.let {
            CardText(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = KeColors.Ink3,
                maxLines = 1,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = ChannelMetaTop),
            )
        }
    }
}

// `.chan.find .av-wrap` (px ÷ 2): a well with a 1 dp inset keyline, its 28 dp icon ink-2.
private val IconFaceSize = 28.dp

@Composable
private fun BoxScope.IconFace(@DrawableRes icon: Int, focused: Boolean) {
    val fill by animateFloatAsState(if (focused) 1f else 0f, KeMotion.fast(), label = "iconFace")
    Box(
        Modifier
            .fillMaxSize()
            .drawBehind {
                drawCircle(lerp(KeColors.Well, KeColors.Ink, fill))
                drawCircle(Color.White.copy(alpha = 0.22f * (1f - fill)), radius = size.minDimension / 2 - 0.5.dp.toPx(), style = Stroke(1.dp.toPx()))
            },
    )
    Icon(painterResource(icon), contentDescription = null, tint = lerp(KeColors.Ink2, KeColors.Ground, fill), modifier = Modifier.align(Alignment.Center).size(IconFaceSize))
}

// `.chan .ck` (px ÷ 2): a 24 dp ink disc 2 dp past the avatar's corner inside a 2 dp ground ring;
// on focus it moves 3 dp out with the growing avatar.
private val CheckDisc = 24.dp
private val CheckOut = 2.dp
private val CheckRing = 2.dp
private val CheckFocusShift = 3.dp

@Composable
private fun BoxScope.CheckMark(checked: Boolean, focused: Boolean) {
    val shown by animateFloatAsState(if (checked) 1f else 0f, KeMotion.fast(), label = "check")
    val lift by animateFloatAsState(if (focused) 1f else 0f, KeMotion.base(), label = "checkLift")
    Box(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .offset(CheckOut, CheckOut)
            .size(CheckDisc)
            .graphicsLayer {
                val scale = shown * (1f + 0.06f * lift)
                scaleX = scale
                scaleY = scale
                alpha = shown
                translationX = CheckFocusShift.toPx() * lift
                translationY = CheckFocusShift.toPx() * lift
            }
            .drawBehind {
                drawCircle(KeColors.Ground, radius = size.minDimension / 2 + CheckRing.toPx())
                drawCircle(KeColors.Ink)
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = KeColors.Ground, modifier = Modifier.size(15.dp))
    }
}

/**
 * A card's column: tracks whether its focusable holds focus (the text brightens and drops a
 * little, D8) and raises the card above its row while it does. While focused it reports its
 * centre ([onFocused], root px) on focus and again as its row scrolls it into place, so the
 * room's light follows the card.
 */
@Composable
private fun CardColumn(
    width: Dp,
    modifier: Modifier = Modifier,
    onFocused: (centerX: Float) -> Unit = {},
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable (focused: Boolean, textShift: () -> Float) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shift = animateFloatAsState(if (focused) 1f else 0f, KeMotion.base(), label = "cardText")
    val placement = remember { Placement() }
    val currentOnFocused by rememberUpdatedState(onFocused)
    Column(
        modifier = modifier
            .zIndex(if (focused) 1f else 0f)
            .width(width)
            .onPlaced {
                placement.coordinates = it
                if (focused) placement.report(currentOnFocused)
            }
            .onFocusChanged { state ->
                focused = state.hasFocus
                if (state.hasFocus) placement.report(currentOnFocused)
            },
        horizontalAlignment = horizontalAlignment,
    ) {
        val shiftPx = with(LocalDensity.current) { FocusedTextShift.toPx() }
        content(focused) { shiftPx * shift.value }
    }
}

// Written by onPlaced, read by the focus reports: no composition reads it.
internal class Placement {
    var coordinates: LayoutCoordinates? = null

    fun report(onFocused: (centerX: Float) -> Unit) {
        coordinates?.takeIf { it.isAttached }?.let { onFocused(it.positionInRoot().x + it.size.width / 2f) }
    }
}

@Composable
private fun CardText(
    text: String,
    style: TextStyle,
    color: Color,
    maxLines: Int,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
) {
    val animatedColor by animateColorAsState(color, KeMotion.fast(), label = "cardTextColor")
    Text(
        text = text,
        style = style,
        color = animatedColor,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        textAlign = textAlign,
        modifier = modifier.fillMaxWidth(),
    )
}

/** A picture filling its box (cropped to it), fading in when it lands (D8); [onLoaded] once it has. */
@Composable
internal fun BoxScope.Picture(
    url: String?,
    modifier: Modifier = Modifier,
    colorFilter: ColorFilter? = null,
    onLoaded: () -> Unit = {},
) {
    if (url == null) return
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current).data(url).crossfade(KeMotion.BASE_MS).build(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        colorFilter = colorFilter,
        onSuccess = { onLoaded() },
        modifier = modifier.fillMaxSize(),
    )
}

/** A video's corner badge: LIVE in place of the length it doesn't have (D11), else its length when known. */
@Composable
internal fun VideoBadge(video: VideoItem, modifier: Modifier = Modifier) {
    if (video.isLive) LiveBadge(modifier) else lengthText(video.durationSeconds)?.let { LengthBadge(it, modifier) }
}

/** A length on its plate (`.dur`), placed by the caller over a picture's corner. */
@Composable
internal fun LengthBadge(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = KeColors.Ink,
        modifier = modifier
            .background(KeColors.BadgeScrim, BadgeShape)
            .padding(start = 4.5.dp, end = 4.5.dp, top = 1.5.dp, bottom = 2.dp),
    )
}

/** D11: a live stream's badge, in place of the length it doesn't have. */
@Composable
internal fun LiveBadge(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(KeColors.Live, BadgeShape)
            .padding(start = 4.5.dp, end = 5.dp, top = 1.5.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.5.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_live),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(11.dp),
        )
        Text(
            text = "LIVE",
            style = MaterialTheme.typography.labelSmall.atWeight(700).copy(letterSpacing = LiveBadgeTracking),
            color = Color.White,
        )
    }
}
