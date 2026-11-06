package com.arshad.ayn.core.lock

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Test

/** The re-lock signals (M5-D9) through [RelockMonitor], on a hand-moved monotonic clock. */
class RelockMonitorTest {
    private var now: Duration = 1.hours
    private var lock: SessionLock? = SessionLock(locked = true, profileCount = 1)
    private val monitor = RelockMonitor(elapsed = { now }, sessionLock = { lock })

    @Test
    fun fiveMinutesInTheBackgroundRelocksOnReturn() {
        monitor.onStart()
        monitor.onUserInteraction()
        monitor.onStop()
        now += 5.minutes

        monitor.onStart()

        assertEquals(RelockDecision.Relock, monitor.decision.value)
    }

    @Test
    fun aShorterTimeInTheBackgroundDoesNot() {
        monitor.onStart()
        monitor.onUserInteraction()
        monitor.onStop()
        now += 4.minutes + 59.seconds

        monitor.onStart()

        assertEquals(RelockDecision.Stay, monitor.decision.value)
    }

    @Test
    fun idleTimeBeforeTheScreensaverAddsToTheTimeInIt() {
        monitor.onStart()
        now += 3.minutes
        monitor.onStop()
        now += 2.minutes

        monitor.onStart()

        assertEquals(RelockDecision.Relock, monitor.decision.value)
    }

    @Test
    fun leavingOnPurposeStartsTheBackgroundTimeFromTheLeave() {
        monitor.onStart()
        now += 4.minutes
        userLeaves()
        monitor.onStop()
        now += 4.minutes

        monitor.onStart()

        assertEquals(RelockDecision.Stay, monitor.decision.value)
    }

    @Test
    fun theScreensaverStartingIsNotALeave() {
        monitor.onStart()
        now += 3.minutes
        userLeaves()
        monitor.onStop()
        now += 1.seconds
        monitor.onDreamingStarted()
        now += 2.minutes

        monitor.onStart()

        assertEquals(RelockDecision.Relock, monitor.decision.value)
    }

    @Test
    fun theScreensaverIsNotALeaveWhenItsBroadcastComesFirst() {
        monitor.onStart()
        now += 3.minutes
        monitor.onDreamingStarted()
        now += 1.seconds
        userLeaves()
        monitor.onStop()
        now += 2.minutes

        monitor.onStart()

        assertEquals(RelockDecision.Relock, monitor.decision.value)
    }

    @Test
    fun theScreensaverCountsFromTheLastKeyPressBeforeIt() {
        monitor.onStart()
        now += 1.minutes
        monitor.onUserInteraction()
        now += 1.minutes
        userLeaves()
        monitor.onStop()
        monitor.onDreamingStarted()
        now += 3.minutes + 59.seconds
        monitor.check()
        assertEquals(RelockDecision.Stay, monitor.decision.value)

        now += 1.seconds
        monitor.check()
        assertEquals(RelockDecision.Relock, monitor.decision.value)
    }

    @Test
    fun aHomeSoonAfterWakingFromTheScreensaverCounts() {
        monitor.onStart()
        now += 3.minutes
        userLeaves()
        monitor.onStop()
        now += 1.seconds
        monitor.onDreamingStarted()
        now += 5.seconds
        monitor.onStart()
        now += 10.seconds
        userLeaves()
        monitor.onStop()
        now += 4.minutes + 55.seconds

        monitor.onStart()

        assertEquals(RelockDecision.Stay, monitor.decision.value)
    }

    @Test
    fun aScreensaverStartingSoonAfterReturningFromHomeLeavesItAlone() {
        monitor.onStart()
        now += 4.minutes
        userLeaves()
        monitor.onStop()
        now += 5.seconds
        monitor.onStart()
        now += 5.seconds
        monitor.onDreamingStarted()
        now += 1.minutes

        monitor.check()

        assertEquals(RelockDecision.Stay, monitor.decision.value)
    }

    @Test
    fun aScreensaverEarlierThanTheWindowLeavesALaterLeaveAlone() {
        monitor.onStart()
        now += 1.minutes
        monitor.onDreamingStarted()
        now += SCREENSAVER_LEAVE_WINDOW + 1.seconds
        userLeaves()
        monitor.onStop()
        now += 4.minutes

        monitor.onStart()

        assertEquals(RelockDecision.Stay, monitor.decision.value)
    }

    @Test
    fun aScreensaverLaterThanTheWindowLeavesTheLeaveAlone() {
        monitor.onStart()
        now += 4.minutes
        userLeaves()
        monitor.onStop()
        now += SCREENSAVER_LEAVE_WINDOW + 1.seconds
        monitor.onDreamingStarted()
        now += 3.minutes

        monitor.onStart()

        assertEquals(RelockDecision.Stay, monitor.decision.value)
    }

    @Test
    fun stoppingWhilePlayingStartsTheIdleClockAtTheStop() {
        monitor.onStart()
        monitor.onPlayingChanged(true)
        now += 1.hours
        monitor.onStop()
        now += 4.minutes

        monitor.onStart()

        assertEquals(RelockDecision.Stay, monitor.decision.value)
    }

    @Test
    fun fiveMinutesIdleInTheForegroundRelocksOnTheCheck() {
        monitor.onStart()
        now += 5.minutes

        monitor.check()

        assertEquals(RelockDecision.Relock, monitor.decision.value)
    }

    @Test
    fun keyPressesKeepTheProfileOpen() {
        monitor.onStart()
        now += 4.minutes
        monitor.onUserInteraction()
        now += 4.minutes

        monitor.check()

        assertEquals(RelockDecision.Stay, monitor.decision.value)
    }

    @Test
    fun playbackInTheForegroundKeepsItOpen() {
        monitor.onStart()
        monitor.onPlayingChanged(true)
        now += 1.hours

        monitor.check()

        assertEquals(RelockDecision.Stay, monitor.decision.value)
    }

    @Test
    fun aPauseStartsTheIdleClock() {
        monitor.onStart()
        monitor.onPlayingChanged(true)
        now += 1.hours
        monitor.onPlayingChanged(false)
        now += 5.minutes - 1.seconds
        monitor.check()
        assertEquals(RelockDecision.Stay, monitor.decision.value)

        now += 1.seconds
        monitor.check()
        assertEquals(RelockDecision.Relock, monitor.decision.value)
    }

    @Test
    fun aPlaybackChangeInTheBackgroundIsNotActivity() {
        monitor.onStart()
        monitor.onStop()
        now += 4.minutes
        monitor.onPlayingChanged(true)
        now += 1.minutes

        monitor.onStart()

        assertEquals(RelockDecision.Relock, monitor.decision.value)
    }

    @Test
    fun playbackInTheBackgroundDoesNotKeepItOpen() {
        monitor.onStart()
        monitor.onPlayingChanged(true)
        monitor.onStop()
        now += 5.minutes

        monitor.onStart()

        assertEquals(RelockDecision.Relock, monitor.decision.value)
        assertEquals(RelockReason.Away, monitor.relockReason)
    }

    @Test
    fun theScreenGoingOffWhileStoppedRelocksOnReturn() {
        monitor.onStart()
        monitor.onStop()
        monitor.onScreenOff()
        now += 10.seconds

        monitor.onStart()

        assertEquals(RelockDecision.Relock, monitor.decision.value)
        assertEquals(RelockReason.ScreenOff, monitor.relockReason)
    }

    /** The reason goes with the decision: a picker decision has none, a consumed relock leaves none. */
    @Test
    fun aRelockReasonLastsUntilItsDecisionIsConsumed() {
        lock = SessionLock(locked = false, profileCount = 2)
        monitor.onStart()
        monitor.onStop()
        now += 30.minutes
        monitor.onStart()
        assertEquals(RelockDecision.Picker to null, monitor.decision.value to monitor.relockReason)

        lock = SessionLock(locked = true, profileCount = 2)
        monitor.onScreenOff()
        assertEquals(RelockDecision.Relock to RelockReason.ScreenOff, monitor.decision.value to monitor.relockReason)

        monitor.consume()
        assertEquals(RelockDecision.Stay to null, monitor.decision.value to monitor.relockReason)
    }

    @Test
    fun theScreenGoingOffInTheForegroundRelocksAtOnce() {
        monitor.onStart()

        monitor.onScreenOff()

        assertEquals(RelockDecision.Relock, monitor.decision.value)
    }

    @Test
    fun theDecisionStaysUntilConsumed() {
        monitor.onStart()
        now += 5.minutes
        monitor.check()
        monitor.onStop()
        monitor.onStart()
        assertEquals(RelockDecision.Relock, monitor.decision.value)

        monitor.consume()
        assertEquals(RelockDecision.Stay, monitor.decision.value)
    }

    @Test
    fun consumingRestartsTheIdleClock() {
        monitor.onStart()
        now += 5.minutes
        monitor.check()
        monitor.consume()
        now += 4.minutes

        monitor.check()

        assertEquals(RelockDecision.Stay, monitor.decision.value)
    }

    @Test
    fun consumingClearsTheScreenGoingOff() {
        monitor.onStart()
        monitor.onScreenOff()
        monitor.consume()

        monitor.check()

        assertEquals(RelockDecision.Stay, monitor.decision.value)
    }

    @Test
    fun aRelockReplacesAPickerStillWaitingButNotTheOtherWayRound() {
        lock = SessionLock(locked = false, profileCount = 2)
        monitor.onStart()
        now += 30.minutes
        monitor.check()
        assertEquals(RelockDecision.Picker, monitor.decision.value)

        lock = SessionLock(locked = true, profileCount = 2)
        monitor.check()
        assertEquals(RelockDecision.Relock, monitor.decision.value)

        lock = SessionLock(locked = false, profileCount = 2)
        monitor.check()
        assertEquals(RelockDecision.Relock, monitor.decision.value)
    }

    @Test
    fun timeAwayBeforeTheProfilesAreKnownIsJudgedOnceTheyAre() {
        lock = null
        monitor.onStop()
        monitor.onScreenOff()
        now += 1.minutes
        monitor.onStart()
        assertEquals(RelockDecision.Stay, monitor.decision.value)

        lock = SessionLock(locked = true, profileCount = 1)
        monitor.check()
        assertEquals(RelockDecision.Relock, monitor.decision.value)
    }

    @Test
    fun nothingIsDecidedBeforeTheProfilesAreKnown() {
        lock = null
        monitor.onStart()
        now += 1.hours

        monitor.check()

        assertEquals(RelockDecision.Stay, monitor.decision.value)
    }

    @Test
    fun theTimeAwayIsJudgedFromIsKnownOnlyWhileStopped() {
        monitor.onStart()
        now += 1.minutes
        monitor.onUserInteraction()
        val lastKey = now
        assertEquals(null, monitor.awayFrom.value)

        now += 1.minutes
        monitor.onStop()
        assertEquals(lastKey, monitor.awayFrom.value)

        monitor.onStart()
        assertEquals(null, monitor.awayFrom.value)
    }

    @Test
    fun theTimeAwayMovesBackWhenTheScreensaverTakesTheLeaveBackAfterTheStop() {
        monitor.onStart()
        val lastKey = now
        now += 3.minutes
        userLeaves()
        monitor.onStop()
        assertEquals(now, monitor.awayFrom.value)

        now += 1.seconds
        monitor.onDreamingStarted()

        assertEquals(lastKey, monitor.awayFrom.value)
    }

    @Test
    fun aTakeBackWhileStartedLeavesNoTimeAway() {
        monitor.onStart()
        now += 1.minutes
        monitor.onDreamingStarted()

        userLeaves()

        assertEquals(null, monitor.awayFrom.value)
    }

    /** The gate acts on onStart's decision before the start completes; that isn't time away. */
    @Test
    fun aDecisionConsumedDuringTheStartLeavesTheTimeAwayAsItWas() {
        monitor.onStart()
        val lastActive = now
        monitor.onStop()
        now += 5.minutes

        monitor.consume()

        assertEquals(lastActive, monitor.awayFrom.value)
    }

    @Test
    fun onlyALockedOpenProfileCountsAsLocked() {
        assertEquals(true, monitor.sessionLocked)

        lock = SessionLock(locked = false, profileCount = 2)
        assertEquals(false, monitor.sessionLocked)

        lock = null
        assertEquals(false, monitor.sessionLocked)
    }

    /** What `Activity.performUserLeaving` calls: HOME, and on Android 11–13 the screensaver. */
    private fun userLeaves() {
        monitor.onUserInteraction()
        monitor.onUserLeaveHint()
    }
}
