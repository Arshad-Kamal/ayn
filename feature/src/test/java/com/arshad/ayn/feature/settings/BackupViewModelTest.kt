package com.arshad.ayn.feature.settings

import android.net.Uri
import com.arshad.ayn.core.backup.BackupRead
import com.arshad.ayn.core.backup.BackupRefusal
import com.arshad.ayn.core.backup.ExportResult
import com.arshad.ayn.core.backup.FakeBackupStore
import com.arshad.ayn.core.backup.ImportResult
import com.arshad.ayn.core.backup.PlannedProfile
import com.arshad.ayn.core.backup.RestoreChoice
import com.arshad.ayn.core.backup.RestorePlan
import com.arshad.ayn.core.backup.RestoreResult
import com.arshad.ayn.core.backup.SavedBackup
import com.arshad.ayn.core.backup.testBackup
import com.arshad.ayn.core.lock.FakeProfileLocks
import com.arshad.ayn.core.lock.LockKind
import com.arshad.ayn.core.lock.LockSecret
import com.arshad.ayn.core.lock.Press
import com.arshad.ayn.core.store.FakeProfileStore
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.ProfileTint
import com.arshad.ayn.feature.profiles.PickKind
import com.arshad.ayn.feature.profiles.PickRequest
import com.arshad.ayn.feature.profiles.PickedFile
import com.arshad.ayn.feature.profiles.RestoredBackup
import com.arshad.ayn.feature.profiles.SettingsPicks
import com.arshad.ayn.feature.profiles.UnlockMessage
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings › Backup & restore and Import from YouTube (§ B3p, M5-D14/D15, KP-D17/D24) over in-memory
 * stores: the saved list and its background counts (Q8), the owner gate first with its pass kept for
 * the flow, export of all or chosen profiles with its results, restore with a choice per profile,
 * playback ended before a restore, the handover of its results to the gate (Q9),
 * deleting a listed backup (BKD-D1/D2), imports for anyone into the opening's profile, and the file picked for this profile's Settings
 * taken from the gate once the profile has loaded (M5-D20). Robolectric only for `android.net.Uri`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val profiles = FakeProfileStore()
    private val locks = FakeProfileLocks().apply { ownerId = OWNER.id }
    private val backups = FakeBackupStore()
    private val picks = FakeSettingsPicks()

    private var playbackEnds = 0
    private val handedOver = mutableListOf<Pair<Long, RestoredBackup>>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun theOwnerSeesThisInstallsBackupsWithTheirCountsAndAnUnreadableOneDateOnly() = runTest(dispatcher) {
        backups.saved += listOf(NEW, UNREAD)
        backups.reads[NEW.uri] = BackupRead.Valid(testBackup("Arshad", "Mira", "Yuri", "Guests"))

        val vm = openAs(OWNER)

        assertEquals(listOf(SavedBackupRow(NEW, profileCount = 4), SavedBackupRow(UNREAD, profileCount = null)), vm.uiState.value.saved)
    }

    @Test
    fun someoneElseGetsNoBackupsAndCantExportOrRestore() = runTest(dispatcher) {
        backups.saved += NEW
        backups.reads[PICKED] = BackupRead.Valid(testBackup("Arshad"))
        val vm = openAs(YURI)

        vm.onExport()
        vm.onBackupFilePicked(PICKED, FILE_NAME)
        advanceUntilIdle()
        vm.onRestore()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isOwner)
        assertEquals(emptyList<SavedBackupRow>(), vm.uiState.value.saved)
        assertEquals(BackupScreen.Page, vm.screen)
        assertEquals(0, backups.exports)
        assertEquals(emptyList<Any>(), backups.restored)
    }

    @Test
    fun allProfilesExportsEveryProfileAndShowsWhatWentIn() = runTest(dispatcher) {
        backups.exportResult = ExportResult.Saved(NEW.fileName, EXPORTED)
        val vm = openAs(OWNER)
        backups.saved += NEW

        vm.onExport()
        advanceUntilIdle()
        assertEquals(BackupScreen.ExportSheet(), vm.screen)
        vm.onExportAll()
        advanceUntilIdle()

        assertEquals(listOf<Set<Long>?>(null), backups.exportedIds)
        assertEquals(BackupScreen.Exported(NEW.fileName, EXPORTED), vm.screen)
        assertEquals(listOf(NEW), vm.uiState.value.saved.map { it.backup })
        assertFalse(vm.uiState.value.working)
    }

    @Test
    fun chooseProfilesStartsWithEveryoneTickedAndExportsTheTickedOnes() = runTest(dispatcher) {
        backups.exportResult = ExportResult.Saved(NEW.fileName, EXPORTED)
        val vm = atExportSheet(OWNER)

        vm.onExportChoose()
        assertEquals(BackupScreen.ExportSheet(chosen = setOf(OWNER.id, MIRA.id, YURI.id)), vm.screen)
        vm.onExportToggle(YURI.id)
        vm.onExportChosen()
        advanceUntilIdle()

        assertEquals(listOf<Set<Long>?>(setOf(OWNER.id, MIRA.id)), backups.exportedIds)
    }

    @Test
    fun nothingTickedExportsNothing() = runTest(dispatcher) {
        val vm = atExportSheet(OWNER)
        vm.onExportChoose()
        listOf(OWNER, MIRA, YURI).forEach { vm.onExportToggle(it.id) }

        vm.onExportChosen()
        advanceUntilIdle()

        assertEquals(0, backups.exports)
        assertEquals(BackupScreen.ExportSheet(chosen = emptySet()), vm.screen)
    }

    @Test
    fun backFromChooseProfilesOffersAllProfilesAgainThenThePage() = runTest(dispatcher) {
        val vm = atExportSheet(OWNER)
        vm.onExportChoose()

        vm.onBack()
        assertEquals(BackupScreen.ExportSheet(), vm.screen)
        vm.onBack()

        assertEquals(BackupScreen.Page, vm.screen)
    }

    @Test
    fun doneOnTheExportResultsReturnsToThePage() = runTest(dispatcher) {
        backups.exportResult = ExportResult.Saved(NEW.fileName, EXPORTED)
        val vm = atExportSheet(OWNER)
        vm.onExportAll()
        advanceUntilIdle()

        vm.onBack()

        assertEquals(BackupScreen.Page, vm.screen)
    }

    @Test
    fun aSettingsRestoresResultsShowUntilDone() = runTest(dispatcher) {
        val vm = openAs(OWNER)
        val restored = RestoredBackup(FILE_NAME, AUG_30, RestoreResult.Restored(ownerId = OWNER.id))

        vm.onRestored(restored)
        assertEquals(BackupScreen.Restored(restored), vm.screen)
        vm.onBack()

        assertEquals(BackupScreen.Page, vm.screen)
    }

    @Test
    fun aFailedExportSaysSo() = runTest(dispatcher) {
        backups.exportResult = ExportResult.Failed
        val vm = atExportSheet(OWNER)

        vm.onExportAll()
        advanceUntilIdle()

        assertEquals(BackupToast.ExportFailed, vm.uiState.value.toast)
        assertEquals(BackupScreen.Page, vm.screen)
    }

    @Test
    fun aToastIsShownOnce() = runTest(dispatcher) {
        backups.exportResult = ExportResult.Failed
        val vm = atExportSheet(OWNER)
        vm.onExportAll()
        advanceUntilIdle()
        assertEquals(BackupToast.ExportFailed, vm.uiState.value.toast)

        vm.onToastShown()

        assertNull(vm.uiState.value.toast)
    }

    @Test
    fun twoExportPressesWriteOneBackup() = runTest(dispatcher) {
        backups.exportResult = ExportResult.Saved(NEW.fileName, EXPORTED)
        backups.exportGate = CompletableDeferred()
        val vm = atExportSheet(OWNER)

        vm.onExportAll()
        vm.onExportAll()
        vm.onExport()
        runCurrent()
        backups.exportGate!!.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, backups.exports)
    }

    @Test
    fun anExportKeepsTheCountsAlreadyReadWhileItRelists() = runTest(dispatcher) {
        backups.saved += NEW
        backups.reads[NEW.uri] = BackupRead.Valid(testBackup("Arshad", "Mira"))
        backups.exportResult = ExportResult.Saved(NEWER.fileName, EXPORTED)
        val vm = atExportSheet(OWNER)
        backups.saved.add(0, NEWER)
        backups.openGate = CompletableDeferred()

        vm.onExportAll()
        advanceUntilIdle()

        assertEquals(listOf(SavedBackupRow(NEWER, null), SavedBackupRow(NEW, 2)), vm.uiState.value.saved)
        backups.reads[NEW.uri] = BackupRead.Valid(testBackup("Arshad", "Mira", "Yuri")) // a re-read would show 3
        backups.openGate!!.complete(Unit)
        advanceUntilIdle()

        assertEquals(2, vm.uiState.value.saved.single { it.backup == NEW }.profileCount)
    }

    @Test
    fun savedBackupsListBeforeTheirCountsAreRead() = runTest(dispatcher) {
        backups.saved += NEW
        backups.reads[NEW.uri] = BackupRead.Valid(testBackup("Arshad", "Mira"))
        backups.openGate = CompletableDeferred()
        val vm = openAs(OWNER)
        assertEquals(listOf(SavedBackupRow(NEW, profileCount = null)), vm.uiState.value.saved)

        backups.openGate!!.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(SavedBackupRow(NEW, profileCount = 2)), vm.uiState.value.saved)
    }

    @Test
    fun theOwnerGateShowsAWaitTheOwnerIsAlreadyIn() = runTest(dispatcher) {
        locks.waits[OWNER.id] = 47.seconds
        val vm = openAs(OWNER_LOCKED)

        vm.onExport()
        runCurrent()

        assertEquals(47.seconds, (vm.screen as BackupScreen.OwnerGate).entry.wait)
    }

    @Test
    fun anOwnerLockRemovedDuringTheGatesReadsAsksNothingAndOpensNothing() = runTest(dispatcher) {
        val vm = openAs(OWNER_LOCKED)
        locks.statusGate = CompletableDeferred()
        vm.onExport()
        runCurrent()

        locks.locks.remove(OWNER.id)
        locks.statusGate!!.complete(Unit)
        advanceUntilIdle()

        assertEquals(BackupScreen.Page, vm.screen)
        assertEquals(0, backups.exports)
    }

    @Test
    fun aLockedOwnersExportAsksForTheirLockBeforeWhichProfilesCountingWrongTries() = runTest(dispatcher) {
        backups.exportResult = ExportResult.Saved(NEW.fileName, EXPORTED)
        val vm = openAs(OWNER_LOCKED)

        vm.onExport()
        advanceUntilIdle()
        assertEquals(BackupScreen.OwnerGate(BackupAction.Export, LockKind.Sequence, returnTo = BackupScreen.Page), vm.screen)
        enter(vm, WRONG_SEQUENCE)
        advanceUntilIdle()
        assertEquals(UnlockMessage.Wrong(triesBeforeWait = 4), (vm.screen as BackupScreen.OwnerGate).entry.message)
        enter(vm, SEQUENCE)
        advanceUntilIdle()
        assertEquals(BackupScreen.ExportSheet(), vm.screen)
        vm.onExportAll()
        advanceUntilIdle()

        assertEquals(1, backups.exports)
        assertEquals(listOf(OWNER.id, OWNER.id), locks.tries)
    }

    @Test
    fun theOwnersPassLastsOnlyForItsFlow() = runTest(dispatcher) {
        val vm = openAs(OWNER_LOCKED)
        vm.onExport()
        advanceUntilIdle()
        enter(vm, SEQUENCE)
        advanceUntilIdle()
        vm.onBack()

        vm.onExport()
        advanceUntilIdle()

        assertEquals(BackupScreen.OwnerGate(BackupAction.Export, LockKind.Sequence, returnTo = BackupScreen.Page), vm.screen)
    }

    @Test
    fun aPickedBackupIsReadThenOfferedProfileByProfile() = runTest(dispatcher) {
        val backup = testBackup("Arshad", "Mira", "Guests", exportedAtMillis = AUG_30)
        backups.reads[PICKED] = BackupRead.Valid(backup)
        planFor(roomForNew = 2, OWNER.id, MIRA.id, null)
        val vm = openAs(OWNER)
        val opening = CompletableDeferred<Unit>().also { backups.openGate = it }

        vm.onBackupFilePicked(PICKED, FILE_NAME)
        advanceUntilIdle()
        assertEquals(BackupScreen.Reading(FILE_NAME), vm.screen)
        opening.complete(Unit)
        advanceUntilIdle()

        val (arshad, mira, guests) = backup.profiles
        assertEquals(
            BackupScreen.RestorePick(
                fileName = FILE_NAME,
                exportedAtMillis = AUG_30,
                rows = listOf(
                    RestoreRow(arshad, replaces = OWNER.id, choice = RestoreChoice.Replace(OWNER.id)),
                    RestoreRow(mira, replaces = MIRA.id, choice = RestoreChoice.Replace(MIRA.id)),
                    RestoreRow(guests, replaces = null, choice = RestoreChoice.AddAsNew),
                ),
                roomForNew = 2,
            ),
            vm.screen,
        )
    }

    @Test
    fun addAsNewIsTheDefaultOnlyWhileThereIsRoom() = runTest(dispatcher) {
        backups.reads[PICKED] = BackupRead.Valid(testBackup("Arshad", "Guests", "Zainab"))
        planFor(roomForNew = 1, OWNER.id, null, null)

        val vm = atRestorePick(OWNER)

        assertEquals(
            listOf(RestoreChoice.Replace(OWNER.id), RestoreChoice.AddAsNew, RestoreChoice.Skip),
            vm.pick.rows.map { it.choice },
        )
    }

    @Test
    fun aSavedBackupFromTheListIsReadToo() = runTest(dispatcher) {
        backups.saved += NEW
        backups.reads[NEW.uri] = BackupRead.Valid(testBackup("Arshad"))
        val vm = openAs(OWNER)

        vm.onSavedBackup(vm.uiState.value.saved.single())
        advanceUntilIdle()

        assertEquals(NEW.fileName, vm.pick.fileName)
    }

    @Test
    fun aListedBackupsPickOffersDelete() = runTest(dispatcher) {
        assertTrue(atListedPick(OWNER).pick.deletable)
    }

    @Test
    fun deleteAsksFirstThenTheFileAndItsRowGoAndThePageSaysSo() = runTest(dispatcher) {
        val vm = atListedPick(OWNER)

        vm.onDeleteBackup()
        assertEquals(BackupDelete.Asked, vm.pick.deleteSheet)
        assertEquals(emptyList<Uri>(), backups.deleted)
        vm.onConfirmDelete()
        advanceUntilIdle()

        assertEquals(listOf(NEW.uri), backups.deleted)
        assertEquals(BackupScreen.Page, vm.screen)
        assertEquals(listOf(UNREAD), vm.uiState.value.saved.map { it.backup })
        assertEquals(BackupToast.Deleted(NEW.fileName), vm.uiState.value.toast)
    }

    @Test
    fun keepItBackAndASwipeCloseTheSheetAndDeleteNothing() = runTest(dispatcher) {
        val vm = atListedPick(OWNER)

        listOf(vm::onKeepBackup, vm::onBack, vm::onDismiss).forEach { close ->
            vm.onDeleteBackup()
            close()
            assertNull(vm.pick.deleteSheet)
        }

        assertEquals(emptyList<Uri>(), backups.deleted)
        assertEquals(listOf(NEW, UNREAD), vm.uiState.value.saved.map { it.backup })
    }

    @Test
    fun aFailedDeleteKeepsTheRowAndThePickSaysSoAndCanBeTriedAgain() = runTest(dispatcher) {
        backups.deleteFails = true
        val vm = atListedPick(OWNER)

        vm.onDeleteBackup()
        vm.onConfirmDelete()
        advanceUntilIdle()
        assertNull(vm.pick.deleteSheet)
        assertEquals(listOf(NEW, UNREAD), vm.uiState.value.saved.map { it.backup })
        assertEquals(BackupToast.DeleteFailed, vm.uiState.value.toast)
        backups.deleteFails = false
        vm.onDeleteBackup()
        vm.onConfirmDelete()
        advanceUntilIdle()

        assertEquals(listOf(UNREAD), vm.uiState.value.saved.map { it.backup })
    }

    @Test
    fun aPickedFileOffersNoDelete() = runTest(dispatcher) {
        backups.reads[PICKED] = BackupRead.Valid(testBackup("Arshad"))
        val vm = atRestorePick(OWNER)

        vm.onDeleteBackup()
        vm.onConfirmDelete()
        advanceUntilIdle()

        assertNull(vm.pick.deleteSheet)
        assertEquals(emptyList<Uri>(), backups.deleted)
    }

    @Test
    fun whileTheDeleteSheetShowsOrRunsThePickTakesNoOtherAction() = runTest(dispatcher) {
        val vm = atListedPick(OWNER)
        val deleting = CompletableDeferred<Unit>().also { backups.deleteGate = it }

        vm.onDeleteBackup()
        vm.onRestoreRow(0)
        vm.onRestore()
        advanceUntilIdle()
        assertNull(vm.pick.choosing)
        vm.onConfirmDelete()
        advanceUntilIdle()
        assertEquals(BackupDelete.Running, vm.pick.deleteSheet)
        vm.onBack()
        vm.onDismiss()
        vm.onConfirmDelete()
        advanceUntilIdle()
        assertEquals(BackupDelete.Running, vm.pick.deleteSheet)
        deleting.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(NEW.uri), backups.deleted)
        assertEquals(emptyList<Any>(), backups.restored)
        assertEquals(BackupScreen.Page, vm.screen)
    }

    @Test
    fun aLockedOwnersDeleteRunsOnThePassTheirRestoreFlowWasGiven() = runTest(dispatcher) {
        backups.saved += NEW
        backups.reads[NEW.uri] = BackupRead.Valid(testBackup("Arshad"))
        val vm = openAs(OWNER_LOCKED)
        vm.onSavedBackup(vm.uiState.value.saved.single())
        advanceUntilIdle()
        enter(vm, SEQUENCE)
        advanceUntilIdle()

        vm.onDeleteBackup()
        vm.onConfirmDelete()
        advanceUntilIdle()

        assertEquals(listOf(NEW.uri), backups.deleted)
        assertEquals(listOf(OWNER.id), locks.tries)
    }

    @Test
    fun aRowsSheetSetsItsChoice() = runTest(dispatcher) {
        backups.reads[PICKED] = BackupRead.Valid(testBackup("Arshad", "Mira"))
        planFor(roomForNew = 0, OWNER.id, MIRA.id)
        val vm = atRestorePick(OWNER)

        vm.onRestoreRow(1)
        assertEquals(1, vm.pick.choosing)
        vm.onRestoreChoice(RestoreChoice.Skip)

        assertNull(vm.pick.choosing)
        assertEquals(listOf(RestoreChoice.Replace(OWNER.id), RestoreChoice.Skip), vm.pick.rows.map { it.choice })
        assertEquals(1, vm.pick.restoreCount)
    }

    @Test
    fun aSheetRefusesAnotherProfilesReplaceAndAddAsNewWithNoRoom() = runTest(dispatcher) {
        backups.reads[PICKED] = BackupRead.Valid(testBackup("Arshad", "Mira", "Guests"))
        planFor(roomForNew = 1, OWNER.id, MIRA.id, null)
        val vm = atRestorePick(OWNER)
        vm.onRestoreRow(1)

        assertFalse(vm.pick.canAddAsNew(1))
        vm.onRestoreChoice(RestoreChoice.AddAsNew)
        vm.onRestoreChoice(RestoreChoice.Replace(YURI.id))

        assertEquals(1, vm.pick.choosing)
        assertEquals(RestoreChoice.Replace(MIRA.id), vm.pick.rows[1].choice)
    }

    @Test
    fun aRowLeftOutMakesRoomForAnotherToBeAdded() = runTest(dispatcher) {
        backups.reads[PICKED] = BackupRead.Valid(testBackup("Arshad", "Guests", "Zainab"))
        planFor(roomForNew = 1, OWNER.id, null, null)
        val vm = atRestorePick(OWNER)
        vm.onRestoreRow(1)
        vm.onRestoreChoice(RestoreChoice.Skip)

        vm.onRestoreRow(2)
        vm.onRestoreChoice(RestoreChoice.AddAsNew)

        assertEquals(RestoreChoice.AddAsNew, vm.pick.rows[2].choice)
    }

    @Test
    fun backClosesASheetThenLeavesThePick() = runTest(dispatcher) {
        backups.reads[PICKED] = BackupRead.Valid(testBackup("Arshad"))
        planFor(roomForNew = 0, OWNER.id)
        val vm = atRestorePick(OWNER)
        vm.onRestoreRow(0)

        vm.onBack()
        assertNull(vm.pick.choosing)
        vm.onBack()

        assertEquals(BackupScreen.Page, vm.screen)
    }

    @Test
    fun aSwipedChoiceSheetClosesToThePickAndASwipedExportSheetToThePage() = runTest(dispatcher) {
        backups.reads[PICKED] = BackupRead.Valid(testBackup("Arshad"))
        planFor(roomForNew = 0, OWNER.id)
        val vm = atRestorePick(OWNER)
        vm.onRestoreRow(0)

        vm.onDismiss()
        assertNull(vm.pick.choosing)
        vm.onDismiss()
        assertNull(vm.pick.choosing)
        vm.onBack()
        vm.onExport()
        advanceUntilIdle()
        vm.onExportChoose()
        vm.onDismiss()

        assertEquals(BackupScreen.Page, vm.screen)
    }

    @Test
    fun touchBackLeavesTheOwnersLockWithPressesEntered() = runTest(dispatcher) {
        val vm = openAs(OWNER_LOCKED)
        vm.onExport()
        advanceUntilIdle()
        vm.onPress(SEQUENCE.first())
        assertEquals(1, (vm.screen as BackupScreen.OwnerGate).entry.entered)

        vm.onEntryLeave()

        assertEquals(BackupScreen.Page, vm.screen)
        assertTrue(locks.tries.isEmpty())
    }

    @Test
    fun touchBackWaitsWhileATryIsChecked() = runTest(dispatcher) {
        val vm = openAs(OWNER_LOCKED)
        vm.onExport()
        advanceUntilIdle()
        locks.unlockGate = CompletableDeferred()
        enter(vm, SEQUENCE)
        runCurrent()

        vm.onEntryLeave()
        assertTrue(vm.screen is BackupScreen.OwnerGate)
        locks.unlockGate!!.complete(Unit)
        advanceUntilIdle()

        assertEquals(BackupScreen.ExportSheet(), vm.screen)
    }

    @Test
    fun allProfilesWhileChoosingGoesBackToAllProfilesWithoutExporting() = runTest(dispatcher) {
        val vm = atExportSheet(OWNER)
        vm.onExportChoose()

        vm.onExportAll()

        assertEquals(BackupScreen.ExportSheet(), vm.screen)
        assertEquals(0, backups.exports)
    }

    @Test
    fun nothingChosenRestoresNothing() = runTest(dispatcher) {
        backups.reads[PICKED] = BackupRead.Valid(testBackup("Arshad"))
        planFor(roomForNew = 0, OWNER.id)
        val vm = atRestorePick(OWNER)
        vm.onRestoreRow(0)
        vm.onRestoreChoice(RestoreChoice.Skip)

        vm.onRestore()
        advanceUntilIdle()

        assertEquals(emptyList<Any>(), backups.restored)
        assertEquals(0, playbackEnds)
    }

    @Test
    fun backDuringAReadShowsNothingWhenItLands() = runTest(dispatcher) {
        backups.reads[PICKED] = BackupRead.Valid(testBackup("Arshad"))
        val vm = openAs(OWNER)
        backups.openGate = CompletableDeferred()
        vm.onBackupFilePicked(PICKED, FILE_NAME)
        runCurrent()

        vm.onBack()
        backups.openGate!!.complete(Unit)
        advanceUntilIdle()
        vm.onRestore()
        advanceUntilIdle()

        assertEquals(BackupScreen.Page, vm.screen)
        assertEquals(emptyList<Any>(), backups.restored)
    }

    @Test
    fun aRefusedFileSaysWhyAndBackReturnsToThePage() = runTest(dispatcher) {
        backups.reads[PICKED] = BackupRead.Refused(BackupRefusal.NotABackup)
        val vm = openAs(OWNER)
        vm.onBackupFilePicked(PICKED, "subscriptions.csv")
        advanceUntilIdle()
        assertEquals(BackupScreen.Refused("subscriptions.csv", BackupRefusal.NotABackup), vm.screen)

        vm.onBack()

        assertEquals(BackupScreen.Page, vm.screen)
    }

    @Test
    fun aDeviceThatCantBeReadFailsTheRestoreAndTryAgainReadsTheFileAgain() = runTest(dispatcher) {
        backups.reads[PICKED] = BackupRead.Valid(testBackup("Arshad"))
        backups.plan = { null }
        val vm = atRestorePick(OWNER)
        assertEquals(BackupScreen.RestoreFailed(FILE_NAME), vm.screen)

        planFor(roomForNew = 0, OWNER.id)
        vm.onRestore()
        advanceUntilIdle()

        assertEquals(listOf(RestoreChoice.Replace(OWNER.id)), vm.pick.rows.map { it.choice })
        assertEquals(emptyList<Any>(), backups.restored)
    }

    @Test
    fun aRestoreEndsPlaybackFirstThenHandsItsResultsToTheGate() = runTest(dispatcher) {
        val backup = testBackup("Arshad", "Mira", exportedAtMillis = AUG_30)
        backups.reads[PICKED] = BackupRead.Valid(backup)
        planFor(roomForNew = 0, OWNER.id, MIRA.id)
        val result = RestoreResult.Restored(ownerId = OWNER.id)
        backups.restoreResult = result
        val vm = atRestorePick(OWNER)
        backups.restoreGate = CompletableDeferred()

        vm.onRestore()
        runCurrent()
        assertTrue(vm.pick.restoring)
        assertEquals(1, playbackEnds) // ended before the restore replaces anything (M5-D3)
        assertEquals(emptyList<Any>(), backups.restored)
        backups.restoreGate!!.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(backup), backups.restored)
        assertEquals(listOf(listOf(RestoreChoice.Replace(OWNER.id), RestoreChoice.Replace(MIRA.id))), backups.restoredChoices)
        assertEquals(listOf(OWNER.id to RestoredBackup(FILE_NAME, AUG_30, result)), handedOver)
    }

    @Test
    fun backIsIgnoredWhileARestoreRuns() = runTest(dispatcher) {
        backups.reads[PICKED] = BackupRead.Valid(testBackup("Arshad"))
        planFor(roomForNew = 0, OWNER.id)
        val vm = atRestorePick(OWNER)
        backups.restoreGate = CompletableDeferred()
        vm.onRestore()
        runCurrent()

        vm.onBack()
        vm.onRestoreRow(0)

        assertTrue(vm.pick.restoring)
        assertNull(vm.pick.choosing)
        backups.restoreGate!!.complete(Unit)
    }

    @Test
    fun aLockedOwnersRestoreAsksForTheLockBeforeTheFileIsRead() = runTest(dispatcher) {
        backups.reads[PICKED] = BackupRead.Valid(testBackup("Arshad"))
        val vm = openAs(OWNER_LOCKED)

        vm.onBackupFilePicked(PICKED, FILE_NAME)
        advanceUntilIdle()
        assertEquals(
            BackupScreen.OwnerGate(BackupAction.Read(PICKED, FILE_NAME), LockKind.Sequence, returnTo = BackupScreen.Page),
            vm.screen,
        )
        vm.onBack()

        assertEquals(BackupScreen.Page, vm.screen)
        assertEquals(emptyList<Any>(), backups.restored)
    }

    @Test
    fun aLockedOwnersRestoreRunsOnTheOnePassItsFlowWasGiven() = runTest(dispatcher) {
        backups.reads[PICKED] = BackupRead.Valid(testBackup("Arshad"))
        planFor(roomForNew = 0, OWNER.id)
        val vm = openAs(OWNER_LOCKED)
        vm.onBackupFilePicked(PICKED, FILE_NAME)
        advanceUntilIdle()

        enter(vm, SEQUENCE)
        advanceUntilIdle()
        vm.onRestore()
        advanceUntilIdle()

        assertEquals(1, backups.restored.size)
        assertEquals(1, handedOver.size)
        assertEquals(listOf(OWNER.id), locks.tries)
    }

    @Test
    fun anotherFileChosenAfterARefusalIsReadOnTheSamePass() = runTest(dispatcher) {
        val other: Uri = Uri.parse("content://picker/other.json")
        backups.reads[PICKED] = BackupRead.Refused(BackupRefusal.Damaged)
        backups.reads[other] = BackupRead.Valid(testBackup("Arshad"))
        val vm = openAs(OWNER_LOCKED)
        vm.onBackupFilePicked(PICKED, FILE_NAME)
        advanceUntilIdle()
        enter(vm, SEQUENCE)
        advanceUntilIdle()

        vm.onBackupFilePicked(other, "other.json")
        advanceUntilIdle()

        assertEquals("other.json", vm.pick.fileName)
        assertEquals(listOf(OWNER.id), locks.tries)
    }

    @Test
    fun aFailedRestoreSaysSoAndTryAgainRestoresTheSameChoices() = runTest(dispatcher) {
        val backup = testBackup("Arshad", "Mira")
        backups.reads[PICKED] = BackupRead.Valid(backup)
        planFor(roomForNew = 0, OWNER.id, MIRA.id)
        backups.restoreResult = RestoreResult.Failed
        val vm = atRestorePick(OWNER)
        vm.onRestoreRow(1)
        vm.onRestoreChoice(RestoreChoice.Skip)
        vm.onRestore()
        advanceUntilIdle()
        assertEquals(BackupScreen.RestoreFailed(FILE_NAME), vm.screen)
        assertEquals(emptyList<Any>(), handedOver)

        backups.restoreResult = RestoreResult.Restored(ownerId = OWNER.id)
        vm.onRestore()
        advanceUntilIdle()

        val chosen = listOf(RestoreChoice.Replace(OWNER.id), RestoreChoice.Skip)
        assertEquals(listOf(chosen, chosen), backups.restoredChoices)
        assertEquals(1, handedOver.size)
    }

    @Test
    fun anImportAddsChannelsForAnyoneIntoTheirProfileAndSaysHowMany() = runTest(dispatcher) {
        backups.importResult = ImportResult.Imported(added = 23, alreadySubscribed = 4)
        val vm = openAs(YURI)

        vm.onImportFilePicked(PICKED, "subscriptions.csv")
        advanceUntilIdle()

        assertEquals(BackupToast.Imported(added = 23, alreadySubscribed = 4), vm.uiState.value.toast)
        assertEquals(listOf(PICKED), backups.imported)
        assertEquals(listOf(YURI.id), backups.importedInto)
    }

    @Test
    fun aBackupPickedForThisProfileOpensItsRestorePick() = runTest(dispatcher) {
        backups.reads[PICKED] = BackupRead.Valid(testBackup("Arshad", "Mira", exportedAtMillis = AUG_30))
        picks.waiting.value = pickOf(PickKind.Backup, OWNER.id, FILE_NAME)

        val vm = openAs(OWNER)

        assertEquals(FILE_NAME, vm.pick.fileName)
        assertNull(picks.waiting.value)
    }

    @Test
    fun anImportPickWaitsUntilItsProfileHasLoaded() = runTest(dispatcher) {
        backups.importResult = ImportResult.Imported(added = 2, alreadySubscribed = 0)
        picks.waiting.value = pickOf(PickKind.Import, YURI.id, "subscriptions.csv")
        newViewModel(YURI.id)
        advanceUntilIdle()
        assertEquals(emptyList<Uri>(), backups.imported)

        profiles.rows.value = listOf(OWNER, MIRA, YURI)
        advanceUntilIdle()

        assertEquals(listOf(PICKED), backups.imported)
        assertEquals(listOf(YURI.id), backups.importedInto)
    }

    @Test
    fun aPickForAnotherProfileIsNotTaken() = runTest(dispatcher) {
        val miras = pickOf(PickKind.Import, MIRA.id, "subscriptions.csv")
        picks.waiting.value = miras

        openAs(YURI)

        assertEquals(emptyList<Uri>(), backups.imported)
        assertEquals(miras, picks.waiting.value)
    }

    @Test
    fun aFileWithNoChannelsIsRefusedAsSuch() = runTest(dispatcher) {
        backups.importResult = ImportResult.Imported(added = 0, alreadySubscribed = 0)
        val vm = openAs(YURI)

        vm.onImportFilePicked(PICKED, "watch-history.html")
        advanceUntilIdle()

        assertEquals(BackupScreen.ImportRefused("watch-history.html", ImportProblem.NoChannels), vm.screen)
        assertNull(vm.uiState.value.toast)
    }

    @Test
    fun aFileWhoseChannelsWereAllThereAlreadySaysSo() = runTest(dispatcher) {
        backups.importResult = ImportResult.Imported(added = 0, alreadySubscribed = 27)
        val vm = openAs(YURI)

        vm.onImportFilePicked(PICKED, "subscriptions.csv")
        advanceUntilIdle()

        assertEquals(BackupToast.Imported(added = 0, alreadySubscribed = 27), vm.uiState.value.toast)
        assertEquals(BackupScreen.Page, vm.screen)
    }

    @Test
    fun anImportThatCantBeReadOrSavedSaysWhy() = runTest(dispatcher) {
        val vm = openAs(YURI)
        val outcomes = mapOf(
            ImportResult.Unreadable to ImportProblem.Unreadable,
            ImportResult.TooLarge to ImportProblem.TooLarge,
            ImportResult.Failed to ImportProblem.Failed,
        )

        val shown = outcomes.keys.map { result ->
            backups.importResult = result
            vm.onImportFilePicked(PICKED, "subscriptions.csv")
            advanceUntilIdle()
            (vm.screen as BackupScreen.ImportRefused).problem
        }

        assertEquals(outcomes.values.toList(), shown)
    }

    private val BackupViewModel.screen: BackupScreen get() = uiState.value.screen

    /** [me]'s Settings over Arshad (owner), Mira and Yuri; the owner's lock as [me] has it. */
    private fun TestScope.openAs(me: Profile): BackupViewModel {
        profiles.rows.value = listOf(if (me == OWNER_LOCKED) OWNER_LOCKED else OWNER, MIRA, YURI)
        if (me == OWNER_LOCKED) locks.locks[OWNER.id] = LockSecret.Sequence(SEQUENCE)
        return newViewModel(me.id).also { advanceUntilIdle() }
    }

    private fun TestScope.newViewModel(profileId: Long): BackupViewModel = BackupViewModel(
        backups = backups,
        profileStore = profiles,
        locks = locks,
        playback = { playbackEnds++ },
        handover = { ownerId, backup -> handedOver += ownerId to backup },
        picks = picks,
        profileId = profileId,
        timeSource = testScheduler.timeSource,
    )

    private fun TestScope.atExportSheet(me: Profile): BackupViewModel = openAs(me).also { vm ->
        vm.onExport()
        advanceUntilIdle()
    }

    /** [me]'s Settings with [PICKED] read; its "Restore which profiles?" when the plan was read. */
    private fun TestScope.atRestorePick(me: Profile): BackupViewModel = openAs(me).also { vm ->
        vm.onBackupFilePicked(PICKED, FILE_NAME)
        advanceUntilIdle()
    }

    /** [me]'s Settings with [NEW] (listed above [UNREAD]) read from the list: its "Restore which profiles?". */
    private fun TestScope.atListedPick(me: Profile): BackupViewModel {
        backups.saved.clear()
        backups.saved += listOf(NEW, UNREAD)
        backups.reads[NEW.uri] = BackupRead.Valid(testBackup("Arshad", "Mira"))
        return openAs(me).also { vm ->
            vm.onSavedBackup(vm.uiState.value.saved.first())
            advanceUntilIdle()
        }
    }

    /** Restores here have [roomForNew] free places; the backup's profiles replace [replaces], in order. */
    private fun planFor(roomForNew: Int, vararg replaces: Long?) {
        backups.plan = { backup ->
            RestorePlan(
                firstLaunch = false,
                roomForNew = roomForNew,
                profiles = backup.profiles.mapIndexed { index, profile -> PlannedProfile(profile, replaces[index]) },
            )
        }
    }

    private val BackupViewModel.pick: BackupScreen.RestorePick get() = screen as BackupScreen.RestorePick

    private fun enter(vm: BackupViewModel, sequence: List<Press>) {
        sequence.forEach(vm::onPress)
        vm.onSubmitSequence()
    }

    private companion object {
        val OWNER = Profile(1, "Arshad", ProfileTint.Amber, isOwner = true, lock = null)
        val OWNER_LOCKED = OWNER.copy(lock = LockKind.Sequence)
        val MIRA = Profile(2, "Mira", ProfileTint.Rose, isOwner = false, lock = null)
        val YURI = Profile(3, "Yuri", ProfileTint.Teal, isOwner = false, lock = null)
        val SEQUENCE = listOf(Press.Up, Press.Up, Press.Down, Press.Left, Press.Right, Press.Down)
        val WRONG_SEQUENCE = listOf(Press.Left, Press.Left, Press.Left, Press.Left, Press.Left, Press.Left)
        const val AUG_30 = 1_788_048_000_000L
        const val FILE_NAME = "Ayn-backup-2026-08-30.json"
        val EXPORTED = testBackup("Arshad", "Mira", "Yuri").profiles
        val NEWER = SavedBackup(Uri.parse("content://downloads/3"), "Ayn-backup-2026-10-05 (1).json", savedAtMillis = 3)
        val NEW = SavedBackup(Uri.parse("content://downloads/1"), "Ayn-backup-2026-10-05.json", savedAtMillis = 2)
        val UNREAD = SavedBackup(Uri.parse("content://downloads/2"), "Ayn-backup-2026-08-30.json", savedAtMillis = 1)
        val PICKED: Uri = Uri.parse("content://picker/backup.json")

        fun pickOf(kind: PickKind, profileId: Long, fileName: String): PickedFile = PickedFile(PickRequest(kind, profileId), PICKED, fileName)
    }
}

/** The gate's side of [SettingsPicks]: it offers the open shell [waiting]. */
private class FakeSettingsPicks : SettingsPicks {
    val waiting = MutableStateFlow<PickedFile?>(null)
    override val settingsPick: StateFlow<PickedFile?> = waiting

    override fun takeSettingsPick(pick: PickedFile): Boolean = waiting.compareAndSet(pick, null)
}
