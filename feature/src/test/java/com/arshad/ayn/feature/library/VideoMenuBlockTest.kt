package com.arshad.ayn.feature.library

import com.arshad.ayn.core.lock.FakeProfileLocks
import com.arshad.ayn.core.lock.LockKind
import com.arshad.ayn.core.lock.LockSecret
import com.arshad.ayn.core.lock.Press
import com.arshad.ayn.core.store.ApprovedVideo
import com.arshad.ayn.core.store.BlockedChannel
import com.arshad.ayn.core.store.BlockedVideo
import com.arshad.ayn.core.store.ContentRule
import com.arshad.ayn.core.store.FakeFilterStore
import com.arshad.ayn.core.store.FakePlaylistStore
import com.arshad.ayn.core.store.FakeProfileSettingsStore
import com.arshad.ayn.core.store.FakeProfileStore
import com.arshad.ayn.core.store.FakeWatchStore
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.ProfileRules
import com.arshad.ayn.core.store.ProfileTint
import com.arshad.ayn.core.store.StoredRule
import com.arshad.ayn.core.store.canonicalChannelUrl
import com.arshad.ayn.feature.testSubscription
import com.arshad.ayn.feature.testVideo
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The card menu's Block over in-memory stores (`kp-filters` cardmenu, blocksheet, blockchoose,
 * blocked, blockmira; KP-D19): the open profile gets its own rule and every other one an owner
 * restriction, the owner's open profile blocks without its lock, anyone else past their own
 * profile enters it first, and Undo takes back only what the block added.
 */
class VideoMenuBlockTest {
    private val dispatcher = StandardTestDispatcher()
    private val filters = FakeFilterStore()
    private val people = FakeProfileStore(listOf(OWNER, MIRA, YURI, KID))
    private val locks = FakeProfileLocks().apply {
        ownerId = OWNER.id
        locks[OWNER.id] = LockSecret.Sequence(SEQUENCE)
    }
    private val video = testVideo("aaaaaaaaaaa", uploaderName = "fern")
    private val channel = BlockTarget.Channel(checkNotNull(video.uploaderUrl), "fern")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun aKidsProfilesMenuHasNoBlock() = runTest(dispatcher) {
        val vm = menuOpenAs(KID)

        assertNull(vm.menu.value?.blockers)
        vm.onBlock(channel)
        assertEquals(MenuStage.Options, vm.menu.value?.stage)
    }

    @Test
    fun theOwnerBlocksInAllProfilesAtOnceWithoutItsLock() = runTest(dispatcher) {
        val vm = menuOpenAs(OWNER)

        vm.onBlock(channel)
        vm.onBlockAll()
        advanceUntilIdle()

        val rule = BlockedChannel(channel.channelUrl, "fern")
        assertEquals(listOf(StoredRule(rule, byOwner = false, addedAtMillis = 0)), rulesOf(OWNER))
        listOf(MIRA, YURI, KID).forEach { assertEquals(listOf(StoredRule(rule, byOwner = true, addedAtMillis = 0)), rulesOf(it)) }
        assertTrue(locks.tries.isEmpty())
        assertNull(vm.menu.value)
        assertEquals(BlockNotice(channel, listOf("Arshad", "Mira", "Yuri", "Noor"), everywhere = true), vm.notice.value)
    }

    @Test
    fun choosingProfilesStartsWithYoursTickedAndBlocksOnlyThereWithoutALock() = runTest(dispatcher) {
        val vm = menuOpenAs(MIRA)

        vm.onBlock(BlockTarget.Video(video, shownTitle = null))
        vm.onBlockChoose()
        assertEquals(setOf(MIRA.id), (vm.menu.value?.stage as MenuStage.Block).chosen)
        vm.onBlockChosen()
        advanceUntilIdle()

        val rule = BlockedVideo(video.id.value, video.title, video.uploaderUrl)
        assertEquals(listOf(StoredRule(rule, byOwner = false, addedAtMillis = 0)), rulesOf(MIRA))
        assertTrue(rulesOf(OWNER).isEmpty())
        assertEquals(listOf("Mira"), (vm.notice.value as BlockNotice).blockedIn)
    }

    @Test
    fun anotherProfileReachingPastItsOwnEntersTheOwnersLockFirst() = runTest(dispatcher) {
        val vm = menuOpenAs(MIRA)

        vm.onBlock(channel)
        vm.onBlockAll()
        advanceUntilIdle()
        assertEquals(LockKind.Sequence, (vm.menu.value?.stage as MenuStage.BlockGate).lockKind)
        assertTrue(rulesOf(YURI).isEmpty())
        SEQUENCE.forEach(vm::onGatePress)
        vm.onGateSubmitSequence()
        advanceUntilIdle()

        assertEquals(listOf(OWNER.id), locks.tries)
        assertEquals(listOf(false), rulesOf(MIRA).map { it.byOwner })
        assertEquals(listOf(true), rulesOf(YURI).map { it.byOwner })
        assertEquals(true, (vm.notice.value as BlockNotice).everywhere)
    }

    @Test
    fun backFromTheOwnersLockReturnsToTheSheetAsChosen() = runTest(dispatcher) {
        val vm = menuOpenAs(MIRA)
        vm.onBlock(channel)
        vm.onBlockChoose()
        vm.onBlockToggle(YURI.id)
        vm.onBlockChosen()
        advanceUntilIdle()

        vm.onBack()

        assertEquals(MenuStage.Block(channel, chosen = setOf(MIRA.id, YURI.id)), vm.menu.value?.stage)
        vm.onBack()
        assertEquals(MenuStage.Options, vm.menu.value?.stage)
    }

    @Test
    fun leavingTheOwnersLockWithPressesEnteredReturnsToTheSheetAsChosen() = runTest(dispatcher) {
        val vm = menuOpenAs(MIRA)
        vm.onBlock(channel)
        vm.onBlockChoose()
        vm.onBlockToggle(YURI.id)
        vm.onBlockChosen()
        advanceUntilIdle()
        vm.onGatePress(Press.Up)
        vm.onGatePress(Press.Down)

        vm.onGateLeave()

        assertEquals(MenuStage.Block(channel, chosen = setOf(MIRA.id, YURI.id)), vm.menu.value?.stage)
    }

    @Test
    fun leavingWhileATryIsCheckedIsIgnoredAndTheBlockLands() = runTest(dispatcher) {
        val vm = menuOpenAs(MIRA)
        vm.onBlock(channel)
        vm.onBlockAll()
        advanceUntilIdle()
        val checking = CompletableDeferred<Unit>()
        locks.unlockGate = checking
        SEQUENCE.forEach(vm::onGatePress)
        vm.onGateSubmitSequence()
        advanceUntilIdle()

        vm.onGateLeave()
        assertTrue((vm.menu.value?.stage as MenuStage.BlockGate).entry.checking)
        checking.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(true), rulesOf(YURI).map { it.byOwner })
        assertEquals(true, (vm.notice.value as BlockNotice).everywhere)
    }

    @Test
    fun undoTakesBackOnlyWhatTheBlockAdded() = runTest(dispatcher) {
        val rule = BlockedChannel(channel.channelUrl, "fern")
        filters.rulesById.value = mapOf(YURI.id to ProfileRules(rules = listOf(StoredRule(rule, byOwner = true, addedAtMillis = 0))))
        val vm = menuOpenAs(OWNER)
        vm.onBlock(channel)
        vm.onBlockAll()
        advanceUntilIdle()

        vm.onUndoNotice()
        advanceUntilIdle()

        assertTrue(rulesOf(OWNER).isEmpty())
        assertTrue(rulesOf(MIRA).isEmpty())
        assertEquals(listOf<ContentRule>(rule), rulesOf(YURI).map { it.rule })
        assertNull(vm.notice.value)
    }

    @Test
    fun aBlockNothingCouldWriteSaysSoWithNothingToUndo() = runTest(dispatcher) {
        filters.failWrites = true
        val vm = menuOpenAs(OWNER)

        vm.onBlock(channel)
        vm.onBlockAll()
        advanceUntilIdle()

        assertEquals(BlockNotice(channel, emptyList(), everywhere = false), vm.notice.value)
    }

    /** KP-D10: one Approve for / Block for pair per kids profile, in the owner's menu only. */
    @Test
    fun onlyTheOwnersMenuApprovesForKids() = runTest(dispatcher) {
        assertEquals(listOf(KID), menuOpenAs(OWNER).menu.value?.blockers?.kids)

        val mira = menuOpenAs(MIRA)
        assertEquals(emptyList<Profile>(), mira.menu.value?.blockers?.kids)
        mira.onApprove(KID)
        assertEquals(MenuStage.Options, mira.menu.value?.stage)
    }

    @Test
    fun approvingTheVideoWritesItForTheKidAndUndoTakesItBack() = runTest(dispatcher) {
        val vm = menuOpenAs(OWNER)

        vm.onApprove(KID)
        assertEquals(MenuStage.Approve(KID), vm.menu.value?.stage)
        vm.onApproveVideo(shownTitle = null)
        advanceUntilIdle()

        val approval = ApprovedVideo(video.id.value, video.title, "fern", video.uploaderUrl, video.thumbnailUrl, video.durationSeconds)
        assertEquals(listOf(StoredRule(approval, byOwner = true, addedAtMillis = 0)), rulesOf(KID))
        assertEquals(ApproveNotice("Noor", video.title, done = true), vm.notice.value)
        assertNull(vm.menu.value)
        assertTrue(locks.tries.isEmpty())

        vm.onUndoNotice()
        advanceUntilIdle()
        assertTrue(rulesOf(KID).isEmpty())
    }

    /** PAF-D4: the notice names the title the card showed; the approval keeps YouTube's. */
    @Test
    fun approvingTheVideoNamesItAsItsCardShowedIt() = runTest(dispatcher) {
        val vm = menuOpenAs(OWNER)

        vm.onApprove(KID)
        vm.onApproveVideo(shownTitle = "A DeArrow title")
        advanceUntilIdle()

        assertEquals(ApproveNotice("Noor", "A DeArrow title", done = true), vm.notice.value)
        assertEquals(video.title, (rulesOf(KID).single().rule as ApprovedVideo).title)
    }

    @Test
    fun approvingTheChannelAddsItToTheKidsChannelsAndUndoTakesItOff() = runTest(dispatcher) {
        val vm = menuOpenAs(OWNER)

        vm.onApprove(KID)
        vm.onApproveChannel()
        advanceUntilIdle()

        assertEquals(listOf(canonicalChannelUrl(channel.channelUrl)), approvedFor(KID))
        assertEquals(ApproveNotice("Noor", "fern", done = true), vm.notice.value)
        vm.onUndoNotice()
        advanceUntilIdle()
        assertTrue(approvedFor(KID).isEmpty())
    }

    @Test
    fun undoingAChannelApprovedBeforeLeavesItApproved() = runTest(dispatcher) {
        filters.approved.value = mapOf(KID.id to listOf(testSubscription(canonicalChannelUrl(channel.channelUrl), name = "fern")))
        val vm = menuOpenAs(OWNER)

        vm.onApprove(KID)
        vm.onApproveChannel()
        advanceUntilIdle()
        vm.onUndoNotice()
        advanceUntilIdle()

        assertEquals(listOf(canonicalChannelUrl(channel.channelUrl)), approvedFor(KID))
    }

    @Test
    fun anApprovalThatCantBeWrittenSaysSo() = runTest(dispatcher) {
        filters.failWrites = true
        val vm = menuOpenAs(OWNER)

        vm.onApprove(KID)
        vm.onApproveVideo(shownTitle = null)
        advanceUntilIdle()

        assertEquals(ApproveNotice("Noor", video.title, done = false), vm.notice.value)
    }

    @Test
    fun blockForAKidBlocksTheVideoThereAsTheOwnersRestriction() = runTest(dispatcher) {
        val vm = menuOpenAs(OWNER)

        vm.onBlockForKid(KID, SHOWN_TITLE)
        advanceUntilIdle()

        val rule = BlockedVideo(video.id.value, SHOWN_TITLE, video.uploaderUrl)
        assertEquals(listOf(StoredRule(rule, byOwner = true, addedAtMillis = 0)), rulesOf(KID))
        assertTrue(rulesOf(OWNER).isEmpty())
        assertEquals(BlockNotice(BlockTarget.Video(video, SHOWN_TITLE), listOf("Noor"), everywhere = false), vm.notice.value)
    }

    private fun approvedFor(profile: Profile): List<String> = filters.approved.value[profile.id].orEmpty().map { it.channelUrl }

    private fun TestScope.menuOpenAs(profile: Profile): VideoMenuViewModel {
        locks.sessionId = profile.id
        val vm = VideoMenuViewModel(
            FakeWatchStore(),
            FakePlaylistStore(),
            FakeProfileSettingsStore(selectedId = profile.id),
            MenuBlocking(filters, people, locks, TimeSource.Monotonic),
        )
        advanceUntilIdle()
        vm.open(video, VideoSource.Elsewhere)
        advanceUntilIdle()
        return vm
    }

    private fun rulesOf(profile: Profile): List<StoredRule> = filters.rulesById.value[profile.id]?.rules.orEmpty()

    private companion object {
        const val SHOWN_TITLE = "The title the card showed"
        val OWNER = Profile(1, "Arshad", ProfileTint.Amber, isOwner = true, lock = LockKind.Sequence)
        val MIRA = Profile(2, "Mira", ProfileTint.Rose, isOwner = false, lock = LockKind.Pin)
        val YURI = Profile(3, "Yuri", ProfileTint.Teal, isOwner = false, lock = null)
        val KID = Profile(4, "Noor", ProfileTint.Blue, isOwner = false, lock = null, isKids = true)
        val SEQUENCE = listOf(Press.Up, Press.Up, Press.Down, Press.Left, Press.Right, Press.Down)
    }
}
