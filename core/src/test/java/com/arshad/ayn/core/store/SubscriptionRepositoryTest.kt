package com.arshad.ayn.core.store

import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.arshad.ayn.core.access.ChannelItem
import com.arshad.ayn.core.lock.OwnerPass
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
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Repository over a real in-memory Room 3 database on the platform SQLite (the production
 * driver): field mapping, upsert and delete, the DAO's SQL ordering, Flow reactivity, and
 * scoping to the session's profile. Room's queries run on the test scheduler and the clock is
 * injected (TEST-DETERM-01, KT-CONC-09).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SubscriptionRepositoryTest {
    private val scope = TestScope()
    private val database: KeDatabase =
        Room.inMemoryDatabaseBuilder<KeDatabase>(RuntimeEnvironment.getApplication())
            .setDriver(AndroidSQLiteDriver())
            .setQueryCoroutineContext(StandardTestDispatcher(scope.testScheduler))
            .build()
    private val dao = database.subscriptionDao()
    private val clock = MutableClock(T0)
    private val profiles = ProfileRepository(database, clock)
    private val session = ProfileSession()
    private val store: SubscriptionStore = SubscriptionRepository(database, session, clock)
    private var ownerId = 0L

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun subscribeStoresChannelFieldsWithClockStamps() = ownerTest {
        store.subscribe(channel(url = CHANNEL_A, name = "Channel A", avatarUrl = "https://img/a"))

        val stored = store.subscription(CHANNEL_A).first()
        assertEquals(
            Subscription(
                channelUrl = CHANNEL_A,
                name = "Channel A",
                avatarUrl = "https://img/a",
                subscribedAtMillis = T0,
                updatedAtMillis = T0,
            ),
            stored,
        )
    }

    @Test
    fun subscriptionFieldsMapWithoutTransposition() = ownerTest {
        // The public API writes both stamps from one `now`, so a swapped mapping is invisible
        // through subscribe(); seed the table with distinct values to pin the entity -> domain map.
        dao.upsert(SubscriptionEntity(ownerId, CHANNEL_A, "A", null, subscribedAtMillis = T0, updatedAtMillis = T0 + 999))

        val stored = store.subscription(CHANNEL_A).first()
        assertEquals(T0, stored?.subscribedAtMillis)
        assertEquals(T0 + 999, stored?.updatedAtMillis)
    }

    @Test
    fun unsubscribeRemovesOnlyThatChannel() = ownerTest {
        store.subscribe(channel(url = CHANNEL_A, name = "A"))
        store.subscribe(channel(url = CHANNEL_B, name = "B"))

        store.unsubscribe(CHANNEL_A)

        assertNull(store.subscription(CHANNEL_A).first())
        assertEquals(listOf(CHANNEL_B), store.subscriptions().first().map { it.channelUrl })
    }

    @Test
    fun aChannelReachedThroughATabUrlIsStoredOnceUnderItsChannelUrl() = ownerTest {
        store.subscribe(channel(url = "$CHANNEL_A/videos", name = "A"))
        store.subscribe(channel(url = CHANNEL_A, name = "A"))

        assertEquals(listOf(CHANNEL_A), store.subscriptions().first().map { it.channelUrl })
        assertEquals(CHANNEL_A, store.subscription("http://m.youtube.com/channel/UCaaaaaaaaaaaaaaaaaaaaaa/streams").first()?.channelUrl)
    }

    @Test
    fun unsubscribingThroughAnotherUrlFormRemovesTheChannel() = ownerTest {
        store.subscribe(channel(url = CHANNEL_A, name = "A"))

        store.unsubscribe("$CHANNEL_A/videos")

        assertEquals(emptyList<Subscription>(), store.subscriptions().first())
    }

    @Test
    fun unsubscribingAnUnknownChannelIsANoOp() = ownerTest {
        store.unsubscribe(CHANNEL_A)

        assertEquals(emptyList<Subscription>(), store.subscriptions().first())
    }

    @Test
    fun subscriptionsListNewestSubscribeFirst() = ownerTest {
        store.subscribe(channel(url = CHANNEL_A, name = "A"))
        clock.nowMillis = T0 + 5_000
        store.subscribe(channel(url = CHANNEL_B, name = "B"))

        assertEquals(listOf(CHANNEL_B, CHANNEL_A), store.subscriptions().first().map { it.channelUrl })
    }

    @Test
    fun sameInstantSubscriptionsListInChannelUrlOrder() = ownerTest {
        store.subscribe(channel(url = CHANNEL_B, name = "B"))
        store.subscribe(channel(url = CHANNEL_A, name = "A"))

        assertEquals(listOf(CHANNEL_A, CHANNEL_B), store.subscriptions().first().map { it.channelUrl })
    }

    @Test
    fun subscriptionsPushRowsToActiveCollectors() = ownerTest {
        val sizes = mutableListOf<Int>()
        launch { store.subscriptions().take(2).map { it.size }.toList(sizes) }
        advanceUntilIdle()

        store.subscribe(channel(url = CHANNEL_A, name = "A"))
        advanceUntilIdle()

        assertEquals(listOf(0, 1), sizes)
    }

    @Test
    fun aChannelsSubscriptionStatePushesSubscribeAndUnsubscribe() = ownerTest {
        val subscribed = mutableListOf<Boolean>()
        launch { store.subscription(CHANNEL_A).take(3).map { it != null }.toList(subscribed) }
        advanceUntilIdle()

        store.subscribe(channel(url = CHANNEL_A, name = "A"))
        advanceUntilIdle()
        store.unsubscribe(CHANNEL_A)
        advanceUntilIdle()

        assertEquals(listOf(false, true, false), subscribed)
    }

    @Test
    fun subscriptionsEmitEmptyBeforeAnyWriteAndTheRowAfter() = ownerTest {
        assertEquals(emptyList<Subscription>(), store.subscriptions().first())

        store.subscribe(channel(url = CHANNEL_A, name = "A"))

        assertEquals(listOf(CHANNEL_A), store.subscriptions().first().map { it.channelUrl })
    }

    @Test
    fun reSubscribingRefreshesFieldsAndStamps() = ownerTest {
        store.subscribe(channel(url = CHANNEL_A, name = "Old name", avatarUrl = null))
        clock.nowMillis = T0 + 5_000

        store.subscribe(channel(url = CHANNEL_A, name = "New name", avatarUrl = "https://img/new"))

        val stored = store.subscription(CHANNEL_A).first()
        assertEquals("New name", stored?.name)
        assertEquals("https://img/new", stored?.avatarUrl)
        assertEquals(T0 + 5_000, stored?.subscribedAtMillis)
        assertEquals(T0 + 5_000, stored?.updatedAtMillis)
        assertEquals(1, store.subscriptions().first().size)
    }

    @Test
    fun twoProfilesKeepTheirOwnSubscriptionsOfTheSameChannel() = ownerTest {
        store.subscribe(channel(url = CHANNEL_A, name = "A"))
        val other = profiles.newProfile("Mira")
        session.select(other)
        store.subscribe(channel(url = CHANNEL_A, name = "A"))
        store.subscribe(channel(url = CHANNEL_B, name = "B"))

        store.unsubscribe(CHANNEL_A)

        assertEquals(listOf(CHANNEL_B), store.subscriptions().first().map { it.channelUrl })
        session.select(ownerId)
        assertEquals(listOf(CHANNEL_A), store.subscriptions().first().map { it.channelUrl })
    }

    @Test
    fun aProfileSwitchReEmitsTheNewProfilesSubscriptions() = ownerTest {
        store.subscribe(channel(url = CHANNEL_A, name = "A"))
        val other = profiles.newProfile("Mira")
        val seen = mutableListOf<List<String>>()
        launch { store.subscriptions().take(2).map { list -> list.map { it.channelUrl } }.toList(seen) }
        advanceUntilIdle()

        session.select(other)
        advanceUntilIdle()

        assertEquals(listOf(listOf(CHANNEL_A), emptyList()), seen)
    }

    @Test
    fun aChannelsStateIsTheSelectedProfilesAndFollowsASwitch() = ownerTest {
        store.subscribe(channel(url = CHANNEL_A, name = "A"))
        val other = profiles.newProfile("Mira")
        val subscribed = mutableListOf<Boolean>()
        launch { store.subscription(CHANNEL_A).take(2).map { it != null }.toList(subscribed) }
        advanceUntilIdle()

        session.select(other)
        advanceUntilIdle()

        assertEquals(listOf(true, false), subscribed)
        assertNull(store.subscription(CHANNEL_A).first())
    }

    /** KP-D10: a kids profile's subscriptions are its approved channels, which only the owner
     *  writes, so a channel page reached through an approved video can't approve its channel. */
    @Test
    fun aKidsProfilesOwnSubscribeAndUnsubscribeChangeNothing() = ownerTest {
        val kid = kidProfile()
        database.subscriptionDao().upsert(SubscriptionEntity(kid, CHANNEL_A, "A", null, T0, T0))
        session.select(kid)

        store.subscribe(channel(url = CHANNEL_B, name = "B"))
        store.unsubscribe(CHANNEL_A)

        assertEquals(listOf(CHANNEL_A), store.subscriptions().first().map { it.channelUrl })
        assertNull(store.subscription(CHANNEL_B).first())
    }

    @Test
    fun readsAndWritesWaitForASelectedProfile() = scope.runTest {
        val seen = mutableListOf<Int>()
        launch { store.subscriptions().take(1).map { it.size }.toList(seen) }
        launch { store.subscribe(channel(url = CHANNEL_A, name = "A")) }
        advanceUntilIdle()
        assertEquals(emptyList<Int>(), seen)

        session.select(profiles.ensureOwner().id)
        advanceUntilIdle()

        assertEquals(listOf(CHANNEL_A), store.subscriptions().first().map { it.channelUrl })
        assertEquals(1, seen.size)
    }

    /** Runs [body] as the owner, as the app does until the launch gate picks a profile. */
    private fun ownerTest(body: suspend TestScope.() -> Unit) = scope.runTest {
        ownerId = profiles.ensureOwner().id
        session.select(ownerId)
        body()
    }

    /** A kids profile needs the owner's lock first (KP-D14); the hash stands in for one. */
    private suspend fun kidProfile(): Long {
        database.profileDao().setLock(ownerId, kind = "sequence", hash = "h", salt = "s", iterations = 1)
        return (profiles.create(OwnerPass(), "Zainab", ProfileTint.Violet, kids = true) as ProfileEdit.Done).profileId
    }

    private suspend fun ProfileRepository.newProfile(name: String): Long =
        (create(OwnerPass(), name, ProfileTint.Rose) as ProfileEdit.Done).profileId

    private fun channel(url: String, name: String, avatarUrl: String? = null): ChannelItem =
        ChannelItem(
            name = name,
            url = url,
            avatarUrl = avatarUrl,
            subscriberCount = -1,
            description = null,
            verified = false,
        )
}

private const val T0 = 1_700_000_000_000L
private const val CHANNEL_A = "https://www.youtube.com/channel/UCaaaaaaaaaaaaaaaaaaaaaa"
private const val CHANNEL_B = "https://www.youtube.com/channel/UCbbbbbbbbbbbbbbbbbbbbbb"
