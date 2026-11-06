package com.arshad.ayn.feature.settings

import com.arshad.ayn.core.access.SegmentCategory
import com.arshad.ayn.core.store.ChatPlacement
import com.arshad.ayn.core.store.DeviceSettings
import com.arshad.ayn.core.store.FakeDeviceSettingsStore
import com.arshad.ayn.core.store.FakeProfileSettingsStore
import com.arshad.ayn.core.store.FakeProfileStore
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.ProfileSettings
import com.arshad.ayn.core.store.ProfileSettingsState
import com.arshad.ayn.core.store.ProfileTint
import com.arshad.ayn.core.store.SegmentAction
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
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Settings' preference pages over in-memory stores: the profile's and the TV's values, switches
 * and choices written to the profile whose settings show (M7-D9), the TV's changed by the owner
 * only (Q17), sheets opened only from the page (or from the sheet they lead on from) and closed
 * by a choice.
 */
class PreferencesViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val profiles = FakeProfileSettingsStore(selectedId = ARSHAD)
    private val device = FakeDeviceSettingsStore()
    private val people = FakeProfileStore(listOf(OWNER, GUEST))

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun showsTheSessionProfilesSettingsAndTheTvs() = runTest(dispatcher) {
        profiles.byProfile.value = mapOf(ARSHAD to ProfileSettings(speedPercent = 150))
        device.current.value = DeviceSettings(maxVideoHeight = 1080)

        val state = newViewModel().uiState.value

        assertEquals(ProfileSettingsState(ARSHAD, ProfileSettings(speedPercent = 150)), state.profile)
        assertEquals(DeviceSettings(maxVideoHeight = 1080), state.device)
    }

    @Test
    fun nothingShowsUntilEachStoreLoads() = runTest(dispatcher) {
        profiles.selected.value = null
        device.current.value = null

        val state = newViewModel().uiState.value

        assertNull(state.profile)
        assertNull(state.device)
    }

    @Test
    fun aProfileSwitchWritesItsOwnFieldOnly() = runTest(dispatcher) {
        val vm = newViewModel()

        vm.onSwitch(ProfileSwitch.DeArrowThumbnails, on = false)
        advanceUntilIdle()

        assertEquals(ProfileSettings(dearrowThumbnails = false), profiles.of(ARSHAD))
        assertEquals(ProfileSettings(dearrowThumbnails = false), vm.uiState.value.profile?.settings)
    }

    @Test
    fun eachProfileSwitchFlipsItsOwnFieldFromTheDefaults() = runTest(dispatcher) {
        val flipped = mapOf(
            ProfileSwitch.Resume to (false to ProfileSettings(resume = false)),
            ProfileSwitch.SponsorBlock to (false to ProfileSettings(sponsorBlock = false)),
            ProfileSwitch.SkipNote to (false to ProfileSettings(skipNote = false)),
            ProfileSwitch.DeArrowTitles to (false to ProfileSettings(dearrowTitles = false)),
            ProfileSwitch.DeArrowThumbnails to (false to ProfileSettings(dearrowThumbnails = false)),
            ProfileSwitch.Dislikes to (false to ProfileSettings(dislikes = false)),
            ProfileSwitch.ShowShorts to (true to ProfileSettings(showShorts = true)),
            ProfileSwitch.ForYou to (false to ProfileSettings(forYou = false)),
        )
        assertEquals(ProfileSwitch.entries.toSet(), flipped.keys)
        val vm = newViewModel()

        flipped.forEach { (switch, change) ->
            val (on, expected) = change
            profiles.byProfile.value = emptyMap()
            advanceUntilIdle()
            vm.onSwitch(switch, on)
            advanceUntilIdle()
            assertEquals("$switch", expected, profiles.of(ARSHAD))
        }
    }

    @Test
    fun eachDeviceSwitchFlipsItsOwnFieldFromTheDefaults() = runTest(dispatcher) {
        val flipped = mapOf(
            DeviceSwitch.FrameRateMatching to DeviceSettings(frameRateMatching = false),
            DeviceSwitch.ClockInPlayer to DeviceSettings(clockInPlayer = false),
            DeviceSwitch.HeroOnHome to DeviceSettings(heroOnHome = false),
            DeviceSwitch.BackgroundAudio to DeviceSettings(backgroundAudio = false),
        )
        assertEquals(DeviceSwitch.entries.toSet(), flipped.keys)
        val vm = newViewModel()

        flipped.forEach { (switch, expected) ->
            device.current.value = DeviceSettings()
            advanceUntilIdle()
            vm.onSwitch(switch, on = false)
            advanceUntilIdle()
            assertEquals("$switch", expected, device.current.value)
            assertEquals("$switch", expected, vm.uiState.value.device)
        }
    }

    @Test
    fun aChangeGoesToTheProfileWhoseSettingsShow() = runTest(dispatcher) {
        val vm = newViewModel()

        // The session moves on before the change is handled: it still belongs to Arshad.
        profiles.selected.value = MIRA
        vm.onSwitch(ProfileSwitch.Dislikes, on = false)
        advanceUntilIdle()

        assertEquals(listOf(ARSHAD), profiles.updatedIds)
        assertFalse(profiles.of(ARSHAD).dislikes)
        assertEquals(ProfileSettings(), profiles.of(MIRA))
        assertEquals(ProfileSettingsState(MIRA, ProfileSettings()), vm.uiState.value.profile)
    }

    @Test
    fun aFailedWriteLeavesTheStoredValueShowing() = runTest(dispatcher) {
        profiles.failWrites = true
        val vm = newViewModel()

        vm.onSwitch(ProfileSwitch.Resume, on = false)
        advanceUntilIdle()

        assertEquals(listOf(ARSHAD), profiles.updatedIds)
        assertEquals(ProfileSettings(), vm.uiState.value.profile?.settings)
    }

    @Test
    fun captionsDoNotOpenBeforeTheProfilesSettingsLoad() = runTest(dispatcher) {
        profiles.selected.value = null
        val vm = newViewModel()

        vm.onOpenCaptions()

        assertNull(vm.uiState.value.sheet)
    }

    @Test
    fun nothingIsWrittenBeforeTheProfilesSettingsLoad() = runTest(dispatcher) {
        profiles.selected.value = null
        val vm = newViewModel()

        vm.onSwitch(ProfileSwitch.Resume, on = false)
        advanceUntilIdle()

        assertEquals(emptyList<Long>(), profiles.updatedIds)
    }

    @Test
    fun pickingACaptionsLanguageStoresItAndClosesTheSheet() = runTest(dispatcher) {
        val vm = newViewModel()

        vm.onOpenCaptions()
        assertEquals(PreferencesSheet.Captions, vm.uiState.value.sheet)
        vm.onCaptionsPicked("ar")
        advanceUntilIdle()

        assertNull(vm.uiState.value.sheet)
        assertEquals("ar", profiles.of(ARSHAD).captionsLanguage)
    }

    @Test
    fun pickingOffTurnsCaptionsOff() = runTest(dispatcher) {
        profiles.byProfile.value = mapOf(ARSHAD to ProfileSettings(captionsLanguage = "en"))
        val vm = newViewModel()

        vm.onOpenCaptions()
        vm.onCaptionsPicked(null)
        advanceUntilIdle()

        assertNull(profiles.of(ARSHAD).captionsLanguage)
    }

    @Test
    fun aCaptionsPickWithoutItsSheetDoesNothing() = runTest(dispatcher) {
        val vm = newViewModel()

        vm.onCaptionsPicked("ar")
        advanceUntilIdle()

        assertEquals(emptyList<Long>(), profiles.updatedIds)
    }

    @Test
    fun pickingACategoryActionStoresItForThatCategoryOnly() = runTest(dispatcher) {
        val vm = newViewModel()

        vm.onOpenCategory(SegmentCategory.Sponsor)
        assertEquals(PreferencesSheet.Category(SegmentCategory.Sponsor), vm.uiState.value.sheet)
        vm.onActionPicked(SegmentAction.Skip)
        advanceUntilIdle()

        assertNull(vm.uiState.value.sheet)
        val actions = profiles.of(ARSHAD).segmentActions
        assertEquals(SegmentAction.Skip, actions[SegmentCategory.Sponsor])
        assertEquals(ProfileSettings().segmentActions - SegmentCategory.Sponsor, actions - SegmentCategory.Sponsor)
    }

    @Test
    fun aCategoryDoesNotOpenWhileSponsorBlockIsOff() = runTest(dispatcher) {
        profiles.byProfile.value = mapOf(ARSHAD to ProfileSettings(sponsorBlock = false))
        val vm = newViewModel()

        vm.onOpenCategory(SegmentCategory.Sponsor)

        assertNull(vm.uiState.value.sheet)
    }

    @Test
    fun anActionPickWithoutACategorySheetDoesNothing() = runTest(dispatcher) {
        val vm = newViewModel()

        vm.onOpenCaptions()
        vm.onActionPicked(SegmentAction.Off)
        advanceUntilIdle()

        assertEquals(PreferencesSheet.Captions, vm.uiState.value.sheet)
        assertEquals(emptyList<Long>(), profiles.updatedIds)
    }

    @Test
    fun aSheetOpensOnlyFromThePage() = runTest(dispatcher) {
        val vm = newViewModel()

        vm.onOpenLicences()
        vm.onOpenCaptions()

        assertEquals(PreferencesSheet.Licences, vm.uiState.value.sheet)
    }

    @Test
    fun backClosesTheSheet() = runTest(dispatcher) {
        val vm = newViewModel()

        vm.onOpenLicences()
        vm.onBack()

        assertNull(vm.uiState.value.sheet)
    }

    @Test
    fun sourceCodeOpensItsSheet() = runTest(dispatcher) {
        val vm = newViewModel()

        vm.onOpenSourceCode()

        assertEquals(PreferencesSheet.SourceCode, vm.uiState.value.sheet)
    }

    @Test
    fun theOwnerIsKnownAndChangesTheTv() = runTest(dispatcher) {
        val state = newViewModel().uiState.value

        assertEquals(ARSHAD, state.ownerId)
        assertEquals("Arshad", state.ownerName)
        assertTrue(state.changesTheTv)
    }

    @Test
    fun anotherProfileDoesNotChangeTheTv() = runTest(dispatcher) {
        profiles.selected.value = MIRA
        val vm = newViewModel()

        vm.onSwitch(DeviceSwitch.ClockInPlayer, on = false)
        vm.onOpenQuality()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.changesTheTv)
        assertNull(vm.uiState.value.sheet)
        assertEquals(DeviceSettings(), device.current.value)
    }

    @Test
    fun beforeTheProfilesLoadNobodyChangesTheTv() = runTest(dispatcher) {
        people.rows.value = null
        val vm = newViewModel()

        vm.onSwitch(DeviceSwitch.HeroOnHome, on = false)
        advanceUntilIdle()

        assertEquals(DeviceSettings(), device.current.value)
    }

    @Test
    fun pickingASpeedStoresItAndClosesTheSheet() = runTest(dispatcher) {
        val vm = newViewModel()

        vm.onOpenSpeed()
        assertEquals(PreferencesSheet.Speed, vm.uiState.value.sheet)
        vm.onSpeedPicked(175)
        advanceUntilIdle()

        assertNull(vm.uiState.value.sheet)
        assertEquals(ProfileSettings(speedPercent = 175), profiles.of(ARSHAD))
    }

    @Test
    fun anotherProfilePicksItsOwnSpeed() = runTest(dispatcher) {
        profiles.selected.value = MIRA
        val vm = newViewModel()

        vm.onOpenSpeed()
        vm.onSpeedPicked(50)
        advanceUntilIdle()

        assertEquals(50, profiles.of(MIRA).speedPercent)
    }

    @Test
    fun aSpeedPickWithoutItsSheetDoesNothing() = runTest(dispatcher) {
        val vm = newViewModel()

        vm.onOpenCaptions()
        vm.onSpeedPicked(200)
        advanceUntilIdle()

        assertEquals(PreferencesSheet.Captions, vm.uiState.value.sheet)
        assertEquals(emptyList<Long>(), profiles.updatedIds)
    }

    @Test
    fun theOwnerPicksTheQualityForTheTv() = runTest(dispatcher) {
        val vm = newViewModel()

        vm.onOpenQuality()
        assertEquals(PreferencesSheet.Quality, vm.uiState.value.sheet)
        vm.onQualityPicked(720)
        advanceUntilIdle()

        assertNull(vm.uiState.value.sheet)
        assertEquals(DeviceSettings(maxVideoHeight = 720), device.current.value)
        assertEquals(ProfileSettings(), profiles.of(ARSHAD))
    }

    @Test
    fun theOwnerPicksTheInterfaceSizeForTheTv() = runTest(dispatcher) {
        val vm = newViewModel()

        vm.onOpenInterfaceSize()
        assertEquals(PreferencesSheet.InterfaceSize, vm.uiState.value.sheet)
        vm.onInterfaceSizePicked(120)
        advanceUntilIdle()

        assertNull(vm.uiState.value.sheet)
        assertEquals(DeviceSettings(interfaceScalePercent = 120), device.current.value)
    }

    @Test
    fun anotherProfileCannotOpenTheInterfaceSize() = runTest(dispatcher) {
        profiles.selected.value = MIRA
        val vm = newViewModel()

        vm.onOpenInterfaceSize()
        vm.onInterfaceSizePicked(120)
        advanceUntilIdle()

        assertNull(vm.uiState.value.sheet)
        assertEquals(DeviceSettings(), device.current.value)
    }

    @Test
    fun aChatPlacementPickWithoutItsSheetDoesNothing() = runTest(dispatcher) {
        val vm = newViewModel()

        vm.onOpenCaptions()
        vm.onChatPlacementPicked(ChatPlacement.Side)
        advanceUntilIdle()

        assertEquals(PreferencesSheet.Captions, vm.uiState.value.sheet)
        assertEquals(emptyList<Long>(), profiles.updatedIds)
    }

    @Test
    fun aQualityPickWithoutItsSheetDoesNothing() = runTest(dispatcher) {
        val vm = newViewModel()

        vm.onOpenCaptions()
        vm.onQualityPicked(720)
        advanceUntilIdle()

        assertEquals(PreferencesSheet.Captions, vm.uiState.value.sheet)
        assertEquals(DeviceSettings(), device.current.value)
    }

    @Test
    fun qualityDoesNotOpenBeforeTheTvsSettingsLoad() = runTest(dispatcher) {
        device.current.value = null
        val vm = newViewModel()

        vm.onOpenQuality()

        assertNull(vm.uiState.value.sheet)
    }

    @Test
    fun allLanguagesOpensFromCaptionsAndBackReturnsThere() = runTest(dispatcher) {
        val vm = newViewModel()

        vm.onOpenAllLanguages()
        assertNull(vm.uiState.value.sheet)
        vm.onOpenCaptions()
        vm.onOpenAllLanguages()
        assertEquals(PreferencesSheet.AllLanguages, vm.uiState.value.sheet)
        vm.onBack()

        assertEquals(PreferencesSheet.Captions, vm.uiState.value.sheet)
    }

    @Test
    fun aSwipedAwaySheetClosesFromAnyStage() = runTest(dispatcher) {
        val vm = newViewModel()

        vm.onOpenCaptions()
        vm.onOpenAllLanguages()
        vm.onDismiss()

        assertNull(vm.uiState.value.sheet)
    }

    @Test
    fun aPickFromAllLanguagesStoresItAndClosesBothSheets() = runTest(dispatcher) {
        val vm = newViewModel()

        vm.onOpenCaptions()
        vm.onOpenAllLanguages()
        vm.onCaptionsPicked("fr")
        advanceUntilIdle()

        assertNull(vm.uiState.value.sheet)
        assertEquals("fr", profiles.of(ARSHAD).captionsLanguage)
    }

    @Test
    fun aLicencesTextOpensFromLicencesAndBackReturnsThere() = runTest(dispatcher) {
        val vm = newViewModel()

        vm.onOpenLicenceText("Rhino")
        assertNull(vm.uiState.value.sheet)
        vm.onOpenLicences()
        vm.onOpenLicenceText("Rhino")
        assertEquals(PreferencesSheet.LicenceText("Rhino"), vm.uiState.value.sheet)
        vm.onBack()
        assertEquals(PreferencesSheet.Licences, vm.uiState.value.sheet)
        vm.onBack()

        assertNull(vm.uiState.value.sheet)
    }

    private fun TestScope.newViewModel(): PreferencesViewModel =
        PreferencesViewModel(profiles, device, people).also { advanceUntilIdle() }

    private companion object {
        const val ARSHAD = 1L
        const val MIRA = 2L
        val OWNER = Profile(ARSHAD, "Arshad", ProfileTint.Amber, isOwner = true, lock = null)
        val GUEST = Profile(MIRA, "Mira", ProfileTint.Rose, isOwner = false, lock = null)
    }
}

/** The captions sheet's order (Q16): the TV's language, a pick from All languages, the leading languages, then the groups. */
class CaptionsChoicesTest {

    @Test
    fun anEnglishTvLeadsWithEnglishThenTheGroups() {
        assertEquals(
            listOf(
                CaptionsSection(null, listOf("en", "bn", "ar")),
                CaptionsSection(CaptionsGroup.Gaelic, listOf("ga", "gd")),
                CaptionsSection(CaptionsGroup.Indigenous, listOf("crk", "bla", "chp", "sto", "oj", "iu")),
            ),
            captionsChoices("en", chosen = null),
        )
    }

    @Test
    fun theTvsLanguageLeadsWhenOffered() {
        assertEquals(listOf("ar", "en", "bn"), captionsChoices("ar", chosen = null).first().tags)
    }

    @Test
    fun aTvLanguageInAGroupLeavesItForTheLead() {
        val sections = captionsChoices("ga", chosen = null)

        assertEquals(listOf("ga", "en", "bn", "ar"), sections.first().tags)
        assertEquals(listOf("gd"), sections.single { it.group == CaptionsGroup.Gaelic }.tags)
    }

    @Test
    fun aTvLanguageNotOfferedLeadsTheList() {
        assertEquals(listOf("sv", "en", "bn", "ar"), captionsChoices("sv", chosen = null).first().tags)
    }

    @Test
    fun aPickFromAllLanguagesFollowsTheTvs() {
        assertEquals(listOf("en", "ja", "bn", "ar"), captionsChoices("en", chosen = "ja").first().tags)
    }

    @Test
    fun aTvWithoutALanguageStartsWithTheLeadingLanguages() {
        assertEquals(OFFERED, captionsChoices("", chosen = null).flatMap { it.tags })
    }

    @Test
    fun aTvLanguageThatIsNoLanguageCodeIsLeftOut() {
        assertEquals(OFFERED, captionsChoices("en_US", chosen = null).flatMap { it.tags })
    }

    @Test
    fun anOfferedChoiceKeepsItsPlace() {
        assertEquals(OFFERED, captionsChoices("en", chosen = "oj").flatMap { it.tags })
    }

    @Test
    fun theTvsOwnChoiceShowsOnce() {
        assertEquals(listOf("sv") + OFFERED, captionsChoices("sv", chosen = "sv").flatMap { it.tags })
    }

    @Test
    fun aPickLeadsWhenTheTvHasNoLanguage() {
        assertEquals(listOf("ja") + OFFERED, captionsChoices("", chosen = "ja").flatMap { it.tags })
    }

    private companion object {
        val OFFERED = listOf("en", "bn", "ar", "ga", "gd", "crk", "bla", "chp", "sto", "oj", "iu")
    }
}
