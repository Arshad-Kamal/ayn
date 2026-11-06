package com.arshad.ayn.feature.subscriptions

import android.util.Log
import com.arshad.ayn.core.access.AccessException
import com.arshad.ayn.core.access.AccessFailure
import com.arshad.ayn.core.access.ChannelTab
import com.arshad.ayn.core.access.Extractor
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.filter.ContentFilter
import com.arshad.ayn.core.filter.PinnedFilter
import com.arshad.ayn.core.store.ChannelFeedStore
import com.arshad.ayn.core.store.RestrictedMode
import com.arshad.ayn.core.store.StoredChannelFeed
import com.arshad.ayn.core.store.Subscription
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * The merged subscriptions feed: fetches every subscribed channel's RSS feed with bounded
 * concurrency and merges newest-first by upload instant. A channel whose RSS fails, or answers
 * without videos, falls back to its uploads tab (YouTube's RSS endpoint has had multi-hour
 * outages); each fallback is reported to [onRssFallback] with its reason, so RSS recovery shows
 * in the log. A channel failing both ways is left out with its uploads tab's failure (one
 * channel never fails the feed); only every-channel-failed surfaces an error. Each channel's RSS
 * Shorts are kept beside its videos (M7-D13); the uploads-tab fallback brings none.
 *
 * App-scoped and shared by Home and Subscriptions: successful channel fetches are cached for
 * [CacheTtl], so moving between the two screens doesn't refetch every channel. Failures are
 * never cached, so Retry always refetches them. The extractor filters what it answers for the
 * session profile (KP-D1), so a cached feed is reused only under the filter it was fetched with
 * ([currentFilter]): another profile, a rule edit or a Restricted Mode change fetches again. A
 * load reads the filter once and pins it ([PinnedFilter]) for every request it makes, so what it
 * fetches was filtered with exactly the filter it is cached under.
 *
 * With [channelFeeds], each load also keeps the channels it answered, each with its fetch time, for
 * the profile and filter it started under, written in [writeScope] after the load returns, and
 * [stored] reads them back for the next start (PERF P9c). A channel the filter refuses is never
 * kept: its empty answer would read as "no uploads" once the channel is allowed again.
 */
class SubscriptionsFeed(
    private val extractor: Extractor,
    private val clock: Clock = Clock.System,
    /** The session profile's filter now; `null` (an unfiltered extractor) is one filter. It is
     *  pinned over the extractor's own read for a whole load ([PinnedFilter]), so it must be the
     *  same source the extractor filters with. */
    private val currentFilter: suspend () -> ContentFilter? = { null },
    private val channelFeeds: ChannelFeedStore? = null,
    /** Outlives the loads: their writes finish after the result is returned. */
    private val writeScope: CoroutineScope? = null,
    private val onRssFallback: (channelUrl: String, reason: String) -> Unit = ::logRssFallback,
) {
    init {
        require(channelFeeds == null || writeScope != null) { "kept feeds need a write scope" }
    }

    /** [loadedAt] is the instant ages are measured against (New badge, relative labels).
     *  [videosByChannel] maps each channel URL that answered to its videos (possibly none),
     *  newest first; [failures] maps each channel URL that failed to why. [shorts] and
     *  [shortsByChannel] are the answered channels' Shorts in the same shape. */
    data class Result(
        val entries: List<VideoItem>,
        val videosByChannel: Map<String, List<VideoItem>>,
        val failures: Map<String, AccessFailure>,
        val loadedAt: Instant,
        val shorts: List<VideoItem> = emptyList(),
        val shortsByChannel: Map<String, List<VideoItem>> = emptyMap(),
    ) {
        val failedChannels: Int get() = failures.size

        /** Each answered channel's newest video; channels without one are absent. */
        val newestByChannel: Map<String, VideoItem> =
            videosByChannel.mapNotNull { (url, videos) -> videos.firstOrNull()?.let { url to it } }.toMap()

        /** No channel has videos to show, fresh or kept: every one failed. */
        val nothingAnswered: Boolean get() = videosByChannel.isEmpty()

        /** Failed channels with nothing to show; one whose kept feed shows ([over]) isn't missing. */
        val missingChannels: Set<String> get() = failures.keys - videosByChannel.keys

        /** This fresh result over [stored], channel by channel: a channel that failed now keeps its
         *  stored videos and Shorts (and stays in [failures]); every answered channel is this one's. */
        fun over(stored: Result): Result {
            val kept = failures.keys.filter { it in stored.videosByChannel }
            if (kept.isEmpty()) return this
            return resultOf(
                videosByChannel = videosByChannel + kept.associateWith { stored.videosByChannel.getValue(it) },
                shortsByChannel = shortsByChannel + kept.associateWith { stored.shortsByChannel[it].orEmpty() },
                failures = failures,
                loadedAt = loadedAt,
            )
        }
    }

    /** The profile's kept feed ([stored]): [result] as it shows now, its newest channel fetched at
     *  [fetchedAt]. */
    class Stored(val result: Result, val fetchedAt: Instant) {
        /** How old the feed was when read. */
        val age: Duration get() = result.loadedAt - fetchedAt
    }

    private class ChannelFeed(val videos: List<VideoItem>, val shorts: List<VideoItem>) {
        /** What [filter] lets through: the cache and the kept feed answer no looser than it, even
         *  for what an extractor that ignored the pin, or an older filter, let in. */
        fun filteredBy(filter: ContentFilter?): ChannelFeed = if (filter == null) {
            this
        } else {
            ChannelFeed(
                videos = videos.filter { filter.allows(it) },
                shorts = if (filter.hidesShorts) emptyList() else shorts.filter { filter.allows(it) },
            )
        }
    }

    private sealed interface ChannelAnswer {
        /** [fetchedAt] is when the fetch behind [feed] started (this load's, or the cache's). */
        class Answered(val feed: ChannelFeed, val fetchedAt: Instant) : ChannelAnswer

        class Failed(val failure: AccessFailure) : ChannelAnswer
    }

    private class CachedFeed(val fetchStartedAt: Instant, val filter: ContentFilter?, val feed: ChannelFeed)

    private val cache = ConcurrentHashMap<String, CachedFeed>()
    private val fetchPermits = Semaphore(FeedConcurrency)

    suspend fun load(subscriptions: List<Subscription>): Result {
        val profileId = channelFeeds?.profileNow
        val filter = currentFilter()
        // A switch between the two reads would keep this load's channels under the wrong profile.
        val keepFor = profileId?.takeIf { channelFeeds?.profileNow == it }
        return withContext(filter?.let(::PinnedFilter) ?: EmptyCoroutineContext) {
            val answers = subscriptions
                .map { subscription -> async { subscription.channelUrl to channelAnswer(subscription.channelUrl, filter) } }
                .awaitAll()
            val feeds = answers
                .mapNotNull { (url, answer) -> (answer as? ChannelAnswer.Answered)?.let { url to it.feed } }
                .toMap()
            val videosByChannel = feeds.mapValues { (_, feed) -> feed.videos.distinctNewestFirst() }
            val shortsByChannel = feeds.mapValues { (_, feed) -> feed.shorts.distinctNewestFirst() }
            if (keepFor != null) {
                keep(keepFor, filter, answers.toMap(), videosByChannel, shortsByChannel)
            }
            resultOf(
                videosByChannel = videosByChannel,
                shortsByChannel = shortsByChannel,
                failures = answers
                    .mapNotNull { (url, answer) -> (answer as? ChannelAnswer.Failed)?.let { url to it.failure } }
                    .toMap(),
                loadedAt = clock.now(),
            )
        }
    }

    /**
     * The session profile's kept feed for [subscriptions], filtered by the session's filter now,
     * its ages measured from now; `null` when nothing is kept for any of them, there is no store,
     * or every channel is fresh in the cache (a load answers at once then). A store that can't be
     * read counts as nothing kept.
     */
    suspend fun stored(subscriptions: List<Subscription>): Stored? {
        val store = channelFeeds ?: return null
        val profileId = store.profileNow ?: return null
        val filter = currentFilter()
        if (store.profileNow != profileId) return null
        if (subscriptions.all { freshCached(it.channelUrl, filter) != null }) return null
        val kept = try {
            store.feeds(profileId, filter.level)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LOG_TAG, "kept feeds read failed: ${e.javaClass.simpleName}")
            return null
        }
        val shown = subscriptions
            .filter { filter?.allowsChannel(it.channelUrl, it.name) != false }
            .mapNotNull { subscription -> kept[subscription.channelUrl]?.let { subscription.channelUrl to it } }
            .toMap()
        if (shown.isEmpty()) return null
        val feeds = shown.mapValues { (_, kept) -> ChannelFeed(kept.videos, kept.shorts).filteredBy(filter) }
        val result = resultOf(
            videosByChannel = feeds.mapValues { (_, feed) -> feed.videos.distinctNewestFirst() },
            shortsByChannel = feeds.mapValues { (_, feed) -> feed.shorts.distinctNewestFirst() },
            failures = emptyMap(),
            loadedAt = clock.now(),
        )
        return Stored(result, fetchedAt = shown.values.maxOf { it.fetchedAt })
    }

    /** Keeps for [profileId] the channels this load answered that [filter] allows, in [writeScope]:
     *  the cache's answers too (a cancelled load may have fetched them), each with its own fetch
     *  time, so the store skips what it already holds. */
    private fun keep(
        profileId: Long,
        filter: ContentFilter?,
        answers: Map<String, ChannelAnswer>,
        videosByChannel: Map<String, List<VideoItem>>,
        shortsByChannel: Map<String, List<VideoItem>>,
    ) {
        val store = channelFeeds ?: return
        val fetched = answers.mapNotNull { (url, answer) ->
            val answered = answer as? ChannelAnswer.Answered ?: return@mapNotNull null
            if (filter?.allowsChannel(url) == false) return@mapNotNull null
            url to StoredChannelFeed(videosByChannel.getValue(url), shortsByChannel.getValue(url), answered.fetchedAt)
        }.toMap()
        if (fetched.isEmpty()) return
        checkNotNull(writeScope).launch {
            try {
                store.record(profileId, filter.level, fetched)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(LOG_TAG, "kept feeds write failed: ${e.javaClass.simpleName}")
            }
        }
    }

    private suspend fun channelAnswer(channelUrl: String, filter: ContentFilter?): ChannelAnswer =
        try {
            cachedChannelFeed(channelUrl, filter)
        } catch (e: AccessException) {
            ChannelAnswer.Failed(e.failure)
        }

    /** One channel's feed, from the cache while fresh; throws when both sources fail. */
    private suspend fun cachedChannelFeed(channelUrl: String, filter: ContentFilter?): ChannelAnswer.Answered {
        freshCached(channelUrl, filter)?.let { return it }
        return fetchPermits.withPermit {
            // Re-checked under the permit: a load queued behind another one reuses what that
            // load fetched while this one waited.
            freshCached(channelUrl, filter) ?: fetch(channelUrl, filter)
        }
    }

    /** The cached answer while fresh, filtered again on the way out ([ChannelFeed.filteredBy]). */
    private fun freshCached(channelUrl: String, filter: ContentFilter?): ChannelAnswer.Answered? {
        val cached = cache[channelUrl]?.takeIf { it.filter == filter } ?: return null
        // A negative age (the wall clock stepped back) counts as stale.
        if (clock.now() - cached.fetchStartedAt !in Duration.ZERO..<CacheTtl) return null
        return ChannelAnswer.Answered(cached.feed.filteredBy(filter), cached.fetchStartedAt)
    }

    /**
     * The channel's RSS feed; its uploads tab's videos when RSS can't be used, reported to
     * [onRssFallback]. An answer with no videos counts too: the vendored feed extractor fails only
     * on 404 (and the transport on 429), so a 5xx/interstitial parses as an empty feed, RSS has
     * answered 200-with-no-entries during outages, and a Shorts-only RSS window has no videos while
     * the uploads tab does (its Shorts are kept).
     */
    private suspend fun fetch(channelUrl: String, filter: ContentFilter?): ChannelAnswer.Answered {
        val startedAt = clock.now()
        val rss = try {
            extractor.channelFeed(channelUrl)
        } catch (rssFailure: AccessException) {
            onRssFallback(channelUrl, rssFailure.cause.toString())
            null
        }
        val rssVideos = rss?.items?.filterIsInstance<VideoItem>().orEmpty()
        if (rss != null && rssVideos.isEmpty()) onRssFallback(channelUrl, "no videos in the answer")
        val feed = ChannelFeed(
            videos = rssVideos.ifEmpty { uploadsTabVideos(channelUrl) },
            shorts = rss?.shorts.orEmpty(),
        )
        // A slow fetch that started earlier (a superseded refresh) never replaces a newer one under
        // the same filter; an entry stamped in the future (the wall clock stepped back) always gets
        // replaced.
        cache.merge(channelUrl, CachedFeed(startedAt, filter, feed)) { cached, fetched ->
            val replace = fetched.filter != cached.filter ||
                fetched.fetchStartedAt > cached.fetchStartedAt ||
                cached.fetchStartedAt > clock.now()
            if (replace) fetched else cached
        }
        return ChannelAnswer.Answered(feed, fetchedAt = startedAt)
    }

    private suspend fun uploadsTabVideos(channelUrl: String): List<VideoItem> =
        extractor.channelTab(channelUrl, ChannelTab.VIDEOS).items.filterIsInstance<VideoItem>()

    companion object {
        /** Bounded concurrent extraction requests across all loads (spec risk "Subs feed fetch cost"). */
        const val FeedConcurrency = 4

        /** How long a channel's fetched feed is reused; YouTube's own RSS updates lag minutes. */
        val CacheTtl = 5.minutes

        /**
         * Feed order, unknown dates last. Sort with it stably: equal instants keep source order.
         * Uploads-tab dates are coarse ("3 months ago"), so the tab's own newest-first order is
         * the better tie-break. Sub-day ages ("2 hours ago") are parsed per item against a fresh
         * clock, so two equal ones can still swap; accepted.
         */
        val NewestFirst: Comparator<VideoItem> = compareByDescending { it.uploadDate }
    }
}

private const val LOG_TAG = "KeFeed"

/** The Restricted Mode a load under this filter fetches with; no filter fetches without it. */
private val ContentFilter?.level: RestrictedMode get() = this?.restrictedMode ?: RestrictedMode.Off

private fun resultOf(
    videosByChannel: Map<String, List<VideoItem>>,
    shortsByChannel: Map<String, List<VideoItem>>,
    failures: Map<String, AccessFailure>,
    loadedAt: Instant,
): SubscriptionsFeed.Result = SubscriptionsFeed.Result(
    entries = videosByChannel.values.flatten().distinctNewestFirst(),
    videosByChannel = videosByChannel,
    failures = failures,
    loadedAt = loadedAt,
    shorts = shortsByChannel.values.flatten().distinctNewestFirst(),
    shortsByChannel = shortsByChannel,
)

/** A video id repeated within a feed or across feeds would crash a lazy-list key (CMP-LAZY-01);
 *  first occurrence wins. The stable sort keeps source order for equals. */
private fun List<VideoItem>.distinctNewestFirst(): List<VideoItem> = distinctBy { it.id.value }.sortedWith(SubscriptionsFeed.NewestFirst)

private fun logRssFallback(channelUrl: String, reason: String) {
    Log.w(LOG_TAG, "RSS unusable for $channelUrl ($reason); trying the uploads tab")
}

private val NewBadgeAge = 4.days

/** The "New" badge: uploaded within [NewBadgeAge] of [now]; unknown dates never wear it. */
fun VideoItem.isNewInFeed(now: Instant): Boolean {
    val uploaded = uploadDate ?: return false
    return now - uploaded <= NewBadgeAge
}
