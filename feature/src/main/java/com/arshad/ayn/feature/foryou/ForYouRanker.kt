package com.arshad.ayn.feature.foryou

import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.store.Impression
import com.arshad.ayn.core.store.RelatedList
import com.arshad.ayn.core.store.SKIP_SHOWS
import com.arshad.ayn.core.store.WatchEntry
import com.arshad.ayn.core.store.canonicalChannelUrl
import com.arshad.ayn.core.store.channelKey
import com.arshad.ayn.core.store.consecutiveSkips
import com.arshad.ayn.core.store.isRealWatch
import kotlin.math.exp
import kotlin.math.min
import kotlin.time.Instant
import com.arshad.ayn.feature.foryou.ForYouWeights as W

/** Why a pick is in For you: its seed ([seedId]), whose kind and subject FY-D8 words. */
data class ForYouReason(val seedId: VideoId, val kind: SeedKind, val subject: String)

data class ForYouPick(val video: VideoItem, val reason: ForYouReason)

/** The hero's pick and when it became the hero (FY-D7). */
data class PinnedPick(val pick: ForYouPick, val since: Instant)

/** For you's picks, best first: the hero's first (FY-D7), then the row's. [pinned] is the
 *  hero to hand the next ranking; null when there are no picks. [seeds] are the ranking's seeds,
 *  heaviest first: the refresher tops up their lists ([ForYouRefresher]). */
data class ForYouRanking(val picks: List<ForYouPick>, val pinned: PinnedPick?, val seeds: List<VideoId>)

/** What one ranking reads; every collection as its store gives it. */
data class ForYouInputs(
    /** [pickSeeds]'s, heaviest first. */
    val seeds: List<Seed>,
    /** The seeds' kept related lists ([com.arshad.ayn.core.store.RelatedListStore.lists]); a
     *  seed without one adds nothing, and a stale list still counts (refetching is the
     *  refresher's). */
    val lists: Map<VideoId, RelatedList>,
    val history: List<WatchEntry>,
    /** The profile's subscriptions' channel URLs, in any form. */
    val subscribedChannels: Set<String>,
    val impressions: List<Impression>,
    val hiddenVideos: Set<VideoId>,
    /** [canonicalChannelUrl]s, as the store keeps them. */
    val hiddenChannels: Set<String>,
    /** The last ranking's [ForYouRanking.pinned]. */
    val pinned: PinnedPick?,
)

/**
 * FY-D3's ranking of [inputs]' seeds' related lists, at [now].
 *
 * A candidate is a video in a seed's list that isn't in the profile's history (watched, in
 * progress, or left under 30 s: FY-D2; Continue watching is part of it), hidden, of a hidden
 * channel, live, or shorter than [W.MIN_LENGTH_SECONDS]; New from your subscriptions drops what For
 * you shows, not the reverse (FY-D7). Rotation drops an unopened card shown within [W.ROTATION]
 * or shown [SKIP_SHOWS] times. Each seed adds its weight times the card's place
 * in its list ([W.PLACE_DECAY]); the sum, scaled to the best, is the card's relevance, blended with
 * its novelty (an unwatched, unsubscribed channel; a seed whose cards were shown less) by
 * [W.NOVELTY_BASE] plus the skip ramp, then multiplied by channel affinity, freshness and the
 * channel's open rate. Picks take the best card whose seed differs from the last pick's when one
 * is left (a kept hero's seed counts as the last pick's), at most [W.PICKS_PER_CHANNEL] per channel
 * and [W.MAX_PICKS] in all, the hero included. The pinned hero stays first for [W.PIN_HOLD] while
 * it is still a candidate (rotation aside), even when no list holds it any more; otherwise the
 * best pick becomes the hero from [now]. Channels compare by [canonicalChannelUrl]. The reason is
 * the seed that adds most to the card.
 */
fun rankForYou(inputs: ForYouInputs, now: Instant): ForYouRanking {
    val context = RankContext(inputs, now)
    val pinned = inputs.pinned?.takeIf { now - it.since < W.PIN_HOLD && context.isCandidate(it.pick.video) }
    val candidates = context.candidates()
        .filter { it.video.id != pinned?.pick?.video?.id && !context.isRotatedOut(it.video.id) }
    val row = interleave(candidates, hero = pinned?.pick)
        .map { ForYouPick(it.video, ForYouReason(it.seed.videoId, it.seed.kind, it.seed.subject)) }
    val picks = listOfNotNull(pinned?.pick) + row
    return ForYouRanking(picks, pinned ?: picks.firstOrNull()?.let { PinnedPick(it, now) }, inputs.seeds.map { it.videoId })
}

private data class Ranked(val video: VideoItem, val channel: String?, val seed: Seed, val score: Double)

private class RankContext(private val inputs: ForYouInputs, private val now: Instant) {
    private val inHistory = inputs.history.mapTo(HashSet()) { it.videoId }
    private val hiddenChannels = inputs.hiddenChannels.mapTo(HashSet(), ::canonicalChannelUrl)
    private val subscribed = inputs.subscribedChannels.mapTo(HashSet(), ::canonicalChannelUrl)
    private val watchesByChannel = inputs.history.filter { it.isRealWatch() }
        .mapNotNull { it.uploaderUrl?.let(::canonicalChannelUrl) }
        .groupingBy { it }.eachCount()
    private val impressions = inputs.impressions.associateBy { it.videoId }

    /** Each channel's open rate over its shown cards, once [W.CHANNEL_MIN_SHOWN] were shown. */
    private val openRates: Map<String, Double> = inputs.impressions
        .filter { it.shownCount > 0 }
        .mapNotNull { shown -> shown.channelUrl?.let { it to shown } }
        .groupBy({ it.first }, { it.second })
        .filterValues { it.size >= W.CHANNEL_MIN_SHOWN }
        .mapValues { (_, shown) -> shown.count { it.openedAt != null }.toDouble() / shown.size }

    /** How used each seed is: the share of its list's cards already shown (FY-D3's less-used seeds). */
    private val seedShownShare: Map<VideoId, Double> = inputs.seeds.associate { seed ->
        val items = inputs.lists[seed.videoId]?.items.orEmpty()
        val shown = items.count { (impressions[it.id]?.shownCount ?: 0) > 0 }
        seed.videoId to if (items.isEmpty()) 0.0 else shown.toDouble() / items.size
    }

    private val noveltyShare = W.NOVELTY_BASE +
        W.NOVELTY_RAMP * min(1.0, consecutiveSkips(inputs.impressions, inputs.history, inputs.hiddenVideos, hiddenChannels).toDouble() / W.SKIPS_TO_FULL_RAMP)

    fun isCandidate(video: VideoItem): Boolean =
        video.id !in inHistory &&
            video.id !in inputs.hiddenVideos &&
            video.channelKey() !in hiddenChannels &&
            !video.isLive &&
            video.durationSeconds >= W.MIN_LENGTH_SECONDS

    fun isRotatedOut(id: VideoId): Boolean {
        val impression = impressions[id] ?: return false
        if (impression.openedAt != null) return false
        val lastShown = impression.lastShownAt ?: return false
        return impression.shownCount >= SKIP_SHOWS || now - lastShown < W.ROTATION
    }

    /** Every candidate with its score, unordered. */
    fun candidates(): List<Ranked> {
        val contributions = LinkedHashMap<VideoId, MutableList<Pair<Seed, Double>>>()
        val videos = HashMap<VideoId, VideoItem>()
        for (seed in inputs.seeds) {
            val items = inputs.lists[seed.videoId]?.items ?: continue
            items.distinctBy { it.id }.forEachIndexed { rank, video ->
                if (!isCandidate(video)) return@forEachIndexed
                videos.putIfAbsent(video.id, video)
                contributions.getOrPut(video.id) { mutableListOf() } += seed to seed.weight / (1 + rank * W.PLACE_DECAY)
            }
        }
        val relevance = contributions.mapValues { (_, adds) -> adds.sumOf { it.second } }
        val best = relevance.values.maxOrNull() ?: return emptyList()
        return contributions.map { (id, adds) ->
            val video = videos.getValue(id)
            val channel = video.channelKey()
            val seed = adds.maxBy { it.second }.first
            val blended = (1 - noveltyShare) * (relevance.getValue(id) / best) + noveltyShare * novelty(channel, seed)
            Ranked(video, channel, seed, blended * affinity(channel) * freshness(video) * openRateFactor(channel))
        }
    }

    /** A new channel (unwatched, unsubscribed; a card without one counts as new) for
     *  [W.NEW_CHANNEL_SHARE], a less-used seed for the rest. */
    private fun novelty(channel: String?, seed: Seed): Double {
        val newChannel = if (channel == null || (channel !in watchesByChannel && channel !in subscribed)) 1.0 else 0.0
        return W.NEW_CHANNEL_SHARE * newChannel + (1 - W.NEW_CHANNEL_SHARE) * (1 - seedShownShare.getValue(seed.videoId))
    }

    private fun affinity(channel: String?): Double {
        if (channel == null) return 1.0
        val raw = W.AFFINITY_PER_WATCH * (watchesByChannel[channel] ?: 0) + if (channel in subscribed) W.AFFINITY_SUBSCRIBED else 0.0
        return 1 + min(raw, W.AFFINITY_CAP)
    }

    private fun freshness(video: VideoItem): Double {
        val uploaded = video.uploadDate ?: return 1.0
        return 1 + W.FRESH_BOOST * halving(now - uploaded, W.FRESH_HALF_LIFE)
    }

    /** [W.IGNORED_CHANNEL] at a 0 % open rate, rising along a sigmoid to 1. */
    private fun openRateFactor(channel: String?): Double {
        val rate = channel?.let(openRates::get) ?: return 1.0
        val atZero = sigmoid(0.0)
        val rise = (sigmoid(rate) - atZero) / (1 - atZero)
        return W.IGNORED_CHANNEL + (1 - W.IGNORED_CHANNEL) * rise
    }

    private fun sigmoid(rate: Double): Double = 1 / (1 + exp(-W.CHANNEL_RATE_STEEPNESS * (rate - W.CHANNEL_RATE_MIDPOINT)))
}

/** The row after [hero]: [candidates] best first, each next card's seed differing from the last
 *  one's (the hero's first) when such a card is left, at most [W.PICKS_PER_CHANNEL] per channel
 *  with the hero counting, [W.MAX_PICKS] with the hero. */
private fun interleave(candidates: List<Ranked>, hero: ForYouPick?): List<Ranked> {
    val perChannel = HashMap<String, Int>()
    hero?.video?.channelKey()?.let { perChannel[it] = 1 }
    val open = candidates
        .sortedWith(compareByDescending<Ranked> { it.score }.thenBy { it.video.id.value })
        .toMutableList()
    val limit = W.MAX_PICKS - if (hero == null) 0 else 1
    val picks = mutableListOf<Ranked>()
    var lastSeed = hero?.reason?.seedId
    fun underCap(ranked: Ranked): Boolean = ranked.channel == null || (perChannel[ranked.channel] ?: 0) < W.PICKS_PER_CHANNEL
    while (picks.size < limit) {
        val next = open.firstOrNull { underCap(it) && it.seed.videoId != lastSeed } ?: open.firstOrNull(::underCap) ?: break
        open.remove(next)
        picks += next
        lastSeed = next.seed.videoId
        next.channel?.let { perChannel.merge(it, 1, Int::plus) }
    }
    return picks
}
