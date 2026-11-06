package com.arshad.ayn.ui.tv.settings

import com.arshad.ayn.ui.tv.testKidsAdd
import com.arshad.ayn.ui.tv.testSync
import com.arshad.ayn.ui.tv.shell.RailShade
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import com.arshad.ayn.core.lock.FakeProfileLocks
import com.arshad.ayn.core.lock.LockKind
import com.arshad.ayn.core.lock.LockSecret
import com.arshad.ayn.core.lock.Press
import com.arshad.ayn.core.lock.UnlockResult
import com.arshad.ayn.core.store.FakeProfileStore
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.ProfileContents
import com.arshad.ayn.core.lock.OwnerPass
import com.arshad.ayn.core.store.ProfileSession
import com.arshad.ayn.core.store.ProfileStore
import com.arshad.ayn.core.store.ProfileTint
import com.arshad.ayn.feature.profiles.RestoredBackup
import com.arshad.ayn.feature.settings.ProfilesScreen
import com.arshad.ayn.feature.settings.ProfilesViewModel
import com.arshad.ayn.feature.settings.RestrictionsViewModel
import com.arshad.ayn.feature.settings.BackupViewModel
import com.arshad.ayn.feature.settings.FiltersViewModel
import com.arshad.ayn.feature.settings.PreferencesViewModel
import com.arshad.ayn.core.store.FakeDeviceSettingsStore
import com.arshad.ayn.core.store.FakeFilterStore
import com.arshad.ayn.core.store.FakeProfileSettingsStore
import com.arshad.ayn.core.backup.FakeBackupStore
import com.arshad.ayn.core.backup.ProfileCounts
import com.arshad.ayn.core.backup.RestoreResult
import com.arshad.ayn.core.backup.RestoredProfile
import com.arshad.ayn.ui.tv.dpadSelect
import com.arshad.ayn.ui.tv.pressBack
import com.arshad.ayn.ui.tv.TEST_SEQUENCE
import com.arshad.ayn.ui.tv.keyOf
import com.arshad.ayn.ui.tv.enterSequence
import com.arshad.ayn.ui.tv.TestPicks
import com.arshad.ayn.ui.tv.theme.KeTheme
import java.time.LocalDate
import java.time.ZoneId
import kotlin.time.TimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings › Profiles on the real ProfilesViewModel over the `:core` fakes: what the owner's and
 * another person's pages list, removing a profile through its sheet and confirm, the owner gate
 * before the add form (after the kind sheet; a kids profile's add flow and the owner's held lock,
 * KP.8e), setting a lock twice (a mismatch starts over; the last OK's release must not
 * click the page), renaming
 * in place, the rail's shade under sheets and covering screens, and a restore's landing (Q9).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp")
class SettingsScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val locks = FakeProfileLocks().apply { ownerId = ARSHAD.id }
    private lateinit var store: FakeProfileStore
    private lateinit var profiles: ProfilesViewModel
    private lateinit var backup: BackupViewModel
    private val backups = FakeBackupStore()
    private val shades = mutableListOf<RailShade>()
    private var restoredShown = 0
    private var nothingToFocus = 0

    /** When set, a removal waits for it, so the page can take focus back before the row goes. */
    private var removeGate: CompletableDeferred<Unit>? = null

    private fun open(me: Profile, vararg everyone: Profile, restored: RestoredBackup? = null) {
        store = FakeProfileStore(everyone.toList())
        store.contentsById.value = mapOf(MIRA.id to MIRA_COUNTS)
        val session = ProfileSession().apply { select(me.id) }
        val gatedStore = object : ProfileStore by store {
            override suspend fun remove(owner: OwnerPass, profileId: Long): Boolean {
                removeGate?.await()
                return store.remove(owner, profileId)
            }
        }
        profiles = ProfilesViewModel(gatedStore, locks, FakeFilterStore(), session, TimeSource.Monotonic)
        backup = BackupViewModel(backups, store, locks, playback = {}, handover = { _, _ -> }, TestPicks(), me.id, TimeSource.Monotonic)
        val preferences = PreferencesViewModel(FakeProfileSettingsStore(me.id), FakeDeviceSettingsStore(), store)
        val filters = FiltersViewModel(FakeProfileSettingsStore(me.id), FakeFilterStore(), store, locks)
        val restrictions = RestrictionsViewModel(FakeFilterStore(), store, locks, TimeSource.Monotonic)
        compose.setContent {
            KeTheme {
                SettingsScreen(
                    profiles = profiles,
                    backup = backup,
                    preferences = preferences,
                    filters = filters,
                    restrictions = restrictions,
                    kidsAdd = testKidsAdd(store, locks),
                    sync = testSync(store, locks, me.id),
                    restored = restored,
                    pickWaiting = false,
                    chooseFile = {},
                    onRestoredShown = { restoredShown++ },
                    onNothingToFocus = { nothingToFocus++ },
                    focusHeldElsewhere = { false },
                    onRailShade = { shades += it },
                )
            }
        }
        compose.waitForIdle()
    }

    private fun clickable(label: String): SemanticsNodeInteraction = compose.onNode(hasText(label) and hasClickAction())

    private fun select(label: String) {
        clickable(label).dpadSelect()
        compose.waitForIdle()
    }

    private val pad: SemanticsNodeInteraction get() = compose.onNode(hasContentDescription("D-pad sequence"))

    private fun enter(sequence: List<Press>) {
        pad.enterSequence(sequence)
        compose.waitForIdle()
    }

    @Test
    fun theOwnersPageListsEveryProfileWithItsLockAndCounts() {
        open(ARSHAD_LOCKED, ARSHAD_LOCKED, MIRA, YURI)

        compose.onNode(hasText("Locked with a PIN · 7 subscriptions · 1 playlist · 96 in history")).assertExists()
        compose.onNode(hasText("No lock · 0 in history")).assertExists()
        compose.onNode(hasText("You")).assertExists()
        compose.onNode(hasText("3 of 5")).assertExists()
        compose.onNode(hasText("Your profile isn’t locked.", substring = true)).assertDoesNotExist()
        clickable("Profiles").assertIsFocused()
    }

    @Test
    fun anUnlockedOwnerIsWarnedAndOfferedALock() {
        open(ARSHAD, ARSHAD, MIRA)

        compose.onNode(hasText("Your profile isn’t locked.", substring = true)).assertExists()
        select("Lock your profile")

        assertEquals(ProfilesScreen.OwnSheet, profiles.uiState.value.screen)
        clickable("Lock").assertIsFocused()
    }

    @Test
    fun someoneElsesPageHoldsOnlyTheirRowsAndNamesTheOwner() {
        open(YURI, ARSHAD, MIRA, YURI)

        compose.onNode(hasText("Your subscriptions, history, playlists and Watch later. Arshad, the owner, adds and removes profiles.")).assertExists()
        listOf("Name", "Colour", "Lock").forEach { clickable(it).assertExists() }
        compose.onNode(hasText("Mira")).assertDoesNotExist()
        compose.onNode(hasText("Add a profile")).assertDoesNotExist()
    }

    @Test
    fun removingAProfileNamesWhatItDeletesThenReturnsToThePage() {
        open(ARSHAD, ARSHAD, MIRA, YURI)

        select("Mira")
        assertEquals(RailShade.Dimmed, shades.last())
        select("Remove profile")
        assertEquals(RailShade.Hidden, shades.last())
        compose.onNode(hasText("This deletes Mira’s 7 subscriptions, 1 playlist, Watch later and 96 videos from history. It can’t be undone.")).assertExists()
        clickable("Keep Mira").assertIsFocused()
        val removing = CompletableDeferred<Unit>().also { removeGate = it }
        select("Remove Mira")
        clickable("Mira").assertIsFocused()
        removing.complete(Unit)
        compose.waitForIdle()

        assertEquals(listOf(ARSHAD, YURI), store.rows.value)
        assertEquals(RailShade.Lit, shades.last())
        compose.onNode(hasText("Mira")).assertDoesNotExist()
        // The row that opened the sheet is gone: focus moves on to the next one.
        clickable("Yuri").assertIsFocused()
        assertEquals(0, nothingToFocus)
    }

    @Test
    fun nothingBehindASheetTakesFocus() {
        open(ARSHAD, ARSHAD, MIRA)
        select("Mira")

        clickable("Restrictions").assertIsFocused()
        clickable("Add a profile").performSemanticsAction(SemanticsActions.RequestFocus)

        clickable("Add a profile").assertIsNotFocused()
        clickable("Restrictions").assertIsFocused()
    }

    @Test
    fun aLockedOwnerIsAskedForTheirLockBeforeTheAddForm() {
        locks.locks[ARSHAD.id] = LockSecret.Sequence(TEST_SEQUENCE)
        open(ARSHAD_LOCKED, ARSHAD_LOCKED, MIRA)

        select("Add a profile")
        compose.onNode(hasText("What kind of profile?")).assertExists()
        select("Standard profile")
        compose.onNode(hasText("Only the owner adds profiles. Enter Arshad’s sequence, then press OK.")).assertExists()
        enter(TEST_SEQUENCE)

        compose.onNode(hasText("New profile · 3 of 5")).assertExists()
        compose.onNode(hasSetTextAction()).assertIsFocused()
    }

    @Test
    fun kidsProfileFromTheKindSheetAsksTheLockedOwnerThenNamesTheKid() {
        locks.locks[ARSHAD.id] = LockSecret.Sequence(TEST_SEQUENCE)
        open(ARSHAD_LOCKED, ARSHAD_LOCKED, MIRA)

        select("Add a profile")
        clickable("Kids profile").assertIsFocused()
        select("Kids profile")
        compose.onNode(hasText("Only the owner adds profiles. Enter Arshad’s sequence, then press OK.")).assertExists()
        enter(TEST_SEQUENCE)

        compose.onNode(hasText("New kids profile · step 1 of 3")).assertExists()
        compose.onNode(hasSetTextAction()).assertIsFocused()
    }

    @Test
    fun kidsProfileForAnOwnerWithoutALockAsksForOneFirst() {
        open(ARSHAD, ARSHAD, MIRA)

        select("Add a profile")
        select("Kids profile")

        compose.onNode(hasText("Lock your profile first")).assertExists()
        compose.onNode(hasText("Lock your profile, then add the kids profile.", substring = true)).assertExists()
        clickable("Lock my profile").assertIsFocused()
    }

    @Test
    fun theOwnersRemoveLockIsHeldWhileAKidsProfileExists() {
        locks.locks[ARSHAD.id] = LockSecret.Sequence(TEST_SEQUENCE)
        val zainab = Profile(4, "Zainab", ProfileTint.Violet, isOwner = false, lock = null, isKids = true)
        open(ARSHAD_LOCKED, ARSHAD_LOCKED, zainab)

        compose.onNode(hasText("Kids profile · 0 approved channels · 0 approved videos · no daily limit")).assertExists()
        select("Arshad")
        select("Lock")

        compose.onNode(hasText("Needed while Zainab has a kids profile")).assertExists()
        compose.onNode(hasText("Your lock leaves Zainab’s profile, adds more time and opens its Parent page.")).assertExists()
        clickable("Remove lock").performSemanticsAction(SemanticsActions.RequestFocus)
        clickable("Remove lock").assertIsNotFocused()
    }

    @Test
    fun aNewLockIsEnteredTwiceAndAMismatchStartsOver() {
        open(YURI, ARSHAD, YURI)

        select("Lock")
        select("D-pad sequence")
        compose.onNode(hasText("New lock · step 1 of 2")).assertExists()
        enter(TEST_SEQUENCE)
        compose.onNode(hasText("New lock · step 2 of 2")).assertExists()
        compose.onNode(hasText("Enter the same sequence again, then press OK.")).assertExists()
        enter(TEST_SEQUENCE.reversed())
        compose.onNode(hasText("The two didn’t match, so nothing was saved. Enter a new sequence.")).assertExists()
        compose.onNode(hasText("New lock · step 1 of 2")).assertExists()

        enter(TEST_SEQUENCE)
        // The confirming OK's release lands on the page, whose Lock row must not take it.
        TEST_SEQUENCE.forEach { press -> pad.performKeyInput { pressKey(keyOf(press)) } }
        val ok = AndroidKeyEvent(AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_DPAD_CENTER)
        pad.performKeyPress(KeyEvent(ok))
        compose.waitForIdle()
        compose.onRoot().performKeyPress(KeyEvent(AndroidKeyEvent.changeAction(ok, AndroidKeyEvent.ACTION_UP)))
        compose.waitForIdle()

        assertEquals(ProfilesScreen.Page, profiles.uiState.value.screen)
        val saved = runBlocking { locks.unlock(YURI.id, LockSecret.Sequence(TEST_SEQUENCE)) }
        assertTrue("the sequence entered twice is the lock", saved is UnlockResult.Opened)
        compose.onNode(hasText("Lock saved", substring = true)).assertExists()
        clickable("Lock").assertIsFocused()
    }

    @Test
    fun aNameIsEditedInItsRowAndATakenOneIsRefused() {
        open(YURI, ARSHAD, MIRA, YURI)

        select("Name")
        val field = compose.onNode(hasSetTextAction())
        field.assertIsFocused()
        field.performTextReplacement("mira")
        field.performImeAction()
        compose.waitForIdle()
        compose.onNode(hasText("mira already has a profile here.")).assertExists()

        field.performTextReplacement("Yuri A")
        field.performImeAction()
        compose.waitForIdle()

        assertEquals("Yuri A", store.rows.value!!.single { it.id == YURI.id }.name)
        clickable("Name").assertIsFocused()
    }

    @Test
    fun theOwnerRenamesInTheirSheetAndAnOkHeldAcrossTheRenameDoesNotReopenIt() {
        open(ARSHAD, ARSHAD, MIRA)
        select("Lock your profile")
        compose.mainClock.autoAdvance = false
        clickable("Name").dpadSelect()
        compose.mainClock.advanceTimeByFrame()
        // Renaming is the same sheet: no second one slides in over a fading copy.
        assertEquals(1, compose.onAllNodes(hasText("Colour")).fetchSemanticsNodes().size)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        val field = compose.onNode(hasSetTextAction())
        field.performTextReplacement("Arshad K")
        compose.waitForIdle()
        field.assertIsFocused()

        val ok = AndroidKeyEvent(AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_DPAD_CENTER)
        compose.onRoot().performKeyPress(KeyEvent(ok))
        field.performImeAction()
        compose.waitForIdle()
        compose.onRoot().performKeyPress(KeyEvent(AndroidKeyEvent.changeAction(ok, AndroidKeyEvent.ACTION_UP)))
        compose.waitForIdle()

        assertEquals("Arshad K", store.rows.value!!.single { it.id == ARSHAD.id }.name)
        assertEquals(ProfilesScreen.OwnSheet, profiles.uiState.value.screen)
        clickable("Name").assertIsFocused()
    }

    @Test
    fun aRestoredShellShowsItsResultsAndDoneLandsOnExportABackup() {
        val exported = LocalDate.now().withMonth(9).withDayOfMonth(12).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        open(ARSHAD, ARSHAD, MIRA, restored = restoredOf(exported, ARSHAD, MIRA))

        compose.onNode(hasText("Backup restored")).assertExists()
        compose.onNode(hasText("2 profiles from September 12. The 2 you replaced keep their locks here.")).assertExists()
        assertEquals(1, restoredShown)
        clickable("Done").assertIsFocused()
        select("Done")

        clickable("Export a backup").assertIsFocused()
    }

    @Test
    fun aBackupFromAnotherYearSaysItsYear() {
        val lastYear = LocalDate.now().minusYears(1).withMonth(9).withDayOfMonth(12)
        val exported = lastYear.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        open(ARSHAD, ARSHAD, restored = restoredOf(exported, ARSHAD))

        compose.onNode(hasText("1 profile from September 12, ${lastYear.year}.", substring = true)).assertExists()
    }

    private fun restoredOf(exported: Long, vararg replaced: Profile): RestoredBackup = RestoredBackup(
        fileName = "Ayn-backup.json",
        exportedAtMillis = exported,
        result = RestoreResult.Restored(
            ownerId = ARSHAD.id,
            profiles = replaced.map { RestoredProfile(it.id, it.name, replaced = true, ProfileCounts(0, 0, 0, 0, 0, 0), 24, 24, emptyList()) },
        ),
    )

    @Test
    fun backFromASheetReturnsFocusToTheRowThatOpenedIt() {
        open(YURI, ARSHAD, YURI)

        select("Colour")
        assertTrue(profiles.uiState.value.screen == ProfilesScreen.Colour)
        compose.pressBack()

        assertEquals(ProfilesScreen.Page, profiles.uiState.value.screen)
        clickable("Colour").assertIsFocused()
    }

    private companion object {
        val ARSHAD = Profile(1, "Arshad", ProfileTint.Amber, isOwner = true, lock = null)
        val ARSHAD_LOCKED = ARSHAD.copy(lock = LockKind.Sequence)
        val MIRA = Profile(2, "Mira", ProfileTint.Rose, isOwner = false, lock = LockKind.Pin)
        val YURI = Profile(3, "Yuri", ProfileTint.Teal, isOwner = false, lock = null)
        val MIRA_COUNTS = ProfileContents(subscriptionCount = 7, playlistCount = 1, historyCount = 96)
    }
}
