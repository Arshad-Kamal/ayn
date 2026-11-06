package com.arshad.ayn.core.store

import com.arshad.ayn.core.access.VideoId
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/** FY-D3's skips, derived from impressions and the watch history: what counts, and what resets. */
class ConsecutiveSkipsTest {

    @Test
    fun aCardShownThreeTimesAndNeverOpenedIsASkip() {
        val skips = skips(listOf(shown("aaaaaaaaaa1", times = 3, at = 10)), history = emptyList())

        assertEquals(1, skips)
    }

    @Test
    fun aCardShownTwiceIsNotYetASkip() {
        assertEquals(0, skips(listOf(shown("aaaaaaaaaa1", times = 2, at = 10)), history = emptyList()))
    }

    @Test
    fun aCardOpenedAndLeftUnderThirtySecondsIsASkip() {
        val impressions = listOf(opened("aaaaaaaaaa1", at = 10))

        assertEquals(1, skips(impressions, listOf(entry("aaaaaaaaaa1", positionMs = 29_999, at = 11))))
    }

    @Test
    fun aCardOpenedAndWatchedIsNotASkipAndResetsTheCount() {
        val impressions = listOf(shown("aaaaaaaaaa1", times = 3, at = 5), opened("bbbbbbbbbb1", at = 10))

        assertEquals(0, skips(impressions, listOf(entry("bbbbbbbbbb1", positionMs = 30_000, at = 12))))
    }

    @Test
    fun onlySkipsAfterTheLastRealWatchCount() {
        val impressions = listOf(
            shown("aaaaaaaaaa1", times = 3, at = 5),
            shown("bbbbbbbbbb1", times = 4, at = 20),
            opened("cccccccccc1", at = 25),
        )
        val history = listOf(
            entry("dddddddddd1", positionMs = 0, watched = true, at = 15),
            entry("cccccccccc1", positionMs = 4_000, at = 26),
        )

        assertEquals(2, skips(impressions, history))
    }

    /** Reopening only renames the entry's card; its earlier real watch still stands. */
    @Test
    fun aCardOpenedAgainAfterARealWatchIsNotASkip() {
        val impressions = listOf(opened("aaaaaaaaaa1", at = 10))

        assertEquals(0, skips(impressions, listOf(entry("aaaaaaaaaa1", positionMs = 300_000, at = 5))))
    }

    @Test
    fun aHiddenCardIsFeedbackNotASkip() {
        val impressions = listOf(shown("aaaaaaaaaa1", times = 3, at = 10), shown("bbbbbbbbbb1", times = 3, at = 11))

        assertEquals(1, skips(impressions, history = emptyList(), hiddenVideos = setOf(VideoId("aaaaaaaaaa1"))))
    }

    @Test
    fun aHiddenChannelsCardsAreFeedbackNotSkips() {
        val impressions = listOf(shown("aaaaaaaaaa1", times = 3, at = 10, channel = CHANNEL), shown("bbbbbbbbbb1", times = 3, at = 11))

        assertEquals(1, skips(impressions, history = emptyList(), hiddenChannels = setOf(CHANNEL)))
    }

    @Test
    fun anOpenedCardWithNoWatchEntryIsNotASkip() {
        assertEquals(0, skips(listOf(opened("aaaaaaaaaa1", at = 10)), history = emptyList()))
    }

    private fun skips(
        impressions: List<Impression>,
        history: List<WatchEntry>,
        hiddenVideos: Set<VideoId> = emptySet(),
        hiddenChannels: Set<String> = emptySet(),
    ): Int = consecutiveSkips(impressions, history, hiddenVideos, hiddenChannels)

    private fun shown(id: String, times: Int, at: Long, channel: String? = null) =
        Impression(VideoId(id), channel, times, Instant.fromEpochMilliseconds(at), openedAt = null)

    private fun opened(id: String, at: Long) =
        Impression(VideoId(id), null, shownCount = 1, lastShownAt = Instant.fromEpochMilliseconds(at - 1), openedAt = Instant.fromEpochMilliseconds(at))

    private fun entry(id: String, positionMs: Long, at: Long, watched: Boolean = false) = WatchEntry(
        videoId = VideoId(id),
        title = null,
        uploaderName = null,
        uploaderUrl = null,
        thumbnailUrl = null,
        positionMs = positionMs,
        durationMs = 3_600_000,
        watched = watched,
        lastWatchedAtMillis = at,
        updatedAtMillis = at,
    )
}

private const val CHANNEL = "https://www.youtube.com/channel/UCHnyfMqiRRG1u-2MsSQLbXA"
