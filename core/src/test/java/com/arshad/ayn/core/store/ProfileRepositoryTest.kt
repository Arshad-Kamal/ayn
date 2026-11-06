package com.arshad.ayn.core.store

import androidx.room3.Room
import androidx.sqlite.SQLiteException
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.arshad.ayn.core.access.ChannelItem
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.lock.OwnerPass
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
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
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Profiles over a real in-memory Room 3 database: the owner, names, the cap, removal, the counts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProfileRepositoryTest {
    private val scope = TestScope()
    private val database: KeDatabase =
        Room.inMemoryDatabaseBuilder<KeDatabase>(RuntimeEnvironment.getApplication())
            .setDriver(AndroidSQLiteDriver())
            .setQueryCoroutineContext(StandardTestDispatcher(scope.testScheduler))
            .build()
    private val clock = SteppingClock()
    private val store = ProfileRepository(database, clock)

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun createOwnerMakesTheFirstProfileTheOwnerWithItsTrimmedNameAndTint() = scope.runTest {
        val edit = store.createOwner("  Arshad ", ProfileTint.Teal) as ProfileEdit.Done

        assertEquals(
            listOf(Profile(edit.profileId, "Arshad", ProfileTint.Teal, isOwner = true, lock = null)),
            store.profiles().first(),
        )
    }

    @Test
    fun createOwnerLeavesAnExistingOwnerAsItIs() = scope.runTest {
        val first = store.createOwner("Arshad", ProfileTint.Amber) as ProfileEdit.Done

        val again = store.createOwner("Mira", ProfileTint.Rose)

        assertEquals(first, again)
        assertEquals(listOf("Arshad"), store.profiles().first().map { it.name })
    }

    @Test
    fun createOwnerRefusesABlankOrOverlongNameAndCreatesNothing() = scope.runTest {
        assertEquals(ProfileEdit.NameInvalid, store.createOwner("  ", ProfileTint.Amber))
        assertEquals(ProfileEdit.NameInvalid, store.createOwner("x".repeat(MAX_PROFILE_NAME + 1), ProfileTint.Amber))

        assertEquals(emptyList<Profile>(), store.profiles().first())
    }

    @Test
    fun profilesListTheOwnerFirstThenInTheOrderAdded() = scope.runTest {
        store.create(OwnerPass(), "Yuri", ProfileTint.Teal)
        store.ensureOwner()
        store.create(OwnerPass(), "Mira", ProfileTint.Rose)

        assertEquals(listOf("Owner", "Yuri", "Mira"), store.profiles().first().map { it.name })
    }

    @Test
    fun aNameIsTrimmedAndMustBeOneToTwentyCharacters() = scope.runTest {
        assertEquals(ProfileEdit.NameInvalid, store.create(OwnerPass(), "   ", ProfileTint.Teal))
        assertEquals(ProfileEdit.NameInvalid, store.create(OwnerPass(), "x".repeat(21), ProfileTint.Teal))
        assertEquals(ProfileEdit.NameInvalid, store.rename(store.ensureOwner().id, " "))
        assertEquals(listOf("Owner"), store.profiles().first().map { it.name })

        val created = store.create(OwnerPass(), "  ${"y".repeat(20)}  ", ProfileTint.Teal)

        assertTrue(created is ProfileEdit.Done)
        assertEquals("y".repeat(20), store.profiles().first().last().name)
    }

    @Test
    fun aNameAnotherProfileHasIsTakenIgnoringCase() = scope.runTest {
        store.ensureOwner()
        val yuri = store.create(OwnerPass(), "Yuri", ProfileTint.Teal) as ProfileEdit.Done

        assertEquals(ProfileEdit.NameTaken, store.create(OwnerPass(), "yuri", ProfileTint.Rose))
        assertEquals(ProfileEdit.NameTaken, store.rename(yuri.profileId, "OWNER"))
        assertEquals(yuri, store.rename(yuri.profileId, "YURI"))
        assertEquals(listOf("Owner", "YURI"), store.profiles().first().map { it.name })
    }

    /** KP-D14: a kids profile needs the owner to have a lock first. */
    @Test
    fun aKidsProfileIsRefusedUntilTheOwnerHasALock() = scope.runTest {
        val owner = store.ensureOwner()

        assertEquals(ProfileEdit.OwnerLockNeeded, store.create(OwnerPass(), "Zainab", ProfileTint.Violet, kids = true))
        assertEquals(listOf("Owner"), store.profiles().first().map { it.name })

        database.profileDao().setLock(owner.id, kind = "sequence", hash = "h", salt = "s", iterations = 1)
        val kid = store.create(OwnerPass(), "Zainab", ProfileTint.Violet, kids = true) as ProfileEdit.Done

        assertEquals(true, store.profiles().first().single { it.id == kid.profileId }.isKids)
    }

    @Test
    fun aStandardProfileNeedsNoOwnerLockAndIsNotKids() = scope.runTest {
        store.ensureOwner()

        val yuri = store.create(OwnerPass(), "Yuri", ProfileTint.Teal) as ProfileEdit.Done

        assertEquals(false, store.profiles().first().single { it.id == yuri.profileId }.isKids)
    }

    @Test
    fun theSixthProfileIsRefused() = scope.runTest {
        store.ensureOwner()
        listOf("B", "C", "D", "E").forEach { store.create(OwnerPass(), it, ProfileTint.Blue) }

        assertEquals(ProfileEdit.Full, store.create(OwnerPass(), "F", ProfileTint.Blue))
        assertEquals(MAX_PROFILES, store.profiles().first().size)
    }

    @Test
    fun renameAndTintChangesAreKeptAndStamped() = scope.runTest {
        val owner = store.ensureOwner()
        val created = database.profileDao().owner()!!.updatedAtMillis

        store.rename(owner.id, "Arshad")
        val renamed = database.profileDao().owner()!!.updatedAtMillis
        store.setTint(owner.id, ProfileTint.Violet)
        val tinted = database.profileDao().owner()!!

        assertEquals(Profile(owner.id, "Arshad", ProfileTint.Violet, isOwner = true, lock = null), store.profiles().first().single())
        assertTrue(created < renamed && renamed < tinted.updatedAtMillis)
        assertEquals(created, tinted.createdAtMillis)
    }

    @Test
    fun aTintKeyThisBuildDoesntKnowReadsAsAmber() {
        assertEquals(ProfileTint.Amber, ProfileTint.of("chartreuse"))
        assertEquals(ProfileTint.Teal, ProfileTint.of("teal"))
    }

    @Test
    fun removingAProfileRemovesItsSubscriptionsAndNoOneElses() = scope.runTest {
        val owner = store.ensureOwner()
        val yuri = (store.create(OwnerPass(), "Yuri", ProfileTint.Teal) as ProfileEdit.Done).profileId
        val session = ProfileSession()
        val subscriptions = SubscriptionRepository(database, session, clock)
        session.select(owner.id)
        subscriptions.subscribe(CHANNEL)
        session.select(yuri)
        subscriptions.subscribe(CHANNEL)

        assertTrue(store.remove(OwnerPass(), yuri))

        assertEquals(listOf(owner), store.profiles().first())
        assertEquals(emptyList<SubscriptionEntity>(), database.subscriptionDao().subscriptions(yuri).first())
        assertEquals(1, database.subscriptionDao().subscriptions(owner.id).first().size)
    }

    @Test
    fun contentsCountEachProfilesOwnRowsWatchLaterApart() = scope.runTest {
        val owner = store.ensureOwner()
        val yuri = (store.create(OwnerPass(), "Yuri", ProfileTint.Teal) as ProfileEdit.Done).profileId
        val session = ProfileSession()
        val subscriptions = SubscriptionRepository(database, session, clock)
        val watch = WatchRepository(database, session, clock)
        val playlists = PlaylistRepository(database, session, clock)
        session.select(owner.id)
        subscriptions.subscribe(CHANNEL)
        subscriptions.subscribe(CHANNEL.copy(url = "https://www.youtube.com/channel/UCbbbbbbbbbbbbbbbbbbbbbb"))
        watch.recordOpened(card("aaaaaaaaaaa"), isShort = false)
        watch.recordOpened(card("bbbbbbbbbbb"), isShort = false)
        watch.recordOpened(card("ccccccccccc"), isShort = false)
        playlists.create("Cooking")
        playlists.addToWatchLater(card("aaaaaaaaaaa"))
        session.select(yuri)
        playlists.create("Games")
        playlists.create("Music")

        assertEquals(
            mapOf(
                owner.id to ProfileContents(subscriptionCount = 2, playlistCount = 1, historyCount = 3),
                yuri to ProfileContents(subscriptionCount = 0, playlistCount = 2, historyCount = 0),
            ),
            store.contents().first(),
        )
    }

    @Test
    fun contentsFollowEachCountedTable() = scope.runTest {
        val owner = store.ensureOwner()
        val session = ProfileSession()
        session.select(owner.id)
        val seen = mutableListOf<Map<Long, ProfileContents>>()
        val reading = launch { store.contents().take(4).toList(seen) }
        advanceUntilIdle()

        SubscriptionRepository(database, session, clock).subscribe(CHANNEL)
        advanceUntilIdle()
        PlaylistRepository(database, session, clock).create("Cooking")
        advanceUntilIdle()
        WatchRepository(database, session, clock).recordOpened(card("aaaaaaaaaaa"), isShort = false)
        advanceUntilIdle()
        reading.cancel()

        assertEquals(
            listOf(
                ProfileContents(subscriptionCount = 0, playlistCount = 0, historyCount = 0),
                ProfileContents(subscriptionCount = 1, playlistCount = 0, historyCount = 0),
                ProfileContents(subscriptionCount = 1, playlistCount = 1, historyCount = 0),
                ProfileContents(subscriptionCount = 1, playlistCount = 1, historyCount = 1),
            ),
            seen.map { it.getValue(owner.id) },
        )
    }

    @Test
    fun theOwnerAndUnknownIdsCantBeRemoved() = scope.runTest {
        val owner = store.ensureOwner()

        assertFalse(store.remove(OwnerPass(), owner.id))
        assertFalse(store.remove(OwnerPass(), owner.id + 100))
        assertEquals(listOf(owner), store.profiles().first())
    }

    @Test
    fun aSecondOwnerFailsTheWrite() = scope.runTest {
        store.ensureOwner()

        try {
            database.profileDao().insert(
                ProfileEntity(name = "Other", tint = "teal", ownerMark = 1, createdAtMillis = 0, updatedAtMillis = 0),
            )
            fail("a second owner was stored")
        } catch (expected: SQLiteException) {
            assertTrue(expected.message, expected.message.orEmpty().contains("profiles.ownerMark"))
            assertEquals(1, store.profiles().first().count { it.isOwner })
        }
    }
}

private val CHANNEL = ChannelItem(
    name = "A",
    url = "https://www.youtube.com/channel/UCaaaaaaaaaaaaaaaaaaaaaa",
    avatarUrl = null,
    subscriberCount = -1,
    description = null,
    verified = false,
)

private fun card(id: String): VideoItem = VideoItem(
    id = VideoId(id),
    title = "Title",
    uploaderName = "Channel",
    uploaderUrl = CHANNEL.url,
    durationSeconds = 600,
    viewCount = 1,
    textualUploadDate = null,
    uploadDate = null,
    thumbnailUrl = null,
    isLive = false,
)

/** Each reading is a second after the last, so a dropped stamp shows. */
private class SteppingClock : Clock {
    private var nowMillis = 1_700_000_000_000L

    override fun now(): Instant = Instant.fromEpochMilliseconds(nowMillis).also { nowMillis += 1_000 }
}
