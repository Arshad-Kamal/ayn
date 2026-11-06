package com.arshad.ayn.feature.subscriptions

import com.arshad.ayn.core.access.AccessFailure
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.filter.ContentFilter
import com.arshad.ayn.core.filter.shows
import com.arshad.ayn.core.store.Subscription
import java.time.DayOfWeek
import java.time.ZoneId
import kotlin.time.Instant

/** What Subscriptions draws (`b3-subscriptions`, `m6-subscriptions-and-channel` states), derived
 *  from [SubscriptionsUiState]. Both UIs draw it (M6-D12). */
sealed interface SubscriptionsLayout {
    /** The list is known ([channelCount], 0 until the store answers), the feed loads. */
    data class Loading(val channelCount: Int) : SubscriptionsLayout

    /** No subscriptions. */
    data object Blank : SubscriptionsLayout

    /** No channel answered; the strip still lists [channels]. */
    data class Failed(val failure: AccessFailure, val channels: List<StripChannel>) : SubscriptionsLayout {
        val channelCount: Int get() = channels.size
    }

    /** The strip, then every channel's feed or the [selected] channel's. [failedNames] (the
     *  partial note) is listed only above every channel's feed. */
    data class Feed(
        val channels: List<StripChannel>,
        val selected: Subscription?,
        val failedNames: List<String>,
        val body: FeedBody,
        val loadedAt: Instant,
    ) : SubscriptionsLayout
}

/** A strip avatar: [failed] dims it and marks it (its feed didn't answer). */
data class StripChannel(val subscription: Subscription, val failed: Boolean)

sealed interface FeedBody {
    /** Every channel's videos by upload day, and every channel's [shorts] newest first while Show
     *  Shorts is on. */
    data class Days(val groups: List<FeedDayGroup>, val shorts: List<VideoItem>) : FeedBody

    /** The selected [channel]'s latest videos and its [shorts] while Show Shorts is on (M7.8m
     *  filter-shorts); one of the two is never empty. */
    data class Latest(val channel: Subscription, val videos: List<VideoItem>, val shorts: List<VideoItem>) : FeedBody

    /** The selected [channel]'s feed failed. */
    data class ChannelFailed(val channel: Subscription) : FeedBody

    /** The selected [channel] answered with no videos, and no Short shown. */
    data class ChannelEmpty(val channel: Subscription) : FeedBody
}

/** Every channel's videos in [feed] by upload day, without what [contentFilter] hides. */
fun everyChannelDays(
    feed: FeedState.Ready,
    zone: ZoneId,
    firstDayOfWeek: DayOfWeek,
    contentFilter: ContentFilter?,
): List<FeedDayGroup> = feedDays(feed.entries.filter { contentFilter.shows(it) }, feed.loadedAt, zone, firstDayOfWeek)

/** Days are the device's: [zone] and a week starting on [firstDayOfWeek]. The feed leaves out what
 *  [contentFilter] now hides: a block from a card's menu takes its cards out at once (KP-D19).
 *  [days], when given, is [everyChannelDays] of this feed, grouped once by the caller. */
fun subscriptionsLayout(
    state: SubscriptionsUiState,
    zone: ZoneId,
    firstDayOfWeek: DayOfWeek,
    contentFilter: ContentFilter? = null,
    days: List<FeedDayGroup>? = null,
): SubscriptionsLayout {
    return when (val feed = state.feedState) {
        FeedState.Loading -> SubscriptionsLayout.Loading(state.subscriptions.size)
        FeedState.NoSubscriptions -> SubscriptionsLayout.Blank
        is FeedState.Failed -> SubscriptionsLayout.Failed(feed.failure, state.subscriptions.map { StripChannel(it, failed = false) })
        is FeedState.Ready -> {
            val selected = state.subscriptions.firstOrNull { it.channelUrl == state.filter }
            val shorts = state.visibleShorts().filter { contentFilter.shows(it) }
            val body = when {
                selected == null -> FeedBody.Days(days ?: everyChannelDays(feed, zone, firstDayOfWeek, contentFilter), shorts)
                selected.channelUrl in feed.failedChannels -> FeedBody.ChannelFailed(selected)
                else -> state.visibleEntries().filter { contentFilter.shows(it) }.let { videos ->
                    if (videos.isEmpty() && shorts.isEmpty()) FeedBody.ChannelEmpty(selected) else FeedBody.Latest(selected, videos, shorts)
                }
            }
            SubscriptionsLayout.Feed(
                channels = state.subscriptions.map { StripChannel(it, failed = it.channelUrl in feed.failedChannels) },
                selected = selected,
                failedNames = if (selected == null) {
                    state.subscriptions.filter { it.channelUrl in feed.failedChannels }.map { it.name }
                } else {
                    emptyList()
                },
                body = body,
                loadedAt = feed.loadedAt,
            )
        }
    }
}
