package com.arshad.ayn.feature.settings

import com.arshad.ayn.core.access.AccessException
import com.arshad.ayn.core.access.ItemsPage
import com.arshad.ayn.core.access.PageToken
import com.arshad.ayn.core.lock.FakeProfileLocks
import com.arshad.ayn.core.lock.LockKind
import com.arshad.ayn.core.lock.LockSecret
import com.arshad.ayn.core.store.Bedtime
import com.arshad.ayn.core.store.FakeFilterStore
import com.arshad.ayn.core.store.FakeProfileStore
import com.arshad.ayn.core.store.FilterPacks
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.ProfileTint
import com.arshad.ayn.feature.FakeSubscriptionStore
import com.arshad.ayn.feature.UnsupportedExtractor
import com.arshad.ayn.feature.profiles.OwnerLockStep
import com.arshad.ayn.feature.testChannel
import com.arshad.ayn.feature.testSubscription
import java.time.DayOfWeek
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Adding a kids profile (`kp-kids` nolock…kidtime; KP-D8, D10, D14) over in-memory stores: the
 * owner's lock (or a first one) before step 1, the name refusals, approving channels from the
 * owner's subscriptions and Find a channel, the time rules, and Add writing it all (or nothing).
 */
class KidsAddViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val filters = FakeFilterStore()
    private val profiles = FakeProfileStore(filters = filters)
    private val locks = FakeProfileLocks().apply { ownerId = OWNER.id }
    private val subscriptions = FakeSubscriptionStore()
    private val extractor = SearchOnly()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun aLockedOwnerEntersTheirPinThenStepOneOpensWithTheNextFreeTint() = runTest(dispatcher) {
        val vm = open(OWNER_PIN)

        vm.onStart()
        advanceUntilIdle()
        assertEquals(KidsAddScreen.OwnerGate(LockKind.Pin), vm.screen)
        PIN.forEach(vm::onDigit)
        advanceUntilIdle()

        assertEquals(KidsAddScreen.Form(), vm.screen)
        assertEquals(ProfileTint.Teal, vm.uiState.value.draft.tint)
    }

    @Test
    fun anOwnerWithoutALockSetsOneBeforeStepOne() = runTest(dispatcher) {
        val vm = open(OWNER)

        vm.onStart()
        advanceUntilIdle()
        assertEquals(KidsAddScreen.OwnerLockNeeded(OwnerLockStep.Ask), vm.screen)
        vm.onOwnerLockStart()
        vm.onOwnerLockKind(LockKind.Pin)
        PIN.forEach(vm::onDigit)
        advanceUntilIdle()
        PIN.forEach(vm::onDigit)
        advanceUntilIdle()

        assertEquals(KidsAddScreen.Form(), vm.screen)
        assertEquals(LockKind.Pin, locks.locks[OWNER.id]?.kind)
    }

    @Test
    fun aBlankOrTakenNameIsRefusedOnStepOne() = runTest(dispatcher) {
        val vm = openAtForm()

        vm.onFormContinue()
        assertEquals(KidsAddScreen.Form(NameProblem.Missing), vm.screen)
        vm.onNameChange("mira")
        vm.onFormContinue()

        assertEquals(KidsAddScreen.Form(NameProblem.Taken), vm.screen)
    }

    @Test
    fun aSubscriptionTogglesItsApprovalWhateverItsUrlForm() = runTest(dispatcher) {
        val vm = openAtChannels()
        val bluey = testSubscription(channelUrl = BLUEY_URL, name = "Bluey")

        vm.onToggleSubscription(bluey)
        assertTrue(vm.uiState.value.draft.approves(BLUEY_URL))
        vm.onToggleChannel(testChannel(url = "$BLUEY_URL/videos", name = "Bluey"))

        assertTrue(vm.uiState.value.draft.channels.isEmpty())
    }

    @Test
    fun findAChannelApprovesAChannelYouTubeFindsAndClosesItsSheet() = runTest(dispatcher) {
        val vm = openAtChannels()
        val pinkfong = testChannel(url = PINKFONG_URL, name = "Pinkfong")
        extractor.answer = ItemsPage(items = listOf(pinkfong), next = null)

        vm.onFindChannel()
        vm.onFindQueryChange("pinkfong")
        vm.onFindSearch()
        advanceUntilIdle()
        assertEquals(FindResults.Found("pinkfong", listOf(pinkfong)), (vm.screen as KidsAddScreen.Channels).find?.results)
        vm.onFindPick(pinkfong)

        assertEquals(KidsAddScreen.Channels(), vm.screen)
        assertEquals(listOf(pinkfong), vm.uiState.value.draft.channels)
    }

    @Test
    fun findAChannelSaysWhenNothingMatchesOrYouTubeCantBeReached() = runTest(dispatcher) {
        val vm = openAtChannels()
        vm.onFindChannel()
        vm.onFindQueryChange("trucks")
        extractor.answer = ItemsPage(items = emptyList(), next = null)

        vm.onFindSearch()
        advanceUntilIdle()
        assertEquals(FindResults.NoMatch("trucks"), (vm.screen as KidsAddScreen.Channels).find?.results)
        extractor.answer = null
        vm.onFindSearch()
        advanceUntilIdle()

        assertEquals(FindResults.Failed, (vm.screen as KidsAddScreen.Channels).find?.results)
    }

    @Test
    fun addWritesTheKidsProfileItsTimeRulesAndItsChannelsNewestFirst() = runTest(dispatcher) {
        val vm = openAtChannels()
        vm.onToggleSubscription(testSubscription(channelUrl = BLUEY_URL, name = "Bluey"))
        vm.onToggleChannel(testChannel(url = PINKFONG_URL, name = "Pinkfong"))
        vm.onChannelsContinue()
        vm.onDayLimit(DayOfWeek.SATURDAY)
        vm.onDayLimitPicked(120)
        vm.onDayLimit(DayOfWeek.SUNDAY)
        vm.onDayLimitPicked(null)
        vm.onBedtimeStart()
        vm.onBedtimePicked(20 * 60)

        vm.onAdd()
        advanceUntilIdle()

        val kid = profiles.rows.value!!.last()
        assertEquals(Profile(kid.id, "Zainab", ProfileTint.Teal, isOwner = false, lock = null, isKids = true), kid)
        val restrictions = filters.rulesById.value.getValue(kid.id).restrictions
        assertEquals(DEFAULT_DAILY_LIMITS + (DayOfWeek.SATURDAY to 120) - DayOfWeek.SUNDAY, restrictions.dailyLimitMinutes)
        assertEquals(Bedtime(20 * 60, 7 * 60), restrictions.bedtime)
        assertEquals(FilterPacks(harmfulForKids = true), restrictions.filterPacks)
        assertEquals(listOf(PINKFONG_URL, BLUEY_URL), filters.approved.value.getValue(kid.id).map { it.channelUrl })
        assertEquals(KidsAddScreen.Closed, vm.screen)
        assertEquals(kid.id, vm.uiState.value.added)
    }

    @Test
    fun bedtimeOffAddsNoBedtime() = runTest(dispatcher) {
        val vm = openAtChannels()
        vm.onChannelsContinue()
        vm.onBedtimeToggle()

        vm.onAdd()
        advanceUntilIdle()

        assertNull(filters.rulesById.value.getValue(profiles.rows.value!!.last().id).restrictions.bedtime)
    }

    @Test
    fun aBedtimeEndingWhenItStartsIsRefused() = runTest(dispatcher) {
        val vm = openAtChannels()
        vm.onChannelsContinue()
        vm.onBedtimeEnd()

        vm.onBedtimePicked(DEFAULT_BEDTIME.startMinute)

        assertEquals(KidsAddScreen.Time(sheet = TimeSheet.BedtimeEnd), vm.screen)
        assertEquals(DEFAULT_BEDTIME, vm.uiState.value.draft.bedtime)
    }

    @Test
    fun aFailedWriteRemovesTheNewProfileAndTheTimeStepSaysSo() = runTest(dispatcher) {
        val vm = openAtChannels()
        vm.onChannelsContinue()
        filters.failWrites = true

        vm.onAdd()
        advanceUntilIdle()

        assertEquals(KidsAddScreen.Time(failed = true), vm.screen)
        assertEquals(listOf(OWNER_PIN, MIRA), profiles.rows.value)
        assertNull(vm.uiState.value.added)
    }

    @Test
    fun backStepsBackAndClosingDropsThePass() = runTest(dispatcher) {
        val vm = openAtChannels()
        vm.onChannelsContinue()
        vm.onDayLimit(DayOfWeek.MONDAY)

        vm.onBack()
        assertEquals(KidsAddScreen.Time(), vm.screen)
        vm.onBack()
        assertEquals(KidsAddScreen.Channels(), vm.screen)
        vm.onBack()
        assertEquals(KidsAddScreen.Form(), vm.screen)
        vm.onBack()
        assertEquals(KidsAddScreen.Closed, vm.screen)
        vm.onStart()
        advanceUntilIdle()

        assertEquals(KidsAddScreen.OwnerGate(LockKind.Pin), vm.screen)
    }

    @Test
    fun touchBackLeavesALockPageWithPressesEnteredButNotWhileATryIsChecked() = runTest(dispatcher) {
        val vm = open(OWNER_PIN)
        vm.onStart()
        advanceUntilIdle()
        vm.onDigit('2')

        vm.onEntryLeave()
        assertEquals(KidsAddScreen.Closed, vm.screen)

        vm.onStart()
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        locks.unlockGate = gate
        PIN.forEach(vm::onDigit)
        advanceUntilIdle()
        vm.onEntryLeave()
        assertTrue((vm.screen as KidsAddScreen.OwnerGate).entry.checking)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(KidsAddScreen.Form(), vm.screen)
    }

    @Test
    fun touchBackOnANewLockReturnsToChooseALock() = runTest(dispatcher) {
        val vm = open(OWNER)
        vm.onStart()
        advanceUntilIdle()
        vm.onOwnerLockStart()
        vm.onOwnerLockKind(LockKind.Pin)
        vm.onDigit('1')

        vm.onEntryLeave()
        assertEquals(KidsAddScreen.OwnerLockNeeded(OwnerLockStep.ChooseKind), vm.screen)
        vm.onEntryLeave()
        assertEquals(KidsAddScreen.OwnerLockNeeded(OwnerLockStep.ChooseKind), vm.screen)
    }

    @Test
    fun aSwipedSheetClosesToItsStep() = runTest(dispatcher) {
        val vm = open(OWNER)
        vm.onStart()
        advanceUntilIdle()
        vm.onOwnerLockStart()
        vm.onDismiss()
        assertEquals(KidsAddScreen.OwnerLockNeeded(OwnerLockStep.Ask), vm.screen)
        vm.onDismiss()
        assertEquals(KidsAddScreen.OwnerLockNeeded(OwnerLockStep.Ask), vm.screen)

        val add = openAtChannels()
        add.onFindChannel()
        add.onFindQueryChange("bluey")
        add.onDismiss()
        assertEquals(KidsAddScreen.Channels(), add.screen)
        add.onChannelsContinue()
        add.onBedtimeStart()
        add.onDismiss()
        assertEquals(KidsAddScreen.Time(), add.screen)
        add.onDismiss()
        assertEquals(KidsAddScreen.Time(), add.screen)
    }

    private val KidsAddViewModel.screen: KidsAddScreen get() = uiState.value.screen

    private fun TestScope.open(owner: Profile): KidsAddViewModel {
        profiles.rows.value = listOf(owner, MIRA)
        if (owner.lock == LockKind.Pin) locks.locks[owner.id] = LockSecret.Pin(PIN.joinToString(""))
        locks.known += owner.id
        return KidsAddViewModel(profiles, locks, filters, subscriptions, extractor, testScheduler.timeSource).also { advanceUntilIdle() }
    }

    private fun TestScope.openAtForm(): KidsAddViewModel = open(OWNER_PIN).also { vm ->
        vm.onStart()
        advanceUntilIdle()
        PIN.forEach(vm::onDigit)
        advanceUntilIdle()
    }

    private fun TestScope.openAtChannels(): KidsAddViewModel = openAtForm().also { vm ->
        vm.onNameChange("Zainab")
        vm.onFormContinue()
    }

    /** Answers a search with [answer], or fails as YouTube out of reach while it is `null`. */
    private class SearchOnly : UnsupportedExtractor("search-only") {
        var answer: ItemsPage? = null

        override suspend fun search(query: String, page: PageToken?): ItemsPage =
            answer ?: throw AccessException("offline", java.io.IOException("no route"))
    }

    private companion object {
        val OWNER = Profile(1, "Arshad", ProfileTint.Amber, isOwner = true, lock = null)
        val OWNER_PIN = OWNER.copy(lock = LockKind.Pin)
        val MIRA = Profile(2, "Mira", ProfileTint.Rose, isOwner = false, lock = null)
        val PIN = listOf('2', '4', '6', '1')
        const val BLUEY_URL = "https://www.youtube.com/channel/UCVzLLZkDuFGAE2BGdBuBNBg"
        const val PINKFONG_URL = "https://www.youtube.com/channel/UCcdwLMPsaU2ezNSJU1nFoBQ"
    }
}
