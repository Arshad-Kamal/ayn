package com.arshad.ayn.ui.mobile.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextInput
import com.arshad.ayn.core.filter.packs.IslamicOnly
import com.arshad.ayn.core.lock.LockKind
import com.arshad.ayn.core.lock.LockSecret
import com.arshad.ayn.core.lock.OwnerPass
import com.arshad.ayn.core.store.BlockedChannel
import com.arshad.ayn.core.store.BlockedVideo
import com.arshad.ayn.core.store.ContentRule
import com.arshad.ayn.core.store.FilterPacks
import com.arshad.ayn.core.store.FilterStore
import com.arshad.ayn.core.store.Keyword
import com.arshad.ayn.core.store.LengthLimits
import com.arshad.ayn.core.store.Pattern
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.ProfileRestrictions
import com.arshad.ayn.core.store.ProfileRules
import com.arshad.ayn.core.store.ProfileSettings
import com.arshad.ayn.core.store.ProfileTint
import com.arshad.ayn.core.store.RestrictedMode
import com.arshad.ayn.core.store.RuleEdit
import com.arshad.ayn.core.store.StoredRule
import com.arshad.ayn.feature.settings.FilterPack
import com.arshad.ayn.feature.settings.HIDE_SHORTS_HINT
import com.arshad.ayn.feature.settings.PACKS_NOTE
import com.arshad.ayn.feature.settings.RestrictionsViewModel
import com.arshad.ayn.feature.settings.hint
import com.arshad.ayn.ui.mobile.installOfflineImageLoader
import com.arshad.ayn.ui.mobile.library.TestStores
import com.arshad.ayn.ui.mobile.library.TestTime
import com.arshad.ayn.ui.mobile.library.inTestZone
import com.arshad.ayn.ui.mobile.shell.MobileShell
import com.arshad.ayn.ui.mobile.shell.RestrictionsKey
import com.arshad.ayn.ui.mobile.shell.SettingsKey
import com.arshad.ayn.ui.mobile.shell.SettingsPage
import com.arshad.ayn.ui.mobile.shell.SettingsPageKey
import com.arshad.ayn.ui.mobile.shell.ShellNav
import com.arshad.ayn.ui.mobile.shell.ShellOpening
import com.arshad.ayn.ui.mobile.shellPages
import com.arshad.ayn.ui.mobile.theme.AynTheme
import java.time.LocalDateTime
import kotlin.time.TimeSource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Filters and the owner's restrictions on the phone (`m6-settings` 17–24, 32) through the shell's
 * real page wiring on the stores' fakes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class FiltersPagesTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val stores = TestStores()
    private lateinit var nav: ShellNav

    @Before
    fun setUp() {
        installOfflineImageLoader(compose.activity)
        stores.people.rows.value = listOf(ARSHAD, MIRA, YURI)
        stores.locks.ownerId = ARSHAD.id
    }

    private fun open(me: Profile = ARSHAD, restrictionsOf: Profile? = null) {
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
            nav.open(SettingsPageKey(SettingsPage.Filters))
            restrictionsOf?.let { nav.open(RestrictionsKey(it.id)) }
        }
        compose.waitForIdle()
    }

    private fun click(label: String) {
        compose.onNode(hasText(label) and hasClickAction()).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun millisOn(day: Int, month: Int = 10): Long = LocalDateTime.of(2026, month, day, 12, 0).inTestZone().toEpochMilliseconds()

    @Test
    fun filtersShowsTheProfilesOwnValuesAndShowShortsOpensInterface() {
        stores.settings.byProfile.value = mapOf(ARSHAD.id to ProfileSettings(hideMembersOnly = true, lengthLimits = LengthLimits(minMinutes = 1)))
        stores.filters.rulesById.value = mapOf(
            ARSHAD.id to ProfileRules(rules = listOf(StoredRule(Keyword("prank"), false, 1), StoredRule(Keyword("reaction"), false, 2))),
        )
        open()

        compose.onNodeWithText("Arshad’s profile", substring = true).assertExists()
        compose.onNode(hasText("Hide members-only videos") and isToggleable()).assertIsOn()
        compose.onNodeWithText("Under 1 min hidden").assertExists()
        compose.onNodeWithText("2 words").assertExists()
        compose.onNode(hasText("Blocked channels") and hasClickAction()).assertDoesNotExist()
        compose.onNode(hasScrollToKeyAction()).performScrollToKey("foot")
        compose.onNodeWithText("To set filters another profile can’t turn off", substring = true).assertExists()

        click("Show Shorts")

        compose.onNodeWithText("For you").assertExists()
    }

    @Test
    fun aRestrictedModePickSavesAndClosesTheSheet() {
        open()
        click("Restricted Mode")

        compose.onNode(hasText("Strict") and isSelectable()).performClick()
        compose.waitForIdle()

        assertEquals(RestrictedMode.Strict, stores.settings.of(ARSHAD.id).restrictedMode)
        compose.onNodeWithText("Hides what YouTube’s filter flags", substring = true).assertDoesNotExist()
    }

    @Test
    fun aLengthPickSavesAndTheSheetStays() {
        open()
        click("Video length")

        compose.onNode(hasText("3 min") and isSelectable()).performClick()
        compose.waitForIdle()

        assertEquals(LengthLimits(minMinutes = 3), stores.settings.of(ARSHAD.id).lengthLimits)
        compose.onNode(hasText("3 min") and isSelectable()).assertIsSelected()
        compose.onNode(hasText("2 hours") and isSelectable()).assertExists()
    }

    @Test
    fun aWordIsAddedFromTheFieldAndAWordAlreadyThereSaysSo() {
        stores.filters.rulesById.value = mapOf(ARSHAD.id to ProfileRules(rules = listOf(StoredRule(Keyword("prank"), false, millisOn(1)))))
        open()
        click("Keywords")
        click("Add a word")

        compose.onNode(hasSetTextAction()).performTextInput("prank")
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.waitForIdle()
        compose.onNodeWithText("“prank” is already on the list.").assertExists()

        compose.onNode(hasSetTextAction()).performTextInput("s")
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.waitForIdle()

        assertEquals(listOf(Keyword("prank"), Keyword("pranks")), stores.filters.rulesById.value.getValue(ARSHAD.id).rules.map { it.rule })
        compose.onNode(hasText("Add a word") and hasClickAction()).assertExists()
    }

    @Test
    fun eachWordSaysWhenItWasAdded() {
        stores.filters.rulesById.value = mapOf(
            ARSHAD.id to ProfileRules(rules = listOf(StoredRule(Keyword("reaction"), false, millisOn(2, month = 9)), StoredRule(Keyword("prank"), false, millisOn(1)))),
        )
        open()
        click("Keywords")

        compose.onNodeWithText("Added 2 days ago").assertExists()
        compose.onNodeWithText("Added September 2").assertExists()

        compose.onNode(hasContentDescription("Remove prank")).performClick()
        compose.waitForIdle()

        assertEquals(listOf(Keyword("reaction")), stores.filters.rulesById.value.getValue(ARSHAD.id).rules.map { it.rule })
    }

    @Test
    fun unblockingTheLastChannelClosesTheSheet() {
        stores.filters.rulesById.value = mapOf(
            ARSHAD.id to ProfileRules(rules = listOf(StoredRule(BlockedChannel("https://www.youtube.com/channel/UCfern", "fern"), false, millisOn(3)))),
        )
        open()
        click("Blocked channels")
        compose.onNodeWithText("Blocked October 3").assertExists()

        compose.onNode(hasContentDescription("Unblock fern")).performClick()
        compose.waitForIdle()

        assertTrue(stores.filters.rulesById.value.getValue(ARSHAD.id).rules.isEmpty())
        compose.onNodeWithText("Nothing from these channels", substring = true).assertDoesNotExist()
        compose.onNode(hasText("Blocked channels") and hasClickAction()).assertDoesNotExist()
    }

    /** KP.15 (`m6-settings` 21a, 21b): a video the owner also blocked in Mira and Yuri says so and
     *  asks where; Only in your profile leaves theirs. */
    @Test
    fun aVideoAlsoBlockedElsewhereAsksWhereAndOnlyHereKeepsTheOthers() {
        stores.locks.sessionId = ARSHAD.id
        val video = BlockedVideo("q7iVFMM0XQA", "Max Verstappen's Pole Lap", null)
        stores.filters.rulesById.value = mapOf(
            ARSHAD.id to ProfileRules(rules = listOf(StoredRule(video, false, millisOn(3)))),
            MIRA.id to ProfileRules(rules = listOf(StoredRule(video, byOwner = true, addedAtMillis = millisOn(3)))),
            YURI.id to ProfileRules(rules = listOf(StoredRule(video, byOwner = true, addedAtMillis = millisOn(3)))),
        )
        open()
        click("Blocked videos")
        compose.onNodeWithText("Blocked October 3 · also in Mira and Yuri").assertExists()

        compose.onNode(hasContentDescription("Unblock Max Verstappen's Pole Lap")).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Unblock Max Verstappen's Pole Lap?").assertExists()
        compose.onNodeWithText("Mira and Yuri keep it blocked").assertExists()
        click("Only in your profile")

        assertTrue(stores.filters.rulesById.value.getValue(ARSHAD.id).rules.isEmpty())
        assertEquals(1, stores.filters.rulesById.value.getValue(MIRA.id).rules.size)
        assertEquals(1, stores.filters.rulesById.value.getValue(YURI.id).rules.size)
    }

    @Test
    fun unblockingEverywhereLiftsTheOtherProfilesBlocks() {
        stores.locks.sessionId = ARSHAD.id
        val channel = BlockedChannel("https://www.youtube.com/channel/UCfern", "fern")
        stores.filters.rulesById.value = mapOf(
            ARSHAD.id to ProfileRules(rules = listOf(StoredRule(channel, false, millisOn(3)))),
            MIRA.id to ProfileRules(rules = listOf(StoredRule(channel, byOwner = true, addedAtMillis = millisOn(3)))),
        )
        open()
        click("Blocked channels")
        compose.onNode(hasContentDescription("Unblock fern")).performClick()
        compose.waitForIdle()
        click("Unblock in all profiles")

        assertTrue(stores.filters.rulesById.value.values.all { it.rules.isEmpty() })
    }

    @Test
    fun unblockingAVideoTakesItOff() {
        stores.filters.rulesById.value = mapOf(
            ARSHAD.id to ProfileRules(
                rules = listOf(
                    StoredRule(BlockedVideo("EkLmxmUfgBU", "Inside the Dark World of Poker Cheating", null), false, millisOn(2)),
                    StoredRule(BlockedVideo("q7iVFMM0XQA", "Max Verstappen's Pole Lap", null), false, millisOn(3)),
                ),
            ),
        )
        open()
        click("Blocked videos")

        compose.onNode(hasContentDescription("Unblock Max Verstappen's Pole Lap")).performClick()
        compose.waitForIdle()

        assertEquals(listOf("EkLmxmUfgBU"), stores.filters.rulesById.value.getValue(ARSHAD.id).rules.map { (it.rule as BlockedVideo).videoId })
        compose.onNodeWithText("Blocked October 2").assertExists()
    }

    @Test
    fun anotherProfileSeesWhatTheOwnerSetAsCountsOutOfReach() {
        stores.filters.rulesById.value = mapOf(
            MIRA.id to ProfileRules(
                restrictions = ProfileRestrictions(restrictedMode = RestrictedMode.Strict, hideShorts = true),
                rules = listOf(StoredRule(Keyword("asmr"), true, 1), StoredRule(Pattern("\\bgiveaway\\b"), true, 2)),
            ),
        )
        open(me = MIRA)

        compose.onNodeWithText("Strict applies while Arshad’s restriction is on").assertExists()
        compose.onNode(hasScrollToKeyAction()).performScrollToKey("shorts")
        compose.onNodeWithText("Shorts have their own switch, in Interface; Arshad keeps them off for you").assertExists()
        compose.onNode(hasScrollToKeyAction()).performScrollToKey("owner")
        compose.onNodeWithText("Set by Arshad, the owner").assertExists()
        compose.onNodeWithText("Only Arshad can change these. They apply on top of yours.").assertExists()
        compose.onNodeWithText("Owner only").assertExists()
        compose.onNode(hasText("Hide Shorts") and isToggleable()).assertIsNotEnabled()
        compose.onNode(hasScrollToKeyAction()).performScrollToKey("foot")
        compose.onNodeWithText("Filters apply to Home", substring = true).assertExists()
        compose.onNodeWithText("To set filters another profile can’t turn off", substring = true).assertDoesNotExist()
    }

    @Test
    fun theOwnersLockOpensMirasRestrictionsAndTheyWriteAsTheOwners() {
        stores.locks.locks[ARSHAD.id] = LockSecret.Pin("1234")
        open(restrictionsOf = MIRA)
        compose.onNodeWithText("Mira’s restrictions").assertExists()
        compose.onNodeWithText("Enter Arshad’s PIN.").assertExists()

        "1234".forEach { compose.onNode(hasText(it.toString()) and hasClickAction()).performClick() }
        compose.waitForIdle()

        compose.onNodeWithText("Set by you, the owner. Mira can’t turn these off.", substring = true).assertExists()
        compose.onNodeWithText("Yuri has no lock, so Mira could switch to it.", substring = true).assertExists()
        compose.onNode(hasText("Hide Shorts") and isToggleable()).performClick()
        compose.waitForIdle()
        assertTrue(stores.filters.rulesById.value.getValue(MIRA.id).restrictions.hideShorts)

        compose.onNode(hasContentDescription("Back")).performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Mira’s restrictions").assertDoesNotExist()
        compose.onNodeWithText("Video length").assertExists()
    }

    @Test
    fun theOwnerRemovesAWordFromMirasRestrictions() {
        stores.filters.rulesById.value = mapOf(
            MIRA.id to ProfileRules(rules = listOf(StoredRule(Keyword("asmr"), true, millisOn(3)), StoredRule(Keyword("prank"), false, millisOn(3)))),
        )
        open(restrictionsOf = MIRA)
        click("Keywords")
        compose.onNodeWithText("Mira’s restrictions · owner only").assertExists()

        compose.onNode(hasContentDescription("Remove asmr")).performClick()
        compose.waitForIdle()

        assertEquals(listOf(StoredRule(Keyword("prank"), false, millisOn(3))), stores.filters.rulesById.value.getValue(MIRA.id).rules)
    }

    @Test
    fun anOpenPageShownAgainStaysOpen() {
        val restrictions = RestrictionsViewModel(stores.filters, stores.people, stores.locks, TimeSource.Monotonic)
        var shown by mutableStateOf(true)
        var gone = false
        compose.setContent { AynTheme { if (shown) RestrictionsPage(MIRA.id, restrictions, TestTime, onGone = { gone = true }) } }
        compose.waitForIdle()
        compose.onNodeWithText("Set by you, the owner.", substring = true).assertExists()

        shown = false
        compose.waitForIdle()
        shown = true
        compose.waitForIdle()

        compose.onNodeWithText("Set by you, the owner.", substring = true).assertExists()
        assertFalse(gone)
    }

    @Test
    fun theOwnersOwnProfileHasNoRestrictionsAndBackLeaves() {
        open(restrictionsOf = ARSHAD)
        compose.onNodeWithText("Arshad’s restrictions").assertExists()
        compose.onNodeWithText("Set by you, the owner.", substring = true).assertDoesNotExist()

        compose.onNode(hasContentDescription("Back")).performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Video length").assertExists()
    }

    @Test
    fun backOnTheOwnersLockLeavesWithTapsEntered() {
        stores.locks.locks[ARSHAD.id] = LockSecret.Pin("1234")
        open(restrictionsOf = MIRA)
        compose.onNode(hasText("1") and hasClickAction()).performClick()

        compose.onNode(hasContentDescription("Back")).performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Enter Arshad’s PIN.").assertDoesNotExist()
        compose.onNodeWithText("Video length").assertExists()
    }

    @Test
    fun aPatternThatIsNotValidStaysInTheFieldWithWhy() {
        val refusing = object : FilterStore by stores.filters {
            override suspend fun addRestriction(owner: OwnerPass, profileId: Long, rule: ContentRule): RuleEdit = RuleEdit.Invalid
        }
        val restrictions = RestrictionsViewModel(refusing, stores.people, stores.locks, TimeSource.Monotonic)
        compose.setContent { AynTheme { RestrictionsPage(MIRA.id, restrictions, TestTime, onGone = {}) } }
        compose.waitForIdle()
        click("Patterns")
        click("Add a pattern")

        compose.onNode(hasSetTextAction()).performTextInput("(free|win) (iphone")
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.waitForIdle()

        compose.onNodeWithText("This pattern isn’t valid", substring = true).assertExists()
        compose.onNode(hasSetTextAction() and hasText("(free|win) (iphone")).assertExists()
    }

    private fun top(label: String): Float = compose.onNode(hasText(label) and hasClickAction()).fetchSemanticsNode().boundsInRoot.top

    private fun hasRole(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    private fun switch(label: String) = compose.onNode(hasText(label) and isToggleable() and hasRole(Role.Switch))

    /** A suggestion's row (its tick), apart from the page's switch of the same name behind the sheet. */
    private fun tick(label: String) = compose.onNode(hasText(label) and hasRole(Role.Checkbox))

    private fun SemanticsNodeInteraction.toggle() {
        performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun flip(label: String) = switch(label).toggle()

    private fun flipTick(label: String) = tick(label).toggle()

    /** Brings the page's Filter packs rows into view (the lazy page composes only what shows). */
    private fun scrollToPacks() {
        compose.onNode(hasScrollToKeyAction()).performScrollToKey("packs")
        compose.waitForIdle()
    }

    /** `m6-settings` P1: the Filter packs rows, each with its value, sit before Shorts; the foot
     *  says where packs come from. */
    @Test
    fun filterPacksShowTheirValuesBeforeShorts() {
        stores.settings.byProfile.value = mapOf(ARSHAD.id to ProfileSettings(filterPacks = FilterPacks(halalOnly = true)))
        open()

        scrollToPacks()
        val tops = listOf("Harmful for kids", "Halal only", "Islamic content only", "Show Shorts").map(::top)
        assertEquals(tops.sorted(), tops)
        compose.onNode(hasText("Halal only") and hasClickAction()).assert(hasText("On"))
        compose.onNode(hasText("Harmful for kids") and hasClickAction()).assert(hasText("Off")).assert(hasText(FilterPack.HarmfulForKids.hint))
        compose.onNode(hasScrollToKeyAction()).performScrollToKey("foot")
        compose.onNodeWithText("$PACKS_NOTE Filters apply to Home", substring = true).assertExists()
    }

    /** FPK-D7 (`m6-settings` P2, P3): Harmful for kids going on offers what the profile lacks, all
     *  ticked; Turn on sets only the ticked ones and the pack's sheet comes back. */
    @Test
    fun harmfulForKidsOffersSuggestionsAndTurnOnSetsTheTickedOnes() {
        stores.settings.byProfile.value = mapOf(ARSHAD.id to ProfileSettings(showShorts = true))
        open()
        click("Harmful for kids")
        compose.onNodeWithText("Always hidden · 18 channels", substring = true).assertExists()

        flip("Use this pack")
        compose.onNodeWithText("Also turn these on?").assertExists()
        tick("Hide Shorts").assert(hasText("Turns off Show Shorts in Interface"))
        compose.onNode(hasText("Turn on 3") and hasClickAction()).assertExists()
        flipTick("Hide live streams")
        click("Turn on 2")

        val settings = stores.settings.of(ARSHAD.id)
        assertTrue(settings.filterPacks.harmfulForKids)
        assertFalse(settings.showShorts)
        assertFalse(settings.hideLive)
        assertEquals(LengthLimits(maxMinutes = 30), settings.lengthLimits)
        compose.onNodeWithText("Also turn these on?").assertDoesNotExist()
        switch("Use this pack").assertIsOn()
    }

    @Test
    fun notNowSetsNothingAndReturnsToThePack() {
        open()
        click("Harmful for kids")
        flip("Use this pack")

        click("Not now")

        assertEquals(ProfileSettings(filterPacks = FilterPacks(harmfulForKids = true)), stores.settings.of(ARSHAD.id))
        switch("Use this pack").assertIsOn()
    }

    @Test
    fun turnOnWithNothingTickedIsOff() {
        open()
        click("Harmful for kids")
        flip("Use this pack")

        listOf("Hide live streams", "Hide videos longer than 30 min").forEach(::flipTick)

        compose.onNode(hasText("Turn on") and hasClickAction()).assertIsNotEnabled()
    }

    /** FPK-D2 (`m6-settings` P5): Islamic content only says it turns Halal only off, and does. */
    @Test
    fun islamicContentOnlyTurnsHalalOnlyOff() {
        stores.settings.byProfile.value = mapOf(ARSHAD.id to ProfileSettings(filterPacks = FilterPacks(halalOnly = true)))
        open()
        click("Islamic content only")
        switch("Use this pack").assert(hasText("Turns Halal only off: one of the two at a time"))

        flip("Use this pack")

        assertEquals(FilterPacks(islamicOnly = true), stores.settings.of(ARSHAD.id).filterPacks)
    }

    /** FPK-D12: a listed channel's switch starts at the adult default; switched off, it is kept. */
    @Test
    fun anIslamicChannelSwitchedOffIsKeptForTheProfile() {
        open()
        click("Islamic content only")
        val alafasy = IslamicOnly.channels.first { it.name == "Alafasy" }
        switch("Alafasy").assertIsOn()

        flip("Alafasy")

        assertEquals(mapOf(alafasy.id to false), stores.settings.of(ARSHAD.id).filterPacks.islamicChannels)
        switch("Alafasy").assertIsOff()
    }

    @Test
    fun islamicContentOnlysRowCountsItsChannels() {
        stores.settings.byProfile.value = mapOf(ARSHAD.id to ProfileSettings(filterPacks = FilterPacks(islamicOnly = true)))
        open()

        compose.onNode(hasText("Islamic content only") and hasClickAction())
            .assert(hasText("On · ${IslamicOnly.channels.count { it.onForAdults }} channels"))
    }

    /** FPK-D8 (`m6-settings` P4): a contested item is a switch of its own, off. */
    @Test
    fun aPackOptionSwitches() {
        open()
        click("Halal only")
        switch("Stage magic").assertIsOff()

        flip("Stage magic")

        assertTrue(stores.settings.of(ARSHAD.id).filterPacks.stageMagic)
        assertFalse(stores.settings.of(ARSHAD.id).filterPacks.halalOnly)
    }

    /** FPK-D15 (`m6-settings` P9, P10): a pack the owner keeps on says so on Mira's own row, and
     *  shows as text under Set by Arshad. */
    @Test
    fun aPackTheOwnerKeepsOnShowsOnSomeoneElsesFilters() {
        stores.filters.rulesById.value = mapOf(MIRA.id to ProfileRules(restrictions = ProfileRestrictions(filterPacks = FilterPacks(halalOnly = true))))
        open(me = MIRA)

        compose.onNode(hasText("Halal only") and hasClickAction()).assert(hasText("Arshad keeps it on for you")).assert(hasText("Off"))
        compose.onNode(hasScrollToKeyAction()).performScrollToKey("owner")
        compose.onNodeWithText("Set by Arshad, the owner").assertExists()
        compose.onNode(hasText("Halal only") and !hasClickAction()).assert(hasText("On"))
        compose.onNodeWithText(FilterPack.HalalOnly.hint).assertExists()
    }

    /** FPK-D1 (`m6-settings` P6): a kids profile's restrictions say Harmful for kids starts on for
     *  every new kids profile, and count the kids Islamic defaults. */
    @Test
    fun aKidsProfilesRestrictionsShowItsPacks() {
        stores.people.rows.value = listOf(ARSHAD, ZAINAB)
        stores.filters.rulesById.value = mapOf(
            ZAINAB.id to ProfileRules(restrictions = ProfileRestrictions(filterPacks = FilterPacks(harmfulForKids = true, islamicOnly = true)), kids = true),
        )
        open(restrictionsOf = ZAINAB)
        scrollToPacks()

        compose.onNode(hasText("Harmful for kids") and hasClickAction()).assert(hasText("On for every new kids profile")).assert(hasText("On"))
        compose.onNode(hasText("Islamic content only") and hasClickAction())
            .assert(hasText("On · ${IslamicOnly.channels.count { it.onForKids }} channels"))
        compose.onNode(hasScrollToKeyAction()).performScrollToKey("foot")
        compose.onNodeWithText(PACKS_NOTE).assertExists()
    }

    /** FPK-D13, D14 (`m6-settings` P7, P8): each pack's sheet says how approvals meet it. */
    @Test
    fun aKidsProfilesPackSheetsSayHowApprovalsMeetThem() {
        stores.people.rows.value = listOf(ARSHAD, ZAINAB)
        stores.filters.rulesById.value = mapOf(ZAINAB.id to ProfileRules(kids = true))
        open(restrictionsOf = ZAINAB)
        scrollToPacks()

        click("Harmful for kids")
        compose.onNodeWithText("Zainab’s restrictions · owner only").assertExists()
        compose.onNodeWithText("Videos you approved one by one always play.", substring = true).assertExists()
        flip("Use this pack")
        click("Not now")
        assertTrue(stores.filters.rulesById.value.getValue(ZAINAB.id).restrictions.filterPacks.harmfulForKids)
        switch("Use this pack").assertIsOn()
    }

    @Test
    fun aKidsProfilesIslamicSheetCountsItsChannelsAsApproved() {
        stores.people.rows.value = listOf(ARSHAD, ZAINAB)
        stores.filters.rulesById.value = mapOf(ZAINAB.id to ProfileRules(kids = true))
        open(restrictionsOf = ZAINAB)
        scrollToPacks()

        click("Islamic content only")

        compose.onNodeWithText("These count as approved for Zainab:", substring = true).assertExists()
        compose.onNodeWithText("Set for children: lectures start off.", substring = true).assertExists()
        assertKidsDefault("Muhammad Al Muqit")
    }

    /** Scrolls the Islamic sheet's channel list to [name] and checks its switch shows its default
     *  for a kids profile (a channel on for adults and off for children tells the two apart). */
    private fun assertKidsDefault(name: String) {
        val channel = IslamicOnly.channels.first { it.name == name }
        assertTrue(channel.onForAdults && !channel.onForKids)
        // The sheet's window comes after the page's, so its list is the last one.
        compose.onAllNodes(hasScrollToKeyAction()).onLast().performScrollToKey(channel.id)
        compose.waitForIdle()
        switch(name).assertIsOff()
    }

    /** FPK-D7 on Restrictions: the suggestions are the owner's restrictions on the profile. */
    @Test
    fun suggestionsOnRestrictionsSetTheOwnersRestrictions() {
        open(restrictionsOf = MIRA)
        scrollToPacks()
        click("Harmful for kids")
        flip("Use this pack")

        tick("Hide Shorts").assert(hasText(HIDE_SHORTS_HINT))
        compose.onNodeWithText("Nothing changes until you choose Turn on. You can change each one any time in Mira’s restrictions.").assertExists()
        flipTick("Hide live streams")
        click("Turn on 2")

        val restrictions = stores.filters.rulesById.value.getValue(MIRA.id).restrictions
        assertTrue(restrictions.filterPacks.harmfulForKids)
        assertTrue(restrictions.hideShorts)
        assertFalse(restrictions.hideLive)
        assertEquals(LengthLimits(maxMinutes = 30), restrictions.lengthLimits)
        assertEquals(ProfileSettings(), stores.settings.of(MIRA.id))
    }

    private companion object {
        val ARSHAD = Profile(1, "Arshad", ProfileTint.Amber, isOwner = true, lock = LockKind.Pin)
        val MIRA = Profile(2, "Mira", ProfileTint.Rose, isOwner = false, lock = LockKind.Pin)
        val YURI = Profile(3, "Yuri", ProfileTint.Teal, isOwner = false, lock = null)
        val ZAINAB = Profile(4, "Zainab", ProfileTint.Teal, isOwner = false, lock = null, isKids = true)
    }
}
