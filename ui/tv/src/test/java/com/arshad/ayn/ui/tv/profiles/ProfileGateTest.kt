package com.arshad.ayn.ui.tv.profiles

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.ComponentActivity
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.arshad.ayn.core.backup.BackupRead
import com.arshad.ayn.core.backup.BackupRefusal
import com.arshad.ayn.core.backup.FakeBackupStore
import com.arshad.ayn.core.backup.PlannedProfile
import com.arshad.ayn.core.backup.ProfileCounts
import com.arshad.ayn.core.backup.RestoreChoice
import com.arshad.ayn.core.backup.RestorePlan
import com.arshad.ayn.core.backup.RestoreResult
import com.arshad.ayn.core.backup.RestoredProfile
import com.arshad.ayn.core.backup.testBackup
import com.arshad.ayn.core.kids.FakeKidsClock
import com.arshad.ayn.core.kids.KidsTime
import com.arshad.ayn.core.lock.FakeAwayRecords
import com.arshad.ayn.core.lock.FakeProfileLocks
import com.arshad.ayn.core.lock.LockKind
import com.arshad.ayn.core.lock.LockSecret
import com.arshad.ayn.core.lock.Press
import com.arshad.ayn.core.lock.RelockMonitor
import com.arshad.ayn.core.lock.sessionLockOf
import com.arshad.ayn.core.store.FakeKidsPin
import com.arshad.ayn.core.store.FakeProfileStore
import com.arshad.ayn.core.store.LastUsedProfile
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.ProfileSession
import com.arshad.ayn.core.store.ProfileTint
import com.arshad.ayn.feature.profiles.GateScreen
import com.arshad.ayn.feature.profiles.PickKind
import com.arshad.ayn.feature.profiles.PickRequest
import com.arshad.ayn.feature.profiles.ProfileGateViewModel
import com.arshad.ayn.feature.profiles.RestoredBackup
import com.arshad.ayn.ui.tv.dpadSelect
import com.arshad.ayn.ui.tv.pressBack
import com.arshad.ayn.ui.tv.shell.KeRail
import com.arshad.ayn.ui.tv.shell.railDestinations
import com.arshad.ayn.ui.tv.TEST_SEQUENCE
import com.arshad.ayn.ui.tv.keyOf
import com.arshad.ayn.ui.tv.theme.KeTheme
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The gate host on the real gate ViewModel over the `:core` fakes: first launch to the owner's
 * shell, the restore's confirm, Choose profiles, no-owner, refused, failed and results steps with
 * Lock my profile, the owner's lock a kids profile waits for and the leave copy, Time's up and More
 * time, the sequence pad and the PIN keypad,
 * BACK on the unlock screen, and the shell's saved
 * state and ViewModels kept for the same profile and dropped for another, the ViewModels before
 * the session changes. The shell is a stub with one back-stack entry (as the real shell's
 * ViewModels live in entries) that counts in saved state and holds a ViewModel whose per-profile
 * write waits on [fetch].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp")
class ProfileGateTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val locks = FakeProfileLocks()
    private val backups = FakeBackupStore()
    private val session = ProfileSession()
    private val kidsClock = FakeKidsClock()
    private lateinit var store: FakeProfileStore
    private lateinit var gate: ProfileGateViewModel
    private val shellModels = mutableListOf<ShellModel>()

    /** What each shell's write waits on (a fetch), and where each write landed: the profile its
     *  shell was opened for, then the profile the session named when it wrote. */
    private val fetch = CompletableDeferred<Unit>()
    private val writes = mutableListOf<Pair<Long, Long>>()

    inner class ShellModel(val openedFor: Long) : ViewModel() {
        var cleared = false

        init {
            viewModelScope.launch {
                fetch.await()
                writes += openedFor to session.currentId()
            }
        }

        override fun onCleared() {
            cleared = true
        }
    }

    private var lastUsedId: Long? = null

    /** Every file picker opened, by what it was opened for. */
    private val pickerRequests = mutableListOf<PickRequest>()

    private fun launch(vararg profiles: Profile) {
        store = FakeProfileStore(profiles.toList())
        val monitor = RelockMonitor(elapsed = { Duration.ZERO }) { sessionLockOf(store.rows.value, session.currentIdOrNull) }
        val lastUsed = object : LastUsedProfile {
            override suspend fun get(): Long? = lastUsedId

            override suspend fun set(profileId: Long) = Unit
        }
        gate = ProfileGateViewModel(
            store, locks, session, monitor, lastUsed, backups,
            playback = {}, awayRecords = FakeAwayRecords(), kidsPin = FakeKidsPin(), kidsClock = kidsClock, restarted = false,
            timeSource = TimeSource.Monotonic,
        )
        compose.setContent {
            KeTheme {
                ProfileGate(gate, chooseFile = { pickerRequests += it }) { opening ->
                    val profile = opening.profile
                    NavHost(rememberNavController(), startDestination = "shell") {
                        composable("shell") {
                            shellModels += viewModel { ShellModel(profile.id) }
                            var count by rememberSaveable { mutableIntStateOf(0) }
                            Surface(onClick = { count++ }) { Text("${profile.name} shell $count") }
                        }
                    }
                    if (opening.settingsPickWaiting) Text("a pick waits for Settings")
                }
            }
        }
        compose.waitForIdle()
    }

    private fun clickable(label: String): SemanticsNodeInteraction = compose.onNode(hasText(label) and hasClickAction())

    private val pad: SemanticsNodeInteraction get() = compose.onNode(hasContentDescription("D-pad sequence"))

    private fun pressOnPad(keys: List<Key>) {
        keys.forEach { key -> pad.performKeyInput { pressKey(key) } }
        compose.waitForIdle()
    }

    private val entry get() = (gate.uiState.value.screen as GateScreen.Unlock).entry

    @Test
    fun firstLaunchNamesTheOwnerAndOpensItsShell() {
        launch()
        clickable("Create my profile").dpadSelect()
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).performTextInput("Arshad")
        clickable("Continue").dpadSelect()
        compose.waitForIdle()

        compose.onNode(hasText("Arshad shell 0")).assertExists()
    }

    @Test
    fun theSequencePadCountsAHeldDirectionOnceAndHintsWhenTooShort() {
        launch(ARSHAD_SEQ, YURI)
        locks.locks[ARSHAD_SEQ.id] = LockSecret.Sequence(TEST_SEQUENCE)
        gate.onPick(ARSHAD_SEQ)
        compose.waitForIdle()

        val down = AndroidKeyEvent(0, 0, AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_DPAD_UP, 0)
        pad.performKeyPress(KeyEvent(down))
        pad.performKeyPress(KeyEvent(AndroidKeyEvent.changeTimeRepeat(down, 50, 1)))
        compose.waitForIdle()
        assertEquals("a held direction is one press", 1, entry.entered)

        pressOnPad(listOf(Key.DirectionCenter))
        compose.onNode(hasText("Enter at least 6 presses, then press OK.")).assertExists()
    }

    /** OK's release after the unlock lands on the shell, which must not take it as a click. */
    @Test
    fun theRightSequenceOpensTheProfile() {
        launch(ARSHAD_SEQ, YURI)
        locks.locks[ARSHAD_SEQ.id] = LockSecret.Sequence(TEST_SEQUENCE)
        gate.onPick(ARSHAD_SEQ)
        compose.waitForIdle()

        pressOnPad(TEST_SEQUENCE.map(::keyOf))
        assertEquals("every press lands", TEST_SEQUENCE.size, entry.entered)
        pressOnPad(listOf(Key.DirectionCenter))
        assertEquals("tries", listOf(ARSHAD_SEQ.id), locks.tries)

        compose.onNode(hasText("Arshad shell 0")).assertExists()
    }

    /** An OK held through the unlock repeats on the shell; its release is still the pad's. */
    @Test
    fun anOkHeldThroughTheUnlockDoesNotClickTheShell() {
        launch(ARSHAD_SEQ, YURI)
        locks.locks[ARSHAD_SEQ.id] = LockSecret.Sequence(TEST_SEQUENCE)
        gate.onPick(ARSHAD_SEQ)
        compose.waitForIdle()
        pressOnPad(TEST_SEQUENCE.map(::keyOf))

        val ok = AndroidKeyEvent(0, 0, AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_DPAD_CENTER, 0)
        pad.performKeyPress(KeyEvent(ok))
        compose.waitForIdle()
        val root = compose.onRoot()
        root.performKeyPress(KeyEvent(AndroidKeyEvent.changeTimeRepeat(ok, 600, 1)))
        root.performKeyPress(KeyEvent(AndroidKeyEvent.changeAction(ok, AndroidKeyEvent.ACTION_UP)))
        compose.waitForIdle()

        compose.onNode(hasText("Arshad shell 0")).assertExists()
    }

    /** Restore an Ayn backup, then the picker returns [name] (through the activity's path). First
     *  launch: the plan adds every profile. */
    private fun pickFile(name: String, read: BackupRead) {
        val uri = Uri.parse("content://downloads/$name")
        backups.reads[uri] = read
        backups.plan = { backup ->
            RestorePlan(firstLaunch = true, roomForNew = backup.profiles.size, profiles = backup.profiles.map { PlannedProfile(it, replaces = null) })
        }
        clickable("Restore an Ayn backup").dpadSelect()
        assertEquals(PickRequest(PickKind.Restore, profileId = null), pickerRequests.last())
        compose.runOnUiThread { gate.onFilePicked(pickerRequests.last(), uri) { name } }
        compose.waitForIdle()
    }

    /** The store writes the restored profiles; the fake's restore doesn't, so the test does. */
    private fun restoreLandsOn(owner: Profile, vararg others: Profile) {
        val profiles = (listOf(owner) + others).map { RestoredProfile(it.id, it.name, replaced = false, NO_COUNTS, 24, 24, emptyList()) }
        backups.restoreResult = RestoreResult.Restored(ownerId = owner.id, profiles = profiles)
        store.rows.value = listOf(owner) + others
    }

    private fun select(label: String) {
        clickable(label).dpadSelect()
        compose.waitForIdle()
    }

    @Test
    fun aBackupIsConfirmedRestoredAndDoneOpensItsOwner() {
        launch()
        pickFile("Ayn-backup.json", BackupRead.Valid(testBackup("Yuri", "Guests")))
        compose.onNode(hasText("Restore this backup?")).assertExists()
        compose.onNode(hasText("It brings back 2 profiles (Yuri and Guests)", substring = true)).assertExists()
        clickable("Restore everything").assertIsFocused()

        restoreLandsOn(YURI_OWNER, GUESTS)
        select("Restore everything")
        compose.onNode(hasText("Backup restored")).assertExists()
        clickable("Lock my profile").assertIsFocused()
        select("Done")

        assertEquals(listOf(RestoreChoice.AddAsNew, RestoreChoice.AddAsNew), backups.restoredChoices.single())
        compose.onNode(hasText("Yuri shell 0")).assertExists()
    }

    @Test
    fun chooseProfilesRestoresOnlyTheTickedOnesAndTheOwnerAlways() {
        launch()
        pickFile("Ayn-backup.json", BackupRead.Valid(testBackup("Yuri", "Guests", "Mira")))
        select("Choose profiles")
        compose.onNode(hasText("Restore which profiles?")).assertExists()
        compose.onNode(hasText("Always restored")).assertExists()
        clickable("Guests").assertIsFocused()

        select("Guests")
        restoreLandsOn(YURI_OWNER)
        select("Restore 2 profiles")

        assertEquals(listOf(RestoreChoice.AddAsNew, RestoreChoice.Skip, RestoreChoice.AddAsNew), backups.restoredChoices.single())
    }

    @Test
    fun aBackupWithNoOwnerCantStartTheShield() {
        launch()
        pickFile("Ayn-backup.json", BackupRead.Valid(testBackup("Mira", withOwner = false)))

        compose.onNode(hasText("This backup has no owner profile")).assertExists()
        compose.onNode(hasText("Create your profile, then restore it from Settings.")).assertExists()
        clickable("Choose another file").assertIsFocused()
    }

    @Test
    fun lockMyProfileOnTheResultsChoosesALockThenAsksForItTwice() {
        launch()
        pickFile("Ayn-backup.json", BackupRead.Valid(testBackup("Yuri")))
        restoreLandsOn(YURI_OWNER)
        select("Restore everything")

        select("Lock my profile")
        compose.onNode(hasText("Choose a lock")).assertExists()
        select("D-pad sequence")
        compose.onNode(hasText("New lock · step 1 of 2")).assertExists()
        compose.pressBack()

        compose.onNode(hasText("Choose a lock")).assertExists()
    }

    @Test
    fun aRefusedFileSaysWhyAndBackLandsOnRestoreAgain() {
        launch()
        pickFile("subscriptions.csv", BackupRead.Refused(BackupRefusal.NotABackup))
        compose.onNode(hasText("subscriptions.csv")).assertExists()
        compose.onNode(hasText("This file isn’t an Ayn backup")).assertExists()
        clickable("Choose another file").assertIsFocused()

        select("Back")
        clickable("Restore an Ayn backup").assertIsFocused()

        select("Create my profile")
        compose.pressBack()
        clickable("Create my profile").assertIsFocused()
    }

    @Test
    fun aFilePickedAfterARefusalIsConfirmedAndTheRemoteBackLeaves() {
        launch()
        pickFile("subscriptions.csv", BackupRead.Refused(BackupRefusal.NotABackup))
        clickable("Choose another file").assertIsFocused()

        val uri = Uri.parse("content://downloads/Ayn-backup.json")
        backups.reads[uri] = BackupRead.Valid(testBackup("Yuri"))
        gate.onRestoreFilePicked(uri, "Ayn-backup.json")
        compose.waitForIdle()
        clickable("Restore everything").assertIsFocused()

        compose.pressBack()
        clickable("Restore an Ayn backup").assertIsFocused()
    }

    @Test
    fun aRunningRestoreKeepsItsConfirmThenAFailureTriesAgain() {
        launch()
        pickFile("Ayn-backup.json", BackupRead.Valid(testBackup("Yuri")))
        backups.restoreResult = RestoreResult.Failed
        val running = CompletableDeferred<Unit>()
        backups.restoreGate = running
        select("Restore everything")
        compose.onNode(hasText("Restore this backup?")).assertExists()
        clickable("Restore everything").assertIsFocused()

        pickerRequests.clear()
        clickable("Choose another file").dpadSelect()
        compose.pressBack()
        assertEquals("no picker while restoring", emptyList<PickRequest>(), pickerRequests)
        compose.onNode(hasText("Restore this backup?")).assertExists()

        running.complete(Unit)
        compose.waitForIdle()
        compose.onNode(hasText("The restore didn’t finish")).assertExists()
        clickable("Try again").assertIsFocused()

        backups.restoreGate = null
        restoreLandsOn(YURI_OWNER)
        select("Try again")

        assertEquals(2, backups.restored.size)
        compose.onNode(hasText("Backup restored")).assertExists()
    }

    @Test
    fun aKidsProfileWaitingForTheOwnersLockNamesTheKidAndOffersTheLock() {
        launch(ARSHAD_UNLOCKED, ZAINAB)
        compose.runOnUiThread { gate.onPick(ZAINAB) }
        compose.waitForIdle()

        compose.onNode(hasText("Zainab’s kids profile")).assertExists()
        compose.onNode(hasText("Lock your profile, then Zainab’s profile opens.", substring = true)).assertExists()
        clickable("Lock my profile").assertIsFocused()
        select("Lock my profile")
        compose.onNode(hasText("Choose a lock")).assertExists()
        compose.pressBack()
        clickable("Lock my profile").assertIsFocused()
        compose.pressBack()
        compose.onNode(hasText("Who’s watching?")).assertExists()
    }

    @Test
    fun leavingAKidsProfileAsksForTheOwnersLockAndSaysWhereBackGoes() {
        val copy = leaveCopy("Zainab", "Arshad", LockKind.Sequence)
        assertEquals("Leaving Zainab’s profile", copy.kicker?.text)
        assertEquals("Enter Arshad’s sequence, then press OK.", copy.line)
        assertEquals("BACK returns to Zainab’s Home", copy.hint)
    }

    @Test
    fun timesUpClosesTheKidsShellAndMoreTimeAsksForTheOwnersLock() {
        kidsOpen()

        compose.runOnUiThread { kidsClock.time.value = KidsTime.TimesUp }
        compose.waitForIdle()
        compose.onNode(hasText("Zainab shell", substring = true)).assertDoesNotExist()
        compose.onNode(hasText("Time’s up for today")).assertExists()
        compose.onNode(hasText("That’s all for today, Zainab. See you tomorrow!")).assertExists()
        compose.onNode(hasText("More time and Switch profile need Arshad’s lock")).assertExists()
        clickable("OK").assertIsFocused()

        select("More time")
        compose.onNode(hasText("More time for Zainab")).assertExists()
        compose.onNode(hasText("Only Arshad adds time. Enter Arshad’s sequence, then press OK.")).assertExists()
        compose.pressBack()
        clickable("More time").assertIsFocused()
    }

    @Test
    fun moreTimeAfterTheOwnersLockReopensTheKidsShell() {
        kidsOpen()
        compose.runOnUiThread { kidsClock.time.value = KidsTime.TimesUp }
        compose.waitForIdle()
        select("More time")

        pressOnPad(TEST_SEQUENCE.map(::keyOf) + Key.DirectionCenter)
        compose.onNode(hasText("How much more time?")).assertExists()
        assertEquals("the lock's OK picks no amount", emptyList<Int>(), kidsClock.grants)
        select("30 more minutes")

        assertEquals(listOf(30), kidsClock.grants)
        compose.onNode(hasText("Zainab shell 0")).assertExists()
    }

    @Test
    fun switchProfileAfterTheOwnersLockOpensThePickerOnTheOwner() {
        kidsOpen()
        compose.runOnUiThread { kidsClock.time.value = KidsTime.TimesUp }
        compose.waitForIdle()

        select("Switch profile")
        compose.onNode(hasText("Only Arshad switches profiles. Enter Arshad’s sequence, then press OK.")).assertExists()
        compose.pressBack()
        clickable("Switch profile").assertIsFocused()

        select("Switch profile")
        pressOnPad(TEST_SEQUENCE.map(::keyOf) + Key.DirectionCenter)
        compose.onNode(hasText("Who’s watching?")).assertExists()
        assertEquals(emptyList<Int>(), kidsClock.grants)
    }

    @Test
    fun backOnTimesUpClosesAyn() {
        kidsOpen()
        compose.runOnUiThread { kidsClock.time.value = KidsTime.Bedtime(java.time.LocalTime.of(7, 0)) }
        compose.waitForIdle()
        compose.onNode(hasText("Ayn opens again at 7:00 AM. Sleep well, Zainab!")).assertExists()

        compose.pressBack()

        assertTrue(compose.activity.isFinishing)
    }

    @Test
    fun aPickForTheOpenProfilesSettingsReachesItsShell() {
        launch(YURI)

        compose.runOnUiThread {
            gate.onFilePicked(PickRequest(PickKind.Import, YURI.id), Uri.parse("content://downloads/subscriptions.csv")) { null }
        }
        compose.waitForIdle()

        compose.onNode(hasText("a pick waits for Settings")).assertExists()
    }

    @Test
    fun aFilePickedAfterAProfileOpenedChangesNothing() {
        launch(YURI)
        gate.onRestoreFilePicked(Uri.parse("content://downloads/backup.json"), "backup.json")
        compose.waitForIdle()

        compose.onNode(hasText("Yuri shell 0")).assertExists()
    }

    @Test
    fun aWrongPinFromNumberKeysWarnsAndTheRightOneOpens() {
        launch(MIRA_PIN, YURI)
        locks.locks[MIRA_PIN.id] = LockSecret.Pin(PIN)
        gate.onPick(MIRA_PIN)
        compose.waitForIdle()

        val five = clickable("5")
        val held = AndroidKeyEvent(0, 0, AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_1, 0)
        five.performKeyPress(KeyEvent(held))
        five.performKeyPress(KeyEvent(AndroidKeyEvent.changeTimeRepeat(held, 600, 1)))
        five.performKeyPress(KeyEvent(AndroidKeyEvent.changeAction(held, AndroidKeyEvent.ACTION_UP)))
        compose.waitForIdle()
        assertEquals("a held number key is one digit", 1, entry.entered)
        "111".forEach { digit -> five.performKeyInput { pressKey(digitKey(digit)) } }
        compose.waitForIdle()
        assertEquals("one try, on the fourth digit", listOf(MIRA_PIN.id), locks.tries)
        compose.onNode(hasText("That’s not it.")).assertExists()

        PIN.forEach { digit -> clickable(digit.toString()).dpadSelect() }
        compose.waitForIdle()
        compose.onNode(hasText("Mira shell 0")).assertExists()
    }

    @Test
    fun backErasesAPressThenReturnsToTheProfiles() {
        launch(ARSHAD_SEQ, YURI)
        locks.locks[ARSHAD_SEQ.id] = LockSecret.Sequence(TEST_SEQUENCE)
        gate.onPick(ARSHAD_SEQ)
        compose.waitForIdle()
        pressOnPad(listOf(Key.DirectionLeft))

        compose.pressBack()
        assertEquals(0, entry.entered)
        compose.pressBack()
        compose.onNode(hasText("Who’s watching?")).assertExists()
    }

    @Test
    fun thePickerStartsOnTheLastUsedProfileAndBackLeavesKe() {
        lastUsedId = YURI.id
        launch(ARSHAD_SEQ, YURI)

        compose.onNode(hasText("Y") and hasClickAction()).assertIsFocused()
        compose.pressBack()
        assertTrue(compose.activity.isFinishing)
    }

    @Test
    fun theSameProfileReopensItsShellAndAnotherStartsAfresh() {
        launch(YURI, GUESTS)
        gate.onPick(YURI)
        compose.waitForIdle()
        clickable("Yuri shell 0").dpadSelect()
        compose.waitForIdle()
        val first = shellModels.last()

        compose.runOnUiThread { gate.onRailProfile() }
        compose.waitForIdle()
        compose.pressBack()
        compose.onNode(hasText("Yuri shell 1")).assertExists()
        assertSame("its ViewModels were kept", first, shellModels.last())
        assertFalse(first.cleared)

        compose.runOnUiThread { gate.onRailProfile() }
        compose.waitForIdle()
        gate.onPick(GUESTS)
        compose.waitForIdle()
        compose.onNode(hasText("Guests shell 0")).assertExists()
        assertTrue("Yuri's ViewModels were cleared", first.cleared)
    }

    @Test
    fun aShellWriteInFlightAcrossASwitchNeverLandsUnderTheNewProfile() {
        launch(YURI, GUESTS)
        gate.onPick(YURI)
        compose.waitForIdle()
        compose.runOnUiThread { gate.onRailProfile() }
        compose.waitForIdle()

        // The fetch lands in the same main-thread turn as the switch, before the gate recomposes.
        compose.runOnUiThread {
            gate.onPick(GUESTS)
            fetch.complete(Unit)
        }
        compose.waitForIdle()

        compose.onNode(hasText("Guests shell 0")).assertExists()
        assertEquals(listOf(GUESTS.id to GUESTS.id), writes)
    }

    @Test
    fun aSettingsRestoreClearsTheShellOnScreenAndLeavesNoneOfItsViewModels() {
        launch(YURI, GUESTS)
        gate.onPick(YURI)
        compose.waitForIdle()
        // What the store's restore leaves: the backup's profiles, its owner selected.
        val restoredOwner = Profile(7, "Mira", ProfileTint.Rose, isOwner = true, lock = null)
        store.rows.value = listOf(restoredOwner)
        session.select(restoredOwner.id)

        compose.runOnUiThread { gate.onRestored(restoredOwner.id, RestoredBackup("Ayn-backup.json", exportedAtMillis = 0, result = RestoreResult.Restored(ownerId = restoredOwner.id))) }
        compose.waitForIdle()

        compose.onNode(hasText("Mira shell 0")).assertExists()
        val yuris = shellModels.filter { it.openedFor == YURI.id }
        assertTrue(yuris.isNotEmpty())
        assertTrue("every ViewModel of Yuri's shell was cleared", yuris.all { it.cleared })
    }

    @Test
    fun theRailSlotNamesWhatItDoesAndOpensTheProfiles() {
        var opened = false
        var profile by mutableStateOf(ARSHAD_SEQ)
        compose.setContent {
            KeTheme {
                KeRail(
                    destinations = railDestinations,
                    current = railDestinations.first(),
                    open = true,
                    currentItem = remember { FocusRequester() },
                    page = remember { FocusRequester() },
                    profile = profile,
                    onSelect = {},
                    onProfile = { opened = true },
                    onOpenChange = {},
                )
            }
        }
        compose.onNode(hasText("Lock and switch", substring = true) and hasClickAction()).dpadSelect()
        compose.waitForIdle()
        assertTrue(opened)

        profile = YURI
        compose.waitForIdle()
        compose.onNode(hasText("Switch profile", substring = true) and hasClickAction()).assertExists()
    }

    /** Arshad (locked) and Zainab, Zainab's shell open. */
    private fun kidsOpen() {
        locks.ownerId = ARSHAD_SEQ.id
        locks.kids += ZAINAB.id
        locks.locks[ARSHAD_SEQ.id] = LockSecret.Sequence(TEST_SEQUENCE)
        launch(ARSHAD_SEQ, ZAINAB)
        compose.runOnUiThread { gate.onPick(ZAINAB) }
        compose.waitForIdle()
        compose.onNode(hasText("Zainab shell 0")).assertExists()
    }

    @Test
    fun copyFollowsTheTriesLeftTheWaitAndTheRefusal() {
        assertEquals("That’s not it. Two more tries, then a 30-second wait.", wrongTryText(2))
        assertEquals("That’s not it. One more try, then a 30-second wait.", wrongTryText(1))
        assertEquals("That’s not it.", wrongTryText(3))
        assertEquals("0:01", waitText(1.milliseconds))
        assertEquals("0:30", waitText(30.seconds))
        assertEquals("0:30", waitText(29_001.milliseconds))
        val headings = BackupRefusal.entries.map { refusalCopy(it).first }
        assertEquals("each refusal has its own heading", headings.size, headings.toSet().size)
    }

    private companion object {
        val ARSHAD_SEQ = Profile(1, "Arshad", ProfileTint.Amber, isOwner = true, lock = LockKind.Sequence)
        val MIRA_PIN = Profile(2, "Mira", ProfileTint.Rose, isOwner = false, lock = LockKind.Pin)
        val YURI = Profile(3, "Yuri", ProfileTint.Teal, isOwner = false, lock = null)
        val GUESTS = Profile(4, "Guests", ProfileTint.Blue, isOwner = false, lock = null)
        val YURI_OWNER = YURI.copy(isOwner = true)
        val ARSHAD_UNLOCKED = ARSHAD_SEQ.copy(lock = null)
        val ZAINAB = Profile(5, "Zainab", ProfileTint.Violet, isOwner = false, lock = null, isKids = true)
        val NO_COUNTS = ProfileCounts(0, 0, 0, 0, 0, 0)
        const val PIN = "2461"

        fun digitKey(digit: Char): Key = Key(AndroidKeyEvent.KEYCODE_0 + (digit - '0'))
    }
}
