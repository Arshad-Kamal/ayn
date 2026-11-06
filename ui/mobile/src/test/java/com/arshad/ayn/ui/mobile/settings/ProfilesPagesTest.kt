package com.arshad.ayn.ui.mobile.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.arshad.ayn.core.lock.LockKind
import com.arshad.ayn.core.lock.LockSecret
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.ProfileContents
import com.arshad.ayn.core.store.ProfileTint
import com.arshad.ayn.ui.mobile.installOfflineImageLoader
import com.arshad.ayn.ui.mobile.library.TestStores
import com.arshad.ayn.ui.mobile.shell.MobileShell
import com.arshad.ayn.ui.mobile.shell.SettingsKey
import com.arshad.ayn.ui.mobile.shell.SettingsPage
import com.arshad.ayn.ui.mobile.shell.SettingsPageKey
import com.arshad.ayn.ui.mobile.shell.ShellNav
import com.arshad.ayn.ui.mobile.shell.ShellOpening
import com.arshad.ayn.ui.mobile.shellPages
import com.arshad.ayn.ui.mobile.theme.AynTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings › Profiles on the phone (`m6-settings` 25–36) through the shell's real page wiring on
 * the stores' fakes: the owner's list and sheets, the owner's lock before a change, a new lock
 * entered twice, a new profile's name, and a non-owner's own rows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class ProfilesPagesTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val stores = TestStores()
    private lateinit var nav: ShellNav

    @Before
    fun setUp() {
        installOfflineImageLoader(compose.activity)
        stores.people.rows.value = listOf(ARSHAD, MIRA, YURI)
        stores.people.contentsById.value = mapOf(
            ARSHAD.id to ProfileContents(subscriptionCount = 12, playlistCount = 3, historyCount = 214),
            MIRA.id to ProfileContents(subscriptionCount = 8, playlistCount = 1, historyCount = 40),
        )
        stores.locks.ownerId = ARSHAD.id
        stores.locks.known += listOf(ARSHAD.id, MIRA.id, YURI.id)
        stores.locks.locks[ARSHAD.id] = LockSecret.Pin("1234")
        stores.locks.locks[MIRA.id] = LockSecret.Pin("2461")
    }

    private fun open(me: Profile = ARSHAD) {
        stores.settings.selected.value = me.id
        stores.profileSession.select(me.id)
        val models = stores.models()
        compose.setContent {
            AynTheme {
                MobileShell(ShellOpening(me, onSwitchProfile = {})) { chrome ->
                    nav = chrome.nav
                    shellPages(chrome, models)
                }
            }
        }
        compose.runOnUiThread {
            nav.open(SettingsKey)
            nav.open(SettingsPageKey(SettingsPage.Profiles))
        }
        compose.waitForIdle()
    }

    private fun click(label: String) {
        compose.onNode(hasText(label) and hasClickAction()).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun digits(pin: String) {
        pin.forEach { compose.onNode(hasText(it.toString()) and hasClickAction()).performClick() }
        compose.waitForIdle()
    }

    @Test
    fun theOwnersListShowsEachProfilesLockAndCounts() {
        open()

        compose.onNodeWithText("Locked with a PIN · 12 subscriptions · 3 playlists · 214 in history").assertExists()
        compose.onNodeWithText("Locked with a PIN · 8 subscriptions · 1 playlist · 40 in history").assertExists()
        compose.onNodeWithText("No lock · 0 in history").assertExists()
        compose.onNodeWithText("Owner").assertExists()
        compose.onNodeWithText("You").assertExists()
        compose.onNodeWithText("3 of 5").assertExists()
        compose.onNodeWithText("Your profile isn’t locked.", substring = true).assertDoesNotExist()
    }

    @Test
    fun removingMiraAsksTheOwnersLockAfterTheConfirm() {
        open()
        click("Mira")
        compose.onNodeWithText("Restrictions and resets ask for your PIN first.").assertExists()
        click("Remove profile")
        compose.onNodeWithText("This deletes Mira’s 8 subscriptions, 1 playlist, Watch later and 40 videos from history. It can’t be undone.")
            .assertExists()

        compose.onNode(hasText("Remove Mira") and hasClickAction()).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Removing Mira").assertExists()
        compose.onNodeWithText("Only the owner removes profiles · ⌫ erases a digit · Back cancels").assertExists()
        digits("1234")

        assertEquals(listOf(ARSHAD, YURI), stores.people.rows.value)
        compose.onNodeWithText("Removing Mira").assertDoesNotExist()
    }

    @Test
    fun backOnTheOwnersLockReturnsToTheConfirmWithADigitEntered() {
        open()
        click("Mira")
        click("Remove profile")
        compose.onNode(hasText("Remove Mira") and hasClickAction()).performClick()
        compose.waitForIdle()
        compose.onNode(hasText("1") and hasClickAction()).performClick()

        // The page's bar has its own Back arrow under the lock's page.
        compose.onAllNodes(hasContentDescription("Back")).onLast().performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Removing Mira").assertDoesNotExist()
        compose.onNodeWithText("Remove Mira?").assertExists()
        assertEquals(3, stores.people.rows.value!!.size)
    }

    @Test
    fun aNewProfileWithATakenNameSaysWhyThenAddsUnderAnother() {
        open()
        click("Add a profile")
        compose.onNodeWithText("What kind of profile?").assertExists()
        compose.onNode(hasText("Kids profile") and isSelectable()).assertIsEnabled()
        click("Standard profile")
        compose.onNodeWithText("Adding a profile").assertExists()
        digits("1234")
        compose.onNodeWithText("New profile · 4 of 5").assertExists()

        compose.onNode(hasSetTextAction()).performTextInput("mira")
        click("Add profile")
        compose.onNodeWithText("mira already has a profile here. Choose another name.").assertExists()
        compose.onNode(hasText("Add profile") and hasClickAction()).assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNode(hasSetTextAction()).performTextInput("Noor")
        compose.onNode(hasText("Add profile") and hasClickAction()).assertIsEnabled()
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.waitForIdle()

        assertEquals("Noor", stores.people.rows.value!!.last().name)
        compose.onNodeWithText("New profile · 4 of 5").assertDoesNotExist()
    }

    @Test
    fun theOwnerRenamesInTheirSheet() {
        open()
        click("Arshad")
        compose.onNodeWithText("Your profile · owner").assertExists()
        compose.onNodeWithText("The owner’s profile can’t be removed.", substring = true).assertExists()
        click("Name")

        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNode(hasSetTextAction()).performTextInput("Arshad K")
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.waitForIdle()

        assertEquals("Arshad K", stores.people.rows.value!!.first().name)
    }

    @Test
    fun aFirstLockIsEnteredTwiceAMismatchStartsOverThenItSaves() {
        open(YURI)
        compose.onNodeWithText("Arshad, the owner, adds and removes profiles.", substring = true).assertExists()
        click("Lock")
        compose.onNodeWithText("Choose a lock").assertExists()
        click("4-digit PIN")
        compose.onNodeWithText("New lock · step 1 of 2").assertExists()
        digits("1357")
        compose.onNodeWithText("New lock · step 2 of 2").assertExists()
        digits("9999")
        compose.onNodeWithText("The two didn’t match, so nothing was saved. Enter a new PIN.").assertExists()
        assertNull(stores.locks.locks[YURI.id])

        digits("1357")
        digits("1357")

        assertEquals(LockKind.Pin, stores.locks.locks[YURI.id]?.kind)
        compose.onNodeWithText("Lock saved", substring = true).assertExists()
    }

    @Test
    fun aColourPickedInItsSheetIsSaved() {
        open(YURI)
        click("Colour")
        compose.onNodeWithText("Your colour").assertExists()

        compose.onNode(hasText("Violet") and isSelectable()).performClick()
        compose.waitForIdle()

        assertEquals(ProfileTint.Violet, stores.people.rows.value!!.last().tint)
    }

    @Test
    fun theOwnersRemoveLockIsHeldWhileAKidsProfileExists() {
        stores.people.rows.value = listOf(ARSHAD, MIRA, ZAINAB)
        open()
        click("Arshad")
        click("Lock")

        compose.onNodeWithText("Needed while Zainab has a kids profile").assertExists()
        compose.onNodeWithText("Your lock leaves Zainab’s profile", substring = true).assertExists()
        compose.onNode(hasText("Remove lock") and hasClickAction()).assertDoesNotExist()
    }

    @Test
    fun restrictionsFromAProfilesSheetOpenItsPage() {
        open()
        click("Mira")

        click("Restrictions")

        compose.onNodeWithText("Mira’s restrictions").assertExists()
        compose.onNodeWithText("Enter Arshad’s PIN.").assertExists()
        compose.runOnUiThread { nav.back() }
        compose.waitForIdle()

        compose.onNodeWithText("Add a profile").assertExists()
        compose.onNodeWithText("Remove profile").assertDoesNotExist()
    }

    @Test
    fun theOwnerRemovesTheirLockAfterTheCurrentOneAndTheSheetSaysSoOnce() {
        open()
        click("Arshad")
        click("Lock")
        click("Remove lock")
        compose.onNodeWithText("Removing your lock").assertExists()
        compose.onNodeWithText("Enter your current PIN first.").assertExists()

        digits("1234")

        assertNull(stores.locks.locks[ARSHAD.id])
        compose.onNodeWithText("Your profile · owner").assertExists()
        compose.onAllNodesWithText("Lock removed", substring = true).assertCountEquals(1)
    }

    @Test
    fun theOwnerResetsMirasLockAfterTheirOwn() {
        open()
        click("Mira")
        click("Reset Mira’s lock")
        compose.onNodeWithText("Resetting Mira’s lock").assertExists()

        digits("1234")

        assertNull(stores.locks.locks[MIRA.id])
    }

    @Test
    fun anUnlockedOwnerIsWarnedAndLockYourProfileOpensTheirSheet() {
        stores.people.rows.value = listOf(ARSHAD.copy(lock = null), MIRA, YURI)
        stores.locks.locks.remove(ARSHAD.id)
        open(ARSHAD.copy(lock = null))
        compose.onNodeWithText("Your profile isn’t locked.", substring = true).assertExists()

        click("Lock your profile")

        compose.onNodeWithText("Your profile · owner").assertExists()
    }

    @Test
    fun backDuringANameEditOnThePageKeepsTheOldNameAndThePage() {
        open(YURI)
        click("Name")
        compose.onNode(hasSetTextAction()).performTextInput(" B")

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()

        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.onNode(hasText("Name") and hasClickAction()).assertExists()
        assertEquals("Yuri", stores.people.rows.value!!.last().name)
    }

    private companion object {
        val ARSHAD = Profile(1, "Arshad", ProfileTint.Amber, isOwner = true, lock = LockKind.Pin)
        val MIRA = Profile(2, "Mira", ProfileTint.Rose, isOwner = false, lock = LockKind.Pin)
        val YURI = Profile(3, "Yuri", ProfileTint.Teal, isOwner = false, lock = null)
        val ZAINAB = Profile(4, "Zainab", ProfileTint.Violet, isOwner = false, lock = null, isKids = true)
    }
}
