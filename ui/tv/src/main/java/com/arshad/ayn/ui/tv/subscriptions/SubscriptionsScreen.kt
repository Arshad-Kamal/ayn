package com.arshad.ayn.ui.tv.subscriptions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.arshad.ayn.core.access.AccessFailure
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.feature.ShortsList
import com.arshad.ayn.feature.library.VideoMenuViewModel
import com.arshad.ayn.feature.library.VideoSource
import com.arshad.ayn.feature.subscriptions.FeedBody
import com.arshad.ayn.feature.subscriptions.FeedState
import com.arshad.ayn.feature.subscriptions.SubscriptionsLayout
import com.arshad.ayn.feature.subscriptions.SubscriptionsViewModel
import com.arshad.ayn.feature.format.channelsText
import com.arshad.ayn.feature.subscriptions.dayNote
import com.arshad.ayn.feature.subscriptions.dayTitle
import com.arshad.ayn.feature.subscriptions.everyChannelDays
import com.arshad.ayn.feature.subscriptions.subscriptionsLayout
import com.arshad.ayn.ui.tv.R
import com.arshad.ayn.ui.tv.common.Appearing
import com.arshad.ayn.ui.tv.common.CardRoomLight
import com.arshad.ayn.ui.tv.common.FocusedCardLight
import com.arshad.ayn.ui.tv.common.GridColumns
import com.arshad.ayn.ui.tv.common.Kicker
import com.arshad.ayn.ui.tv.common.LocalContentFilter
import com.arshad.ayn.ui.tv.common.PageHead
import com.arshad.ayn.ui.tv.common.PillButton
import com.arshad.ayn.ui.tv.common.Statement
import com.arshad.ayn.ui.tv.common.UnreadableReply
import com.arshad.ayn.ui.tv.common.failureKicker
import com.arshad.ayn.feature.format.latestVideosText
import com.arshad.ayn.feature.format.metaLine
import com.arshad.ayn.ui.tv.common.rememberSheen
import com.arshad.ayn.ui.tv.common.restingRoomLight
import com.arshad.ayn.ui.tv.common.skeleton
import com.arshad.ayn.ui.tv.focus.ControlStyle
import com.arshad.ayn.ui.tv.focus.ControlSurface
import com.arshad.ayn.ui.tv.focus.ScreenFocus
import com.arshad.ayn.ui.tv.focus.TintedIcon
import com.arshad.ayn.ui.tv.focus.TintedText
import com.arshad.ayn.ui.tv.focus.focusMemory
import com.arshad.ayn.ui.tv.library.VideoMenuHost
import com.arshad.ayn.ui.tv.shell.RailShade
import com.arshad.ayn.ui.tv.theme.KeColors
import com.arshad.ayn.ui.tv.theme.KeLayout
import com.arshad.ayn.ui.tv.theme.weightSpan
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Date
import java.util.Locale
import kotlin.time.Instant

// b3-subscriptions geometry (px ÷ 2), from the screen's top: the head is 38 dp from the safe
// line, the strip 17 below it, the feed from 145 (186 under the partial note), a statement
// from 125, a selected channel's statement from 170.
private val StripTop = 17.dp
private val NoteTop = 9.dp
private val NoteHeight = 38.dp
private val NotePillHeight = 27.dp
private val FeedTop = 145.dp
private val NotedFeedTop = 186.dp
private val StatementTop = 125.dp
private val ChannelStatementTop = 170.dp
private val NoteTextSize = 12.5.sp
private val AllSkeletonWidth = 59.dp
private val AllSkeletonHeight = 32.dp
private val AvatarSkeleton = 38.dp
private const val StripSkeletonMax = 14
private const val NamedFailures = 3

/**
 * Subscriptions (`b3-subscriptions` states 1, 3–10) on [SubscriptionsViewModel]: the head, the
 * channel strip selecting on focus, the partial note, and the feed by upload day (or the selected
 * channel's latest videos), the focused card lighting the room. With Show Shorts on, a Shorts
 * shelf plays through [onPlayShort] with the shelf as its run (M7-D13). Holding OK on a video or
 * Short opens its card menu on [menu] ([onRailShade] while it shows). Each start says whether it is
 * an arrival ([consumeArrival]: from the rail, or the app back in the foreground), and every key
 * press is reported, for when a fresh feed lands over the kept one (PERF P9c).
 */
@Composable
internal fun SubscriptionsScreen(
    viewModel: SubscriptionsViewModel,
    menu: VideoMenuViewModel,
    onNothingToFocus: () -> Unit,
    focusHeldElsewhere: () -> Boolean,
    onPlay: (video: VideoItem, startOver: Boolean) -> Unit,
    onPlayShort: (short: VideoItem, run: ShortsList) -> Unit,
    onOpenChannel: (channelUrl: String) -> Unit,
    onFindChannel: () -> Unit,
    onRailShade: (RailShade) -> Unit,
    modifier: Modifier = Modifier,
    consumeArrival: () -> Boolean = { false },
) {
    LifecycleStartEffect(viewModel) {
        viewModel.onVisible(arrived = consumeArrival())
        onStopOrDispose { }
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val locale = LocalConfiguration.current.locales[0]
    val contentFilter = LocalContentFilter.current
    val zone = ZoneId.systemDefault()
    val firstDayOfWeek = WeekFields.of(locale).firstDayOfWeek
    // Grouped once per feed, so a selection in the strip (and All again) doesn't regroup it.
    val feed = state.feedState
    val days = remember(feed, zone, firstDayOfWeek, contentFilter) {
        (feed as? FeedState.Ready)?.let { everyChannelDays(it, zone, firstDayOfWeek, contentFilter) }
    }
    val layout = remember(state, days, contentFilter) {
        subscriptionsLayout(state, zone, firstDayOfWeek, contentFilter, days)
    }
    val playShort: (VideoItem) -> Unit = { short -> onPlayShort(short, viewModel.openShorts()) }
    // Read as the page comes back from the Shorts player (a covered destination leaves composition).
    var shortsReturn by remember { mutableStateOf(viewModel.shortsReturn()) }
    VideoMenuHost(menu, onPlay, onOpenChannel, onRailShade, modifier, onPlayShort = playShort) { pageModifier, openMenu ->
        SubscriptionsPage(
            layout = layout,
            viewModel = viewModel,
            onNothingToFocus = onNothingToFocus,
            focusHeldElsewhere = focusHeldElsewhere,
            returnKey = shortsReturn?.let(::shortKey),
            onReturned = {
                shortsReturn = null
                viewModel.shortsReturned()
            },
            onPlay = { video -> onPlay(video, false) },
            onPlayShort = playShort,
            onOpenMenu = openMenu,
            onOpenShortMenu = { short -> menu.open(short, VideoSource.Elsewhere, short = true) },
            onOpenChannel = onOpenChannel,
            onFindChannel = onFindChannel,
            modifier = pageModifier.onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown) viewModel.onKeyPressed()
                false
            },
        )
    }
}

@Composable
private fun SubscriptionsPage(
    layout: SubscriptionsLayout,
    viewModel: SubscriptionsViewModel,
    onNothingToFocus: () -> Unit,
    focusHeldElsewhere: () -> Boolean,
    returnKey: String?,
    onReturned: () -> Unit,
    onPlay: (VideoItem) -> Unit,
    onPlayShort: (VideoItem) -> Unit,
    onOpenMenu: (VideoItem) -> Unit,
    onOpenShortMenu: (VideoItem) -> Unit,
    onOpenChannel: (String) -> Unit,
    onFindChannel: () -> Unit,
    modifier: Modifier,
) {
    ScreenFocus(
        onNothingToFocus = onNothingToFocus,
        modifier = modifier,
        initialFocusKey = layout.initialFocusKey,
        entryKey = layout.entryKey,
        focusHeldElsewhere = focusHeldElsewhere,
        returnKey = returnKey,
        onReturned = onReturned,
    ) {
        when (layout) {
            is SubscriptionsLayout.Loading -> Box(Modifier.fillMaxSize().restingRoomLight()) {
                Column {
                    PageHead("Subscriptions", channelsText(layout.channelCount))
                    StripSkeleton(layout.channelCount, Modifier.padding(top = StripTop))
                }
                FeedSkeleton(Modifier.padding(top = FeedTop))
            }
            SubscriptionsLayout.Blank -> Box(Modifier.fillMaxSize().restingRoomLight()) {
                PageHead("Subscriptions", aside = null)
                Statement(
                    kicker = Kicker(R.drawable.ic_nav_subscriptions, "Nothing here yet"),
                    title = "Follow channels without an account",
                    body = "Subscribe from any channel page and new videos land here, newest first. Your list stays on this Shield.",
                    modifier = Modifier.padding(start = KeLayout.ContentStart, top = StatementTop),
                ) {
                    PillButton(R.drawable.ic_nav_search, "Find a channel", FindKey, onFindChannel)
                }
            }
            is SubscriptionsLayout.Failed -> Box(Modifier.fillMaxSize().restingRoomLight()) {
                PageHead("Subscriptions", channelsText(layout.channelCount))
                FailedStatement(layout.failure, layout.channelCount, onRetry = viewModel::refresh)
            }
            is SubscriptionsLayout.Feed -> Feed(
                layout = layout,
                onSelect = viewModel::selectFilter,
                onRetry = viewModel::refresh,
                onPlay = onPlay,
                onPlayShort = onPlayShort,
                onOpenMenu = onOpenMenu,
                onOpenShortMenu = onOpenShortMenu,
                onOpenChannel = onOpenChannel,
            )
        }
    }
}

@Composable
private fun Feed(
    layout: SubscriptionsLayout.Feed,
    onSelect: (String?) -> Unit,
    onRetry: () -> Unit,
    onPlay: (VideoItem) -> Unit,
    onPlayShort: (VideoItem) -> Unit,
    onOpenMenu: (VideoItem) -> Unit,
    onOpenShortMenu: (VideoItem) -> Unit,
    onOpenChannel: (String) -> Unit,
) {
    val cardLight = remember { FocusedCardLight() }
    val feedFocused = remember { mutableStateOf(false) }
    val selected = layout.selected
    val noted = layout.failedNames.isNotEmpty()
    val body = layout.body
    val locale = LocalConfiguration.current.locales[0]
    val cards = remember(body, locale) { feedCards(body, locale) }
    // Each selection's feed starts at its top; saved, so BACK from the player finds its card again.
    // Saved under the selection's own key: a filter changed while the page was covered (the
    // channel unsubscribed) never gets another selection's scroll.
    val gridState = key(selected?.channelUrl) { rememberSaveable(saver = LazyGridState.Saver) { LazyGridState() } }
    val joinedRows = rememberJoiningRows(selected?.channelUrl)
    Box(Modifier.fillMaxSize().restingRoomLight()) {
        FeedRoomLight(cardLight, feedFocused, lightable = cards != null)
        Column {
            PageHead("Subscriptions", feedAside(layout))
            ChannelStrip(
                channels = layout.channels,
                selectedUrl = selected?.channelUrl,
                onSelect = onSelect,
                modifier = Modifier.padding(top = StripTop),
            )
            if (noted) PartialNote(layout.failedNames, onRetry, Modifier.padding(top = NoteTop))
        }
        // A new selection brings a new feed: it fades in from the top, scrolled to its start, its
        // rows joining one per frame. Every selection's cards go through one grid.
        Appearing(Modifier.onFocusChanged { feedFocused.value = it.hasFocus }, key = selected?.channelUrl) {
            if (cards != null) {
                FeedGrid(
                    groups = cards.groups,
                    shelf = cards.shelf,
                    now = layout.loadedAt,
                    onPlay = onPlay,
                    onPlayShort = onPlayShort,
                    onOpenMenu = onOpenMenu,
                    onOpenShortMenu = onOpenShortMenu,
                    onCardFocused = cardLight::onCardFocused,
                    state = gridState,
                    joinedRows = joinedRows,
                    // Only every channel's feed has the partial note above it.
                    modifier = Modifier.padding(top = if (noted) NotedFeedTop else FeedTop),
                )
            } else {
                when (body) {
                    is FeedBody.ChannelFailed -> ChannelStatement(
                        kicker = Kicker(R.drawable.ic_alert, "${body.channel.name} didn’t answer"),
                        title = "Nothing to show from ${body.channel.name}",
                        body = "Its videos will show here once it answers. Try again, or pick another channel.",
                    ) {
                        PillButton(R.drawable.ic_retry, "Try again", RetryChannelKey, onRetry)
                    }
                    is FeedBody.ChannelEmpty -> ChannelStatement(
                        kicker = Kicker(R.drawable.ic_nav_subscriptions, "No videos in your feed"),
                        title = "Nothing from ${body.channel.name} yet",
                        body = "This channel has no videos to list here. Its page shows everything it has.",
                    ) {
                        PillButton(R.drawable.ic_nav_subscriptions, "Open channel", OpenChannelKey, { onOpenChannel(body.channel.channelUrl) })
                    }
                    is FeedBody.Days, is FeedBody.Latest -> Unit
                }
            }
        }
    }
}

/** The room lit by the focused card while the feed holds [focused]: read here, so focus crossing
 *  between the strip and the feed recomposes only the light. */
@Composable
private fun FeedRoomLight(card: FocusedCardLight, focused: State<Boolean>, lightable: Boolean) {
    CardRoomLight(card, lit = focused.value && lightable)
}

/** The grid's content for a feed of cards; `null` for a statement (a channel failed or empty). */
private data class FeedCards(val groups: List<FeedGroup>, val shelf: FeedShelf?)

/** b3-subscriptions shorts: the shelf follows the first day; a picked channel's (filter-shorts)
 *  follows its first row. */
private fun feedCards(body: FeedBody, locale: Locale): FeedCards? = when (body) {
    is FeedBody.Days -> FeedCards(
        groups = body.groups.map { FeedGroup(dayTitle(it.day, locale), dayNote(it.day, locale), it.videos) },
        shelf = body.shorts.takeIf { it.isNotEmpty() }?.let {
            FeedShelf("From your channels", it, afterVideos = body.groups.firstOrNull()?.videos?.size ?: 0, channelUrl = null)
        },
    )
    is FeedBody.Latest -> FeedCards(
        groups = body.videos.takeIf { it.isNotEmpty() }
            ?.let { listOf(FeedGroup("Latest from ${body.channel.name}", note = null, it)) }
            .orEmpty(),
        shelf = body.shorts.takeIf { it.isNotEmpty() }?.let {
            FeedShelf("From ${body.channel.name}", it, afterVideos = GridColumns, channelUrl = body.channel.channelUrl)
        },
    )
    is FeedBody.ChannelFailed, is FeedBody.ChannelEmpty -> null
}

@Composable
private fun feedAside(layout: SubscriptionsLayout.Feed): String {
    val selected = layout.selected ?: return metaLine(channelsText(layout.channels.size), "updated ${clockTime(layout.loadedAt)}")
    return metaLine(
        selected.name,
        when (val body = layout.body) {
            is FeedBody.Latest -> body.videos.takeIf { it.isNotEmpty() }?.let { latestVideosText(it.size) }
            is FeedBody.ChannelFailed -> "didn’t refresh"
            is FeedBody.ChannelEmpty -> "no videos"
            is FeedBody.Days -> null
        },
    )
}

@Composable
private fun clockTime(at: Instant): String =
    android.text.format.DateFormat.getTimeFormat(LocalContext.current).format(Date(at.toEpochMilliseconds()))

/** State 4: which channels didn't refresh, and Try again. */
@Composable
private fun PartialNote(names: List<String>, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val count = names.size
    val listed = names.take(NamedFailures).joinToString(", ") + if (count > NamedFailures) " and ${count - NamedFailures} more" else ""
    val text = buildAnnotatedString {
        withStyle(weightSpan(640, KeColors.Ink)) {
            append(if (count == 1) "1 channel didn’t refresh:" else "$count channels didn’t refresh:")
        }
        append(" $listed. ")
        append(if (count == 1) "Its videos are missing until it answers." else "Their videos are missing until they answer.")
    }
    Row(
        modifier = modifier
            .padding(start = KeLayout.ContentStart, end = KeLayout.SafeHorizontal)
            .height(NoteHeight)
            .background(KeColors.Warn.copy(alpha = 0.1f), CircleShape)
            .border(0.5.dp, KeColors.Warn.copy(alpha = 0.28f), CircleShape)
            .padding(start = 12.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(painterResource(R.drawable.ic_alert), contentDescription = null, tint = KeColors.Warn, modifier = Modifier.size(15.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = NoteTextSize),
            color = KeColors.Ink2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        ControlSurface(
            onClick = onRetry,
            style = ControlStyle.Pill,
            modifier = Modifier.padding(start = 5.dp).focusMemory(RetryFailedKey).height(NotePillHeight),
        ) {
            Row(
                modifier = Modifier.fillMaxHeight().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TintedIcon(painterResource(R.drawable.ic_retry), contentDescription = null, modifier = Modifier.size(14.dp))
                TintedText("Try again", style = MaterialTheme.typography.labelLarge.copy(fontSize = NoteTextSize))
            }
        }
    }
}

@Composable
private fun FailedStatement(failure: AccessFailure, channelCount: Int, onRetry: () -> Unit) {
    Statement(
        kicker = failureKicker(failure),
        title = "Subscriptions couldn’t refresh",
        body = when {
            failure != AccessFailure.OFFLINE ->
                "Your list is safe on this Shield. $UnreadableReply"
            channelCount == 1 -> "Your channel didn’t answer. Your list is safe on this Shield; try again in a moment."
            else -> "None of your $channelCount channels answered. Your list is safe on this Shield; try again in a moment."
        },
        modifier = Modifier.padding(start = KeLayout.ContentStart, top = StatementTop),
    ) {
        PillButton(R.drawable.ic_retry, "Try again", RetryKey, onRetry)
    }
}

/** States 9–10: a compact statement where the selected channel's feed would be. */
@Composable
private fun ChannelStatement(
    kicker: Kicker,
    title: String,
    body: String,
    action: @Composable () -> Unit,
) {
    Statement(
        kicker = kicker,
        title = title,
        body = body,
        compact = true,
        modifier = Modifier.padding(start = KeLayout.ContentStart, top = ChannelStatementTop),
    ) {
        action()
    }
}

/** State 7: All and the avatars as skeletons, as many as the list holds (up to a strip's width). */
@Composable
private fun StripSkeleton(channelCount: Int, modifier: Modifier = Modifier) {
    val sheen = rememberSheen()
    Row(
        modifier = modifier.padding(start = KeLayout.ContentStart).height(StripHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Spacer(Modifier.size(AllSkeletonWidth, AllSkeletonHeight).skeleton(sheen, RoundedCornerShape(50)))
        repeat(channelCount.coerceAtMost(StripSkeletonMax)) {
            Spacer(Modifier.size(AvatarSkeleton).skeleton(sheen, CircleShape))
        }
    }
}
