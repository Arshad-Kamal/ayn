package com.arshad.ayn.feature.subscriptions

import com.arshad.ayn.core.access.AccessFailure
import com.arshad.ayn.core.access.ItemsPage
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.filter.ContentFilter
import com.arshad.ayn.core.filter.FilteringExtractor
import com.arshad.ayn.core.store.Keyword
import com.arshad.ayn.core.store.ProfileRules
import com.arshad.ayn.core.store.ProfileSettings
import com.arshad.ayn.core.store.StoredRule
import com.arshad.ayn.feature.FakeFeedExtractor
import com.arshad.ayn.feature.TestClock
import com.arshad.ayn.feature.testSubscription
import com.arshad.ayn.feature.testVideo
import java.net.UnknownHostException
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Feed merge tests against the shared fake extractor: newest-first ordering by upload
 * instant with source-order ties, the RSS → uploads-tab fallback and its report, per-channel
 * failure isolation with each failure's kind, per-channel videos, cross-channel dedupe, the
 * all-failed signal, the bounded concurrency, the New-badge band, and the shared cache. Real-payload coverage is [SubscriptionsFeedFixtureTest].
 */
class SubscriptionsFeedTest {

    private val extractor = FakeFeedExtractor()
    private val clock = TestClock()
    private val rssFallbacks = mutableListOf<String>()
    private val feed = SubscriptionsFeed(extractor, clock) { channelUrl, _ -> rssFallbacks += channelUrl }

    @Test
    fun mergesNewestFirstAcrossChannels() = runTest {
        extractor.feeds[CHANNEL_A] = page(
            video("aaaaaaaaaaa", uploaded = "2026-06-22T10:00:00Z"),
            video("bbbbbbbbbbb", uploaded = "2026-06-26T10:00:00Z"),
        )
        extractor.feeds[CHANNEL_B] = page(
            video("ccccccccccc", uploaded = "2026-06-27T21:00:00Z"),
            video("ddddddddddd", uploaded = "2026-06-13T10:00:00Z"),
        )

        val result = feed.load(listOf(testSubscription(CHANNEL_A), testSubscription(CHANNEL_B)))

        assertEquals(
            listOf("ccccccccccc", "bbbbbbbbbbb", "aaaaaaaaaaa", "ddddddddddd"),
            result.entries.map { it.id.value },
        )
    }

    @Test
    fun undatedEntriesSortLast() = runTest {
        extractor.feeds[CHANNEL_A] = page(
            video("aaaaaaaaaaa", uploaded = null),
            video("bbbbbbbbbbb", uploaded = "2026-06-01T00:00:00Z"),
        )

        val result = feed.load(listOf(testSubscription(CHANNEL_A)))

        assertEquals(listOf("bbbbbbbbbbb", "aaaaaaaaaaa"), result.entries.map { it.id.value })
    }

    @Test
    fun equalInstantsKeepSourceOrder() = runTest {
        extractor.feeds[CHANNEL_A] = page(
            video("bbbbbbbbbbb", uploaded = "2026-06-01T00:00:00Z"),
            video("aaaaaaaaaaa", uploaded = "2026-06-01T00:00:00Z"),
        )

        val result = feed.load(listOf(testSubscription(CHANNEL_A)))

        assertEquals(listOf("bbbbbbbbbbb", "aaaaaaaaaaa"), result.entries.map { it.id.value })
    }

    @Test
    fun eachChannelsShortsMergeNewestFirstBesideItsVideos() = runTest {
        extractor.feeds[CHANNEL_A] = page(video("aaaaaaaaaaa")).copy(
            shorts = listOf(video("sssssssssa1", uploaded = "2026-06-20T00:00:00Z"), video("sssssssssa2", uploaded = "2026-06-27T00:00:00Z")),
        )
        extractor.feeds[CHANNEL_B] = page(video("bbbbbbbbbbb")).copy(
            shorts = listOf(video("sssssssssb1", uploaded = "2026-06-25T00:00:00Z")),
        )

        val result = feed.load(listOf(testSubscription(CHANNEL_A), testSubscription(CHANNEL_B)))

        assertEquals(listOf("sssssssssa2", "sssssssssb1", "sssssssssa1"), result.shorts.map { it.id.value })
        assertEquals(listOf("sssssssssa2", "sssssssssa1"), result.shortsByChannel.getValue(CHANNEL_A).map { it.id.value })
        assertEquals(setOf("aaaaaaaaaaa", "bbbbbbbbbbb"), result.entries.map { it.id.value }.toSet())
    }

    @Test
    fun anRssAnswerOfOnlyShortsKeepsThemWhileItsVideosComeFromTheUploadsTab() = runTest {
        extractor.feeds[CHANNEL_A] = ItemsPage(items = emptyList(), next = null, shorts = listOf(video("sssssssssa1")))
        extractor.uploadsTabs[CHANNEL_A] = page(video("aaaaaaaaaaa"))

        val result = feed.load(listOf(testSubscription(CHANNEL_A)))

        assertEquals(listOf("aaaaaaaaaaa"), result.entries.map { it.id.value })
        assertEquals(listOf("sssssssssa1"), result.shorts.map { it.id.value })
        assertEquals(listOf(CHANNEL_A), rssFallbacks)
    }

    @Test
    fun aCachedFeedKeepsItsShorts() = runTest {
        extractor.feeds[CHANNEL_A] = page(video("aaaaaaaaaaa")).copy(shorts = listOf(video("sssssssssa1")))
        feed.load(listOf(testSubscription(CHANNEL_A)))

        val again = feed.load(listOf(testSubscription(CHANNEL_A)))

        assertEquals(1, extractor.calls)
        assertEquals(listOf("sssssssssa1"), again.shorts.map { it.id.value })
    }

    @Test
    fun anRssFailureFallsBackToTheUploadsTabAndReportsTheChannel() = runTest {
        extractor.rssDownChannels += CHANNEL_A
        extractor.uploadsTabs[CHANNEL_A] = page(video("aaaaaaaaaaa"))
        extractor.feeds[CHANNEL_B] = page(video("bbbbbbbbbbb"))

        val result = feed.load(listOf(testSubscription(CHANNEL_A), testSubscription(CHANNEL_B)))

        assertEquals(setOf("aaaaaaaaaaa", "bbbbbbbbbbb"), result.entries.map { it.id.value }.toSet())
        assertEquals(0, result.failedChannels)
        assertEquals(listOf(CHANNEL_A), rssFallbacks)
    }

    @Test
    fun anRssAnswerWithoutVideosFallsBackToo() = runTest {
        // Default fake RSS answer: no items (a 5xx/interstitial parses this way).
        extractor.uploadsTabs[CHANNEL_A] = page(video("aaaaaaaaaaa"))

        val result = feed.load(listOf(testSubscription(CHANNEL_A)))

        assertEquals(listOf("aaaaaaaaaaa"), result.entries.map { it.id.value })
        assertEquals(listOf(CHANNEL_A), rssFallbacks)
    }

    @Test
    fun aFallbackResultIsCachedLikeRss() = runTest {
        extractor.rssDownChannels += CHANNEL_A
        extractor.uploadsTabs[CHANNEL_A] = page(video("aaaaaaaaaaa"))
        feed.load(listOf(testSubscription(CHANNEL_A)))

        clock.current += SubscriptionsFeed.CacheTtl - 1.seconds
        feed.load(listOf(testSubscription(CHANNEL_A)))

        assertEquals(1, extractor.calls)
        assertEquals(1, extractor.uploadsTabCalls)
        assertEquals(listOf(CHANNEL_A), rssFallbacks)
    }

    @Test
    fun fallbackFetchesStayWithinTheBound() = runTest {
        val channels = List(10) { index -> "https://www.youtube.com/channel/UC$index" }
        channels.forEachIndexed { index, url ->
            extractor.rssDownChannels += url
            extractor.uploadsTabs[url] = page(video("v%010d".format(index)))
        }

        val result = feed.load(channels.map { testSubscription(it) })

        assertEquals(10, result.entries.size)
        assertEquals(SubscriptionsFeed.FeedConcurrency, extractor.maxInFlight)
    }

    @Test
    fun oneChannelFailingNeverFailsTheFeed() = runTest {
        extractor.feeds[CHANNEL_A] = page(video("aaaaaaaaaaa"))
        extractor.failingChannels += CHANNEL_B

        val result = feed.load(listOf(testSubscription(CHANNEL_A), testSubscription(CHANNEL_B)))

        assertEquals(listOf("aaaaaaaaaaa"), result.entries.map { it.id.value })
        assertEquals(1, result.failedChannels)
    }

    @Test
    fun everyChannelFailingLeavesNoEntries() = runTest {
        extractor.failingChannels += CHANNEL_A

        val result = feed.load(listOf(testSubscription(CHANNEL_A)))

        assertTrue(result.entries.isEmpty())
        assertEquals(1, result.failedChannels)
        assertEquals("the RSS failure is still reported", listOf(CHANNEL_A), rssFallbacks)
    }

    @Test
    fun eachFailedChannelCarriesItsUploadsTabsFailureKind() = runTest {
        extractor.failingChannels += listOf(CHANNEL_A, CHANNEL_B)
        extractor.failureCauses[CHANNEL_A] = UnknownHostException("www.youtube.com")

        val result = feed.load(listOf(testSubscription(CHANNEL_A), testSubscription(CHANNEL_B)))

        assertEquals(mapOf(CHANNEL_A to AccessFailure.OFFLINE, CHANNEL_B to AccessFailure.UNREADABLE), result.failures)
    }

    @Test
    fun videosByChannelListsEachAnsweredChannelNewestFirstIncludingOneWithNone() = runTest {
        extractor.feeds[CHANNEL_A] = page(
            video("aaaaaaaaaaa", uploaded = "2026-06-22T10:00:00Z"),
            video("bbbbbbbbbbb", uploaded = "2026-06-26T10:00:00Z"),
        )
        extractor.failingChannels += CHANNEL_B
        val channelC = "https://www.youtube.com/channel/UCc" // answers, but with no videos either way

        val result = feed.load(listOf(testSubscription(CHANNEL_A), testSubscription(CHANNEL_B), testSubscription(channelC)))

        assertEquals(
            mapOf(CHANNEL_A to listOf("bbbbbbbbbbb", "aaaaaaaaaaa"), channelC to emptyList()),
            result.videosByChannel.mapValues { (_, videos) -> videos.map { it.id.value } },
        )
    }

    @Test
    fun aVideoInTwoFeedsIsListedUnderBothChannels() = runTest {
        extractor.feeds[CHANNEL_A] = page(video("aaaaaaaaaaa"))
        extractor.feeds[CHANNEL_B] = page(video("aaaaaaaaaaa"))

        val result = feed.load(listOf(testSubscription(CHANNEL_A), testSubscription(CHANNEL_B)))

        assertEquals(listOf("aaaaaaaaaaa"), result.videosByChannel.getValue(CHANNEL_A).map { it.id.value })
        assertEquals(listOf("aaaaaaaaaaa"), result.videosByChannel.getValue(CHANNEL_B).map { it.id.value })
    }

    @Test
    fun aRepeatedIdWithinOneFeedIsListedOnce() = runTest {
        extractor.feeds[CHANNEL_A] = page(video("aaaaaaaaaaa"), video("aaaaaaaaaaa"))

        val result = feed.load(listOf(testSubscription(CHANNEL_A)))

        assertEquals(1, result.videosByChannel.getValue(CHANNEL_A).size)
        assertEquals(1, result.entries.size)
    }

    @Test
    fun duplicateIdsAcrossChannelsAreDeduped() = runTest {
        extractor.feeds[CHANNEL_A] = page(video("aaaaaaaaaaa"))
        extractor.feeds[CHANNEL_B] = page(video("aaaaaaaaaaa"))

        val result = feed.load(listOf(testSubscription(CHANNEL_A), testSubscription(CHANNEL_B)))

        assertEquals(1, result.entries.size)
    }

    @Test
    fun channelFetchesAreBounded() = runTest {
        // 10 channels through FeedConcurrency=4: the fake tracks in-flight calls; removing
        // the semaphore regresses this to maxInFlight == 10.
        repeat(10) { index ->
            extractor.feeds["https://www.youtube.com/channel/UC$index"] = page(video("v%010d".format(index)))
        }

        val result = feed.load(
            List(10) { index -> testSubscription("https://www.youtube.com/channel/UC$index") },
        )

        assertEquals(10, result.entries.size)
        assertEquals(SubscriptionsFeed.FeedConcurrency, extractor.maxInFlight)
    }

    @Test
    fun eachChannelsNewestVideoIsKeyedByItsSubscription() = runTest {
        // The uploader URL on a video need not match the subscription's URL form.
        extractor.feeds[CHANNEL_A] = page(
            video("aaaaaaaaaaa", uploaded = "2026-06-22T10:00:00Z"),
            video("bbbbbbbbbbb", uploaded = "2026-06-26T10:00:00Z"),
        )
        extractor.failingChannels += CHANNEL_B
        val channelC = "https://www.youtube.com/channel/UCc" // answers, but with no videos either way

        val result = feed.load(listOf(testSubscription(CHANNEL_A), testSubscription(CHANNEL_B), testSubscription(channelC)))

        assertEquals(mapOf(CHANNEL_A to "bbbbbbbbbbb"), result.newestByChannel.mapValues { it.value.id.value })
    }

    @Test
    fun newBadgeCoversFourDaysAndNoMore() {
        val now = Instant.parse("2026-06-28T00:00:00Z")

        assertTrue(video("aaaaaaaaaaa", uploaded = "2026-06-24T00:00:00Z").isNewInFeed(now))
        assertFalse(video("bbbbbbbbbbb", uploaded = "2026-06-23T23:59:59Z").isNewInFeed(now))
        assertFalse(video("ccccccccccc", uploaded = null).isNewInFeed(now))
    }

    @Test
    fun aSecondLoadWithinTheTtlReusesTheFetchedFeed() = runTest {
        extractor.feeds[CHANNEL_A] = page(video("aaaaaaaaaaa"))
        feed.load(listOf(testSubscription(CHANNEL_A)))

        clock.current += SubscriptionsFeed.CacheTtl - 1.seconds
        val second = feed.load(listOf(testSubscription(CHANNEL_A)))

        assertEquals(1, extractor.calls)
        assertEquals(listOf("aaaaaaaaaaa"), second.entries.map { it.id.value })
    }

    @Test
    fun aLoadAfterTheTtlRefetches() = runTest {
        extractor.feeds[CHANNEL_A] = page(video("aaaaaaaaaaa"))
        feed.load(listOf(testSubscription(CHANNEL_A)))
        extractor.feeds[CHANNEL_A] = page(video("bbbbbbbbbbb"))

        clock.current += SubscriptionsFeed.CacheTtl
        val second = feed.load(listOf(testSubscription(CHANNEL_A)))

        assertEquals(2, extractor.calls)
        assertEquals(listOf("bbbbbbbbbbb"), second.entries.map { it.id.value })
    }

    /** The extractor answers filtered for the session profile, so another filter (another
     *  profile, a rule edit) never reuses a feed fetched under the first (KP-D1). */
    @Test
    fun aFeedCachedUnderOneFilterIsFetchedAgainUnderAnother() = runTest {
        var filter = ContentFilter.of(ProfileSettings(), ProfileRules())
        val filtered = SubscriptionsFeed(extractor, clock, currentFilter = { filter })
        extractor.feeds[CHANNEL_A] = page(video("aaaaaaaaaaa"))
        filtered.load(listOf(testSubscription(CHANNEL_A)))

        filter = ContentFilter.of(ProfileSettings(), ProfileRules(rules = listOf(StoredRule(Keyword("prank"), byOwner = false, addedAtMillis = 0))))
        filtered.load(listOf(testSubscription(CHANNEL_A)))
        filter = ContentFilter.of(ProfileSettings(), ProfileRules(rules = listOf(StoredRule(Keyword("prank"), byOwner = true, addedAtMillis = 9))))
        filtered.load(listOf(testSubscription(CHANNEL_A)))

        assertEquals("a changed filter fetches; one that filters alike reuses", 2, extractor.calls)
    }

    /** Through the real filter seam: a load reads the profile's filter once, however many channels,
     *  and a rule changed mid-load never mixes into it (each read here loosens the next). */
    @Test
    fun aLoadReadsTheFilterOnceAndUsesItForEveryChannel() = runTest {
        val channels = List(6) { index -> "https://www.youtube.com/channel/UC$index" }
        channels.forEachIndexed { index, url ->
            extractor.feeds[url] = page(video("v%010d".format(index)), video("p%010d".format(index)).copy(title = "prank"))
        }
        var reads = 0
        var filter = ContentFilter.of(ProfileSettings(), ProfileRules(rules = listOf(StoredRule(Keyword("prank"), byOwner = true, addedAtMillis = 0))))
        val read: suspend () -> ContentFilter = { reads++; filter.also { filter = ContentFilter.of(ProfileSettings(), ProfileRules()) } }
        val seamFeed = SubscriptionsFeed(FilteringExtractor(extractor, read), clock, currentFilter = read)

        val result = seamFeed.load(channels.map { testSubscription(it) })

        assertEquals(1, reads)
        assertEquals("every channel filtered by the load's filter", 6, result.entries.size)
        assertTrue(result.entries.none { it.title == "prank" })
    }

    @Test
    fun failuresAreNeverCached() = runTest {
        extractor.failingChannels += CHANNEL_A
        feed.load(listOf(testSubscription(CHANNEL_A)))
        extractor.failingChannels.clear()
        extractor.feeds[CHANNEL_A] = page(video("aaaaaaaaaaa"))

        val retry = feed.load(listOf(testSubscription(CHANNEL_A)))

        assertEquals(listOf("aaaaaaaaaaa"), retry.entries.map { it.id.value })
        assertEquals(0, retry.failedChannels)
    }

    @Test
    fun theFetchBoundHoldsAcrossConcurrentLoads() = runTest {
        val channels = List(10) { index -> "https://www.youtube.com/channel/UC$index" }
        channels.forEachIndexed { index, url -> extractor.feeds[url] = page(video("v%010d".format(index))) }

        val first = async { feed.load(channels.take(5).map { testSubscription(it) }) }
        val second = async { feed.load(channels.drop(5).map { testSubscription(it) }) }
        awaitAll(first, second)

        // Per-load bounds would reach 8 here.
        assertEquals(SubscriptionsFeed.FeedConcurrency, extractor.maxInFlight)
    }

    @Test
    fun anOverlappingLoadReusesWhatTheEarlierLoadFetchedWhileItWaited() = runTest {
        val channels = List(5) { index -> "https://www.youtube.com/channel/UC$index" }
        channels.forEachIndexed { index, url -> extractor.feeds[url] = page(video("v%010d".format(index))) }

        val home = async { feed.load(channels.map { testSubscription(it) }) }
        val subscriptions = async { feed.load(channels.map { testSubscription(it) }) }
        awaitAll(home, subscriptions)

        // 4 permits: the first load's 4 fetches finish before the second load's permits come
        // up, so it refetches only channel 5, which the first load still has in flight.
        assertEquals(6, extractor.calls)
    }

    @Test
    fun aSupersededSlowFetchNeverReplacesANewerOne() = runTest {
        extractor.sequencePages = mutableListOf(page(video("aaaaaaaaaaa")), page(video("bbbbbbbbbbb")))
        extractor.gateCall = 1
        val gate = CompletableDeferred<Unit>()
        extractor.gate = gate
        val superseded = async { feed.load(listOf(testSubscription(CHANNEL_A))) }
        advanceUntilIdle() // the first fetch is held in the gate
        clock.current += 1.seconds
        feed.load(listOf(testSubscription(CHANNEL_A))) // the newer fetch lands first
        gate.complete(Unit)
        superseded.await()

        val third = feed.load(listOf(testSubscription(CHANNEL_A)))

        assertEquals(listOf("bbbbbbbbbbb"), third.entries.map { it.id.value })
        assertEquals(2, extractor.calls)
    }

    @Test
    fun aCacheEntryFromTheFutureIsReplacedByTheRefetch() = runTest {
        // The wall clock stepped back after the fetch.
        extractor.feeds[CHANNEL_A] = page(video("aaaaaaaaaaa"))
        feed.load(listOf(testSubscription(CHANNEL_A)))
        clock.current -= 1.seconds
        extractor.feeds[CHANNEL_A] = page(video("bbbbbbbbbbb"))
        feed.load(listOf(testSubscription(CHANNEL_A))) // stale: refetched

        val third = feed.load(listOf(testSubscription(CHANNEL_A)))

        assertEquals(2, extractor.calls) // the refetch was cached
        assertEquals(listOf("bbbbbbbbbbb"), third.entries.map { it.id.value })
    }

    @Test
    fun resultsCarryTheLoadInstant() = runTest {
        clock.current = Instant.parse("2026-06-28T12:00:00Z")

        val result = feed.load(listOf(testSubscription(CHANNEL_A)))

        assertEquals(Instant.parse("2026-06-28T12:00:00Z"), result.loadedAt)
    }

    private fun page(vararg items: VideoItem) = ItemsPage(items = items.toList(), next = null)

    private fun video(id: String, uploaded: String? = null): VideoItem =
        testVideo(id).copy(uploadDate = uploaded?.let(Instant::parse))

    private companion object {
        const val CHANNEL_A = "https://www.youtube.com/channel/UCa"
        const val CHANNEL_B = "https://www.youtube.com/channel/UCb"
    }
}
