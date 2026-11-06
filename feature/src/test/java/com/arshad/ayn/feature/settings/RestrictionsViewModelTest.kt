package com.arshad.ayn.feature.settings

import com.arshad.ayn.core.lock.FakeProfileLocks
import com.arshad.ayn.core.lock.LockKind
import com.arshad.ayn.core.lock.LockSecret
import com.arshad.ayn.core.lock.OwnerPass
import com.arshad.ayn.core.lock.Press
import com.arshad.ayn.core.store.ContentRule
import com.arshad.ayn.core.store.FakeFilterStore
import com.arshad.ayn.core.store.FilterStore
import com.arshad.ayn.core.store.FakeProfileStore
import com.arshad.ayn.core.store.FilterPacks
import com.arshad.ayn.core.store.Keyword
import com.arshad.ayn.core.store.LengthLimits
import com.arshad.ayn.core.store.Pattern
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.ProfileRestrictions
import com.arshad.ayn.core.store.ProfileRules
import com.arshad.ayn.core.store.ProfileTint
import com.arshad.ayn.core.store.RestrictedMode
import com.arshad.ayn.core.store.RuleEdit
import com.arshad.ayn.core.store.StoredRule
import kotlin.time.TimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The owner's restrictions on another profile over in-memory stores (`kp-filters` restrictions,
 * patterns, patternbad; KP-D12, D7): the owner's lock first while the owner has one, writes as
 * owner restrictions, a pattern that doesn't compile kept in its field with the reason, BACK back
 * to the profile's sheet, the page closing when its profile goes.
 */
class RestrictionsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val filters = FakeFilterStore()
    private val people = FakeProfileStore(listOf(OWNER, MIRA, YURI, KID))
    private val locks = FakeProfileLocks().apply { ownerId = OWNER.id }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun anUnlockedOwnerGoesStraightToThePage() = runTest(dispatcher) {
        val vm = newViewModel()

        vm.onOpen(MIRA.id)
        advanceUntilIdle()

        assertEquals(RestrictionsScreen.Page(MIRA.id), vm.uiState.value.screen)
        assertEquals(MIRA, vm.uiState.value.target)
    }

    @Test
    fun aLockedOwnerIsAskedFirstAndTheRightSequenceOpensThePage() = runTest(dispatcher) {
        locks.locks[OWNER.id] = LockSecret.Sequence(SEQUENCE)
        val vm = newViewModel()

        vm.onOpen(MIRA.id)
        advanceUntilIdle()
        assertEquals(LockKind.Sequence, (vm.uiState.value.screen as RestrictionsScreen.OwnerGate).lockKind)
        SEQUENCE.forEach(vm::onPress)
        vm.onSubmitSequence()
        advanceUntilIdle()

        assertEquals(RestrictionsScreen.Page(MIRA.id), vm.uiState.value.screen)
    }

    @Test
    fun touchBackLeavesTheGateWithPressesEnteredButNotWhileATryIsChecked() = runTest(dispatcher) {
        locks.locks[OWNER.id] = LockSecret.Sequence(SEQUENCE)
        val vm = newViewModel()
        vm.onOpen(MIRA.id)
        advanceUntilIdle()

        locks.unlockGate = CompletableDeferred()
        SEQUENCE.forEach(vm::onPress)
        vm.onSubmitSequence()
        advanceUntilIdle()
        vm.onGateLeave()
        assertTrue(vm.uiState.value.screen is RestrictionsScreen.OwnerGate)
        locks.unlockGate?.complete(Unit)
        advanceUntilIdle()
        assertEquals(RestrictionsScreen.Page(MIRA.id), vm.uiState.value.screen)

        vm.onBack()
        vm.onOpen(MIRA.id)
        advanceUntilIdle()
        vm.onPress(Press.Up)
        vm.onGateLeave()

        assertEquals(RestrictionsScreen.Closed, vm.uiState.value.screen)
    }

    @Test
    fun theOwnersOwnProfileHasNoRestrictions() = runTest(dispatcher) {
        val vm = newViewModel()

        vm.onOpen(OWNER.id)
        advanceUntilIdle()

        assertEquals(RestrictionsScreen.Closed, vm.uiState.value.screen)
    }

    @Test
    fun switchesAndPicksWriteTheOwnersRestrictions() = runTest(dispatcher) {
        val vm = openOn(MIRA)

        vm.onHideShorts(true)
        vm.onSwitch(FilterSwitch.HideLive, true)
        vm.onOpenRestrictedMode()
        vm.onRestrictedModePicked(RestrictedMode.Strict)
        vm.onOpenLength()
        vm.onLongerPicked(30)
        advanceUntilIdle()

        val expected = ProfileRestrictions(restrictedMode = RestrictedMode.Strict, hideShorts = true, hideLive = true, lengthLimits = LengthLimits(maxMinutes = 30))
        assertEquals(expected, filters.rulesById.value.getValue(MIRA.id).restrictions)
    }

    @Test
    fun aPatternSavedLeadsTheListAsAnOwnerRule() = runTest(dispatcher) {
        val vm = openOn(MIRA)

        vm.onOpenPatterns()
        vm.onAddRule()
        vm.onRuleChange("\\bskibidi\\w*")
        vm.onRuleDone()
        advanceUntilIdle()

        assertEquals(listOf(Pattern("\\bskibidi\\w*")), vm.uiState.value.patterns.map { it.rule })
        assertTrue(filters.rulesById.value.getValue(MIRA.id).rules.single().byOwner)
        assertEquals(RestrictionsSheet.Patterns(), (vm.uiState.value.screen as RestrictionsScreen.Page).sheet)
    }

    @Test
    fun aPatternThatDoesNotCompileStaysWithTheReason() = runTest(dispatcher) {
        // The real store refuses a pattern that doesn't compile as Invalid (validRule).
        val refusing = object : FilterStore by filters {
            override suspend fun addRestriction(owner: OwnerPass, profileId: Long, rule: ContentRule): RuleEdit = RuleEdit.Invalid
        }
        val vm = RestrictionsViewModel(refusing, people, locks, TimeSource.Monotonic).also { advanceUntilIdle() }
        vm.onOpen(MIRA.id)
        advanceUntilIdle()

        vm.onOpenPatterns()
        vm.onAddRule()
        vm.onRuleChange("\\bminecraft\\s(free|hack\\b")
        vm.onRuleDone()
        advanceUntilIdle()

        val draft = ((vm.uiState.value.screen as RestrictionsScreen.Page).sheet as RestrictionsSheet.Patterns).draft
        assertEquals("\\bminecraft\\s(free|hack\\b", draft?.text)
        assertTrue(draft?.problem is RuleProblem.Invalid)
    }

    @Test
    fun ownRulesStayOutOfTheOwnersLists() = runTest(dispatcher) {
        filters.rulesById.value = mapOf(
            MIRA.id to ProfileRules(rules = listOf(StoredRule(Keyword("asmr"), byOwner = false, 0), StoredRule(Keyword("prank"), byOwner = true, 1))),
        )

        val vm = openOn(MIRA)

        assertEquals(listOf(Listed(Keyword("prank"), 1L)), vm.uiState.value.keywords)
    }

    @Test
    fun backStepsFromTheWordToTheSheetToTheProfilesSheet() = runTest(dispatcher) {
        val vm = openOn(MIRA)
        vm.onOpenKeywords()
        vm.onAddRule()

        vm.onBack()
        assertEquals(RestrictionsSheet.Keywords(), (vm.uiState.value.screen as RestrictionsScreen.Page).sheet)
        vm.onBack()
        assertEquals(RestrictionsScreen.Page(MIRA.id), vm.uiState.value.screen)
        vm.onBack()

        assertEquals(RestrictionsScreen.Closed, vm.uiState.value.screen)
    }

    @Test
    fun swipingASheetAwayClosesItAndKeepsThePage() = runTest(dispatcher) {
        val vm = openOn(MIRA)
        vm.onOpenPatterns()
        vm.onAddRule()

        vm.onDismiss()

        assertEquals(RestrictionsScreen.Page(MIRA.id), vm.uiState.value.screen)
    }

    @Test
    fun aPatternAlreadySavingStillSavesWhenTheSheetIsSwipedAway() = runTest(dispatcher) {
        val vm = openOn(MIRA)
        vm.onOpenPatterns()
        vm.onAddRule()
        vm.onRuleChange("\\bskibidi\\w*")

        vm.onRuleDone()
        vm.onDismiss()
        advanceUntilIdle()

        assertEquals(listOf(Pattern("\\bskibidi\\w*")), vm.uiState.value.patterns.map { it.rule })
        assertEquals(RestrictionsScreen.Page(MIRA.id), vm.uiState.value.screen)
    }

    @Test
    fun theUnlockedOthersLeaveOutTheTargetAndKids() = runTest(dispatcher) {
        val vm = openOn(MIRA)

        assertEquals(listOf(OWNER, YURI), vm.uiState.value.unlockedOthers)
    }

    @Test
    fun leavingClosesThePage() = runTest(dispatcher) {
        val vm = openOn(MIRA)

        vm.onLeave()

        assertEquals(RestrictionsScreen.Closed, vm.uiState.value.screen)
    }

    @Test
    fun aRemovedProfileClosesItsPage() = runTest(dispatcher) {
        val vm = openOn(MIRA)

        people.rows.value = listOf(OWNER, YURI, KID)
        advanceUntilIdle()

        assertEquals(RestrictionsScreen.Closed, vm.uiState.value.screen)
    }

    @Test
    fun packsWriteAsTheOwnersRestrictionsOneOfHalalAndIslamicAtATime() = runTest(dispatcher) {
        val vm = openOn(MIRA)

        vm.onOpenPack(FilterPack.HalalOnly)
        vm.onUsePack(FilterPack.HalalOnly, on = true)
        vm.onPackOption(PackOption.LootBoxes, on = true)
        advanceUntilIdle()
        assertEquals(FilterPacks(halalOnly = true, lootBoxes = true), vm.uiState.value.packs)
        vm.onBack()
        vm.onOpenPack(FilterPack.IslamicOnly)
        vm.onUsePack(FilterPack.IslamicOnly, on = true)
        advanceUntilIdle()

        assertEquals(FilterPacks(islamicOnly = true, lootBoxes = true), filters.rulesById.value.getValue(MIRA.id).restrictions.filterPacks)
    }

    @Test
    fun aKidsProfilesIslamicChannelSwitchesStartFromTheKidsDefaults() = runTest(dispatcher) {
        val vm = openOn(KID)
        vm.onOpenPack(FilterPack.IslamicOnly)

        vm.onIslamicChannel(YAQEEN, on = true)
        advanceUntilIdle()
        assertEquals(mapOf(YAQEEN to true), vm.uiState.value.packs?.islamicChannels)
        vm.onIslamicChannel(YAQEEN, on = false)
        advanceUntilIdle()

        assertEquals(emptyMap<String, Boolean>(), vm.uiState.value.packs?.islamicChannels)
    }

    @Test
    fun harmfulForKidsGoingOnOffersTheRestrictionsNotYetSetAndTurnOnSetsThem() = runTest(dispatcher) {
        filters.rulesById.value = mapOf(MIRA.id to ProfileRules(ProfileRestrictions(hideLive = true)))
        val vm = openOn(MIRA)
        vm.onOpenPack(FilterPack.HarmfulForKids)

        vm.onUsePack(FilterPack.HarmfulForKids, on = true)
        advanceUntilIdle()
        assertEquals(
            RestrictionsSheet.Suggestions(SuggestionOffer(listOf(Suggestion.HideShorts, Suggestion.HideLongerThan30))),
            (vm.uiState.value.screen as RestrictionsScreen.Page).sheet,
        )
        vm.onTurnOnSuggestions()
        advanceUntilIdle()

        val expected = ProfileRestrictions(
            hideShorts = true,
            hideLive = true,
            lengthLimits = LengthLimits(maxMinutes = 30),
            filterPacks = FilterPacks(harmfulForKids = true),
        )
        assertEquals(expected, filters.rulesById.value.getValue(MIRA.id).restrictions)
        assertEquals(RestrictionsSheet.Pack(FilterPack.HarmfulForKids), (vm.uiState.value.screen as RestrictionsScreen.Page).sheet)
    }

    @Test
    fun turnOnNeverLoosensALengthSetMeanwhile() = runTest(dispatcher) {
        val vm = openOn(MIRA)
        vm.onOpenPack(FilterPack.HarmfulForKids)
        vm.onUsePack(FilterPack.HarmfulForKids, on = true)
        advanceUntilIdle()

        filters.rulesById.value = mapOf(MIRA.id to ProfileRules(ProfileRestrictions(lengthLimits = LengthLimits(maxMinutes = 20))))
        advanceUntilIdle()
        vm.onTurnOnSuggestions()
        advanceUntilIdle()

        val expected = ProfileRestrictions(hideShorts = true, hideLive = true, lengthLimits = LengthLimits(maxMinutes = 20))
        assertEquals(expected, filters.rulesById.value.getValue(MIRA.id).restrictions)
    }

    @Test
    fun packEditsAreIgnoredOutsideTheirOwnSheet() = runTest(dispatcher) {
        val vm = openOn(MIRA)

        vm.onUsePack(FilterPack.HalalOnly, on = true)
        vm.onOpenPack(FilterPack.HalalOnly)
        vm.onPackOption(PackOption.Overstimulating, on = true)
        vm.onIslamicChannel(YAQEEN, on = false)
        advanceUntilIdle()

        assertEquals(ProfileRestrictions(), filters.rulesById.value[MIRA.id]?.restrictions ?: ProfileRestrictions())
    }

    @Test
    fun backFromTheSuggestionsSetsNothingAndReturnsToThePackThenThePage() = runTest(dispatcher) {
        val vm = openOn(MIRA)
        vm.onOpenPack(FilterPack.HarmfulForKids)
        vm.onUsePack(FilterPack.HarmfulForKids, on = true)
        advanceUntilIdle()

        vm.onBack()
        assertEquals(RestrictionsSheet.Pack(FilterPack.HarmfulForKids), (vm.uiState.value.screen as RestrictionsScreen.Page).sheet)
        vm.onBack()
        advanceUntilIdle()

        assertEquals(RestrictionsScreen.Page(MIRA.id), vm.uiState.value.screen)
        assertEquals(ProfileRestrictions(filterPacks = FilterPacks(harmfulForKids = true)), filters.rulesById.value.getValue(MIRA.id).restrictions)
    }

    private fun TestScope.newViewModel(): RestrictionsViewModel =
        RestrictionsViewModel(filters, people, locks, TimeSource.Monotonic).also { advanceUntilIdle() }

    private fun TestScope.openOn(profile: Profile): RestrictionsViewModel = newViewModel().also {
        it.onOpen(profile.id)
        advanceUntilIdle()
    }

    private companion object {
        val OWNER = Profile(1, "Arshad", ProfileTint.Amber, isOwner = true, lock = null)
        val MIRA = Profile(2, "Mira", ProfileTint.Rose, isOwner = false, lock = LockKind.Pin)
        val YURI = Profile(3, "Yuri", ProfileTint.Teal, isOwner = false, lock = null)
        val KID = Profile(4, "Noor", ProfileTint.Blue, isOwner = false, lock = null, isKids = true)
        val SEQUENCE = listOf(Press.Up, Press.Up, Press.Down, Press.Left, Press.Right, Press.Down)

        // IslamicOnly: Yaqeen Institute starts off for kids (lectures, FPK-D12).
        const val YAQEEN = "UC3vHW2h22WE-pNi5WJtRIjg"
    }
}
