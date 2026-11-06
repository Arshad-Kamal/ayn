package com.arshad.ayn.ui.mobile.settings

import android.content.ClipboardManager
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performImeAction
import kotlinx.coroutines.CompletableDeferred
import org.robolectric.shadows.ShadowDialog
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.arshad.ayn.core.lock.LockKind
import com.arshad.ayn.core.lock.LockSecret
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.ProfileTint
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
import com.arshad.ayn.ui.mobile.installOfflineImageLoader
import com.arshad.ayn.ui.mobile.library.TestStores
import com.arshad.ayn.ui.mobile.library.TestTime
import com.arshad.ayn.ui.mobile.shell.MobileShell
import com.arshad.ayn.ui.mobile.shell.SettingsKey
import com.arshad.ayn.ui.mobile.shell.SettingsPage
import com.arshad.ayn.ui.mobile.shell.ShellNav
import com.arshad.ayn.ui.mobile.shell.ShellOpening
import com.arshad.ayn.ui.mobile.shellPages
import com.arshad.ayn.ui.mobile.theme.AynTheme
import kotlin.time.Duration.Companion.minutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings › Sync on the phone (`m8-sync-phone`) through the shell's real page wiring on the real
 * SyncViewModel over the `:core` sync fakes: the index row (dimmed for anyone but the owner), Turn
 * on through Google's sign-in (its code copied as Google's page opens; Sign in again on a Drive
 * link) or Nextcloud's address, its sign-in opened in the browser (the login address's token kept
 * out of semantics) and a new passphrase (masked; a short one doesn't link), a wrong passphrase,
 * another WebDAV server's fields, the owner's lock first, the page while on (Sync now, Turn off's
 * sheet), a removed sign-in's notice and Sign in again, the read-only page, and Match your profiles'
 * row choices and a read that failed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class SyncPagesTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val stores = TestStores()
    private val browser = TestBrowser()
    private lateinit var nav: ShellNav

    @Before
    fun setUp() {
        installOfflineImageLoader(compose.activity)
        stores.people.rows.value = listOf(ARSHAD, MIRA)
        stores.locks.ownerId = ARSHAD.id
        stores.locks.known += listOf(ARSHAD.id, MIRA.id)
    }

    private fun open(me: Profile = ARSHAD, page: SettingsPage? = SettingsPage.Sync) {
        stores.settings.selected.value = me.id
        stores.profileSession.select(me.id)
        val models = stores.models()
        compose.setContent {
            AynTheme {
                CompositionLocalProvider(LocalUriHandler provides browser) {
                    MobileShell(ShellOpening(me, onSwitchProfile = {})) { chrome ->
                        nav = chrome.nav
                        shellPages(chrome, models)
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnUiThread { if (page != null) nav.openSettings(page) else nav.open(SettingsKey) }
        compose.waitForIdle()
    }

    /** The topmost button [label] names: the page stays composed under a sheet or a page over it. */
    private fun click(label: String) {
        compose.onAllNodes(hasText(label) and hasClickAction()).onLast().performClick()
        compose.waitForIdle()
    }

    private fun shows(text: String) {
        assertTrue(text, compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty())
    }

    private fun field(label: String) = compose.onNode(hasSetTextAction() and hasText(label))

    /** The topmost button [label] names. */
    private fun button(label: String) = compose.onAllNodes(hasText(label) and hasClickAction()).onLast()

    /** System Back on the cover's own window. */
    private fun dialogBack() {
        compose.runOnUiThread { (ShadowDialog.getLatestDialog() as ComponentDialog).onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun type(label: String, text: String) {
        field(label).performTextReplacement(text)
        compose.waitForIdle()
    }

    /** [label]'s field is a password field reading out only dots for [typed] (CMP-SEC-01); the raw
     *  text stays in the test-only InputText. */
    private fun assertMasked(label: String, typed: String) {
        field(label)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("•".repeat(typed.length))))
    }

    private fun onUi(action: () -> Unit) {
        compose.runOnUiThread(action)
        compose.waitForIdle()
    }

    @Test
    fun theOwnerTurnsOnSyncThroughNextcloudInTheBrowserAndANewPassphrase() {
        open()
        shows("Keep every profile the same on your phone and TV")

        click("Turn on sync")
        shows("Where should Ayn keep your sync files?")
        click("Nextcloud")
        shows("Your Nextcloud’s address")
        type("Address", "cloud.example.org")
        click("Continue")

        shows("Sign in to cloud.example.org as you always do")
        assertEquals(listOf("https://cloud.example.org/login/v2/flow/FLOW-TOKEN"), browser.opened)
        val token = hasText("FLOW-TOKEN", substring = true) or hasContentDescription("FLOW-TOKEN", substring = true)
        compose.onAllNodes(token, useUnmergedTree = true).assertCountEquals(0)
        click("Open the sign-in page")
        assertEquals(2, browser.opened.size)
        onUi { stores.signIn.grant.complete(SignInEnd.Granted(nextcloudCredential())) }

        shows("Choose a sync passphrase")
        type("Sync passphrase", "two words here")
        assertMasked("Sync passphrase", "two words here")
        click("Turn on sync")

        assertEquals("two words here", stores.sync.links.single().second)
        shows("turn it on on your TV too")
        compose.onNode(hasText("Sync now") and hasClickAction()).assertExists()
    }

    @Test
    fun theOwnerTurnsOnSyncThroughGoogleCopyingTheCodeAsGooglesPageOpens() {
        open()
        shows("Ayn keeps its sync files in your Google Drive or on your own Nextcloud")

        click("Turn on sync")
        shows("Sign in with Google. Ayn uses an Ayn sync folder in your Drive; nothing else there is touched")
        click("Google Drive")

        shows("Sign in with Google")
        shows("GQVQ-JKEC")
        shows("Waiting for you to allow Ayn · the code works for 30 minutes")
        assertTrue(browser.opened.isEmpty())
        click("Copy code and open google.com/device")
        assertEquals(listOf("https://www.google.com/device"), browser.opened)
        val clip = compose.activity.getSystemService(ClipboardManager::class.java).primaryClip
        assertEquals("GQVQ-JKEC", clip?.getItemAt(0)?.text?.toString())
        onUi { stores.drive.grant.complete(SignInEnd.Granted(driveCredential())) }

        shows("Choose a sync passphrase")
        shows("so Google can’t read them")
    }

    @Test
    fun aPhoneWithNoBrowserStillCopiesGooglesCodeAndSaysThePageCantOpen() {
        browser.works = false
        open()
        click("Turn on sync")
        click("Google Drive")

        click("Copy code and open google.com/device")

        shows("No app on this phone opens web pages")
        val clip = compose.activity.getSystemService(ClipboardManager::class.java).primaryClip
        assertEquals("GQVQ-JKEC", clip?.getItemAt(0)?.text?.toString())
    }

    @Test
    fun theCopyButtonBesideGooglesCodeCopiesIt() {
        open()
        click("Turn on sync")
        click("Google Drive")

        compose.onNode(hasContentDescription("Copy code")).performClick()
        compose.waitForIdle()

        val clip = compose.activity.getSystemService(ClipboardManager::class.java).primaryClip
        assertEquals("GQVQ-JKEC", clip?.getItemAt(0)?.text?.toString())
    }

    @Test
    fun aRefusalOnGooglesPageSaysSoAndTryAgainShowsANewCode() {
        open()
        click("Turn on sync")
        click("Google Drive")
        onUi { stores.drive.grant.complete(SignInEnd.Refused(SetupFailure.DENIED)) }

        shows("Ayn wasn’t allowed on Google’s page. Try again to start over")
        compose.onAllNodes(hasText("GQVQ-JKEC")).assertCountEquals(0)
        stores.drive.grant = CompletableDeferred()
        click("Try again")

        shows("GQVQ-JKEC")
        assertEquals(2, stores.drive.starts)
    }

    @Test
    fun aDriveLinkGoogleRefusedSignsInAgainAsTheSameAccount() {
        stores.sync.status.value = SyncStatus(linked = true, account = driveCredential().account(), failure = SyncFailure.SIGN_IN_REJECTED)
        open()
        shows("Google says Ayn’s access was removed.")
        shows("As owner@example.org")

        click("Sign in again")
        shows("Sign in again · Google Drive")
        onUi { stores.drive.grant.complete(SignInEnd.Granted(driveCredential())) }

        assertEquals(listOf(driveCredential().account()), stores.sync.signIns.map { it.account() })
        compose.onNode(hasText("Sync now") and hasClickAction()).assertExists()
    }

    @Test
    fun aPhoneWithNoBrowserSaysTheSignInPageCantOpen() {
        browser.works = false
        open()
        click("Turn on sync")
        click("Nextcloud")
        type("Address", "cloud.example.org")
        click("Continue")

        shows("No app on this phone opens web pages")
    }

    @Test
    fun aNewPassphraseShorterThanEightCharactersDoesNotLink() {
        open()
        reachPassphrase()

        type("Sync passphrase", "short")
        button("Turn on sync").assertIsNotEnabled()
        field("Sync passphrase").performImeAction()
        compose.waitForIdle()

        assertTrue(stores.sync.links.isEmpty())
    }

    @Test
    fun backOnThePassphraseLeavesWithoutLinking() {
        open()
        reachPassphrase()
        type("Sync passphrase", "two words here")

        dialogBack()

        assertTrue(stores.sync.links.isEmpty())
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        button("Turn on sync").assertExists()
    }

    @Test
    fun theSignInPageOpensOnceAndItsButtonsWaitWhileTheFolderIsChecked() {
        stores.sync.lookGate = CompletableDeferred()
        open()
        click("Turn on sync")
        click("Nextcloud")
        type("Address", "cloud.example.org")
        click("Continue")
        onUi { stores.signIn.grant.complete(SignInEnd.Granted(nextcloudCredential())) }

        shows("Access granted. Checking the Ayn folder…")
        button("Open the sign-in page").assertIsNotEnabled()
        button("Change address").assertIsNotEnabled()
        assertEquals(1, browser.opened.size)
        onUi { stores.sync.lookGate?.complete(Unit) }

        shows("Choose a sync passphrase")
    }

    @Test
    fun aWrongPassphraseSaysWhoseFilesItDoesNotOpen() {
        stores.sync.lookResult = FolderLook.Ready(listOf(SHIELD))
        stores.sync.linkResult = LinkResult.WrongPassphrase
        open()
        reachPassphrase()
        shows("Enter your sync passphrase")

        type("Sync passphrase", "not the one")
        click("Continue")

        shows("That passphrase doesn’t open Shield Android TV’s sync files. Check for a typo and try again.")
    }

    @Test
    fun anotherWebDavServersFieldsConnect() {
        open()
        click("Turn on sync")
        click("Another WebDAV server")
        shows("Ayn makes an Ayn folder there and keeps one file per device in it.")

        type("Server address", "https://dav.example.com/remote.php/dav/files/arshad/")
        type("User name", "arshad")
        type("App password", "secret-app-password")
        assertMasked("App password", "secret-app-password")
        click("Connect")

        val credential = stores.sync.looked.single() as SyncCredential.WebDav
        assertEquals("arshad", credential.user)
        assertEquals("secret-app-password", credential.password)
        assertTrue(credential.folder, credential.folder.startsWith("https://dav.example.com/remote.php/dav/files/arshad/"))
        shows("Choose a sync passphrase")
    }

    @Test
    fun aRefusedWebDavSignInMarksTheUserAndPasswordAndKeepsThem() {
        stores.sync.lookResult = FolderLook.Failed(SyncFailure.SIGN_IN_REJECTED)
        open()
        click("Turn on sync")
        click("Another WebDAV server")
        type("Server address", "https://dav.example.com/remote.php/dav/files/arshad/")
        type("User name", "arshad")
        type("App password", "wrong-password")
        click("Connect")

        shows("Wrong user name or password")
        field("User name").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        field("App password").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        field("Server address").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Error))
        assertMasked("App password", "wrong-password")
        field("User name").assert(hasText("arshad"))
    }

    @Test
    fun aLockedOwnerUnlocksBeforeTheServices() {
        stores.people.rows.value = listOf(ARSHAD_LOCKED, MIRA)
        stores.locks.locks[ARSHAD.id] = LockSecret.Pin("1234")
        open(ARSHAD_LOCKED)

        click("Turn on sync")
        shows("Turning on sync")
        "1234".forEach { compose.onNode(hasText(it.toString()) and hasClickAction()).performClick() }
        compose.waitForIdle()

        shows("Where should Ayn keep your sync files?")
    }

    @Test
    fun thePageWhileOnSyncsNowAndTurnsOffFromItsSheet() {
        stores.sync.status.value = onStatus()
        open()
        shows("Synced 2 minutes ago")
        shows("Pixel 7 Pro · synced 2 minutes ago")
        shows("Shield Android TV")

        click("Sync now")
        assertEquals(1, stores.syncRequests)
        click("Turn off sync on this phone")
        shows("Turn off sync on this phone?")
        click("Keep syncing")
        assertEquals(0, stores.sync.unlinks)
        click("Turn off sync on this phone")
        click("Turn off")

        assertEquals(1, stores.sync.unlinks)
        shows("Sync is off")
    }

    @Test
    fun turningOffSaysWhenThisPhonesFileStayedInTheFolder() {
        stores.sync.status.value = onStatus()
        stores.sync.unlinkRemoves = false
        open()

        click("Turn off sync on this phone")
        click("Turn off")

        shows("This phone’s file couldn’t be removed from the Ayn folder")
    }

    @Test
    fun aRemovedSignInShowsItsNoticeAndSignInAgainFirst() {
        stores.sync.status.value = onStatus().copy(failure = SyncFailure.SIGN_IN_REJECTED)
        open()

        shows("Nextcloud says Ayn’s access was removed. Sign in again to keep syncing. Nothing on this phone has changed.")
        click("Sign in again")

        shows("Sign in again · Nextcloud")
        field("Address").assert(hasText("cloud.example.org"))
    }

    @Test
    fun theIndexRowSaysSyncsStateAndAnyoneButTheOwnerReadsThePageWithoutItsRows() {
        stores.sync.status.value = onStatus()
        open(MIRA, page = null)
        shows("Only the owner can set up sync")

        click("Sync")
        shows("Synced 2 minutes ago")
        shows("Only the owner sets up sync")
        compose.onNode(hasText("Sync now") and hasClickAction()).assertDoesNotExist()
        compose.onNode(hasText("Turn off sync on this phone") and hasClickAction()).assertDoesNotExist()
    }

    @Test
    fun theOwnersIndexRowSaysSyncsState() {
        open(page = null)
        shows("Off · keep your phone and TV the same")

        onUi { stores.sync.status.value = onStatus() }

        shows("On · synced 2 minutes ago")
    }

    @Test
    fun matchsRowOpensItsChoicesAndStartSyncingKeepsThem() {
        stores.sync.lookResult = FolderLook.Ready(listOf(SHIELD))
        stores.sync.linkResult = LinkResult.Linked(joined = true)
        stores.sync.previewResult = MatchLook.Ready(PREVIEW)
        open()
        reachPassphrase()
        type("Sync passphrase", "the folder's one")
        click("Continue")

        shows("Match your profiles")
        shows("The owner always matches the owner")
        click("Mira")
        shows("With Mira on Shield Android TV")
        click("Keep apart")
        // Mira here, now kept apart, beside Mira on the Shield, now only there.
        compose.onNode(hasText("Mira") and hasText("Syncs as a new profile") and hasClickAction()).assertExists()
        click("Start syncing")

        assertEquals(setOf("mira-here"), stores.sync.started.single().apart)
        shows("Sync is on")
    }

    @Test
    fun backOnMatchLeavesItAndUnlinks() {
        stores.sync.lookResult = FolderLook.Ready(listOf(SHIELD))
        stores.sync.linkResult = LinkResult.Linked(joined = true)
        stores.sync.previewResult = MatchLook.Ready(PREVIEW)
        open()
        reachPassphrase()
        type("Sync passphrase", "the folder's one")
        click("Continue")
        shows("Match your profiles")

        dialogBack()

        assertEquals(1, stores.sync.unlinks)
        compose.onAllNodes(hasText("Match your profiles")).assertCountEquals(0)
    }

    @Test
    fun aMatchReadThatFailedOffersTryAgain() {
        stores.sync.lookResult = FolderLook.Ready(listOf(SHIELD))
        stores.sync.linkResult = LinkResult.Linked(joined = true)
        stores.sync.previewResult = MatchLook.Failed(SyncFailure.NO_CONNECTION)
        open()
        reachPassphrase()
        type("Sync passphrase", "the folder's one")
        click("Continue")

        shows("Ayn couldn’t read the profiles to match")
        stores.sync.previewResult = MatchLook.Ready(PREVIEW)
        click("Try again")

        assertEquals(2, stores.sync.previews)
        shows("Match your profiles")
    }

    /** Turn on → Nextcloud → its address → granted: the passphrase. */
    private fun reachPassphrase() {
        click("Turn on sync")
        click("Nextcloud")
        type("Address", "cloud.example.org")
        click("Continue")
        onUi { stores.signIn.grant.complete(SignInEnd.Granted(nextcloudCredential())) }
    }

    private fun onStatus(): SyncStatus = SyncStatus(
        linked = true,
        lastSyncedMillis = (TestTime.clock.now() - 2.minutes).toEpochMilliseconds(),
        account = SyncAccount(SyncService.NEXTCLOUD, "cloud.example.org", "arshad"),
        deviceId = "pixel-7-pro-0a1b2c3d",
        devices = listOf(SHIELD),
    )

    /** The browser: each page it was asked to open; not [works], no app takes one. */
    private class TestBrowser : UriHandler {
        var works = true
        val opened = mutableListOf<String>()

        override fun openUri(uri: String) {
            require(works) { "Can't open $uri." }
            opened += uri
        }
    }

    private companion object {
        val ARSHAD = Profile(1, "Arshad", ProfileTint.Amber, isOwner = true, lock = null)
        val ARSHAD_LOCKED = ARSHAD.copy(lock = LockKind.Pin)
        val MIRA = Profile(2, "Mira", ProfileTint.Rose, isOwner = false, lock = null)
        val SHIELD = SyncDevice("shield-android-tv-1a2b3c4d", syncedAtMillis = null)
        val PREVIEW = MatchPreview(
            local = listOf(
                MatchProfile("arshad-here", "Arshad", "amber", owner = true, kids = false, historyCount = 10),
                MatchProfile("mira-here", "Mira", "rose", owner = false, kids = false, historyCount = 5),
            ),
            remote = listOf(
                MatchProfile("arshad-there", "Arshad", "amber", owner = true, kids = false, historyCount = 12, devices = listOf(SHIELD)),
                MatchProfile("mira-there", "Mira", "rose", owner = false, kids = false, historyCount = 7, devices = listOf(SHIELD)),
            ),
            pairs = mapOf("arshad-here" to "arshad-there", "mira-here" to "mira-there"),
            fixed = mapOf("arshad-here" to "arshad-there"),
        )
    }
}
