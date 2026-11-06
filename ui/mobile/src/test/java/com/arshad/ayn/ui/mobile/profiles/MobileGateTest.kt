package com.arshad.ayn.ui.mobile.profiles

import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.arshad.ayn.core.access.ServerClock
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
import com.arshad.ayn.core.filter.ContentFilter
import com.arshad.ayn.core.kids.FakeKidsClock
import com.arshad.ayn.core.kids.KidsTime
import com.arshad.ayn.core.lock.FakeAwayRecords
import com.arshad.ayn.core.lock.FakeProfileLocks
import com.arshad.ayn.core.lock.LockKind
import com.arshad.ayn.core.lock.LockSecret
import com.arshad.ayn.core.lock.Press
import com.arshad.ayn.core.lock.RelockMonitor
import com.arshad.ayn.core.lock.sessionLockOf
import com.arshad.ayn.core.store.ApprovedVideo
import com.arshad.ayn.core.store.FakeFilterStore
import com.arshad.ayn.core.store.FakeKidsPin
import com.arshad.ayn.core.store.FakeKidsReviewKeeps
import com.arshad.ayn.core.store.FakeKidsTimeStore
import com.arshad.ayn.core.store.FakeProfileStore
import com.arshad.ayn.core.store.LastUsedProfile
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.ProfileRules
import com.arshad.ayn.core.store.ProfileSession
import com.arshad.ayn.core.store.ProfileSettings
import com.arshad.ayn.core.store.ProfileTint
import com.arshad.ayn.core.store.StoredRule
import com.arshad.ayn.feature.home.HomeViewModel
import com.arshad.ayn.feature.kids.KidsStores
import com.arshad.ayn.feature.player.RecordedEnigmaPage
import com.arshad.ayn.feature.player.ScriptedPlayer
import com.arshad.ayn.feature.player.dressedItem
import com.arshad.ayn.feature.profiles.GateScreen
import com.arshad.ayn.feature.profiles.OwnerLockStep
import com.arshad.ayn.feature.profiles.PickKind
import com.arshad.ayn.feature.profiles.PickRequest
import com.arshad.ayn.feature.profiles.ProfileGateViewModel
import com.arshad.ayn.feature.profiles.ProfilePlayback
import com.arshad.ayn.feature.profiles.RestoreStep
import com.arshad.ayn.feature.profiles.UnlockEntry
import com.arshad.ayn.ui.mobile.TestMobileApp
import com.arshad.ayn.ui.mobile.shell.MobileShell
import com.arshad.ayn.ui.mobile.shell.TabKey
import com.arshad.ayn.ui.mobile.theme.AynTheme
import java.time.LocalTime
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config


/**
 * The phone's profile gate over the real [ProfileGateViewModel] and in-memory stores: the pads'
 * taps reach the lock entry ([com.arshad.ayn.feature.profiles.LockEntryInput]) as presses and
 * digits, erase and OK, a wrong try and the too-short hint, Back leaving the unlock, and first
 * launch (the owner named, a backup confirmed or refused), a wait's countdown, Back closing Ayn
 * from the picker, and the shell's saved state and ViewModels across a reopen (resumed) or another
 * profile (fresh) through the real shell. Elsewhere the shell is a stub naming its profile.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class MobileGateTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val locks = FakeProfileLocks()
    private val backups = FakeBackupStore()
    private val session = ProfileSession()
    private lateinit var store: FakeProfileStore
    private lateinit var gate: ProfileGateViewModel
    private val pickerRequests = mutableListOf<PickRequest>()
    private val probes = mutableListOf<ShellProbe>()

    class ShellProbe(val name: String) : ViewModel() {
        var cleared = false

        override fun onCleared() {
            cleared = true
        }
    }

    private lateinit var monitor: RelockMonitor
    private var elapsed = Duration.ZERO

    /** A phone's gate (M6-D8) and the player it keeps a video in. */
    private var phone = false
    private val kept = ScriptedPlayer()
    private val kidsClock = FakeKidsClock()

    private fun launch(vararg profiles: Profile) {
        launchWith(*profiles) {
            AynTheme {
                MobileGate(gate, chooseFile = { pickerRequests += it }, kept = kept) { opening -> Text("${opening.profile.name} shell") }
            }
        }
    }

    private fun launchWith(vararg profiles: Profile, content: @Composable () -> Unit) {
        store = FakeProfileStore(profiles.toList())
        monitor = RelockMonitor(elapsed = { elapsed }) { sessionLockOf(store.rows.value, session.currentIdOrNull) }
        val lastUsed = object : LastUsedProfile {
            override suspend fun get(): Long? = null

            override suspend fun set(profileId: Long) = Unit
        }
        gate = ProfileGateViewModel(
            store, locks, session, monitor, lastUsed, backups,
            playback = object : ProfilePlayback {
                override fun end() = kept.clear()

                override fun holdsItem(): Boolean = kept.currentMediaItem != null
            },
            awayRecords = FakeAwayRecords(), kidsPin = FakeKidsPin(), kidsClock = kidsClock, restarted = false,
            timeSource = TimeSource.Monotonic,
            keepsPlaybackOnRelock = phone,
        )
        compose.setContent(content)
        compose.waitForIdle()
    }

    private fun systemBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun tap(label: String) {
        compose.onNode(hasText(label) and hasClickAction()).performClick()
        compose.waitForIdle()
    }

    private fun key(description: String): SemanticsNodeInteraction = compose.onNode(hasContentDescription(description) and hasClickAction())

    private fun tapKeys(vararg descriptions: String) {
        descriptions.forEach { key(it).performClick() }
        compose.waitForIdle()
    }

    private fun tapSequence(presses: List<Press>) {
        tapKeys(*presses.map { it.name }.toTypedArray())
    }

    private val entry: UnlockEntry get() = (gate.uiState.value.screen as GateScreen.Unlock).entry

    private fun atSequenceUnlock() {
        locks.locks[ARSHAD.id] = LockSecret.Sequence(SEQUENCE)
        launch(ARSHAD, YURI)
        tap("Arshad")
    }

    /** Set 1 states 7–8 (M6-D8): the screen going off re-locks Arshad but keeps his video; his
     *  unlock says why and shows it with its own pause; Back's picker says another profile stops it. */
    @Test
    fun aPhoneRelockShowsTheKeptVideoOnTheUnlockAndThePicker() {
        phone = true
        atSequenceUnlock()
        tapSequence(SEQUENCE)
        tapKeys("OK")
        compose.runOnUiThread {
            kept.setMediaItem(dressedItem("JsBZOcqZerk"))
            kept.prepare()
            kept.play()
            kept.ready()
            monitor.onStart()
            monitor.onStop()
            monitor.onScreenOff()
            monitor.onStart()
        }
        compose.waitForIdle()

        compose.onNodeWithText("Arshad’s profile locked when the screen went off").assertExists()
        compose.onNodeWithText("Still playing · background audio").assertExists()
        compose.onNodeWithText(RecordedEnigmaPage.title).assertExists()
        key("Pause").performClick()
        compose.waitForIdle()
        assertFalse(kept.isPlayWhenReady)
        compose.runOnUiThread { kept.clear() }
        compose.waitForIdle()
        compose.onNodeWithText("Still playing · background audio").assertDoesNotExist()
        compose.runOnUiThread { kept.setMediaItem(dressedItem("JsBZOcqZerk")) }
        compose.waitForIdle()

        systemBack()
        compose.onNodeWithText("Playing for Arshad").assertExists()
        compose.onNodeWithText("Choosing another profile stops it. Arshad’s unlock keeps it playing.").assertExists()
        tap("Yuri")
        compose.onNode(hasText("Yuri shell")).assertExists()
        assertNull(kept.currentMediaItem)
    }

    /** Q42: a re-lock after time away says so under the unlock. */
    @Test
    fun aPhoneRelockAfterTimeAwaySaysSo() {
        phone = true
        atSequenceUnlock()
        tapSequence(SEQUENCE)
        tapKeys("OK")
        compose.runOnUiThread {
            kept.setMediaItem(dressedItem("JsBZOcqZerk"))
            monitor.onStart()
            monitor.onStop()
            elapsed += 5.minutes
            monitor.onStart()
        }
        compose.waitForIdle()

        compose.onNodeWithText("Arshad’s profile locked after 5 minutes away").assertExists()
    }

    @Test
    fun tappedArrowsAreThePressesAndTheRightSequenceOpensTheProfile() {
        atSequenceUnlock()
        tapSequence(SEQUENCE.take(4))
        compose.onNode(hasContentDescription("4 entered")).assertExists()

        tapSequence(SEQUENCE.drop(4))
        tapKeys("OK")

        compose.onNode(hasText("Arshad shell")).assertExists()
        assertEquals(listOf(ARSHAD.id), locks.tries)
    }

    @Test
    fun theEraseKeyTakesThePressAndOkWithTooFewIsAHint() {
        atSequenceUnlock()
        tapKeys("Up", "Left")

        tapKeys("Erase")
        assertEquals(1, entry.entered)
        tapKeys("OK")

        compose.onNode(hasText("Tap at least 6 presses, then OK.")).assertExists()
        assertTrue(locks.tries.isEmpty())
    }

    @Test
    fun aWrongSequenceSaysSoAndClearsTheDots() {
        atSequenceUnlock()
        tapSequence(List(6) { Press.Left })
        tapKeys("OK")

        compose.onNode(hasText("That’s not it.", substring = true)).assertExists()
        assertEquals(0, entry.entered)
    }

    @Test
    fun backLeavesTheUnlockWithPressesEntered() {
        atSequenceUnlock()
        tapKeys("Up", "Up")

        compose.onNode(hasContentDescription("Back") and hasClickAction()).performClick()
        compose.waitForIdle()

        compose.onNode(hasText("Who’s watching?")).assertExists()
        assertEquals(GateScreen.Picker(focusId = ARSHAD.id), gate.uiState.value.screen)
    }

    @Test
    fun aProfileWaitingCountsDownAndItsPadTakesNoTaps() {
        locks.locks[ARSHAD.id] = LockSecret.Sequence(SEQUENCE)
        locks.waits[ARSHAD.id] = 47.seconds
        launch(ARSHAD, YURI)
        tap("Arshad")

        compose.onNode(hasText("Too many tries. Try again in 0:4", substring = true)).assertExists()
        key("Up").assertIsNotEnabled()
    }

    @Test
    fun aWrongTryThatStartsAWaitShowsTheCountdownAndLeavesNoRejectionPending() {
        atSequenceUnlock()
        locks.wrongTries[ARSHAD.id] = WRONG_TRIES_BEFORE_A_WAIT
        tapSequence(List(6) { Press.Left })
        tapKeys("OK")

        compose.onNode(hasText("Too many tries.", substring = true)).assertExists()
        assertEquals(0, entry.rejectedDots)
    }

    @Test
    fun aWaitShowsWholeSecondsRoundedUpAndTheWrongTryWarnsTwoAhead() {
        assertEquals("0:48", waitText(47.4.seconds))
        assertEquals("1:00", waitText(60.seconds))
        assertEquals("0:01", waitText(0.5.seconds))
        assertEquals("That’s not it. Two more tries, then a 30-second wait.", wrongTryText(2))
        assertEquals("That’s not it. One more try, then a 30-second wait.", wrongTryText(1))
        assertEquals("That’s not it.", wrongTryText(3))
    }

    @Test
    fun backOnThePickerAtLaunchClosesAyn() {
        launch(ARSHAD, YURI)

        systemBack()

        assertTrue(compose.activity.isFinishing)
    }

    @Test
    fun reopeningAProfileFromThePickerResumesItsShellAndAnotherStartsFresh() {
        launchWith(YURI, GUESTS) {
            AynTheme {
                MobileGate(gate, chooseFile = {}) { opening ->
                    MobileShell(opening) { _ ->
                        entry<TabKey> {
                            val probe = viewModel { ShellProbe(opening.profile.name) }
                            probes += probe
                            var taps by rememberSaveable { mutableIntStateOf(0) }
                            TextButton(onClick = { taps++ }) { Text("${opening.profile.name} tapped $taps") }
                        }
                    }
                }
            }
        }
        tap("Yuri")
        tap("Yuri tapped 0")
        val yuris = probes.last()

        compose.runOnUiThread { gate.onRailProfile() }
        compose.waitForIdle()
        systemBack()
        compose.onNode(hasText("Yuri tapped 1")).assertExists()
        assertSame(yuris, probes.last())

        compose.runOnUiThread { gate.onRailProfile() }
        compose.waitForIdle()
        tap("Guests")

        compose.onNode(hasText("Guests tapped 0")).assertExists()
        assertEquals("Guests", probes.last().name)
        assertTrue(yuris.cleared)
    }

    @Test
    fun theAppIsPortraitAndSettingsOpensFromTheMenuAndBackReturnsHome() {
        launchWith(ARSHAD.copy(lock = null)) { TestMobileApp(gate) }
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, compose.activity.requestedOrientation)

        compose.onNode(hasContentDescription("Arshad’s profile menu")).performClick()
        compose.waitForIdle()
        tap("Settings")
        compose.onNode(hasContentDescription("Back") and hasClickAction()).performClick()
        compose.waitForIdle()
        // This harness's For you never ranks (no selected profile): Home shows once its hero hold ends.
        shadowOf(Looper.getMainLooper()).idleFor(HomeViewModel.HeroHoldMs, TimeUnit.MILLISECONDS)
        compose.waitForIdle()

        compose.onNode(hasText("Follow channels to fill Home")).assertExists()
    }

    /** The notification's tap waits for the shell: the unlock first, then it is taken. */
    @Test
    fun theNotificationsTapWaitsThroughTheUnlock() {
        var expandPlayer by mutableStateOf(true)
        locks.locks[ARSHAD.id] = LockSecret.Sequence(SEQUENCE)
        launchWith(ARSHAD, YURI) { TestMobileApp(gate, expandPlayer = expandPlayer, onPlayerExpanded = { expandPlayer = false }) }
        tap("Arshad")
        assertTrue("still waiting under the unlock", expandPlayer)

        tapSequence(SEQUENCE)
        tapKeys("OK")

        assertFalse("taken by the shell", expandPlayer)
    }

    /** KP-D11 through the app's own wiring: a kid's Home is the owner's picks, never the adult Home. */
    @Test
    fun aKidsHomeShowsTheOwnersPicksThroughTheApp() {
        locks.locks[ARSHAD.id] = LockSecret.Sequence(SEQUENCE)
        locks.ownerId = ARSHAD.id
        locks.kids += ZAINAB.id
        val filters = FakeFilterStore()
        val pick = ApprovedVideo("aqz-KE-bpKQ", "Big Buck Bunny", "Blender", null, null, durationSeconds = 596)
        filters.rulesById.value = mapOf(ZAINAB.id to ProfileRules(rules = listOf(StoredRule(pick, byOwner = true, addedAtMillis = 0)), kids = true))
        val kids = KidsStores(kidsClock, FakeKidsTimeStore(), FakeKidsReviewKeeps(), ServerClock(TimeSource.Monotonic))
        val passAll = flowOf(ContentFilter.of(ProfileSettings(), ProfileRules()))
        launchWith(ARSHAD, ZAINAB) { TestMobileApp(gate, kids, profileStore = store, filterStore = filters, contentFilter = passAll) }

        tap("Zainab")
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Big Buck Bunny")).fetchSemanticsNodes().isNotEmpty() }

        compose.onNode(hasText("Picked for you by Arshad")).assertExists()
        compose.onNode(hasText("Follow channels to fill Home")).assertDoesNotExist()
    }

    @Test
    fun aPinOpensOnItsFourthDigit() {
        locks.locks[MIRA.id] = LockSecret.Pin("2461")
        launch(MIRA, YURI)
        tap("Mira")

        tapKeys("2", "4", "9")
        tapKeys("Erase")
        tapKeys("6", "1")

        compose.onNode(hasText("Mira shell")).assertExists()
    }

    @Test
    fun anUnlockedProfileOpensFromThePicker() {
        launch(ARSHAD.copy(lock = null), YURI)

        tap("Yuri")

        compose.onNode(hasText("Yuri shell")).assertExists()
    }

    @Test
    fun firstLaunchNamesTheOwnerAndOpensItsShell() {
        launch()
        tap("Create my profile")
        compose.onNode(hasSetTextAction()).performTextInput("Arshad")

        tap("Continue")

        compose.onNode(hasText("Arshad shell")).assertExists()
    }

    @Test
    fun continueWithNoNameAsksForOne() {
        launch()
        tap("Create my profile")

        tap("Continue")

        compose.onNode(hasText("Your profile needs a name first", substring = true)).assertExists()
    }

    @Test
    fun aPickedBackupIsConfirmedWithItsProfilesAndRestored() {
        launch()
        pickFile("Ayn-backup.json", BackupRead.Valid(testBackup("Yuri", "Guests")))
        compose.onNode(hasText("Restore this backup?")).assertExists()
        compose.onNode(hasText("It brings back 2 profiles (Yuri and Guests)", substring = true)).assertExists()

        tap("Restore")

        assertTrue((gate.uiState.value.screen as GateScreen.Restore).step is RestoreStep.Done)
    }

    @Test
    fun aFileThatIsntABackupSaysSoAndBackReturnsToWelcome() {
        launch()
        pickFile("subscriptions.csv", BackupRead.Refused(BackupRefusal.NotABackup))
        compose.onNode(hasText("This file isn’t an Ayn backup")).assertExists()

        tap("Back")

        compose.onNode(hasText("Everything Ayn keeps stays on this phone")).assertExists()
    }

    @Test
    fun chooseProfilesKeepsTheOwnerAndRestoresOnlyTheTickedOnes() {
        launch()
        pickFile("Ayn-backup.json", BackupRead.Valid(testBackup("Yuri", "Mira", "Guests")))
        tap("Choose profiles")
        compose.onNode(hasText("Always restored")).assertExists()

        tap("Guests")
        tap("Restore 2 profiles")

        assertEquals(listOf(RestoreChoice.AddAsNew, RestoreChoice.AddAsNew, RestoreChoice.Skip), backups.restoredChoices.single())
    }

    @Test
    fun theResultsLockMyProfileAndTouchBackOnTheNewLockReturnsToChooseALock() {
        backups.restoreResult = RestoreResult.Restored(
            ownerId = YURI.id,
            profiles = listOf(RestoredProfile(YURI.id, "Yuri", replaced = false, ProfileCounts(5, 16, 0, 0, 0, 0), 24, 24, emptyList())),
        )
        launch()
        pickFile("Ayn-backup.json", BackupRead.Valid(testBackup("Yuri")))
        tap("Restore")
        compose.runOnUiThread { store.rows.value = listOf(YURI.copy(isOwner = true)) }
        compose.waitForIdle()
        compose.onNode(hasText("Backup restored")).assertExists()
        compose.onNode(hasText("Added")).assertExists()
        compose.onNode(hasText("Choose profiles")).assertDoesNotExist()

        tap("Lock my profile")
        tap("4-digit PIN")
        compose.onNode(hasText("New lock · step 1 of 2")).assertExists()
        compose.onNode(hasText("1") and hasClickAction()).performClick()
        compose.waitForIdle()
        compose.onNode(hasContentDescription("1 entered")).assertExists()
        compose.onAllNodes(hasContentDescription("Back")).onLast().performClick()
        compose.waitForIdle()

        val done = (gate.uiState.value.screen as GateScreen.Restore).step as RestoreStep.Done
        assertEquals(OwnerLockStep.ChooseKind, done.lock)
    }

    @Test
    fun doneOnTheResultsOpensTheOwner() {
        backups.restoreResult = RestoreResult.Restored(ownerId = YURI.id)
        launch()
        pickFile("Ayn-backup.json", BackupRead.Valid(testBackup("Yuri")))
        tap("Restore")
        compose.runOnUiThread { store.rows.value = listOf(YURI.copy(isOwner = true)) }
        compose.waitForIdle()

        tap("Done")

        compose.onNode(hasText("Yuri shell")).assertExists()
    }

    private fun pickFile(name: String, read: BackupRead) {
        val uri = Uri.parse("content://downloads/$name")
        backups.reads[uri] = read
        backups.plan = { backup ->
            RestorePlan(firstLaunch = true, roomForNew = backup.profiles.size, profiles = backup.profiles.map { PlannedProfile(it, replaces = null) })
        }
        tap("Restore an Ayn backup")
        assertEquals(PickRequest(PickKind.Restore, profileId = null), pickerRequests.last())
        compose.runOnUiThread { gate.onFilePicked(pickerRequests.last(), uri) { name } }
        compose.waitForIdle()
    }

    /** Zainab's shell open (Arshad, the owner, locked with a sequence), then her time running out. */
    private fun kidsTimeStopped(time: KidsTime) {
        locks.locks[ARSHAD.id] = LockSecret.Sequence(SEQUENCE)
        locks.ownerId = ARSHAD.id
        locks.kids += ZAINAB.id
        launch(ARSHAD, ZAINAB)
        tap("Zainab")
        compose.onNodeWithText("Zainab shell").assertExists()
        compose.runOnUiThread { kidsClock.time.value = time }
        compose.waitForIdle()
    }

    /** `m6-kids` 9, 11–12: Time's up names the owner's lock; More time asks it on Zainab's tries,
     *  then the amount, and the shell comes back. */
    @Test
    fun timesUpsMoreTimeAsksTheOwnersLockThenTheAmountAndReopensTheShell() {
        kidsTimeStopped(KidsTime.TimesUp)
        compose.onNodeWithText("Time’s up for today").assertExists()
        compose.onNodeWithText("More time and Switch profile need Arshad’s lock").assertExists()

        tap("More time")
        compose.onNodeWithText("Only Arshad adds time. Enter Arshad’s sequence, then tap OK.").assertExists()
        tapSequence(SEQUENCE)
        tapKeys("OK")
        compose.onNodeWithText("How much more time?").assertExists()
        tap("15 more minutes")

        compose.onNodeWithText("Zainab shell").assertExists()
        assertEquals(listOf(15), kidsClock.grants)
    }

    /** A video the kid's shell showed is named saved on Time's up, over its picture; OK closes Ayn. */
    @Test
    fun timesUpNamesTheSavedVideoTheShellShowedAndOkClosesAyn() {
        locks.locks[ARSHAD.id] = LockSecret.Sequence(SEQUENCE)
        locks.ownerId = ARSHAD.id
        locks.kids += ZAINAB.id
        launchWith(ARSHAD, ZAINAB) {
            AynTheme {
                MobileGate(gate, chooseFile = {}, kept = kept) { opening ->
                    SideEffect { opening.onPicture("https://i.ytimg.com/vi/JsBZOcqZerk/hqdefault.jpg") }
                    Text("${opening.profile.name} shell")
                }
            }
        }
        tap("Zainab")
        compose.runOnUiThread { kidsClock.time.value = KidsTime.TimesUp }
        compose.waitForIdle()

        compose.onNodeWithText("That’s all for today, Zainab. Your video is saved right where you stopped. See you tomorrow!").assertExists()
        tap("OK")
        assertTrue(compose.activity.isFinishing)
    }

    /** Switch profile asks the owner's lock, then Who's watching? opens. */
    @Test
    fun timesUpsSwitchProfileTakesTheOwnersLockToThePicker() {
        kidsTimeStopped(KidsTime.TimesUp)
        tap("Switch profile")
        compose.onNodeWithText("Only Arshad switches profiles. Enter Arshad’s sequence, then tap OK.").assertExists()
        tapSequence(SEQUENCE)
        tapKeys("OK")

        compose.onNodeWithText("Who’s watching?").assertExists()
    }

    /** Touch Back on More time's pad returns to Time's up even with presses entered. */
    @Test
    fun touchBackOnMoreTimesPadReturnsToTimesUp() {
        kidsTimeStopped(KidsTime.TimesUp)
        tap("More time")
        tapSequence(SEQUENCE.take(2))

        systemBack()

        compose.onNodeWithText("Time’s up for today").assertExists()
    }

    /** `m6-kids` 10: bedtime says when Ayn opens again; no video stopped, so none is named. */
    @Test
    fun bedtimeSaysWhenAynOpensAgain() {
        kidsTimeStopped(KidsTime.Bedtime(LocalTime.of(7, 0)))

        compose.onNodeWithText("It’s bedtime").assertExists()
        compose.onNodeWithText("Ayn opens again at 7:00 AM. Sleep well, Zainab!").assertExists()
    }

    /** `m6-kids` 21 at the gate: a kids profile picked while the owner has no lock asks for one;
     *  the new lock's Back returns to Choose a lock, then Back steps out to the picker. */
    @Test
    fun aKidsProfileWithoutTheOwnersLockAsksForItFirst() {
        locks.ownerId = ARSHAD.id
        locks.kids += ZAINAB.id
        launch(ARSHAD.copy(lock = null), ZAINAB)
        tap("Zainab")

        compose.onNodeWithText("Lock your profile first").assertExists()
        compose.onNodeWithText("Zainab’s kids profile").assertExists()
        tap("Lock my profile")
        tap("Sequence")
        tapSequence(SEQUENCE.take(2))
        systemBack()
        compose.onNodeWithText("Choose a lock").assertExists()
        systemBack()
        tap("Back")

        compose.onNodeWithText("Who’s watching?").assertExists()
    }

    private companion object {
        val ZAINAB = Profile(5, "Zainab", ProfileTint.Violet, isOwner = false, lock = null, isKids = true)
        val ARSHAD = Profile(1, "Arshad", ProfileTint.Amber, isOwner = true, lock = LockKind.Sequence)
        val MIRA = Profile(2, "Mira", ProfileTint.Rose, isOwner = false, lock = LockKind.Pin)
        val YURI = Profile(3, "Yuri", ProfileTint.Teal, isOwner = false, lock = null)
        val GUESTS = Profile(4, "Guests", ProfileTint.Blue, isOwner = false, lock = null)
        /** Four wrong tries in a row are free; the next starts a wait (LockBackoff FREE_TRIES). */
        const val WRONG_TRIES_BEFORE_A_WAIT = 4
        val SEQUENCE = listOf(Press.Up, Press.Up, Press.Down, Press.Left, Press.Right, Press.Down)
    }
}
