package com.arshad.ayn.feature.profiles

import com.arshad.ayn.core.lock.FakeProfileLocks
import com.arshad.ayn.core.lock.LockKind
import com.arshad.ayn.core.lock.LockSecret
import com.arshad.ayn.core.lock.OwnerPass
import com.arshad.ayn.core.lock.Press
import com.arshad.ayn.core.lock.UnlockResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lock-entry input's setup mode (a new lock entered twice, nothing counted) and a try whose
 * pass is the owner's. Its unlock behaviour (hint, tries, waits, erase, a try in flight) is pinned
 * through the gate in [ProfileGateViewModelTest].
 */
class LockEntryInputTest {

    private val locks = FakeProfileLocks().apply { known += PROFILE }
    private var shown: UnlockEntry? = UnlockEntry()

    /** What the setup handed over, saved as the profile's lock the way Settings will. */
    private val saved = mutableListOf<LockSecret>()

    @Test
    fun aSequenceEnteredTwiceBecomesTheLock() = runTest {
        val input = settingUp(LockKind.Sequence)
        enter(input, FIRST)
        assertEquals(UnlockEntry(confirming = true), shown)

        enter(input, FIRST)
        advanceUntilIdle()

        assertEquals(1, saved.size)
        assertTrue(locks.unlock(PROFILE, LockSecret.Sequence(FIRST)) is UnlockResult.Opened)
    }

    @Test
    fun aSecondEntryThatDiffersStartsOverAndSavesNothing() = runTest {
        val input = settingUp(LockKind.Sequence)
        enter(input, FIRST)

        enter(input, SECOND)
        advanceUntilIdle()

        assertEquals(
            UnlockEntry(rejectedDots = SECOND.size, rejections = 1, message = UnlockMessage.Mismatch),
            shown,
        )
        assertEquals(emptyList<LockSecret>(), saved)
        assertEquals(emptyList<Long>(), locks.tries)
    }

    @Test
    fun aSecondPinOneDigitOffStartsOverAndSavesNothing() = runTest {
        val input = settingUp(LockKind.Pin)
        "2461".forEach(input::digit)

        "2462".forEach(input::digit)
        advanceUntilIdle()

        assertEquals(UnlockEntry(rejectedDots = 4, rejections = 1, message = UnlockMessage.Mismatch), shown)
        assertEquals(emptyList<LockSecret>(), saved)
    }

    @Test
    fun afterAMismatchTheFirstEntryIsForgotten() = runTest {
        val input = settingUp(LockKind.Sequence)
        enter(input, FIRST)
        enter(input, SECOND)

        enter(input, SECOND)
        assertEquals(UnlockEntry(confirming = true, rejections = 1), shown)
        assertEquals(emptyList<LockSecret>(), saved)
        enter(input, SECOND)
        advanceUntilIdle()

        assertEquals(1, saved.size)
        assertTrue(locks.unlock(PROFILE, LockSecret.Sequence(SECOND)) is UnlockResult.Opened)
    }

    @Test
    fun aNewPinMovesOnAtItsFourthDigitAndIsTakenAtTheSecondFourth() = runTest {
        val input = settingUp(LockKind.Pin)
        "246".forEach(input::digit)
        assertEquals(3, shown!!.entered)
        input.digit('1')
        assertEquals(UnlockEntry(confirming = true), shown)

        "2461".forEach(input::digit)
        advanceUntilIdle()

        assertTrue(locks.unlock(PROFILE, LockSecret.Pin("2461")) is UnlockResult.Opened)
    }

    @Test
    fun okWithTooFewPressesWhileSettingUpIsAHint() = runTest {
        val input = settingUp(LockKind.Sequence)
        repeat(5) { input.press(Press.Up) }

        input.submitSequence()

        assertEquals(UnlockMessage.TooShort, shown!!.message)
        assertFalse(shown!!.confirming)
        assertEquals(5, shown!!.entered)
    }

    @Test
    fun inputIsOffAndBackIsTakenWhileTheNewLockIsSaved() = runTest {
        val saving = CompletableDeferred<Unit>()
        val input = settingUp(LockKind.Sequence) { saving.await() }
        enter(input, FIRST)
        enter(input, FIRST)
        advanceUntilIdle()
        assertTrue(shown!!.checking)

        input.press(Press.Up)

        assertTrue(input.back()) // taken, so the host stays
        assertEquals(FIRST.size, shown!!.entered)
        saving.complete(Unit)
    }

    @Test
    fun backErasesThenLeavesTheChoiceToTheHost() = runTest {
        val input = settingUp(LockKind.Sequence)
        input.press(Press.Up)

        assertTrue(input.back())
        assertEquals(0, shown!!.entered)
        assertFalse(input.back())
    }

    @Test
    fun nothingIsTakenWhileNoEntryShows() = runTest {
        val input = settingUp(LockKind.Pin)
        shown = null

        input.digit('2')
        shown = UnlockEntry()
        "461".forEach(input::digit)

        assertEquals(3, shown!!.entered)
        assertFalse(shown!!.confirming)
    }

    @Test
    fun theOwnersLockHandsOverAnOwnerPassOnceRight() = runTest {
        locks.ownerId = PROFILE
        locks.locks[PROFILE] = LockSecret.Pin("2461")
        val passes = mutableListOf<OwnerPass>()
        val input = newInput()
        input.startTry(LockKind.Pin, check = locks::unlockOwner, opened = { passes += it })
        "9999".forEach(input::digit)
        advanceUntilIdle()
        assertEquals(UnlockMessage.Wrong(triesBeforeWait = 4), shown!!.message)

        "2461".forEach(input::digit)
        advanceUntilIdle()

        assertEquals(1, passes.size)
        assertEquals(listOf(PROFILE, PROFILE), locks.tries)
    }

    // Helpers

    private fun TestScope.newInput(): LockEntryInput = LockEntryInput(
        scope = this,
        timeSource = testScheduler.timeSource,
        read = { shown },
        write = { change -> shown = shown?.let(change) },
    )

    private fun TestScope.settingUp(
        kind: LockKind,
        beforeSave: suspend () -> Unit = {},
    ): LockEntryInput = newInput().apply {
        startSetup(kind) { secret ->
            beforeSave()
            saved += secret
            locks.setLock(locks.passWithoutLock(PROFILE)!!, secret)
        }
    }

    private fun enter(input: LockEntryInput, sequence: List<Press>) {
        sequence.forEach(input::press)
        input.submitSequence()
    }

    private companion object {
        const val PROFILE = 4L
        val FIRST = listOf(Press.Up, Press.Up, Press.Down, Press.Left, Press.Right, Press.Down)
        val SECOND = listOf(Press.Left, Press.Left, Press.Left, Press.Right, Press.Right, Press.Right, Press.Up)
    }
}
