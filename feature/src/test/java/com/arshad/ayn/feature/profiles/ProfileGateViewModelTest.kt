package com.arshad.ayn.feature.profiles

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.arshad.ayn.core.backup.BackupRead
import com.arshad.ayn.core.backup.BackupRefusal
import com.arshad.ayn.core.backup.FakeBackupStore
import com.arshad.ayn.core.backup.PlannedProfile
import com.arshad.ayn.core.backup.RestoreChoice
import com.arshad.ayn.core.backup.RestorePlan
import com.arshad.ayn.core.backup.RestoreResult
import com.arshad.ayn.core.backup.testBackup
import com.arshad.ayn.core.kids.FakeKidsClock
import com.arshad.ayn.core.kids.KidsTime
import com.arshad.ayn.core.lock.AwayRecord
import com.arshad.ayn.core.lock.FakeAwayRecords
import com.arshad.ayn.core.lock.FakeProfileLocks
import com.arshad.ayn.core.lock.LockKind
import com.arshad.ayn.core.lock.LockSecret
import com.arshad.ayn.core.lock.Press
import com.arshad.ayn.core.lock.RelockDecision
import com.arshad.ayn.core.lock.RelockMonitor
import com.arshad.ayn.core.lock.RelockReason
import com.arshad.ayn.core.lock.UnlockResult
import com.arshad.ayn.core.lock.sessionLockOf
import com.arshad.ayn.core.store.FakeKidsPin
import com.arshad.ayn.core.store.FakeProfileStore
import com.arshad.ayn.core.store.LastUsedProfile
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.ProfileSession
import com.arshad.ayn.core.store.ProfileTint
import java.time.LocalTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The root gate (M5-D16, § B3p) over in-memory stores and the real [RelockMonitor] on a test
 * clock: where a cold start goes, a restart after KE's process died and the record it reads
 * (M5-D21), first launch (owner, restore), the picker, unlock input and its
 * tries and waits, re-lock and rail handling, the switch order (playback ends and the old shell's
 * ViewModels clear before the session changes), where a file pick goes (M5-D20, Q29), and the
 * kids routes: pin first, the owner's lock asked for, leaving by the owner's lock (KP-D23), and
 * Time's up with the owner's More time (KP-D26).
 * Robolectric only for `android.net.Uri`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProfileGateViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val profiles = FakeProfileStore()
    private val locks = FakeProfileLocks()
    private val session = ProfileSession()
    private val lastUsed = MemoryLastUsedProfile()
    private val backups = FakeBackupStore()
    private var elapsed = Duration.ZERO
    private val monitor = RelockMonitor(elapsed = { elapsed }) { sessionLockOf(profiles.rows.value, session.currentIdOrNull) }
    private val awayRecords = FakeAwayRecords()
    private val kidsPin = FakeKidsPin()
    private val kidsClock = FakeKidsClock()

    /** The gate is built for a restart after KE's process died (M5-D21). */
    private var restarted = false

    /** Each playback end, recorded with the session's profile at that moment. */
    private val playbackEnds = mutableListOf<Long?>()

    /** Whether the player holds a video; an end clears it. */
    private var playbackHolds = false

    /** The gate is a phone's (M6-D8). */
    private var phone = false

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // Cold start

    @Test
    fun aDeviceWithNoProfileStartsAtWelcome() = runTest(dispatcher) {
        profiles.rows.value = emptyList()

        val gate = newGate()
        advanceUntilIdle()

        assertEquals(GateScreen.Welcome, gate.screen)
        assertNull(session.currentIdOrNull)
    }

    @Test
    fun theOnlyProfileUnlockedOpensStraightAway() = runTest(dispatcher) {
        profiles.rows.value = listOf(OWNER)

        val gate = newGate()
        advanceUntilIdle()

        assertEquals(GateScreen.Open(OWNER), gate.screen)
        assertEquals(OWNER.id, session.currentIdOrNull)
        assertEquals(1, gate.uiState.value.shellKey)
        assertEquals(OWNER.id, lastUsed.id)
    }

    @Test
    fun nothingShowsUntilTheProfilesLoad() = runTest(dispatcher) {
        val gate = newGate()
        advanceUntilIdle()

        assertEquals(GateScreen.Loading, gate.screen)
    }

    @Test
    fun twoProfilesStartAtThePickerOnTheLastUsed() = runTest(dispatcher) {
        profiles.rows.value = listOf(OWNER, YURI)
        lastUsed.id = YURI.id

        val gate = newGate()
        advanceUntilIdle()

        assertEquals(GateScreen.Picker(focusId = YURI.id), gate.screen)
        assertNull(session.currentIdOrNull)
    }

    @Test
    fun aLastUsedIdNoProfileHasStartsThePickerOnTheOwner() = runTest(dispatcher) {
        profiles.rows.value = listOf(OWNER, YURI)
        lastUsed.id = 99

        val gate = newGate()
        advanceUntilIdle()

        assertEquals(GateScreen.Picker(focusId = OWNER.id), gate.screen)
    }

    @Test
    fun theOnlyProfileLockedStartsAtThePicker() = runTest(dispatcher) {
        profiles.rows.value = listOf(OWNER.copy(lock = LockKind.Sequence))

        val gate = newGate()
        advanceUntilIdle()

        assertEquals(GateScreen.Picker(focusId = OWNER.id), gate.screen)
    }

    // Restart after KE's process died (M5-D21)

    @Test
    fun aRestartReopensTheUnlockedProfileLeftOpenWithinThirtyMinutes() = runTest(dispatcher) {
        profiles.rows.value = listOf(OWNER, YURI)
        lastUsed.id = OWNER.id
        awayRecords.record = AwayRecord(YURI.id, elapsed)
        elapsed += 29.minutes
        restarted = true

        val gate = newGate()
        advanceUntilIdle()

        assertEquals(GateScreen.Open(YURI), gate.screen)
        assertEquals(YURI.id, session.currentIdOrNull)
        assertEquals(YURI.id, lastUsed.id)
    }

    @Test
    fun aRestartAfterThirtyMinutesAwayStartsAtThePicker() = runTest(dispatcher) {
        profiles.rows.value = listOf(OWNER, YURI)
        lastUsed.id = YURI.id
        awayRecords.record = AwayRecord(YURI.id, elapsed)
        elapsed += 30.minutes
        restarted = true

        val gate = newGate()
        advanceUntilIdle()

        assertEquals(GateScreen.Picker(focusId = YURI.id), gate.screen)
        assertNull(session.currentIdOrNull)
    }

    @Test
    fun aRestartLeftOnALockedProfileStillAsksForItsLock() = runTest(dispatcher) {
        profiles.rows.value = listOf(ARSHAD_SEQ)
        lastUsed.id = ARSHAD_SEQ.id
        awayRecords.record = AwayRecord(ARSHAD_SEQ.id, elapsed)
        elapsed += 1.minutes
        restarted = true

        val gate = newGate()
        advanceUntilIdle()

        assertEquals(GateScreen.Picker(focusId = ARSHAD_SEQ.id), gate.screen)
        assertNull(session.currentIdOrNull)
    }

    @Test
    fun aFreshLaunchKeepsTheColdStartRule() = runTest(dispatcher) {
        profiles.rows.value = listOf(OWNER, YURI)
        lastUsed.id = YURI.id
        awayRecords.record = AwayRecord(YURI.id, elapsed)
        elapsed += 1.minutes

        val gate = newGate()
        advanceUntilIdle()

        assertEquals(GateScreen.Picker(focusId = YURI.id), gate.screen)
    }

    @Test
    fun keLeftWithAShellOpenKeepsItsProfileAndLastActiveTime() = runTest(dispatcher) {
        val gate = atPicker(OWNER, YURI)
        gate.onPick(YURI)
        advanceUntilIdle()
        monitor.onStart()
        val lastActive = elapsed
        elapsed += 1.minutes

        monitor.onStop()
        advanceUntilIdle()

        assertEquals(AwayRecord(YURI.id, lastActive), awayRecords.record)
    }

    /** An activity recreated in a live process while KE is stopped: the gate starts loading with
     *  the monitor still away, and must read the record before anything overwrites it. */
    @Test
    fun aGateBuiltWhileKeIsStillAwayReadsTheRecordBeforeWritingOne() = runTest(dispatcher) {
        monitor.onStart()
        monitor.onStop()
        awayRecords.record = AwayRecord(YURI.id, elapsed)
        elapsed += 1.minutes
        restarted = true

        val gate = newGate()
        advanceUntilIdle()
        profiles.rows.value = listOf(OWNER, YURI)
        advanceUntilIdle()

        assertEquals(GateScreen.Open(YURI), gate.screen)
    }

    @Test
    fun aScreenOffOnTheGateBeforeAnyProfileOpenedDoesntLockTheProfileUnlockedAfter() = runTest(dispatcher) {
        val gate = atPicker(ARSHAD_SEQ, YURI)
        monitor.onStart()
        monitor.onScreenOff()
        gate.onPick(ARSHAD_SEQ)
        advanceUntilIdle()
        SEQUENCE.forEach(gate::onPress)
        gate.onSubmitSequence()
        advanceUntilIdle()

        monitor.check()
        advanceUntilIdle()

        assertEquals(GateScreen.Open(ARSHAD_SEQ), gate.screen)
    }

    @Test
    fun keLeftWithNoShellOpenClearsTheRecord() = runTest(dispatcher) {
        awayRecords.record = AwayRecord(OWNER.id, elapsed)
        atPicker(OWNER, YURI)
        monitor.onStart()

        monitor.onStop()
        advanceUntilIdle()

        assertNull(awayRecords.record)
    }

    // First launch

    @Test
    fun continueWithNoNameAsksForOneAndCreatesNothing() = runTest(dispatcher) {
        val gate = firstLaunch()
        gate.onCreateProfile()
        gate.onNameChange("   ")

        gate.onCreateOwner()
        advanceUntilIdle()

        assertTrue((gate.screen as GateScreen.NameOwner).nameMissing)
        assertEquals(emptyList<Profile>(), profiles.rows.value)
    }

    @Test
    fun namingTheOwnerCreatesItOpensItAndKeepsItAsLastUsed() = runTest(dispatcher) {
        val gate = firstLaunch()
        gate.onCreateProfile()
        gate.onNameChange("Arshad")
        gate.onTintChange(ProfileTint.Teal)

        gate.onCreateOwner()
        advanceUntilIdle()

        val owner = profiles.rows.value!!.single()
        assertEquals(Profile(owner.id, "Arshad", ProfileTint.Teal, isOwner = true, lock = null), owner)
        assertEquals(GateScreen.Open(owner), gate.screen)
        assertEquals(owner.id, session.currentIdOrNull)
        assertEquals(owner.id, lastUsed.id)
    }

    @Test
    fun aNameLongerThanTwentyCharactersIsntTaken() = runTest(dispatcher) {
        val gate = firstLaunch()
        gate.onCreateProfile()
        gate.onNameChange("a".repeat(20))

        gate.onNameChange("a".repeat(21))

        assertEquals("a".repeat(20), (gate.screen as GateScreen.NameOwner).name)
    }

    @Test
    fun aPickedBackupIsReadThenConfirmedWithItsProfiles() = runTest(dispatcher) {
        val gate = firstLaunch()
        val backup = testBackup("Arshad", "Mira", "Yuri")
        backups.reads[FILE] = BackupRead.Valid(backup)

        gate.onRestoreFilePicked(FILE, "Ayn-backup-2026-10-03.json")
        assertEquals(GateScreen.Restore("Ayn-backup-2026-10-03.json", RestoreStep.Reading), gate.screen)
        advanceUntilIdle()

        assertEquals(
            GateScreen.Restore("Ayn-backup-2026-10-03.json", RestoreStep.Confirm(backup.profiles, deviceSettings = false)),
            gate.screen,
        )
    }

    @Test
    fun aBackupWithoutItsOwnerCantStartTheDevice() = runTest(dispatcher) {
        val gate = firstLaunch()
        backups.reads[FILE] = BackupRead.Valid(testBackup("Mira", "Yuri", withOwner = false))
        gate.onRestoreFilePicked(FILE, null)
        advanceUntilIdle()

        gate.onRestore()
        advanceUntilIdle()

        assertEquals(GateScreen.Restore(null, RestoreStep.NoOwner), gate.screen)
        assertEquals(emptyList<Any>(), backups.restored)
    }

    @Test
    fun aRefusedFileSaysWhyAndRestoresNothing() = runTest(dispatcher) {
        val gate = firstLaunch()
        backups.reads[FILE] = BackupRead.Refused(BackupRefusal.NewerVersion)
        gate.onRestoreFilePicked(FILE, null)
        advanceUntilIdle()

        gate.onRestore()
        advanceUntilIdle()

        assertEquals(GateScreen.Restore(null, RestoreStep.Refused(BackupRefusal.NewerVersion)), gate.screen)
        assertEquals(emptyList<Any>(), backups.restored)
    }

    @Test
    fun restoreEverythingAddsEveryProfileThenShowsWhatCameBack() = runTest(dispatcher) {
        val gate = firstLaunch()
        val backup = testBackup("Arshad", "Mira")
        backups.reads[FILE] = BackupRead.Valid(backup)
        val result = RestoreResult.Restored(ownerId = 7)
        backups.restoreResult = result
        gate.onRestoreFilePicked(FILE, null)
        advanceUntilIdle()

        backups.restoreGate = CompletableDeferred()
        gate.onRestore()
        runCurrent()
        assertEquals(1, playbackEnds.size) // ended before the restore writes anything (M5-D3)
        assertEquals(emptyList<Any>(), backups.restored)
        backups.restoreGate!!.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(backup), backups.restored)
        assertEquals(listOf(listOf(RestoreChoice.AddAsNew, RestoreChoice.AddAsNew)), backups.restoredChoices)
        assertEquals(GateScreen.Restore(null, RestoreStep.Done(result)), gate.screen)
    }

    @Test
    fun doneOnTheResultsOpensTheRestoredOwner() = runTest(dispatcher) {
        val gate = restoredAtFirstLaunch()
        // The real store writes the backup's profiles and selects its owner.
        session.select(RESTORED_OWNER.id)

        gate.onRestoreDone()
        advanceUntilIdle()

        assertEquals(GateScreen.Open(RESTORED_OWNER), gate.screen)
        assertEquals(RESTORED_OWNER.id, session.currentIdOrNull)
    }

    @Test
    fun chooseProfilesKeepsTheOwnerAndRestoresTheTickedOnes() = runTest(dispatcher) {
        val gate = firstLaunch()
        val backup = testBackup("Arshad", "Mira", "Yuri")
        backups.reads[FILE] = BackupRead.Valid(backup)
        gate.onRestoreFilePicked(FILE, null)
        advanceUntilIdle()

        gate.onChooseProfiles()
        assertEquals(RestoreStep.Choose(backup.profiles, deviceSettings = false, chosen = setOf(0, 1, 2)), gate.restoreStep)
        gate.onToggleRestoreProfile(0)
        gate.onToggleRestoreProfile(2)
        gate.onRestore()
        advanceUntilIdle()

        assertEquals(
            listOf(listOf(RestoreChoice.AddAsNew, RestoreChoice.AddAsNew, RestoreChoice.Skip)),
            backups.restoredChoices,
        )
    }

    @Test
    fun backFromChooseProfilesReturnsToTheConfirm() = runTest(dispatcher) {
        val gate = firstLaunch()
        val backup = testBackup("Arshad", "Mira")
        backups.reads[FILE] = BackupRead.Valid(backup)
        gate.onRestoreFilePicked(FILE, null)
        advanceUntilIdle()
        gate.onChooseProfiles()
        gate.onToggleRestoreProfile(1)

        gate.onFirstLaunchBack()

        assertEquals(RestoreStep.Confirm(backup.profiles, deviceSettings = false), gate.restoreStep)
    }

    @Test
    fun aFailedRestoreSaysSoAndBackReturnsToWelcome() = runTest(dispatcher) {
        val gate = firstLaunch()
        backups.reads[FILE] = BackupRead.Valid(testBackup("Arshad"))
        backups.restoreResult = RestoreResult.Failed
        gate.onRestoreFilePicked(FILE, null)
        advanceUntilIdle()
        gate.onRestore()
        advanceUntilIdle()
        assertEquals(GateScreen.Restore(null, RestoreStep.Failed), gate.screen)

        gate.onFirstLaunchBack()

        assertEquals(GateScreen.Welcome, gate.screen)
    }

    @Test
    fun tryAgainAfterAFailedRestoreRunsTheSameChoices() = runTest(dispatcher) {
        val gate = firstLaunch()
        backups.reads[FILE] = BackupRead.Valid(testBackup("Arshad", "Mira"))
        backups.restoreResult = RestoreResult.Failed
        gate.onRestoreFilePicked(FILE, null)
        advanceUntilIdle()
        gate.onChooseProfiles()
        gate.onToggleRestoreProfile(1)
        gate.onRestore()
        advanceUntilIdle()

        backups.restoreResult = RestoreResult.Restored(ownerId = 7)
        gate.onRestore()
        advanceUntilIdle()

        val chosen = listOf(RestoreChoice.AddAsNew, RestoreChoice.Skip)
        assertEquals(listOf(chosen, chosen), backups.restoredChoices)
        assertTrue(gate.restoreStep is RestoreStep.Done)
    }

    @Test
    fun aDeviceWhoseOwnerIsLockedIsRefusedTheFirstLaunchRestore() = runTest(dispatcher) {
        val gate = firstLaunch()
        locks.ownerId = OWNER.id
        locks.locks[OWNER.id] = LockSecret.Sequence(SEQUENCE)
        backups.reads[FILE] = BackupRead.Valid(testBackup("Arshad"))
        gate.onRestoreFilePicked(FILE, null)
        advanceUntilIdle()

        gate.onRestore()
        advanceUntilIdle()

        assertEquals(GateScreen.Restore(null, RestoreStep.Failed), gate.screen)
        assertEquals(emptyList<Any>(), backups.restored)
        assertEquals(emptyList<Long?>(), playbackEnds)
    }

    @Test
    fun aDeviceThatHasAnOwnerAlreadyIsRefusedTheFirstLaunchRestore() = runTest(dispatcher) {
        val gate = firstLaunch()
        backups.plan = { backup -> RestorePlan(firstLaunch = false, roomForNew = 4, backup.profiles.map { PlannedProfile(it, replaces = null) }) }
        backups.reads[FILE] = BackupRead.Valid(testBackup("Arshad"))
        gate.onRestoreFilePicked(FILE, null)
        advanceUntilIdle()

        gate.onRestore()
        advanceUntilIdle()

        assertEquals(GateScreen.Restore(null, RestoreStep.Failed), gate.screen)
        assertEquals(emptyList<Any>(), backups.restored)
    }

    @Test
    fun backIsIgnoredWhileARestoreRuns() = runTest(dispatcher) {
        val gate = firstLaunch()
        val backup = testBackup("Arshad")
        backups.reads[FILE] = BackupRead.Valid(backup)
        backups.restoreGate = CompletableDeferred()
        gate.onRestoreFilePicked(FILE, null)
        advanceUntilIdle()
        gate.onRestore()
        runCurrent()

        gate.onFirstLaunchBack()

        assertEquals(GateScreen.Restore(null, RestoreStep.Confirm(backup.profiles, deviceSettings = false, restoring = true)), gate.screen)
    }

    @Test
    fun lockMyProfileOnTheResultsSetsTheOwnersLockThenOpensTheOwner() = runTest(dispatcher) {
        val gate = restoredAtFirstLaunch()

        gate.onLockMyProfile()
        gate.onOwnerLockKind(LockKind.Sequence)
        assertEquals(RestoreStep.Done(RESTORED, OwnerLockStep.NewLock(LockKind.Sequence)), gate.restoreStep)
        enterSequenceTwice(gate)

        assertTrue(locks.unlock(RESTORED_OWNER.id, LockSecret.Sequence(SEQUENCE)) is UnlockResult.Opened)
        assertEquals(GateScreen.Open(RESTORED_OWNER), gate.screen)
    }

    @Test
    fun backFromTheResultsLockStepsReturnsToTheResultsThenOpensTheOwner() = runTest(dispatcher) {
        val gate = restoredAtFirstLaunch()
        gate.onLockMyProfile()
        gate.onOwnerLockKind(LockKind.Pin)

        gate.onFirstLaunchBack()
        assertEquals(RestoreStep.Done(RESTORED, OwnerLockStep.ChooseKind), gate.restoreStep)
        gate.onFirstLaunchBack()
        assertEquals(RestoreStep.Done(RESTORED), gate.restoreStep)
        gate.onFirstLaunchBack()
        advanceUntilIdle()

        assertEquals(GateScreen.Open(RESTORED_OWNER), gate.screen)
        assertNull(locks.locks[RESTORED_OWNER.id])
    }

    @Test
    fun touchBackOnTheResultsNewLockReturnsToChooseALockWithDigitsEntered() = runTest(dispatcher) {
        val gate = restoredAtFirstLaunch()
        gate.onLockMyProfile()
        gate.onOwnerLockKind(LockKind.Pin)
        gate.onDigit('1')
        assertEquals(1, ((gate.restoreStep as RestoreStep.Done).lock as OwnerLockStep.NewLock).entry.entered)

        gate.onRestoreLockLeave()

        assertEquals(RestoreStep.Done(RESTORED, OwnerLockStep.ChooseKind), gate.restoreStep)
    }

    @Test
    fun aFilePickedAfterTheRestoreIsNotTaken() = runTest(dispatcher) {
        val gate = restoredAtFirstLaunch()

        gate.onRestoreFilePicked(FILE, "another.json")
        advanceUntilIdle()

        assertEquals(RestoreStep.Done(RESTORED), gate.restoreStep)
    }

    // Picker and unlock

    @Test
    fun pickingAnUnlockedProfileOpensIt() = runTest(dispatcher) {
        val gate = atPicker(OWNER, YURI)

        gate.onPick(YURI)
        advanceUntilIdle()

        assertEquals(GateScreen.Open(YURI), gate.screen)
        assertEquals(YURI.id, session.currentIdOrNull)
    }

    @Test
    fun okWithTooFewPressesHintsAndCountsNoTry() = runTest(dispatcher) {
        val gate = atUnlock(ARSHAD_SEQ)
        repeat(5) { gate.onPress(Press.Up) }

        gate.onSubmitSequence()
        advanceUntilIdle()

        assertEquals(UnlockMessage.TooShort, gate.entry.message)
        assertEquals(5, gate.entry.entered)
        assertEquals(emptyList<Long>(), locks.tries)
    }

    @Test
    fun theRightSequenceOpensTheProfile() = runTest(dispatcher) {
        val gate = atUnlock(ARSHAD_SEQ)
        SEQUENCE.forEach(gate::onPress)

        gate.onSubmitSequence()
        advanceUntilIdle()

        assertEquals(GateScreen.Open(ARSHAD_SEQ), gate.screen)
        assertEquals(ARSHAD_SEQ.id, session.currentIdOrNull)
    }

    @Test
    fun pressesStopAtEight() = runTest(dispatcher) {
        val gate = atUnlock(ARSHAD_SEQ)

        repeat(10) { gate.onPress(Press.Left) }

        assertEquals(8, gate.entry.entered)
    }

    @Test
    fun aWrongTryClearsTheDotsAndWarnsAtTwoLeft() = runTest(dispatcher) {
        val gate = atUnlock(ARSHAD_SEQ)
        repeat(2) { wrongSequence(gate) }
        repeat(7) { gate.onPress(Press.Down) }

        gate.onSubmitSequence()
        advanceUntilIdle()

        val entry = gate.entry
        assertEquals(0, entry.entered)
        assertEquals(7, entry.rejectedDots)
        assertEquals(3, entry.rejections)
        assertEquals(UnlockMessage.Wrong(triesBeforeWait = 2), entry.message)
        assertTrue(gate.screen is GateScreen.Unlock)
    }

    @Test
    fun theFifthWrongTryStartsAThirtySecondWaitThatRefusesInputUntilItEnds() = runTest(dispatcher) {
        val gate = atUnlock(ARSHAD_SEQ)
        repeat(5) { wrongSequence(gate) }
        assertEquals(30.seconds, gate.entry.wait)

        gate.onPress(Press.Up)
        advanceTimeBy(29.seconds)
        runCurrent()
        assertEquals(0, gate.entry.entered)
        assertEquals(1.seconds, gate.entry.wait)

        advanceTimeBy(1.seconds)
        runCurrent()
        gate.onPress(Press.Up)

        assertNull(gate.entry.wait)
        assertEquals(1, gate.entry.entered)
    }

    @Test
    fun aTryRefusedAsWaitingShowsTheWaitAndCountsNothing() = runTest(dispatcher) {
        val gate = atUnlock(ARSHAD_SEQ)
        SEQUENCE.forEach(gate::onPress)
        locks.waits[ARSHAD_SEQ.id] = 20.seconds // started elsewhere (the owner gate) meanwhile

        gate.onSubmitSequence()
        runCurrent()

        assertEquals(20.seconds, gate.entry.wait)
        assertEquals(0, gate.entry.entered)
        assertFalse(gate.entry.checking)
        assertEquals(emptyList<Long>(), locks.tries)
    }

    @Test
    fun backDuringAWaitReturnsToThePickerAndTheWaitDoesntFollowToAnotherProfile() = runTest(dispatcher) {
        val gate = atPicker(ARSHAD_SEQ, MIRA_PIN)
        locks.waits[ARSHAD_SEQ.id] = 47.seconds
        gate.onPick(ARSHAD_SEQ)
        runCurrent()

        gate.onUnlockBack()
        assertEquals(GateScreen.Picker(focusId = ARSHAD_SEQ.id), gate.screen)
        gate.onPick(MIRA_PIN)
        advanceTimeBy(60.seconds)
        runCurrent()
        gate.onDigit('2')

        assertNull(gate.entry.wait)
        assertEquals(1, gate.entry.entered)
    }

    @Test
    fun aStatusLandingAfterTheUnlockScreenWasLeftChangesNothing() = runTest(dispatcher) {
        val gate = atPicker(ARSHAD_SEQ, YURI)
        locks.locks.remove(ARSHAD_SEQ.id) // its lock went away: the status would open it
        locks.statusGate = CompletableDeferred()
        gate.onPick(ARSHAD_SEQ)
        runCurrent()

        gate.onUnlockBack()
        locks.statusGate!!.complete(Unit)
        advanceUntilIdle()

        assertEquals(GateScreen.Picker(focusId = ARSHAD_SEQ.id), gate.screen)
        assertNull(session.currentIdOrNull)
    }

    @Test
    fun aLockedProfileWhoseLockWentAwayOpensOnArrival() = runTest(dispatcher) {
        val gate = atPicker(ARSHAD_SEQ, YURI)
        locks.locks.remove(ARSHAD_SEQ.id)

        gate.onPick(ARSHAD_SEQ)
        advanceUntilIdle()

        assertEquals(GateScreen.Open(ARSHAD_SEQ), gate.screen)
    }

    @Test
    fun aProfileAlreadyWaitingShowsItsWaitOnArrival() = runTest(dispatcher) {
        locks.waits[ARSHAD_SEQ.id] = 47.seconds

        val gate = atUnlock(ARSHAD_SEQ)

        assertEquals(47.seconds, gate.entry.wait)
        assertEquals(47.seconds, gate.entry.waitShown)
    }

    @Test
    fun backErasesAPressThenReturnsToThePickerOnThatProfile() = runTest(dispatcher) {
        val gate = atUnlock(ARSHAD_SEQ)
        gate.onPress(Press.Up)
        gate.onPress(Press.Right)

        gate.onUnlockBack()
        assertEquals(1, gate.entry.entered)
        gate.onUnlockBack()
        assertEquals(0, gate.entry.entered)
        gate.onUnlockBack()

        assertEquals(GateScreen.Picker(focusId = ARSHAD_SEQ.id), gate.screen)
    }

    @Test
    fun aPinSubmitsOnItsFourthDigit() = runTest(dispatcher) {
        val gate = atUnlock(MIRA_PIN)
        "248".forEach(gate::onDigit)
        gate.onErase()
        assertEquals(2, gate.entry.entered)

        "61".forEach(gate::onDigit)
        advanceUntilIdle()

        assertEquals(GateScreen.Open(MIRA_PIN), gate.screen)
    }

    @Test
    fun backErasesADigitThenReturnsToThePicker() = runTest(dispatcher) {
        val gate = atUnlock(MIRA_PIN)
        "24".forEach(gate::onDigit)

        gate.onUnlockBack()
        assertEquals(1, gate.entry.entered)
        gate.onUnlockBack()
        assertEquals(0, gate.entry.entered)
        gate.onUnlockBack()

        assertEquals(GateScreen.Picker(focusId = MIRA_PIN.id), gate.screen)
    }

    @Test
    fun aWrongPinClearsItsFourDigits() = runTest(dispatcher) {
        val gate = atUnlock(MIRA_PIN)

        "9999".forEach(gate::onDigit)
        advanceUntilIdle()

        assertEquals(0, gate.entry.entered)
        assertEquals(4, gate.entry.rejectedDots)
        assertEquals(UnlockMessage.Wrong(triesBeforeWait = 4), gate.entry.message)
    }

    @Test
    fun sequencePressesDontReachAPinAndDigitsDontReachASequence() = runTest(dispatcher) {
        val gate = atPicker(MIRA_PIN, ARSHAD_SEQ)
        gate.onPick(MIRA_PIN)
        runCurrent()
        gate.onPress(Press.Up)
        assertEquals(0, gate.entry.entered)
        gate.onUnlockBack()

        gate.onPick(ARSHAD_SEQ)
        runCurrent()
        gate.onDigit('4')

        assertEquals(0, gate.entry.entered)
    }

    @Test
    fun aTryInFlightIgnoresBackAndInputAndStillLands() = runTest(dispatcher) {
        val gate = atUnlock(ARSHAD_SEQ)
        locks.unlockGate = CompletableDeferred()
        SEQUENCE.forEach(gate::onPress)
        gate.onSubmitSequence()
        runCurrent()
        assertTrue(gate.entry.checking)

        gate.onUnlockBack()
        gate.onPress(Press.Up)
        assertTrue(gate.screen is GateScreen.Unlock)
        assertEquals(6, gate.entry.entered)
        assertEquals(listOf(ARSHAD_SEQ.id), locks.tries)
        locks.unlockGate!!.complete(Unit)
        advanceUntilIdle()

        assertEquals(GateScreen.Open(ARSHAD_SEQ), gate.screen)
    }

    @Test
    fun theEraseKeyTakesTheLastPressOfASequence() = runTest(dispatcher) {
        val gate = atUnlock(ARSHAD_SEQ)
        gate.onPress(Press.Up)
        gate.onPress(Press.Right)

        gate.onErase()

        assertEquals(1, gate.entry.entered)
        assertTrue(gate.screen is GateScreen.Unlock)
    }

    @Test
    fun touchBackLeavesForThePickerWithPressesEntered() = runTest(dispatcher) {
        val gate = atUnlock(ARSHAD_SEQ)
        gate.onPress(Press.Up)
        gate.onPress(Press.Right)

        gate.onUnlockLeave()

        assertEquals(GateScreen.Picker(focusId = ARSHAD_SEQ.id), gate.screen)
    }

    @Test
    fun touchBackIsIgnoredWhileATryIsChecked() = runTest(dispatcher) {
        val gate = atUnlock(ARSHAD_SEQ)
        locks.unlockGate = CompletableDeferred()
        SEQUENCE.forEach(gate::onPress)
        gate.onSubmitSequence()
        runCurrent()

        gate.onUnlockLeave()
        assertTrue(gate.screen is GateScreen.Unlock)
        locks.unlockGate!!.complete(Unit)
        advanceUntilIdle()

        assertEquals(GateScreen.Open(ARSHAD_SEQ), gate.screen)
    }

    // Re-lock, rail and switching

    @Test
    fun aRelockEndsPlaybackShowsThePickerAndConsumesTheDecision() = runTest(dispatcher) {
        val gate = openedThroughUnlock(ARSHAD_SEQ, YURI)

        awayFor(5.minutes)
        advanceUntilIdle()

        assertEquals(GateScreen.Picker(focusId = ARSHAD_SEQ.id), gate.screen)
        assertEquals(listOf<Long?>(ARSHAD_SEQ.id), playbackEnds)
        assertEquals(RelockDecision.Stay, monitor.decision.value)
    }

    /** M6-D8: on a phone the screen going off re-locks the profile but keeps its video; its unlock
     *  says why, and unlocking resumes the same shell. */
    @Test
    fun aPhoneRelockKeepsTheVideoAndShowsThatProfilesUnlock() = runTest(dispatcher) {
        phone = true
        val gate = openedThroughUnlock(ARSHAD_SEQ, YURI)
        val key = gate.uiState.value.shellKey
        playbackHolds = true

        monitor.onStop()
        monitor.onScreenOff()
        monitor.onStart()
        advanceUntilIdle()

        assertEquals(GateScreen.Unlock(ARSHAD_SEQ, relockReason = RelockReason.ScreenOff), gate.screen)
        assertEquals(ARSHAD_SEQ, gate.uiState.value.keptFor)
        assertEquals(emptyList<Long?>(), playbackEnds)
        assertEquals(RelockDecision.Stay, monitor.decision.value)

        SEQUENCE.forEach(gate::onPress)
        gate.onSubmitSequence()
        advanceUntilIdle()
        assertEquals(GateScreen.Open(ARSHAD_SEQ), gate.screen)
        assertEquals(key, gate.uiState.value.shellKey)
        assertNull(gate.uiState.value.keptFor)
        assertEquals(emptyList<Long?>(), playbackEnds)
    }

    /** Back from the kept unlock: the picker still carries the video, with no way back to the shell;
     *  choosing another profile ends it. */
    @Test
    fun aPhonesKeptVideoLastsOnThePickerUntilAnotherProfileOpens() = runTest(dispatcher) {
        phone = true
        val gate = openedThroughUnlock(ARSHAD_SEQ, YURI)
        playbackHolds = true
        awayFor(5.minutes)
        advanceUntilIdle()
        assertEquals(GateScreen.Unlock(ARSHAD_SEQ, relockReason = RelockReason.Away), gate.screen)

        gate.onUnlockLeave()
        assertEquals(GateScreen.Picker(focusId = ARSHAD_SEQ.id, canReturn = false), gate.screen)
        assertEquals(ARSHAD_SEQ, gate.uiState.value.keptFor)

        gate.onPick(YURI)
        advanceUntilIdle()
        assertEquals(GateScreen.Open(YURI), gate.screen)
        assertEquals(listOf<Long?>(ARSHAD_SEQ.id), playbackEnds)
        assertNull(gate.uiState.value.keptFor)
    }

    /** With nothing in the player a phone re-lock is the TV's: the picker. */
    @Test
    fun aPhoneRelockWithNothingPlayingShowsThePicker() = runTest(dispatcher) {
        phone = true
        val gate = openedThroughUnlock(ARSHAD_SEQ, YURI)

        awayFor(5.minutes)
        advanceUntilIdle()

        assertEquals(GateScreen.Picker(focusId = ARSHAD_SEQ.id), gate.screen)
        assertNull(gate.uiState.value.keptFor)
    }

    /** Lock and switch is deliberate: it ends the video on a phone too. */
    @Test
    fun aPhonesLockAndSwitchEndsTheVideo() = runTest(dispatcher) {
        phone = true
        val gate = openedThroughUnlock(ARSHAD_SEQ, YURI)
        playbackHolds = true

        gate.onRailProfile()

        assertEquals(listOf<Long?>(ARSHAD_SEQ.id), playbackEnds)
        assertNull(gate.uiState.value.keptFor)
    }

    /** An unlocked profile's picker (the rail, 30 minutes away) keeps a phone's held video too, also
     *  through another profile's unlock; back to the profile, nothing is kept and nothing ended. */
    @Test
    fun aPhonesPickerOverAnUnlockedProfileKeepsItsVideo() = runTest(dispatcher) {
        phone = true
        val gate = atPicker(OWNER, MIRA_PIN)
        gate.onPick(OWNER)
        advanceUntilIdle()
        monitor.onStart()
        playbackHolds = true

        gate.onRailProfile()
        assertEquals(OWNER, gate.uiState.value.keptFor)
        gate.onPick(MIRA_PIN)
        assertEquals(OWNER, gate.uiState.value.keptFor)
        assertEquals(GateScreen.Unlock(MIRA_PIN), gate.screen)
        gate.onUnlockLeave()
        assertTrue(gate.onPickerBack())
        assertEquals(GateScreen.Open(OWNER), gate.screen)
        assertNull(gate.uiState.value.keptFor)

        awayFor(30.minutes)
        advanceUntilIdle()
        assertEquals(GateScreen.Picker(focusId = OWNER.id), gate.screen)
        assertEquals(OWNER, gate.uiState.value.keptFor)
        assertEquals(emptyList<Long?>(), playbackEnds)
    }

    /** The TV never keeps a video under the picker, held or not. */
    @Test
    fun aTvRelockEndsAHeldVideo() = runTest(dispatcher) {
        val gate = openedThroughUnlock(ARSHAD_SEQ, YURI)
        playbackHolds = true

        awayFor(5.minutes)
        advanceUntilIdle()

        assertEquals(GateScreen.Picker(focusId = ARSHAD_SEQ.id), gate.screen)
        assertEquals(listOf<Long?>(ARSHAD_SEQ.id), playbackEnds)
        assertNull(gate.uiState.value.keptFor)
    }

    @Test
    fun aDecisionWithNoShellOpenIsConsumedSoTheNextOneStillActs() = runTest(dispatcher) {
        val gate = openedThroughUnlock(ARSHAD_SEQ, YURI)
        awayFor(5.minutes)
        advanceUntilIdle()

        awayFor(5.minutes) // again, on the picker
        advanceUntilIdle()
        assertEquals(RelockDecision.Stay, monitor.decision.value)
        gate.onPick(ARSHAD_SEQ)
        advanceUntilIdle()
        SEQUENCE.forEach(gate::onPress)
        gate.onSubmitSequence()
        advanceUntilIdle()
        awayFor(5.minutes)
        advanceUntilIdle()

        assertEquals(GateScreen.Picker(focusId = ARSHAD_SEQ.id), gate.screen)
    }

    @Test
    fun unlockingTheSameProfileAfterARelockResumesItsShell() = runTest(dispatcher) {
        val gate = openedThroughUnlock(ARSHAD_SEQ, YURI)
        val key = gate.uiState.value.shellKey
        val shell = gate.shellViewModels.sentinel()
        awayFor(5.minutes)
        advanceUntilIdle()

        gate.onPick(ARSHAD_SEQ)
        advanceUntilIdle()
        SEQUENCE.forEach(gate::onPress)
        gate.onSubmitSequence()
        advanceUntilIdle()

        assertEquals(GateScreen.Open(ARSHAD_SEQ), gate.screen)
        assertEquals(key, gate.uiState.value.shellKey)
        assertSame(shell, gate.shellViewModels.sentinel())
        assertFalse(shell.cleared)
    }

    @Test
    fun switchingToAnotherProfileEndsPlaybackFirstAndStartsAFreshShell() = runTest(dispatcher) {
        val gate = atPicker(OWNER, YURI)
        gate.onPick(OWNER)
        advanceUntilIdle()
        val key = gate.uiState.value.shellKey
        val ownersShell = gate.shellViewModels
        val ownersModel = ownersShell.sentinel()
        gate.onRailProfile()

        gate.onPick(YURI)
        advanceUntilIdle()

        assertEquals(listOf<Long?>(OWNER.id), playbackEnds)
        assertEquals(YURI.id, session.currentIdOrNull)
        assertEquals(key + 1, gate.uiState.value.shellKey)
        assertEquals("cleared while the session was still the owner's", OWNER.id, ownersModel.clearedUnder)
        assertNotSame(ownersShell, gate.shellViewModels)
    }

    @Test
    fun theGateClearedClearsTheOpenShellsViewModels() = runTest(dispatcher) {
        val activity = ViewModelStore()
        val gate = ViewModelProvider.create(activity, viewModelFactory { initializer { atPicker(OWNER, YURI) } })[ProfileGateViewModel::class]
        gate.onPick(OWNER)
        advanceUntilIdle()
        val shell = gate.shellViewModels.sentinel()

        activity.clear()

        assertTrue(shell.cleared)
    }

    @Test
    fun anUnlockedProfileAwayThirtyMinutesGetsThePickerWithoutEndingPlayback() = runTest(dispatcher) {
        val gate = atPicker(OWNER, YURI)
        gate.onPick(OWNER)
        advanceUntilIdle()
        val shell = gate.shellViewModels.sentinel()

        awayFor(30.minutes)
        advanceUntilIdle()

        assertEquals(GateScreen.Picker(focusId = OWNER.id), gate.screen)
        assertEquals(emptyList<Long?>(), playbackEnds)
        assertFalse(gate.onPickerBack())

        val key = gate.uiState.value.shellKey
        gate.onPick(OWNER)
        advanceUntilIdle()
        assertEquals(key, gate.uiState.value.shellKey)
        assertEquals(emptyList<Long?>(), playbackEnds)
        assertSame(shell, gate.shellViewModels.sentinel())
    }

    @Test
    fun aRailPickerKeepsBackToTheSessionAfterADetourIntoAnUnlockScreen() = runTest(dispatcher) {
        val gate = atPicker(OWNER, MIRA_PIN)
        gate.onPick(OWNER)
        advanceUntilIdle()
        gate.onRailProfile()
        gate.onPick(MIRA_PIN)
        runCurrent()

        gate.onUnlockBack()

        assertEquals(GateScreen.Picker(focusId = MIRA_PIN.id, canReturn = true), gate.screen)
        assertTrue(gate.onPickerBack())
        assertEquals(GateScreen.Open(OWNER), gate.screen)
    }

    @Test
    fun theRailSlotOverAnUnlockedProfileOpensAPickerBackReturnsFrom() = runTest(dispatcher) {
        val gate = atPicker(OWNER, YURI)
        gate.onPick(OWNER)
        advanceUntilIdle()
        val key = gate.uiState.value.shellKey

        gate.onRailProfile()
        assertEquals(GateScreen.Picker(focusId = OWNER.id, canReturn = true), gate.screen)

        assertTrue(gate.onPickerBack())
        assertEquals(GateScreen.Open(OWNER), gate.screen)
        assertEquals(key, gate.uiState.value.shellKey)
        assertEquals(emptyList<Long?>(), playbackEnds)
    }

    @Test
    fun theRailSlotOverALockedProfileLocksItNow() = runTest(dispatcher) {
        val gate = openedThroughUnlock(ARSHAD_SEQ, YURI)

        gate.onRailProfile()

        assertEquals(GateScreen.Picker(focusId = ARSHAD_SEQ.id, canReturn = false), gate.screen)
        assertEquals(listOf<Long?>(ARSHAD_SEQ.id), playbackEnds)
        assertFalse(gate.onPickerBack())
    }

    @Test
    fun aLockSetWhileOpenMakesTheRailSlotLockNow() = runTest(dispatcher) {
        val gate = atPicker(OWNER, YURI)
        gate.onPick(YURI)
        advanceUntilIdle()

        profiles.rows.value = listOf(OWNER, YURI.copy(lock = LockKind.Pin))
        advanceUntilIdle()
        gate.onRailProfile()

        assertEquals(GateScreen.Picker(focusId = YURI.id, canReturn = false), gate.screen)
        assertEquals(listOf<Long?>(YURI.id), playbackEnds)
    }

    // Kids profiles (KP-D9, D14, D23)

    @Test
    fun aKidsProfilePickedIsPinnedBeforeItOpens() = runTest(dispatcher) {
        val gate = atPickerWithKids()

        gate.onPick(KIDS)
        advanceUntilIdle()

        assertEquals(GateScreen.Open(KIDS), gate.screen)
        assertEquals(KIDS.id, kidsPin.pinnedId)
        assertEquals(KIDS.id, session.currentIdOrNull)
    }

    @Test
    fun aKidsProfileWhosePinIsntWrittenStaysClosed() = runTest(dispatcher) {
        val gate = atPickerWithKids()
        kidsPin.writes = false

        gate.onPick(KIDS)
        advanceUntilIdle()

        assertEquals(GateScreen.Picker(focusId = KIDS.id), gate.screen)
        assertNull(session.currentIdOrNull)
    }

    @Test
    fun aStartOpensThePinnedKidsProfileBeforeAnyOtherRoute() = runTest(dispatcher) {
        kidsPin.pinnedId = KIDS.id
        awayRecords.record = AwayRecord(YURI.id, elapsed) // a restart would reopen Yuri
        elapsed += 1.minutes
        restarted = true

        val gate = atPickerWithKids(ARSHAD_SEQ, YURI)

        assertEquals(GateScreen.Open(KIDS), gate.screen)
        assertEquals(KIDS.id, session.currentIdOrNull)
    }

    @Test
    fun aPinNamingNoKidsProfileStartsAsUsual() = runTest(dispatcher) {
        kidsPin.pinnedId = YURI.id
        lastUsed.id = YURI.id

        val gate = atPickerWithKids(ARSHAD_SEQ, YURI)

        assertEquals(GateScreen.Picker(focusId = YURI.id), gate.screen)
        assertNull(session.currentIdOrNull)
    }

    @Test
    fun aPinnedKidsProfileStaysOpenAfterTimeAwayAndTheScreenGoingOff() = runTest(dispatcher) {
        val gate = kidsOpen()

        awayFor(31.minutes)
        monitor.onScreenOff()
        advanceUntilIdle()

        assertEquals(GateScreen.Open(KIDS), gate.screen)
        assertEquals(emptyList<Long?>(), playbackEnds)
    }

    @Test
    fun aKidsProfilePickedWhileTheOwnerHasNoLockAsksForOneFirst() = runTest(dispatcher) {
        val gate = atPickerWithKids(owner = OWNER)

        gate.onPick(KIDS)
        advanceUntilIdle()

        assertEquals(GateScreen.OwnerLockNeeded(KIDS), gate.screen)
        assertNull(kidsPin.pinnedId)
        assertNull(session.currentIdOrNull)
    }

    @Test
    fun theOwnerSetsALockThenTheKidsProfileOpensPinned() = runTest(dispatcher) {
        val gate = atPickerWithKids(owner = OWNER)
        gate.onPick(KIDS)
        advanceUntilIdle()

        gate.onOwnerLockStart()
        assertEquals(GateScreen.OwnerLockNeeded(KIDS, OwnerLockStep.ChooseKind), gate.screen)
        gate.onOwnerLockKind(LockKind.Sequence)
        assertEquals(GateScreen.OwnerLockNeeded(KIDS, OwnerLockStep.NewLock(LockKind.Sequence)), gate.screen)
        enterSequenceTwice(gate)

        assertTrue(locks.unlockOwner(LockSecret.Sequence(SEQUENCE)) is UnlockResult.Opened)
        assertEquals(GateScreen.Open(KIDS), gate.screen)
        assertEquals(KIDS.id, kidsPin.pinnedId)
    }

    @Test
    fun aMismatchedOwnerLockSavesNothingAndAsksAgain() = runTest(dispatcher) {
        val gate = atPickerWithKids(owner = OWNER)
        gate.onPick(KIDS)
        advanceUntilIdle()
        gate.onOwnerLockStart()
        gate.onOwnerLockKind(LockKind.Pin)

        PIN.forEach(gate::onDigit)
        "1111".forEach(gate::onDigit)
        advanceUntilIdle()

        val step = (gate.screen as GateScreen.OwnerLockNeeded).step as OwnerLockStep.NewLock
        assertEquals(UnlockMessage.Mismatch, step.entry.message)
        assertNull(locks.locks[OWNER.id])
        assertNull(kidsPin.pinnedId)
    }

    @Test
    fun backFromTheOwnerLockErasesThenStepsBackToThePicker() = runTest(dispatcher) {
        val gate = atPickerWithKids(owner = OWNER)
        gate.onPick(KIDS)
        advanceUntilIdle()
        gate.onOwnerLockStart()
        gate.onOwnerLockKind(LockKind.Pin)
        gate.onDigit('1')

        gate.onOwnerLockBack()
        assertEquals(GateScreen.OwnerLockNeeded(KIDS, OwnerLockStep.NewLock(LockKind.Pin)), gate.screen)
        gate.onOwnerLockBack()
        assertEquals(GateScreen.OwnerLockNeeded(KIDS, OwnerLockStep.ChooseKind), gate.screen)
        gate.onOwnerLockBack()
        assertEquals(GateScreen.OwnerLockNeeded(KIDS), gate.screen)
        gate.onOwnerLockBack()

        assertEquals(GateScreen.Picker(focusId = KIDS.id), gate.screen)
        assertNull(locks.locks[OWNER.id])
        assertNull(kidsPin.pinnedId)
    }

    @Test
    fun touchBackOnTheNewOwnerLockReturnsToChooseALockWithDigitsEntered() = runTest(dispatcher) {
        val gate = atPickerWithKids(owner = OWNER)
        gate.onPick(KIDS)
        advanceUntilIdle()
        gate.onOwnerLockStart()
        gate.onOwnerLockKind(LockKind.Pin)
        gate.onDigit('1')

        gate.onOwnerLockLeave()

        assertEquals(GateScreen.OwnerLockNeeded(KIDS, OwnerLockStep.ChooseKind), gate.screen)
        assertNull(locks.locks[OWNER.id])
    }

    @Test
    fun touchBackOnTheNewOwnerLockIsIgnoredWhileItSaves() = runTest(dispatcher) {
        val gate = atPickerWithKids(owner = OWNER)
        gate.onPick(KIDS)
        advanceUntilIdle()
        gate.onOwnerLockStart()
        gate.onOwnerLockKind(LockKind.Pin)
        locks.setLockGate = CompletableDeferred()
        repeat(2) { PIN.forEach(gate::onDigit) }
        runCurrent()

        gate.onOwnerLockLeave()
        assertTrue((gate.screen as GateScreen.OwnerLockNeeded).step is OwnerLockStep.NewLock)
        locks.setLockGate?.complete(Unit)
        advanceUntilIdle()

        assertEquals(GateScreen.Open(KIDS), gate.screen)
    }

    @Test
    fun theRailSlotInAKidsProfileAsksForTheOwnersLock() = runTest(dispatcher) {
        val gate = kidsOpen()

        gate.onRailProfile()

        assertEquals(GateScreen.Unlock(ARSHAD_SEQ, leaving = KIDS), gate.screen)
        assertEquals(emptyList<Long?>(), playbackEnds)
    }

    @Test
    fun wrongTriesLeavingAKidsProfileCountOnItsOwnTries() = runTest(dispatcher) {
        val gate = kidsOpen()
        gate.onRailProfile()
        runCurrent()

        wrongSequence(gate)

        assertEquals(listOf(KIDS.id), locks.tries)
        assertNull(locks.wrongTries[ARSHAD_SEQ.id])
        assertEquals(GateScreen.Unlock::class, gate.screen::class)
    }

    @Test
    fun leavingShowsTheKidsProfilesOwnWaitNotTheOwners() = runTest(dispatcher) {
        val gate = kidsOpen()
        locks.waits[ARSHAD_SEQ.id] = 30.seconds
        locks.waits[KIDS.id] = 20.seconds

        gate.onRailProfile()
        runCurrent()

        assertEquals(20.seconds, gate.entry.waitShown)
    }

    @Test
    fun theOwnersLockLeavesTheKidsProfileForThePickerOnTheOwner() = runTest(dispatcher) {
        val gate = kidsOpen()
        gate.onRailProfile()
        runCurrent()

        SEQUENCE.forEach(gate::onPress)
        gate.onSubmitSequence()
        advanceUntilIdle()

        assertEquals(GateScreen.Picker(focusId = ARSHAD_SEQ.id, canReturn = false), gate.screen)
        assertNull(kidsPin.pinnedId)
        assertEquals(1, kidsPin.unpins)
        assertEquals(listOf<Long?>(KIDS.id), playbackEnds)
        assertFalse(gate.onPickerBack())
    }

    @Test
    fun theParentPagesPassLeavesTheKidsProfileWithoutASecondAsk() = runTest(dispatcher) {
        val gate = kidsOpen()

        gate.onLeaveKids(checkNotNull(FakeProfileLocks().ownerPassWithoutLock()))
        advanceUntilIdle()

        assertEquals(GateScreen.Picker(focusId = ARSHAD_SEQ.id, canReturn = false), gate.screen)
        assertNull(kidsPin.pinnedId)
        assertEquals(listOf<Long?>(KIDS.id), playbackEnds)
    }

    @Test
    fun backWithNothingEnteredReturnsToTheKidsShellStillPinned() = runTest(dispatcher) {
        val gate = kidsOpen()
        val key = gate.uiState.value.shellKey
        gate.onRailProfile()
        runCurrent()
        gate.onPress(Press.Up)

        gate.onUnlockBack()
        assertEquals(0, gate.entry.entered)
        gate.onUnlockBack()

        assertEquals(GateScreen.Open(KIDS), gate.screen)
        assertEquals(key, gate.uiState.value.shellKey)
        assertEquals(KIDS.id, kidsPin.pinnedId)
        assertEquals(emptyList<Long?>(), playbackEnds)
    }

    // Time's up and bedtime (KP-D8, D26)

    @Test
    fun aKidsShellClosesWhenItsTimeIsUpAndResumesWhenTimeRunsAgain() = runTest(dispatcher) {
        val gate = kidsOpen()
        val key = gate.uiState.value.shellKey
        val shell = gate.shellViewModels.sentinel()

        kidsClock.time.value = KidsTime.TimesUp
        runCurrent()
        assertEquals(GateScreen.TimeStopped(KIDS, KidsTime.TimesUp), gate.screen)

        kidsClock.time.value = KidsTime.Left(10.minutes)
        runCurrent()
        assertEquals(GateScreen.Open(KIDS), gate.screen)
        assertEquals(key, gate.uiState.value.shellKey)
        assertFalse(shell.cleared)
    }

    @Test
    fun aKidsProfileOpenedAfterItsTimeIsUpGoesStraightToTimesUp() = runTest(dispatcher) {
        kidsClock.time.value = KidsTime.TimesUp
        val gate = atPickerWithKids()

        gate.onPick(KIDS)
        advanceUntilIdle()

        assertEquals(GateScreen.TimeStopped(KIDS, KidsTime.TimesUp), gate.screen)
    }

    @Test
    fun bedtimeReplacesTimesUpOnTheSameScreen() = runTest(dispatcher) {
        val gate = kidsOpen()
        kidsClock.time.value = KidsTime.TimesUp
        runCurrent()

        kidsClock.time.value = KidsTime.Bedtime(LocalTime.of(7, 0))
        runCurrent()

        assertEquals(GateScreen.TimeStopped(KIDS, KidsTime.Bedtime(LocalTime.of(7, 0))), gate.screen)
    }

    @Test
    fun anotherProfilesShellNeverStopsForTime() = runTest(dispatcher) {
        val gate = atPicker(OWNER, YURI)
        gate.onPick(OWNER)
        advanceUntilIdle()

        kidsClock.time.value = KidsTime.TimesUp
        runCurrent()

        assertEquals(GateScreen.Open(OWNER), gate.screen)
    }

    @Test
    fun moreTimeAsksForTheOwnersLockCountedOnTheKidsTries() = runTest(dispatcher) {
        val gate = timesUp()

        gate.onMoreTime()
        runCurrent()
        assertEquals(StoppedStep.Unlock(ARSHAD_SEQ, StoppedAsk.MoreTime), gate.stoppedStep)
        wrongSequence(gate)

        assertEquals(listOf(KIDS.id), locks.tries)
        assertNull(locks.wrongTries[ARSHAD_SEQ.id])
        assertEquals(1, (gate.stoppedStep as StoppedStep.Unlock).entry.rejections)
    }

    @Test
    fun theOwnersLockThenAnAmountGivesMoreTimeAndResumesTheShell() = runTest(dispatcher) {
        val gate = timesUp()
        val key = gate.uiState.value.shellKey
        kidsClock.tomorrow = 90
        gate.onMoreTime()
        runCurrent()

        SEQUENCE.forEach(gate::onPress)
        gate.onSubmitSequence()
        advanceUntilIdle()
        assertEquals(StoppedStep.Pick(limitTomorrow = 90), gate.stoppedStep)

        gate.onMoreTimePick(30)
        advanceUntilIdle()

        assertEquals(listOf(30), kidsClock.grants)
        assertEquals(GateScreen.Open(KIDS), gate.screen)
        assertEquals(key, gate.uiState.value.shellKey)
        assertEquals(KIDS.id, kidsPin.pinnedId)
    }

    @Test
    fun aGrantThatDoesntSaveStaysOnThePickAndSaysSo() = runTest(dispatcher) {
        val gate = atMoreTimePick()
        kidsClock.failGrants = true

        gate.onMoreTimePick(15)
        advanceUntilIdle()

        assertEquals(StoppedStep.Pick(limitTomorrow = null, failed = true), gate.stoppedStep)
    }

    @Test
    fun anAmountOutsideTheChoicesIsIgnored() = runTest(dispatcher) {
        val gate = atMoreTimePick()

        gate.onMoreTimePick(45)
        advanceUntilIdle()

        assertEquals(emptyList<Int>(), kidsClock.grants)
    }

    @Test
    fun backFromTheUnlockErasesThenReturnsToTimesUp() = runTest(dispatcher) {
        val gate = timesUp()
        gate.onMoreTime()
        runCurrent()
        gate.onPress(Press.Up)

        gate.onStoppedBack()
        assertEquals(0, (gate.stoppedStep as StoppedStep.Unlock).entry.entered)
        gate.onStoppedBack()

        assertEquals(StoppedStep.Statement, gate.stoppedStep)
    }

    @Test
    fun touchBackFromTheUnlockReturnsToTimesUpWithPressesEnteredButNotWhileATryIsChecked() = runTest(dispatcher) {
        val gate = timesUp()
        gate.onMoreTime()
        runCurrent()
        gate.onPress(Press.Up)

        gate.onStoppedLeave()
        assertEquals(StoppedStep.Statement, gate.stoppedStep)

        gate.onMoreTime()
        runCurrent()
        locks.unlockGate = CompletableDeferred()
        SEQUENCE.forEach(gate::onPress)
        gate.onSubmitSequence()
        runCurrent()
        gate.onStoppedLeave()
        assertTrue(gate.stoppedStep is StoppedStep.Unlock)
        locks.unlockGate?.complete(Unit)
        advanceUntilIdle()
        assertEquals(StoppedStep.Pick(limitTomorrow = null), gate.stoppedStep)
    }

    @Test
    fun touchBackFromThePickDropsThePassButNotWhileTheGrantSaves() = runTest(dispatcher) {
        val gate = atMoreTimePick()
        kidsClock.grantGate = CompletableDeferred()
        gate.onMoreTimePick(15)
        runCurrent()

        gate.onStoppedLeave()
        assertTrue(gate.stoppedStep is StoppedStep.Pick)
        kidsClock.failGrants = true
        kidsClock.grantGate?.complete(Unit)
        advanceUntilIdle()
        gate.onStoppedLeave()
        gate.onMoreTimePick(15)
        advanceUntilIdle()

        assertEquals(StoppedStep.Statement, gate.stoppedStep)
        assertEquals(emptyList<Int>(), kidsClock.grants)
    }

    @Test
    fun backFromThePickDropsThePass() = runTest(dispatcher) {
        val gate = atMoreTimePick()

        gate.onStoppedBack()
        gate.onMoreTimePick(15)
        advanceUntilIdle()

        assertEquals(StoppedStep.Statement, gate.stoppedStep)
        assertEquals(emptyList<Int>(), kidsClock.grants)
    }

    // Switch profile from Time's up

    @Test
    fun switchProfileAsksForTheOwnersLockCountedOnTheKidsTries() = runTest(dispatcher) {
        val gate = timesUp()

        gate.onSwitchProfile()
        runCurrent()
        assertEquals(StoppedStep.Unlock(ARSHAD_SEQ, StoppedAsk.SwitchProfile), gate.stoppedStep)
        wrongSequence(gate)

        assertEquals(listOf(KIDS.id), locks.tries)
        assertNull(locks.wrongTries[ARSHAD_SEQ.id])
        assertEquals(KIDS.id, kidsPin.pinnedId)
    }

    @Test
    fun theOwnersLockOnSwitchProfileLeavesForThePickerOnTheOwner() = runTest(dispatcher) {
        val gate = timesUp()
        gate.onSwitchProfile()
        runCurrent()

        SEQUENCE.forEach(gate::onPress)
        gate.onSubmitSequence()
        advanceUntilIdle()

        assertEquals(GateScreen.Picker(focusId = ARSHAD_SEQ.id, canReturn = false), gate.screen)
        assertNull(kidsPin.pinnedId)
        assertEquals(listOf<Long?>(KIDS.id), playbackEnds)
        assertEquals(emptyList<Int>(), kidsClock.grants)
        assertFalse(gate.onPickerBack())
    }

    @Test
    fun theKidPickedAgainAfterSwitchingIsStillOnTimesUp() = runTest(dispatcher) {
        val gate = timesUp()
        gate.onSwitchProfile()
        runCurrent()
        SEQUENCE.forEach(gate::onPress)
        gate.onSubmitSequence()
        advanceUntilIdle()

        gate.onPick(KIDS)
        advanceUntilIdle()

        assertEquals(GateScreen.TimeStopped(KIDS, KidsTime.TimesUp), gate.screen)
        assertEquals(KIDS.id, kidsPin.pinnedId)
    }

    @Test
    fun backFromSwitchProfilesUnlockErasesThenReturnsToTimesUp() = runTest(dispatcher) {
        val gate = timesUp()
        gate.onSwitchProfile()
        runCurrent()
        gate.onPress(Press.Up)

        gate.onStoppedBack()
        assertEquals(0, (gate.stoppedStep as StoppedStep.Unlock).entry.entered)
        gate.onStoppedBack()

        assertEquals(StoppedStep.Statement, gate.stoppedStep)
        assertEquals(KIDS.id, kidsPin.pinnedId)
    }

    @Test
    fun moreTimesLateStatusReadNeverActsOnSwitchProfilesUnlock() = runTest(dispatcher) {
        val gate = timesUp()
        locks.statusGate = CompletableDeferred()
        gate.onMoreTime()
        runCurrent()
        gate.onStoppedBack()
        gate.onSwitchProfile()
        runCurrent()

        // The owner's lock went away meanwhile, so both reads pass free; only Switch profile's acts.
        locks.locks.remove(ARSHAD_SEQ.id)
        locks.statusGate!!.complete(Unit)
        advanceUntilIdle()

        assertEquals(GateScreen.Picker(focusId = ARSHAD_SEQ.id, canReturn = false), gate.screen)
        assertNull(kidsPin.pinnedId)
    }

    @Test
    fun switchProfilesLateStatusReadNeverActsOnMoreTimesUnlock() = runTest(dispatcher) {
        val gate = timesUp()
        locks.statusGate = CompletableDeferred()
        gate.onSwitchProfile()
        runCurrent()
        gate.onStoppedBack()
        gate.onMoreTime()
        runCurrent()

        locks.locks.remove(ARSHAD_SEQ.id)
        locks.statusGate!!.complete(Unit)
        advanceUntilIdle()

        assertEquals(StoppedStep.Pick(limitTomorrow = null), gate.stoppedStep)
        assertEquals(KIDS.id, kidsPin.pinnedId)
        assertEquals(emptyList<Long?>(), playbackEnds)
    }

    // Settings restore

    @Test
    fun aSettingsRestoreReopensTheOwnersShellFreshWithItsResults() = runTest(dispatcher) {
        val gate = atPicker(OWNER, YURI)
        gate.onPick(OWNER)
        advanceUntilIdle()
        val key = gate.uiState.value.shellKey
        val oldShell = gate.shellViewModels.sentinel()

        // Replaced profiles keep their ids: the owner's shell is the same profile, reopened.
        gate.onRestored(OWNER.id, SETTINGS_RESTORE)
        advanceUntilIdle()

        assertEquals(GateScreen.Open(OWNER), gate.screen)
        assertEquals(key + 1, gate.uiState.value.shellKey)
        assertTrue(oldShell.cleared)
        assertEquals(SETTINGS_RESTORE, gate.uiState.value.restored)
        assertEquals(emptyList<Long?>(), playbackEnds)
    }

    @Test
    fun theRestoredResultsAreShownOnce() = runTest(dispatcher) {
        val gate = atPicker(OWNER, YURI)
        gate.onPick(OWNER)
        advanceUntilIdle()
        gate.onRestored(OWNER.id, SETTINGS_RESTORE)
        advanceUntilIdle()
        assertEquals(SETTINGS_RESTORE, gate.uiState.value.restored)

        gate.onRestoredShown()

        assertNull(gate.uiState.value.restored)
    }

    @Test
    fun aLockedOwnerWhoJustRestoredIsntAskedForTheLockAgain() = runTest(dispatcher) {
        val gate = openedThroughUnlock(ARSHAD_SEQ, YURI)

        gate.onRestored(OWNER.id, SETTINGS_RESTORE)
        advanceUntilIdle()

        assertEquals(GateScreen.Open(ARSHAD_SEQ), gate.screen)
        assertEquals(SETTINGS_RESTORE, gate.uiState.value.restored)
    }

    @Test
    fun onlyTheRestoredShellSeesTheResults() = runTest(dispatcher) {
        val gate = atPicker(OWNER, YURI)
        gate.onPick(OWNER)
        advanceUntilIdle()
        gate.onRestored(OWNER.id, SETTINGS_RESTORE)
        advanceUntilIdle()

        gate.onRailProfile()
        gate.onPick(YURI)
        advanceUntilIdle()

        assertNull(gate.uiState.value.restored)
    }

    @Test
    fun aRestoreHandedOverWithNoShellOpenShowsWhenTheOwnerOpensNext() = runTest(dispatcher) {
        val gate = atPicker(OWNER, YURI)

        gate.onRestored(OWNER.id, SETTINGS_RESTORE)
        advanceUntilIdle()
        assertEquals(GateScreen.Picker(focusId = OWNER.id), gate.screen)
        assertNull(gate.uiState.value.restored)

        gate.onPick(OWNER)
        advanceUntilIdle()
        assertEquals(SETTINGS_RESTORE, gate.uiState.value.restored)
    }

    @Test
    fun aHeldRestoreIsDroppedWhenAnotherProfileOpensFirst() = runTest(dispatcher) {
        val gate = atPicker(OWNER, YURI)
        gate.onRestored(OWNER.id, SETTINGS_RESTORE)

        gate.onPick(YURI)
        advanceUntilIdle()
        assertNull(gate.uiState.value.restored)
        gate.onRailProfile()
        gate.onPick(OWNER)
        advanceUntilIdle()

        assertNull(gate.uiState.value.restored)
    }

    // File picks (M5-D20, Q29)

    @Test
    fun aRestorePickThatLandsBeforeTheProfilesLoadIsReadOnceTheWelcomeShows() = runTest(dispatcher) {
        backups.reads[FILE] = BackupRead.Valid(testBackup("Arshad"))
        val gate = newGate()
        gate.onFilePicked(PickRequest(PickKind.Restore, profileId = null), FILE) { "Ayn-backup.json" }
        advanceUntilIdle()
        assertEquals(GateScreen.Loading, gate.screen)

        profiles.rows.value = emptyList()
        advanceUntilIdle()

        assertEquals(GateScreen.Restore("Ayn-backup.json", RestoreStep.Confirm(testBackup("Arshad").profiles, deviceSettings = false)), gate.screen)
    }

    @Test
    fun aPickForTheOpenShellsSettingsIsOfferedAtOnce() = runTest(dispatcher) {
        profiles.rows.value = listOf(OWNER)
        val gate = newGate()
        advanceUntilIdle()

        gate.onFilePicked(PickRequest(PickKind.Import, OWNER.id), FILE) { "subscriptions.csv" }
        advanceUntilIdle()

        assertEquals(PickedFile(PickRequest(PickKind.Import, OWNER.id), FILE, "subscriptions.csv"), gate.settingsPick.value)
    }

    @Test
    fun aSettingsPickWaitsBehindItsProfilesUnlockAndIsTakenOnceItsShellOpens() = runTest(dispatcher) {
        val gate = atPicker(ARSHAD_SEQ, YURI)
        gate.onFilePicked(PickRequest(PickKind.Backup, OWNER.id), FILE) { null }
        gate.onPick(ARSHAD_SEQ)
        advanceUntilIdle()
        assertNull(gate.settingsPick.value)

        SEQUENCE.forEach(gate::onPress)
        gate.onSubmitSequence()
        advanceUntilIdle()

        val pick = PickedFile(PickRequest(PickKind.Backup, OWNER.id), FILE, null)
        assertEquals(GateScreen.Open(ARSHAD_SEQ), gate.screen)
        assertEquals(pick, gate.settingsPick.value)
        assertTrue(gate.takeSettingsPick(pick))
        assertFalse(gate.takeSettingsPick(pick))
        advanceUntilIdle()
        assertNull(gate.settingsPick.value)
    }

    @Test
    fun aSettingsPickIsDroppedWhenAnotherProfileOpensFirst() = runTest(dispatcher) {
        val gate = atPicker(ARSHAD_SEQ, YURI)
        gate.onFilePicked(PickRequest(PickKind.Import, OWNER.id), FILE) { null }
        gate.onPick(YURI)
        advanceUntilIdle()
        assertNull(gate.settingsPick.value)

        gate.onRailProfile()
        gate.onPick(ARSHAD_SEQ)
        advanceUntilIdle()
        SEQUENCE.forEach(gate::onPress)
        gate.onSubmitSequence()
        advanceUntilIdle()

        assertEquals(GateScreen.Open(ARSHAD_SEQ), gate.screen)
        assertNull(gate.settingsPick.value)
    }

    @Test
    fun aPickThatLandsWhileAnotherProfilesShellIsOpenIsDropped() = runTest(dispatcher) {
        val gate = atPicker(OWNER, YURI)
        gate.onPick(YURI)
        advanceUntilIdle()
        gate.onFilePicked(PickRequest(PickKind.Import, OWNER.id), FILE) { null }
        advanceUntilIdle()

        gate.onRailProfile()
        gate.onPick(OWNER)
        advanceUntilIdle()

        assertEquals(GateScreen.Open(OWNER), gate.screen)
        assertNull(gate.settingsPick.value)
        assertFalse(gate.takeSettingsPick(PickedFile(PickRequest(PickKind.Import, OWNER.id), FILE, null)))
    }

    @Test
    fun aSecondPickReplacesOneStillWaiting() = runTest(dispatcher) {
        val gate = atPicker(OWNER, YURI)
        val second = Uri.parse("content://downloads/second.csv")
        gate.onFilePicked(PickRequest(PickKind.Import, OWNER.id), FILE) { null }
        advanceUntilIdle()
        gate.onFilePicked(PickRequest(PickKind.Import, OWNER.id), second) { null }

        gate.onPick(OWNER)
        advanceUntilIdle()

        assertEquals(PickedFile(PickRequest(PickKind.Import, OWNER.id), second, null), gate.settingsPick.value)
    }

    @Test
    fun aRestorePickIsNeverOfferedToAShell() = runTest(dispatcher) {
        val gate = atPicker(OWNER, YURI)
        val restore = PickedFile(PickRequest(PickKind.Restore, profileId = null), FILE, null)
        gate.onFilePicked(restore.request, FILE) { null }

        gate.onPick(OWNER)
        advanceUntilIdle()

        assertEquals(GateScreen.Open(OWNER), gate.screen)
        assertNull(gate.settingsPick.value)
        assertFalse(gate.takeSettingsPick(restore))
    }

    @Test
    fun aPickWhoseNameLandsAfterAnotherProfileOpenedIsDropped() = runTest(dispatcher) {
        val gate = atPicker(OWNER, YURI)
        val name = CompletableDeferred<String?>()
        gate.onFilePicked(PickRequest(PickKind.Import, OWNER.id), FILE) { name.await() }
        gate.onPick(YURI)
        advanceUntilIdle()
        name.complete("subscriptions.csv")
        advanceUntilIdle()

        gate.onRailProfile()
        gate.onPick(OWNER)
        advanceUntilIdle()

        assertEquals(GateScreen.Open(OWNER), gate.screen)
        assertNull(gate.settingsPick.value)
    }

    // Helpers

    private val ProfileGateViewModel.screen: GateScreen get() = uiState.value.screen
    private val ProfileGateViewModel.entry: UnlockEntry get() = (screen as GateScreen.Unlock).entry
    private val ProfileGateViewModel.restoreStep: RestoreStep get() = (screen as GateScreen.Restore).step
    private val ProfileGateViewModel.stoppedStep: StoppedStep get() = (screen as GateScreen.TimeStopped).step

    private fun TestScope.newGate(): ProfileGateViewModel = ProfileGateViewModel(
        profileStore = profiles,
        locks = locks,
        session = session,
        relock = monitor,
        lastUsed = lastUsed,
        backups = backups,
        playback = object : ProfilePlayback {
            override fun end() {
                playbackEnds += session.currentIdOrNull
                playbackHolds = false
            }

            override fun holdsItem(): Boolean = playbackHolds
        },
        awayRecords = awayRecords,
        kidsPin = kidsPin,
        kidsClock = kidsClock,
        restarted = restarted,
        timeSource = testScheduler.timeSource,
        keepsPlaybackOnRelock = phone,
    )

    /** No profiles yet: the welcome, and a store whose plans add every profile. */
    private fun TestScope.firstLaunch(): ProfileGateViewModel {
        profiles.rows.value = emptyList()
        backups.plan = { backup -> RestorePlan(firstLaunch = true, roomForNew = 5, backup.profiles.map { PlannedProfile(it, replaces = null) }) }
        return newGate().also { advanceUntilIdle() }
    }

    /** First launch's restore of Arshad and Mira done: its results on screen, the profiles written. */
    private fun TestScope.restoredAtFirstLaunch(): ProfileGateViewModel {
        val gate = firstLaunch()
        backups.reads[FILE] = BackupRead.Valid(testBackup("Arshad", "Mira"))
        backups.restoreResult = RESTORED
        gate.onRestoreFilePicked(FILE, null)
        advanceUntilIdle()
        gate.onRestore()
        advanceUntilIdle()
        profiles.rows.value = listOf(RESTORED_OWNER, Profile(8, "Mira", ProfileTint.Rose, isOwner = false, lock = null))
        advanceUntilIdle()
        return gate
    }

    private fun TestScope.atPicker(vararg on: Profile): ProfileGateViewModel {
        profiles.rows.value = on.toList()
        on.forEach { profile ->
            locks.known += profile.id
            when (profile.lock) {
                LockKind.Sequence -> locks.locks[profile.id] = LockSecret.Sequence(SEQUENCE)
                LockKind.Pin -> locks.locks[profile.id] = LockSecret.Pin(PIN)
                null -> Unit
            }
        }
        return newGate().also { advanceUntilIdle() }
    }

    private fun TestScope.atUnlock(profile: Profile): ProfileGateViewModel {
        val gate = atPicker(profile, YURI)
        gate.onPick(profile)
        runCurrent() // a wait it is already in keeps counting down
        return gate
    }

    private fun TestScope.openedThroughUnlock(locked: Profile, other: Profile): ProfileGateViewModel {
        val gate = atPicker(locked, other)
        gate.onPick(locked)
        advanceUntilIdle()
        SEQUENCE.forEach(gate::onPress)
        gate.onSubmitSequence()
        advanceUntilIdle()
        monitor.onStart()
        return gate
    }

    private fun TestScope.wrongSequence(gate: ProfileGateViewModel) {
        repeat(6) { gate.onPress(Press.Left) }
        gate.onSubmitSequence()
        runCurrent()
    }

    /** The owner (locked with [SEQUENCE]) and [KIDS], as the lock store knows them. */
    private fun TestScope.atPickerWithKids(owner: Profile = ARSHAD_SEQ, vararg others: Profile): ProfileGateViewModel {
        locks.ownerId = owner.id
        locks.kids += KIDS.id
        return atPicker(owner, KIDS, *others)
    }

    /** [KIDS] picked and open, pinned. */
    private fun TestScope.kidsOpen(): ProfileGateViewModel {
        val gate = atPickerWithKids()
        gate.onPick(KIDS)
        advanceUntilIdle()
        monitor.onStart()
        return gate
    }

    /** [KIDS] open, then its time up. */
    private fun TestScope.timesUp(): ProfileGateViewModel {
        val gate = kidsOpen()
        kidsClock.time.value = KidsTime.TimesUp
        runCurrent()
        return gate
    }

    /** Time's up, the owner's lock entered: More time's amounts show. */
    private fun TestScope.atMoreTimePick(): ProfileGateViewModel {
        val gate = timesUp()
        gate.onMoreTime()
        runCurrent()
        SEQUENCE.forEach(gate::onPress)
        gate.onSubmitSequence()
        advanceUntilIdle()
        return gate
    }

    private fun TestScope.enterSequenceTwice(gate: ProfileGateViewModel) {
        repeat(2) {
            SEQUENCE.forEach(gate::onPress)
            gate.onSubmitSequence()
        }
        advanceUntilIdle()
    }

    /** KE goes to the background for [away], then comes back. */
    private fun awayFor(away: Duration) {
        monitor.onStop()
        elapsed += away
        monitor.onStart()
    }

    /** A shell ViewModel recording the session's profile when it was cleared. */
    private inner class ShellSentinel : ViewModel() {
        var cleared = false
        var clearedUnder: Long? = null

        override fun onCleared() {
            cleared = true
            clearedUnder = session.currentIdOrNull
        }
    }

    /** This store's [ShellSentinel], created on first use. */
    private fun ViewModelStore.sentinel(): ShellSentinel =
        ViewModelProvider.create(this, viewModelFactory { initializer { ShellSentinel() } })[ShellSentinel::class]

    private class MemoryLastUsedProfile : LastUsedProfile {
        var id: Long? = null

        override suspend fun get(): Long? = id

        override suspend fun set(profileId: Long) {
            id = profileId
        }
    }

    private companion object {
        val OWNER = Profile(1, "Arshad", ProfileTint.Amber, isOwner = true, lock = null)
        val ARSHAD_SEQ = OWNER.copy(lock = LockKind.Sequence)
        val MIRA_PIN = Profile(2, "Mira", ProfileTint.Rose, isOwner = false, lock = LockKind.Pin)
        val YURI = Profile(3, "Yuri", ProfileTint.Teal, isOwner = false, lock = null)
        val KIDS = Profile(4, "Zainab", ProfileTint.Rose, isOwner = false, lock = null, isKids = true)
        val SEQUENCE = listOf(Press.Up, Press.Up, Press.Down, Press.Left, Press.Right, Press.Down)
        const val PIN = "2461"
        val FILE: Uri = Uri.parse("content://downloads/backup.json")
        val RESTORED_OWNER = Profile(7, "Arshad", ProfileTint.Amber, isOwner = true, lock = null)
        val RESTORED = RestoreResult.Restored(ownerId = RESTORED_OWNER.id)
        val SETTINGS_RESTORE = RestoredBackup("Ayn-backup-2026-10-03.json", exportedAtMillis = 1_727_913_600_000, result = RestoreResult.Restored(ownerId = 1))
    }
}
