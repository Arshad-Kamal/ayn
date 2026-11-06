package com.arshad.ayn.feature.home

import com.arshad.ayn.core.access.AccessFailure
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.filter.ContentFilter
import com.arshad.ayn.core.filter.shows
import com.arshad.ayn.core.store.canonicalChannelUrl
import com.arshad.ayn.feature.foryou.ForYouPick
import com.arshad.ayn.feature.foryou.ForYouReason
import com.arshad.ayn.feature.library.HistoryVideo
import kotlin.time.Instant

/** What Home draws (`b3-home`, `m6-home-and-menus` states), derived from [HomeUiState]; rows with
 *  nothing are left out. Both UIs draw it (M6-D12). */
sealed interface HomeLayout {
    /** The skeleton. */
    data object Loading : HomeLayout

    /** Nothing from YouTube came back (D8). */
    data class Failed(val failure: AccessFailure) : HomeLayout

    data class Browse(val top: HomeTop, val rows: List<HomeRow>) : HomeLayout
}

/** What sits above the rows. */
sealed interface HomeTop {
    /** The hero ([HomeUiState.hero]: For you's first pick, else the newest unwatched subscription
     *  upload); [reason] is For you's, `null` for a subscription hero. */
    data class HeroTop(
        val hero: Hero,
        val avatarUrl: String?,
        val now: Instant,
        val inWatchLater: Boolean,
        val reason: ForYouReason? = null,
    ) : HomeTop

    /** No subscriptions yet. */
    data object Blank : HomeTop

    /** Subscribed, every feed failed. */
    data class FeedsFailed(val channelCount: Int) : HomeTop

    /** Feeds answered but none holds an unwatched video: the rows alone. */
    data object None : HomeTop

    /** Hero on Home is off and the feeds answered (`b3-home` nohero, Q61): the rows from the top, any
     *  hero's video leading New from your subscriptions. A kids Home is always so. */
    data object HeroOff : HomeTop

    /** A kids profile with no approved channel, no approved video and nothing to continue. */
    data object NothingApproved : HomeTop
}

sealed interface HomeRow {
    val key: String

    data class Videos(override val key: String, val title: String, val videos: List<VideoItem>, val now: Instant?) : HomeRow

    data class Channels(val channels: List<ChannelCard>, val now: Instant) : HomeRow {
        override val key: String = "channels"
    }

    /** For you's picks (FY-D7: the first row; from the first pick when no hero shows it). */
    data class ForYou(val picks: List<ForYouPick>, val now: Instant) : HomeRow {
        override val key: String = "foryou"
    }

    /** Videos left part-way, each with its bar and what's left (Q21: it leads the rows). */
    data class ContinueWatching(val videos: List<HistoryVideo>) : HomeRow {
        override val key: String = "continue"
    }
}

/** [heroOn] is the device's Hero on Home. For you never waits on the subscription feed (FY-D7): it
 *  shows while the feed loads and fills a profile with no subscriptions; when Home fails (offline,
 *  `b3-home` fy-offline, or unreadable), Home's error shows without it. A kids
 *  Home (`kp-kids` kidhome) has no hero; its subscriptions are "your channels", and the owner's
 *  picks follow their newest videos. */
fun homeLayout(state: HomeUiState, heroOn: Boolean = true): HomeLayout {
    state.failure?.let { return HomeLayout.Failed(it) }
    val live = (state.liveNow as? LiveNowState.Ready)?.items.orEmpty()
    val liveRow = HomeRow.Videos("live", "Live now", live, now = null).takeIf { live.isNotEmpty() }
    val continueRow = state.continueWatching.takeIf { it.isNotEmpty() }?.let(HomeRow::ContinueWatching)
    val kids = state.kids
    val pickedRow = kids?.videos?.takeIf { it.isNotEmpty() }?.let { videos ->
        HomeRow.Videos("picked", kids.ownerName?.let { "Picked for you by $it" } ?: "Picked for you", videos, now = null)
    }
    val forYou = state.forYou
    val subscriptions = state.subscriptions
    val ready = subscriptions as? SubscriptionsState.Ready
    if (forYou == null && subscriptions == SubscriptionsState.Loading) return HomeLayout.Loading
    // A hero drawn now would give way to For you's moments later (PAF-D5).
    if (forYou == null && state.heroHeld && heroOn) return HomeLayout.Loading
    if (forYou == null && subscriptions == SubscriptionsState.NoSubscriptions) {
        if (kids == null) return HomeLayout.Browse(HomeTop.Blank, listOfNotNull(continueRow, liveRow))
        val rows = listOfNotNull(continueRow, pickedRow)
        return HomeLayout.Browse(if (rows.isEmpty()) HomeTop.NothingApproved else HomeTop.HeroOff, rows)
    }
    val now = ready?.loadedAt ?: state.attemptedAt
    val hero = state.hero
    val failedFeeds = ready?.takeIf { it.feedsFailed }
    val top = when {
        // The statement keeps the top over For you's hero and Hero off (FY-D7, `b3-home` fy-feeds-failed).
        failedFeeds != null -> HomeTop.FeedsFailed(failedFeeds.channels.size)
        !heroOn || kids != null -> HomeTop.HeroOff
        hero != null -> HomeTop.HeroTop(
            hero = hero,
            avatarUrl = heroAvatar(hero.video.uploaderUrl, ready?.channels.orEmpty()),
            now = now,
            inWatchLater = state.heroInWatchLater,
            reason = forYou?.reason,
        )
        else -> HomeTop.None
    }
    // A hero drawn above shows For you's first pick; otherwise the row starts with it.
    val forYouRow = forYou?.let { shown -> HomeRow.ForYou(if (top is HomeTop.HeroTop) shown.row else shown.picks, now) }
        ?.takeIf { it.picks.isNotEmpty() }
    // With Hero off only the subscriptions' hero leads this row; For you's starts its own (FY-D7).
    val leading = ready?.hero?.video?.takeIf { !heroOn }
    val newVideos = listOfNotNull(leading) + ready?.newFromSubscriptions.orEmpty()
    val newTitle = if (kids != null) "New from your channels" else "New from your subscriptions"
    val newRow = newVideos.takeIf { it.isNotEmpty() }?.let { HomeRow.Videos("new", newTitle, it, now) }
    val channelsRow = ready?.channels?.takeIf { it.isNotEmpty() }?.let { HomeRow.Channels(it, now) }
    return HomeLayout.Browse(top, listOfNotNull(forYouRow, continueRow, newRow, pickedRow, liveRow, channelsRow))
}

/**
 * [this] without what [filter] now hides from YouTube's rows (For you, New from your
 * subscriptions, Live now): a block made from a card's menu takes them out at once and its Undo
 * brings them back (KP-D19). A For you hero that goes passes its place to the next pick, shown
 * without its page; a subscriptions hero that goes leaves the top empty until the rows reload.
 * The stores' rows (Continue watching) come filtered already. A subscriptions hero whose page the
 * filter refused ([HeroPage.Filtered]) goes the same way, with or without [filter] loaded.
 */
fun HomeUiState.shownUnder(filter: ContentFilter?): HomeUiState {
    val shows = { video: VideoItem -> filter.shows(video) }
    val shownForYou = forYou?.let { shown ->
        val kept = shown.picks.filter { shows(it.video) }
        kept.firstOrNull()?.let { first ->
            val hero = shown.hero.takeIf { it.video.id == first.video.id } ?: Hero(first.video, isNew = false, page = HeroPage.Unavailable)
            ForYouShown(hero, first.reason, kept.drop(1))
        }
    }
    val shownSubscriptions = (subscriptions as? SubscriptionsState.Ready)?.let { ready ->
        ready.copy(
            hero = ready.hero?.takeIf { it.page != HeroPage.Filtered && shows(it.video) },
            newFromSubscriptions = ready.newFromSubscriptions.filter(shows),
        )
    } ?: subscriptions
    val shownLive = (liveNow as? LiveNowState.Ready)?.let { live -> LiveNowState.Ready(live.items.filter(shows)) } ?: liveNow
    return copy(forYou = shownForYou, subscriptions = shownSubscriptions, liveNow = shownLive)
}

/** The hero's channel avatar. An uploads-tab item names its channel by the tab's URL, so both
 *  sides compare canonical (M5-D19). */
fun heroAvatar(uploaderUrl: String?, channels: List<ChannelCard>): String? {
    val channel = canonicalChannelUrl(uploaderUrl ?: return null)
    return channels.firstOrNull { canonicalChannelUrl(it.subscription.channelUrl) == channel }?.subscription?.avatarUrl
}
