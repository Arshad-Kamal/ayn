package com.arshad.ayn.feature.settings

import com.arshad.ayn.core.store.FakeProfileStore
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.ProfileTint
import com.arshad.ayn.core.sync.SyncAccount
import com.arshad.ayn.core.sync.SyncFailure
import com.arshad.ayn.core.sync.SyncService
import com.arshad.ayn.core.sync.SyncStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The launch notice (M8-D11): it waits for the launch's own sync, tells its failure to the owner
 * and whom to tell to anyone else, says nothing when the sync worked, while unlinked or awaiting
 * Match, and is taken once.
 */
class SyncLaunchNoticeTest {
    private val status = MutableStateFlow(FAILED_BEFORE_LAUNCH)
    private val profiles = FakeProfileStore(listOf(OWNER, MIRA))

    private fun notice(): SyncLaunchNotice = SyncLaunchNotice(status, profiles.profiles())

    @Test
    fun itWaitsForTheLaunchsOwnSyncAndTellsTheOwnerItsFailure() = runTest {
        val launch = notice()
        val taken = async { launch.take(OWNER.id) }
        runCurrent()
        assertFalse(taken.isCompleted)

        status.value = FAILED_BEFORE_LAUNCH.copy(syncing = true, checks = 4)
        runCurrent()
        assertFalse(taken.isCompleted)
        status.value = FAILED_BEFORE_LAUNCH.copy(checks = 4)

        assertEquals(LaunchNotice(SyncFailure.SIGN_IN_REJECTED, ACCOUNT, isOwner = true, ownerName = "Arshad"), taken.await())
    }

    @Test
    fun anotherProfileLearnsWhomToTell() = runTest {
        val launch = notice()
        status.value = FAILED_BEFORE_LAUNCH.copy(checks = 4)

        assertEquals(LaunchNotice(SyncFailure.SIGN_IN_REJECTED, ACCOUNT, isOwner = false, ownerName = "Arshad"), launch.take(MIRA.id))
    }

    @Test
    fun aLaunchSyncThatWorkedTellsNothing() = runTest {
        val launch = notice()
        status.value = FAILED_BEFORE_LAUNCH.copy(failure = null, checks = 4)

        assertNull(launch.take(OWNER.id))
    }

    @Test
    fun unlinkedOrAwaitingMatchTellsNothing() = runTest {
        val unlinked = notice()
        status.value = SyncStatus(linked = false, checks = 4)
        assertNull(unlinked.take(OWNER.id))

        val matching = notice()
        status.value = FAILED_BEFORE_LAUNCH.copy(awaitingMatch = true, checks = 5)
        assertNull(matching.take(OWNER.id))
    }

    /** The TV's Home takes it, leaves before the sync ends (cancelled), and takes it on its return. */
    @Test
    fun aCallerCancelledWhileWaitingLeavesTheNoticeForTheNext() = runTest {
        val launch = notice()
        val left = async { launch.take(OWNER.id) }
        runCurrent()
        left.cancel()

        status.value = FAILED_BEFORE_LAUNCH.copy(checks = 4)

        assertEquals(SyncFailure.SIGN_IN_REJECTED, launch.take(OWNER.id)?.failure)
    }

    /** A sync that ends while Home isn't shown yet: Home tells the last one's outcome, not a
     *  failure healed since. */
    @Test
    fun homeTellsTheLastSyncsOutcome() = runTest {
        val launch = notice()
        status.value = FAILED_BEFORE_LAUNCH.copy(checks = 4)
        status.value = FAILED_BEFORE_LAUNCH.copy(failure = null, checks = 5)

        assertNull(launch.take(OWNER.id))
    }

    @Test
    fun theNoticeIsTakenOnce() = runTest {
        val launch = notice()
        status.value = FAILED_BEFORE_LAUNCH.copy(checks = 4)

        launch.take(OWNER.id)

        assertNull(launch.take(MIRA.id))
    }

    private companion object {
        val OWNER = Profile(1L, "Arshad", ProfileTint.Amber, isOwner = true, lock = null)
        val MIRA = Profile(2L, "Mira", ProfileTint.Teal, isOwner = false, lock = null)
        val ACCOUNT = SyncAccount(SyncService.NEXTCLOUD, "cloud.example.org", "arshad")

        /** The status at launch: the last session's sync failed, 3 syncs ended in this process. */
        val FAILED_BEFORE_LAUNCH = SyncStatus(linked = true, failure = SyncFailure.SIGN_IN_REJECTED, account = ACCOUNT, checks = 3)
    }
}
