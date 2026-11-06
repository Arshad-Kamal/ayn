package com.arshad.ayn.ui.tv.settings

import com.arshad.ayn.ui.tv.testKidsAdd
import com.arshad.ayn.ui.tv.testSync
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import com.arshad.ayn.core.backup.FakeBackupStore
import com.arshad.ayn.core.filter.packs.IslamicOnly
import com.arshad.ayn.core.lock.FakeProfileLocks
import com.arshad.ayn.core.store.BlockedChannel
import com.arshad.ayn.core.store.BlockedVideo
import com.arshad.ayn.core.store.ContentRule
import com.arshad.ayn.core.store.FakeDeviceSettingsStore
import com.arshad.ayn.core.store.FakeFilterStore
import com.arshad.ayn.core.store.FakeProfileSettingsStore
import com.arshad.ayn.core.store.FakeProfileStore
import com.arshad.ayn.core.store.FilterPacks
import com.arshad.ayn.core.store.Keyword
import com.arshad.ayn.core.store.LengthLimits
import com.arshad.ayn.core.store.Pattern
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.ProfileRestrictions
import com.arshad.ayn.core.store.ProfileRules
import com.arshad.ayn.core.store.ProfileSession
import com.arshad.ayn.core.store.ProfileSettings
import com.arshad.ayn.core.store.ProfileTint
import com.arshad.ayn.core.store.RestrictedMode
import com.arshad.ayn.core.store.StoredRule
import com.arshad.ayn.feature.settings.BackupViewModel
import com.arshad.ayn.feature.settings.FilterPack
import com.arshad.ayn.feature.settings.FiltersSheet
import com.arshad.ayn.feature.settings.HIDE_SHORTS_HINT
import com.arshad.ayn.feature.settings.PACKS_NOTE
import com.arshad.ayn.feature.settings.hint
import com.arshad.ayn.feature.settings.FiltersViewModel
import com.arshad.ayn.feature.settings.PreferencesViewModel
import com.arshad.ayn.feature.settings.ProfilesViewModel
import com.arshad.ayn.feature.settings.RestrictionsViewModel
import com.arshad.ayn.ui.tv.TestPicks
import com.arshad.ayn.ui.tv.dpadSelect
import com.arshad.ayn.ui.tv.press
import com.arshad.ayn.ui.tv.pressBack
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
 * Settings › Filters (`kp-filters` filters, rm, keywords, keywordadd, channels, length,
 * mirafilters) on the real FiltersViewModel over the `:core` fakes: the page's stored values,
 * the Restricted Mode and length sheets, adding and removing a word, unblocking a channel (focus
 * returns to the row that opened each), Show Shorts leading to Interface's switch, and what the
 * owner set shown dimmed to anyone else, out of focus's reach; the owner's Restrictions on
 * another profile (`kp-filters` psheet, restrictions) from its sheet and back; the filter packs
 * (`kp-filters` fpk-*): rows, sheets and suggestions, on Filters and on Restrictions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp")
class FiltersSettingsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val profileSettings = FakeProfileSettingsStore(ARSHAD.id)
    private val filterStore = FakeFilterStore()
    private lateinit var filters: FiltersViewModel

    private fun open(me: Profile = ARSHAD) {
        val store = FakeProfileStore(listOf(ARSHAD, MIRA, ZAINAB))
        val locks = FakeProfileLocks().apply {
            ownerId = ARSHAD.id
            sessionId = me.id
        }
        val session = ProfileSession().apply { select(me.id) }
        profileSettings.selected.value = me.id
        val profiles = ProfilesViewModel(store, locks, FakeFilterStore(), session, TimeSource.Monotonic)
        val backup = BackupViewModel(FakeBackupStore(), store, locks, playback = {}, handover = { _, _ -> }, TestPicks(), me.id, TimeSource.Monotonic)
        val preferences = PreferencesViewModel(profileSettings, FakeDeviceSettingsStore(), store)
        filters = FiltersViewModel(profileSettings, filterStore, store, locks)
        val restrictions = RestrictionsViewModel(filterStore, store, locks, TimeSource.Monotonic)
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
        row("Filters").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
    }

    private fun row(label: String): SemanticsNodeInteraction = compose.onNode(hasText(label) and hasClickAction())

    private fun rowWith(label: String): SemanticsNodeInteraction =
        compose.onNode(hasClickAction() and (hasText(label) or hasAnyDescendant(hasText(label))))

    private fun focusedRow(label: String): SemanticsNodeInteraction = compose.onNode(hasText(label) and hasClickAction() and isFocused())

    private fun select(label: String) {
        row(label).dpadSelect()
        compose.waitForIdle()
    }

    private fun pressDown() = compose.press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)

    /** [profile]'s own rules, oldest first as the store lists them, then the owner's. */
    private fun rules(
        profile: Profile,
        vararg own: ContentRule,
        restrictions: ProfileRestrictions = ProfileRestrictions(),
        byOwner: List<ContentRule> = emptyList(),
    ) {
        val stored = own.mapIndexed { i, rule -> StoredRule(rule, byOwner = false, addedAtMillis = i.toLong()) } +
            byOwner.map { StoredRule(it, byOwner = true, addedAtMillis = 0) }
        filterStore.rulesById.value = filterStore.rulesById.value + (profile.id to ProfileRules(restrictions, stored))
    }

    @Test
    fun filtersSitsAfterProfilesAndShowsTheStoredValues() {
        profileSettings.byProfile.value = mapOf(ARSHAD.id to ProfileSettings(hideMembersOnly = true, lengthLimits = LengthLimits(maxMinutes = 30)))
        rules(ARSHAD, Keyword("prank"), Keyword("asmr"), CHANNEL)
        open()

        val tops = listOf("Profiles", "Filters", "Playback").map { row(it).fetchSemanticsNode().boundsInRoot.top }
        assertEquals(tops.sorted(), tops)
        rowWith("Video length").assertExists()
        compose.onNode(hasText("Over 30 min hidden")).assertExists()
        compose.onNode(hasText("2 words")).assertExists()
        assertEquals(ToggleableState.On, switchState("Hide members-only videos"))
        compose.onNode(hasText("$PACKS_NOTE $FILTERS_REACH$FILTERS_OWNER_TAIL")).assertExists()
    }

    /** `kp-filters` fpk-filters: the Filter packs rows sit after Blocked, before Shorts. */
    @Test
    fun filterPacksSitAfterBlockedWithTheirValues() {
        profileSettings.byProfile.value = mapOf(ARSHAD.id to ProfileSettings(filterPacks = FilterPacks(halalOnly = true)))
        open()

        val tops = listOf("Keywords", "Harmful for kids", "Halal only", "Islamic content only", "Show Shorts")
            .map { rowWith(it).fetchSemanticsNode().boundsInRoot.top }
        assertEquals(tops.sorted(), tops)
        rowWith("Halal only").assert(hasText("On"))
        rowWith("Harmful for kids").assert(hasText("Off"))
        rowWith("Islamic content only").assert(hasText("Off"))
    }

    /** FPK-D7 (`kp-filters` fpk-suggest): Harmful for kids going on offers what the profile lacks;
     *  Turn on sets only the ticked ones and its sheet comes back, focus on Use this pack. */
    @Test
    fun harmfulForKidsOffersSuggestionsAndTurnOnSetsTheTickedOnes() {
        open()
        select("Harmful for kids")
        focusedRow("Use this pack").assertExists()

        select("Use this pack")
        compose.onNodeWithText("Also turn these on?").assertExists()
        focusedRow("Turn on 2").assertExists()
        sheetRow("Hide live streams").dpadSelect()
        compose.waitForIdle()
        focusedRow("Hide live streams").assertExists()
        select("Turn on 1")

        val settings = profileSettings.of(ARSHAD.id)
        assertTrue(settings.filterPacks.harmfulForKids)
        assertFalse(settings.hideLive)
        assertEquals(LengthLimits(maxMinutes = 30), settings.lengthLimits)
        assertEquals(FiltersSheet.Pack(FilterPack.HarmfulForKids), filters.uiState.value.sheet)
        focusedRow("Use this pack").assertExists()
    }

    @Test
    fun backOnTheSuggestionsSetsNothingAndReturnsToThePack() {
        open()
        select("Harmful for kids")
        select("Use this pack")

        compose.pressBack()
        compose.waitForIdle()

        assertEquals(ProfileSettings(filterPacks = FilterPacks(harmfulForKids = true)), profileSettings.of(ARSHAD.id))
        focusedRow("Use this pack").assertExists()
        compose.pressBack()
        compose.waitForIdle()
        rowWith("Harmful for kids").assertIsFocused()
    }

    /** FPK-D2: Islamic content only says it turns Halal only off, and does. */
    @Test
    fun islamicContentOnlyTurnsHalalOnlyOff() {
        profileSettings.byProfile.value = mapOf(ARSHAD.id to ProfileSettings(filterPacks = FilterPacks(halalOnly = true)))
        open()
        select("Islamic content only")
        compose.onNode(hasText("Turns Halal only off: one of the two at a time")).assertExists()

        select("Use this pack")
        compose.pressBack()
        compose.waitForIdle()

        assertEquals(FilterPacks(islamicOnly = true), profileSettings.of(ARSHAD.id).filterPacks)
        rowWith("Halal only").assert(hasText("Off"))
    }

    /** FPK-D12: the row counts the channels on by default for a profile that isn't a kids one. */
    @Test
    fun islamicContentOnlysRowCountsItsChannels() {
        profileSettings.byProfile.value = mapOf(ARSHAD.id to ProfileSettings(filterPacks = FilterPacks(islamicOnly = true)))
        open()

        rowWith("Islamic content only").assert(hasText("On · ${IslamicOnly.channels.count { it.onForAdults }} channels"))
    }

    @Test
    fun turnOnWithNothingTickedTakesNoFocus() {
        open()
        select("Harmful for kids")
        select("Use this pack")

        sheetRow("Hide live streams").dpadSelect()
        sheetRow("Hide videos longer than 30 min").dpadSelect()
        compose.waitForIdle()
        row("Turn on").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()

        row("Turn on").assertIsNotFocused()
    }

    /** A sheet with a foot note scrolls its rows in all the height above the note, not half of it. */
    @Test
    fun aSheetsRowsUseTheHeightAboveItsNote() {
        open()

        select("Halal only")

        val rows = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).onLast()
            .fetchSemanticsNode().boundsInRoot
        val note = compose.onNodeWithText("Ayn reads titles", substring = true).fetchSemanticsNode().boundsInRoot
        val gapDp = (note.top - rows.bottom) / compose.density.density
        assertTrue("rows end ${gapDp}dp above the note", gapDp in 0f..12f)
    }

    @Test
    fun anIslamicChannelSwitchedOffIsKeptForTheProfile() {
        open()
        select("Islamic content only")
        val alafasy = IslamicOnly.channels.first { it.name == "Alafasy" }
        assertEquals(ToggleableState.On, switchState("Alafasy"))

        sheetRow("Alafasy").dpadSelect()
        compose.waitForIdle()

        assertEquals(mapOf(alafasy.id to false), profileSettings.of(ARSHAD.id).filterPacks.islamicChannels)
        assertEquals(ToggleableState.Off, switchState("Alafasy"))
    }

    @Test
    fun aPackOptionSwitches() {
        open()
        select("Halal only")

        sheetRow("Stage magic").dpadSelect()
        compose.waitForIdle()

        assertTrue(profileSettings.of(ARSHAD.id).filterPacks.stageMagic)
        assertFalse(profileSettings.of(ARSHAD.id).filterPacks.halalOnly)
    }

    @Test
    fun strictIsPickedAndFocusReturnsToItsRow() {
        open()

        select("Restricted Mode")
        focusedRow("Off").assertExists()
        select("Strict")

        assertEquals(RestrictedMode.Strict, profileSettings.of(ARSHAD.id).restrictedMode)
        assertNull(filters.uiState.value.sheet)
        row("Restricted Mode").assertIsFocused()
    }

    @Test
    fun theLengthSheetSetsBothBoundsAndStaysOpen() {
        open()

        select("Video length")
        focusedRow("Off").assertExists()
        select("3 min")
        select("2 hours")

        assertEquals(LengthLimits(3, 120), profileSettings.of(ARSHAD.id).lengthLimits)
        compose.pressBack()
        row("Video length").assertIsFocused()
    }

    @Test
    fun aWordTypedInPlaceIsSavedAtTheTop() {
        rules(ARSHAD, Keyword("prank"))
        open()

        select("Keywords")
        focusedRow("Add a word").assertExists()
        select("Add a word")
        compose.onNode(hasSetTextAction()).performTextInput("unbox")
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.waitForIdle()

        val words = listOf("unbox", "prank").map { row(it).fetchSemanticsNode().boundsInRoot.top }
        assertEquals(words.sorted(), words)
        focusedRow("Add a word").assertExists()
    }

    @Test
    fun aWordAlreadyThereSaysSoUnderTheField() {
        rules(ARSHAD, Keyword("prank"))
        open()
        select("Keywords")
        select("Add a word")

        compose.onNode(hasSetTextAction()).performTextInput("prank")
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.waitForIdle()

        compose.onNode(hasText("“prank” is already on the list.")).assertExists()
        compose.onNode(hasSetTextAction()).assertIsFocused()
    }

    @Test
    fun removingAWordHandsFocusToTheNext() {
        rules(ARSHAD, Keyword("asmr"), Keyword("prank"))
        open()
        select("Keywords")

        row("prank").performSemanticsAction(SemanticsActions.RequestFocus)
        select("prank")

        row("prank").assertDoesNotExist()
        focusedRow("asmr").assertExists()
    }

    @Test
    fun unblockingTheLastChannelClosesTheSheet() {
        rules(ARSHAD, CHANNEL)
        open()

        select("Blocked channels")
        compose.onNode(hasText("Nothing from these channels shows anywhere in your profile, and their videos won’t play. OK unblocks one.")).assertExists()
        focusedRow("Mrwhosetheboss").assertExists()
        select("Mrwhosetheboss")

        assertNull(filters.uiState.value.sheet)
        row("Blocked channels").assertIsFocused()
        row("Blocked channels").assert(hasText("None"))
    }

    /** `kp-filters` channels, unblockall: the owner's block also on Mira says so and asks
     *  where; Unblock in all profiles lifts Mira's too and focus lands on the row left (Linus, above it). */
    @Test
    fun aChannelAlsoBlockedInMiraAsksWhereAndUnblocksEverywhere() {
        rules(ARSHAD, CHANNEL, OTHER_CHANNEL)
        rules(MIRA, byOwner = listOf(CHANNEL))
        open()

        select("Blocked channels")
        compose.onNode(hasText("also in Mira", substring = true)).assertExists()
        select("Mrwhosetheboss")

        compose.onNodeWithText("Unblock Mrwhosetheboss?").assertExists()
        compose.onNodeWithText("You blocked it in Mira too.").assertExists()
        focusedRow("Unblock in all profiles").assertExists()
        select("Unblock in all profiles")

        assertEquals(FiltersSheet.BlockedChannels, filters.uiState.value.sheet)
        assertTrue(filterStore.rulesById.value.values.all { rules -> rules.rules.none { it.rule == CHANNEL } })
        focusedRow("Linus Tech Tips").assertExists()
    }

    @Test
    fun backFromUnblockWhereReturnsToItsRowStillBlocked() {
        rules(ARSHAD, CHANNEL, OTHER_CHANNEL)
        rules(MIRA, byOwner = listOf(CHANNEL))
        open()

        select("Blocked channels")
        select("Mrwhosetheboss")
        compose.pressBack()
        compose.waitForIdle()

        assertEquals(FiltersSheet.BlockedChannels, filters.uiState.value.sheet)
        focusedRow("Mrwhosetheboss").assertExists()
        assertTrue(filterStore.rulesById.value.getValue(MIRA.id).rules.any { it.rule == CHANNEL })
    }

    @Test
    fun anEmptyBlockedListOpensNothing() {
        open()

        select("Blocked videos")

        assertNull(filters.uiState.value.sheet)
    }

    @Test
    fun showShortsLeadsToInterfacesSwitch() {
        open()

        select("Show Shorts")

        rowWith("For you").assertExists()
        rowWith("Show Shorts").assertIsFocused()
    }

    @Test
    fun someoneElseSeesTheOwnersRestrictionsOutOfReach() {
        rules(
            MIRA,
            restrictions = ProfileRestrictions(restrictedMode = RestrictedMode.Strict, hideShorts = true),
            byOwner = listOf(Pattern("\\bskibidi\\w*"), Keyword("prank")),
        )
        open(MIRA)

        compose.onNode(hasText("Set by Arshad, the owner")).assertExists()
        compose.onNode(hasText("Only Arshad can change these. They apply on top of yours.")).assertExists()
        compose.onNode(hasText("Strict applies while Arshad’s restriction is on")).assertExists()
        compose.onNode(hasText("Shorts have their own switch, in Interface; Arshad keeps them off for you")).assertExists()
        compose.onNode(hasText("$PACKS_NOTE $FILTERS_REACH")).assertExists()
        compose.onNode(hasText("\\bskibidi\\w*", substring = true)).assertDoesNotExist()
        row("Show Shorts").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()

        pressDown()

        row("Show Shorts").assertIsFocused()
    }

    /** FPK-D15 (`kp-filters` fpk-mira): a pack the owner keeps on says so on Mira's own row, and
     *  shows On, dimmed and out of focus's reach, under Set by Arshad. */
    @Test
    fun aPackTheOwnerKeepsOnShowsOnSomeoneElsesFilters() {
        rules(MIRA, restrictions = ProfileRestrictions(filterPacks = FilterPacks(halalOnly = true)))
        open(MIRA)

        val halal = compose.onAllNodes(hasText("Halal only") and hasClickAction())
        assertEquals(2, halal.fetchSemanticsNodes().size)
        halal[0].assert(hasText("Arshad keeps it on for you")).assert(hasText("Off"))
        compose.onAllNodes(hasText("Harmful for kids") and hasClickAction())[0].assert(hasText(FilterPack.HarmfulForKids.hint))
        val ownersRow = halal[1]
        ownersRow.assert(hasText("On"))
        ownersRow.performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        ownersRow.assertIsNotFocused()
    }

    /** FPK-D1 (`kp-filters` fpk-zainab): a kids profile's Harmful for kids is on from the start and says so. */
    @Test
    fun aKidsProfilesHarmfulForKidsSaysItStartsOn() {
        rules(ZAINAB, restrictions = ProfileRestrictions(filterPacks = FilterPacks(harmfulForKids = true)))
        open()

        openRestrictions("Zainab")

        rowWith("Harmful for kids").assert(hasText("On for every new kids profile")).assert(hasText("On"))
    }

    /** FPK-D14 (`kp-filters` fpk-z-harmful): the sheet says how approvals meet the pack. */
    @Test
    fun aKidsProfilesHarmfulForKidsSheetSaysApprovalsPass() {
        open()
        openRestrictions("Zainab")

        rowWith("Harmful for kids").dpadSelect()
        compose.waitForIdle()

        compose.onNode(hasText("Videos you approved one by one always play.", substring = true)).assertExists()
    }

    @Test
    fun turningARestrictionPackOffWritesTheOwnersRestriction() {
        rules(ZAINAB, restrictions = ProfileRestrictions(filterPacks = FilterPacks(harmfulForKids = true)))
        open()
        openRestrictions("Zainab")
        rowWith("Harmful for kids").dpadSelect()
        compose.waitForIdle()

        select("Use this pack")

        assertFalse(filterStore.rulesById.value.getValue(ZAINAB.id).restrictions.filterPacks.harmfulForKids)
    }

    /** FPK-D12, D13 (`kp-filters` fpk-z-islamic): on a kids profile the row counts the kids
     *  defaults and the sheet says its channels count as approved. */
    @Test
    fun aKidsProfilesIslamicContentOnlyUsesTheKidsDefaults() {
        rules(ZAINAB, restrictions = ProfileRestrictions(filterPacks = FilterPacks(islamicOnly = true)))
        open()
        openRestrictions("Zainab")

        rowWith("Islamic content only").assert(hasText("On · ${IslamicOnly.channels.count { it.onForKids }} channels"))
        rowWith("Islamic content only").dpadSelect()
        compose.waitForIdle()
        compose.onNode(hasText("These count as approved for Zainab:", substring = true)).assertExists()
    }

    /** FPK-D7 on Restrictions: the suggestions are the owner's restrictions on the profile. */
    @Test
    fun suggestionsOnRestrictionsSetTheOwnersRestrictions() {
        open()
        openRestrictions("Mira")
        rowWith("Harmful for kids").dpadSelect()
        compose.waitForIdle()

        select("Use this pack")
        sheetRow("Hide Shorts").assert(hasText(HIDE_SHORTS_HINT))
        compose.onNodeWithText("Nothing changes until you choose Turn on. You can change each one any time in Mira’s restrictions.").assertExists()
        sheetRow("Hide live streams").dpadSelect()
        compose.waitForIdle()
        focusedRow("Hide live streams").assertExists()
        select("Turn on 2")

        val restrictions = filterStore.rulesById.value.getValue(MIRA.id).restrictions
        assertTrue(restrictions.filterPacks.harmfulForKids)
        assertTrue(restrictions.hideShorts)
        assertFalse(restrictions.hideLive)
        assertEquals(LengthLimits(maxMinutes = 30), restrictions.lengthLimits)
        assertEquals(ProfileSettings(), profileSettings.of(MIRA.id))
    }

    private fun openRestrictions(name: String) {
        row("Profiles").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        select(name)
        select("Restrictions")
    }

    @Test
    fun theOwnerOpensAProfilesRestrictionsAndBackReturnsToItsSheet() {
        open()
        row("Profiles").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        select("Mira")
        focusedRow("Restrictions").assertExists()

        select("Restrictions")

        compose.onNode(hasText("Mira’s restrictions")).assertExists()
        compose.onNode(hasText("Set by you, the owner. Mira can’t turn these off.", substring = true)).assertExists()
        row("Restricted Mode").assertIsFocused()
        compose.pressBack()
        focusedRow("Restrictions").assertExists()
    }

    /** Leaving Profiles with a profile's Restrictions open closes them and its sheet: the owner's
     *  own Filters sheets then show, and BACK steps back through them, not through the hidden page. */
    @Test
    fun anotherCategoryClosesAnOpenRestrictionsPage() {
        open()
        openRestrictions("Zainab")

        compose.press(AndroidKeyEvent.KEYCODE_DPAD_LEFT)
        compose.onNode(hasText("Zainab’s restrictions")).assertExists()
        compose.press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        select("Halal only")

        compose.onNode(hasText("Filters · Arshad’s profile")).assertExists()
        compose.onNode(hasText("Remove profile")).assertDoesNotExist()
        compose.pressBack()
        compose.waitForIdle()
        rowWith("Halal only").assertIsFocused()
        compose.onNode(hasText("Remove profile")).assertDoesNotExist()
    }

    /** The owner's blocks on Mira name her profile. */
    @Test
    fun aProfilesRestrictionBlockedListsNameThatProfile() {
        rules(MIRA, byOwner = listOf(CHANNEL, VIDEO))
        open()
        row("Profiles").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        select("Mira")
        select("Restrictions")

        rowWith("Blocked channels").dpadSelect()
        compose.waitForIdle()

        compose.onNode(hasText("Nothing from these channels shows anywhere in Mira’s profile, and their videos won’t play. OK unblocks one.")).assertExists()
        compose.pressBack()
        compose.waitForIdle()
        rowWith("Blocked videos").dpadSelect()
        compose.waitForIdle()
        compose.onNode(hasText("These videos don’t show anywhere in Mira’s profile, and won’t play. OK unblocks one.")).assertExists()
    }

    @Test
    fun aRestrictionsSwitchWritesTheOwnersRule() {
        open()
        row("Profiles").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        select("Mira")
        select("Restrictions")

        rowWith("Hide Shorts").dpadSelect()
        compose.waitForIdle()

        assertEquals(true, filterStore.rulesById.value[MIRA.id]?.restrictions?.hideShorts)
        assertEquals(ProfileSettings(), profileSettings.of(MIRA.id))
    }

    private fun switchState(label: String): ToggleableState? =
        rowWith(label).fetchSemanticsNode().config.getOrElseNullable(SemanticsProperties.ToggleableState) { null }

    /** A sheet's row whose label the page also shows (Hide live streams): the sheet's comes last. */
    private fun sheetRow(label: String): SemanticsNodeInteraction = compose.onAllNodes(hasText(label) and hasClickAction()).onLast()

    private companion object {
        val ARSHAD = Profile(1, "Arshad", ProfileTint.Amber, isOwner = true, lock = null)
        val MIRA = Profile(2, "Mira", ProfileTint.Rose, isOwner = false, lock = null)
        val ZAINAB = Profile(3, "Zainab", ProfileTint.Violet, isOwner = false, lock = null, isKids = true)
        val CHANNEL = BlockedChannel("https://www.youtube.com/channel/UCMiJRAwDNSNzuYeN2uWa0pA", "Mrwhosetheboss")
        val VIDEO = BlockedVideo("dQw4w9WgXcQ", "Never Gonna Give You Up", channelUrl = null)
        val OTHER_CHANNEL = BlockedChannel("https://www.youtube.com/channel/UCXuqSBlHAE6Xw-yeJA0Tunw", "Linus Tech Tips")
    }
}
