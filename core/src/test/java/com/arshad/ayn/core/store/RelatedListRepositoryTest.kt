package com.arshad.ayn.core.store

import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.access.newpipe.CHAPTERS_VIDEO_FLOW
import com.arshad.ayn.core.access.newpipe.CHAPTERS_VIDEO_ID
import com.arshad.ayn.core.access.newpipe.FixtureDownloader
import com.arshad.ayn.core.access.newpipe.NewPipeAccess
import com.arshad.ayn.core.lock.OwnerPass
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * For you's related lists (FY-D4) over a real in-memory Room 3 database: what a list keeps, its
 * 3-day reuse, failures, the per-profile cap, profile scoping, the Restricted Mode tag, and the
 * ways a profile's lists go (profile removed, Clear history).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RelatedListRepositoryTest {
    private val scope = TestScope()
    private val database: KeDatabase =
        Room.inMemoryDatabaseBuilder<KeDatabase>(RuntimeEnvironment.getApplication())
            .setDriver(AndroidSQLiteDriver())
            .setQueryCoroutineContext(StandardTestDispatcher(scope.testScheduler))
            .build()
    private val clock = MutableClock(T0)
    private val profiles = ProfileRepository(database, clock)
    private val session = ProfileSession()
    private var level = RestrictedMode.Off
    private val store: RelatedListStore = RelatedListRepository(database, session, restrictedMode = { level }, clock = clock)
    private var ownerId = 0L

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun aRecordedListReadsBackInYouTubesOrderWithEveryField() = ownerTest {
        val items = listOf(FULL_ITEM, BARE_ITEM)

        store.record(SEED, items)

        assertEquals(mapOf(SEED to RelatedList(items, fetchedAt = Instant.fromEpochMilliseconds(T0), failedAt = null)), store.lists(listOf(SEED)))
    }

    @Test
    fun anEmptyListRecordsNothing() = ownerTest {
        store.record(SEED, emptyList())

        assertEquals(emptyMap<VideoId, RelatedList>(), store.lists(listOf(SEED)))
    }

    @Test
    fun onlyTheAskedVideosListsComeBack() = ownerTest {
        store.record(SEED, listOf(item(1)))
        store.record(OTHER_SEED, listOf(item(2)))

        assertEquals(setOf(OTHER_SEED), store.lists(listOf(OTHER_SEED, VideoId("nolistyet00"))).keys)
    }

    @Test
    fun aListIsReusedForThreeDays() = ownerTest {
        store.record(SEED, listOf(item(1)))
        val list = store.lists(listOf(SEED)).getValue(SEED)
        val fetchedAt = Instant.fromEpochMilliseconds(T0)

        assertTrue(list.isFresh(fetchedAt + 3.days - 1.milliseconds))
        assertFalse(list.isFresh(fetchedAt + 3.days))
    }

    @Test
    fun aNewFetchReplacesTheListAndClearsTheFailure() = ownerTest {
        store.record(SEED, listOf(item(1)))
        clock.nowMillis = T0 + 1_000
        store.recordFailure(SEED)
        clock.nowMillis = T0 + 2_000

        store.record(SEED, listOf(item(2), item(3)))

        assertEquals(
            RelatedList(listOf(item(2), item(3)), fetchedAt = Instant.fromEpochMilliseconds(T0 + 2_000), failedAt = null),
            store.lists(listOf(SEED)).getValue(SEED),
        )
    }

    @Test
    fun aFailedFetchKeepsTheListItHad() = ownerTest {
        store.record(SEED, listOf(item(1)))
        clock.nowMillis = T0 + 1_000

        store.recordFailure(SEED)

        assertEquals(
            RelatedList(listOf(item(1)), fetchedAt = Instant.fromEpochMilliseconds(T0), failedAt = Instant.fromEpochMilliseconds(T0 + 1_000)),
            store.lists(listOf(SEED)).getValue(SEED),
        )
    }

    @Test
    fun aFailureWithNoListIsRememberedAsNeverFetched() = ownerTest {
        store.recordFailure(SEED)

        val list = store.lists(listOf(SEED)).getValue(SEED)

        assertEquals(RelatedList(emptyList(), fetchedAt = null, failedAt = Instant.fromEpochMilliseconds(T0)), list)
        assertFalse(list.isFresh(Instant.fromEpochMilliseconds(T0)))
    }

    @Test
    fun aProfileKeepsItsNewestThreeHundredLists() = ownerTest {
        // Seed 0 is the oldest write; seed 1's failure, written last, makes it the newest.
        (0..RelatedListStore.MAX_LISTS).forEach { n ->
            clock.nowMillis = T0 + n
            store.record(seed(n), listOf(item(1)))
        }
        clock.nowMillis = T0 + 10_000
        store.recordFailure(seed(1))
        clock.nowMillis = T0 + 10_001

        store.record(seed(RelatedListStore.MAX_LISTS + 1), listOf(item(1)))

        val kept = store.lists((0..RelatedListStore.MAX_LISTS + 1).map(::seed)).keys
        assertEquals(RelatedListStore.MAX_LISTS, kept.size)
        assertFalse(seed(0) in kept)
        assertFalse(seed(2) in kept)
        assertTrue(seed(1) in kept)
        assertTrue(seed(RelatedListStore.MAX_LISTS + 1) in kept)
    }

    @Test
    fun aFailureAtTheCapPushesOutTheOldestList() = ownerTest {
        (1..RelatedListStore.MAX_LISTS).forEach { n ->
            clock.nowMillis = T0 + n
            store.record(seed(n), listOf(item(1)))
        }
        clock.nowMillis = T0 + 10_000

        store.recordFailure(SEED)

        val kept = store.lists((1..RelatedListStore.MAX_LISTS).map(::seed) + SEED).keys
        assertEquals(RelatedListStore.MAX_LISTS, kept.size)
        assertFalse(seed(1) in kept)
        assertTrue(SEED in kept)
    }

    /** The real seam's output: the recorded watch page's related list, parsed by the fork. */
    @Test
    fun aRecordedPagesRelatedListReadsBackToTheMillisecond() = ownerTest {
        val page = NewPipeAccess(downloader = FixtureDownloader(CHAPTERS_VIDEO_FLOW)).video(VideoId(CHAPTERS_VIDEO_ID))
        assertTrue("the recorded page has related videos", page.relatedVideos.isNotEmpty())

        store.record(page.id, page.relatedVideos)

        val expected = page.relatedVideos.map { it.copy(uploadDate = it.uploadDate?.toEpochMilliseconds()?.let(Instant::fromEpochMilliseconds)) }
        assertEquals(expected, store.lists(listOf(page.id)).getValue(page.id).items)
    }

    @Test
    fun theCapCountsEachProfileApart() = ownerTest {
        store.record(SEED, listOf(item(1)))
        val other = profiles.newProfile("Yuri")
        session.select(other)
        (1..RelatedListStore.MAX_LISTS).forEach { n ->
            clock.nowMillis = T0 + n
            store.record(seed(n), listOf(item(1)))
        }

        session.select(ownerId)

        assertEquals(setOf(SEED), store.lists(listOf(SEED)).keys)
    }

    @Test
    fun eachProfileReadsOnlyItsOwnLists() = ownerTest {
        store.record(SEED, listOf(item(1)))
        val other = profiles.newProfile("Yuri")

        session.select(other)

        assertEquals(emptyMap<VideoId, RelatedList>(), store.lists(listOf(SEED)))
    }

    @Test
    fun theListsGoWithTheirProfile() = ownerTest {
        val other = profiles.newProfile("Yuri")
        session.select(other)
        store.record(SEED, listOf(item(1)))

        profiles.remove(OwnerPass(), other)

        assertEquals(emptyList<RelatedListEntity>(), database.relatedListDao().lists(other, listOf(SEED.value)))
    }

    @Test
    fun clearHistoryEmptiesTheProfilesListsOnly() = ownerTest {
        store.record(SEED, listOf(item(1)))
        val other = profiles.newProfile("Yuri")
        session.select(other)
        store.record(SEED, listOf(item(2)))
        session.select(ownerId)

        WatchRepository(database, session, clock).clear()

        assertEquals(emptyMap<VideoId, RelatedList>(), store.lists(listOf(SEED)))
        session.select(other)
        assertEquals(listOf(item(2)), store.lists(listOf(SEED)).getValue(SEED).items)
    }

    /** KP-D15: a list fetched under another Restricted Mode level reads as none, so it is fetched
     *  again; the next write replaces it under the current level. */
    @Test
    fun aListWrittenUnderAnotherRestrictedModeReadsAsNone() = ownerTest {
        store.record(SEED, listOf(item(1)))

        level = RestrictedMode.Strict
        assertEquals(emptyMap<VideoId, RelatedList>(), store.lists(listOf(SEED)))
        store.record(SEED, listOf(item(2)))
        assertEquals(listOf(item(2)), store.lists(listOf(SEED)).getValue(SEED).items)

        level = RestrictedMode.Off
        assertEquals(emptyMap<VideoId, RelatedList>(), store.lists(listOf(SEED)))
    }

    @Test
    fun aRowFromBeforeTheTagReadsAsFetchedWithoutRestrictedMode() = ownerTest {
        database.relatedListDao().upsert(
            RelatedListEntity(ownerId, SEED.value, encodeRelatedItems(listOf(item(1))), fetchedAtMillis = T0, failedAtMillis = null, restrictedMode = null),
        )

        assertEquals(listOf(item(1)), store.lists(listOf(SEED)).getValue(SEED).items)
        level = RestrictedMode.Strict
        assertNull(store.lists(listOf(SEED))[SEED])
    }

    @Test
    fun aDamagedRowReadsAsNoList() = ownerTest {
        database.relatedListDao().upsert(
            RelatedListEntity(ownerId, SEED.value, "{not a list", fetchedAtMillis = T0, failedAtMillis = null, restrictedMode = null),
        )

        assertNull(store.lists(listOf(SEED))[SEED])
    }

    @Test
    fun aStoredEntryWithABadIdIsDropped() {
        val text = """[{"id":"short","title":"x","durationSeconds":1,"viewCount":1},""" +
            """{"id":"aaaaaaaaaa1","title":"kept","durationSeconds":61,"viewCount":-1}]"""

        assertEquals(listOf("aaaaaaaaaa1"), decodeRelatedItems(text)?.map { it.id.value })
    }

    private fun ownerTest(body: suspend TestScope.() -> Unit) = scope.runTest {
        ownerId = profiles.ensureOwner().id
        session.select(ownerId)
        body()
    }

    private suspend fun ProfileRepository.newProfile(name: String): Long =
        (create(OwnerPass(), name, ProfileTint.Rose) as ProfileEdit.Done).profileId
}

private const val T0 = 1_700_000_000_000L
private val SEED = VideoId("seedseed001")
private val OTHER_SEED = VideoId("seedseed002")

private fun seed(n: Int): VideoId = VideoId("s%010d".format(n))

private fun item(n: Int): VideoItem = BARE_ITEM.copy(id = VideoId("r%010d".format(n)), title = "Related $n")

/** Every field set, as a listing parse gives it. */
private val FULL_ITEM = VideoItem(
    id = VideoId("aaaaaaaaaa1"),
    title = "Why the Moon's craters are round",
    uploaderName = "Veritasium",
    uploaderUrl = "https://www.youtube.com/channel/UCHnyfMqiRRG1u-2MsSQLbXA",
    durationSeconds = 754,
    viewCount = 1_234_567,
    textualUploadDate = "3 days ago",
    uploadDate = Instant.fromEpochMilliseconds(T0 - 3 * 86_400_000L),
    thumbnailUrl = "https://i.ytimg.com/vi/aaaaaaaaaa1/hqdefault.jpg",
    isLive = false,
)

/** Every optional field unknown; a live entry (length -1). */
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
