package com.arshad.ayn.ui.tv.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import com.arshad.ayn.core.lock.LockKind
import com.arshad.ayn.core.lock.LockSecret
import com.arshad.ayn.ui.tv.TEST_SEQUENCE
import com.arshad.ayn.ui.tv.enterSequence
import com.arshad.ayn.ui.tv.pressBack
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import com.arshad.ayn.core.backup.FakeBackupStore
import com.arshad.ayn.core.lock.FakeProfileLocks
import com.arshad.ayn.core.store.FakeDeviceSettingsStore
import com.arshad.ayn.core.store.FakeFilterStore
import com.arshad.ayn.core.store.FakeProfileSettingsStore
import com.arshad.ayn.core.store.FakeProfileStore
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.ProfileSession
import com.arshad.ayn.core.store.ProfileTint
import com.arshad.ayn.core.sync.DriveSignInStart
import com.arshad.ayn.core.sync.FakeDriveSignIn
import com.arshad.ayn.core.sync.FakeSyncControl
import com.arshad.ayn.core.sync.FakeSyncSignIn
import com.arshad.ayn.core.sync.FolderLook
import com.arshad.ayn.core.sync.LinkResult
import com.arshad.ayn.core.sync.MatchLook
import com.arshad.ayn.core.sync.MatchPreview
import com.arshad.ayn.core.sync.MatchProfile
import com.arshad.ayn.core.sync.SetupFailure
import com.arshad.ayn.core.sync.SignInEnd
import com.arshad.ayn.core.sync.SyncAccount
import com.arshad.ayn.core.sync.SyncCredential
import com.arshad.ayn.core.sync.SyncDevice
import com.arshad.ayn.core.sync.SyncFailure
import com.arshad.ayn.core.sync.SyncService
import com.arshad.ayn.core.sync.SyncStatus
import com.arshad.ayn.core.sync.account
import com.arshad.ayn.core.sync.driveCredential
import com.arshad.ayn.core.sync.nextcloudCredential
import com.arshad.ayn.feature.settings.BackupViewModel
import com.arshad.ayn.feature.settings.FiltersViewModel
import com.arshad.ayn.feature.settings.PreferencesViewModel
import com.arshad.ayn.feature.settings.ProfilesViewModel
import com.arshad.ayn.feature.settings.RestrictionsViewModel
import com.arshad.ayn.feature.settings.SyncScreen
import com.arshad.ayn.feature.settings.SyncViewModel
import com.arshad.ayn.ui.tv.TestPicks
import com.arshad.ayn.ui.tv.dpadSelect
import com.arshad.ayn.ui.tv.testKidsAdd
import com.arshad.ayn.ui.tv.testSync
import com.arshad.ayn.ui.tv.theme.KeTheme
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings › Sync on the TV (`m8-sync-tv`) on the real SyncViewModel over the `:core` sync fakes:
 * the owner's rows and anyone else's read-only page, the owner's lock first, Turn on through
 * Google's sign-in (its code and QR, a refusal's Try again, Sign in again on a Drive link) or
 * Nextcloud's address, its QR sign-in (the login address's token kept out of semantics) and a new
 * passphrase (masked; a short one doesn't link), a wrong passphrase, another WebDAV server's fields
 * edited in place (BACK ends an edit), the page while on (Sync now, Turn off's sheet and where
 * focus returns), a removed sign-in's notice and Sign in again, and Match your profiles' row
 * choices and a read that failed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp")
class SyncSettingsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val locks = FakeProfileLocks().apply { ownerId = ARSHAD.id }
    private val control = FakeSyncControl()
    private val signIn = FakeSyncSignIn()

    /** Google's sign-in; `null`, as most tests have it, hides Drive. */
    private var google: FakeDriveSignIn? = null
    private var syncRequests = 0
    private lateinit var sync: SyncViewModel

    private fun open(me: Profile = ARSHAD) {
        val store = FakeProfileStore(listOf(me.takeIf { it.isOwner } ?: ARSHAD, MIRA))
        val session = ProfileSession().apply { select(me.id) }
        val profiles = ProfilesViewModel(store, locks, FakeFilterStore(), session, TimeSource.Monotonic)
        val backup = BackupViewModel(FakeBackupStore(), store, locks, playback = {}, handover = { _, _ -> }, TestPicks(), me.id, TimeSource.Monotonic)
        val preferences = PreferencesViewModel(FakeProfileSettingsStore(me.id), FakeDeviceSettingsStore(), store)
        val filters = FiltersViewModel(FakeProfileSettingsStore(me.id), FakeFilterStore(), store, locks)
        val restrictions = RestrictionsViewModel(FakeFilterStore(), store, locks, TimeSource.Monotonic)
        sync = testSync(store, locks, me.id, control, signIn, google) { syncRequests++ }
        compose.setContent {
            KeTheme {
                SettingsScreen(
                    profiles = profiles,
                    backup = backup,
                    preferences = preferences,
                    filters = filters,
                    restrictions = restrictions,
                    kidsAdd = testKidsAdd(store, locks),
                    sync = sync,
                    restored = null,
                    pickWaiting = false,
                    chooseFile = {},
                    onRestoredShown = {},
                    onNothingToFocus = {},
                    focusHeldElsewhere = { false },
                    onRailShade = {},
                )
            }
        }
        compose.waitForIdle()
        clickable("Sync").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
    }

    /** The topmost row or button [label] names: the page stays composed under a cover, which comes after it. */
    private fun clickable(label: String): SemanticsNodeInteraction = compose.onAllNodes(hasText(label) and hasClickAction()).onLast()

    private fun select(label: String) {
        clickable(label).dpadSelect()
        compose.waitForIdle()
    }

    private fun shows(text: String) {
        assertTrue(text, compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty())
    }

    private fun type(text: String) {
        compose.onNode(hasSetTextAction()).performTextReplacement(text)
        compose.waitForIdle()
    }

    private fun onUi(action: () -> Unit) {
        compose.runOnUiThread(action)
        compose.waitForIdle()
    }

    @Test
    fun theOwnerTurnsOnSyncThroughNextcloudsQrSignInAndANewPassphrase() {
        open()
        shows("Ayn keeps its sync files on your own Nextcloud or another WebDAV server")

        select("Turn on sync")
        shows("Where should Ayn keep your sync files?")
        clickable("Nextcloud").assertIsFocused()
        select("Nextcloud")
        shows("Your Nextcloud’s address")
        type("cloud.example.org")
        select("Continue")

        shows("Sign in to cloud.example.org as you always do")
        compose.onNode(hasContentDescription("QR code for cloud.example.org")).assertExists()
        val token = hasText("FLOW-TOKEN", substring = true) or hasContentDescription("FLOW-TOKEN", substring = true)
        compose.onAllNodes(token, useUnmergedTree = true).assertCountEquals(0)
        clickable("Change address").assertIsFocused()
        assertEquals(listOf("cloud.example.org"), signIn.addresses)
        onUi { signIn.grant.complete(SignInEnd.Granted(nextcloudCredential())) }

        shows("Choose a sync passphrase")
        type("two words here")
        compose.onNode(hasSetTextAction()).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        select("Turn on sync")

        assertEquals("two words here", control.links.single().second)
        shows("Sync is on · turn it on on your phone too")
        clickable("Sync now").assertExists()
    }

    @Test
    fun withAGoogleSignInDriveComesFirstAndSignsInOnThePhoneThroughAQrCode() {
        val drive = FakeDriveSignIn().also { google = it }
        open()
        shows("Ayn keeps its sync files in your Google Drive or on your own Nextcloud")

        select("Turn on sync")
        clickable("Google Drive").assertIsFocused()
        shows("Sign in on your phone. Ayn uses an Ayn sync folder in your Drive; nothing else there is touched")
        select("Google Drive")

        shows("Scan the code, or open google.com/device")
        shows("GQVQ-JKEC")
        shows("Waiting for you to allow Ayn · the code works for 30 minutes")
        compose.onNode(hasContentDescription("QR code for google.com/device")).assertExists()
        clickable("Cancel").assertIsFocused()
        onUi { drive.grant.complete(SignInEnd.Granted(driveCredential())) }

        shows("Choose a sync passphrase")
        shows("Google Drive · owner@example.org")
    }

    @Test
    fun aRefusalOnGooglesPageSaysSoWithTryAgainFocused() {
        val drive = FakeDriveSignIn().also { google = it }
        open()
        select("Turn on sync")
        select("Google Drive")

        onUi { drive.grant.complete(SignInEnd.Refused(SetupFailure.DENIED)) }

        shows("Ayn wasn’t allowed on Google’s page. Try again to start over")
        clickable("Try again").assertIsFocused()
        drive.startResult = DriveSignInStart.Refused(SetupFailure.NO_CONNECTION)
        select("Try again")
        shows("Ayn couldn’t reach Google. Check your connection and try again")
        clickable("Try again").assertIsFocused()
        assertEquals(2, drive.starts)
    }

    @Test
    fun aDriveLinkGoogleRefusedSignsInAgainOnThePhone() {
        google = FakeDriveSignIn()
        control.status.value = SyncStatus(linked = true, account = driveCredential().account(), failure = SyncFailure.SIGN_IN_REJECTED)
        open()
        shows("Google says Ayn’s access was removed. Sign in again to keep syncing. Nothing on this Shield has changed.")
        shows("On your phone, as owner@example.org")

        select("Sign in again")

        shows("Sign in again · Google Drive")
        shows("GQVQ-JKEC")
    }

    @Test
    fun aNewPassphraseShorterThanEightCharactersDoesNotLink() {
        open()
        reachPassphrase()

        type("short")
        select("Turn on sync")

        assertTrue(control.links.isEmpty())
        shows("At least 8 characters. A few words you’ll remember work well.")
    }

    @Test
    fun aWrongPassphraseSaysWhoseFilesItDoesNotOpen() {
        control.lookResult = FolderLook.Ready(listOf(PIXEL))
        control.linkResult = LinkResult.WrongPassphrase
        open()
        reachPassphrase()
        shows("Enter your sync passphrase")

        type("not the one")
        select("Continue")

        shows("That passphrase doesn’t open Pixel 7 Pro’s sync files. Check for a typo and try again.")
    }

    @Test
    fun anotherWebDavServersFieldsAreEditedInPlaceThenConnect() {
        open()
        select("Turn on sync")
        select("Another WebDAV server")
        clickable("Server address").assertIsFocused()

        editField("Server address", "https://dav.example.com/remote.php/dav/files/arshad/")
        editField("User name", "arshad")
        editField("App password", "secret-app-password")
        compose.onNode(hasText("•".repeat("secret-app-password".length))).assertExists()
        select("Connect")

        val credential = control.looked.single() as SyncCredential.WebDav
        assertEquals("arshad", credential.user)
        assertEquals("secret-app-password", credential.password)
        assertTrue(credential.folder, credential.folder.startsWith("https://dav.example.com/remote.php/dav/files/arshad/"))
        shows("Choose a sync passphrase")
    }

    @Test
    fun backEndsAnInPlaceEditKeepingTheFieldThenLeavesTheStep() {
        open()
        select("Turn on sync")
        select("Another WebDAV server")
        select("App password")
        compose.onNode(hasSetTextAction()).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        compose.onNode(hasSetTextAction()).performTextReplacement("secret")

        compose.pressBack()
        assertTrue(sync.uiState.value.screen is SyncScreen.WebDav)
        clickable("App password").assertIsFocused()
        shows("••••••")
        compose.pressBack()

        assertEquals(SyncScreen.Services, sync.uiState.value.screen)
    }

    @Test
    fun aLockedOwnerUnlocksBeforeTheServices() {
        locks.locks[ARSHAD.id] = LockSecret.Sequence(TEST_SEQUENCE)
        open(ARSHAD_LOCKED)

        select("Turn on sync")
        shows("Turning on sync")
        compose.onNode(hasContentDescription("D-pad sequence")).enterSequence(TEST_SEQUENCE)
        compose.waitForIdle()

        shows("Where should Ayn keep your sync files?")
    }

    private fun editField(label: String, value: String) {
        select(label)
        compose.onNode(hasSetTextAction()).performTextReplacement(value)
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.waitForIdle()
        clickable(label).assertIsFocused()
    }

    @Test
    fun thePageWhileOnSyncsNowAndTurnsOffFromItsSheet() {
        control.status.value = onStatus()
        open()
        shows("Synced 2 minutes ago")
        shows("Pixel 7 Pro")

        select("Sync now")
        assertEquals(1, syncRequests)
        select("Turn off sync on this Shield")
        shows("Turn off sync on this Shield?")
        clickable("Keep syncing").assertIsFocused()
        compose.pressBack()
        assertEquals(0, control.unlinks)
        clickable("Turn off sync on this Shield").assertIsFocused()
        select("Turn off sync on this Shield")
        select("Turn off sync")

        assertEquals(1, control.unlinks)
        shows("Sync is off")
    }

    @Test
    fun aRemovedSignInShowsItsNoticeAndSignInAgainFirst() {
        control.status.value = onStatus().copy(failure = SyncFailure.SIGN_IN_REJECTED)
        open()

        shows("Nextcloud says Ayn’s access was removed. Sign in again to keep syncing. Nothing on this Shield has changed.")
        select("Sign in again")

        shows("Sign in again · Nextcloud")
        clickable("Continue").assertIsFocused()
    }

    @Test
    fun anyoneButTheOwnerReadsSyncWithoutItsRows() {
        control.status.value = onStatus()
        open(MIRA)

        shows("Synced 2 minutes ago")
        shows("Only the owner sets up sync")
        compose.onNode(hasText("Sync now") and hasClickAction()).assertDoesNotExist()
        compose.onNode(hasText("Turn off sync on this Shield") and hasClickAction()).assertDoesNotExist()
    }

    @Test
    fun matchsRowOpensItsChoicesAndStartSyncingKeepsThem() {
        control.lookResult = FolderLook.Ready(listOf(PIXEL))
        control.linkResult = LinkResult.Linked(joined = true)
        control.previewResult = MatchLook.Ready(PREVIEW)
        open()
        reachPassphrase()
        type("the folder's one")
        select("Continue")

        shows("Match your profiles")
        clickable("Mira").assertIsFocused()
        select("Mira")
        clickable("With Mira on Pixel 7 Pro").assertIsFocused()
        select("Keep apart")
        // Mira here, now kept apart, beside Mira on the Pixel, now only there.
        compose.onNode(hasText("Mira") and hasText("Syncs as a new profile") and hasClickAction()).assertIsFocused()
        select("Start syncing")

        assertEquals(setOf("mira-here"), control.started.single().apart)
        assertEquals(SyncScreen.Page, sync.uiState.value.screen)
    }

    @Test
    fun aMatchReadThatFailedOffersTryAgain() {
        control.lookResult = FolderLook.Ready(listOf(PIXEL))
        control.linkResult = LinkResult.Linked(joined = true)
        control.previewResult = MatchLook.Failed(SyncFailure.NO_CONNECTION)
        open()
        reachPassphrase()
        type("the folder's one")
        select("Continue")

        shows("Ayn couldn’t read the profiles to match")
        clickable("Try again").assertIsFocused()
        control.previewResult = MatchLook.Ready(PREVIEW)
        select("Try again")

        assertEquals(2, control.previews)
        assertTrue(sync.uiState.value.screen is SyncScreen.Match)
    }

    /** Turn on → Nextcloud → its address → granted: the passphrase. */
    private fun reachPassphrase() {
        select("Turn on sync")
        select("Nextcloud")
        type("cloud.example.org")
        select("Continue")
        onUi { signIn.grant.complete(SignInEnd.Granted(nextcloudCredential())) }
    }

    private fun onStatus(): SyncStatus = SyncStatus(
        linked = true,
        lastSyncedMillis = (Clock.System.now() - 2.minutes).toEpochMilliseconds(),
        account = SyncAccount(SyncService.NEXTCLOUD, "cloud.example.org", "arshad"),
        deviceId = FakeSyncControl.THIS_DEVICE,
        devices = listOf(PIXEL),
    )

    private companion object {
        val ARSHAD = Profile(1, "Arshad", ProfileTint.Amber, isOwner = true, lock = null)
        val ARSHAD_LOCKED = ARSHAD.copy(lock = LockKind.Sequence)
        val MIRA = Profile(2, "Mira", ProfileTint.Rose, isOwner = false, lock = null)
        val PIXEL = SyncDevice("pixel-7-pro-1a2b3c4d", syncedAtMillis = null)
        val PREVIEW = MatchPreview(
            local = listOf(
                MatchProfile("arshad-here", "Arshad", "amber", owner = true, kids = false, historyCount = 10),
                MatchProfile("mira-here", "Mira", "rose", owner = false, kids = false, historyCount = 5),
            ),
            remote = listOf(
                MatchProfile("arshad-there", "Arshad", "amber", owner = true, kids = false, historyCount = 12, devices = listOf(PIXEL)),
                MatchProfile("mira-there", "Mira", "rose", owner = false, kids = false, historyCount = 7, devices = listOf(PIXEL)),
            ),
            pairs = mapOf("arshad-here" to "arshad-there", "mira-here" to "mira-there"),
            fixed = mapOf("arshad-here" to "arshad-there"),
        )
    }
}
