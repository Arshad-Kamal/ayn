package com.arshad.ayn.core.lock

import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.arshad.ayn.core.store.KeDatabase
import com.arshad.ayn.core.store.MutableClock
import com.arshad.ayn.core.store.ProfileEdit
import com.arshad.ayn.core.store.ProfileRepository
import com.arshad.ayn.core.store.ProfileSession
import com.arshad.ayn.core.store.ProfileTint
import com.arshad.ayn.core.store.ensureOwner
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Locks over a real in-memory Room 3 database (§ B3p, M5-D15), with an injected clock. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LockRepositoryTest {
    private val scope = TestScope()
    private val dispatcher = StandardTestDispatcher(scope.testScheduler)
    private val database: KeDatabase =
        Room.inMemoryDatabaseBuilder<KeDatabase>(RuntimeEnvironment.getApplication())
            .setDriver(AndroidSQLiteDriver())
            .setQueryCoroutineContext(dispatcher)
            .build()
    private val clock = MutableClock(T0)
    private val profiles = ProfileRepository(database, clock)
    private val session = ProfileSession()
    private val locks = lockRepository()

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun aProfileWithNoLockOpensWithoutOne() = scope.runTest {
        val owner = profiles.ensureOwner().id

        assertEquals(LockStatus.None, locks.status(owner))
        assertNotNull(locks.passWithoutLock(owner))
        assertEquals(owner, (locks.unlock(owner, PIN) as UnlockResult.Opened).pass.profileId)
    }

    @Test
    fun aSetLockOpensWithItsSecretAndShowsOnTheProfile() = scope.runTest {
        val owner = lockedOwner(PIN)

        assertEquals(LockStatus.Locked(LockKind.Pin, wait = null), locks.status(owner))
        assertEquals(LockKind.Pin, profiles.profiles().first().single().lock)
        assertNull(locks.passWithoutLock(owner))
        assertEquals(owner, (locks.unlock(owner, PIN) as UnlockResult.Opened).pass.profileId)
    }

    @Test
    fun aWrongSecretIsRefusedAndSaysHowManyTriesAreLeft() = scope.runTest {
        val owner = lockedOwner(PIN)

        assertEquals(UnlockResult.Wrong(triesBeforeWait = 4, wait = null), locks.unlock(owner, WRONG_PIN))
        assertEquals(UnlockResult.Wrong(triesBeforeWait = 3, wait = null), locks.unlock(owner, WRONG_PIN))
    }

    @Test
    fun aSecretOfTheOtherKindIsWrong() = scope.runTest {
        val owner = lockedOwner(SEQUENCE)

        assertEquals(UnlockResult.Wrong(triesBeforeWait = 4, wait = null), locks.unlock(owner, PIN))
    }

    @Test
    fun theFifthWrongTryStartsA30SecondWait() = scope.runTest {
        val owner = lockedOwner(PIN)
        repeat(4) { locks.unlock(owner, WRONG_PIN) }

        assertEquals(UnlockResult.Wrong(triesBeforeWait = 0, wait = 30.seconds), locks.unlock(owner, WRONG_PIN))
        assertEquals(LockStatus.Locked(LockKind.Pin, wait = 30.seconds), locks.status(owner))
    }

    @Test
    fun theRightSecretIsRefusedUncheckedDuringAWait() = scope.runTest {
        val owner = lockedOwner(PIN)
        repeat(5) { locks.unlock(owner, WRONG_PIN) }
        clock.nowMillis += 10_000

        assertEquals(UnlockResult.Waiting(remaining = 20.seconds), locks.unlock(owner, PIN))
    }

    @Test
    fun triesDuringAWaitDontCountAndTheNextWrongOneDoublesIt() = scope.runTest {
        val owner = lockedOwner(PIN)
        repeat(5) { locks.unlock(owner, WRONG_PIN) }
        repeat(3) { locks.unlock(owner, WRONG_PIN) }
        clock.nowMillis += 30_000

        assertEquals(UnlockResult.Wrong(triesBeforeWait = 0, wait = 1.minutes), locks.unlock(owner, WRONG_PIN))
    }

    @Test
    fun theRightSecretOpensOnceTheWaitIsOver() = scope.runTest {
        val owner = lockedOwner(PIN)
        repeat(5) { locks.unlock(owner, WRONG_PIN) }
        clock.nowMillis += 30_000

        assertTrue(locks.unlock(owner, PIN) is UnlockResult.Opened)
    }

    @Test
    fun triesAndTheWaitSurviveARestart() = scope.runTest {
        val owner = lockedOwner(PIN)
        repeat(5) { locks.unlock(owner, WRONG_PIN) }
        clock.nowMillis += 10_000

        assertEquals(LockStatus.Locked(LockKind.Pin, wait = 20.seconds), lockRepository().status(owner))
    }

    @Test
    fun aRightSecretStartsTheTriesOver() = scope.runTest {
        val owner = lockedOwner(PIN)
        repeat(4) { locks.unlock(owner, WRONG_PIN) }
        locks.unlock(owner, PIN)

        assertEquals(UnlockResult.Wrong(triesBeforeWait = 4, wait = null), locks.unlock(owner, WRONG_PIN))
    }

    @Test
    fun aClockSetBackCantLengthenTheWait() = scope.runTest {
        val owner = lockedOwner(PIN)
        repeat(5) { locks.unlock(owner, WRONG_PIN) }
        clock.nowMillis = T0 - 3_600_000

        assertEquals(LockStatus.Locked(LockKind.Pin, wait = 30.seconds), locks.status(owner))
        clock.nowMillis += 30_000
        assertTrue(locks.unlock(owner, PIN) is UnlockResult.Opened)
    }

    @Test
    fun aClockSetForwardEndsTheWait() = scope.runTest {
        val owner = lockedOwner(PIN)
        repeat(5) { locks.unlock(owner, WRONG_PIN) }
        clock.nowMillis = T0 + 86_400_000

        assertTrue(locks.unlock(owner, PIN) is UnlockResult.Opened)
    }

    @Test
    fun aWaitThatEndedDoesntComeBackWhenTheClockIsSetBack() = scope.runTest {
        val owner = lockedOwner(PIN)
        repeat(5) { locks.unlock(owner, WRONG_PIN) }
        clock.nowMillis = T0 + 31_000
        locks.status(owner)
        clock.nowMillis = T0

        assertEquals(LockStatus.Locked(LockKind.Pin, wait = null), locks.status(owner))
    }

    @Test
    fun aTryCutShortWhileItsSecretIsCheckedStillCounts() = scope.runTest {
        val owner = lockedOwner(PIN)
        repeat(4) { locks.unlock(owner, WRONG_PIN) }
        val held = HeldDispatcher()
        val cutShort = launch { LockRepository(database, session, LockHasher(held, iterations = FAST), clock).unlock(owner, PIN) }
        advanceUntilIdle()
        cutShort.cancel()
        held.release()
        advanceUntilIdle()

        assertEquals(LockStatus.Locked(LockKind.Pin, wait = 30.seconds), locks.status(owner))
    }

    @Test
    fun twoTriesAtOnceBothCount() = scope.runTest {
        val owner = lockedOwner(PIN)
        val first = async { locks.unlock(owner, WRONG_PIN) }
        val second = async { locks.unlock(owner, WRONG_PIN) }
        first.await()
        second.await()
        repeat(2) { locks.unlock(owner, WRONG_PIN) }

        assertEquals(UnlockResult.Wrong(triesBeforeWait = 0, wait = 30.seconds), locks.unlock(owner, WRONG_PIN))
    }

    @Test
    fun aLockChangedWithAPassFromTheOldOneOpensOnlyWithTheNewOne() = scope.runTest {
        val owner = lockedOwner(PIN)
        val pass = (locks.unlock(owner, PIN) as UnlockResult.Opened).pass

        locks.setLock(pass, SEQUENCE)

        assertTrue(locks.unlock(owner, PIN) is UnlockResult.Wrong)
        assertTrue(locks.unlock(owner, SEQUENCE) is UnlockResult.Opened)
    }

    @Test
    fun aRemovedLockLeavesTheProfileOpen() = scope.runTest {
        val owner = lockedOwner(PIN)
        val pass = (locks.unlock(owner, PIN) as UnlockResult.Opened).pass

        locks.setLock(pass, secret = null)

        assertEquals(LockStatus.None, locks.status(owner))
        assertNull(profiles.profiles().first().single().lock)
    }

    @Test
    fun theOwnerPassIsFreeWithNoOwnerOrAnOwnerWithoutALock() = scope.runTest {
        assertNotNull(locks.ownerPassWithoutLock())
        profiles.ensureOwner()

        assertNotNull(locks.ownerPassWithoutLock())
    }

    @Test
    fun theOwnersOpenProfileGivesTheOwnerPassWithoutAskingForItsLock() = scope.runTest {
        val owner = lockedOwner(PIN)
        session.select(owner)

        assertNotNull(locks.ownerPassInOwnSession())
    }

    @Test
    fun anotherOpenProfileOrNoneGivesNoOwnerPass() = scope.runTest {
        lockedOwner(PIN)
        assertNull(locks.ownerPassInOwnSession())
        val yuri = (profiles.create(OwnerPass(), "Yuri", ProfileTint.Teal) as ProfileEdit.Done).profileId
        session.select(yuri)

        assertNull(locks.ownerPassInOwnSession())
    }

    @Test
    fun theOwnerGateOpensWithAnySecretBeforeThereIsAnOwner() = scope.runTest {
        assertTrue(locks.unlockOwner(PIN) is UnlockResult.Opened)
    }

    @Test
    fun anotherProfileStillOpensWhileTheOwnerWaits() = scope.runTest {
        val owner = lockedOwner(PIN)
        val yuri = (profiles.create(OwnerPass(), "Yuri", ProfileTint.Teal) as ProfileEdit.Done).profileId
        locks.setLock(checkNotNull(locks.passWithoutLock(yuri)), SEQUENCE)
        repeat(5) { locks.unlock(owner, WRONG_PIN) }

        assertEquals(yuri, (locks.unlock(yuri, SEQUENCE) as UnlockResult.Opened).pass.profileId)
        assertEquals(UnlockResult.Wrong(triesBeforeWait = 4, wait = null), locks.unlock(yuri, WRONG_SEQUENCE))
    }

    @Test
    fun aLockedOwnerGivesItsPassOnlyForItsLock() = scope.runTest {
        lockedOwner(PIN)

        assertNull(locks.ownerPassWithoutLock())
        assertTrue(locks.unlockOwner(WRONG_PIN) is UnlockResult.Wrong)
        assertTrue(locks.unlockOwner(PIN) is UnlockResult.Opened)
    }

    @Test
    fun ownerGatePromptsCountOnTheOwnersTries() = scope.runTest {
        val owner = lockedOwner(PIN)
        repeat(4) { locks.unlockOwner(WRONG_PIN) }

        assertEquals(UnlockResult.Wrong(triesBeforeWait = 0, wait = 30.seconds), locks.unlock(owner, WRONG_PIN))
        assertEquals(UnlockResult.Waiting(remaining = 30.seconds), locks.unlockOwner(PIN))
    }

    @Test
    fun anOwnerResetRemovesAnotherProfilesLockAndItsWait() = scope.runTest {
        lockedOwner(PIN)
        val yuri = (profiles.create(OwnerPass(), "Yuri", ProfileTint.Teal) as ProfileEdit.Done).profileId
        locks.setLock(checkNotNull(locks.passWithoutLock(yuri)), SEQUENCE)
        repeat(5) { locks.unlock(yuri, WRONG_SEQUENCE) }

        locks.resetLock((locks.unlockOwner(PIN) as UnlockResult.Opened).pass, yuri)

        assertEquals(LockStatus.None, locks.status(yuri))
        locks.setLock(checkNotNull(locks.passWithoutLock(yuri)), SEQUENCE)
        assertEquals(LockStatus.Locked(LockKind.Sequence, wait = null), locks.status(yuri))
        assertEquals(UnlockResult.Wrong(triesBeforeWait = 4, wait = null), locks.unlock(yuri, WRONG_SEQUENCE))
    }

    /** KP-D14: the owner's lock stays while a kids profile exists; changing it is still fine. */
    @Test
    fun theOwnersLockCantBeRemovedOrResetWhileAKidsProfileExists() = scope.runTest {
        val owner = lockedOwner(PIN)
        val kid = (profiles.create(OwnerPass(), "Zainab", ProfileTint.Violet, kids = true) as ProfileEdit.Done).profileId

        assertFalse(locks.setLock((locks.unlock(owner, PIN) as UnlockResult.Opened).pass, secret = null))
        locks.resetLock(OwnerPass(), owner)
        assertEquals(LockStatus.Locked(LockKind.Pin, wait = null), locks.status(owner))

        assertTrue(locks.setLock((locks.unlock(owner, PIN) as UnlockResult.Opened).pass, SEQUENCE))
        assertTrue(locks.unlock(owner, SEQUENCE) is UnlockResult.Opened)

        profiles.remove(OwnerPass(), kid)
        assertTrue(locks.setLock((locks.unlock(owner, SEQUENCE) as UnlockResult.Opened).pass, secret = null))
        assertEquals(LockStatus.None, locks.status(owner))
    }

    /** KP-D14: a kids profile has no lock of its own. */
    @Test
    fun aKidsProfileCantSetALock() = scope.runTest {
        lockedOwner(PIN)
        val kid = (profiles.create(OwnerPass(), "Zainab", ProfileTint.Violet, kids = true) as ProfileEdit.Done).profileId

        assertFalse(locks.setLock(checkNotNull(locks.passWithoutLock(kid)), SEQUENCE))

        assertEquals(LockStatus.None, locks.status(kid))
    }

    /** KP-D9: a kid trying the owner's lock waits on its own tries; the owner's stay untouched. */
    @Test
    fun ownerPromptsFromAKidsProfileCountOnItsOwnTries() = scope.runTest {
        val owner = lockedOwner(PIN)
        val kid = (profiles.create(OwnerPass(), "Zainab", ProfileTint.Violet, kids = true) as ProfileEdit.Done).profileId

        repeat(4) { locks.unlockOwnerFromKids(kid, WRONG_PIN) }
        assertEquals(UnlockResult.Wrong(triesBeforeWait = 0, wait = 30.seconds), locks.unlockOwnerFromKids(kid, WRONG_PIN))

        assertEquals(LockStatus.Locked(LockKind.Pin, wait = 30.seconds), locks.ownerStatusFromKids(kid))
        assertEquals(UnlockResult.Waiting(remaining = 30.seconds), locks.unlockOwnerFromKids(kid, PIN))
        assertEquals(LockStatus.Locked(LockKind.Pin, wait = null), locks.status(owner))
        assertTrue(locks.unlockOwner(PIN) is UnlockResult.Opened)
    }

    @Test
    fun theOwnersLockFromAKidsProfileOpensAndStartsItsTriesOver() = scope.runTest {
        lockedOwner(PIN)
        val kid = (profiles.create(OwnerPass(), "Zainab", ProfileTint.Violet, kids = true) as ProfileEdit.Done).profileId
        locks.unlockOwnerFromKids(kid, WRONG_PIN)

        assertTrue(locks.unlockOwnerFromKids(kid, PIN) is UnlockResult.Opened)
        assertEquals(UnlockResult.Wrong(triesBeforeWait = 4, wait = null), locks.unlockOwnerFromKids(kid, WRONG_PIN))
    }

    @Test
    fun onlyAKidsProfileCountsOwnerPromptsOnItsOwnTries() = scope.runTest {
        lockedOwner(PIN)
        val yuri = (profiles.create(OwnerPass(), "Yuri", ProfileTint.Teal) as ProfileEdit.Done).profileId

        assertEquals(UnlockResult.Wrong(triesBeforeWait = 0, wait = null), locks.unlockOwnerFromKids(yuri, PIN))
        assertEquals(UnlockResult.Wrong(triesBeforeWait = 0, wait = null), locks.unlockOwnerFromKids(UNKNOWN_ID, PIN))
    }

    @Test
    fun anUnknownProfileNeverOpens() = scope.runTest {
        assertEquals(UnlockResult.Wrong(triesBeforeWait = 0, wait = null), locks.unlock(UNKNOWN_ID, PIN))
        assertEquals(LockStatus.None, locks.status(UNKNOWN_ID))
        assertNull(locks.passWithoutLock(UNKNOWN_ID))
    }

    /** Holds what it is given until [release]: a compare on it waits mid-way. */
    private class HeldDispatcher : CoroutineDispatcher() {
        private val held = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            held.addLast(block)
        }

        fun release() {
            while (held.isNotEmpty()) held.removeFirst().run()
        }
    }

    private fun lockRepository(): LockRepository = LockRepository(database, session, LockHasher(dispatcher, iterations = FAST), clock)

    private suspend fun lockedOwner(secret: LockSecret): Long {
        val owner = profiles.ensureOwner().id
        locks.setLock(checkNotNull(locks.passWithoutLock(owner)), secret)
        return owner
    }
}

private val PIN = LockSecret.Pin("4071")
private val WRONG_PIN = LockSecret.Pin("1234")
private val SEQUENCE = LockSecret.Sequence(listOf(Press.Up, Press.Up, Press.Down, Press.Down, Press.Left, Press.Right))
private val WRONG_SEQUENCE = LockSecret.Sequence(List(6) { Press.Left })
private const val FAST = 1_000
private const val T0 = 1_700_000_000_000L
private const val UNKNOWN_ID = 999L
