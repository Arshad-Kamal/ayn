package com.arshad.ayn.core.sync

import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.arshad.ayn.core.access.ChannelItem
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.backup.BackupBundle
import com.arshad.ayn.core.backup.BackupRemoval
import com.arshad.ayn.core.lock.OwnerPass
import com.arshad.ayn.core.store.ChatPlacement
import com.arshad.ayn.core.store.FilterRepository
import com.arshad.ayn.core.store.ForYouRepository
import com.arshad.ayn.core.store.KeDatabase
import com.arshad.ayn.core.store.Keyword
import com.arshad.ayn.core.store.MAX_PROFILES
import com.arshad.ayn.core.store.MutableClock
import com.arshad.ayn.core.store.PlaylistRepository
import com.arshad.ayn.core.store.PositionSave
import com.arshad.ayn.core.store.ProfileEdit
import com.arshad.ayn.core.store.ProfileRepository
import com.arshad.ayn.core.store.ProfileSession
import com.arshad.ayn.core.store.ProfileSettingsRepository
import com.arshad.ayn.core.store.ProfileTint
import com.arshad.ayn.core.store.RemovalKind
import com.arshad.ayn.core.store.RestrictedMode
import com.arshad.ayn.core.store.SubscriptionRepository
import com.arshad.ayn.core.store.WatchRepository
import com.arshad.ayn.core.store.ensureOwner
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [SyncDatabase] over two real in-memory databases, a TV and a phone, on one injected clock
 * (M8-D16): a joining device pairs, an applied merge reads back as the merge and both devices
 * converge, a second apply writes nothing; two owners, name clashes, the profile cap, the open
 * profile and old removal records.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncDatabaseTest {
    private val scope = TestScope()
    private val dispatcher = StandardTestDispatcher(scope.testScheduler)
    private val clock = MutableClock(T0)
    private val tv = Device()
    private val phone = Device()

    @After
    fun closeDatabases() {
        tv.database.close()
        phone.database.close()
    }

    /** M8-D5, M8-D13: owner with owner, a profile by name and kind, a playlist by name; the
     *  removal records under the old ids follow them. */
    @Test
    fun aJoiningDeviceTakesThePairedIdsWithItsRemovalRecords() = scope.runTest {
        tv.fill()
        val tvIds = tv.snapshot()
        val phoneOwner = phone.owner()
        val phoneMira = phone.addProfile("MIRA")
        phone.session.select(phoneOwner)
        val mix = checkNotNull(phone.playlists.create("Mix"))
        phone.playlists.add(mix, card(V1))
        tick()
        phone.playlists.remove(mix, V1)

        phone.data.apply(listOf(tvIds), pair = true, openProfileId = null)

        val snapshot = phone.snapshot()
        assertEquals(tvIds.profiles.map { it.syncId }, snapshot.profiles.map { it.syncId })
        assertEquals(phoneMira, phone.database.profileDao().all().single { it.syncId == tvIds.profiles[1].syncId }.id)
        val tvMix = tvIds.profiles[0].playlists.single { it.name == "Mix" }.syncId
        assertEquals(listOf(tvMix), snapshot.profiles[0].playlists.filter { it.name == "Mix" }.map { it.syncId })
        assertTrue(
            snapshot.removals.contains(BackupRemoval(tvIds.profiles[0].syncId!!, RemovalKind.PLAYLIST_ITEM, "$tvMix/${V1.value}", clock.nowMillis)),
        )
    }

    /** What an apply writes reads back as the merge, record for record, in the merge's order
     *  (a profile and a playlist older than this device's own arrive after them); every kind's
     *  removal arrives; both devices end on the same snapshot. */
    @Test
    fun anAppliedMergeReadsBackAsTheMergeAndBothDevicesConverge() = scope.runTest {
        tv.fill()
        phone.owner()
        phone.data.apply(listOf(tv.snapshot()), pair = true, openProfileId = null)
        tick()
        val tvOwner = tv.ownerId()
        tv.subscriptions.unsubscribe(CHANNEL_A)
        tv.playlists.addToWatchLater(card(V2))
        tv.watch.remove(V2)
        tv.forYou.unhideVideo(V2)
        tv.forYou.unhideChannel(CHANNEL_B)
        tv.filters.removeOwn(tvOwner, Keyword("prank"))
        tv.playlists.delete(tv.database.playlistDao().allPlaylists().single { it.name == "Mix" }.id)
        tv.playlists.create("Early")
        tv.addProfile("Sam")
        tick()
        phone.session.select(phone.ownerId())
        phone.watch.recordPosition(PositionSave(phone.ownerId(), V1, positionMs = 300_000, durationMs = 600_000, ended = false))
        phone.playlists.create("Late")
        phone.addProfile("Zed")
        phone.subscriptions.subscribe(channel(CHANNEL_C, "Channel C"))
        tick()
        val tvSnapshot = tv.snapshot()
        val expected = mergeSnapshots(phone.snapshot(), tvSnapshot)

        phone.data.apply(listOf(tvSnapshot), pair = false, openProfileId = null)
        tv.data.apply(listOf(phone.snapshot()), pair = false, openProfileId = null)

        assertEquals(expected.copy(exportedAtMillis = 0), phone.snapshot().copy(exportedAtMillis = 0))
        assertEquals(phone.snapshot(), tv.snapshot())
        val owner = expected.profiles[0]
        assertEquals(listOf(CHANNEL_C, CHANNEL_B), owner.subscriptions.map { it.channelUrl })
        assertEquals(listOf(V1.value), owner.history.map { it.videoId })
        assertEquals(300_000L, owner.history.single().positionMs)
        assertEquals(listOf(V3.value, V2.value), owner.watchLater?.videos?.map { it.videoId })
        assertEquals(listOf("Early", "Late"), owner.playlists.map { it.name })
        assertEquals(listOf("Owner", "Mira", "Sam", "Zed"), expected.profiles.map { it.name })
        assertEquals(emptyList<Any>(), owner.hiddenVideos + owner.hiddenChannels + owner.rules)
        assertEquals(ChatPlacement.Side, phone.settings.settings().first().settings.chatPlacement)
    }

    @Test
    fun applyingWhatIsAlreadyHereWritesNothing() = scope.runTest {
        tv.fill()
        phone.owner()
        val writes = mutableListOf<Set<String>>()
        val watching = launch {
            phone.database.invalidationTracker.createFlow(*SYNCED_TABLES, emitInitialState = false).collect { writes += it }
        }
        advanceUntilIdle()
        phone.data.apply(listOf(tv.snapshot()), pair = true, openProfileId = null)
        advanceUntilIdle()
        assertTrue("the watcher sees the first apply's writes", writes.isNotEmpty())
        writes.clear()

        phone.data.apply(listOf(tv.snapshot()), pair = false, openProfileId = null)
        advanceUntilIdle()

        assertEquals(emptyList<Set<String>>(), writes)
        watching.cancel()
    }

    /** Two devices linked at once to an empty folder: both owners end under the smaller id, the
     *  larger one's records and removal records with it. */
    @Test
    fun twoOwnersEndUnderTheSmallerSyncIdOnBothDevices() = scope.runTest {
        tv.owner()
        phone.owner()
        tv.database.profileDao().setSyncId(tv.snapshot().profiles[0].syncId!!, "a-owner")
        phone.database.profileDao().setSyncId(phone.snapshot().profiles[0].syncId!!, "b-owner")
        phone.session.select(phone.ownerId())
        phone.subscriptions.subscribe(channel(CHANNEL_A, "Channel A"))
        phone.subscriptions.subscribe(channel(CHANNEL_B, "Channel B"))
        tick()
        phone.subscriptions.unsubscribe(CHANNEL_B)

        tv.data.apply(listOf(phone.snapshot()), pair = false, openProfileId = null)
        phone.data.apply(listOf(tv.snapshot()), pair = false, openProfileId = null)

        listOf(tv, phone).forEach { device ->
            val snapshot = device.snapshot()
            assertEquals(listOf("a-owner"), snapshot.profiles.map { it.syncId })
            assertEquals(listOf(CHANNEL_A), snapshot.profiles[0].subscriptions.map { it.channelUrl })
            assertEquals(listOf("a-owner"), snapshot.removals.map { it.profileSyncId })
        }
    }

    /** M8-D16: the profile created first keeps the name; the other gets " 2", stamped now, so the
     *  rename syncs instead of being settled by each device alone. */
    @Test
    fun aNameTwoProfilesShareGoesToTheOlderTheOtherGetsANumberStampedNow() = scope.runTest {
        tv.owner()
        phone.owner()
        phone.data.apply(listOf(tv.snapshot()), pair = true, openProfileId = null)
        val tvSam = tv.addProfile("Sam")
        tick()
        phone.addProfile("sam")
        tick()

        phone.data.apply(listOf(tv.snapshot()), pair = false, openProfileId = null)

        val sams = phone.snapshot().profiles.drop(1)
        assertEquals(listOf("Sam", "sam 2"), sams.map { it.name })
        assertEquals(tv.database.profileDao().profile(tvSam)?.syncId, sams[0].syncId)
        assertEquals(clock.nowMillis, sams[1].updatedAtMillis)
        tv.data.apply(listOf(phone.snapshot()), pair = false, openProfileId = null)
        assertEquals(phone.snapshot(), tv.snapshot())
    }

    /** Two profiles swapping names elsewhere: neither name write may meet the other's old name. */
    @Test
    fun twoProfilesSwappingNamesArriveSwapped() = scope.runTest {
        tv.owner()
        phone.owner()
        val ann = tv.addProfile("Ann")
        tick() // created apart: profiles of one creation time order by their random sync ids
        val bob = tv.addProfile("Bob")
        phone.data.apply(listOf(tv.snapshot()), pair = true, openProfileId = null)
        tick()
        tv.profiles.rename(ann, "Tmp")
        tv.profiles.rename(bob, "Ann")
        tv.profiles.rename(ann, "Bob")

        phone.data.apply(listOf(tv.snapshot()), pair = false, openProfileId = null)

        assertEquals(tv.snapshot(), phone.snapshot())
        assertEquals(listOf("Owner", "Bob", "Ann"), phone.snapshot().profiles.map { it.name })
    }

    @Test
    fun aProfilePastTheCapStaysOnTheDeviceThatHasIt() = scope.runTest {
        tv.owner()
        phone.owner()
        phone.data.apply(listOf(tv.snapshot()), pair = true, openProfileId = null)
        repeat(MAX_PROFILES - 1) { phone.addProfile("Phone $it") }
        tv.addProfile("TV only")

        phone.data.apply(listOf(tv.snapshot()), pair = false, openProfileId = null)

        val names = phone.snapshot().profiles.map { it.name }
        assertEquals(MAX_PROFILES, names.size)
        assertFalse("TV only" in names)
    }

    /** M8-D16: a profile removed on the TV stays on the phone while it is open there; the phone's
     *  copy doesn't bring it back on the TV. */
    @Test
    fun aRemovedProfileStaysWhileItIsOpenHere() = scope.runTest {
        val phoneMira = miraRemovedOnTheTv()

        phone.data.apply(listOf(tv.snapshot()), pair = false, openProfileId = phoneMira)

        assertEquals(listOf("Owner", "Mira"), phone.database.profileDao().all().map { it.name })
        tv.data.apply(listOf(phone.snapshot()), pair = false, openProfileId = null)
        assertEquals(listOf("Owner"), tv.database.profileDao().all().map { it.name })
    }

    @Test
    fun aRemovedProfileKeptWhileOpenGoesAtAnApplyAfterItClosed() = scope.runTest {
        val phoneMira = miraRemovedOnTheTv()
        phone.data.apply(listOf(tv.snapshot()), pair = false, openProfileId = phoneMira)

        phone.data.apply(listOf(tv.snapshot()), pair = false, openProfileId = null)

        assertEquals(listOf("Owner"), phone.database.profileDao().all().map { it.name })
    }

    /** Mira on both devices, then removed on the TV; returns the phone's Mira. */
    private suspend fun miraRemovedOnTheTv(): Long {
        tv.owner()
        val mira = tv.addProfile("Mira")
        phone.owner()
        phone.data.apply(listOf(tv.snapshot()), pair = true, openProfileId = null)
        tick()
        assertTrue(tv.profiles.remove(OwnerPass(), mira))
        return phone.database.profileDao().all().single { it.name == "Mira" }.id
    }

    @Test
    fun removalRecordsPastNinetyDaysArentKept() = scope.runTest {
        tv.owner()
        tv.session.select(tv.ownerId())
        tv.subscriptions.subscribe(channel(CHANNEL_A, "Channel A"))
        tv.subscriptions.subscribe(channel(CHANNEL_B, "Channel B"))
        tv.subscriptions.unsubscribe(CHANNEL_A)
        clock.nowMillis += 60.days.inWholeMilliseconds
        tv.subscriptions.unsubscribe(CHANNEL_B)
        clock.nowMillis += 31.days.inWholeMilliseconds
        phone.owner()

        phone.data.apply(listOf(tv.snapshot()), pair = true, openProfileId = null)
        tv.data.apply(listOf(phone.snapshot()), pair = false, openProfileId = null)

        assertEquals(listOf(CHANNEL_B), phone.snapshot().removals.map { it.itemKey })
        assertEquals(listOf(CHANNEL_B), tv.snapshot().removals.map { it.itemKey })
    }

    /** A device: its own database and stores, on the shared clock. */
    private inner class Device {
        val database: KeDatabase = Room.inMemoryDatabaseBuilder<KeDatabase>(RuntimeEnvironment.getApplication())
            .setDriver(AndroidSQLiteDriver())
            .setQueryCoroutineContext(dispatcher)
            .build()
        val session = ProfileSession()
        val profiles = ProfileRepository(database, clock)
        val subscriptions = SubscriptionRepository(database, session, clock)
        val watch = WatchRepository(database, session, clock)
        val playlists = PlaylistRepository(database, session, clock)
        val settings = ProfileSettingsRepository(database, session, clock)
        val forYou = ForYouRepository(database, session, clock)
        val filters = FilterRepository(database, clock)
        val data = SyncDatabase(database, clock)

        suspend fun owner(): Long = profiles.ensureOwner().id

        suspend fun ownerId(): Long = checkNotNull(database.profileDao().owner()).id

        suspend fun addProfile(name: String): Long = (profiles.create(OwnerPass(), name, ProfileTint.Rose) as ProfileEdit.Done).profileId

        suspend fun snapshot(): BackupBundle = data.snapshot()

        /** The owner and Mira, holding every synced record kind at distinct times. */
        suspend fun fill() {
            val owner = owner()
            session.select(owner)
            subscriptions.subscribe(channel(CHANNEL_A, "Channel A"))
            tick()
            subscriptions.subscribe(channel(CHANNEL_B, "Channel B"))
            tick()
            watch.recordOpened(card(V1), isShort = false)
            watch.recordPosition(PositionSave(owner, V1, positionMs = 60_000, durationMs = 600_000, ended = false))
            tick()
            watch.recordPosition(PositionSave(owner, V2, positionMs = 10_000, durationMs = 600_000, ended = false))
            tick()
            val mix = checkNotNull(playlists.create("Mix"))
            playlists.add(mix, card(V2))
            tick()
            playlists.add(mix, card(V1))
            tick()
            playlists.addToWatchLater(card(V3))
            tick()
            settings.update(owner) { it.copy(speedPercent = 150, captionsLanguage = "fr", chatPlacement = ChatPlacement.Side) }
            tick()
            forYou.hideVideo(V2)
            forYou.hideChannel(CHANNEL_B)
            filters.addOwn(owner, Keyword("prank"))
            tick()
            val mira = addProfile("Mira")
            session.select(mira)
            subscriptions.subscribe(channel(CHANNEL_A, "Channel A"))
            filters.updateRestrictions(OwnerPass(), mira) { it.copy(restrictedMode = RestrictedMode.Strict) }
            tick()
            session.select(owner)
        }
    }

    private fun tick() {
        clock.nowMillis += 1_000
    }

    private fun channel(url: String, name: String): ChannelItem =
        ChannelItem(name = name, url = url, avatarUrl = null, subscriberCount = -1, description = null, verified = false)

    private fun card(videoId: VideoId): VideoItem = VideoItem(
        id = videoId,
        title = "Title ${videoId.value}",
        uploaderName = "Channel A",
        uploaderUrl = CHANNEL_A,
        durationSeconds = 600,
        viewCount = 1_234,
        textualUploadDate = "3 days ago",
        uploadDate = Instant.fromEpochMilliseconds(T0 - 86_400_000L),
        thumbnailUrl = "https://img/${videoId.value}",
        isLive = false,
    )
}

private const val T0 = 1_700_000_000_000L
private const val CHANNEL_A = "https://www.youtube.com/channel/UCaaaaaaaaaaaaaaaaaaaaaa"
private const val CHANNEL_B = "https://www.youtube.com/channel/UCbbbbbbbbbbbbbbbbbbbbbb"
private const val CHANNEL_C = "https://www.youtube.com/channel/UCcccccccccccccccccccccc"
private val V1 = VideoId("v1aaaaaaaaa")
private val V2 = VideoId("v2bbbbbbbbb")
private val V3 = VideoId("v3ccccccccc")
