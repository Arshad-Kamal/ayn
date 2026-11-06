package com.arshad.ayn.core.store

import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.arshad.ayn.core.access.ChannelItem
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.lock.OwnerPass
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
 * Every delete path leaves its removal record for sync (M8-D6, M8-D12), keyed by the profile's
 * sync id and stamped by the injected clock; deleting nothing records nothing. Real in-memory
 * Room 3 on the platform SQLite, through the repositories the app uses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemovalRecordsTest {
    private val scope = TestScope()
    private val database: KeDatabase =
        Room.inMemoryDatabaseBuilder<KeDatabase>(RuntimeEnvironment.getApplication())
            .setDriver(AndroidSQLiteDriver())
            .setQueryCoroutineContext(StandardTestDispatcher(scope.testScheduler))
            .build()
    private val clock = MutableClock(T0)
    private val session = ProfileSession()
    private val profiles = ProfileRepository(database, clock)
    private val subscriptions = SubscriptionRepository(database, session, clock)
    private val watch = WatchRepository(database, session, clock)
    private val playlists = PlaylistRepository(database, session, clock)
    private val forYou = ForYouRepository(database, session, clock)
    private val filters = FilterRepository(database, clock)
    private var ownerId = 0L

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun newProfilesAndPlaylistsGetTheirOwnSyncIdsAndWatchLaterTheSharedOne() = ownerTest {
        val yuri = profiles.newProfile("Yuri")
        playlists.create("Mix")
        playlists.create("Mix")
        playlists.addToWatchLater(card(VIDEO_A))

        val profileIds = database.profileDao().all().map { it.syncId }
        assertEquals(setOf(ownerId, yuri), database.profileDao().all().map { it.id }.toSet())
        assertEquals(2, profileIds.toSet().size)
        assertTrue(profileIds.all { UUID_FORM.matches(it) })
        val (watchLater, own) = database.playlistDao().allPlaylists().partition { it.watchLaterMark != null }
        assertEquals(listOf(WATCH_LATER_SYNC_ID), watchLater.map { it.syncId })
        assertEquals(2, own.map { it.syncId }.toSet().size)
        assertTrue(own.all { UUID_FORM.matches(it.syncId) })
    }

    @Test
    fun anUnsubscribeRecordsTheChannelAtTheClock() = ownerTest {
        subscriptions.subscribe(channel(CHANNEL_A))
        clock.nowMillis = T1

        subscriptions.unsubscribe("$CHANNEL_A/videos")

        assertEquals(listOf(record(RemovalKind.SUBSCRIPTION, CHANNEL_A, T1)), database.removalDao().all())
    }

    @Test
    fun removingWhatIsNotThereRecordsNothing() = ownerTest {
        val mix = checkNotNull(playlists.create("Mix"))
        playlists.addToWatchLater(card(VIDEO_B))
        val watchLater = checkNotNull(database.playlistDao().watchLaterId(ownerId))
        filters.addRestriction(OwnerPass(), ownerId, Keyword("prank"))
        subscriptions.subscribe(channel(CHANNEL_B))
        val kid = kidProfile()

        subscriptions.unsubscribe(CHANNEL_A)
        watch.remove(VIDEO_A)
        watch.clear()
        playlists.remove(mix, VIDEO_A)
        playlists.removeFromWatchLater(VIDEO_A)
        playlists.delete(watchLater)
        playlists.delete(MISSING_ID)
        forYou.unhideVideo(VIDEO_A)
        forYou.unhideChannel(CHANNEL_A)
        filters.removeOwn(ownerId, Keyword("prank"))
        filters.removeRestriction(OwnerPass(), ownerId, Keyword("other"))
        filters.removeApprovedChannel(OwnerPass(), ownerId, CHANNEL_B)
        filters.removeApprovedChannel(OwnerPass(), kid, CHANNEL_A)
        profiles.remove(OwnerPass(), MISSING_ID)

        assertEquals(emptyList<RemovalEntity>(), database.removalDao().all())
    }

    /** A kids profile's own unsubscribe and history edits change nothing (KP-D10), so they record
     *  nothing either. */
    @Test
    fun aKidsProfilesOwnRemovalsRecordNothing() = ownerTest {
        val kid = kidProfile()
        filters.approveChannel(OwnerPass(), kid, channel(CHANNEL_A))
        session.select(kid)
        watch.recordOpened(card(VIDEO_A), isShort = false)

        subscriptions.unsubscribe(CHANNEL_A)
        watch.remove(VIDEO_A)
        watch.clear()

        assertEquals(emptyList<RemovalEntity>(), database.removalDao().all())
    }

    @Test
    fun aRepeatRemovalMovesTheRecordsTime() = ownerTest {
        subscriptions.subscribe(channel(CHANNEL_A))
        subscriptions.unsubscribe(CHANNEL_A)
        subscriptions.subscribe(channel(CHANNEL_A))
        clock.nowMillis = T1

        subscriptions.unsubscribe(CHANNEL_A)

        assertEquals(listOf(record(RemovalKind.SUBSCRIPTION, CHANNEL_A, T1)), database.removalDao().all())
    }

    @Test
    fun removingAHistoryEntryOrClearingHistoryRecordsEachVideo() = ownerTest {
        listOf(VIDEO_A, VIDEO_B, VIDEO_C).forEach { watch.recordOpened(card(it), isShort = false) }
        clock.nowMillis = T1
        watch.remove(VIDEO_A)
        clock.nowMillis = T2

        watch.clear()

        assertEquals(
            listOf(
                record(RemovalKind.WATCH, VIDEO_A.value, T1),
                record(RemovalKind.WATCH, VIDEO_B.value, T2),
                record(RemovalKind.WATCH, VIDEO_C.value, T2),
            ),
            database.removalDao().all(),
        )
    }

    @Test
    fun aDeletedPlaylistIsRecordedOnceByItsSyncIdNotItemByItem() = ownerTest {
        val mix = checkNotNull(playlists.create("Mix"))
        playlists.add(mix, card(VIDEO_A))
        val mixSyncId = playlistSyncId(mix)

        playlists.delete(mix)

        assertEquals(listOf(record(RemovalKind.PLAYLIST, mixSyncId, T0)), database.removalDao().all())
    }

    @Test
    fun aRemovedVideoIsRecordedUnderItsPlaylistsSyncId() = ownerTest {
        val mix = checkNotNull(playlists.create("Mix"))
        playlists.add(mix, card(VIDEO_A))
        playlists.addToWatchLater(card(VIDEO_B))

        playlists.remove(mix, VIDEO_A)
        playlists.removeFromWatchLater(VIDEO_B)

        assertEquals(
            listOf(
                record(RemovalKind.PLAYLIST_ITEM, "${playlistSyncId(mix)}/${VIDEO_A.value}", T0),
                record(RemovalKind.PLAYLIST_ITEM, "$WATCH_LATER_SYNC_ID/${VIDEO_B.value}", T0),
            ).sortedBy { it.itemKey },
            database.removalDao().all(),
        )
    }

    @Test
    fun anUnhideRecordsTheVideoOrChannel() = ownerTest {
        forYou.hideVideo(VIDEO_A)
        forYou.hideChannel(CHANNEL_A)

        forYou.unhideVideo(VIDEO_A)
        forYou.unhideChannel("$CHANNEL_A/featured")

        assertEquals(
            listOf(record(RemovalKind.HIDDEN_CHANNEL, CHANNEL_A, T0), record(RemovalKind.HIDDEN_VIDEO, VIDEO_A.value, T0)),
            database.removalDao().all(),
        )
    }

    @Test
    fun aRemovedRuleRecordsItsSetterKindAndValue() = ownerTest {
        val yuri = profiles.newProfile("Yuri")
        filters.addOwn(yuri, Keyword("prank"))
        filters.addRestriction(OwnerPass(), yuri, Keyword("prank"))
        filters.addRestriction(OwnerPass(), yuri, BlockedChannel(CHANNEL_A, "A"))

        filters.removeOwn(yuri, Keyword(" prank "))
        filters.removeRestriction(OwnerPass(), yuri, BlockedChannel(CHANNEL_A, null))

        assertEquals(
            listOf(
                record(RemovalKind.RULE, "0/keyword/prank", T0, syncIdOf(yuri)),
                record(RemovalKind.RULE, "1/channel/$CHANNEL_A", T0, syncIdOf(yuri)),
            ).sortedBy { it.itemKey },
            database.removalDao().all(),
        )
    }

    @Test
    fun aKidsApprovedChannelRemovedByTheOwnerIsRecordedAsItsSubscription() = ownerTest {
        val kid = kidProfile()
        filters.approveChannel(OwnerPass(), kid, channel(CHANNEL_A))

        filters.removeApprovedChannel(OwnerPass(), kid, CHANNEL_A)

        assertEquals(listOf(record(RemovalKind.SUBSCRIPTION, CHANNEL_A, T0, syncIdOf(kid))), database.removalDao().all())
    }

    @Test
    fun aRemovedProfileKeepsOnlyItsOwnRecordAndOthersKeepTheirs() = ownerTest {
        val yuri = profiles.newProfile("Yuri")
        val yuriSyncId = syncIdOf(yuri)
        subscriptions.subscribe(channel(CHANNEL_A))
        subscriptions.unsubscribe(CHANNEL_A)
        session.select(yuri)
        subscriptions.subscribe(channel(CHANNEL_B))
        subscriptions.unsubscribe(CHANNEL_B)
        clock.nowMillis = T1

        profiles.remove(OwnerPass(), yuri)

        assertEquals(
            listOf(
                record(RemovalKind.SUBSCRIPTION, CHANNEL_A, T0, syncIdOf(ownerId)),
                record(RemovalKind.PROFILE, yuriSyncId, T1, yuriSyncId),
            ).sortedBy { it.profileSyncId },
            database.removalDao().all(),
        )
    }

    @Test
    fun anAttemptToRemoveTheOwnerLeavesItsRecordsAsTheyWere() = ownerTest {
        subscriptions.subscribe(channel(CHANNEL_A))
        subscriptions.unsubscribe(CHANNEL_A)
        clock.nowMillis = T1

        profiles.remove(OwnerPass(), ownerId)

        assertEquals(listOf(record(RemovalKind.SUBSCRIPTION, CHANNEL_A, T0)), database.removalDao().all())
    }

    private fun ownerTest(body: suspend TestScope.() -> Unit) = scope.runTest {
        ownerId = profiles.ensureOwner().id
        session.select(ownerId)
        body()
    }

    private suspend fun syncIdOf(profileId: Long): String = checkNotNull(database.profileDao().profile(profileId)).syncId

    private suspend fun playlistSyncId(playlistId: Long): String =
        database.playlistDao().allPlaylists().single { it.id == playlistId }.syncId

    private suspend fun record(kind: String, key: String, atMillis: Long, profileSyncId: String? = null): RemovalEntity =
        RemovalEntity(profileSyncId ?: syncIdOf(ownerId), kind, key, atMillis)

    /** A kids profile needs the owner's lock first (KP-D14); the hash stands in for one. */
    private suspend fun kidProfile(): Long {
        database.profileDao().setLock(ownerId, kind = "sequence", hash = "h", salt = "s", iterations = 1)
        return (profiles.create(OwnerPass(), "Zainab", ProfileTint.Violet, kids = true) as ProfileEdit.Done).profileId
    }

    private suspend fun ProfileRepository.newProfile(name: String): Long =
        (create(OwnerPass(), name, ProfileTint.Rose) as ProfileEdit.Done).profileId

    private fun channel(url: String): ChannelItem =
        ChannelItem(name = "Channel", url = url, avatarUrl = null, subscriberCount = -1, description = null, verified = false)

    private fun card(videoId: VideoId): VideoItem = VideoItem(
        id = videoId,
        title = "Title",
        uploaderName = "Channel",
        uploaderUrl = CHANNEL_A,
        durationSeconds = 600,
        viewCount = 1,
        textualUploadDate = null,
        uploadDate = null,
        thumbnailUrl = null,
        isLive = false,
    )
}

private const val T0 = 1_700_000_000_000L
private const val T1 = T0 + 60_000
private const val T2 = T0 + 120_000
private const val CHANNEL_A = "https://www.youtube.com/channel/UCaaaaaaaaaaaaaaaaaaaaaa"
private const val CHANNEL_B = "https://www.youtube.com/channel/UCbbbbbbbbbbbbbbbbbbbbbb"
private const val MISSING_ID = 9_999L
private val VIDEO_A = VideoId("aaaaaaaaaaa")
private val VIDEO_B = VideoId("bbbbbbbbbbb")
private val VIDEO_C = VideoId("ccccccccccc")
private val UUID_FORM = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
