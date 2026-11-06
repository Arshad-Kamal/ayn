package com.arshad.ayn.feature.settings

import com.arshad.ayn.core.lock.FakeProfileLocks
import com.arshad.ayn.core.store.BlockedChannel
import com.arshad.ayn.core.store.BlockedVideo
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
import com.arshad.ayn.core.store.ProfileSettings
import com.arshad.ayn.core.store.ProfileTint
import com.arshad.ayn.core.store.RestrictedMode
import com.arshad.ayn.core.store.StoredRule
import java.time.DayOfWeek
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Settings › Filters over in-memory stores (`kp-filters`, KP-D12): the profile's own filters
 * written to the profile whose filters show, its own rules listed newest first and removed, the
 * owner's restrictions counted for anyone else, sheets opened from the page only.
 */
class FiltersViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val settings = FakeProfileSettingsStore(selectedId = ARSHAD)
    private val filters = FakeFilterStore()
    private val people = FakeProfileStore(listOf(OWNER, GUEST, YURI_PROFILE))
    private val locks = FakeProfileLocks().apply {
        ownerId = ARSHAD
        sessionId = ARSHAD
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun aSwitchWritesItsOwnFieldToTheProfileShown() = runTest(dispatcher) {
        val vm = newViewModel()

        vm.onSwitch(FilterSwitch.HideMembersOnly, on = true)
        advanceUntilIdle()

        assertEquals(ProfileSettings(hideMembersOnly = true), settings.of(ARSHAD))
        assertEquals(listOf(ARSHAD), settings.updatedIds)
    }

    @Test
    fun restrictedModeOpensFromThePageAndAChoiceClosesIt() = runTest(dispatcher) {
        val vm = newViewModel()

        vm.onRestrictedModePicked(RestrictedMode.Strict)
        advanceUntilIdle()
        assertEquals(RestrictedMode.Off, settings.of(ARSHAD).restrictedMode)

        vm.onOpenRestrictedMode()
        vm.onRestrictedModePicked(RestrictedMode.Strict)
        advanceUntilIdle()

        assertEquals(RestrictedMode.Strict, settings.of(ARSHAD).restrictedMode)
        assertNull(vm.uiState.value.sheet)
    }

    @Test
    fun lengthPicksKeepTheSheetOpenAndSetEachBound() = runTest(dispatcher) {
        val vm = newViewModel()

        vm.onOpenLength()
        vm.onShorterPicked(3)
        advanceUntilIdle()
        vm.onLongerPicked(60)
        advanceUntilIdle()

        assertEquals(LengthLimits(3, 60), settings.of(ARSHAD).lengthLimits)
        assertEquals(FiltersSheet.Length, vm.uiState.value.sheet)
    }

    @Test
    fun aLengthNotOfferedIsIgnored() = runTest(dispatcher) {
        val vm = newViewModel()

        vm.onOpenLength()
        vm.onShorterPicked(7)
        advanceUntilIdle()

        assertEquals(LengthLimits(), settings.of(ARSHAD).lengthLimits)
    }

    @Test
    fun aPickDropsARestoredBoundItWouldCross() = runTest(dispatcher) {
        settings.byProfile.value = mapOf(ARSHAD to ProfileSettings(lengthLimits = LengthLimits(minMinutes = 45)))
        val vm = newViewModel()

        vm.onOpenLength()
        vm.onLongerPicked(30)
        advanceUntilIdle()

        assertEquals(LengthLimits(maxMinutes = 30), settings.of(ARSHAD).lengthLimits)
    }

    @Test
    fun ownRulesListNewestFirstAndLeaveTheOwnersOut() = runTest(dispatcher) {
        filters.rulesById.value = mapOf(
            ARSHAD to ProfileRules(
                rules = listOf(
                    StoredRule(Keyword("prank"), byOwner = false, addedAtMillis = 1),
                    StoredRule(Keyword("asmr"), byOwner = true, addedAtMillis = 2),
                    StoredRule(Keyword("mukbang"), byOwner = false, addedAtMillis = 3),
                    StoredRule(CHANNEL, byOwner = false, addedAtMillis = 4),
                ),
            ),
        )

        val state = newViewModel().uiState.value

        assertEquals(listOf(Listed(Keyword("mukbang"), 3L), Listed(Keyword("prank"), 1L)), state.ownKeywords)
        assertEquals(listOf(Listed(CHANNEL, 4L)), state.ownChannels)
        assertEquals(emptyList<Listed<BlockedVideo>>(), state.ownVideos)
    }

    @Test
    fun aWordAddedLeadsTheListAndTheAddRowComesBack() = runTest(dispatcher) {
        filters.rulesById.value = mapOf(ARSHAD to ProfileRules(rules = listOf(StoredRule(Keyword("prank"), false, 0))))
        val vm = newViewModel()

        vm.onOpenKeywords()
        vm.onAddKeyword()
        vm.onKeywordChange("unbox")
        vm.onKeywordDone()
        advanceUntilIdle()

        assertEquals(listOf(Keyword("unbox"), Keyword("prank")), vm.uiState.value.ownKeywords.map { it.rule })
        assertEquals(FiltersSheet.Keywords(), vm.uiState.value.sheet)
    }

    @Test
    fun aWordAlreadyThereStaysInTheFieldWithWhy() = runTest(dispatcher) {
        filters.rulesById.value = mapOf(ARSHAD to ProfileRules(rules = listOf(StoredRule(Keyword("prank"), false, 0))))
        val vm = newViewModel()

        vm.onOpenKeywords()
        vm.onAddKeyword()
        vm.onKeywordChange("prank")
        vm.onKeywordDone()
        advanceUntilIdle()

        assertEquals(FiltersSheet.Keywords(RuleDraft("prank", RuleProblem.AlreadyThere)), vm.uiState.value.sheet)
    }

    @Test
    fun aFailedSaveSaysSoAndTypingClearsIt() = runTest(dispatcher) {
        filters.failWrites = true
        val vm = newViewModel()

        vm.onOpenKeywords()
        vm.onAddKeyword()
        vm.onKeywordChange("unbox")
        vm.onKeywordDone()
        advanceUntilIdle()
        assertEquals(RuleProblem.Failed, (vm.uiState.value.sheet as FiltersSheet.Keywords).draft?.problem)

        vm.onKeywordChange("unboxing")

        assertEquals(FiltersSheet.Keywords(RuleDraft("unboxing")), vm.uiState.value.sheet)
    }

    @Test
    fun aBlankWordJustEndsTheTyping() = runTest(dispatcher) {
        val vm = newViewModel()

        vm.onOpenKeywords()
        vm.onAddKeyword()
        vm.onKeywordChange("   ")
        vm.onKeywordDone()
        advanceUntilIdle()

        assertEquals(FiltersSheet.Keywords(), vm.uiState.value.sheet)
        assertEquals(emptyList<Listed<Keyword>>(), vm.uiState.value.ownKeywords)
    }

    @Test
    fun aWordLongerThanTheLimitIsNotTyped() = runTest(dispatcher) {
        val vm = newViewModel()

        vm.onOpenKeywords()
        vm.onAddKeyword()
        vm.onKeywordChange("a".repeat(51))

        assertEquals(FiltersSheet.Keywords(RuleDraft()), vm.uiState.value.sheet)
    }

    @Test
    fun backDropsTheWordFirstThenClosesTheSheet() = runTest(dispatcher) {
        val vm = newViewModel()

        vm.onOpenKeywords()
        vm.onAddKeyword()
        vm.onKeywordChange("unbox")
        vm.onBack()
        assertEquals(FiltersSheet.Keywords(), vm.uiState.value.sheet)
        vm.onBack()

        assertNull(vm.uiState.value.sheet)
    }

    @Test
    fun swipingTheSheetAwayClosesItWithTheWordBeingTyped() = runTest(dispatcher) {
        val vm = newViewModel()

        vm.onOpenKeywords()
        vm.onAddKeyword()
        vm.onKeywordChange("unbox")
        vm.onDismiss()

        assertNull(vm.uiState.value.sheet)
    }

    @Test
    fun removingAWordTakesItOff() = runTest(dispatcher) {
        filters.rulesById.value = mapOf(ARSHAD to ProfileRules(rules = listOf(StoredRule(Keyword("prank"), false, 0))))
        val vm = newViewModel()

        vm.onOpenKeywords()
        vm.onRemoveKeyword("prank")
        advanceUntilIdle()

        assertEquals(emptyList<Listed<Keyword>>(), vm.uiState.value.ownKeywords)
    }

    @Test
    fun blockedChannelsOpenOnlyWithOneAndCloseWithTheLast() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.onOpenBlockedChannels()
        assertNull(vm.uiState.value.sheet)

        filters.rulesById.value = mapOf(ARSHAD to ProfileRules(rules = listOf(StoredRule(CHANNEL, false, 0))))
        advanceUntilIdle()
        vm.onOpenBlockedChannels()
        assertEquals(FiltersSheet.BlockedChannels, vm.uiState.value.sheet)
        vm.onUnblockChannel(CHANNEL)
        advanceUntilIdle()

        assertEquals(emptyList<Listed<BlockedChannel>>(), vm.uiState.value.ownChannels)
        assertNull(vm.uiState.value.sheet)
    }

    /** KP.15: the owner's block also set on Mira and Yuri asks where; everywhere lifts all three. */
    @Test
    fun aBlockAlsoSetElsewhereAsksWhereAndEverywhereLiftsItInEveryProfile() = runTest(dispatcher) {
        filters.rulesById.value = blockedEverywhere()
        val vm = newViewModel()

        vm.onOpenBlockedChannels()
        assertEquals(listOf("Mira", "Yuri"), vm.uiState.value.alsoIn(CHANNEL).map { it.name })
        vm.onUnblockChannel(CHANNEL)
        advanceUntilIdle()
        assertEquals(FiltersSheet.UnblockWhere(CHANNEL), vm.uiState.value.sheet)
        assertEquals(3, filters.rulesById.value.values.count { rules -> rules.rules.any { it.rule == CHANNEL } })

        vm.onUnblockEverywhere()
        advanceUntilIdle()

        assertTrue(filters.rulesById.value.values.all { rules -> rules.rules.none { it.rule == CHANNEL } })
        assertEquals(listOf(OTHER_CHANNEL), vm.uiState.value.ownChannels.map { it.rule })
        assertEquals(FiltersSheet.BlockedChannels, vm.uiState.value.sheet)
        assertEquals(OTHER_CHANNEL.key, vm.uiState.value.listFocus)
    }

    @Test
    fun onlyInYourProfileLeavesTheOtherProfilesBlocked() = runTest(dispatcher) {
        filters.rulesById.value = blockedEverywhere()
        val vm = newViewModel()

        vm.onOpenBlockedChannels()
        vm.onUnblockChannel(CHANNEL)
        vm.onUnblockHereOnly()
        advanceUntilIdle()

        assertTrue(filters.rulesById.value.getValue(ARSHAD).rules.none { it.rule == CHANNEL })
        assertEquals(listOf(MIRA, YURI), listOf(MIRA, YURI).filter { id -> filters.rulesById.value.getValue(id).rules.any { it.rule == CHANNEL } })
        assertEquals(FiltersSheet.BlockedChannels, vm.uiState.value.sheet)
    }

    @Test
    fun backFromUnblockWhereReturnsToTheListWithItStillBlockedAndFocusOnIt() = runTest(dispatcher) {
        filters.rulesById.value = blockedEverywhere()
        val vm = newViewModel()

        vm.onOpenBlockedChannels()
        vm.onUnblockChannel(CHANNEL)
        vm.onBack()
        advanceUntilIdle()

        assertEquals(FiltersSheet.BlockedChannels, vm.uiState.value.sheet)
        assertEquals(CHANNEL.key, vm.uiState.value.listFocus)
        assertEquals(3, filters.rulesById.value.values.count { rules -> rules.rules.any { it.rule == CHANNEL } })
    }

    /** Mira's own block of the same channel isn't the owner's restriction: nothing to ask. */
    @Test
    fun aBlockOnlyTheOwnerHasUnblocksAtOnce() = runTest(dispatcher) {
        filters.rulesById.value = mapOf(
            ARSHAD to ProfileRules(rules = listOf(StoredRule(CHANNEL, false, 0), StoredRule(OTHER_CHANNEL, false, 1))),
            MIRA to ProfileRules(rules = listOf(StoredRule(CHANNEL, byOwner = false, addedAtMillis = 0))),
        )
        val vm = newViewModel()

        vm.onOpenBlockedChannels()
        vm.onUnblockChannel(CHANNEL)
        advanceUntilIdle()

        assertEquals(FiltersSheet.BlockedChannels, vm.uiState.value.sheet)
        assertEquals(listOf(OTHER_CHANNEL), vm.uiState.value.ownChannels.map { it.rule })
        assertTrue(filters.rulesById.value.getValue(MIRA).rules.any { it.rule == CHANNEL })
    }

    @Test
    fun anotherProfilesPageNeverAsks() = runTest(dispatcher) {
        settings.selected.value = MIRA
        filters.rulesById.value = mapOf(
            MIRA to ProfileRules(rules = listOf(StoredRule(CHANNEL, false, 0), StoredRule(OTHER_CHANNEL, false, 1))),
            YURI to ProfileRules(rules = listOf(StoredRule(CHANNEL, byOwner = true, addedAtMillis = 0))),
        )
        val vm = newViewModel()

        assertEquals(emptyList<Profile>(), vm.uiState.value.alsoIn(CHANNEL))
        vm.onOpenBlockedChannels()
        vm.onUnblockChannel(CHANNEL)
        advanceUntilIdle()

        assertEquals(FiltersSheet.BlockedChannels, vm.uiState.value.sheet)
        assertTrue(filters.rulesById.value.getValue(YURI).rules.any { it.rule == CHANNEL })
    }

    @Test
    fun aVideoOnTopUnblockedEverywhereHandsFocusToTheRowAfterIt() = runTest(dispatcher) {
        val older = BlockedVideo("JsBZOcqZerk", "Enigma", null)
        filters.rulesById.value = mapOf(
            ARSHAD to ProfileRules(rules = listOf(StoredRule(older, false, 0), StoredRule(VIDEO, false, 1))),
            MIRA to ProfileRules(rules = listOf(StoredRule(VIDEO, byOwner = true, addedAtMillis = 0))),
        )
        val vm = newViewModel()

        vm.onOpenBlockedVideos()
        vm.onUnblockVideo(VIDEO)
        advanceUntilIdle()
        assertEquals(FiltersSheet.UnblockWhere(VIDEO), vm.uiState.value.sheet)
        vm.onUnblockEverywhere()
        advanceUntilIdle()

        assertEquals(FiltersSheet.BlockedVideos, vm.uiState.value.sheet)
        assertEquals(older.key, vm.uiState.value.listFocus)
        assertTrue(filters.rulesById.value.values.all { rules -> rules.rules.none { it.rule == VIDEO } })
    }

    /** Matching is by kind and key: Mira's copy without the channel's name is lifted too. */
    @Test
    fun everywhereLiftsACopyStoredWithoutTheName() = runTest(dispatcher) {
        filters.rulesById.value = blockedEverywhere() + (MIRA to ProfileRules(rules = listOf(StoredRule(CHANNEL.copy(name = null), byOwner = true, addedAtMillis = 0))))
        val vm = newViewModel()

        vm.onOpenBlockedChannels()
        vm.onUnblockChannel(CHANNEL)
        assertEquals(listOf("Mira", "Yuri"), vm.uiState.value.alsoIn(CHANNEL).map { it.name })
        vm.onUnblockEverywhere()
        advanceUntilIdle()

        assertTrue(filters.rulesById.value.getValue(MIRA).rules.isEmpty())
    }

    @Test
    fun withoutTheOwnersPassEverywhereRemovesNothing() = runTest(dispatcher) {
        locks.sessionId = null
        filters.rulesById.value = blockedEverywhere()
        val vm = newViewModel()

        vm.onOpenBlockedChannels()
        vm.onUnblockChannel(CHANNEL)
        vm.onUnblockEverywhere()
        advanceUntilIdle()

        assertEquals(3, filters.rulesById.value.values.count { rules -> rules.rules.any { it.rule == CHANNEL } })
        assertEquals(FiltersSheet.BlockedChannels, vm.uiState.value.sheet)
    }

    @Test
    fun aRuleUnblockedElsewhereWhileAskingReturnsToTheList() = runTest(dispatcher) {
        filters.rulesById.value = blockedEverywhere()
        val vm = newViewModel()

        vm.onOpenBlockedChannels()
        vm.onUnblockChannel(CHANNEL)
        filters.removeOwn(ARSHAD, CHANNEL)
        advanceUntilIdle()

        assertEquals(FiltersSheet.BlockedChannels, vm.uiState.value.sheet)
        assertEquals(listOf(OTHER_CHANNEL), vm.uiState.value.ownChannels.map { it.rule })
    }

    @Test
    fun aListOpenedAgainStartsWithoutTheLastFocus() = runTest(dispatcher) {
        filters.rulesById.value = blockedEverywhere()
        val vm = newViewModel()

        vm.onOpenBlockedChannels()
        vm.onUnblockChannel(CHANNEL)
        vm.onBack()
        assertEquals(CHANNEL.key, vm.uiState.value.listFocus)
        vm.onDismiss()
        vm.onOpenBlockedChannels()

        assertNull(vm.uiState.value.listFocus)
    }

    private fun blockedEverywhere(): Map<Long, ProfileRules> = mapOf(
        ARSHAD to ProfileRules(rules = listOf(StoredRule(CHANNEL, false, 0), StoredRule(OTHER_CHANNEL, false, 1))),
        MIRA to ProfileRules(rules = listOf(StoredRule(CHANNEL, byOwner = true, addedAtMillis = 0))),
        YURI to ProfileRules(rules = listOf(StoredRule(CHANNEL, byOwner = true, addedAtMillis = 0))),
    )

    @Test
    fun unblockingAVideoKeepsTheSheetWhileOthersRemain() = runTest(dispatcher) {
        val other = BlockedVideo("JsBZOcqZerk", "Enigma", null)
        filters.rulesById.value = mapOf(ARSHAD to ProfileRules(rules = listOf(StoredRule(VIDEO, false, 0), StoredRule(other, false, 1))))
        val vm = newViewModel()

        vm.onOpenBlockedVideos()
        vm.onUnblockVideo(VIDEO)
        advanceUntilIdle()

        assertEquals(listOf(Listed(other, 1L)), vm.uiState.value.ownVideos)
        assertEquals(FiltersSheet.BlockedVideos, vm.uiState.value.sheet)
    }

    @Test
    fun anotherProfileSeesWhatTheOwnerSetAsCounts() = runTest(dispatcher) {
        settings.selected.value = MIRA
        val restrictions = ProfileRestrictions(restrictedMode = RestrictedMode.Strict, hideShorts = true)
        filters.rulesById.value = mapOf(
            MIRA to ProfileRules(
                restrictions = restrictions,
                rules = listOf(
                    StoredRule(Pattern("\\bskibidi\\w*"), byOwner = true, addedAtMillis = 0),
                    StoredRule(Keyword("prank"), byOwner = true, addedAtMillis = 1),
                    StoredRule(Keyword("asmr"), byOwner = false, addedAtMillis = 2),
                ),
            ),
        )

        val state = newViewModel().uiState.value

        assertEquals(OwnerFilters(restrictions, channels = 0, videos = 0, keywords = 1, patterns = 1), state.ownerSet)
        assertEquals(listOf(Keyword("asmr")), state.ownKeywords.map { it.rule })
    }

    @Test
    fun theOwnersStrictAndHiddenShortsHoldOverTheProfilesOwn() = runTest(dispatcher) {
        settings.selected.value = MIRA
        settings.byProfile.value = mapOf(MIRA to ProfileSettings(showShorts = true))
        filters.rulesById.value = mapOf(MIRA to ProfileRules(restrictions = ProfileRestrictions(restrictedMode = RestrictedMode.Strict, hideShorts = true)))
        val vm = newViewModel()

        assertTrue(vm.uiState.value.ownerStrict)
        assertTrue(vm.uiState.value.ownerHidesShorts)
        assertFalse(vm.uiState.value.showsShorts)

        filters.rulesById.value = mapOf(MIRA to ProfileRules())
        advanceUntilIdle()

        assertFalse(vm.uiState.value.ownerStrict)
        assertTrue(vm.uiState.value.showsShorts)
    }

    @Test
    fun aWordAlreadySavingStillSavesWhenTheSheetIsSwipedAway() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.onOpenKeywords()
        vm.onAddKeyword()
        vm.onKeywordChange("unbox")

        vm.onKeywordDone()
        vm.onDismiss()
        advanceUntilIdle()

        assertEquals(listOf(Keyword("unbox")), vm.uiState.value.ownKeywords.map { it.rule })
        assertNull(vm.uiState.value.sheet)
    }

    @Test
    fun theOwnerSetNothingWhenOnlyTimeRulesAreSet() = runTest(dispatcher) {
        settings.selected.value = MIRA
        filters.rulesById.value = mapOf(MIRA to ProfileRules(restrictions = ProfileRestrictions(dailyLimitMinutes = mapOf(DayOfWeek.MONDAY to 60))))

        assertNull(newViewModel().uiState.value.ownerSet)
    }

    @Test
    fun theOwnersOwnPageHasNoOwnerGroup() = runTest(dispatcher) {
        filters.rulesById.value = mapOf(ARSHAD to ProfileRules(rules = listOf(StoredRule(Keyword("prank"), byOwner = true, addedAtMillis = 0))))

        val state = newViewModel().uiState.value

        assertNotNull(state.rules)
        assertNull(state.ownerSet)
    }

    @Test
    fun aPackOpensFromThePageAndUseThisPackWritesItToTheProfileShown() = runTest(dispatcher) {
        val vm = newViewModel()

        vm.onUsePack(FilterPack.HalalOnly, on = true)
        advanceUntilIdle()
        assertEquals(FilterPacks(), settings.of(ARSHAD).filterPacks)

        vm.onOpenPack(FilterPack.HalalOnly)
        vm.onUsePack(FilterPack.HalalOnly, on = true)
        advanceUntilIdle()

        assertEquals(FilterPacks(halalOnly = true), settings.of(ARSHAD).filterPacks)
        assertEquals(FilterPacks(halalOnly = true), vm.uiState.value.packs)
        assertEquals(FiltersSheet.Pack(FilterPack.HalalOnly), vm.uiState.value.sheet)
    }

    @Test
    fun islamicContentOnlyGoingOnTurnsHalalOnlyOff() = runTest(dispatcher) {
        settings.byProfile.value = mapOf(ARSHAD to ProfileSettings(filterPacks = FilterPacks(halalOnly = true, stageMagic = true)))
        val vm = newViewModel()

        vm.onOpenPack(FilterPack.IslamicOnly)
        vm.onUsePack(FilterPack.IslamicOnly, on = true)
        advanceUntilIdle()

        assertEquals(FilterPacks(islamicOnly = true, stageMagic = true), settings.of(ARSHAD).filterPacks)
    }

    @Test
    fun halalOnlyGoingOnTurnsIslamicContentOnlyOff() = runTest(dispatcher) {
        settings.byProfile.value = mapOf(ARSHAD to ProfileSettings(filterPacks = FilterPacks(islamicOnly = true)))
        val vm = newViewModel()

        vm.onOpenPack(FilterPack.HalalOnly)
        vm.onUsePack(FilterPack.HalalOnly, on = true)
        advanceUntilIdle()

        assertEquals(FilterPacks(halalOnly = true), settings.of(ARSHAD).filterPacks)
    }

    @Test
    fun anOptionWritesOnlyFromItsOwnPacksSheet() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.onOpenPack(FilterPack.HalalOnly)

        vm.onPackOption(PackOption.Overstimulating, on = true)
        vm.onPackOption(PackOption.Festivals, on = true)
        advanceUntilIdle()

        assertEquals(FilterPacks(festivals = true), settings.of(ARSHAD).filterPacks)
    }

    @Test
    fun anIslamicChannelSwitchKeepsOnlyWhatDiffersFromTheAdultDefault() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.onOpenPack(FilterPack.IslamicOnly)

        vm.onIslamicChannel(YAQEEN, on = false)
        vm.onIslamicChannel(TWO_HOLY_MOSQUES, on = true)
        vm.onIslamicChannel("UCnotOnTheList0000000000", on = true)
        advanceUntilIdle()
        assertEquals(mapOf(YAQEEN to false, TWO_HOLY_MOSQUES to true), settings.of(ARSHAD).filterPacks.islamicChannels)
        val enabled = vm.uiState.value.packs!!.enabledIslamicChannels(kids = false)
        assertFalse(YAQEEN in enabled)
        assertTrue(TWO_HOLY_MOSQUES in enabled)

        vm.onIslamicChannel(YAQEEN, on = true)
        advanceUntilIdle()

        assertEquals(mapOf(TWO_HOLY_MOSQUES to true), settings.of(ARSHAD).filterPacks.islamicChannels)
    }

    @Test
    fun anIslamicChannelSwitchIsIgnoredOutsideItsSheet() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.onOpenPack(FilterPack.HalalOnly)

        vm.onIslamicChannel(TWO_HOLY_MOSQUES, on = true)
        advanceUntilIdle()

        assertEquals(FilterPacks(), settings.of(ARSHAD).filterPacks)
    }

    @Test
    fun harmfulForKidsGoingOnOffersTheSuggestionsTickedAndSetsNone() = runTest(dispatcher) {
        settings.byProfile.value = mapOf(ARSHAD to ProfileSettings(showShorts = true, lengthLimits = LengthLimits(maxMinutes = 60)))
        val vm = newViewModel()

        vm.onOpenPack(FilterPack.HarmfulForKids)
        vm.onUsePack(FilterPack.HarmfulForKids, on = true)
        advanceUntilIdle()

        val all = listOf(Suggestion.HideShorts, Suggestion.HideLive, Suggestion.HideLongerThan30)
        assertEquals(FiltersSheet.Suggestions(SuggestionOffer(all, all.toSet())), vm.uiState.value.sheet)
        assertEquals(
            ProfileSettings(showShorts = true, lengthLimits = LengthLimits(maxMinutes = 60), filterPacks = FilterPacks(harmfulForKids = true)),
            settings.of(ARSHAD),
        )
    }

    @Test
    fun onlyTheSuggestionsTheProfileLacksAreOffered() = runTest(dispatcher) {
        settings.byProfile.value = mapOf(ARSHAD to ProfileSettings(showShorts = false, hideLive = true, lengthLimits = LengthLimits(maxMinutes = 60)))
        val vm = newViewModel()

        vm.onOpenPack(FilterPack.HarmfulForKids)
        vm.onUsePack(FilterPack.HarmfulForKids, on = true)

        assertEquals(FiltersSheet.Suggestions(SuggestionOffer(listOf(Suggestion.HideLongerThan30))), vm.uiState.value.sheet)
    }

    @Test
    fun nothingIsOfferedWhenTheProfileHasEverySuggestion() = runTest(dispatcher) {
        settings.byProfile.value = mapOf(ARSHAD to ProfileSettings(showShorts = false, hideLive = true, lengthLimits = LengthLimits(maxMinutes = 30)))
        val vm = newViewModel()

        vm.onOpenPack(FilterPack.HarmfulForKids)
        vm.onUsePack(FilterPack.HarmfulForKids, on = true)

        assertEquals(FiltersSheet.Pack(FilterPack.HarmfulForKids), vm.uiState.value.sheet)
    }

    @Test
    fun harmfulForKidsAlreadyOnOrGoingOffOffersNothing() = runTest(dispatcher) {
        settings.byProfile.value = mapOf(ARSHAD to ProfileSettings(filterPacks = FilterPacks(harmfulForKids = true)))
        val vm = newViewModel()
        vm.onOpenPack(FilterPack.HarmfulForKids)

        vm.onUsePack(FilterPack.HarmfulForKids, on = true)
        assertEquals(FiltersSheet.Pack(FilterPack.HarmfulForKids), vm.uiState.value.sheet)
        vm.onUsePack(FilterPack.HarmfulForKids, on = false)
        advanceUntilIdle()

        assertEquals(FiltersSheet.Pack(FilterPack.HarmfulForKids), vm.uiState.value.sheet)
        assertEquals(FilterPacks(), settings.of(ARSHAD).filterPacks)
    }

    @Test
    fun turnOnSetsTheTickedSuggestionsAndReturnsToThePack() = runTest(dispatcher) {
        settings.byProfile.value = mapOf(ARSHAD to ProfileSettings(showShorts = true))
        val vm = newViewModel()
        vm.onOpenPack(FilterPack.HarmfulForKids)
        vm.onUsePack(FilterPack.HarmfulForKids, on = true)
        advanceUntilIdle()

        vm.onSuggestionTicked(Suggestion.HideLive, on = false)
        vm.onTurnOnSuggestions()
        advanceUntilIdle()

        val expected = ProfileSettings(showShorts = false, lengthLimits = LengthLimits(maxMinutes = 30), filterPacks = FilterPacks(harmfulForKids = true))
        assertEquals(expected, settings.of(ARSHAD))
        assertEquals(FiltersSheet.Pack(FilterPack.HarmfulForKids), vm.uiState.value.sheet)
    }

    @Test
    fun turnOnNeverLoosensASettingChangedMeanwhile() = runTest(dispatcher) {
        settings.byProfile.value = mapOf(ARSHAD to ProfileSettings(showShorts = true))
        val vm = newViewModel()
        vm.onOpenPack(FilterPack.HarmfulForKids)
        vm.onUsePack(FilterPack.HarmfulForKids, on = true)
        advanceUntilIdle()

        settings.byProfile.value = mapOf(ARSHAD to ProfileSettings(lengthLimits = LengthLimits(maxMinutes = 20)))
        advanceUntilIdle()
        vm.onTurnOnSuggestions()
        advanceUntilIdle()

        assertEquals(ProfileSettings(hideLive = true, lengthLimits = LengthLimits(maxMinutes = 20)), settings.of(ARSHAD))
    }

    @Test
    fun notNowAndBackSetNothingAndReturnToThePack() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.onOpenPack(FilterPack.HarmfulForKids)
        vm.onUsePack(FilterPack.HarmfulForKids, on = true)
        advanceUntilIdle()

        vm.onNotNow()
        assertEquals(FiltersSheet.Pack(FilterPack.HarmfulForKids), vm.uiState.value.sheet)
        vm.onUsePack(FilterPack.HarmfulForKids, on = false)
        advanceUntilIdle()
        vm.onUsePack(FilterPack.HarmfulForKids, on = true)
        advanceUntilIdle()
        vm.onBack()
        advanceUntilIdle()

        assertEquals(FiltersSheet.Pack(FilterPack.HarmfulForKids), vm.uiState.value.sheet)
        assertEquals(ProfileSettings(filterPacks = FilterPacks(harmfulForKids = true)), settings.of(ARSHAD))
    }

    @Test
    fun turningHarmfulForKidsOffAndOnOffersTheSuggestionsAgain() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.onOpenPack(FilterPack.HarmfulForKids)
        vm.onUsePack(FilterPack.HarmfulForKids, on = true)
        advanceUntilIdle()
        val offered = vm.uiState.value.sheet
        vm.onNotNow()

        vm.onUsePack(FilterPack.HarmfulForKids, on = false)
        advanceUntilIdle()
        vm.onUsePack(FilterPack.HarmfulForKids, on = true)
        advanceUntilIdle()

        assertTrue(offered is FiltersSheet.Suggestions)
        assertEquals(offered, vm.uiState.value.sheet)
    }

    @Test
    fun aSuggestionNotOfferedCannotBeTicked() = runTest(dispatcher) {
        settings.byProfile.value = mapOf(ARSHAD to ProfileSettings(hideLive = true))
        val vm = newViewModel()
        vm.onOpenPack(FilterPack.HarmfulForKids)
        vm.onUsePack(FilterPack.HarmfulForKids, on = true)

        vm.onSuggestionTicked(Suggestion.HideLive, on = true)

        val offer = (vm.uiState.value.sheet as FiltersSheet.Suggestions).offer
        assertEquals(setOf(Suggestion.HideLongerThan30), offer.ticked)
    }

    @Test
    fun anotherProfileSeesThePacksTheOwnerKeepsOn() = runTest(dispatcher) {
        settings.selected.value = MIRA
        val restrictions = ProfileRestrictions(filterPacks = FilterPacks(halalOnly = true))
        filters.rulesById.value = mapOf(MIRA to ProfileRules(restrictions = restrictions))

        val state = newViewModel().uiState.value

        assertEquals(OwnerFilters(restrictions, channels = 0, videos = 0, keywords = 0, patterns = 0), state.ownerSet)
        assertTrue(state.ownerKeeps(FilterPack.HalalOnly))
        assertFalse(state.ownerKeeps(FilterPack.IslamicOnly))
        assertEquals(FilterPacks(), state.packs)
    }

    @Test
    fun packOptionsAndChannelSwitchesWithNoPackOnAreNothingTheOwnerSet() = runTest(dispatcher) {
        settings.selected.value = MIRA
        val idle = FilterPacks(overstimulating = true, islamicChannels = mapOf(TWO_HOLY_MOSQUES to true))
        filters.rulesById.value = mapOf(MIRA to ProfileRules(restrictions = ProfileRestrictions(filterPacks = idle)))

        assertNull(newViewModel().uiState.value.ownerSet)
    }

    private fun TestScope.newViewModel(): FiltersViewModel = FiltersViewModel(settings, filters, people, locks).also { advanceUntilIdle() }

    private companion object {
        const val ARSHAD = 1L
        const val MIRA = 2L
        const val YURI = 3L
        val YURI_PROFILE = Profile(YURI, "Yuri", ProfileTint.Amber, isOwner = false, lock = null)
        val OWNER = Profile(ARSHAD, "Arshad", ProfileTint.Amber, isOwner = true, lock = null)
        val GUEST = Profile(MIRA, "Mira", ProfileTint.Rose, isOwner = false, lock = null)
        val CHANNEL = BlockedChannel("https://www.youtube.com/channel/UCMiJRAwDNSNzuYeN2uWa0pA", "Mrwhosetheboss")
        val OTHER_CHANNEL = BlockedChannel("https://www.youtube.com/channel/UCXuqSBlHAE6Xw-yeJA0Tunw", "Linus Tech Tips")
        val VIDEO = BlockedVideo("EkLmxmUfgBU", "Inside the Dark World of Poker Cheating", null)

        // IslamicOnly: Yaqeen Institute starts on for adults, the Two Holy Mosques Authority off.
        const val YAQEEN = "UC3vHW2h22WE-pNi5WJtRIjg"
        const val TWO_HOLY_MOSQUES = "UCdAnnrdcG0t8x1qDjVgaIfQ"
    }
}
