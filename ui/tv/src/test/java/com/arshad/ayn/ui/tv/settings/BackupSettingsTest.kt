package com.arshad.ayn.ui.tv.settings

import com.arshad.ayn.ui.tv.testKidsAdd
import com.arshad.ayn.ui.tv.testSync
import com.arshad.ayn.ui.tv.shell.RailShade
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performSemanticsAction
import com.arshad.ayn.core.backup.BackupRead
import com.arshad.ayn.core.backup.BackupRefusal
import com.arshad.ayn.core.backup.ExportResult
import com.arshad.ayn.core.backup.FakeBackupStore
import com.arshad.ayn.core.backup.ImportResult
import com.arshad.ayn.core.backup.RestoreChoice
import com.arshad.ayn.core.backup.RestoreResult
import com.arshad.ayn.core.backup.SavedBackup
import com.arshad.ayn.core.backup.testBackup
import com.arshad.ayn.core.lock.FakeProfileLocks
import com.arshad.ayn.core.lock.LockKind
import com.arshad.ayn.core.lock.LockSecret
import com.arshad.ayn.core.lock.Press
import com.arshad.ayn.core.store.FakeProfileStore
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.ProfileContents
import com.arshad.ayn.core.store.ProfileSession
import com.arshad.ayn.core.store.ProfileTint
import com.arshad.ayn.feature.profiles.PickKind
import com.arshad.ayn.feature.profiles.PickRequest
import com.arshad.ayn.feature.profiles.PickedFile
import com.arshad.ayn.feature.profiles.RestoredBackup
import com.arshad.ayn.feature.settings.BACKUP_SUB
import com.arshad.ayn.feature.settings.BackupScreen
import com.arshad.ayn.feature.settings.BackupViewModel
import com.arshad.ayn.feature.settings.FiltersViewModel
import com.arshad.ayn.feature.settings.PreferencesViewModel
import com.arshad.ayn.core.store.FakeDeviceSettingsStore
import com.arshad.ayn.core.store.FakeFilterStore
import com.arshad.ayn.core.store.FakeProfileSettingsStore
import com.arshad.ayn.feature.settings.ImportProblem
import com.arshad.ayn.feature.settings.ProfilesViewModel
import com.arshad.ayn.feature.settings.RestrictionsViewModel
import com.arshad.ayn.ui.tv.TEST_SEQUENCE
import com.arshad.ayn.ui.tv.TestPicks
import com.arshad.ayn.ui.tv.dpadSelect
import com.arshad.ayn.ui.tv.enterSequence
import com.arshad.ayn.ui.tv.pressBack
import com.arshad.ayn.ui.tv.theme.KeMotion
import com.arshad.ayn.ui.tv.theme.KeTheme
import java.time.LocalDate
import java.time.ZoneId
import kotlin.time.TimeSource
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings › Backup & restore and Import from YouTube on the real ViewModels over the `:core`
 * fakes, with a file picker that returns [pickerResult] through the gate's [picks]
 * (M5-D20), or a pick already waiting as Settings opens: which second category each person
 * gets and that focus chooses it, the saved list, the owner's lock before an export, Which profiles?
 * and the export's results, a restore from the list through the owner's lock and a choice per
 * profile to the gate's handover, deleting a saved backup from its pick (and where focus lands), a
 * refused or unreadable file and which picker each cover opens, a failed restore, the reading
 * screen holding focus, and an import's toast or its no-channels screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp")
class BackupSettingsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val locks = FakeProfileLocks().apply { ownerId = ARSHAD.id }
    private val backups = FakeBackupStore()
    private lateinit var backup: BackupViewModel
    private lateinit var focusManager: FocusManager
    private val shades = mutableListOf<RailShade>()
    private var playbackEnds = 0
    private val handedOver = mutableListOf<Pair<Long, RestoredBackup>>()

    /** What the file picker returns when opened; `null` is a cancelled picker. Its
     *  provider gives no display name. */
    private var pickerResult: Uri? = null
    private var pickerLaunches = 0
    private val picks = TestPicks()

    private fun open(me: Profile, vararg everyone: Profile, pickWaiting: Boolean = false) {
        val store = FakeProfileStore(everyone.toList())
        store.contentsById.value = mapOf(ARSHAD.id to ARSHAD_COUNTS, MIRA.id to MIRA_COUNTS)
        val session = ProfileSession().apply { select(me.id) }
        val profiles = ProfilesViewModel(store, locks, FakeFilterStore(), session, TimeSource.Monotonic)
        backup = BackupViewModel(
            backups = backups,
            profileStore = store,
            locks = locks,
            playback = { playbackEnds++ },
            handover = { id, restored -> handedOver += id to restored },
            picks = picks,
            profileId = me.id,
            timeSource = TimeSource.Monotonic,
        )
        val chooseFile: (PickKind) -> Unit = { kind ->
            pickerLaunches++
            pickerResult?.let { picks.waiting.value = PickedFile(PickRequest(kind, me.id), it, fileName = null) }
        }
        val preferences = PreferencesViewModel(FakeProfileSettingsStore(me.id), FakeDeviceSettingsStore(), store)
        val filters = FiltersViewModel(FakeProfileSettingsStore(me.id), FakeFilterStore(), store, locks)
        val restrictions = RestrictionsViewModel(FakeFilterStore(), store, locks, TimeSource.Monotonic)
        compose.setContent {
            focusManager = LocalFocusManager.current
            KeTheme {
                SettingsScreen(
                    profiles = profiles,
                    backup = backup,
                    preferences = preferences,
                    filters = filters,
                    restrictions = restrictions,
                    kidsAdd = testKidsAdd(store, locks),
                    sync = testSync(store, locks, me.id),
                    restored = null,
                    pickWaiting = pickWaiting,
                    chooseFile = chooseFile,
                    onRestoredShown = {},
                    onNothingToFocus = {},
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

    private fun focus(label: String) {
        clickable(label).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
    }

    private fun onUi(action: () -> Unit) {
        compose.runOnUiThread(action)
        compose.waitForIdle()
    }

    /**
     * The picker reads a picked file's name off the main thread; wait for [text] to show. Polling
     * the nodes idles the main looper, which a ViewModel-state poll wouldn't under Robolectric.
     */
    private fun awaitText(text: String) {
        compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun enter(sequence: List<Press>) {
        compose.onNode(hasContentDescription("D-pad sequence")).enterSequence(sequence)
        compose.waitForIdle()
    }

    @Test
    fun theOwnerChoosesBackupAndRestoreByFocusAndSeesTheirSavedBackups() {
        backups.saved += listOf(OCTOBER, AUGUST)
        backups.reads[OCTOBER.uri] = BackupRead.Valid(testBackup("Arshad", "Mira", "Yuri", "Guests"))
        open(ARSHAD, ARSHAD, MIRA)

        compose.onNode(hasText("Import from YouTube")).assertDoesNotExist()
        focus("Backup & restore")

        compose.onNode(hasText(BACKUP_SUB)).assertExists()
        clickable("Export a backup").assertExists()
        compose.onNode(hasText("4 profiles · saved October 3")).assertExists()
        compose.onNode(hasText("saved August 30")).assertExists()
        clickable("Find a backup…").assertExists()
        compose.onNode(hasText("The subscriptions.csv file from Google Takeout, into your profile")).assertExists()
    }

    @Test
    fun someoneElseImportsTheFileThePickerReturnsAndIsToldWhatWasAdded() {
        backups.importResult = ImportResult.Imported(added = 23, alreadySubscribed = 4)
        pickerResult = TAKEOUT
        open(MIRA, ARSHAD, MIRA)

        compose.onNode(hasText("Backup & restore")).assertDoesNotExist()
        focus("Import from YouTube")
        compose.onNode(hasText("The subscriptions.csv file from Google Takeout, or a NewPipe export (file picker)")).assertExists()
        clickable("Export a backup").assertDoesNotExist()
        select("Import subscriptions from YouTube")

        awaitText("23 channels added · 4 were already in your subscriptions")
        assertEquals(listOf(TAKEOUT), backups.imported)
    }

    @Test
    fun anImportOfChannelsAllAlreadyThereSaysNothingNew() {
        backups.importResult = ImportResult.Imported(added = 0, alreadySubscribed = 27)
        open(MIRA, ARSHAD, MIRA)
        focus("Import from YouTube")

        onUi { backup.onImportFilePicked(TAKEOUT, "subscriptions.csv") }

        compose.onNode(hasText("Nothing new · all 27 channels were already in your subscriptions")).assertExists()
    }

    @Test
    fun aFileWithNoChannelsOffersAnotherFromThePicker() {
        backups.importResult = ImportResult.Imported(added = 0, alreadySubscribed = 0)
        pickerResult = TAKEOUT
        open(MIRA, ARSHAD, MIRA)
        focus("Import from YouTube")
        select("Import subscriptions from YouTube")
        awaitText("No YouTube channels in this file")

        assertEquals(ImportProblem.NoChannels, (backup.uiState.value.screen as BackupScreen.ImportRefused).problem)
        clickable("Choose another file").assertIsFocused()
        backups.importResult = ImportResult.Imported(added = 2, alreadySubscribed = 0)
        select("Choose another file")

        awaitText("2 channels added")
        assertEquals(listOf(TAKEOUT, TAKEOUT), backups.imported)
        assertEquals(BackupScreen.Page, backup.uiState.value.screen)
    }

    @Test
    fun theSameRefusedFilePickedAgainKeepsFocusOnItsCover() {
        backups.importResult = ImportResult.Imported(added = 0, alreadySubscribed = 0)
        pickerResult = TAKEOUT
        open(MIRA, ARSHAD, MIRA)
        focus("Import from YouTube")
        select("Import subscriptions from YouTube")
        awaitText("No YouTube channels in this file")
        // The import answers a few frames later, as on a device: while the cover fades out.
        val importing = CompletableDeferred<Unit>()
        backups.importGate = importing
        compose.mainClock.autoAdvance = false

        // The same file again, straight to the ViewModel: the picker's name read hops threads.
        compose.runOnUiThread { backup.onImportFilePicked(TAKEOUT, null) }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(FADE_PART_MS)
        assertEquals(BackupScreen.Page, backup.uiState.value.screen)
        compose.onNode(hasText("No YouTube channels in this file")).assertExists()
        compose.runOnUiThread { importing.complete(Unit) }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()

        compose.onNode(hasText("No YouTube channels in this file")).assertExists()
        clickable("Choose another file").assertIsFocused()
        clickable("Import subscriptions from YouTube").assertIsNotFocused()
    }

    @Test
    fun backFromAFileWithNoChannelsReturnsToTheImportRow() {
        backups.importResult = ImportResult.Imported(added = 0, alreadySubscribed = 0)
        open(MIRA, ARSHAD, MIRA)
        focus("Import from YouTube")
        focus("Import subscriptions from YouTube")
        onUi { backup.onImportFilePicked(TAKEOUT, "watch-history.html") }
        compose.onNode(hasText("watch-history.html")).assertExists()

        compose.pressBack()

        assertEquals(BackupScreen.Page, backup.uiState.value.screen)
        clickable("Import subscriptions from YouTube").assertIsFocused()
    }

    @Test
    fun leavingAPageForTheCategoriesLandsOnTheOneShowing() {
        open(ARSHAD, ARSHAD, MIRA, YURI)
        // Add a profile sits lower than the Backup & restore category.
        focus("Add a profile")

        onUi { focusManager.moveFocus(FocusDirection.Left) }

        clickable("Profiles").assertIsFocused()
        clickable("Add a profile").assertExists()
    }

    @Test
    fun leavingTheBackupPageForTheCategoriesKeepsItShowing() {
        open(ARSHAD, ARSHAD, MIRA)
        focus("Backup & restore")
        // Export a backup sits level with the Profiles category.
        focus("Export a backup")

        onUi { focusManager.moveFocus(FocusDirection.Left) }

        clickable("Backup & restore").assertIsFocused()
        clickable("Export a backup").assertExists()
    }

    @Test
    fun findABackupReadsTheFileThePickerReturns() {
        pickerResult = NOT_A_BACKUP
        backups.reads[NOT_A_BACKUP] = BackupRead.Refused(BackupRefusal.NotABackup)
        backups.reads[SEPTEMBER.uri] = BackupRead.Valid(testBackup("Arshad", exportedAtMillis = SEPTEMBER_12))
        open(ARSHAD, ARSHAD)
        focus("Backup & restore")

        select("Find a backup…")
        awaitText("This file isn’t an Ayn backup")
        // The test's content provider gives no display name.
        compose.onNode(hasText("The chosen file")).assertExists()
        pickerResult = SEPTEMBER.uri
        select("Choose another file")

        awaitText("Restore which profiles?")
        assertEquals(2, pickerLaunches)
        // A picked file isn't this install's to delete (BKD-D2).
        clickable("Delete this backup").assertDoesNotExist()
    }

    @Test
    fun theSameRefusedBackupPickedAgainKeepsFocusOnItsCover() {
        pickerResult = NOT_A_BACKUP
        backups.reads[NOT_A_BACKUP] = BackupRead.Refused(BackupRefusal.NotABackup)
        open(ARSHAD, ARSHAD)
        focus("Backup & restore")
        select("Find a backup…")
        awaitText("This file isn’t an Ayn backup")
        // The read answers while the refusal still fades out under the reading screen.
        val reading = CompletableDeferred<Unit>()
        backups.openGate = reading
        compose.mainClock.autoAdvance = false

        compose.runOnUiThread { backup.onBackupFilePicked(NOT_A_BACKUP, null) }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(FADE_PART_MS)
        assertEquals(BackupScreen.Reading(null), backup.uiState.value.screen)
        compose.onNode(hasText("This file isn’t an Ayn backup")).assertExists()
        compose.runOnUiThread { reading.complete(Unit) }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()

        clickable("Choose another file").assertIsFocused()
    }

    @Test
    fun anImportPickWaitingAsSettingsOpensIsTakenOnTheImportCategory() {
        backups.importResult = ImportResult.Imported(added = 23, alreadySubscribed = 4)
        picks.waiting.value = PickedFile(PickRequest(PickKind.Import, MIRA.id), TAKEOUT, "subscriptions.csv")

        open(MIRA, ARSHAD, MIRA, pickWaiting = true)

        awaitText("23 channels added · 4 were already in your subscriptions")
        assertEquals(listOf(TAKEOUT), backups.imported)
        assertNull(picks.waiting.value)
        clickable("Import from YouTube").assertIsFocused()
        clickable("Import subscriptions from YouTube").assertExists()
    }

    @Test
    fun aBackupPickWaitingAsSettingsOpensIsReadAndBackLandsOnItsCategory() {
        backups.reads[SEPTEMBER.uri] = BackupRead.Valid(testBackup("Arshad", exportedAtMillis = SEPTEMBER_12))
        picks.waiting.value = PickedFile(PickRequest(PickKind.Backup, ARSHAD.id), SEPTEMBER.uri, SEPTEMBER.fileName)

        open(ARSHAD, ARSHAD, pickWaiting = true)
        awaitText("Restore which profiles?")
        compose.pressBack()

        assertEquals(BackupScreen.Page, backup.uiState.value.screen)
        clickable("Backup & restore").assertIsFocused()
        clickable("Find a backup…").assertExists()
    }

    @Test
    fun aCancelledPickerLeavesThePageAsItWas() {
        open(ARSHAD, ARSHAD)
        focus("Backup & restore")

        select("Find a backup…")

        assertEquals(1, pickerLaunches)
        assertEquals(BackupScreen.Page, backup.uiState.value.screen)
        assertEquals(RailShade.Lit, shades.last())
    }

    @Test
    fun aSavedBackupIsRestoredAfterTheOwnersLockAndItsChoicesThenHandedToTheGate() {
        locks.locks[ARSHAD.id] = LockSecret.Sequence(TEST_SEQUENCE)
        backups.saved += SEPTEMBER
        backups.reads[SEPTEMBER.uri] = BackupRead.Valid(testBackup("Arshad", "Mira", "Yuri", exportedAtMillis = SEPTEMBER_12))
        open(ARSHAD_LOCKED, ARSHAD_LOCKED, MIRA)
        focus("Backup & restore")

        select(SEPTEMBER.fileName)
        compose.onNode(hasText("Restoring a backup")).assertExists()
        compose.onNode(hasText("Only the owner restores backups. Enter Arshad’s sequence, then press OK.")).assertExists()
        enter(TEST_SEQUENCE)
        awaitText("Restore which profiles?")
        assertEquals(RailShade.Hidden, shades.last())
        clickable("Arshad").assertIsFocused()
        compose.onNode(hasText("Adds 3 profiles")).assertExists()

        select("Mira")
        compose.onNode(hasText("In the backup from September 12")).assertExists()
        select("Don’t restore")
        clickable("Mira").assertIsFocused()
        select("Restore 2 profiles")

        assertEquals(1, playbackEnds)
        assertEquals(listOf(RestoreChoice.AddAsNew, RestoreChoice.Skip, RestoreChoice.AddAsNew), backups.restoredChoices.single())
        assertEquals(listOf(1L to RestoredBackup(SEPTEMBER.fileName, SEPTEMBER_12, RestoreResult.Restored(ownerId = 1))), handedOver)
    }

    @Test
    fun keepWhatIsHereReturnsToTheBackupsRow() {
        backups.saved += SEPTEMBER
        backups.reads[SEPTEMBER.uri] = BackupRead.Valid(testBackup("Arshad", exportedAtMillis = SEPTEMBER_12))
        open(ARSHAD, ARSHAD)
        focus("Backup & restore")
        select(SEPTEMBER.fileName)

        select("Keep what’s here")

        assertEquals(BackupScreen.Page, backup.uiState.value.screen)
        assertEquals(RailShade.Lit, shades.last())
        assertEquals(emptyList<Any>(), backups.restored)
        clickable(SEPTEMBER.fileName).assertIsFocused()
    }

    @Test
    fun backClosesAChoiceSheetThenLeavesTheRestorePick() {
        backups.saved += SEPTEMBER
        backups.reads[SEPTEMBER.uri] = BackupRead.Valid(testBackup("Arshad", "Mira", exportedAtMillis = SEPTEMBER_12))
        open(ARSHAD, ARSHAD)
        focus("Backup & restore")
        select(SEPTEMBER.fileName)
        select("Mira")

        compose.pressBack()
        clickable("Mira").assertIsFocused()
        compose.pressBack()

        assertEquals(BackupScreen.Page, backup.uiState.value.screen)
        clickable(SEPTEMBER.fileName).assertIsFocused()
    }

    @Test
    fun deleteBackupRemovesTheSavedBackupAndFocusLandsOnTheNextOne() {
        backups.saved += listOf(OCTOBER, SEPTEMBER, AUGUST)
        backups.reads[SEPTEMBER.uri] = BackupRead.Valid(testBackup("Arshad", "Mira", exportedAtMillis = SEPTEMBER_12))
        open(ARSHAD, ARSHAD)
        focus("Backup & restore")
        select(SEPTEMBER.fileName)

        select("Delete this backup")
        compose.onNode(hasText("Delete this backup?")).assertExists()
        compose.onNode(hasText("Saved September 12")).assertExists()
        compose.onNode(hasText("${SEPTEMBER.fileName} holds 2 profiles. Deleting it removes the file from Downloads. It can’t be undone.")).assertExists()
        compose.onNode(hasText("Other backups and the profiles on this Shield stay as they are.")).assertExists()
        clickable("Keep it").assertIsFocused()
        select("Delete backup")

        assertEquals(listOf(SEPTEMBER.uri), backups.deleted)
        assertEquals(BackupScreen.Page, backup.uiState.value.screen)
        clickable(SEPTEMBER.fileName).assertDoesNotExist()
        clickable(AUGUST.fileName).assertIsFocused()
        compose.onNode(hasText("Deleted · ${SEPTEMBER.fileName}")).assertExists()
    }

    @Test
    fun deletingTheLastSavedBackupLandsOnFindABackup() {
        backups.saved += SEPTEMBER
        backups.reads[SEPTEMBER.uri] = BackupRead.Valid(testBackup("Arshad", exportedAtMillis = SEPTEMBER_12))
        open(ARSHAD, ARSHAD)
        focus("Backup & restore")
        select(SEPTEMBER.fileName)
        select("Delete this backup")

        select("Delete backup")

        clickable("Find a backup…").assertIsFocused()
    }

    @Test
    fun keepItClosesTheDeleteSheetBackOnItsButton() {
        backups.saved += SEPTEMBER
        backups.reads[SEPTEMBER.uri] = BackupRead.Valid(testBackup("Arshad", exportedAtMillis = SEPTEMBER_12))
        open(ARSHAD, ARSHAD)
        focus("Backup & restore")
        select(SEPTEMBER.fileName)
        select("Delete this backup")

        select("Keep it")

        compose.onNode(hasText("Delete this backup?")).assertDoesNotExist()
        clickable("Delete this backup").assertIsFocused()
        assertEquals(emptyList<Uri>(), backups.deleted)
    }

    @Test
    fun aDeleteThatFailsKeepsTheBackupAndSaysSo() {
        backups.saved += SEPTEMBER
        backups.reads[SEPTEMBER.uri] = BackupRead.Valid(testBackup("Arshad", exportedAtMillis = SEPTEMBER_12))
        backups.deleteFails = true
        open(ARSHAD, ARSHAD)
        focus("Backup & restore")
        select(SEPTEMBER.fileName)
        select("Delete this backup")

        select("Delete backup")

        compose.onNode(hasText("Couldn’t delete the backup")).assertExists()
        compose.onNode(hasText("Restore which profiles?")).assertExists()
        clickable("Delete this backup").assertIsFocused()
    }

    @Test
    fun whileAFileIsReadTheCoverHoldsFocusAndThePageNone() {
        backups.saved += SEPTEMBER
        backups.openGate = CompletableDeferred()
        open(ARSHAD, ARSHAD)
        focus("Backup & restore")

        select(SEPTEMBER.fileName)

        assertEquals(BackupScreen.Reading(SEPTEMBER.fileName), backup.uiState.value.screen)
        compose.onNode(hasText(SEPTEMBER.fileName) and hasClickAction()).assertIsNotFocused()
        compose.onNode(isFocused()).assertExists()
    }

    @Test
    fun backWhileAFileIsReadReturnsToItsRow() {
        backups.saved += SEPTEMBER
        backups.openGate = CompletableDeferred()
        open(ARSHAD, ARSHAD)
        focus("Backup & restore")
        select(SEPTEMBER.fileName)

        compose.pressBack()

        assertEquals(BackupScreen.Page, backup.uiState.value.screen)
        clickable(SEPTEMBER.fileName).assertIsFocused()
    }

    @Test
    fun aFileThatIsNotABackupPointsAtTheImport() {
        backups.reads[TAKEOUT] = BackupRead.Refused(BackupRefusal.NotABackup)
        open(ARSHAD, ARSHAD)
        focus("Backup & restore")

        onUi { backup.onBackupFilePicked(TAKEOUT, "subscriptions.csv") }

        compose.onNode(hasText("This file isn’t an Ayn backup")).assertExists()
        compose.onNode(hasText("To add subscriptions from a Google Takeout file, use Import subscriptions from YouTube.", substring = true)).assertExists()
        clickable("Choose another file").assertIsFocused()
        select("Back")
        assertEquals(BackupScreen.Page, backup.uiState.value.screen)
    }

    @Test
    fun aRestoreThatFailsOffersToTryAgain() {
        backups.restoreResult = RestoreResult.Failed
        backups.saved += SEPTEMBER
        backups.reads[SEPTEMBER.uri] = BackupRead.Valid(testBackup("Arshad", exportedAtMillis = SEPTEMBER_12))
        open(ARSHAD, ARSHAD)
        focus("Backup & restore")
        select(SEPTEMBER.fileName)

        select("Restore 1 profile")

        compose.onNode(hasText("The restore didn’t finish")).assertExists()
        clickable("Try again").assertIsFocused()
        assertEquals(emptyList<Pair<Long, RestoredBackup>>(), handedOver)
    }

    @Test
    fun tryAgainRunsTheRestoreOnceMore() {
        backups.restoreResult = RestoreResult.Failed
        backups.saved += SEPTEMBER
        backups.reads[SEPTEMBER.uri] = BackupRead.Valid(testBackup("Arshad", exportedAtMillis = SEPTEMBER_12))
        open(ARSHAD, ARSHAD)
        focus("Backup & restore")
        select(SEPTEMBER.fileName)
        select("Restore 1 profile")
        backups.restoreResult = RestoreResult.Restored(ownerId = 1)

        select("Try again")

        assertEquals(listOf(1L to RestoredBackup(SEPTEMBER.fileName, SEPTEMBER_12, RestoreResult.Restored(ownerId = 1))), handedOver)
    }

    @Test
    fun allProfilesExportsAtOnceAndTheResultsSayWhatWentIn() {
        backups.exportResult = ExportResult.Saved("Ayn-backup-2026-10-05.json", testBackup("Arshad", "Mira").profiles)
        open(ARSHAD, ARSHAD, MIRA)
        focus("Backup & restore")

        select("Export a backup")
        compose.onNode(hasText("Which profiles?")).assertExists()
        clickable("All profiles").assertIsFocused()
        select("All profiles")

        compose.onNode(hasText("Downloads/Ayn-backup-2026-10-05.json")).assertExists()
        compose.onNode(hasText("Backup saved")).assertExists()
        assertEquals(listOf<Set<Long>?>(null), backups.exportedIds)
        select("Done")
        clickable("Export a backup").assertIsFocused()
    }

    @Test
    fun chooseProfilesExportsOnlyTheTickedOnes() {
        backups.exportResult = ExportResult.Saved("Ayn-backup-2026-10-05.json", testBackup("Arshad").profiles)
        open(ARSHAD, ARSHAD, MIRA)
        focus("Backup & restore")
        select("Export a backup")

        select("Choose profiles")
        clickable("Arshad").assertIsFocused()
        select("Mira")
        select("Export 1 profile")

        assertEquals(listOf<Set<Long>?>(setOf(ARSHAD.id)), backups.exportedIds)
    }

    @Test
    fun anExportThatFailsSaysNothingChanged() {
        backups.exportResult = ExportResult.Failed
        open(ARSHAD, ARSHAD)
        focus("Backup & restore")

        select("Export a backup")
        select("All profiles")

        compose.onNode(hasText("The backup wasn’t saved · nothing changed; try again")).assertExists()
    }

    @Test
    fun aLockedOwnerUnlocksBeforeWhichProfiles() {
        locks.locks[ARSHAD.id] = LockSecret.Sequence(TEST_SEQUENCE)
        open(ARSHAD_LOCKED, ARSHAD_LOCKED)
        focus("Backup & restore")

        select("Export a backup")
        compose.onNode(hasText("Exporting a backup")).assertExists()
        compose.onNode(hasText("Only the owner exports backups. Enter Arshad’s sequence, then press OK.")).assertExists()
        enter(TEST_SEQUENCE)

        compose.onNode(hasText("Which profiles?")).assertExists()
        assertEquals(0, backups.exports)
    }

    private companion object {
        // A third of a cover's fade (KeMotion.base()), so the store answers mid-fade.
        const val FADE_PART_MS = KeMotion.BASE_MS / 3L
        val ARSHAD = Profile(1, "Arshad", ProfileTint.Amber, isOwner = true, lock = null)
        val ARSHAD_LOCKED = ARSHAD.copy(lock = LockKind.Sequence)
        val MIRA = Profile(2, "Mira", ProfileTint.Rose, isOwner = false, lock = LockKind.Pin)
        val YURI = Profile(3, "Yuri", ProfileTint.Teal, isOwner = false, lock = null)
        val ARSHAD_COUNTS = ProfileContents(subscriptionCount = 11, playlistCount = 2, historyCount = 180)
        val MIRA_COUNTS = ProfileContents(subscriptionCount = 7, playlistCount = 1, historyCount = 96)

        fun thisYear(month: Int, day: Int): Long =
            LocalDate.now().withMonth(month).withDayOfMonth(day).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

        val SEPTEMBER_12 = thisYear(9, 12)
        val OCTOBER = SavedBackup(Uri.parse("content://downloads/1"), "Ayn-backup-2026-10-03.json", thisYear(10, 3))
        val AUGUST = SavedBackup(Uri.parse("content://downloads/2"), "Ayn-backup-2026-08-30.json", thisYear(8, 30))
        val SEPTEMBER = SavedBackup(Uri.parse("content://downloads/3"), "Ayn-backup-2026-09-12.json", SEPTEMBER_12)
        val TAKEOUT: Uri = Uri.parse("content://downloads/takeout")
        val NOT_A_BACKUP: Uri = Uri.parse("content://downloads/notes")
    }
}
