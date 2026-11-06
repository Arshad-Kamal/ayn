package com.arshad.ayn.core.store

import androidx.room3.Room
import androidx.room3.useReaderConnection
import androidx.sqlite.SQLiteException
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.lock.OwnerPass
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [PlaylistRepository] over a real in-memory Room 3 database (M5-D13): save order, one entry per
 * video, the hub's summaries, Watch later, names, and profile scoping.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaylistRepositoryTest {
    private val scope = TestScope()
    private val database: KeDatabase =
        Room.inMemoryDatabaseBuilder<KeDatabase>(RuntimeEnvironment.getApplication())
            .setDriver(AndroidSQLiteDriver())
            .setQueryCoroutineContext(StandardTestDispatcher(scope.testScheduler))
            .build()
    private val clock = MutableClock(T0)
    private val profiles = ProfileRepository(database, clock)
    private val session = ProfileSession()
    private val store: PlaylistStore = PlaylistRepository(database, session, clock)

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun savedVideosComeBackAsTheirCardsInSaveOrder() = ownerTest {
        val playlist = store.create("Mix")!!
        store.add(playlist, card(VIDEO_B))
        clock.nowMillis = T0 + 1_000
        store.add(playlist, card(VIDEO_A))

        assertEquals(
            listOf(SavedVideo(saved(VIDEO_B), addedAtMillis = T0), SavedVideo(saved(VIDEO_A), addedAtMillis = T0 + 1_000)),
            store.videos(playlist).first(),
        )
    }

    @Test
    fun savingAVideoAgainKeepsItsPlaceItsCardAndThePlaylistsDate() = ownerTest {
        val playlist = store.create("Mix")!!
        store.add(playlist, card(VIDEO_A))
        store.add(playlist, card(VIDEO_B))
        clock.nowMillis = T0 + 5_000

        store.add(playlist, card(VIDEO_A).copy(title = "Renamed upstream"))

        assertEquals(listOf("Title a", "Title b"), store.videos(playlist).first().map { it.video.title })
        assertEquals(T0, store.playlists().first().single().updatedAtMillis)
    }

    @Test
    fun summariesCountTheLengthTakeTheFirstCoverAndListTheLastChangedFirst() = ownerTest {
        val older = store.create("Older")!!
        clock.nowMillis = T0 + 1_000
        val newer = store.create("Newer")!!
        clock.nowMillis = T0 + 2_000
        store.add(older, card(VIDEO_C, durationSeconds = 30))
        store.add(older, card(VIDEO_A, durationSeconds = 600))
        store.add(older, card(VIDEO_B, durationSeconds = -1))

        assertEquals(
            listOf(
                PlaylistSummary(older, "Older", videoCount = 3, totalDurationSeconds = 630, coverThumbnailUrl = "https://img/c", updatedAtMillis = T0 + 2_000),
                PlaylistSummary(newer, "Newer", videoCount = 0, totalDurationSeconds = 0, coverThumbnailUrl = null, updatedAtMillis = T0 + 1_000),
            ),
            store.playlists().first(),
        )
    }

    @Test
    fun lengthsTooLargeToAddUpStillListTheirPlaylist() = ownerTest {
        val playlist = store.create("Mix")!!
        store.add(playlist, card(VIDEO_A, durationSeconds = Long.MAX_VALUE))
        store.add(playlist, card(VIDEO_B, durationSeconds = Long.MAX_VALUE))

        assertEquals(Long.MAX_VALUE, store.playlists().first().single().totalDurationSeconds)
    }

    @Test
    fun summariesFollowItemWritesAsTheyHappen() = ownerTest {
        val playlist = store.create("Mix")!!
        val counts = mutableListOf<Int>()
        launch { store.playlists().take(2).map { it.single().videoCount }.toList(counts) }
        advanceUntilIdle()

        // Straight to the item table, without the store's playlist stamp: the summary's subqueries
        // alone must trigger the re-emission.
        database.playlistDao().insertItem(itemOf(playlist, VIDEO_A))
        advanceUntilIdle()

        assertEquals(listOf(0, 1), counts)
    }

    @Test
    fun removingAVideoStampsThePlaylistAndKeepsTheOthersInOrder() = ownerTest {
        val playlist = store.create("Mix")!!
        listOf(VIDEO_A, VIDEO_B, VIDEO_C).forEach { store.add(playlist, card(it)) }
        clock.nowMillis = T0 + 3_000

        store.remove(playlist, VIDEO_B)

        assertEquals(T0 + 3_000, store.playlists().first().single().updatedAtMillis)
        clock.nowMillis = T0 + 4_000
        store.add(playlist, card(VIDEO_B))
        assertEquals(listOf(VIDEO_A, VIDEO_C, VIDEO_B), store.videos(playlist).first().map { it.video.id })
    }

    @Test
    fun removingAVideoThatIsntThereChangesNothing() = ownerTest {
        val playlist = store.create("Mix")!!
        clock.nowMillis = T0 + 3_000

        store.remove(playlist, VIDEO_A)

        assertEquals(T0, store.playlists().first().single().updatedAtMillis)
    }

    @Test
    fun namesAreTrimmedAndBoundedOnCreateAndRename() = ownerTest {
        assertNull(store.create("   "))
        assertNull(store.create("x".repeat(MAX_PLAYLIST_NAME + 1)))
        val playlist = store.create("  Mix  ")!!
        assertEquals("Mix", store.playlists().first().single().name)
        clock.nowMillis = T0 + 1_000

        assertFalse(store.rename(playlist, ""))
        assertTrue(store.rename(playlist, " ${"y".repeat(MAX_PLAYLIST_NAME)} "))

        val summary = store.playlists().first().single()
        assertEquals("y".repeat(MAX_PLAYLIST_NAME), summary.name)
        assertEquals(T0 + 1_000, summary.updatedAtMillis)
    }

    @Test
    fun twoPlaylistsMayShareAName() = ownerTest {
        store.create("Mix")
        store.create("mix")

        assertEquals(setOf("Mix", "mix"), store.playlists().first().mapTo(HashSet()) { it.name })
    }

    @Test
    fun deletingAPlaylistTakesItsVideos() = ownerTest {
        val playlist = store.create("Mix")!!
        store.add(playlist, card(VIDEO_A))

        assertTrue(store.delete(playlist))

        assertEquals(emptyList<PlaylistSummary>(), store.playlists().first())
        assertEquals(emptyList<SavedVideo>(), store.videos(playlist).first())
        assertEquals(SavedIn(watchLater = false, playlistIds = emptySet()), store.savedIn(VIDEO_A).first())
        assertFalse(store.delete(playlist))
    }

    @Test
    fun watchLaterIsSeparateFromThePlaylistsAndCantBeRenamedOrDeleted() = ownerTest {
        val playlist = store.create("Mix")!!
        store.addToWatchLater(card(VIDEO_B))
        store.addToWatchLater(card(VIDEO_A))
        store.add(playlist, card(VIDEO_A))

        assertEquals(listOf(VIDEO_B, VIDEO_A), store.watchLater().first().map { it.video.id })
        assertEquals(listOf(playlist), store.playlists().first().map { it.id })
        assertEquals(SavedIn(watchLater = true, playlistIds = setOf(playlist)), store.savedIn(VIDEO_A).first())
        assertEquals(SavedIn(watchLater = true, playlistIds = emptySet()), store.savedIn(VIDEO_B).first())

        val watchLaterId = database.playlistDao().watchLaterId(ownerId())!!
        assertFalse(store.rename(watchLaterId, "Mine"))
        assertFalse(store.delete(watchLaterId))

        store.removeFromWatchLater(VIDEO_A)
        assertEquals(listOf(VIDEO_B), store.watchLater().first().map { it.video.id })
        assertEquals(SavedIn(watchLater = false, playlistIds = setOf(playlist)), store.savedIn(VIDEO_A).first())
    }

    @Test
    fun aProfileCantHoldASecondWatchLater() = ownerTest {
        store.addToWatchLater(card(VIDEO_A))
        // Its own sync id, so only the Watch later mark can refuse it.
        val second = PlaylistEntity(
            profileId = ownerId(),
            name = "x",
            watchLaterMark = WATCH_LATER_MARK,
            createdAtMillis = 0,
            updatedAtMillis = 0,
            syncId = "second",
        )

        try {
            database.playlistDao().insertPlaylist(second)
            fail("a second Watch later was stored")
        } catch (expected: SQLiteException) {
            assertTrue(expected.message, expected.message.orEmpty().contains("playlists.watchLaterMark"))
        }
    }

    @Test
    fun anotherProfilesPlaylistReadsEmptyAndTakesNoWrites() = ownerTest {
        val owners = store.create("Owner's")!!
        store.add(owners, card(VIDEO_A))
        store.addToWatchLater(card(VIDEO_A))
        val other = profiles.newProfile("Yuri")

        session.select(other)
        store.add(owners, card(VIDEO_B))
        store.remove(owners, VIDEO_A)

        assertEquals(emptyList<SavedVideo>(), store.videos(owners).first())
        assertEquals(emptyList<PlaylistSummary>(), store.playlists().first())
        assertEquals(emptyList<SavedVideo>(), store.watchLater().first())
        assertEquals(SavedIn(watchLater = false, playlistIds = emptySet()), store.savedIn(VIDEO_A).first())
        assertFalse(store.rename(owners, "Mine"))
        assertFalse(store.delete(owners))

        session.select(ownerId())
        assertEquals(listOf(VIDEO_A), store.videos(owners).first().map { it.video.id })
        assertEquals("Owner's", store.playlists().first().single().name)
    }

    /** One read for For you: the playlists as the hub lists them (last changed first, the newer of
     *  a tie first), each in order; Watch later and another profile's playlists left out. */
    @Test
    fun playlistVideosListEveryOwnPlaylistInTheHubsOrder() = ownerTest {
        val older = store.create("Older")!!
        store.add(older, card(VIDEO_B))
        store.add(older, card(VIDEO_A))
        clock.nowMillis = T0 + 1_000
        val newer = store.create("Newer")!!
        store.add(newer, card(VIDEO_C))
        store.add(newer, card(VIDEO_A))
        val tied = store.create("Tied")!!
        store.add(tied, card(VIDEO_B))
        clock.nowMillis = T0 + 2_000
        store.add(older, card(VIDEO_C))
        store.addToWatchLater(card(VIDEO_C))
        val other = profiles.newProfile("Yuri")
        session.select(other)
        store.add(store.create("Yuri's")!!, card(VIDEO_B))
        session.select(ownerId())

        val read = store.playlistVideos().first()

        assertEquals(listOf(older, tied, newer), store.playlists().first().map { it.id })
        assertEquals(listOf(VIDEO_B, VIDEO_A, VIDEO_C, VIDEO_B, VIDEO_C, VIDEO_A), read.map { it.video.id })
        assertEquals(store.playlists().first().flatMap { store.videos(it.id).first() }, read)
    }

    @Test
    fun eachProfileHasItsOwnWatchLater() = ownerTest {
        store.addToWatchLater(card(VIDEO_A))
        val other = profiles.newProfile("Yuri")

        session.select(other)
        store.addToWatchLater(card(VIDEO_B))

        assertEquals(listOf(VIDEO_B), store.watchLater().first().map { it.video.id })
        session.select(ownerId())
        assertEquals(listOf(VIDEO_A), store.watchLater().first().map { it.video.id })
    }

    @Test
    fun removingAProfileTakesItsPlaylists() = ownerTest {
        val other = profiles.newProfile("Yuri")
        session.select(other)
        val playlist = store.create("Mix")!!
        store.add(playlist, card(VIDEO_A))
        store.addToWatchLater(card(VIDEO_B))

        assertTrue(profiles.remove(OwnerPass(), other))

        assertEquals(0, countRows("SELECT COUNT(*) FROM playlists"))
        assertEquals(0, countRows("SELECT COUNT(*) FROM playlist_items"))
    }

    private suspend fun ownerId(): Long = profiles.ensureOwner().id

    private fun ownerTest(body: suspend TestScope.() -> Unit) = scope.runTest {
        session.select(profiles.ensureOwner().id)
        body()
    }

    private suspend fun ProfileRepository.newProfile(name: String): Long =
        (create(OwnerPass(), name, ProfileTint.Rose) as ProfileEdit.Done).profileId

    private suspend fun countRows(sql: String): Int =
        database.useReaderConnection { connection -> connection.usePrepared(sql) { it.step(); it.getInt(0) } }

    private fun card(videoId: VideoId, durationSeconds: Long = 600): VideoItem = VideoItem(
        id = videoId,
        title = "Title ${videoId.value.first()}",
        uploaderName = "Channel",
        uploaderUrl = CHANNEL,
        durationSeconds = durationSeconds,
        viewCount = 1_234,
        textualUploadDate = "3 days ago",
        uploadDate = UPLOADED,
        thumbnailUrl = "https://img/${videoId.value.first()}",
        isLive = false,
    )

    /** [card] as the store gives it back: views, date text and the live marker aren't kept. */
    private fun saved(videoId: VideoId): VideoItem = card(videoId).copy(viewCount = -1, textualUploadDate = null)

    private fun itemOf(playlistId: Long, videoId: VideoId): PlaylistItemEntity = PlaylistItemEntity(
        playlistId = playlistId,
        videoId = videoId.value,
        position = 0,
        title = "Title",
        uploaderName = null,
        uploaderUrl = null,
        durationSeconds = 60,
        uploadDateMillis = null,
        thumbnailUrl = null,
        addedAtMillis = T0,
        updatedAtMillis = T0,
    )
}

private const val T0 = 1_700_000_000_000L
private const val CHANNEL = "https://www.youtube.com/channel/UCaaaaaaaaaaaaaaaaaaaaaa"
private val UPLOADED = Instant.fromEpochMilliseconds(1_690_000_000_000L)
private val VIDEO_A = VideoId("aaaaaaaaaaa")
private val VIDEO_B = VideoId("bbbbbbbbbbb")
private val VIDEO_C = VideoId("ccccccccccc")
