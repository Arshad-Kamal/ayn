package com.arshad.ayn.ui.mobile.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.arshad.ayn.core.access.SegmentCategory
import com.arshad.ayn.core.backup.SavedBackup
import com.arshad.ayn.core.lock.LockKind
import com.arshad.ayn.core.store.ChatPlacement
import com.arshad.ayn.core.store.DeviceSettings
import com.arshad.ayn.core.store.Keyword
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.ProfileRules
import com.arshad.ayn.core.store.ProfileTint
import com.arshad.ayn.core.store.SegmentAction
import com.arshad.ayn.core.store.StoredRule
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
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Settings' index and preference pages on the phone (`m6-settings` 1–16) through the shell's real
 * page wiring on the stores' fakes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class SettingsPagesTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val stores = TestStores()
    private lateinit var nav: ShellNav

    @Before
    fun setUp() {
        installOfflineImageLoader(compose.activity)
        stores.people.rows.value = listOf(ARSHAD, MIRA, ZAID, GUESTS)
        stores.profileSession.select(ARSHAD.id)
    }

    private fun open(me: Profile = ARSHAD, page: SettingsPage? = null) {
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
            page?.let { nav.open(SettingsPageKey(it)) }
        }
        compose.waitForIdle()
    }

    /** A row in a sheet that scrolls: brought into view first. */
    private fun clickInSheet(label: String) {
        compose.onNode(hasText(label) and hasClickAction()).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun click(label: String) {
        compose.onNode(hasText(label) and hasClickAction()).performClick()
        compose.waitForIdle()
    }

    @Test
    fun theIndexSaysWhatEachCategoryHoldsNow() {
        stores.filters.rulesById.value = mapOf(
            ARSHAD.id to ProfileRules(rules = listOf(StoredRule(Keyword("prank"), false, 1), StoredRule(Keyword("asmr"), false, 2))),
        )
        stores.backups.saved += listOf(SavedBackup(Uri.EMPTY, "Ayn-backup-1.json", 1), SavedBackup(Uri.EMPTY, "Ayn-backup-2.json", 2))
        open()

        compose.onNodeWithText("Owner").assertExists()
        compose.onNodeWithText("4 profiles · you’re the owner").assertExists()
        compose.onNodeWithText("Restricted Mode off · 2 keywords").assertExists()
        compose.onNodeWithText("Normal speed · captions off · background audio on").assertExists()
        compose.onNodeWithText("On · sponsors get a skip button").assertExists()
        compose.onNodeWithText("Titles and thumbnails").assertExists()
        compose.onNodeWithText("Shown in Details").assertExists()
        compose.onNodeWithText("Shorts off · For you on · Hero on Home").assertExists()
        compose.onNodeWithText("2 backups on this phone").assertExists()
    }

    @Test
    fun settingsSlidesTheBarAwayAndBackBringsItBack() {
        open()
        compose.onNode(hasText("Home") and isSelectable()).assertIsNotDisplayed()

        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()

        compose.onNode(hasText("Home") and isSelectable()).assertIsDisplayed()
    }

    @Test
    fun noBackupsYetIsNoLine() {
        open()

        compose.onNodeWithText("Backup & restore").assertExists()
        compose.onNodeWithText("backups on this phone", substring = true).assertDoesNotExist()
    }

    @Test
    fun anotherProfileSeesImportInsteadOfBackupAndWhoOwnsAyn() {
        open(me = MIRA)

        compose.onNodeWithText("Owner").assertDoesNotExist()
        compose.onNodeWithText("Arshad is the owner").assertExists()
        compose.onNodeWithText("Import from YouTube").assertExists()
        compose.onNodeWithText("Backup & restore").assertDoesNotExist()
    }

    @Test
    fun aCategoryOpensItsPageAndBackReturnsToTheList() {
        open()
        click("Playback")
        compose.onNodeWithText("Resume where you left off").assertExists()

        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Restricted Mode off").assertExists()
    }

    @Test
    fun backgroundAudioOffSaysWhatOffDoes() {
        open(page = SettingsPage.Playback)

        compose.onNode(hasText("Background audio") and isToggleable()).assertIsOn().performClick()
        compose.waitForIdle()

        assertFalse(stores.device.current.value!!.backgroundAudio)
        compose.onNode(hasText("Background audio") and isToggleable()).assertIsOff()
        compose.onNodeWithText("Off: leaving Ayn or locking the phone pauses the video").assertExists()
    }

    @Test
    fun anotherProfileSeesThisPhonesRowsOutOfReach() {
        open(me = MIRA, page = SettingsPage.Playback)

        compose.onNodeWithText("Mira’s profile").assertExists()
        compose.onNodeWithText("Only the owner, Arshad, can change these").assertExists()
        compose.onNode(hasText("Background audio") and isToggleable()).assertIsNotEnabled()
        compose.onNode(hasText("Quality") and hasClickAction()).assertIsNotEnabled()
    }

    @Test
    fun aSpeedPickSavesAndClosesTheSheet() {
        open(page = SettingsPage.Playback)
        click("Speed")
        compose.onNode(hasText("Normal") and isSelectable()).assertIsSelected()

        compose.onNode(hasText("1.5×") and isSelectable()).performClick()
        compose.waitForIdle()

        assertEquals(150, stores.settings.of(ARSHAD.id).speedPercent)
        compose.onNode(hasText("1.5×") and hasClickAction()).assertExists()
        compose.onNodeWithText("Every video starts at this speed", substring = true).assertDoesNotExist()
    }

    /** LIVE-D21: chat shows beside the video until changed; a pick saves, closes, and the row says it. */
    @Test
    fun aLiveChatPickSavesAndTheRowSaysIt() {
        open(page = SettingsPage.Playback)
        click("Live chat")
        compose.onNode(hasText("Beside the video") and isSelectable()).assertIsSelected()

        compose.onNode(hasText("Bottom-left corner") and isSelectable()).performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals(ChatPlacement.BottomLeft, stores.settings.of(ARSHAD.id).chatPlacement)
        compose.onNode(hasText("Bottom-left corner") and hasClickAction()).assertExists()
        compose.onNodeWithText("Over the picture applies sideways", substring = true).assertDoesNotExist()
    }

    @Test
    fun aQualityPickSavesForThisPhoneAndClosesTheSheet() {
        open(page = SettingsPage.Playback)
        click("Quality")

        compose.onNode(hasText("Up to 720p") and isSelectable()).performClick()
        compose.waitForIdle()

        assertEquals(720, stores.device.current.value!!.maxVideoHeight)
        compose.onNodeWithText("Up to 720p").assertExists()
        compose.onNodeWithText("The most Ayn plays on this phone", substring = true).assertDoesNotExist()
    }

    @Test
    fun aCaptionsPickSavesAndTheRowSaysIt() {
        open(page = SettingsPage.Playback)
        click("Captions")

        compose.onNode(hasText("Arabic") and isSelectable()).performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals("ar", stores.settings.of(ARSHAD.id).captionsLanguage)
        compose.onNodeWithText("Arabic").assertExists()
        compose.onNodeWithText("Gaelic").assertDoesNotExist()
    }

    @Test
    fun allLanguagesOpensInTheCaptionsSheetAndBackReturns() {
        open(page = SettingsPage.Playback)
        click("Captions")
        clickInSheet("All languages…")
        compose.onNodeWithText("A video shows captions only when it has them in the language you pick.").assertExists()

        compose.onNodeWithContentDescription("Back to Captions").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Gaelic").assertExists()
    }

    @Test
    fun sponsorBlockOffKeepsTheChoicesOutOfReach() {
        open(page = SettingsPage.SponsorBlock)

        compose.onNode(hasText("Use SponsorBlock") and isToggleable()).performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Off · your category choices are kept").assertExists()
        compose.onNode(hasText("Sponsor") and hasClickAction()).assertIsNotEnabled()
    }

    @Test
    fun aCategorysActionIsPickedFromItsSheet() {
        open(page = SettingsPage.SponsorBlock)
        click("Sponsor")

        compose.onNode(hasText("Skip automatically") and isSelectable()).performClick()
        compose.waitForIdle()

        assertEquals(SegmentAction.Skip, stores.settings.of(ARSHAD.id).segmentActions[SegmentCategory.Sponsor])
        compose.onNodeWithText("Jumps past it and shows a note with Undo").assertDoesNotExist()
        compose.onNode(hasText("Sponsor") and hasClickAction()).assert(hasText("Skip automatically"))
    }

    @Test
    fun interfaceHasNoInterfaceSizeAndItsSwitchesSave() {
        open(page = SettingsPage.Interface)
        compose.onNodeWithText("Interface size").assertDoesNotExist()

        compose.onNode(hasText("Hero on Home") and isToggleable()).performClick()
        compose.waitForIdle()

        assertEquals(DeviceSettings(heroOnHome = false), stores.device.current.value)
    }

    @Test
    fun aLicenceOpensAsAPageAndBackReturnsToTheList() {
        open(page = SettingsPage.About)
        compose.onNodeWithText("GPL\u20113.0\u2011or\u2011later").assertExists()
        click("Open-source licences")
        clickInSheet("Rhino")

        compose.onNodeWithText("Licences · Rhino").assertExists()
        compose.waitUntil(LICENCE_READ_MS) { compose.onAllNodesWithText("means each individual or legal entity", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Mozilla Public License 2.0").assertExists()

        compose.onNode(hasContentDescription("Back") and hasAnyAncestor(isDialog())).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Licences · Rhino").assertDoesNotExist()
        compose.onNodeWithText("Licences").assertExists()
    }

    @Test
    fun sourceCodeOpensTheRepositoryInTheBrowser() {
        open(page = SettingsPage.About)
        compose.onNodeWithText("github.com/Arshad-Kamal/ayn · read, build and change Ayn").assertExists()

        click("Source code")

        val started = shadowOf(compose.activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals("https://github.com/Arshad-Kamal/ayn", started.dataString)
    }

    @Test
    fun withoutABrowserSourceCodeLeavesAboutShowing() {
        shadowOf(compose.activity.application).checkActivities(true)
        open(page = SettingsPage.About)

        click("Source code")

        compose.onNodeWithText("Open-source licences").assertExists()
    }

    private companion object {
        val ARSHAD = Profile(1, "Arshad", ProfileTint.Amber, isOwner = true, lock = LockKind.Pin)
        val MIRA = Profile(2, "Mira", ProfileTint.Rose, isOwner = false, lock = null)
        val ZAID = Profile(3, "Zaid", ProfileTint.Teal, isOwner = false, lock = null)
        val GUESTS = Profile(4, "Guests", ProfileTint.Blue, isOwner = false, lock = null)

        // The text is read on Dispatchers.IO, a real thread here: the wait is wall-clock, and a loaded
        // build machine has taken over 5 s.
        const val LICENCE_READ_MS = 20_000L
    }
}
