package com.arshad.ayn.feature.subscriptions

import com.arshad.ayn.core.access.ItemsPage
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.filter.ContentFilter
import com.arshad.ayn.core.store.BlockedChannel
import com.arshad.ayn.core.store.ContentRule
import com.arshad.ayn.core.store.FakeChannelFeedStore
import com.arshad.ayn.core.store.Keyword
import com.arshad.ayn.core.store.ProfileRestrictions
import com.arshad.ayn.core.store.ProfileRules
import com.arshad.ayn.core.store.ProfileSettings
import com.arshad.ayn.core.store.RestrictedMode
import com.arshad.ayn.core.store.StoredChannelFeed
import com.arshad.ayn.core.store.StoredRule
import com.arshad.ayn.feature.FakeFeedExtractor
import com.arshad.ayn.feature.TestClock
import com.arshad.ayn.feature.testSubscription
import com.arshad.ayn.feature.testVideo
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The kept feed (PERF P9c) through [SubscriptionsFeed]: what a load keeps (only what it fetched,
 * only allowed channels, under the profile and filter it started with), what [SubscriptionsFeed.stored]
 * reads back (re-filtered by the session's filter, ages from now), and the per-channel merge of a
 * fresh load over it ([SubscriptionsFeed.Result.over]).
 */
class SubscriptionsFeedKeptTest {
    private val extractor = FakeFeedExtractor()
    private val clock = TestClock()
    private val store = FakeChannelFeedStore(profileNow = OWNER)
    private var filter: ContentFilter = ContentFilter.of(ProfileSettings(), ProfileRules())

    private fun TestScope.keepingFeed(): SubscriptionsFeed =
        SubscriptionsFeed(extractor, clock, currentFilter = { filter }, channelFeeds = store, writeScope = this)

    @Test
    fun aLoadKeepsWhatItFetchedUnderTheProfileAndRestrictedModeItStartedWith() = runTest {
        filter = strict()
        extractor.feeds[CHANNEL_A] = ItemsPage(listOf(video("aaaaaaaaaaa")), next = null, shorts = listOf(video("sssssssssss")))
        extractor.feeds[CHANNEL_B] = page(video("bbbbbbbbbbb"))
        val startedAt = clock.current

        keepingFeed().load(subs(CHANNEL_A, CHANNEL_B))
        advanceUntilIdle()

        val record = store.recorded.single()
        assertEquals(OWNER, record.profileId)
        assertEquals(RestrictedMode.Strict, record.restrictedMode)
        assertEquals(
            mapOf(
                CHANNEL_A to StoredChannelFeed(listOf(video("aaaaaaaaaaa")), listOf(video("sssssssssss")), startedAt),
                CHANNEL_B to StoredChannelFeed(listOf(video("bbbbbbbbbbb")), emptyList(), startedAt),
            ),
            record.feeds,
        )
    }

    /** A cache answer is kept with the cache's fetch time (the store skips what it holds), so a
     *  channel fetched by a cancelled load is still kept. */
    @Test
    fun cacheAnswersKeepTheirFetchTimeAndFailuresAndRefusedChannelsAreNotKept() = runTest {
        filter = ContentFilter.of(ProfileSettings(), rules(BlockedChannel(CHANNEL_C, "C")))
        val feed = keepingFeed()
        extractor.feeds[CHANNEL_A] = page(video("aaaaaaaaaaa"))
        val firstFetch = clock.current
        feed.load(subs(CHANNEL_A))
        clock.current += 1.minutes
        extractor.feeds[CHANNEL_B] = page(video("bbbbbbbbbbb"))
        extractor.failingChannels += CHANNEL_D

        feed.load(subs(CHANNEL_A, CHANNEL_B, CHANNEL_C, CHANNEL_D))
        advanceUntilIdle()

        val second = store.recorded.last().feeds
        assertEquals(setOf(CHANNEL_A, CHANNEL_B), second.keys)
        assertEquals(firstFetch, second.getValue(CHANNEL_A).fetchedAt)
        assertEquals(clock.current, second.getValue(CHANNEL_B).fetchedAt)
    }

    @Test
    fun aWriteThatFailsLeavesTheLoadsResult() = runTest {
        store.failWrites = true
        extractor.feeds[CHANNEL_A] = page(video("aaaaaaaaaaa"))

        val result = keepingFeed().load(subs(CHANNEL_A))
        advanceUntilIdle()

        assertEquals(listOf("aaaaaaaaaaa"), result.entries.map { it.id.value })
    }

    /** The store drops a write for a profile the session left (ChannelFeedRepositoryTest); the
     *  load hands it the profile it started under, never the one switched to. */
    @Test
    fun aProfileSwitchMidLoadKeepsUnderTheProfileTheLoadStartedWith() = runTest {
        extractor.feeds[CHANNEL_A] = page(video("aaaaaaaaaaa"))
        extractor.gateCall = 1
        val gate = CompletableDeferred<Unit>()
        extractor.gate = gate
        val load = async { keepingFeed().load(subs(CHANNEL_A)) }
        advanceUntilIdle()

        store.profileNow = KID
        filter = strict()
        gate.complete(Unit)
        load.await()
        advanceUntilIdle()

        assertEquals(OWNER, store.recorded.single().profileId)
        assertEquals(RestrictedMode.Off, store.recorded.single().restrictedMode)
    }

    @Test
    fun aSwitchBetweenTheLoadsTwoStartReadsKeepsNothing() = runTest {
        extractor.feeds[CHANNEL_A] = page(video("aaaaaaaaaaa"))
        val feed = SubscriptionsFeed(
            extractor,
            clock,
            currentFilter = { store.profileNow = KID; filter },
            channelFeeds = store,
            writeScope = this,
        )

        feed.load(subs(CHANNEL_A))
        advanceUntilIdle()

        assertEquals(emptyList<FakeChannelFeedStore.Record>(), store.recorded)
    }

    @Test
    fun theKeptFeedReadsFilteredByTheSessionFilterWithAgesFromNow() = runTest {
        store.keep(CHANNEL_A, StoredChannelFeed(listOf(video("aaaaaaaaaaa"), video("ppppppppppp", title = "prank")), listOf(video("sssssssssss")), AT - 2.hours))
        store.keep(CHANNEL_B, StoredChannelFeed(listOf(video("bbbbbbbbbbb", uploaded = "2026-06-27T00:00:00Z")), emptyList(), AT - 1.hours))
        filter = ContentFilter.of(ProfileSettings(), rules(Keyword("prank")))

        val stored = keepingFeed().stored(subs(CHANNEL_A, CHANNEL_B))!!

        assertEquals(listOf("bbbbbbbbbbb", "aaaaaaaaaaa"), stored.result.entries.map { it.id.value })
        assertEquals(listOf("sssssssssss"), stored.result.shorts.map { it.id.value })
        assertEquals(clock.current, stored.result.loadedAt)
        assertEquals(AT - 1.hours, stored.fetchedAt)
        assertEquals(clock.current - (AT - 1.hours), stored.age)
        assertEquals(emptyMap<String, Any>(), stored.result.failures)
    }

    @Test
    fun theKeptFeedLeavesOutUnsubscribedAndRefusedChannelsAndShortsTheFilterHides() = runTest {
        store.keep(CHANNEL_A, StoredChannelFeed(listOf(video("aaaaaaaaaaa")), listOf(video("sssssssssss")), AT))
        store.keep(CHANNEL_B, StoredChannelFeed(listOf(video("bbbbbbbbbbb")), emptyList(), AT))
        store.keep(CHANNEL_C, StoredChannelFeed(listOf(video("ccccccccccc")), emptyList(), AT))
        filter = ContentFilter.of(ProfileSettings(), rules(BlockedChannel(CHANNEL_B, "B")).copy(restrictions = ProfileRestrictions(hideShorts = true)))

        val stored = keepingFeed().stored(subs(CHANNEL_A, CHANNEL_B))!!

        assertEquals(setOf(CHANNEL_A), stored.result.videosByChannel.keys)
        assertEquals(emptyList<VideoItem>(), stored.result.shorts)
    }

    /** KP-D15: a Restricted Mode change between the write and the read leaves nothing kept. */
    @Test
    fun aFeedKeptUnderAnotherRestrictedModeIsNotShown() = runTest {
        store.keep(CHANNEL_A, StoredChannelFeed(listOf(video("aaaaaaaaaaa")), emptyList(), AT))
        filter = strict()

        assertNull(keepingFeed().stored(subs(CHANNEL_A)))
    }

    @Test
    fun nothingKeptForAnotherProfileOrWhenEveryChannelIsFreshInTheCache() = runTest {
        val feed = keepingFeed()
        store.keep(CHANNEL_A, StoredChannelFeed(listOf(video("aaaaaaaaaaa")), emptyList(), AT), profileId = KID)
        assertNull(feed.stored(subs(CHANNEL_A)))

        store.keep(CHANNEL_A, StoredChannelFeed(listOf(video("aaaaaaaaaaa")), emptyList(), AT))
        extractor.feeds[CHANNEL_A] = page(video("bbbbbbbbbbb"))
        feed.load(subs(CHANNEL_A))
        assertNull("a load now answers from the cache", feed.stored(subs(CHANNEL_A)))
    }

    @Test
    fun aStoreThatCantBeReadIsNothingKept() = runTest {
        store.keep(CHANNEL_A, StoredChannelFeed(listOf(video("aaaaaaaaaaa")), emptyList(), AT))
        store.failReads = true

        assertNull(keepingFeed().stored(subs(CHANNEL_A)))
    }

    @Test
    fun aFreshLoadOverTheKeptFeedKeepsAFailedChannelsKeptVideos() = runTest {
        store.keep(CHANNEL_A, StoredChannelFeed(listOf(video("aaaaaaaaaa1")), listOf(video("sssssssssss")), AT))
        store.keep(CHANNEL_B, StoredChannelFeed(listOf(video("bbbbbbbbbb1")), emptyList(), AT))
        val feed = keepingFeed()
        val kept = feed.stored(subs(CHANNEL_A, CHANNEL_B, CHANNEL_C))!!
        extractor.failingChannels += setOf(CHANNEL_A, CHANNEL_C)
        extractor.feeds[CHANNEL_B] = page(video("bbbbbbbbbb2"))

        val merged = feed.load(subs(CHANNEL_A, CHANNEL_B, CHANNEL_C)).over(kept.result)

        assertEquals(listOf("aaaaaaaaaa1"), merged.videosByChannel.getValue(CHANNEL_A).map { it.id.value })
        assertEquals("an answered channel is the fresh load's", listOf("bbbbbbbbbb2"), merged.videosByChannel.getValue(CHANNEL_B).map { it.id.value })
        assertEquals(listOf("sssssssssss"), merged.shorts.map { it.id.value })
        assertEquals(setOf(CHANNEL_A, CHANNEL_C), merged.failures.keys)
        assertEquals("only a channel with nothing to show is missing", setOf(CHANNEL_C), merged.missingChannels)
    }

    @Test
    fun anOfflineFreshLoadOverTheKeptFeedStillShowsIt() = runTest {
        store.keep(CHANNEL_A, StoredChannelFeed(listOf(video("aaaaaaaaaaa")), emptyList(), AT))
        val feed = keepingFeed()
        val kept = feed.stored(subs(CHANNEL_A))!!
        extractor.failingChannels += CHANNEL_A

        val fresh = feed.load(subs(CHANNEL_A))
        val merged = fresh.over(kept.result)

        assertTrue(fresh.nothingAnswered)
        assertEquals(kept.result.entries, merged.entries)
        assertEquals(false, merged.nothingAnswered)
        assertEquals(emptySet<String>(), merged.missingChannels)
    }

    private fun strict(): ContentFilter =
        ContentFilter.of(ProfileSettings(), ProfileRules(restrictions = ProfileRestrictions(restrictedMode = RestrictedMode.Strict)))

    private fun rules(vararg rules: ContentRule): ProfileRules =
        ProfileRules(rules = rules.map { StoredRule(it, byOwner = true, addedAtMillis = 0) })

    private fun subs(vararg urls: String) = urls.map { testSubscription(it) }

    private fun page(vararg items: VideoItem) = ItemsPage(items = items.toList(), next = null)

    private fun video(id: String, uploaded: String? = null, title: String = "title $id"): VideoItem =
        testVideo(id, title = title).copy(uploadDate = uploaded?.let(Instant::parse))

    private companion object {
        const val OWNER = 1L
        const val KID = 2L
        val AT: Instant = Instant.parse("2026-06-27T12:00:00Z")
        const val CHANNEL_A = "https://www.youtube.com/channel/UCaaaaaaaaaaaaaaaaaaaaaa"
        const val CHANNEL_B = "https://www.youtube.com/channel/UCbbbbbbbbbbbbbbbbbbbbbb"
        const val CHANNEL_C = "https://www.youtube.com/channel/UCcccccccccccccccccccccc"
        const val CHANNEL_D = "https://www.youtube.com/channel/UCdddddddddddddddddddddd"
    }
}
