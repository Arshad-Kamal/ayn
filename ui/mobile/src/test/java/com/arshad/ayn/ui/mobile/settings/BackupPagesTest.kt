package com.arshad.ayn.ui.mobile.settings

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.arshad.ayn.core.backup.BackupProfileSummary
import com.arshad.ayn.core.backup.BackupRead
import com.arshad.ayn.core.backup.BackupRefusal
import com.arshad.ayn.core.backup.ExportResult
import com.arshad.ayn.core.backup.ImportResult
import com.arshad.ayn.core.backup.PlannedProfile
import com.arshad.ayn.core.backup.ProfileCounts
import com.arshad.ayn.core.backup.RestoreChoice
import com.arshad.ayn.core.backup.RestorePlan
import com.arshad.ayn.core.backup.RestoreResult
import com.arshad.ayn.core.backup.RestoredProfile
import com.arshad.ayn.core.backup.SavedBackup
import com.arshad.ayn.core.backup.SettingSkip
import com.arshad.ayn.core.backup.testBackup
import com.arshad.ayn.core.lock.LockKind
import com.arshad.ayn.core.lock.LockSecret
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.ProfileTint
import com.arshad.ayn.feature.profiles.PickKind
import com.arshad.ayn.feature.profiles.PickRequest
import com.arshad.ayn.feature.profiles.PickedFile
import com.arshad.ayn.feature.profiles.RestoredBackup
import com.arshad.ayn.feature.settings.ResultRow
import com.arshad.ayn.ui.mobile.installOfflineImageLoader
import com.arshad.ayn.ui.mobile.library.TestStores
import com.arshad.ayn.ui.mobile.shell.MobileShell
import com.arshad.ayn.ui.mobile.shell.SettingsPage
import com.arshad.ayn.ui.mobile.shell.ShellNav
import com.arshad.ayn.ui.mobile.shell.ShellOpening
import com.arshad.ayn.ui.mobile.shellPages
import com.arshad.ayn.ui.mobile.theme.AynTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings › Backup & restore on the phone (`m6-settings` 37–45, 48–50) through the shell's real page
 * wiring on the stores' fakes: export (all, chosen), a picked backup through the owner's lock to
 * "Restore which profiles?" and a row's choice, deleting a saved backup from that page, a refused file, the import page with its
 * snackbar, a pick waiting for the page, and a restore's results on the reopened shell.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class BackupPagesTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val stores = TestStores()
    private lateinit var nav: ShellNav
    private var restoredShown = 0

    @Before
    fun setUp() {
        installOfflineImageLoader(compose.activity)
        stores.people.rows.value = listOf(ARSHAD, MIRA, YURI)
        stores.locks.ownerId = ARSHAD.id
        stores.locks.known += listOf(ARSHAD.id, MIRA.id, YURI.id)
        stores.locks.locks[ARSHAD.id] = LockSecret.Pin("1234")
    }

    private fun open(me: Profile = ARSHAD, restored: RestoredBackup? = null, pickWaiting: Boolean = false) {
        stores.settings.selected.value = me.id
        stores.profileSession.select(me.id)
        val models = stores.models()
        compose.setContent {
            AynTheme {
                val opening = ShellOpening(me, onSwitchProfile = {}, restored = restored, settingsPickWaiting = pickWaiting, onRestoredShown = { restoredShown++ })
                MobileShell(opening) { chrome ->
                    nav = chrome.nav
                    shellPages(chrome, models)
                }
            }
        }
        compose.waitForIdle()
        if (restored == null && !pickWaiting) compose.runOnUiThread { nav.openSettings(SettingsPage.Backup) }
        compose.waitForIdle()
    }

    private fun click(label: String) {
        compose.onNode(hasText(label) and hasClickAction()).performClick()
        compose.waitForIdle()
    }

    private fun digits(pin: String) {
        pin.forEach { compose.onNode(hasText(it.toString()) and hasClickAction()).performClick() }
        compose.waitForIdle()
    }

    /** The file picker gave [uri] back for [request]. */
    private fun picked(request: PickRequest, uri: Uri, name: String) {
        compose.runOnUiThread { stores.picks.settingsPick.value = PickedFile(request, uri, name) }
        compose.waitForIdle()
    }

    @Test
    fun theOwnersPageListsThisPhonesBackupsAndChoosingProfilesExportsTheTickedOnes() {
        stores.backups.saved += SavedBackup(SAVED, "Ayn-backup-2026-10-14.json", 0)
        stores.backups.reads[SAVED] = BackupRead.Valid(testBackup("Arshad", "Mira", "Yuri", "Guests"))
        stores.backups.exportResult = ExportResult.Saved("Ayn-backup-2026-10-16.json", listOf(summary("Arshad", owner = true), summary("Mira")))
        open()
        compose.onNodeWithText("4 profiles", substring = true).assertExists()

        click("Export a backup")
        digits("1234")
        compose.onNodeWithText("Which profiles?").assertExists()
        click("Choose profiles")
        click("Yuri")
        click("Export 2 profiles")

        assertEquals(setOf(ARSHAD.id, MIRA.id), stores.backups.exportedIds.single())
        compose.onNodeWithText("Backup saved").assertExists()
        compose.onNodeWithText("Downloads/Ayn-backup-2026-10-16.json").assertExists()
        click("Done")
        compose.onNodeWithText("Backup saved").assertDoesNotExist()
    }

    @Test
    fun aPickedBackupAsksTheOwnersLockThenEachRowsChoiceAndRestoresThem() {
        stores.backups.reads[PICKED] = BackupRead.Valid(testBackup("Arshad", "Mira"))
        stores.backups.plan = { backup ->
            RestorePlan(firstLaunch = false, roomForNew = 0, profiles = backup.profiles.mapIndexed { i, p -> PlannedProfile(p, listOf(ARSHAD.id, MIRA.id)[i]) })
        }
        open()
        click("Find a backup…")
        assertEquals(PickRequest(PickKind.Backup, ARSHAD.id), stores.picked.single())
        picked(stores.picked.single(), PICKED, "Ayn-backup-2026-10-14.json")
        compose.onNodeWithText("Restoring a backup").assertExists()
        digits("1234")

        compose.onNodeWithText("Restore which profiles?").assertExists()
        compose.onNodeWithText("Replaces 2 profiles").assertExists()
        // A picked file isn't this phone's to delete (BKD-D2).
        compose.onNodeWithText("Delete this backup").assertDoesNotExist()
        click("Mira")
        compose.onNode(hasText("Add as new") and hasClickAction()).assertIsNotEnabled()
        click("Don’t restore")
        click("Restore 1 profile")

        assertEquals(listOf(RestoreChoice.Replace(ARSHAD.id), RestoreChoice.Skip), stores.backups.restoredChoices.single())
    }

    @Test
    fun aSavedBackupIsDeletedFromItsRestorePageAfterTheConfirm() {
        stores.backups.saved += SavedBackup(SAVED, SAVED_NAME, 0)
        stores.backups.reads[SAVED] = BackupRead.Valid(testBackup("Arshad", "Mira"))
        open()
        click(SAVED_NAME)
        digits("1234")

        click("Delete this backup")
        compose.onNodeWithText("Delete this backup?").assertExists()
        compose.onNodeWithText("$SAVED_NAME holds 2 profiles. Deleting it removes the file from Downloads. It can’t be undone.").assertExists()
        compose.onNodeWithText("Other backups and the profiles on this phone stay as they are.").assertExists()
        click("Delete backup")

        assertEquals(listOf(SAVED), stores.backups.deleted)
        compose.onNodeWithText("Restore which profiles?").assertDoesNotExist()
        compose.onNode(hasText(SAVED_NAME) and hasClickAction()).assertDoesNotExist()
        compose.onNodeWithText("Deleted · $SAVED_NAME").assertExists()
    }

    @Test
    fun aDeleteThatFailsKeepsTheRestorePageAndSaysSo() {
        stores.backups.saved += SavedBackup(SAVED, SAVED_NAME, 0)
        stores.backups.reads[SAVED] = BackupRead.Valid(testBackup("Arshad"))
        stores.backups.deleteFails = true
        open()
        click(SAVED_NAME)
        digits("1234")
        click("Delete this backup")

        click("Delete backup")

        compose.onNodeWithText("Couldn’t delete the backup").assertExists()
        compose.onNodeWithText("Delete this backup?").assertDoesNotExist()
        compose.onNodeWithText("Restore which profiles?").assertExists()
    }

    @Test
    fun keepItClosesTheDeleteSheetAndKeepsTheBackup() {
        stores.backups.saved += SavedBackup(SAVED, SAVED_NAME, 0)
        stores.backups.reads[SAVED] = BackupRead.Valid(testBackup("Arshad"))
        open()
        click(SAVED_NAME)
        digits("1234")
        click("Delete this backup")

        click("Keep it")

        compose.onNodeWithText("Delete this backup?").assertDoesNotExist()
        compose.onNodeWithText("Restore which profiles?").assertExists()
        assertTrue(stores.backups.deleted.isEmpty())
    }

    @Test
    fun aFileThatIsntABackupPointsAtTheImportAndChoosesAnother() {
        stores.backups.reads[PICKED] = BackupRead.Refused(BackupRefusal.NotABackup)
        stores.locks.locks.remove(ARSHAD.id)
        open()
        click("Find a backup…")
        picked(stores.picked.single(), PICKED, "subscriptions.csv")

        compose.onNodeWithText("This file isn’t an Ayn backup").assertExists()
        compose.onNodeWithText("use Import subscriptions from YouTube", substring = true).assertExists()
        click("Choose another file")
        assertEquals(2, stores.picked.size)
    }

    @Test
    fun anyoneElseImportsIntoTheirProfileAndTheSnackbarSaysWhatWasAdded() {
        stores.backups.importResult = ImportResult.Imported(added = 31, alreadySubscribed = 4)
        open(MIRA)
        compose.onNodeWithText("Import from YouTube").assertExists()
        compose.onNodeWithText("Export a backup").assertDoesNotExist()

        click("Import subscriptions from YouTube")
        picked(PickRequest(PickKind.Import, MIRA.id), PICKED, "subscriptions.csv")

        assertEquals(listOf(MIRA.id), stores.backups.importedInto)
        compose.onNodeWithText("31 channels added", substring = true).assertExists()
    }

    @Test
    fun aPickWaitingForSettingsOpensBackupAndIsTaken() {
        stores.backups.importResult = ImportResult.Imported(added = 2, alreadySubscribed = 0)
        stores.picks.settingsPick.value = PickedFile(PickRequest(PickKind.Import, MIRA.id), PICKED, "subscriptions.csv")

        open(MIRA, pickWaiting = true)

        assertEquals(listOf(MIRA.id), stores.backups.importedInto)
        compose.onNodeWithText("2 channels added", substring = true).assertExists()
    }

    @Test
    fun aRestoresResultsShowOnTheReopenedShellWithWhatDidntCarryOver() {
        val result = RestoreResult.Restored(
            ownerId = ARSHAD.id,
            profiles = listOf(
                RestoredProfile(MIRA.id, "Mira", replaced = true, ProfileCounts(8, 37, 1, 9, 0, 0), 23, 24, listOf(SettingSkip("speedPercent", "300", "100"))),
            ),
        )

        open(restored = RestoredBackup("Ayn-backup-2026-10-14.json", 0, result))

        compose.onNodeWithText("Not everything was restored").assertExists()
        compose.onNodeWithText("Mira · Playback speed").assertExists()
        compose.onNodeWithText("23 of 24 settings", substring = true).assertExists()
        compose.onNodeWithText("Replaced").assertExists()
        assertEquals(1, restoredShown)
        click("Done")
        compose.onNodeWithText("Export a backup").assertExists()
    }

    @Test
    fun touchBackLeavesTheOwnersLockWithADigitEntered() {
        open()
        click("Export a backup")
        compose.onNode(hasText("1") and hasClickAction()).performClick()
        compose.waitForIdle()
        compose.onNode(hasContentDescription("1 entered")).assertExists()

        compose.onAllNodes(hasContentDescription("Back")).onLast().performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Exporting a backup").assertDoesNotExist()
        assertTrue(stores.locks.tries.isEmpty())
    }

    @Test
    fun aFileWithNoChannelsSaysSoAndOffersAnother() {
        stores.backups.importResult = ImportResult.Imported(added = 0, alreadySubscribed = 0)
        open(MIRA)
        click("Import subscriptions from YouTube")
        picked(PickRequest(PickKind.Import, MIRA.id), PICKED, "notes.csv")

        compose.onNodeWithText("No YouTube channels in this file").assertExists()
        click("Choose another file")
        assertEquals(2, stores.picked.size)
    }

    @Test
    fun aCardCountsEverythingItCarried() {
        val kid = ResultRow("Zainab", ProfileTint.Rose, owner = false, kids = true, how = "Added", ProfileCounts(1, 3, 0, 0, 2, 1), "23 of 24", settingsKept = 1)
        val adult = ResultRow("Arshad", ProfileTint.Amber, owner = true, kids = false, how = null, ProfileCounts(12, 214, 3, 26, 4, 0), "24")

        assertEquals(
            listOf("1 approved channel", "3 in history", "0 playlists", "2 hidden", "1 approved video", "23 of 24 settings"),
            resultCounts(kid).map { "${it.value} ${it.word}" },
        )
        assertEquals(listOf(false, false, false, false, false, true), resultCounts(kid).map { it.warn })
        assertEquals("3 playlists · 26 videos", resultCounts(adult)[2].let { "${it.value} ${it.word}" })
        assertEquals(5, resultCounts(adult).size)
    }

    @Test
    fun thePhoneLinesSayWhatHappenedToThisPhonesSettings() {
        val kept = RestoreResult.Restored(ownerId = 1)
        assertEquals("stayed as they are: a backup’s are restored only on a phone with no profiles yet.", restoredPhoneLine(kept, firstLaunch = false))
        assertEquals("start at their defaults: a backup’s come back only with every profile.", restoredPhoneLine(kept, firstLaunch = true))
        assertEquals("restored: $PHONE_SETTINGS.", restoredPhoneLine(kept.copy(deviceSettings = true), firstLaunch = true))
        assertEquals("come back too: $PHONE_SETTINGS.", firstPickPhoneLine(emptyList()))
        assertEquals("come back only with every profile. With Yuri left out, they start at their defaults.", firstPickPhoneLine(listOf("Yuri")))
    }

    private fun summary(name: String, owner: Boolean = false) =
        BackupProfileSummary(name, ProfileTint.Amber, owner, kids = false, ProfileCounts(12, 214, 3, 26, 4, 0))

    private companion object {
        val ARSHAD = Profile(1, "Arshad", ProfileTint.Amber, isOwner = true, lock = LockKind.Pin)
        val MIRA = Profile(2, "Mira", ProfileTint.Rose, isOwner = false, lock = null)
        val YURI = Profile(3, "Yuri", ProfileTint.Teal, isOwner = false, lock = null)
        val SAVED: Uri = Uri.parse("content://downloads/saved")
        const val SAVED_NAME = "Ayn-backup-2026-10-14.json"
        val PICKED: Uri = Uri.parse("content://downloads/picked")
    }
}
