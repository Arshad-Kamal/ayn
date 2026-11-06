package com.arshad.ayn.feature.settings

import com.arshad.ayn.core.lock.FakeProfileLocks
import com.arshad.ayn.core.lock.LockKind
import com.arshad.ayn.core.lock.LockSecret
import com.arshad.ayn.core.lock.Press
import com.arshad.ayn.core.lock.UnlockResult
import com.arshad.ayn.core.store.ApprovedVideo
import com.arshad.ayn.core.store.Bedtime
import com.arshad.ayn.core.store.FakeFilterStore
import com.arshad.ayn.core.store.FakeProfileStore
import com.arshad.ayn.core.store.ProfileRestrictions
import com.arshad.ayn.core.store.ProfileRules
import com.arshad.ayn.core.store.StoredRule
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.ProfileContents
import com.arshad.ayn.core.store.ProfileSession
import com.arshad.ayn.core.store.ProfileTint
import com.arshad.ayn.feature.profiles.UnlockEntry
import com.arshad.ayn.feature.profiles.UnlockMessage
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.DayOfWeek

/**
 * Settings › Profiles (§ B3p, M5-D15) over in-memory stores: who sees which rows, the owner gate
 * before every owner-only write while the owner is locked, adding, removing and resetting, and
 * each person's own name, colour and lock (set twice, changed or removed after the current one).
 */
class ProfilesViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val profiles = FakeProfileStore()
    private val locks = FakeProfileLocks().apply { ownerId = OWNER.id }
    private val session = ProfileSession()
    private val filters = FakeFilterStore()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun theOwnerSeesEveryProfileWithItsCountsAndOneNotCountedYetHoldsNothing() = runTest(dispatcher) {
        profiles.contentsById.value = mapOf(OWNER.id to ARSHAD_COUNTS, MIRA.id to MIRA_COUNTS)

        val vm = openAs(OWNER)

        assertEquals(
            listOf(
                ProfileRow(OWNER, ARSHAD_COUNTS),
                ProfileRow(MIRA, MIRA_COUNTS),
                ProfileRow(YURI, ProfileContents(subscriptionCount = 0, playlistCount = 0, historyCount = 0)),
            ),
            vm.uiState.value.rows,
        )
        assertTrue(vm.uiState.value.canAdd)
    }

    @Test
    fun someoneElseSeesOnlyTheirOwnProfile() = runTest(dispatcher) {
        val vm = openAs(YURI)

        assertEquals(listOf(YURI), vm.uiState.value.rows.map { it.profile })
        assertFalse(vm.uiState.value.canAdd)
        assertFalse(vm.uiState.value.ownerHasNoLock)
    }

    @Test
    fun someoneElsesPageNamesTheOwnerAfterARename() = runTest(dispatcher) {
        val vm = openAs(YURI)
        assertEquals("Arshad", vm.uiState.value.ownerName)

        profiles.rows.value = listOf(OWNER.copy(name = "Abba"), MIRA, YURI)
        advanceUntilIdle()

        assertEquals("Abba", vm.uiState.value.ownerName)
    }

    @Test
    fun theOwnersPageWarnsOnlyWhileTheOwnerHasNoLock() = runTest(dispatcher) {
        val vm = openAs(OWNER)
        assertTrue(vm.uiState.value.ownerHasNoLock)

        profiles.rows.value = listOf(OWNER_LOCKED, MIRA, YURI)
        advanceUntilIdle()

        assertFalse(vm.uiState.value.ownerHasNoLock)
    }

    @Test
    fun aFullDeviceCantAddAProfile() = runTest(dispatcher) {
        profiles.rows.value = listOf(OWNER, MIRA, YURI, profile(4, "Zaid"), profile(5, "Huda"))
        val vm = openAs(OWNER)

        vm.onAddProfile()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.canAdd)
        assertEquals(ProfilesScreen.Page, vm.screen)
    }

    @Test
    fun anUnlockedOwnerAddsAProfileWithoutTheGate() = runTest(dispatcher) {
        val vm = openAs(OWNER)

        vm.addStandard()
        advanceUntilIdle()
        assertEquals(ProfilesScreen.AddForm(tint = ProfileTint.Blue), vm.screen)
        vm.onAddNameChange("Zaid")
        vm.onAddTintChange(ProfileTint.Violet)
        vm.onAddConfirm()
        advanceUntilIdle()

        assertEquals(ProfilesScreen.Page, vm.screen)
        assertEquals(
            Profile(4, "Zaid", ProfileTint.Violet, isOwner = false, lock = null),
            vm.uiState.value.rows.last().profile,
        )
    }

    @Test
    fun aLockedOwnerEntersTheirLockBeforeTheAddFormAndWrongTriesCountOnTheOwner() = runTest(dispatcher) {
        val vm = openAs(OWNER_LOCKED)

        vm.addStandard()
        advanceUntilIdle()
        assertEquals(ProfilesScreen.OwnerGate(OwnerAction.Add, LockKind.Sequence), vm.screen)
        enter(vm, WRONG_SEQUENCE)
        advanceUntilIdle()
        assertEquals(UnlockMessage.Wrong(triesBeforeWait = 4), vm.entry.message)
        enter(vm, SEQUENCE)
        advanceUntilIdle()

        assertEquals(ProfilesScreen.AddForm(tint = ProfileTint.Blue), vm.screen)
        assertEquals(listOf(OWNER.id, OWNER.id), locks.tries)
    }

    @Test
    fun theOwnerGateShowsAWaitTheOwnerIsAlreadyIn() = runTest(dispatcher) {
        locks.waits[OWNER.id] = 47.seconds
        val vm = openAs(OWNER_LOCKED)

        vm.addStandard()
        runCurrent()

        assertEquals(47.seconds, vm.entry.wait)
    }

    @Test
    fun reopeningTheAddFormAsksTheGateAgain() = runTest(dispatcher) {
        val vm = openAs(OWNER_LOCKED)
        vm.addStandard()
        advanceUntilIdle()
        enter(vm, SEQUENCE)
        advanceUntilIdle()

        vm.onBack()
        vm.addStandard()
        advanceUntilIdle()

        assertTrue(vm.screen is ProfilesScreen.OwnerGate)
    }

    @Test
    fun aNameAnotherProfileHasIsRefusedAndABlankOneAskedFor() = runTest(dispatcher) {
        val vm = openAs(OWNER)
        vm.addStandard()
        advanceUntilIdle()

        vm.onAddConfirm()
        advanceUntilIdle()
        assertEquals(NameProblem.Missing, (vm.screen as ProfilesScreen.AddForm).problem)
        vm.onAddNameChange("mira")
        vm.onAddConfirm()
        advanceUntilIdle()

        assertEquals(NameProblem.Taken, (vm.screen as ProfilesScreen.AddForm).problem)
        assertEquals(3, profiles.rows.value!!.size)
    }

    @Test
    fun removingAProfileAsksTheLockedOwnerAfterTheConfirm() = runTest(dispatcher) {
        val vm = openAs(OWNER_LOCKED)
        vm.onOpenProfile(MIRA.id)
        vm.onRemoveProfile(MIRA.id)
        assertEquals(ProfilesScreen.ConfirmRemove(MIRA.id), vm.screen)

        vm.onConfirmRemove()
        advanceUntilIdle()
        assertEquals(ProfilesScreen.OwnerGate(OwnerAction.Remove(MIRA.id), LockKind.Sequence), vm.screen)
        enter(vm, SEQUENCE)
        advanceUntilIdle()

        assertEquals(ProfilesScreen.Page, vm.screen)
        assertEquals(listOf(OWNER_LOCKED, YURI), profiles.rows.value)
    }

    @Test
    fun backFromTheOwnerGateReturnsToTheConfirmAndRemovesNothing() = runTest(dispatcher) {
        val vm = openAs(OWNER_LOCKED)
        vm.onOpenProfile(MIRA.id)
        vm.onRemoveProfile(MIRA.id)
        vm.onConfirmRemove()
        advanceUntilIdle()

        vm.onBack()

        assertEquals(ProfilesScreen.ConfirmRemove(MIRA.id), vm.screen)
        assertEquals(3, profiles.rows.value!!.size)
    }

    @Test
    fun theOwnerResetsAnotherProfilesLock() = runTest(dispatcher) {
        val vm = openAs(OWNER)
        vm.onOpenProfile(MIRA.id)

        vm.onResetLock(MIRA.id)
        advanceUntilIdle()

        assertEquals(ProfilesScreen.Page, vm.screen)
        assertNull(locks.locks[MIRA.id])
    }

    @Test
    fun someoneElseCantOpenAnotherProfileOrAddOne() = runTest(dispatcher) {
        val vm = openAs(YURI)

        vm.onOpenProfile(MIRA.id)
        vm.onAddProfile()
        advanceUntilIdle()

        assertEquals(ProfilesScreen.Page, vm.screen)
    }

    @Test
    fun aSheetWhoseProfileWentAwayCloses() = runTest(dispatcher) {
        val vm = openAs(OWNER)
        vm.onOpenProfile(MIRA.id)

        profiles.rows.value = listOf(OWNER, YURI)
        advanceUntilIdle()

        assertEquals(ProfilesScreen.Page, vm.screen)
    }

    @Test
    fun aNameIsEditedInItsRowAndSaved() = runTest(dispatcher) {
        val vm = openAs(YURI)

        vm.onRename()
        assertEquals(ProfilesScreen.Rename("Yuri"), vm.screen)
        vm.onRenameChange("Yuri A")
        vm.onRenameDone()
        advanceUntilIdle()

        assertEquals(ProfilesScreen.Page, vm.screen)
        assertEquals("Yuri A", vm.uiState.value.me!!.name)
    }

    @Test
    fun aNameAnotherProfileHasIsRefusedInTheRow() = runTest(dispatcher) {
        val vm = openAs(YURI)
        vm.onRename()
        vm.onRenameChange("MIRA")

        vm.onRenameDone()
        advanceUntilIdle()

        assertEquals(ProfilesScreen.Rename("MIRA", problem = NameProblem.Taken), vm.screen)
    }

    @Test
    fun theOwnersOwnEditsReturnToTheirSheet() = runTest(dispatcher) {
        val vm = openAs(OWNER)
        vm.onOpenProfile(OWNER.id)
        vm.onRename()
        vm.onRenameChange("Arshad K")

        vm.onRenameDone()
        advanceUntilIdle()

        assertEquals(ProfilesScreen.OwnSheet, vm.screen)
    }

    @Test
    fun aColourPickedFromTheSheetIsSaved() = runTest(dispatcher) {
        val vm = openAs(YURI)
        vm.onColour()

        vm.onTintPicked(ProfileTint.Rose)
        advanceUntilIdle()

        assertEquals(ProfilesScreen.Page, vm.screen)
        assertEquals(ProfileTint.Rose, vm.uiState.value.me!!.tint)
    }

    @Test
    fun aFirstLockIsChosenEnteredTwiceAndSaved() = runTest(dispatcher) {
        val vm = openAs(YURI)
        vm.onLock()
        advanceUntilIdle()
        assertEquals(ProfilesScreen.ChooseKind, vm.screen)

        vm.onLockKind(LockKind.Pin)
        "2461".forEach(vm::onDigit)
        assertTrue(vm.entry.confirming)
        "2461".forEach(vm::onDigit)
        advanceUntilIdle()

        assertEquals(ProfilesScreen.Page, vm.screen)
        assertEquals(ProfilesToast.LockSaved, vm.uiState.value.toast)
        assertNotNull(locks.locks[YURI.id]) // a profile with no lock would open with anything
        assertTrue(locks.unlock(YURI.id, LockSecret.Pin("2461")) is UnlockResult.Opened)
        vm.onToastShown()
        assertNull(vm.uiState.value.toast)
    }

    @Test
    fun aNewLockThatDoesntMatchStartsOverAndSavesNothing() = runTest(dispatcher) {
        val vm = openAs(YURI)
        vm.onLock()
        advanceUntilIdle()
        vm.onLockKind(LockKind.Sequence)
        enter(vm, SEQUENCE)

        enter(vm, WRONG_SEQUENCE)
        advanceUntilIdle()

        assertEquals(UnlockMessage.Mismatch, vm.entry.message)
        assertFalse(vm.entry.confirming)
        assertNull(locks.locks[YURI.id])
    }

    @Test
    fun backFromANewLockReturnsToTheChoiceStillAbleToSave() = runTest(dispatcher) {
        val vm = openAs(YURI)
        vm.onLock()
        advanceUntilIdle()
        vm.onLockKind(LockKind.Pin)

        vm.onBack()
        assertEquals(ProfilesScreen.ChooseKind, vm.screen)
        vm.onLockKind(LockKind.Sequence)
        enter(vm, SEQUENCE)
        enter(vm, SEQUENCE)
        advanceUntilIdle()

        assertNotNull(locks.locks[YURI.id])
        assertTrue(locks.unlock(YURI.id, LockSecret.Sequence(SEQUENCE)) is UnlockResult.Opened)
    }

    @Test
    fun changingALockAsksForTheCurrentOneFirstCountingWrongTries() = runTest(dispatcher) {
        val vm = openAs(MIRA)
        vm.onLock()
        advanceUntilIdle()
        assertEquals(ProfilesScreen.LockMenu, vm.screen)

        vm.onChangeLock(LockChange.Change)
        advanceUntilIdle()
        assertEquals(ProfilesScreen.CurrentLock(LockChange.Change, LockKind.Pin), vm.screen)
        "9999".forEach(vm::onDigit)
        advanceUntilIdle()
        "2461".forEach(vm::onDigit)
        advanceUntilIdle()
        assertEquals(ProfilesScreen.ChooseKind, vm.screen)
        vm.onLockKind(LockKind.Sequence)
        enter(vm, SEQUENCE)
        enter(vm, SEQUENCE)
        advanceUntilIdle()

        assertEquals(listOf(MIRA.id, MIRA.id), locks.tries.take(2))
        assertTrue(locks.unlock(MIRA.id, LockSecret.Sequence(SEQUENCE)) is UnlockResult.Opened)
    }

    @Test
    fun removingALockAfterTheCurrentOneSaysSo() = runTest(dispatcher) {
        val vm = openAs(MIRA)
        vm.onLock()
        advanceUntilIdle()
        vm.onChangeLock(LockChange.Remove)
        advanceUntilIdle()

        "2461".forEach(vm::onDigit)
        advanceUntilIdle()

        assertEquals(ProfilesScreen.Page, vm.screen)
        assertEquals(ProfilesToast.LockRemoved, vm.uiState.value.toast)
        assertNull(locks.locks[MIRA.id])
    }

    /** KP-D14: the store keeps the owner's lock while a kids profile exists; no Removed notice. */
    @Test
    fun theOwnersLockRemovalRefusedForAKidsProfileSaysNothingRemoved() = runTest(dispatcher) {
        locks.kids += YURI.id
        val vm = openAs(OWNER_LOCKED)
        vm.onOpenProfile(OWNER.id)
        vm.onLock()
        advanceUntilIdle()
        vm.onChangeLock(LockChange.Remove)
        advanceUntilIdle()

        enter(vm, SEQUENCE)
        advanceUntilIdle()

        assertEquals(ProfilesScreen.OwnSheet, vm.screen)
        assertNull(vm.uiState.value.toast)
        assertNotNull(locks.locks[OWNER.id])
    }

    @Test
    fun backFromTheCurrentLockErasesThenReturnsToTheMenu() = runTest(dispatcher) {
        val vm = openAs(MIRA)
        vm.onLock()
        advanceUntilIdle()
        vm.onChangeLock(LockChange.Remove)
        advanceUntilIdle()
        vm.onDigit('2')

        vm.onBack()
        assertEquals(0, vm.entry.entered)
        vm.onBack()

        assertEquals(ProfilesScreen.LockMenu, vm.screen)
        assertNotNull(locks.locks[MIRA.id])
    }

    @Test
    fun aProfileAddedElsewhereWhileTheFormIsOpenLeavesNoRoom() = runTest(dispatcher) {
        profiles.rows.value = listOf(OWNER, MIRA, YURI, profile(4, "Zaid"))
        val vm = openAs(OWNER)
        vm.addStandard()
        advanceUntilIdle()
        vm.onAddNameChange("Huda")
        profiles.rows.value = profiles.rows.value!! + profile(5, "Sara")

        vm.onAddConfirm()
        advanceUntilIdle()

        assertEquals(NameProblem.Full, (vm.screen as ProfilesScreen.AddForm).problem)
    }

    @Test
    fun backDuringTheOwnersStatusReadMeansTheRemoveNeverStarts() = runTest(dispatcher) {
        val vm = openAs(OWNER_LOCKED)
        vm.onOpenProfile(MIRA.id)
        vm.onRemoveProfile(MIRA.id)
        locks.statusGate = CompletableDeferred()
        vm.onConfirmRemove()
        runCurrent()

        vm.onBack()
        locks.statusGate!!.complete(Unit)
        advanceUntilIdle()

        assertEquals(ProfilesScreen.ProfileSheet(MIRA.id), vm.screen)
        assertEquals(3, profiles.rows.value!!.size)
    }

    @Test
    fun anOwnerCheckInFlightHoldsBackAndStillRemoves() = runTest(dispatcher) {
        val vm = openAs(OWNER_LOCKED)
        vm.onOpenProfile(MIRA.id)
        vm.onRemoveProfile(MIRA.id)
        vm.onConfirmRemove()
        advanceUntilIdle()
        locks.unlockGate = CompletableDeferred()
        enter(vm, SEQUENCE)
        runCurrent()

        vm.onBack()
        assertTrue(vm.screen is ProfilesScreen.OwnerGate)
        locks.unlockGate!!.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(OWNER_LOCKED, YURI), profiles.rows.value)
    }

    @Test
    fun aConfirmWhoseProfileWentAwayCloses() = runTest(dispatcher) {
        val vm = openAs(OWNER)
        vm.onOpenProfile(MIRA.id)
        vm.onRemoveProfile(MIRA.id)

        profiles.rows.value = listOf(OWNER, YURI)
        advanceUntilIdle()

        assertEquals(ProfilesScreen.Page, vm.screen)
    }

    @Test
    fun aRowOpensNothingWhileAnotherScreenShows() = runTest(dispatcher) {
        val vm = openAs(OWNER)
        vm.onOpenProfile(MIRA.id)

        vm.onOpenProfile(YURI.id)

        assertEquals(ProfilesScreen.ProfileSheet(MIRA.id), vm.screen)
    }

    @Test
    fun backStepsUpOneLevelFromEachOwnerScreen() = runTest(dispatcher) {
        val vm = openAs(OWNER_LOCKED)
        vm.onOpenProfile(MIRA.id)
        vm.onResetLock(MIRA.id)
        advanceUntilIdle()
        assertEquals(ProfilesScreen.OwnerGate(OwnerAction.ResetLock(MIRA.id), LockKind.Sequence), vm.screen)

        vm.onBack()
        assertEquals(ProfilesScreen.ProfileSheet(MIRA.id), vm.screen)
        vm.onBack()
        assertEquals(ProfilesScreen.Page, vm.screen)
        vm.addStandard()
        advanceUntilIdle()
        assertEquals(ProfilesScreen.OwnerGate(OwnerAction.Add, LockKind.Sequence), vm.screen)
        vm.onBack()
        assertEquals(ProfilesScreen.ProfileKind, vm.screen)
        vm.onBack()
        assertEquals(ProfilesScreen.Page, vm.screen)
        vm.onOpenProfile(OWNER.id)
        vm.onColour()
        vm.onBack()
        assertEquals(ProfilesScreen.OwnSheet, vm.screen)
        vm.onBack()

        assertEquals(ProfilesScreen.Page, vm.screen)
        assertNotNull(locks.locks[MIRA.id])
    }

    @Test
    fun touchBackLeavesALockPageOneLevelUpWithPressesEntered() = runTest(dispatcher) {
        val vm = openAs(OWNER_LOCKED)
        vm.onOpenProfile(MIRA.id)
        vm.onRemoveProfile(MIRA.id)
        vm.onConfirmRemove()
        advanceUntilIdle()
        vm.onPress(Press.Up)

        vm.onEntryLeave()
        assertEquals(ProfilesScreen.ConfirmRemove(MIRA.id), vm.screen)
        vm.onDismiss()
        vm.onOpenProfile(OWNER.id)
        vm.onLock()
        advanceUntilIdle()
        vm.onChangeLock(LockChange.Change)
        advanceUntilIdle()
        vm.onPress(Press.Down)
        vm.onEntryLeave()

        assertEquals(ProfilesScreen.LockMenu, vm.screen)
        assertEquals(3, profiles.rows.value!!.size)
    }

    @Test
    fun touchBackWaitsForATryBeingCheckedAndLeavesNothingElse() = runTest(dispatcher) {
        val vm = openAs(OWNER_LOCKED)
        vm.onOpenProfile(MIRA.id)
        vm.onResetLock(MIRA.id)
        advanceUntilIdle()
        locks.unlockGate = CompletableDeferred()
        enter(vm, SEQUENCE)
        runCurrent()

        vm.onEntryLeave()
        assertTrue(vm.screen is ProfilesScreen.OwnerGate)
        locks.unlockGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(ProfilesScreen.Page, vm.screen)
        vm.onEntryLeave()

        assertEquals(ProfilesScreen.Page, vm.screen)
        assertNull(locks.locks[MIRA.id])
    }

    @Test
    fun aSwipedSheetClosesToThePageAndANameAlreadySavingStillSaves() = runTest(dispatcher) {
        val vm = openAs(OWNER)
        vm.onOpenProfile(OWNER.id)
        vm.onColour()

        vm.onDismiss()
        assertEquals(ProfilesScreen.Page, vm.screen)
        vm.onOpenProfile(OWNER.id)
        vm.onRename()
        vm.onRenameChange("Arshad K")
        vm.onDismiss()
        assertEquals(ProfilesScreen.Page, vm.screen)
        vm.onOpenProfile(OWNER.id)
        vm.onRename()
        vm.onRenameChange("Arshad K")
        vm.onRenameDone()
        vm.onDismiss()
        assertEquals(ProfilesScreen.Page, vm.screen)
        advanceUntilIdle()

        assertEquals(ProfilesScreen.Page, vm.screen)
        assertEquals("Arshad K", vm.uiState.value.me!!.name)
    }

    @Test
    fun aNameBeingSavedTakesNoEditsAndHoldsBack() = runTest(dispatcher) {
        val vm = openAs(YURI)
        vm.onRename()
        vm.onRenameChange("Yuri A")
        vm.onRenameDone()

        vm.onRenameChange("Yuri B")
        vm.onBack()
        assertEquals(ProfilesScreen.Rename("Yuri A", saving = true), vm.screen)
        advanceUntilIdle()

        assertEquals("Yuri A", vm.uiState.value.me!!.name)
    }

    @Test
    fun aProfileBeingAddedTakesNoEditsAndHoldsBack() = runTest(dispatcher) {
        val vm = openAs(OWNER)
        vm.addStandard()
        advanceUntilIdle()
        vm.onAddNameChange("Zaid")
        vm.onAddConfirm()

        vm.onAddNameChange("Zaid B")
        vm.onAddTintChange(ProfileTint.Violet)
        vm.onBack()
        assertEquals(ProfilesScreen.AddForm("Zaid", ProfileTint.Blue, saving = true), vm.screen)
        advanceUntilIdle()

        assertEquals(Profile(4, "Zaid", ProfileTint.Blue, isOwner = false, lock = null), profiles.rows.value!!.last())
    }

    @Test
    fun aNameStopsAtTwentyCharacters() = runTest(dispatcher) {
        val vm = openAs(YURI)
        vm.onRename()
        vm.onRenameChange("a".repeat(20))

        vm.onRenameChange("a".repeat(21))

        assertEquals("a".repeat(20), (vm.screen as ProfilesScreen.Rename).name)
    }

    @Test
    fun aBlankNameIsAskedForAndTheOldOneKept() = runTest(dispatcher) {
        val vm = openAs(YURI)
        vm.onRename()
        vm.onRenameChange("  ")

        vm.onRenameDone()
        advanceUntilIdle()

        assertEquals(NameProblem.Missing, (vm.screen as ProfilesScreen.Rename).problem)
        assertEquals("Yuri", vm.uiState.value.me!!.name)
    }

    @Test
    fun backDuringTheLockStatusReadShowsNoLockInput() = runTest(dispatcher) {
        val vm = openAs(MIRA)
        vm.onLock()
        advanceUntilIdle()
        locks.statusGate = CompletableDeferred()
        vm.onChangeLock(LockChange.Change)
        runCurrent()

        vm.onBack()
        locks.statusGate!!.complete(Unit)
        advanceUntilIdle()

        assertEquals(ProfilesScreen.Page, vm.screen)
    }

    @Test
    fun aLockRemovedElsewhereBeforeTheCurrentOneIsAskedReturnsToYourRows() = runTest(dispatcher) {
        val vm = openAs(MIRA)
        vm.onLock()
        advanceUntilIdle()
        locks.locks.remove(MIRA.id)

        vm.onChangeLock(LockChange.Change)
        advanceUntilIdle()

        assertEquals(ProfilesScreen.Page, vm.screen)
    }

    @Test
    fun theCurrentLockShowsAWaitItIsAlreadyIn() = runTest(dispatcher) {
        locks.waits[MIRA.id] = 47.seconds
        val vm = openAs(MIRA)
        vm.onLock()
        advanceUntilIdle()

        vm.onChangeLock(LockChange.Remove)
        runCurrent()

        assertEquals(47.seconds, vm.entry.wait)
    }

    @Test
    fun addAProfileAsksWhichKindFirstAndBackLeavesItForThePage() = runTest(dispatcher) {
        val vm = openAs(OWNER_LOCKED)

        vm.onAddProfile()
        advanceUntilIdle()
        assertEquals(ProfilesScreen.ProfileKind, vm.screen)
        vm.onBack()

        assertEquals(ProfilesScreen.Page, vm.screen)
        assertTrue(locks.tries.isEmpty())
    }

    @Test
    fun aKidsProfileAddedFromTheKindSheetReturnsToThePage() = runTest(dispatcher) {
        val vm = openAs(OWNER)
        vm.onAddProfile()

        vm.onKidAdded()

        assertEquals(ProfilesScreen.Page, vm.screen)
    }

    @Test
    fun aKidsProfilesRowCarriesItsApprovedVideosAndDailyLimits() = runTest(dispatcher) {
        val kid = Profile(4, "Zainab", ProfileTint.Violet, isOwner = false, lock = null, isKids = true)
        profiles.rows.value = listOf(OWNER_LOCKED, MIRA, kid)
        val limits = mapOf(DayOfWeek.MONDAY to 60, DayOfWeek.SATURDAY to 90)
        filters.rulesById.value = mapOf(
            kid.id to ProfileRules(
                restrictions = ProfileRestrictions(dailyLimitMinutes = limits, bedtime = Bedtime(1170, 420)),
                rules = listOf(StoredRule(ApprovedVideo("aqz-KE-bpKQ", "Big Buck Bunny", null, null, null, 596), byOwner = true, addedAtMillis = 0)),
                kids = true,
            ),
        )

        val vm = openAs(OWNER_LOCKED)

        assertEquals(KidSummary(approvedVideos = 1, dailyLimitMinutes = limits), vm.uiState.value.rows.last().kid)
        assertNull(vm.uiState.value.rows.first().kid)
        assertEquals(listOf("Zainab"), vm.uiState.value.kidsNames)
    }

    @Test
    fun aKidsProfilesRowFollowsItsRulesAsTheyChange() = runTest(dispatcher) {
        val kid = Profile(4, "Zainab", ProfileTint.Violet, isOwner = false, lock = null, isKids = true)
        profiles.rows.value = listOf(OWNER_LOCKED, kid)
        val vm = openAs(OWNER_LOCKED)
        assertEquals(KidSummary(approvedVideos = 0, dailyLimitMinutes = emptyMap()), vm.uiState.value.rows.last().kid)

        filters.rulesById.value = mapOf(kid.id to ProfileRules(ProfileRestrictions(dailyLimitMinutes = mapOf(DayOfWeek.SUNDAY to 30))))
        advanceUntilIdle()

        assertEquals(mapOf(DayOfWeek.SUNDAY to 30), vm.uiState.value.rows.last().kid!!.dailyLimitMinutes)
    }

    private val ProfilesViewModel.screen: ProfilesScreen get() = uiState.value.screen

    private val ProfilesViewModel.entry: UnlockEntry
        get() = (screen as ProfilesScreen.EntryScreen).entry

    /** [me]'s Settings › Profiles over Arshad (owner), Mira (PIN) and Yuri, unless the test
     *  set other rows; the stores' locks follow each profile's lock. */
    private fun TestScope.openAs(me: Profile): ProfilesViewModel {
        if (profiles.rows.value == null) {
            profiles.rows.value = listOf(if (me == OWNER_LOCKED) OWNER_LOCKED else OWNER, MIRA, YURI)
        }
        profiles.rows.value!!.forEach { profile ->
            locks.known += profile.id
            when (profile.lock) {
                LockKind.Sequence -> locks.locks[profile.id] = LockSecret.Sequence(SEQUENCE)
                LockKind.Pin -> locks.locks[profile.id] = LockSecret.Pin("2461")
                null -> Unit
            }
        }
        session.select(me.id)
        return ProfilesViewModel(profiles, locks, filters, session, testScheduler.timeSource).also { advanceUntilIdle() }
    }

    /** Add a profile, then Standard profile from the kind sheet. */
    private fun ProfilesViewModel.addStandard() {
        onAddProfile()
        onStandardProfile()
    }

    private fun enter(vm: ProfilesViewModel, sequence: List<Press>) {
        sequence.forEach(vm::onPress)
        vm.onSubmitSequence()
    }

    private companion object {
        val OWNER = Profile(1, "Arshad", ProfileTint.Amber, isOwner = true, lock = null)
        val OWNER_LOCKED = OWNER.copy(lock = LockKind.Sequence)
        val MIRA = Profile(2, "Mira", ProfileTint.Rose, isOwner = false, lock = LockKind.Pin)
        val YURI = Profile(3, "Yuri", ProfileTint.Teal, isOwner = false, lock = null)
        val ARSHAD_COUNTS = ProfileContents(subscriptionCount = 41, playlistCount = 3, historyCount = 214)
        val MIRA_COUNTS = ProfileContents(subscriptionCount = 12, playlistCount = 1, historyCount = 96)
        val SEQUENCE = listOf(Press.Up, Press.Up, Press.Down, Press.Left, Press.Right, Press.Down)
        val WRONG_SEQUENCE = listOf(Press.Left, Press.Left, Press.Left, Press.Left, Press.Left, Press.Left)

        fun profile(id: Long, name: String): Profile = Profile(id, name, ProfileTint.Violet, isOwner = false, lock = null)
    }
}
