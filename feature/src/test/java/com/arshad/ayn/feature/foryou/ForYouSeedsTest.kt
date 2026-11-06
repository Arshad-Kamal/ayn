package com.arshad.ayn.feature.foryou

import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.store.SavedVideo
import com.arshad.ayn.core.store.WatchEntry
import com.arshad.ayn.feature.testVideo
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForYouSeedsTest {

    /** Weights by hand: a 1 day old finished watch 0.906, a 2 days old 60 % watch 0.820 (mostly
     *  watched counts as finished), a 40 % watch from now 0.6. */
    @Test
    fun finishedOrMostlyWatchedSeedFirstThenTheMoreRecent() {
        val history = listOf(
            entry("partly00001", NOW, positionMs = 240_000, watched = false),
            entry("finished001", NOW - 1.days),
            entry("mostly00001", NOW - 2.days, positionMs = 360_000, watched = false),
        )

        val seeds = pickSeeds(sources(history = history), NOW, UTC)

        assertEquals(listOf("finished001", "mostly00001", "partly00001"), seeds.map { it.videoId.value })
        assertTrue(seeds.all { it.kind == SeedKind.Watched })
        assertEquals("title finished001", seeds.first().subject)
    }

    @Test
    fun aBounceAShortALiveStreamAndAnUntitledEntryNeverSeed() {
        val history = listOf(
            entry("bounce00001", NOW, positionMs = 29_999, watched = false),
            entry("short000001", NOW, positionMs = 45_000, durationMs = 45_000),
            entry("live0000001", NOW, positionMs = 600_000, durationMs = null, watched = false),
            entry("untitled001", NOW, title = null),
            entry("minute00001", NOW, durationMs = 60_000),
        )

        val seeds = pickSeeds(sources(history = history), NOW, UTC)

        assertEquals(listOf("minute00001"), seeds.map { it.videoId.value })
    }

    @Test
    fun atMostTwoSeedsPerChannelInAnyUrlForm() {
        val history = listOf(
            entry("veritas0001", NOW - 1.hours, uploaderUrl = VERITASIUM),
            entry("veritas0002", NOW - 2.hours, uploaderUrl = "http://m.youtube.com/channel/UCHnyfMqiRRG1u-2MsSQLbXA/videos"),
            entry("veritas0003", NOW - 3.hours, uploaderUrl = "https://youtube.com/channel/UCHnyfMqiRRG1u-2MsSQLbXA"),
            entry("other000001", NOW - 4.hours),
        )

        val seeds = pickSeeds(sources(history = history), NOW, UTC)

        assertEquals(listOf("veritas0001", "veritas0002", "other000001"), seeds.map { it.videoId.value })
        assertEquals(VERITASIUM, seeds[1].channelUrl)
    }

    @Test
    fun atMostEightSeedsTheHeaviest() {
        val history = (1..10).map { n -> entry("watch%06d".format(n), NOW - n.hours) }

        val seeds = pickSeeds(sources(history = history), NOW, UTC)

        assertEquals((1..8).map { "watch%06d".format(it) }, seeds.map { it.videoId.value })
    }

    /** Saved weights by hand: 0.7 halving weekly from the save; a video in Watch later and a
     *  playlist seeds once; one in history (a bounce) is history's, so never a saved seed. */
    @Test
    fun savedVideosSeedUnlessInHistoryOrShort() {
        val history = (1..5).map { n -> entry("watch%06d".format(n), NOW - 30.days) } +
            entry("bounced0001", NOW, positionMs = 5_000, watched = false)
        val watchLater = listOf(
            saved("later000001", NOW - 1.days),
            saved("both0000001", NOW - 3.days),
            saved("bounced0001", NOW),
            saved("short000001", NOW, durationSeconds = 59),
        )
        val playlists = listOf(saved("both0000001", NOW - 3.days), saved("unknownlen1", NOW - 2.days, durationSeconds = -1))

        val seeds = pickSeeds(sources(history = history, watchLater = watchLater, playlists = playlists), NOW, UTC)

        val savedSeeds = seeds.filter { it.kind == SeedKind.Saved }
        assertEquals(listOf("later000001", "unknownlen1", "both0000001"), savedSeeds.map { it.videoId.value })
        assertEquals("title later000001", savedSeeds.first().subject)
    }

    @Test
    fun underFiveRealWatchesTheTwoNewestSubscriptionUploadsSeed() {
        val history = (1..4).map { n -> entry("watch%06d".format(n), NOW - n.days) }
        val uploads = listOf(
            upload("liveupload1", NOW, "Live channel").copy(isLive = true),
            upload("upload00001", NOW - 3.days, "Older"),
            upload("upload00002", NOW - 1.hours, "Tom Scott"),
            upload("upload00003", NOW - 1.days, "fern"),
        )

        val followed = pickSeeds(sources(history = history, uploads = uploads), NOW, UTC).filter { it.kind == SeedKind.Followed }

        assertEquals(listOf("upload00002", "upload00003"), followed.map { it.videoId.value })
        assertEquals(listOf("Tom Scott", "fern"), followed.map { it.subject })
    }

    @Test
    fun subscriptionUploadsKeepTheirSlotsAgainstFresherSavedVideos() {
        val history = (1..4).map { n -> entry("watch%06d".format(n), NOW - 30.days) }
        val watchLater = (1..8).map { n -> saved("later%06d".format(n), NOW - 1.hours) }
        val uploads = listOf(upload("upload00001", NOW - 2.days, "Tom Scott"), upload("upload00002", NOW - 3.days, "fern"))

        val seeds = pickSeeds(sources(history = history, watchLater = watchLater, uploads = uploads), NOW, UTC)

        assertEquals(8, seeds.size)
        assertEquals(listOf("upload00001", "upload00002"), seeds.filter { it.kind == SeedKind.Followed }.map { it.videoId.value })
    }

    @Test
    fun fiveRealWatchesSeedNoSubscriptionUploads() {
        val history = (1..5).map { n -> entry("watch%06d".format(n), NOW - n.days) }
        val uploads = listOf(upload("upload00001", NOW - 1.hours, "Tom Scott"))

        val seeds = pickSeeds(sources(history = history, uploads = uploads), NOW, UTC)

        assertTrue(seeds.none { it.kind == SeedKind.Followed })
    }

    /**
     * Monday 20:00 is the weekday evening bucket. By hand: a weekday-morning watch 12 h ago weighs
     * 0.952, a Friday-evening watch 3 days 1 h ago 0.740; with five real watches in the evening
     * bucket the evening ones are boosted 1.5× (Friday's to 1.110) and Friday's leads.
     */
    @Test
    fun aSeedFromTheCurrentTimeBucketGainsOnceTheBucketHasHistory() {
        val morning = entry("morning0001", Instant.parse("2026-06-29T08:00:00Z"))
        val friday = entry("friday00001", Instant.parse("2026-06-26T19:00:00Z"))
        val otherEvenings = listOf("2026-06-22T21:00:00Z", "2026-06-23T18:30:00Z", "2026-06-24T22:00:00Z", "2026-06-25T20:00:00Z")
            .mapIndexed { n, at -> entry("evening%04d".format(n), Instant.parse(at)) }

        val boosted = pickSeeds(sources(history = listOf(morning, friday) + otherEvenings), NOW, UTC).map { it.videoId.value }
        val unboosted = pickSeeds(sources(history = listOf(morning, friday) + otherEvenings.drop(1)), NOW, UTC).map { it.videoId.value }

        assertEquals(listOf("friday00001", "evening0003", "morning0001", "evening0002", "evening0001", "evening0000"), boosted)
        assertEquals(listOf("morning0001", "friday00001", "evening0003", "evening0002", "evening0001"), unboosted)
    }

    private fun sources(
        history: List<WatchEntry> = emptyList(),
        watchLater: List<SavedVideo> = emptyList(),
        playlists: List<SavedVideo> = emptyList(),
        uploads: List<VideoItem> = emptyList(),
    ): SeedSources = SeedSources(history, watchLater, playlists, uploads)

    private fun saved(id: String, at: Instant, durationSeconds: Long = 600): SavedVideo =
        SavedVideo(testVideo(id).copy(durationSeconds = durationSeconds), at.toEpochMilliseconds())

    private fun upload(id: String, at: Instant, channel: String): VideoItem =
        testVideo(id, uploaderName = channel).copy(uploadDate = at, durationSeconds = -1)
}
