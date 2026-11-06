package com.arshad.ayn.core.store

import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.lock.OwnerPass
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A profile's For you state (FY-D6, FY-D11) over a real in-memory Room 3 database: feedback with
 * its undo, channels by their canonical URL, impressions per Home visit and per opening, the
 * 30-day prune, and profile scoping.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ForYouRepositoryTest {
    private val scope = TestScope()
    private val database: KeDatabase =
        Room.inMemoryDatabaseBuilder<KeDatabase>(RuntimeEnvironment.getApplication())
            .setDriver(AndroidSQLiteDriver())
            .setQueryCoroutineContext(StandardTestDispatcher(scope.testScheduler))
            .build()
    private val clock = MutableClock(T0)
    private val profiles = ProfileRepository(database, clock)
    private val session = ProfileSession()
    private val store: ForYouStore = ForYouRepository(database, session, clock)
    private var ownerId = 0L

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun aHiddenVideoIsHiddenUntilUndone() = ownerTest {
        store.hideVideo(VIDEO_A)
        assertEquals(setOf(VIDEO_A), store.hiddenVideos().first())

        store.unhideVideo(VIDEO_A)

        assertEquals(emptySet<VideoId>(), store.hiddenVideos().first())
    }

    @Test
    fun hidingAVideoTwiceKeepsTheFirstTime() = ownerTest {
        store.hideVideo(VIDEO_A)
        clock.nowMillis = T0 + 1_000

        store.hideVideo(VIDEO_A)

        assertEquals(listOf(T0), database.forYouDao().allHiddenVideos().map { it.hiddenAtMillis })
    }

    @Test
    fun aChannelIsHiddenAndUndoneUnderItsCanonicalUrl() = ownerTest {
        store.hideChannel("$CHANNEL/videos")
        assertEquals(setOf(CHANNEL), store.hiddenChannels().first())

        store.unhideChannel("http://m.youtube.com/channel/UCHnyfMqiRRG1u-2MsSQLbXA")

        assertEquals(emptySet<String>(), store.hiddenChannels().first())
    }

    @Test
    fun eachProfileHasItsOwnFeedback() = ownerTest {
        store.hideVideo(VIDEO_A)
        store.hideChannel(CHANNEL)

        session.select(profiles.newProfile("Yuri"))

        assertEquals(emptySet<VideoId>(), store.hiddenVideos().first())
        assertEquals(emptySet<String>(), store.hiddenChannels().first())
    }

    @Test
    fun eachHomeVisitCountsACardOnceWithItsChannel() = ownerTest {
        store.recordShown(listOf(card(VIDEO_A), card(VIDEO_A), card(VIDEO_B, uploaderUrl = null)))
        clock.nowMillis = T0 + 1_000

        store.recordShown(listOf(card(VIDEO_A, uploaderUrl = null)))

        assertEquals(
            setOf(
                Impression(VIDEO_A, CHANNEL, shownCount = 2, lastShownAt = at(T0 + 1_000), openedAt = null),
                Impression(VIDEO_B, null, shownCount = 1, lastShownAt = at(T0), openedAt = null),
            ),
            store.impressions().toSet(),
        )
    }

    @Test
    fun anOpenedCardKeepsItsShows() = ownerTest {
        store.recordShown(listOf(card(VIDEO_A)))
        clock.nowMillis = T0 + 5_000

        store.recordOpened(card(VIDEO_A))

        assertEquals(listOf(Impression(VIDEO_A, CHANNEL, 1, at(T0), openedAt = at(T0 + 5_000))), store.impressions())
    }

    @Test
    fun aCardOpenedBeforeAShowWasRecordedHasNone() = ownerTest {
        store.recordOpened(card(VIDEO_A))

        assertEquals(listOf(Impression(VIDEO_A, CHANNEL, 0, lastShownAt = null, openedAt = at(T0))), store.impressions())
    }

    @Test
    fun aShowDropsImpressionsThirtyDaysOld() = ownerTest {
        store.recordShown(listOf(card(VIDEO_A)))
        store.recordShown(listOf(card(VIDEO_B)))
        clock.nowMillis = T0 + 1
        store.recordOpened(card(VIDEO_B))
        clock.nowMillis = (at(T0) + 30.days).toEpochMilliseconds()

        store.recordShown(listOf(card(VIDEO_C)))

        assertEquals(setOf(VIDEO_B, VIDEO_C), store.impressions().map { it.videoId }.toSet())
    }

    @Test
    fun readsLeaveOutImpressionsThirtyDaysOld() = ownerTest {
        store.recordShown(listOf(card(VIDEO_A)))

        clock.nowMillis = (at(T0) + 30.days).toEpochMilliseconds()

        assertEquals(emptyList<Impression>(), store.impressions())
    }

    @Test
    fun aShowDropsOtherProfilesOldImpressionsToo() = ownerTest {
        store.recordShown(listOf(card(VIDEO_A)))
        session.select(profiles.newProfile("Yuri"))
        clock.nowMillis = (at(T0) + 30.days).toEpochMilliseconds()

        store.recordShown(listOf(card(VIDEO_B)))

        assertEquals(emptyList<ImpressionEntity>(), database.forYouDao().impressions(ownerId, afterMillis = 0))
    }

    /** FY-D11: an Undo reaches whoever follows the hidden set at once. */
    @Test
    fun theHiddenSetFollowsHideAndUndo() = ownerTest {
        val seen = mutableListOf<Set<VideoId>>()
        launch { store.hiddenVideos().take(3).toList(seen) }
        advanceUntilIdle()

        store.hideVideo(VIDEO_A)
        advanceUntilIdle()
        store.unhideVideo(VIDEO_A)
        advanceUntilIdle()

        assertEquals(listOf(emptySet(), setOf(VIDEO_A), emptySet()), seen)
    }

    @Test
    fun eachProfileReadsOnlyItsOwnImpressions() = ownerTest {
        store.recordShown(listOf(card(VIDEO_A)))

        session.select(profiles.newProfile("Yuri"))

        assertEquals(emptyList<Impression>(), store.impressions())
    }

    @Test
    fun forYouStateGoesWithItsProfile() = ownerTest {
        val other = profiles.newProfile("Yuri")
        session.select(other)
        store.hideVideo(VIDEO_A)
        store.hideChannel(CHANNEL)
        store.recordShown(listOf(card(VIDEO_B)))

        profiles.remove(OwnerPass(), other)

        assertEquals(emptyList<HiddenVideoEntity>(), database.forYouDao().allHiddenVideos())
        assertEquals(emptyList<HiddenChannelEntity>(), database.forYouDao().allHiddenChannels())
        assertEquals(emptyList<ImpressionEntity>(), database.forYouDao().impressions(other, afterMillis = 0))
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
private const val CHANNEL = "https://www.youtube.com/channel/UCHnyfMqiRRG1u-2MsSQLbXA"
private val VIDEO_A = VideoId("aaaaaaaaaa1")
private val VIDEO_B = VideoId("bbbbbbbbbb1")
private val VIDEO_C = VideoId("cccccccccc1")

private fun at(millis: Long): Instant = Instant.fromEpochMilliseconds(millis)

/** A card whose channel URL is a tab of [CHANNEL], so the store's canonical key shows. */
private fun card(id: VideoId, uploaderUrl: String? = "$CHANNEL/videos"): VideoItem = VideoItem(
    id = id,
    title = "Title ${id.value}",
    uploaderName = "Veritasium",
    uploaderUrl = uploaderUrl,
    durationSeconds = 600,
    viewCount = -1,
    textualUploadDate = null,
    uploadDate = null,
    thumbnailUrl = null,
    isLive = false,
)
