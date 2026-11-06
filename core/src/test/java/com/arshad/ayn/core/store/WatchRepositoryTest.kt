package com.arshad.ayn.core.store

import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.lock.OwnerPass
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [WatchRepository] over a real in-memory Room 3 database: the two writers' column groups
 * (M5-D8) in both orders, the M5-D7 thresholds as the store applies them, ordering, and
 * profile scoping, with the position writer naming its own profile.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WatchRepositoryTest {
    private val scope = TestScope()
    private val database: KeDatabase =
        Room.inMemoryDatabaseBuilder<KeDatabase>(RuntimeEnvironment.getApplication())
            .setDriver(AndroidSQLiteDriver())
            .setQueryCoroutineContext(StandardTestDispatcher(scope.testScheduler))
            .build()
    private val clock = MutableClock(T0)
    private val profiles = ProfileRepository(database, clock)
    private val session = ProfileSession()
    private val store: WatchStore = WatchRepository(database, session, clock)
    private var ownerId = 0L

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun anOpenedVideoEntersHistoryWithItsCardAndNoPlace() = ownerTest {
        store.recordOpened(card(VIDEO_A), isShort = false)

        assertEquals(
            listOf(
                WatchEntry(
                    videoId = VIDEO_A,
                    title = "Title a",
                    uploaderName = "Channel",
                    uploaderUrl = CHANNEL,
                    thumbnailUrl = "https://img/a",
                    positionMs = 0,
                    durationMs = null,
                    watched = false,
                    lastWatchedAtMillis = T0,
                    updatedAtMillis = T0,
                ),
            ),
            store.history().first(),
        )
    }

    @Test
    fun aPositionSavedBeforeTheCardKeepsItsPlace() = ownerTest {
        store.recordPosition(save(VIDEO_A, positionMs = 60_000, durationMs = 600_000))
        clock.nowMillis = T0 + 1_000

        store.recordOpened(card(VIDEO_A), isShort = false)

        val entry = store.history().first().single()
        assertEquals("Title a", entry.title)
        assertEquals(60_000L, entry.positionMs)
        assertEquals(600_000L, entry.durationMs)
        assertEquals(T0, entry.lastWatchedAtMillis)
        assertEquals(T0 + 1_000, entry.updatedAtMillis)
    }

    @Test
    fun aCardWithoutAChannelKeepsTheStoredOne() = ownerTest {
        store.recordOpened(card(VIDEO_A), isShort = false)

        store.recordOpened(card(VIDEO_A).copy(uploaderName = null, uploaderUrl = null), isShort = false)

        val entry = store.history().first().single()
        assertEquals("Channel", entry.uploaderName)
        assertEquals(CHANNEL, entry.uploaderUrl)
    }

    @Test
    fun aVideoPlayedAsAShortStaysOneThroughALaterVideoOpenAndPositionWrites() = ownerTest {
        store.recordPosition(save(VIDEO_A, positionMs = 0, durationMs = 30_000))
        store.recordOpened(card(VIDEO_A), isShort = true)

        store.recordOpened(card(VIDEO_A), isShort = false)
        store.recordPosition(save(VIDEO_A, positionMs = 5_000, durationMs = 30_000))

        assertTrue(store.history().first().single().isShort)
    }

    @Test
    fun aVideoOpenedOnlyAsAVideoIsNotAShort() = ownerTest {
        store.recordOpened(card(VIDEO_A), isShort = false)
        store.recordPosition(save(VIDEO_B, positionMs = 60_000, durationMs = 600_000))

        assertEquals(listOf(false, false), store.history().first().map { it.isShort })
    }

    @Test
    fun aCardRecordedBeforeAPositionKeepsItsFields() = ownerTest {
        store.recordOpened(card(VIDEO_A), isShort = false)
        clock.nowMillis = T0 + 1_000

        store.recordPosition(save(VIDEO_A, positionMs = 60_000, durationMs = 600_000))

        val entry = store.history().first().single()
        assertEquals("Title a", entry.title)
        assertEquals("https://img/a", entry.thumbnailUrl)
        assertEquals(60_000L, entry.positionMs)
        assertEquals(T0 + 1_000, entry.lastWatchedAtMillis)
    }

    @Test
    fun aPlaceFrom30SecondsToShortOf90PercentIsAResumePoint() = ownerTest {
        store.recordPosition(save(VIDEO_A, positionMs = 30_000, durationMs = 600_000))
        store.recordPosition(save(VIDEO_B, positionMs = 539_999, durationMs = 600_000))

        assertEquals(30_000L, store.resumePointMs(VIDEO_A))
        assertEquals(539_999L, store.resumePointMs(VIDEO_B))
    }

    @Test
    fun theOpeningSecondsAreNoResumePoint() = ownerTest {
        store.recordPosition(save(VIDEO_A, positionMs = 29_999, durationMs = 600_000))

        assertNull(store.resumePointMs(VIDEO_A))
    }

    @Test
    fun ninetyPercentInIsWatchedWithNoResumePoint() = ownerTest {
        store.recordPosition(save(VIDEO_A, positionMs = 540_000, durationMs = 600_000))

        assertNull(store.resumePointMs(VIDEO_A))
        assertEquals(setOf(VIDEO_A), store.watchedIds().first())
    }

    @Test
    fun anEndedVideoIsWatched() = ownerTest {
        store.recordPosition(save(VIDEO_A, positionMs = 0, durationMs = null, ended = true))

        assertEquals(setOf(VIDEO_A), store.watchedIds().first())
    }

    @Test
    fun aWatchedVideoWatchedAgainStaysWatchedAndCanResume() = ownerTest {
        store.recordPosition(save(VIDEO_A, positionMs = 600_000, durationMs = 600_000, ended = true))

        store.recordPosition(save(VIDEO_A, positionMs = 120_000, durationMs = 600_000))

        assertEquals(setOf(VIDEO_A), store.watchedIds().first())
        assertEquals(120_000L, store.resumePointMs(VIDEO_A))
    }

    @Test
    fun aPositionWhoseLengthIsUnknownKeepsTheStoredLength() = ownerTest {
        store.recordPosition(save(VIDEO_A, positionMs = 60_000, durationMs = 600_000))

        store.recordPosition(save(VIDEO_A, positionMs = 90_000, durationMs = null))

        assertEquals(600_000L, store.history().first().single().durationMs)
        assertEquals(90_000L, store.resumePointMs(VIDEO_A))
    }

    @Test
    fun aVideoOfUnknownLengthHasNoResumePoint() = ownerTest {
        store.recordPosition(save(VIDEO_A, positionMs = 60_000, durationMs = null))

        assertNull(store.resumePointMs(VIDEO_A))
    }

    @Test
    fun historyAndResumePointsListTheMostRecentlyWatchedFirst() = ownerTest {
        store.recordPosition(save(VIDEO_A, positionMs = 60_000, durationMs = 600_000))
        clock.nowMillis = T0 + 1_000
        store.recordPosition(save(VIDEO_B, positionMs = 1_000, durationMs = 600_000))
        clock.nowMillis = T0 + 2_000
        store.recordPosition(save(VIDEO_C, positionMs = 120_000, durationMs = 600_000))

        assertEquals(listOf(VIDEO_C, VIDEO_B, VIDEO_A), store.history().first().map { it.videoId })
        assertEquals(listOf(VIDEO_C, VIDEO_A), store.resumePoints().first().map { it.videoId })
    }

    @Test
    fun theKotlinResumeRuleMatchesTheStoresAtItsEdges() = ownerTest {
        val edges = listOf(
            29_999L to 600_000L, 30_000L to 600_000L, 539_999L to 600_000L, 540_000L to 600_000L,
            60_000L to null, 60_000L to 0L, 0L to 600_000L,
        )
        val ids = edges.indices.map { VideoId("edge${it}xxxxxx") }
        edges.zip(ids).forEach { (edge, id) -> store.recordPosition(save(id, positionMs = edge.first, durationMs = edge.second)) }

        val byStore = store.resumePoints().first().map { it.videoId }.toSet()
        val byKotlin = store.history().first().filter { WatchThresholds.hasResumePoint(it.positionMs, it.durationMs) }.map { it.videoId }.toSet()

        assertEquals(setOf(ids[1], ids[2]), byStore)
        assertEquals(byStore, byKotlin)
    }

    @Test
    fun aPositionSaveGoesToItsOwnProfileNotTheSelectedOne() = ownerTest {
        val other = profiles.newProfile("Yuri")
        session.select(other)

        store.recordPosition(save(VIDEO_A, positionMs = 60_000, durationMs = 600_000, profileId = ownerId))

        assertEquals(emptyList<WatchEntry>(), store.history().first())
        session.select(ownerId)
        assertEquals(listOf(VIDEO_A), store.history().first().map { it.videoId })
    }

    @Test
    fun aPositionSaveForARemovedProfileStoresNothing() = ownerTest {
        val removed = profiles.newProfile("Yuri")
        profiles.remove(OwnerPass(), removed)

        val result = runCatching { store.recordPosition(save(VIDEO_A, positionMs = 60_000, durationMs = 600_000, profileId = removed)) }

        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("FOREIGN KEY"))
        session.select(removed)
        assertEquals(emptyList<WatchEntry>(), store.history().first())
    }

    @Test
    fun removeAndClearTouchOnlyTheSelectedProfile() = ownerTest {
        val other = profiles.newProfile("Yuri")
        store.recordOpened(card(VIDEO_A), isShort = false)
        store.recordOpened(card(VIDEO_B), isShort = false)
        store.recordPosition(save(VIDEO_A, positionMs = 60_000, durationMs = 600_000, profileId = other))

        store.remove(VIDEO_A)
        assertEquals(listOf(VIDEO_B), store.history().first().map { it.videoId })
        store.clear()
        assertEquals(emptyList<WatchEntry>(), store.history().first())

        session.select(other)
        assertEquals(listOf(VIDEO_A), store.history().first().map { it.videoId })
    }

    /** KP-D11: a kids profile's history is the owner's to review; it still records what plays. */
    @Test
    fun aKidsProfileCantRemoveOrClearItsHistory() = ownerTest {
        database.profileDao().setLock(ownerId, kind = "sequence", hash = "h", salt = "s", iterations = 1)
        val kid = (profiles.create(OwnerPass(), "Zainab", ProfileTint.Violet, kids = true) as ProfileEdit.Done).profileId
        session.select(kid)
        store.recordOpened(card(VIDEO_A), isShort = false)
        clock.nowMillis = T0 + 1_000
        store.recordPosition(save(VIDEO_B, positionMs = 60_000, durationMs = 600_000, profileId = kid))

        store.remove(VIDEO_A)
        store.clear()

        assertEquals(listOf(VIDEO_B, VIDEO_A), store.history().first().map { it.videoId })
    }

    private fun ownerTest(body: suspend TestScope.() -> Unit) = scope.runTest {
        ownerId = profiles.ensureOwner().id
        session.select(ownerId)
        body()
    }

    private suspend fun ProfileRepository.newProfile(name: String): Long =
        (create(OwnerPass(), name, ProfileTint.Rose) as ProfileEdit.Done).profileId

    private fun save(
        videoId: VideoId,
        positionMs: Long,
        durationMs: Long?,
        ended: Boolean = false,
        profileId: Long = ownerId,
    ): PositionSave = PositionSave(profileId, videoId, positionMs, durationMs, ended)

    private fun card(videoId: VideoId): VideoItem = VideoItem(
        id = videoId,
        title = "Title ${videoId.value.first()}",
        uploaderName = "Channel",
        uploaderUrl = CHANNEL,
        durationSeconds = 600,
        viewCount = 1,
        textualUploadDate = null,
        uploadDate = null,
        thumbnailUrl = "https://img/${videoId.value.first()}",
        isLive = false,
    )
}

private const val T0 = 1_700_000_000_000L
private const val CHANNEL = "https://www.youtube.com/channel/UCaaaaaaaaaaaaaaaaaaaaaa"
private val VIDEO_A = VideoId("aaaaaaaaaaa")
private val VIDEO_B = VideoId("bbbbbbbbbbb")
private val VIDEO_C = VideoId("ccccccccccc")
