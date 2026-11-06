package com.arshad.ayn.core.store

import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.access.newpipe.FixtureDownloader
import com.arshad.ayn.core.access.newpipe.NewPipeAccess
import com.arshad.ayn.core.access.newpipe.RECORDED_CHANNEL_URL
import com.arshad.ayn.core.lock.OwnerPass
import kotlin.time.Instant
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The kept channel feeds (PERF P9c) over a real in-memory Room 3 database: the round trip, the
 * Restricted Mode tag, damaged rows, newer-wins, pruning to the profile's subscriptions, and the
 * writes a profile switch or a removed profile drops.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChannelFeedRepositoryTest {
    private val scope = TestScope()
    private val dispatcher = StandardTestDispatcher(scope.testScheduler)
    private val database: KeDatabase =
        Room.inMemoryDatabaseBuilder<KeDatabase>(RuntimeEnvironment.getApplication())
            .setDriver(AndroidSQLiteDriver())
            .setQueryCoroutineContext(dispatcher)
            .build()
    private val profiles = ProfileRepository(database, MutableClock(T0))
    private val session = ProfileSession()
    private val clock = MutableClock(T0 + 10_000)
    private val store: ChannelFeedStore = ChannelFeedRepository(database, session, compute = dispatcher, clock = clock)
    private var ownerId = 0L

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun aKeptFeedReadsBackWithEveryFieldMembersOnlyIncluded() = ownerTest {
        val feed = StoredChannelFeed(listOf(FULL_ITEM, BARE_ITEM), shorts = listOf(item(9)), fetchedAt = at(T0))

        store.record(ownerId, RestrictedMode.Off, mapOf(CHANNEL_A to feed))

        assertEquals(mapOf(CHANNEL_A to feed), store.feeds(ownerId, RestrictedMode.Off))
    }

    /** The real seam's output: the recorded RSS feed, parsed by the fork, kept to the millisecond. */
    @Test
    fun aRecordedFeedReadsBackAsFetched() = ownerTest {
        subscribe(RECORDED_CHANNEL_URL)
        val page = NewPipeAccess(downloader = FixtureDownloader("channel-feed")).channelFeed(RECORDED_CHANNEL_URL)
        val videos = page.items.filterIsInstance<VideoItem>()
        assertTrue("the recorded feed has videos and Shorts", videos.isNotEmpty() && page.shorts.isNotEmpty())
        val feed = StoredChannelFeed(videos, page.shorts, at(T0))

        store.record(ownerId, RestrictedMode.Off, mapOf(RECORDED_CHANNEL_URL to feed))

        assertEquals(feed, store.feeds(ownerId, RestrictedMode.Off).getValue(RECORDED_CHANNEL_URL))
    }

    @Test
    fun aFeedFetchedUnderAnotherRestrictedModeReadsAsAbsent() = ownerTest {
        store.record(ownerId, RestrictedMode.Strict, mapOf(CHANNEL_A to feedOf(1, T0)))

        assertEquals(emptyMap<String, StoredChannelFeed>(), store.feeds(ownerId, RestrictedMode.Off))
        assertEquals(setOf(CHANNEL_A), store.feeds(ownerId, RestrictedMode.Strict).keys)
    }

    @Test
    fun aDamagedRowReadsAsAbsentAndTheOthersStillRead() = ownerTest {
        store.record(ownerId, RestrictedMode.Off, mapOf(CHANNEL_A to feedOf(1, T0)))
        database.channelFeedDao().upsert(listOf(ChannelFeedEntity(ownerId, CHANNEL_B, "{not a list", "[]", T0, "off")))

        assertEquals(setOf(CHANNEL_A), store.feeds(ownerId, RestrictedMode.Off).keys)
    }

    @Test
    fun aNewerFetchReplacesTheFeedAndAnOlderOneDoesNot() = ownerTest {
        store.record(ownerId, RestrictedMode.Off, mapOf(CHANNEL_A to feedOf(1, T0 + 1_000)))

        store.record(ownerId, RestrictedMode.Off, mapOf(CHANNEL_A to feedOf(2, T0)))
        assertEquals(feedOf(1, T0 + 1_000), store.feeds(ownerId, RestrictedMode.Off).getValue(CHANNEL_A))

        store.record(ownerId, RestrictedMode.Off, mapOf(CHANNEL_A to feedOf(3, T0 + 2_000)))
        assertEquals(feedOf(3, T0 + 2_000), store.feeds(ownerId, RestrictedMode.Off).getValue(CHANNEL_A))
    }

    @Test
    fun aFeedFromTheSameFetchIsNotRewritten() = ownerTest {
        store.record(ownerId, RestrictedMode.Off, mapOf(CHANNEL_A to feedOf(1, T0)))

        store.record(ownerId, RestrictedMode.Off, mapOf(CHANNEL_A to feedOf(2, T0)))

        assertEquals(feedOf(1, T0), store.feeds(ownerId, RestrictedMode.Off).getValue(CHANNEL_A))
    }

    /** The wall clock stepped back after a write: the feed stamped in the future still gets replaced. */
    @Test
    fun aFeedStampedInTheFutureIsReplaced() = ownerTest {
        store.record(ownerId, RestrictedMode.Off, mapOf(CHANNEL_A to feedOf(1, T0 + 1_000_000)))

        store.record(ownerId, RestrictedMode.Off, mapOf(CHANNEL_A to feedOf(2, T0)))

        assertEquals(feedOf(2, T0), store.feeds(ownerId, RestrictedMode.Off).getValue(CHANNEL_A))
    }

    @Test
    fun anUnsubscribeDropsTheChannelsKeptFeed() = ownerTest {
        store.record(ownerId, RestrictedMode.Off, mapOf(CHANNEL_A to feedOf(1, T0), CHANNEL_B to feedOf(2, T0)))

        SubscriptionRepository(database, session).unsubscribe(CHANNEL_B)

        assertEquals(setOf(CHANNEL_A), store.feeds(ownerId, RestrictedMode.Off).keys)
    }

    @Test
    fun aWriteDropsTheFeedsOfChannelsNoLongerSubscribed() = ownerTest {
        store.record(ownerId, RestrictedMode.Off, mapOf(CHANNEL_A to feedOf(1, T0), CHANNEL_B to feedOf(2, T0)))
        database.subscriptionDao().delete(ownerId, CHANNEL_B)

        store.record(ownerId, RestrictedMode.Off, mapOf(CHANNEL_A to feedOf(3, T0 + 1)))

        assertEquals(setOf(CHANNEL_A), store.feeds(ownerId, RestrictedMode.Off).keys)
    }

    @Test
    fun aWriteAfterTheSessionMovedToAnotherProfileIsDropped() = ownerTest {
        val other = profiles.newProfile("Yuri")
        session.select(other)

        store.record(ownerId, RestrictedMode.Off, mapOf(CHANNEL_A to feedOf(1, T0)))

        assertEquals(emptyMap<String, StoredChannelFeed>(), store.feeds(ownerId, RestrictedMode.Off))
    }

    @Test
    fun aWriteForARemovedProfileIsDroppedWithoutAnError() = ownerTest {
        val other = profiles.newProfile("Yuri")
        session.select(other)
        profiles.remove(OwnerPass(), other)

        store.record(other, RestrictedMode.Off, mapOf(CHANNEL_A to feedOf(1, T0)))

        assertEquals(emptyMap<String, StoredChannelFeed>(), store.feeds(other, RestrictedMode.Off))
    }

    @Test
    fun eachProfileKeepsItsOwnFeedsAndTheyGoWithIt() = ownerTest {
        store.record(ownerId, RestrictedMode.Off, mapOf(CHANNEL_A to feedOf(1, T0)))
        val other = profiles.newProfile("Yuri")
        session.select(other)
        database.subscriptionDao().insertAll(listOf(SubscriptionEntity(other, CHANNEL_A, "A", null, T0, T0)))
        store.record(other, RestrictedMode.Off, mapOf(CHANNEL_A to feedOf(2, T0)))

        profiles.remove(OwnerPass(), other)

        assertEquals(emptyList<ChannelFeedEntity>(), database.channelFeedDao().feeds(other, "off"))
        assertEquals(feedOf(1, T0), store.feeds(ownerId, RestrictedMode.Off).getValue(CHANNEL_A))
    }

    private fun ownerTest(body: suspend TestScope.() -> Unit) = scope.runTest {
        ownerId = profiles.ensureOwner().id
        session.select(ownerId)
        subscribe(CHANNEL_A)
        subscribe(CHANNEL_B)
        body()
    }

    private suspend fun subscribe(channelUrl: String) {
        database.subscriptionDao().insertAll(listOf(SubscriptionEntity(ownerId, channelUrl, "Channel", null, T0, T0)))
    }

    private suspend fun ProfileRepository.newProfile(name: String): Long =
        (create(OwnerPass(), name, ProfileTint.Rose) as ProfileEdit.Done).profileId
}

private const val T0 = 1_700_000_000_000L
private const val CHANNEL_A = "https://www.youtube.com/channel/UCaaaaaaaaaaaaaaaaaaaaaa"
private const val CHANNEL_B = "https://www.youtube.com/channel/UCbbbbbbbbbbbbbbbbbbbbbb"

private fun at(millis: Long): Instant = Instant.fromEpochMilliseconds(millis)

private fun feedOf(n: Int, fetchedAtMillis: Long) = StoredChannelFeed(listOf(item(n)), emptyList(), at(fetchedAtMillis))

private fun item(n: Int): VideoItem = BARE_ITEM.copy(id = VideoId("v%010d".format(n)), title = "Video $n")

/** Every field set, members-only and upcoming included. */
private val FULL_ITEM = VideoItem(
    id = VideoId("aaaaaaaaaa1"),
    title = "Why the Moon's craters are round",
    uploaderName = "Veritasium",
    uploaderUrl = "https://www.youtube.com/channel/UCHnyfMqiRRG1u-2MsSQLbXA",
    durationSeconds = 754,
    viewCount = 1_234_567,
    textualUploadDate = "3 days ago",
    uploadDate = at(T0 - 3 * 86_400_000L),
    thumbnailUrl = "https://i.ytimg.com/vi/aaaaaaaaaa1/hqdefault.jpg",
    isLive = false,
    membersOnly = true,
    upcoming = true,
)

/** Every optional field unknown. */
private val BARE_ITEM = VideoItem(
    id = VideoId("bbbbbbbbbb1"),
    title = "Live now",
    uploaderName = null,
    uploaderUrl = null,
    durationSeconds = -1,
    viewCount = -1,
    textualUploadDate = null,
    uploadDate = null,
    thumbnailUrl = null,
    isLive = true,
)
