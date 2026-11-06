package com.arshad.ayn.ui.tv.settings

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.performSemanticsAction
import com.arshad.ayn.core.access.SegmentCategory
import com.arshad.ayn.core.backup.FakeBackupStore
import com.arshad.ayn.core.lock.FakeProfileLocks
import com.arshad.ayn.core.store.ChatPlacement
import com.arshad.ayn.core.store.DeviceSettings
import com.arshad.ayn.core.store.FakeDeviceSettingsStore
import com.arshad.ayn.core.store.FakeFilterStore
import com.arshad.ayn.core.store.FakeProfileSettingsStore
import com.arshad.ayn.core.store.FakeProfileStore
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.ProfileSession
import com.arshad.ayn.core.store.ProfileSettings
import com.arshad.ayn.core.store.ProfileTint
import com.arshad.ayn.core.store.SegmentAction
import com.arshad.ayn.feature.settings.BackupViewModel
import com.arshad.ayn.feature.settings.FiltersViewModel
import com.arshad.ayn.feature.settings.LICENSED_PARTS
import com.arshad.ayn.feature.settings.PreferencesSheet
import com.arshad.ayn.feature.settings.PreferencesViewModel
import com.arshad.ayn.feature.settings.ProfilesViewModel
import com.arshad.ayn.feature.settings.RestrictionsViewModel
import com.arshad.ayn.feature.settings.allLanguages
import com.arshad.ayn.ui.tv.TestPicks
import com.arshad.ayn.ui.tv.dpadSelect
import com.arshad.ayn.ui.tv.focus.SelectDwellMs
import com.arshad.ayn.ui.tv.pressBack
import com.arshad.ayn.ui.tv.testKidsAdd
import com.arshad.ayn.ui.tv.testSync
import com.arshad.ayn.ui.tv.theme.KeTheme
import kotlin.time.TimeSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings' preference pages (`b3-settings`) on the real PreferencesViewModel over the `:core`
 * fakes: the category list, each page's stored values, switches written to the profile's or the
 * TV's store (the owner's only, Q17), the Speed, Quality, Captions, All languages, category,
 * Licences and licence text sheets (a choice closes its sheet and focus returns to the row that
 * opened it), SponsorBlock off, the DeArrow sample, and About's credits.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp")
class PreferencesSettingsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val profileSettings = FakeProfileSettingsStore(ARSHAD.id)
    private val device = FakeDeviceSettingsStore()
    private lateinit var preferences: PreferencesViewModel

    /** [ownerStore] is what the preferences read the owner from (the profiles' store unless a test holds it back). */
    private fun open(me: Profile = ARSHAD, vararg everyone: Profile = arrayOf(ARSHAD), ownerStore: FakeProfileStore? = null) {
        val store = FakeProfileStore(everyone.toList())
        val locks = FakeProfileLocks().apply { ownerId = ARSHAD.id }
        val session = ProfileSession().apply { select(me.id) }
        profileSettings.selected.value = me.id
        val profiles = ProfilesViewModel(store, locks, FakeFilterStore(), session, TimeSource.Monotonic)
        val backup = BackupViewModel(FakeBackupStore(), store, locks, playback = {}, handover = { _, _ -> }, TestPicks(), me.id, TimeSource.Monotonic)
        preferences = PreferencesViewModel(profileSettings, device, ownerStore ?: store)
        val filters = FiltersViewModel(profileSettings, FakeFilterStore(), store, locks)
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
    }

    private fun row(label: String): SemanticsNodeInteraction = compose.onNode(hasText(label) and hasClickAction())

    /** A row holding [label] somewhere in it (a switch row's label sits beside its switch). */
    private fun rowWith(label: String): SemanticsNodeInteraction = compose.onNode(hasClickAction() and (hasText(label) or hasAnyDescendant(hasText(label))))

    private fun choose(category: String) {
        row(category).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
    }

    private fun select(label: String) {
        row(label).dpadSelect()
        compose.waitForIdle()
    }

    private fun press(keyCode: Int) {
        val down = AndroidKeyEvent(AndroidKeyEvent.ACTION_DOWN, keyCode)
        compose.onRoot().performKeyPress(KeyEvent(down))
        compose.onRoot().performKeyPress(KeyEvent(AndroidKeyEvent.changeAction(down, AndroidKeyEvent.ACTION_UP)))
        compose.waitForIdle()
    }

    /** The focused clickable row reading [label] (a sheet's choice can repeat a value on the page beneath). */
    private fun focusedRow(label: String): SemanticsNodeInteraction = compose.onNode(hasText(label) and hasClickAction() and isFocused())

    @Test
    fun theOwnersCategoriesFollowTheMockup() {
        open()

        val labels = listOf("Profiles", "Filters", "Playback", "SponsorBlock", "DeArrow", "Dislikes", "Interface", "Sync", "Backup & restore", "About")
        val tops = labels.map { row(it).fetchSemanticsNode().boundsInRoot.top }
        assertEquals(tops.sorted(), tops)
    }

    @Test
    fun aCategoryFocusOnlyPassesOverBuildsNoPaneAndTheOneItRestsOnIsChosen() {
        open()
        compose.mainClock.autoAdvance = false

        row("Playback").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.mainClock.advanceTimeBy(SelectDwellMs / 2)
        row("Dislikes").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.mainClock.advanceTimeBy(SelectDwellMs - 40)

        row("Quality").assertDoesNotExist()
        rowWith("Show dislike counts").assertDoesNotExist()
        row("Lock your profile").assertExists()

        compose.mainClock.advanceTimeBy(80)
        rowWith("Show dislike counts").assertExists()
        row("Quality").assertDoesNotExist()
        row("Dislikes").assertIsFocused()
    }

    @Test
    fun focusLeavingTheCategoriesBeforeItRestsChoosesNothing() {
        open()
        compose.mainClock.autoAdvance = false

        row("Playback").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.mainClock.advanceTimeBy(SelectDwellMs / 2)
        row("Lock your profile").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.mainClock.advanceTimeBy(SelectDwellMs * 3)

        row("Quality").assertDoesNotExist()
        row("Lock your profile").assertIsFocused()
    }

    @Test
    fun focusComingBackToTheChosenCategoryBeforeItRestsChoosesNothingNew() {
        open()
        compose.mainClock.autoAdvance = false

        row("Playback").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.mainClock.advanceTimeBy(SelectDwellMs / 2)
        row("Profiles").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.mainClock.advanceTimeBy(SelectDwellMs * 3)

        row("Quality").assertDoesNotExist()
        row("Lock your profile").assertExists()
        row("Profiles").assertIsFocused()
    }

    @Test
    fun okOnACategoryChoosesItWithoutWaiting() {
        open()
        compose.mainClock.autoAdvance = false

        row("Playback").dpadSelect()
        compose.mainClock.advanceTimeBy(SelectDwellMs / 3)

        row("Quality").assertExists()
    }

    @Test
    fun someoneElseHasImportInBackupsPlaceAndTheirOwnPages() {
        profileSettings.byProfile.value = mapOf(MIRA.id to ProfileSettings(dislikes = false))
        open(MIRA, ARSHAD, MIRA)

        val labels = listOf("Profiles", "Filters", "Playback", "SponsorBlock", "DeArrow", "Dislikes", "Interface", "Sync", "Import from YouTube", "About")
        val tops = labels.map { row(it).fetchSemanticsNode().boundsInRoot.top }
        assertEquals(tops.sorted(), tops)
        compose.onNode(hasText("Backup & restore")).assertDoesNotExist()
        choose("Dislikes")

        compose.onNode(hasText("Mira’s profile", substring = true)).assertExists()
        assertEquals(ToggleableState.Off, switchState("Show dislike counts"))
    }

    @Test
    fun playbackShowsTheProfilesAndTheTvsValues() {
        profileSettings.byProfile.value = mapOf(ARSHAD.id to ProfileSettings(speedPercent = 150, captionsLanguage = "ar"))
        device.current.value = DeviceSettings(maxVideoHeight = 1080, frameRateMatching = false)
        open()

        choose("Playback")

        row("1.5×").assertExists()
        row("Arabic").assertExists()
        row("Best available, up to 1080p").assertExists()
        compose.onNode(hasText("Everyone on this Shield")).assertExists()
        assertEquals(ToggleableState.Off, switchState("Match the TV to the video’s frame rate"))
    }

    @Test
    fun aProfileSwitchWritesTheProfilesSettings() {
        open()
        choose("Playback")

        select("Resume where you left off")

        assertFalse(profileSettings.of(ARSHAD.id).resume)
        assertEquals(ToggleableState.Off, switchState("Resume where you left off"))
    }

    @Test
    fun eachSwitchRowWritesItsOwnSetting() {
        open()
        val profile = ProfileSettings()
        val tv = DeviceSettings()
        // (category, row, the stored value after one press)
        val rows: List<Triple<String, String, () -> Boolean>> = listOf(
            Triple("Playback", "Resume where you left off") { profileSettings.of(ARSHAD.id).resume },
            Triple("Playback", "Match the TV to the video’s frame rate") { device.current.value!!.frameRateMatching },
            Triple("SponsorBlock", "Show a note after each skip") { profileSettings.of(ARSHAD.id).skipNote },
            Triple("DeArrow", "Use DeArrow titles") { profileSettings.of(ARSHAD.id).dearrowTitles },
            Triple("DeArrow", "Use DeArrow thumbnails") { profileSettings.of(ARSHAD.id).dearrowThumbnails },
            Triple("Dislikes", "Show dislike counts") { profileSettings.of(ARSHAD.id).dislikes },
            Triple("Interface", "Show Shorts") { !profileSettings.of(ARSHAD.id).showShorts },
            Triple("Interface", "For you") { profileSettings.of(ARSHAD.id).forYou },
            Triple("Interface", "Clock in the player") { device.current.value!!.clockInPlayer },
            Triple("Interface", "Hero on Home") { device.current.value!!.heroOnHome },
            Triple("SponsorBlock", "Use SponsorBlock") { profileSettings.of(ARSHAD.id).sponsorBlock },
        )
        rows.forEach { (category, label, stillDefault) ->
            profileSettings.byProfile.value = emptyMap()
            device.current.value = tv
            compose.waitForIdle()
            row(category).performSemanticsAction(SemanticsActions.RequestFocus)
            row(category).performSemanticsAction(SemanticsActions.RequestFocus)
            compose.waitForIdle()
            rowWith(label).dpadSelect()
            compose.waitForIdle()
            assertFalse(label, stillDefault())
            val changedFields = listOf(profileSettings.of(ARSHAD.id) != profile, device.current.value != tv).count { it }
            assertEquals(label, 1, changedFields)
        }
    }

    @Test
    fun aPageWaitsForTheTvsSettings() {
        device.current.value = null
        open()
        choose("Playback")
        row("Quality").assertDoesNotExist()

        device.current.value = DeviceSettings()
        compose.waitForIdle()

        row("Quality").assertExists()
    }

    @Test
    fun aTvSwitchWritesTheTvsSettings() {
        open()
        choose("Interface")

        select("Clock in the player")

        assertFalse(device.current.value!!.clockInPlayer)
        assertEquals(ProfileSettings(), profileSettings.of(ARSHAD.id))
    }

    @Test
    fun theCaptionsSheetStoresALanguageAndFocusReturnsToItsRow() {
        open()
        choose("Playback")

        select("Captions")
        compose.onNode(hasText("This Shield’s language")).assertExists()
        focusedRow("Off").assertExists()
        select("Arabic")

        assertEquals("ar", profileSettings.of(ARSHAD.id).captionsLanguage)
        assertNull(preferences.uiState.value.sheet)
        row("Captions").assertIsFocused()
        row("Arabic").assertExists()
    }

    @Test
    fun theCaptionsSheetOpensOnTheChosenLanguageFurtherDown() {
        profileSettings.byProfile.value = mapOf(ARSHAD.id to ProfileSettings(captionsLanguage = "iu"))
        open()
        choose("Playback")

        select("Captions")

        focusedRow("Inuktitut").assertExists()
    }

    @Test
    fun theCaptionsSheetGroupsItsLanguagesAndEndsWithAllLanguages() {
        open()
        choose("Playback")

        select("Captions")

        compose.onNode(hasText("Gaelic")).assertExists()
        compose.onNode(hasText("Indigenous languages")).assertExists()
        compose.onNode(hasText("Gàidhlig")).assertExists()
        compose.onNode(hasText("All languages…") and hasText("${allLanguages().size} languages, by the names this Shield gives them")).assertExists()
    }

    @Test
    fun allLanguagesOpensOnTheFirstAndBackReturnsToItsRow() {
        open()
        choose("Playback")
        select("Captions")

        select("All languages…")
        assertEquals(PreferencesSheet.AllLanguages, preferences.uiState.value.sheet)
        focusedRow(allLanguages().first().name).assertExists()
        compose.pressBack()

        assertEquals(PreferencesSheet.Captions, preferences.uiState.value.sheet)
        focusedRow("All languages…").assertExists()
    }

    @Test
    fun aPickFromAllLanguagesStoresItAndFocusReturnsToCaptions() {
        profileSettings.byProfile.value = mapOf(ARSHAD.id to ProfileSettings(captionsLanguage = "fr"))
        val languages = allLanguages()
        val next = languages[languages.indexOfFirst { it.tag == "fr" } + 1]
        open()
        choose("Playback")
        select("Captions")
        select("All languages…")
        focusedRow("French").assertExists()

        press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        focusedRow(next.name).dpadSelect()
        compose.waitForIdle()

        assertEquals(next.tag, profileSettings.of(ARSHAD.id).captionsLanguage)
        assertNull(preferences.uiState.value.sheet)
        row("Captions").assertIsFocused()
    }

    /** LIVE-D21: chat shows beside the video until changed, and the sheet opens there. */
    @Test
    fun liveChatIsBesideTheVideoUntilChanged() {
        open()
        choose("Playback")
        row("Beside the video").assertExists()

        select("Live chat")

        focusedRow("Beside the video").assertExists()
    }

    @Test
    fun aLiveChatPickIsStoredAndFocusReturnsToItsRow() {
        open()
        choose("Playback")
        select("Live chat")

        select("Top-right corner")

        assertEquals(ChatPlacement.TopRight, profileSettings.of(ARSHAD.id).chatPlacement)
        assertNull(preferences.uiState.value.sheet)
        row("Live chat").assertIsFocused()
        row("Top-right corner").assertExists()
    }

    @Test
    fun theLiveChatSheetOpensOnTheChosenPlace() {
        profileSettings.byProfile.value = mapOf(ARSHAD.id to ProfileSettings(chatPlacement = ChatPlacement.BottomRight))
        open()
        choose("Playback")

        select("Live chat")

        focusedRow("Bottom-right corner").assertExists()
    }

    @Test
    fun theSpeedSheetOpensOnTheSpeedAndStoresTheNewOne() {
        open()
        choose("Playback")

        select("Speed")
        focusedRow("Normal").assertExists()
        select("1.5×")

        assertEquals(150, profileSettings.of(ARSHAD.id).speedPercent)
        row("Speed").assertIsFocused()
        row("1.5×").assertExists()
    }

    @Test
    fun theOwnerPicksTheTvsQuality() {
        open()
        choose("Playback")

        select("Quality")
        focusedRow("Up to 2160p").assertExists()
        // `b3-settings` v14 quality: the caps that carry a line.
        listOf("Up to 2160p" to "4K", "Up to 1080p" to "Full HD", "Up to 480p" to "Uses the least data").forEach { (cap, line) ->
            compose.onNode(hasText(cap) and hasText(line) and hasClickAction()).assertExists()
        }
        select("Up to 720p")

        assertEquals(720, device.current.value!!.maxVideoHeight)
        assertEquals(ProfileSettings(), profileSettings.of(ARSHAD.id))
        row("Quality").assertIsFocused()
        row("Best available, up to 720p").assertExists()
    }

    @Test
    fun theOwnerPicksTheInterfaceSize() {
        open()
        choose("Interface")

        select("Interface size")
        focusedRow("100%").assertExists()
        // The page's own row also reads 100%: each size has a row in the sheet.
        listOf("80%", "90%", "100%", "110%", "120%").forEach { size ->
            assertTrue(size, compose.onAllNodes(hasText(size) and hasClickAction()).fetchSemanticsNodes().isNotEmpty())
        }
        // `b3-settings` v15 ifsize: the sizes that carry a line.
        listOf("80%" to "More fits on the screen", "100%" to "As Ayn is drawn", "120%" to "The largest text").forEach { (size, line) ->
            compose.onNode(hasText(size) and hasText(line) and hasClickAction()).assertExists()
        }
        select("120%")

        assertEquals(120, device.current.value!!.interfaceScalePercent)
        row("Interface size").assertIsFocused()
        row("120%").assertExists()
    }

    @Test
    fun focusPassesOverTheCodecWhileItIsTheOnlyOne() {
        open()
        choose("Playback")

        row("Quality").performSemanticsAction(SemanticsActions.RequestFocus)
        press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)

        rowWith("Match the TV to the video’s frame rate").assertIsFocused()
        compose.onNode(hasText("The one codec Ayn plays for now; it carries 4K and 60 fps on the Shield")).assertExists()
    }

    @Test
    fun someoneElseCannotReachOrChangeTheTvsRows() {
        open(MIRA, ARSHAD, MIRA)
        choose("Playback")
        compose.onNode(hasText("Only the owner, Arshad, can change these")).assertExists()

        row("Live chat").performSemanticsAction(SemanticsActions.RequestFocus)
        press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        row("Live chat").assertIsFocused()
        row("Quality").performSemanticsAction(SemanticsActions.OnClick)
        rowWith("Match the TV to the video’s frame rate").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()

        assertNull(preferences.uiState.value.sheet)
        assertEquals(DeviceSettings(), device.current.value)
    }

    @Test
    fun aPageWaitsForWhoTheOwnerIs() {
        val owners = FakeProfileStore(null)
        open(ownerStore = owners)
        choose("Playback")
        row("Quality").assertDoesNotExist()

        owners.rows.value = listOf(ARSHAD)
        compose.waitForIdle()

        row("Quality").assertExists()
    }

    @Test
    fun someoneElsesInterfaceKeepsTheirShortsAndForYouAndDimsTheTvsRows() {
        open(MIRA, ARSHAD, MIRA)
        choose("Interface")
        compose.onNode(hasText("Only the owner, Arshad, can change these")).assertExists()

        rowWith("For you").performSemanticsAction(SemanticsActions.RequestFocus)
        press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)

        rowWith("For you").assertIsFocused()
        select("Show Shorts")
        select("For you")
        assertEquals(ProfileSettings(showShorts = true, forYou = false), profileSettings.of(MIRA.id))
    }

    @Test
    fun deArrowsSampleFollowsItsSwitches() {
        profileSettings.byProfile.value = mapOf(ARSHAD.id to ProfileSettings(dearrowTitles = false))
        open()
        choose("DeArrow")

        compose.onAllNodesWithText(ORIGINAL_TITLE).assertCountEquals(2)
        compose.onNode(hasText("With DeArrow")).assertExists()

        rowWith("Use DeArrow thumbnails").dpadSelect()
        compose.waitForIdle()

        compose.onNode(hasText("With DeArrow · off")).assertExists()
    }

    @Test
    fun aCategorySheetOpensOnItsActionAndStoresTheNewOne() {
        open()
        choose("SponsorBlock")

        select("Sponsor")
        assertEquals(PreferencesSheet.Category(SegmentCategory.Sponsor), preferences.uiState.value.sheet)
        focusedRow("Show a skip button").assertExists()
        // Skip automatically, by its line in the sheet (two page rows read the same).
        select("Jumps past it and shows a note with Undo")

        assertEquals(SegmentAction.Skip, profileSettings.of(ARSHAD.id).segmentActions[SegmentCategory.Sponsor])
        row("Sponsor").assertIsFocused()
    }

    @Test
    fun backFromASheetKeepsThePageAndItsRow() {
        open()
        choose("SponsorBlock")
        select("Self-promotion")

        compose.pressBack()

        assertNull(preferences.uiState.value.sheet)
        row("Self-promotion").assertIsFocused()
        assertEquals(ProfileSettings(), profileSettings.of(ARSHAD.id))
    }

    @Test
    fun afterBackFromASheetLeftLandsOnThePagesCategoryAndDownChoosesTheNext() {
        open()
        choose("Playback")
        select("Captions")

        compose.pressBack()
        row("Captions").assertIsFocused()
        press(AndroidKeyEvent.KEYCODE_DPAD_LEFT)
        row("Playback").assertIsFocused()
        press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)

        row("Sponsor").assertExists()
    }

    @Test
    fun withSponsorBlockOffFocusPassesOverTheCategories() {
        profileSettings.byProfile.value = mapOf(ARSHAD.id to ProfileSettings(sponsorBlock = false))
        open()
        choose("SponsorBlock")
        row("Off · your category choices are kept").assertExists()

        rowWith("Use SponsorBlock").performSemanticsAction(SemanticsActions.RequestFocus)
        press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)

        rowWith("Use SponsorBlock").assertIsFocused()
        row("Sponsor").assertExists()
    }

    @Test
    fun withSponsorBlockOnDownReachesTheCategories() {
        open()
        choose("SponsorBlock")

        rowWith("Use SponsorBlock").performSemanticsAction(SemanticsActions.RequestFocus)
        press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)

        row("Sponsor").assertIsFocused()
    }

    @Test
    fun aboutCreditsAreTextAndTheLicencesSheetListsAynsParts() {
        open()
        choose("About")
        compose.onNode(hasText("Skip segments from sponsor.ajay.app · CC BY-NC-SA 4.0")).assertExists()

        row("Source code").performSemanticsAction(SemanticsActions.RequestFocus)
        press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        row("Source code").assertIsFocused()

        select("Open-source licences")
        focusedRow("NewPipeExtractor (LibreTube fork)").assertExists()
        compose.onNode(hasText("Runs YouTube’s player script · MPL\u20112.0")).assertExists()
        compose.pressBack()

        row("Open-source licences").assertIsFocused()
        select("Ayn’s licence")
        compose.pressBack()
        row("Ayn’s licence").assertIsFocused()
    }

    @Test
    fun sourceCodeShowsTheRepositoryAsACodeAndBackReturnsToTheRow() {
        open()
        choose("About")

        select("Source code")
        assertEquals(PreferencesSheet.SourceCode, preferences.uiState.value.sheet)
        compose.onNode(isFocused() and hasAnyDescendant(hasContentDescription("QR code for github.com/Arshad-Kamal/ayn"))).assertExists()
        compose.pressBack()

        assertEquals(null, preferences.uiState.value.sheet)
        row("Source code").assertIsFocused()
    }

    @Test
    fun theLicencesListIsTheApprovedPartsInOrder() {
        // `b3-settings` v20 licences: name, what it does, SPDX id.
        val approved = listOf(
            Triple("NewPipeExtractor (LibreTube fork)", "Reads YouTube’s pages", "GPL-3.0-or-later"),
            Triple("LibreTube player code", "Streams the video", "GPL-3.0-or-later"),
            Triple("AndroidX, Jetpack Compose, Media3, Guava", "Screens and the player", "Apache-2.0"),
            Triple("OkHttp, Okio, Coil, Accompanist, Kotlin libraries, nanojson", "Network, images, code", "Apache-2.0"),
            Triple("JSR 305, JSpecify, JetBrains annotations", "Code annotations", "Apache-2.0"),
            Triple("Rhino", "Runs YouTube’s player script", "MPL-2.0"),
            Triple("Public Suffix List (in OkHttp)", "Web address rules", "MPL-2.0"),
            Triple("yt-dlp EJS", "Plays made-for-kids videos", "Unlicense"),
            Triple("meriyah (in EJS)", "Reads the player script", "ISC"),
            Triple("astring (in EJS)", "Rewrites the player script", "MIT"),
            Triple("BgUtils (in the token page)", "Playback tokens", "MIT"),
            Triple("Protocol Buffers", "The stream’s format", "BSD-3-Clause"),
            Triple("googlevideo schemas", "The stream’s messages", "MIT"),
            Triple("jsoup", "Video descriptions", "MIT"),
            Triple("RE2/J", "Title patterns in filters", "BSD-3-Clause"),
            Triple("QR Code generator", "Sign-in codes on the TV", "MIT"),
            Triple("Archivo", "The typeface", "OFL-1.1"),
        )
        open()
        choose("About")
        select("Open-source licences")

        val tops = approved.map { (name, role, id) ->
            val hint = "$role · ${id.replace('-', '\u2011')}"
            compose.onNode(hasText(name) and hasText(hint) and hasClickAction()).fetchSemanticsNode().positionInRoot.y
        }
        assertEquals(tops.sorted(), tops)
        assertEquals(approved.size, LICENSED_PARTS.size)
    }

    @Test
    fun aLicencesTextOpensFromItsPartAndBackReturnsToIt() {
        open()
        choose("About")
        select("Open-source licences")

        select("Rhino")
        compose.onNode(hasText("Licences · Rhino")).assertExists()
        compose.onNode(hasText("Mozilla Public License 2.0")).assertExists()
        compose.waitUntil(timeoutMillis = LICENCE_READ_MS) {
            compose.onAllNodesWithText("Mozilla Public License Version 2.0").fetchSemanticsNodes().isNotEmpty()
        }
        press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(PreferencesSheet.LicenceText("Rhino"), preferences.uiState.value.sheet)
        compose.onNode(isFocused()).assertExists()
        compose.pressBack()

        assertEquals(PreferencesSheet.Licences, preferences.uiState.value.sheet)
        focusedRow("Rhino").assertExists()
    }

    @Test
    fun upAndDownTurnALicencesTextAPageAtATimeAndKeepItsFocus() {
        open()
        choose("About")
        select("Open-source licences")
        select("Rhino")
        val heading = compose.onNode(hasText("Mozilla Public License Version 2.0"))
        compose.waitUntil(timeoutMillis = LICENCE_READ_MS) {
            compose.onAllNodesWithText("Mozilla Public License Version 2.0").fetchSemanticsNodes().isNotEmpty()
        }
        val text = compose.onNode(isFocused()).fetchSemanticsNode()
        val page = (text.boundsInRoot.height * 0.85f).toInt()
        fun headingY() = heading.fetchSemanticsNode().positionInRoot.y
        val start = headingY()

        press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(start - page, headingY(), 2f)
        compose.mainClock.autoAdvance = false
        pressWithoutWaiting(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        pressWithoutWaiting(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        compose.mainClock.advanceTimeBy(2_000)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(start - 3 * page, headingY(), 3f)
        press(AndroidKeyEvent.KEYCODE_DPAD_UP)

        assertEquals(start - 2 * page, headingY(), 3f)
        assertEquals(text.id, compose.onNode(isFocused()).fetchSemanticsNode().id)
    }

    private fun pressWithoutWaiting(keyCode: Int) {
        val down = AndroidKeyEvent(AndroidKeyEvent.ACTION_DOWN, keyCode)
        compose.onRoot().performKeyPress(KeyEvent(down))
        compose.onRoot().performKeyPress(KeyEvent(AndroidKeyEvent.changeAction(down, AndroidKeyEvent.ACTION_UP)))
    }

    private fun switchState(label: String): ToggleableState? {
        val node = rowWith(label).fetchSemanticsNode()
        return node.config.getOrElseNullable(SemanticsProperties.ToggleableState) { null }
    }

    private companion object {
        val ARSHAD = Profile(1, "Arshad", ProfileTint.Amber, isOwner = true, lock = null)
        val MIRA = Profile(2, "Mira", ProfileTint.Rose, isOwner = false, lock = null)
        const val ORIGINAL_TITLE = "The Insane Real Engineering of the Nazi Enigma Machine"

        // The text is read on Dispatchers.IO, a real thread here: the wait is wall-clock, and a loaded
        // build machine has taken over 5 s.
        const val LICENCE_READ_MS = 20_000L
    }
}
