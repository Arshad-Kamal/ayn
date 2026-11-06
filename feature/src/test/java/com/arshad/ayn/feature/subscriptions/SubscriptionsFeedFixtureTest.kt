package com.arshad.ayn.feature.subscriptions

import com.arshad.ayn.core.access.newpipe.FixtureDownloader
import com.arshad.ayn.core.access.newpipe.NewPipeAccess
import com.arshad.ayn.core.access.newpipe.RECORDED_CHANNEL_URL
import com.arshad.ayn.core.access.newpipe.RecordedChannel
import com.arshad.ayn.feature.TestClock
import com.arshad.ayn.feature.testSubscription
import java.time.DayOfWeek
import java.time.ZoneOffset
import com.arshad.ayn.core.store.FakeChannelFeedStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-layer tests: recorded payloads replayed through the real seam ([NewPipeAccess] on
 * `:core` test fixtures) into [SubscriptionsFeed]. Real RSS items carry ISO `<published>` dates,
 * not "3 days ago" text; the merge must order and badge on them. Goldens: [RecordedChannel]
 * (the feed's id order differs from its date order, so an id-order sort fails here).
 */
class SubscriptionsFeedFixtureTest {

    @Test
    fun recordedFeedMergesNewestFirstWithTheNewBadgeOnRecentUploads() {
        val clock = TestClock(RecordedChannel.FEED_CLOCK)
        val feed = SubscriptionsFeed(NewPipeAccess(downloader = FixtureDownloader("channel-feed")), clock)

        val result = runBlocking { feed.load(listOf(testSubscription(RECORDED_CHANNEL_URL))) }

        assertEquals(RecordedChannel.FEED_ENTRIES.map { it.first }, result.entries.map { it.id.value })
        assertEquals(
            RecordedChannel.FEED_ENTRIES.map { it.first },
            result.videosByChannel.getValue(RECORDED_CHANNEL_URL).map { it.id.value },
        )
        assertEquals(0, result.failedChannels)
        assertEquals(
            RecordedChannel.FEED_NEW_IDS,
            result.entries.filter { it.isNewInFeed(result.loadedAt) }.map { it.id.value },
        )
    }

    @Test
    fun recordedFeedKeepsItsShortsNewestFirst() {
        val feed = SubscriptionsFeed(NewPipeAccess(downloader = FixtureDownloader("channel-feed")), TestClock(RecordedChannel.FEED_CLOCK))

        val result = runBlocking { feed.load(listOf(testSubscription(RECORDED_CHANNEL_URL))) }

        assertEquals(RecordedChannel.FEED_SHORTS.map { it.first }, result.shorts.map { it.id.value })
        assertEquals(RecordedChannel.FEED_SHORTS.map { it.second }, result.shorts.map { it.uploadDate })
        assertEquals(result.shorts, result.shortsByChannel.getValue(RECORDED_CHANNEL_URL))
    }

    @Test
    fun recordedFeedGroupsIntoTheWeeksOfItsPublishedDates() {
        // FEED_CLOCK is Sunday 2026-06-28 16:00 UTC; entries were published 06-26, -24, -22, -19.
        val clock = TestClock(RecordedChannel.FEED_CLOCK)
        val feed = SubscriptionsFeed(NewPipeAccess(downloader = FixtureDownloader("channel-feed")), clock)
        val result = runBlocking { feed.load(listOf(testSubscription(RECORDED_CHANNEL_URL))) }

        val groups = feedDays(result.entries, result.loadedAt, ZoneOffset.UTC, DayOfWeek.MONDAY)

        assertEquals(
            listOf(
                FeedDay.EarlierThisWeek to RecordedChannel.FEED_ENTRIES.take(3).map { it.first },
                FeedDay.LastWeek to RecordedChannel.FEED_ENTRIES.drop(3).map { it.first },
            ),
            groups.map { it.day to it.videos.map { video -> video.id.value } },
        )
    }

    @Test
    fun anRssFailureFallsBackToTheRecordedUploadsTabInItsOwnOrder() {
        // The channel-videos flow has no RSS body, so the feed request fails like a live 404.
        val fallbacks = mutableListOf<String>()
        val access = NewPipeAccess(downloader = FixtureDownloader("channel-videos"))
        val feed = SubscriptionsFeed(access, TestClock()) { channelUrl, _ -> fallbacks += channelUrl }

        val result = runBlocking { feed.load(listOf(testSubscription(RECORDED_CHANNEL_URL))) }

        assertEquals(listOf(RECORDED_CHANNEL_URL), fallbacks)
        assertEquals(0, result.failedChannels)
        assertEquals(RecordedChannel.UPLOADS_TAB_VIDEOS, result.entries.size)
        // The first four share "3 months ago", so tab order must survive the date sort.
        assertEquals(RecordedChannel.UPLOADS_TAB_FIRST_IDS, result.entries.take(RecordedChannel.UPLOADS_TAB_FIRST_IDS.size).map { it.id.value })
        assertTrue("uploads-tab items carry approximate dates", result.entries.all { it.uploadDate != null })
    }

    /** PERF P9c: the recorded feed, kept by one load, reads back as the next start's feed (a new
     *  feed: the first one's cache would answer the load at once). */
    @Test
    fun recordedFeedKeptByALoadReadsBackAtTheNextStart() = runTest {
        val kept = FakeChannelFeedStore()
        val clock = TestClock(RecordedChannel.FEED_CLOCK)
        val access = NewPipeAccess(downloader = FixtureDownloader("channel-feed"))
        val subscriptions = listOf(testSubscription(RECORDED_CHANNEL_URL))
        val loaded = SubscriptionsFeed(access, clock, channelFeeds = kept, writeScope = this).load(subscriptions)
        advanceUntilIdle()

        val stored = SubscriptionsFeed(access, clock, channelFeeds = kept, writeScope = this).stored(subscriptions)

        assertEquals(loaded.entries, stored?.result?.entries)
        assertEquals(loaded.shorts, stored?.result?.shorts)
        assertEquals(RecordedChannel.FEED_CLOCK, stored?.fetchedAt)
    }
}
