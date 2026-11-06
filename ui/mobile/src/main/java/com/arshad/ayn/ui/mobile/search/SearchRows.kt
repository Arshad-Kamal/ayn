package com.arshad.ayn.ui.mobile.search

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arshad.ayn.core.access.ChannelItem
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.feature.format.ageText
import com.arshad.ayn.feature.format.blurb
import com.arshad.ayn.feature.format.dateText
import com.arshad.ayn.feature.format.metaLine
import com.arshad.ayn.feature.format.subscribersText
import com.arshad.ayn.feature.format.viewsText
import com.arshad.ayn.feature.format.watchingText
import com.arshad.ayn.ui.mobile.R
import com.arshad.ayn.ui.mobile.common.Picture
import com.arshad.ayn.ui.mobile.common.pressScale
import com.arshad.ayn.ui.mobile.common.rememberCardFace
import com.arshad.ayn.ui.mobile.common.rememberFaceArrival
import com.arshad.ayn.ui.mobile.common.rememberPictureLight
import com.arshad.ayn.ui.mobile.home.CardPicture
import com.arshad.ayn.ui.mobile.home.MoreButton
import com.arshad.ayn.ui.mobile.home.SkeletonBar
import com.arshad.ayn.ui.mobile.home.TitleSkeleton
import com.arshad.ayn.ui.mobile.home.rememberCardPress
import com.arshad.ayn.ui.mobile.theme.AynColors
import com.arshad.ayn.ui.mobile.theme.AynMotion
import com.arshad.ayn.ui.mobile.theme.atWeight
import com.arshad.ayn.ui.mobile.theme.weightSpan
import kotlin.time.Instant

internal val RowInset = 16.dp
private val RowPictureWidth = 168.dp

/** The Library's lists' pictures (History, playlists: `.hrow`, `.nrow`). */
internal val ListPictureWidth = 150.dp
private val NumberWidth = 18.dp
private val RowPictureShape = RoundedCornerShape(11.dp)
private val ShortWidth = 148.dp
private val ShortCorner = 14.dp
private val ShortShape = RoundedCornerShape(ShortCorner)
private val LitRing = 2.5.dp

/**
 * A compact result row (`m6-search` 7, M6-D13; Subscriptions' and a channel's lists, M6-D14): the
 * picture left with its length and watch bar ([progress]), up to three title lines, then [meta]
 * (by default the channel, then views and age). A tap plays; a long press or the ⋮ opens the card
 * menu ([onOpenMenu]; a kids profile's rows have none). Pressed, or [held] while its menu is open,
 * the picture lifts and spills its colour, as a card does. A playlist's rows lead with their
 * [number] (`.nrow`) and the Library's take a narrower picture ([pictureWidth]).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ResultRow(
    video: VideoItem,
    progress: Float?,
    onClick: () -> Unit,
    onOpenMenu: (() -> Unit)?,
    modifier: Modifier = Modifier,
    held: Boolean = false,
    meta: String? = rowMeta(video),
    number: Int? = null,
    pictureWidth: Dp = RowPictureWidth,
) {
    val face = rememberCardFace(video)
    val arrival = rememberFaceArrival(face != null)
    val interaction = remember { MutableInteractionSource() }
    val press = rememberCardPress(interaction, held, face?.thumbnailUrl)
    Row(
        modifier
            .fillMaxWidth()
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = "Play",
                onLongClickLabel = onOpenMenu?.let { "Open the menu" },
                onLongClick = onOpenMenu,
                onClick = onClick,
            )
            .padding(start = if (number != null) RowInset - 4.dp else RowInset, end = RowInset),
        horizontalArrangement = Arrangement.spacedBy(if (number != null) 10.dp else 12.dp),
    ) {
        if (number != null) RowNumber(number, Modifier.align(Alignment.CenterVertically))
        CardPicture(video, face, arrival, press, progress, RowPictureShape, Modifier.width(pictureWidth).aspectRatio(16f / 9f))
        Column(Modifier.weight(1f)) {
            if (face != null) {
                Text(
                    face.title,
                    style = MaterialTheme.typography.bodyMedium.atWeight(620).copy(fontSize = 14.5.sp, lineHeight = 18.3.sp),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.graphicsLayer { alpha = arrival.value },
                )
            } else {
                TitleSkeleton()
            }
            meta?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp, lineHeight = 17.5.sp),
                    color = AynColors.Ink3,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        if (onOpenMenu != null) MoreButton(onOpenMenu, Modifier.size(width = 32.dp, height = 40.dp))
    }
}

/** A playlist row's place in it (`.nrow .n`). */
@Composable
private fun RowNumber(number: Int, modifier: Modifier = Modifier) {
    Text(
        "$number",
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 13.sp, fontFeatureSettings = "tnum"),
        color = AynColors.Ink3,
        textAlign = TextAlign.Center,
        modifier = modifier.width(NumberWidth),
    )
}

/** The channel (unless [withChannel] is off), then views and age (a stream's audience instead), on
 *  two lines; the age is YouTube's text, or measured at [now] when given and the upload is dated;
 *  an upcoming video's start in its place. */
internal fun rowMeta(video: VideoItem, now: Instant? = null, withChannel: Boolean = true): String? {
    val age = now?.let { video.ageText(it) } ?: video.dateText()
    val second = if (video.isLive) watchingText(video.viewCount) else metaLine(viewsText(video.viewCount), age).ifEmpty { null }
    return listOfNotNull(video.uploaderName.takeIf { withChannel }, second).joinToString("\n").ifEmpty { null }
}

/**
 * A channel in the results (`m6-search` 8): its avatar glowing in its own colour, name,
 * subscribers and the first lines of its description. A tap opens it ([onClick]).
 */
@Composable
internal fun ChannelLead(channel: ChannelItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val light by rememberPictureLight(channel.avatarUrl)
    Row(
        modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = "Open the channel", onClick = onClick)
            .padding(start = RowInset, end = RowInset, top = 6.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            Modifier
                .size(80.dp)
                .graphicsLayer {
                    shadowElevation = 14.dp.toPx()
                    shape = CircleShape
                    clip = true
                    spotShadowColor = light
                    ambientShadowColor = light
                }
                .background(AynColors.Surface2),
        ) { Picture(channel.avatarUrl) }
        Column(Modifier.weight(1f)) {
            Text(
                channel.name,
                style = MaterialTheme.typography.headlineLarge.copy(fontSize = 24.sp, lineHeight = 25.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            subscribersText(channel.subscriberCount)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = AynColors.Ink2, modifier = Modifier.padding(top = 3.dp))
            }
            channel.description?.let(::blurb)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp, lineHeight = 17.sp),
                    color = AynColors.Ink3,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(painterResource(R.drawable.ic_chevron), contentDescription = null, tint = AynColors.Ink3, modifier = Modifier.size(20.dp))
    }
}

/**
 * A page's Shorts under [title] (`m6-search` 9, Subscriptions' shelf), after the first result or
 * day while Show Shorts is on: a sideways run of tall pictures; a tap plays one with the shelf as
 * its run ([onPlay]), a long press opens its menu ([onOpenMenu]; none in a kids profile). Shorts
 * have no DeArrow face (Q40). The Short the Shorts player came back from ([lit]) is scrolled to
 * and lit (`m6-shorts` 8).
 */
@Composable
internal fun ShortsShelf(
    shorts: List<VideoItem>,
    onPlay: (VideoItem) -> Unit,
    onOpenMenu: ((VideoItem) -> Unit)?,
    modifier: Modifier = Modifier,
    title: String = "Shorts",
    lit: VideoId? = null,
) {
    val row = rememberLazyListState()
    val inView = remember { BringIntoViewRequester() }
    // The row brings the card in (it may not be composed yet), then the shelf comes into the page's view.
    LaunchedEffect(lit) {
        val index = shorts.indexOfFirst { it.id == lit }
        if (index < 0) return@LaunchedEffect
        row.animateScrollToItem(index)
        inView.bringIntoView()
    }
    Column(modifier.bringIntoViewRequester(inView).padding(top = 4.dp, bottom = 2.dp)) {
        Row(
            Modifier.padding(start = RowInset, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(painterResource(R.drawable.ic_shorts), contentDescription = null, tint = AynColors.Ink, modifier = Modifier.size(19.dp))
            Text(title, style = MaterialTheme.typography.titleLarge)
        }
        LazyRow(state = row, contentPadding = PaddingValues(horizontal = RowInset), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(shorts, key = { it.id.value }) { short ->
                ShortCard(
                    short,
                    onClick = { onPlay(short) },
                    onOpenMenu = onOpenMenu?.let { open -> { open(short) } },
                    modifier = Modifier.width(ShortWidth),
                    lit = short.id == lit,
                )
            }
        }
    }
}

/** A Short's tall picture, title and views, as wide as [modifier] makes it; while [lit] a ring
 *  shows round its picture (fading, alpha only). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ShortCard(short: VideoItem, onClick: () -> Unit, onOpenMenu: (() -> Unit)?, modifier: Modifier = Modifier, lit: Boolean = false) {
    val interaction = remember { MutableInteractionSource() }
    val ring = animateFloatAsState(if (lit) 1f else 0f, AynMotion.base(), label = "lit")
    Column(
        modifier
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = "Play",
                onLongClickLabel = onOpenMenu?.let { "Open the menu" },
                onLongClick = onOpenMenu,
                onClick = onClick,
            ),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(9f / 16f)
                .pressScale(interaction)
                .drawWithContent {
                    drawContent()
                    val alpha = ring.value
                    if (alpha > 0f) {
                        val width = LitRing.toPx()
                        drawRoundRect(
                            color = AynColors.Ink.copy(alpha = alpha),
                            topLeft = Offset(width / 2, width / 2),
                            size = Size(size.width - width, size.height - width),
                            cornerRadius = CornerRadius(ShortCorner.toPx() - width / 2),
                            style = Stroke(width),
                        )
                    }
                }
                .clip(ShortShape)
                .background(AynColors.Surface2),
        ) { Picture(short.thumbnailUrl) }
        Text(
            short.title,
            style = MaterialTheme.typography.bodyMedium.atWeight(620).copy(fontSize = 13.5.sp, lineHeight = 17.sp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        viewsText(short.viewCount)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = AynColors.Ink3, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** One of YouTube's suggestions (`m6-search` 4): what was typed, then the completion in bold. */
@Composable
internal fun SuggestionRow(suggestion: String, typed: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clickable(role = Role.Button, onClickLabel = "Search", onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(painterResource(R.drawable.ic_nav_search), contentDescription = null, tint = AynColors.Ink3, modifier = Modifier.size(19.dp))
        Text(
            completionText(suggestion, typed),
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
            color = AynColors.Ink2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** [suggestion] with what follows [typed] in bold; all of it when it doesn't start with it. */
internal fun completionText(suggestion: String, typed: String): AnnotatedString = buildAnnotatedString {
    val prefix = typed.trimStart()
    val start = if (prefix.isNotEmpty() && suggestion.startsWith(prefix, ignoreCase = true)) prefix.length else 0
    append(suggestion.substring(0, start))
    withStyle(weightSpan(680, AynColors.Ink)) { append(suggestion.substring(start)) }
}

/** A result row's shape while the search runs (`m6-search` 6), pulsing with [pulse]; a playlist's
 *  numbered as its rows will be ([number], [pictureWidth]). */
@Composable
internal fun ResultSkeleton(pulse: State<Float>, modifier: Modifier = Modifier, number: Int? = null, pictureWidth: Dp = RowPictureWidth) {
    Row(
        modifier.padding(start = if (number != null) RowInset - 4.dp else RowInset, end = RowInset),
        horizontalArrangement = Arrangement.spacedBy(if (number != null) 10.dp else 12.dp),
    ) {
        if (number != null) RowNumber(number, Modifier.align(Alignment.CenterVertically).clearAndSetSemantics { })
        SkeletonBar(pulse, Modifier.width(pictureWidth).aspectRatio(16f / 9f), RowPictureShape)
        Column(Modifier.weight(1f).padding(top = 2.dp, end = 30.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            SkeletonBar(pulse, Modifier.fillMaxWidth().height(12.dp))
            SkeletonBar(pulse, Modifier.fillMaxWidth(0.7f).height(12.dp))
            SkeletonBar(pulse, Modifier.fillMaxWidth(0.45f).height(12.dp))
        }
    }
}
