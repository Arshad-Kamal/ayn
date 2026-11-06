package com.arshad.ayn.feature.foryou

import com.arshad.ayn.core.access.recorded
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.access.newpipe.CHAPTERS_VIDEO_FLOW
import com.arshad.ayn.core.access.newpipe.CHAPTERS_VIDEO_ID
import com.arshad.ayn.core.access.newpipe.FIXED_VIDEO_ID
import com.arshad.ayn.core.access.newpipe.LIVE_VIDEO_FLOW
import com.arshad.ayn.core.access.newpipe.RecordedLiveVideo
import com.arshad.ayn.core.access.newpipe.STORYBOARDS_VIDEO_FLOW
import com.arshad.ayn.core.store.Impression
import com.arshad.ayn.core.store.RelatedList
import com.arshad.ayn.core.store.WatchEntry
import java.time.ZoneId
import kotlin.time.Duration
import kotlin.time.Instant

/** A Monday evening (UTC). */
internal val NOW: Instant = Instant.parse("2026-06-29T20:00:00Z")
internal val UTC: ZoneId = ZoneId.of("UTC")

internal const val VERITASIUM = "https://www.youtube.com/channel/UCHnyfMqiRRG1u-2MsSQLbXA"

/**
 * The related lists of the recorded watch pages, parsed through the real seam. Upload dates are
 * dropped: the fork reads "9 mo ago" against the replay's own clock, which no fixed clock matches.
 */
internal object RecordedLists {
    /** 20 videos; Veritasium's at 0, 7, 10 and 17, fern's at 2 and 16. */
    val chapters: List<VideoItem> by lazy { related(CHAPTERS_VIDEO_FLOW, CHAPTERS_VIDEO_ID) }

    /** 26 videos; the 16th ([RecordedLiveVideo] page's `82CIirYB7RY`) is live. */
    val live: List<VideoItem> by lazy { related(LIVE_VIDEO_FLOW, RecordedLiveVideo.ID) }

    /** 25 videos (the page's mix isn't a video). */
    val storyboards: List<VideoItem> by lazy { related(STORYBOARDS_VIDEO_FLOW, FIXED_VIDEO_ID) }

    private fun related(flow: String, id: String): List<VideoItem> =
        recorded(flow) { video(VideoId(id)) }.relatedVideos.map { it.copy(uploadDate = null) }
}

internal fun ForYouRanking.ids(): List<String> = picks.map { it.video.id.value }

internal fun relatedList(items: List<VideoItem>): RelatedList = RelatedList(items, fetchedAt = NOW, failedAt = null)

internal fun seed(
    id: String,
    kind: SeedKind = SeedKind.Watched,
    subject: String = "seed $id",
    channelUrl: String? = null,
    weight: Double = 1.0,
): Seed = Seed(VideoId(id), kind, subject, channelUrl, weight)

internal fun entry(
    id: String,
    lastWatchedAt: Instant,
    positionMs: Long = 0,
    durationMs: Long? = 600_000,
    watched: Boolean = true,
    title: String? = "title $id",
    uploaderUrl: String? = "https://www.youtube.com/channel/UC$id",
): WatchEntry = WatchEntry(
    videoId = VideoId(id),
    title = title,
    uploaderName = "uploader $id",
    uploaderUrl = uploaderUrl,
    thumbnailUrl = null,
    positionMs = positionMs,
    durationMs = durationMs,
    watched = watched,
    lastWatchedAtMillis = lastWatchedAt.toEpochMilliseconds(),
    updatedAtMillis = lastWatchedAt.toEpochMilliseconds(),
)

internal fun shown(id: String, count: Int, ago: Duration, channelUrl: String? = null, openedAgo: Duration? = null): Impression =
    Impression(VideoId(id), channelUrl, count, lastShownAt = NOW - ago, openedAt = openedAgo?.let { NOW - it })
