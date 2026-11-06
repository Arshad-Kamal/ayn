package com.arshad.ayn.core.sync

import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** [SyncTriggers]' schedule (M8-D8) on virtual time: rounds counted, none run. */
class SyncTriggersTest {
    private val scope = TestScope()
    private val changes = MutableSharedFlow<Any>(extraBufferCapacity = 64)
    private val switches = MutableStateFlow(1L)
    private var open = true
    private var rounds = 0
    private var roundGate: CompletableDeferred<Unit>? = null

    private val triggers = SyncTriggers(
        sync = {
            rounds++
            roundGate?.await()
        },
        scope = scope.backgroundScope,
        localChanges = changes,
        profileSwitches = switches,
        isOpen = { open },
    )

    @Test
    fun startingSyncsOnce() = scope.runTest {
        triggers.start()
        runCurrent()

        assertEquals(1, rounds)
    }

    /** Playback saves its place every 10 s: the first change still goes out a minute later. */
    @Test
    fun aChangeIsPushedAMinuteAfterTheFirstOneEvenWhileChangesKeepComing() = scope.runTest {
        triggers.start()
        runCurrent()
        repeat(6) {
            changes.emit(Unit)
            advanceTimeBy(10.seconds)
        }
        runCurrent()

        assertEquals(2, rounds)
    }

    @Test
    fun aChangeIsntPushedBeforeAMinute() = scope.runTest {
        triggers.start()
        runCurrent()
        changes.emit(Unit)
        advanceTimeBy(59.seconds)

        assertEquals(1, rounds)
    }

    @Test
    fun aChangeIsntPushedWhileClosed() = scope.runTest {
        triggers.start()
        runCurrent()
        open = false
        changes.emit(Unit)
        advanceTimeBy(1.minutes + 1.seconds)

        assertEquals(1, rounds)
    }

    @Test
    fun aProfileSwitchSyncsTheFirstSelectionDoesnt() = scope.runTest {
        triggers.start()
        runCurrent()
        switches.value = 2L
        runCurrent()

        assertEquals(2, rounds)
    }

    @Test
    fun theOtherDevicesAreCheckedEveryFiveMinutesOnlyWhileOpen() = scope.runTest {
        triggers.start()
        runCurrent()
        advanceTimeBy(5.minutes + 1.seconds)
        assertEquals(2, rounds)

        open = false
        advanceTimeBy(5.minutes)
        assertEquals(2, rounds)
    }

    @Test
    fun requestsDuringARoundMakeOneMoreRound() = scope.runTest {
        val gate = CompletableDeferred<Unit>().also { roundGate = it }
        triggers.start()
        runCurrent()
        repeat(3) { triggers.request() }
        gate.complete(Unit)
        runCurrent()

        assertEquals(2, rounds)
    }
}
