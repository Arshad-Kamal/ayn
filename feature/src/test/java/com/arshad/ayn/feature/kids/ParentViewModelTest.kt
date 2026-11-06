package com.arshad.ayn.feature.kids

import com.arshad.ayn.core.lock.FakeProfileLocks
import com.arshad.ayn.core.lock.LockKind
import com.arshad.ayn.core.lock.LockSecret
import com.arshad.ayn.core.lock.OwnerPass
import com.arshad.ayn.core.lock.Press
import com.arshad.ayn.core.store.ApprovedVideo
import com.arshad.ayn.core.store.Bedtime
import com.arshad.ayn.core.store.FakeFilterStore
import com.arshad.ayn.core.store.FakeProfileStore
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.ProfileRestrictions
import com.arshad.ayn.core.store.ProfileRules
import com.arshad.ayn.core.store.ProfileTint
import com.arshad.ayn.core.store.StoredRule
import com.arshad.ayn.core.store.Subscription
import com.arshad.ayn.feature.settings.isPickableBedtimeMinute
import com.arshad.ayn.feature.settings.isPickableLimit
import com.arshad.ayn.feature.settings.TimeSheet
import java.time.DayOfWeek
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A kids profile's Parent page over in-memory stores (`kp-kids` parentgate, parent; KP-D9, D10,
 * D11): the owner's lock each time it shows, counted on the kid's tries; the pass gone once it
 * hides; BACK's steps; limits, bedtime and approvals written as owner restrictions; Leave with the
 * page's pass.
 */
class ParentViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val filters = FakeFilterStore()
    private val people = FakeProfileStore(listOf(OWNER, KID))
    private val locks = FakeProfileLocks().apply {
        ownerId = OWNER.id
        kids += KID.id
    }
    private val leaves = mutableListOf<OwnerPass>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun anOwnerWithoutALockOpensThePageStraightAway() = runTest(dispatcher) {
        val vm = newViewModel()

        vm.onShown()
        advanceUntilIdle()

        assertEquals(ParentScreen.Page(), vm.uiState.value.screen)
        assertNotNull(vm.ownerPass)
    }

    @Test
    fun theOwnersLockOpensThePageCountedOnTheKidsTries() = runTest(dispatcher) {
        locks.locks[OWNER.id] = LockSecret.Sequence(SEQUENCE)
        val vm = newViewModel()

        vm.onShown()
        advanceUntilIdle()
        assertEquals(LockKind.Sequence, (vm.uiState.value.screen as ParentScreen.OwnerGate).lockKind)
        SEQUENCE.forEach(vm::onPress)
        vm.onSubmitSequence()
        advanceUntilIdle()

        assertEquals(ParentScreen.Page(), vm.uiState.value.screen)
        assertEquals(listOf(KID.id), locks.tries)
    }

    @Test
    fun hidingThePageDropsThePassAndShowingItAgainAsksAgain() = runTest(dispatcher) {
        locks.locks[OWNER.id] = LockSecret.Sequence(SEQUENCE)
        val vm = openedPage()

        vm.onHidden()
        assertNull(vm.ownerPass)
        vm.onLeave()
        vm.onShown()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.screen is ParentScreen.OwnerGate)
        assertTrue(leaves.isEmpty())
    }

    @Test
    fun backOnTheGateOrThePageClosesItButAReviewOrSheetStepsBack() = runTest(dispatcher) {
        locks.locks[OWNER.id] = LockSecret.Sequence(SEQUENCE)
        val vm = newViewModel()
        vm.onShown()
        advanceUntilIdle()
        assertFalse(vm.onBack())

        val page = openedPage()
        page.onOpenReview()
        assertTrue(page.onBack())
        assertEquals(ParentScreen.Page(), page.uiState.value.screen)
        page.onOpenDailyLimits()
        page.onDayLimit(DayOfWeek.MONDAY)
        assertTrue(page.onBack())
        assertEquals(ParentScreen.Page(ParentSheet.DailyLimits), page.uiState.value.screen)
        assertTrue(page.onBack())
        assertFalse(page.onBack())
    }

    @Test
    fun dismissingATimeSheetClosesItWithItsList() = runTest(dispatcher) {
        val page = openedPage()
        page.onOpenBedtime()
        page.onBedtimeStart()

        page.onDismiss()

        assertEquals(ParentScreen.Page(), page.uiState.value.screen)
    }

    @Test
    fun touchBackMayLeaveTheGateWithPressesEnteredButNotWhileATryIsChecked() = runTest(dispatcher) {
        locks.locks[OWNER.id] = LockSecret.Sequence(SEQUENCE)
        val vm = newViewModel()
        vm.onShown()
        advanceUntilIdle()
        vm.onPress(Press.Up)

        assertTrue(vm.canLeaveGate())

        vm.onErase()
        locks.unlockGate = CompletableDeferred()
        SEQUENCE.forEach(vm::onPress)
        vm.onSubmitSequence()
        advanceUntilIdle()
        assertFalse(vm.canLeaveGate())
        locks.unlockGate?.complete(Unit)
        advanceUntilIdle()
        assertEquals(ParentScreen.Page(), vm.uiState.value.screen)
    }

    @Test
    fun aDaysLimitPickedIsTheOwnersRestrictionAndNoLimitTakesItOff() = runTest(dispatcher) {
        val vm = openedPage()

        vm.onOpenDailyLimits()
        vm.onDayLimit(DayOfWeek.SATURDAY)
        vm.onDayLimitPicked(120)
        advanceUntilIdle()
        assertEquals(mapOf(DayOfWeek.SATURDAY to 120), restrictions().dailyLimitMinutes)
        assertEquals(ParentScreen.Page(ParentSheet.DailyLimits), vm.uiState.value.screen)

        vm.onDayLimit(DayOfWeek.SATURDAY)
        vm.onDayLimitPicked(null)
        advanceUntilIdle()
        assertEquals(emptyMap<DayOfWeek, Int>(), restrictions().dailyLimitMinutes)
    }

    @Test
    fun bedtimeTurnedOffAndOnKeepsItsTimesAndAPickedBoundTurnsItOn() = runTest(dispatcher) {
        filters.rulesById.value = mapOf(KID.id to ProfileRules(restrictions = ProfileRestrictions(bedtime = BEDTIME), kids = true))
        val vm = openedPage()

        vm.onOpenBedtime()
        vm.onBedtimeToggle()
        advanceUntilIdle()
        assertNull(restrictions().bedtime)
        vm.onBedtimeToggle()
        advanceUntilIdle()
        assertEquals(BEDTIME, restrictions().bedtime)

        vm.onBedtimeToggle()
        advanceUntilIdle()
        vm.onBedtimeStart()
        assertEquals(ParentScreen.Page(ParentSheet.Time(TimeSheet.BedtimeStart)), vm.uiState.value.screen)
        vm.onBedtimePicked(21 * 60)
        advanceUntilIdle()
        assertEquals(Bedtime(21 * 60, BEDTIME.endMinute), restrictions().bedtime)
        assertEquals(ParentScreen.Page(ParentSheet.Bedtime), vm.uiState.value.screen)
    }

    @Test
    fun removingTheLastApprovedChannelClosesItsSheet() = runTest(dispatcher) {
        filters.approved.value = mapOf(KID.id to listOf(CHANNEL))
        val vm = openedPage()

        vm.onOpenChannels()
        assertEquals(ParentScreen.Page(ParentSheet.ApprovedChannels), vm.uiState.value.screen)
        vm.onRemoveChannel(CHANNEL.channelUrl)
        advanceUntilIdle()

        assertTrue(filters.approved.value[KID.id].orEmpty().isEmpty())
        assertEquals(ParentScreen.Page(), vm.uiState.value.screen)
    }

    @Test
    fun approvedVideosListNewestFirst() {
        val later = VIDEO.copy(videoId = "XqZsoesa55w")
        val rules = ProfileRules(rules = listOf(StoredRule(VIDEO, byOwner = true, addedAtMillis = 1), StoredRule(later, byOwner = true, addedAtMillis = 2)), kids = true)

        assertEquals(listOf(later, VIDEO), ParentUiState(rules = rules).videos.map { it.rule })
    }

    @Test
    fun onlyOfferedTimesArePickable() {
        assertEquals(listOf(true, true, true, false, false, false), listOf(null, 15, 240, 0, 50, 255).map(::isPickableLimit))
        assertEquals(listOf(true, false), listOf(1290, 1295).map(::isPickableBedtimeMinute))
    }

    @Test
    fun anApprovedVideoIsRemovedAsTheOwnersRule() = runTest(dispatcher) {
        filters.rulesById.value = mapOf(KID.id to ProfileRules(rules = listOf(StoredRule(VIDEO, byOwner = true, addedAtMillis = 1)), kids = true))
        val vm = openedPage()

        vm.onOpenVideos()
        vm.onRemoveVideo(VIDEO)
        advanceUntilIdle()

        assertTrue(filters.rulesById.value[KID.id]?.rules.orEmpty().isEmpty())
        assertEquals(ParentScreen.Page(), vm.uiState.value.screen)
    }

    @Test
    fun leaveHandsOverThePagesPass() = runTest(dispatcher) {
        val vm = openedPage()

        vm.onLeave()

        assertEquals(1, leaves.size)
    }

    private fun restrictions(): ProfileRestrictions = filters.rulesById.value[KID.id]?.restrictions ?: ProfileRestrictions()

    private fun TestScope.openedPage(): ParentViewModel {
        val vm = newViewModel()
        vm.onShown()
        advanceUntilIdle()
        if (vm.uiState.value.screen is ParentScreen.OwnerGate) {
            SEQUENCE.forEach(vm::onPress)
            vm.onSubmitSequence()
            advanceUntilIdle()
        }
        assertTrue(vm.uiState.value.screen is ParentScreen.Page)
        return vm
    }

    private fun TestScope.newViewModel(): ParentViewModel =
        ParentViewModel(KID.id, people, locks, filters, leave = { leaves += it }, TimeSource.Monotonic).also { advanceUntilIdle() }

    private companion object {
        val OWNER = Profile(1, "Arshad", ProfileTint.Amber, isOwner = true, lock = LockKind.Sequence)
        val KID = Profile(4, "Zainab", ProfileTint.Violet, isOwner = false, lock = null, isKids = true)
        val SEQUENCE = listOf(Press.Up, Press.Up, Press.Down, Press.Left, Press.Right, Press.Down)
        val BEDTIME = Bedtime(19 * 60 + 30, 7 * 60)
        val CHANNEL = Subscription("https://www.youtube.com/channel/UCbCmjCuTUZos6Inko4u57UQ", "CoComelon", null, 1, 1)
        val VIDEO = ApprovedVideo("e_04ZrNroTo", "Wheels on the Bus", "CoComelon", null, null, 120)
    }
}
