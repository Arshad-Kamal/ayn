package com.arshad.ayn.feature.foryou

import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.store.SavedVideo
import com.arshad.ayn.core.store.WatchEntry
import com.arshad.ayn.core.store.WatchThresholds
import com.arshad.ayn.core.store.canonicalChannelUrl
import com.arshad.ayn.core.store.channelKey
import com.arshad.ayn.core.store.isRealWatch
import java.time.DayOfWeek
import java.time.ZoneId
import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import com.arshad.ayn.feature.foryou.ForYouWeights as W

/** Where a seed came from; it words the card's reason (FY-D8). */
enum class SeedKind {
    /** "Because you watched <title>". */
    Watched,

    /** "Because you saved <title>": Watch later and playlists. */
    Saved,

    /** "Because you follow <channel>": a subscription upload. */
    Followed,
}

/** A video whose related list feeds For you. */
data class Seed(
    val videoId: VideoId,
    val kind: SeedKind,
    /** The reason's subject: the video's title, or its channel's name for [SeedKind.Followed]. */
    val subject: String,
    /** [canonicalChannelUrl]; null when the source had none. */
    val channelUrl: String?,
    val weight: Double,
)

/** What a profile's seeds are picked from; every list as its store gives it. */
data class SeedSources(
    val history: List<WatchEntry>,
    val watchLater: List<SavedVideo>,
    val playlistVideos: List<SavedVideo>,
    /** Recent uploads of the profile's subscriptions (the feed's). */
    val subscriptionUploads: List<VideoItem>,
)

/**
 * FY-D2's seeds, heaviest first: up to [W.SEED_COUNT], at most [W.SEEDS_PER_CHANNEL] per channel.
 * History seeds are real watches (played to [WatchThresholds.MIN_RESUME_MS] or watched), finished
 * or mostly watched weighing more, boosted when last watched in [now]'s time bucket once that
 * bucket holds [W.BUCKET_MIN_WATCHES] real watches. Watch later and playlist videos not in
 * history seed as saved. With fewer than [W.FEW_WATCHES] real watches, up to [W.FOLLOWED_SEEDS]
 * of the newest subscription uploads seed too, in slots of their own. Every seed's weight halves each
 * [W.SEED_HALF_LIFE]. A Short played in the 16:9 player carries no Short marker (PAF-D6) and no
 * entry carries a live one, so a history seed needs a known length of [W.MIN_LENGTH_SECONDS] or
 * more (a live stream's is unknown); a saved or followed video is left out when live or when its
 * known length is shorter (RSS and saved cards may not know theirs; a saved card never says live).
 */
fun pickSeeds(sources: SeedSources, now: Instant, zone: ZoneId): List<Seed> {
    val realWatches = sources.history.filter { it.isRealWatch() }
    val bucket = TimeBucket.of(now, zone)
    val inBucket = realWatches.filterTo(HashSet()) { TimeBucket.of(Instant.fromEpochMilliseconds(it.lastWatchedAtMillis), zone) == bucket }
    val bucketHasHistory = inBucket.size >= W.BUCKET_MIN_WATCHES
    val inHistory = sources.history.mapTo(HashSet()) { it.videoId }

    val watched = realWatches.mapNotNull { entry ->
        val title = entry.title?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val durationMs = entry.durationMs?.takeIf { it >= W.MIN_LENGTH_SECONDS * 1000 } ?: return@mapNotNull null
        val finished = entry.watched || entry.positionMs.toDouble() / durationMs >= W.MOSTLY_WATCHED
        val boost = if (bucketHasHistory && entry in inBucket) W.BUCKET_BOOST else 1.0
        Seed(
            videoId = entry.videoId,
            kind = SeedKind.Watched,
            subject = title,
            channelUrl = entry.uploaderUrl?.let(::canonicalChannelUrl),
            weight = (if (finished) W.FINISHED_SEED else W.PARTLY_WATCHED_SEED) * boost *
                halving(now - Instant.fromEpochMilliseconds(entry.lastWatchedAtMillis), W.SEED_HALF_LIFE),
        )
    }
    val saved = (sources.watchLater + sources.playlistVideos)
        .filter { it.video.id !in inHistory && it.video.canSeed() }
        .map { saved ->
            Seed(
                videoId = saved.video.id,
                kind = SeedKind.Saved,
                subject = saved.video.title,
                channelUrl = saved.video.channelKey(),
                weight = W.SAVED_SEED * halving(now - Instant.fromEpochMilliseconds(saved.addedAtMillis), W.SEED_HALF_LIFE),
            )
        }
    val followed = if (realWatches.size >= W.FEW_WATCHES) {
        emptyList()
    } else {
        val savedIds = saved.mapTo(HashSet()) { it.videoId }
        sources.subscriptionUploads
            .filter { video -> video.id !in inHistory && video.id !in savedIds && video.canSeed() && !video.uploaderName.isNullOrBlank() }
            .sortedByDescending { it.uploadDate }
            .take(W.FOLLOWED_SEEDS)
            .map { video ->
                Seed(
                    videoId = video.id,
                    kind = SeedKind.Followed,
                    subject = video.uploaderName.orEmpty(),
                    channelUrl = video.channelKey(),
                    weight = W.FOLLOWED_SEED * (video.uploadDate?.let { halving(now - it, W.SEED_HALF_LIFE) } ?: 1.0),
                )
            }
    }

    // FY-D2's "also": followed seeds keep their slots however many saved videos outweigh them.
    val perChannel = HashMap<String, Int>()
    val kept = capped(watched + saved, W.SEED_COUNT - followed.size, perChannel) + capped(followed, followed.size, perChannel)
    return kept.sortedWith(heaviestFirst)
}

private val heaviestFirst = compareByDescending<Seed> { it.weight }.thenBy { it.videoId.value }

/** Up to [limit] of [seeds], heaviest first, each video once (a video saved twice seeds at its
 *  heavier weight), at most [W.SEEDS_PER_CHANNEL] per channel counting [perChannel]'s, which it
 *  updates. */
private fun capped(seeds: List<Seed>, limit: Int, perChannel: MutableMap<String, Int>): List<Seed> =
    seeds.sortedWith(heaviestFirst)
        .asSequence()
        .distinctBy { it.videoId }
        .filter { seed ->
            val channel = seed.channelUrl ?: return@filter true
            val count = perChannel.getOrDefault(channel, 0)
            (count < W.SEEDS_PER_CHANNEL).also { fits -> if (fits) perChannel[channel] = count + 1 }
        }
        .take(limit)
        .toList()

/** A saved or followed video that may seed: not live, not known to be under
 *  [W.MIN_LENGTH_SECONDS] (an unknown length, -1, passes), titled. */
private fun VideoItem.canSeed(): Boolean = !isLive && durationSeconds !in 0..<W.MIN_LENGTH_SECONDS && title.isNotBlank()

/** 1 at [age] zero, halving each [halfLife]; a time in the future counts as now. */
internal fun halving(age: Duration, halfLife: Duration): Double = 0.5.pow(age.coerceAtLeast(Duration.ZERO) / halfLife)

/** One of Flow's 8 time buckets: weekday or weekend × morning, afternoon, evening or night. */
private data class TimeBucket(val weekend: Boolean, val part: DayPart) {
    enum class DayPart { Morning, Afternoon, Evening, Night }

    companion object {
        fun of(at: Instant, zone: ZoneId): TimeBucket {
            val local = at.toJavaInstant().atZone(zone)
            val part = when (local.hour) {
                in 6..11 -> DayPart.Morning
                in 12..17 -> DayPart.Afternoon
                in 18..23 -> DayPart.Evening
                else -> DayPart.Night
            }
            return TimeBucket(local.dayOfWeek == DayOfWeek.SATURDAY || local.dayOfWeek == DayOfWeek.SUNDAY, part)
        }
    }
}
