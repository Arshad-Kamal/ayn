package com.arshad.ayn.core.lock

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Collects the re-lock signals (M5-D9) and applies [relockDecision]: the activity's start and
 * stop, key presses, leaves, playback, the screensaver's start, and the screen going off. Main
 * thread only.
 *
 * A stop doesn't count as activity: idle time before the screensaver adds to the time in it
 * (§ B3p, "screensaver included"). Leaving on purpose (HOME, which no key callback sees) does:
 * the 5 minutes in the background count from it. Android 11–13 also report the screensaver's
 * start as a leave (its activity starts without `FLAG_ACTIVITY_NO_USER_ACTION`, added in 14), so
 * a leave within [SCREENSAVER_LEAVE_WINDOW] of [onDreamingStarted], either side, is taken back.
 *
 * [elapsed] is a monotonic clock that keeps counting while the device sleeps (Android's
 * `elapsedRealtime`), so wall-clock changes don't move it. [sessionLock] is `null` until the
 * profiles are known; nothing is decided then (a cold start goes through [launchGate]).
 */
class RelockMonitor(
    private val elapsed: () -> Duration,
    private val sessionLock: () -> SessionLock?,
) {
    private val pending = MutableStateFlow(RelockDecision.Stay)

    /** The decision the root gate has yet to act on; it stays until [consume]. */
    val decision: StateFlow<RelockDecision> = pending.asStateFlow()

    /** Why the pending [decision] is a [RelockDecision.Relock]; `null` otherwise. Set before it is
     *  published. */
    var relockReason: RelockReason? = null
        private set

    /** Whether the open profile is locked, so KE hides its content on the way out (M5-D9). */
    val sessionLocked: Boolean get() = sessionLock()?.locked == true

    private val away = MutableStateFlow<Duration?>(null)

    /** While KE is stopped, the last active time when it stopped, which the rules judge the time
     *  away from (moved back if a screensaver's leave is taken back after the stop); `null`
     *  while started. Kept on disk so a restart after a process death can judge the time away
     *  too (M5-D21). */
    val awayFrom: StateFlow<Duration?> = away.asStateFlow()

    /** The re-lock clock now. */
    fun now(): Duration = elapsed()

    private var started = false
    private var playing = false
    private var screenWentOff = false
    private var lastActiveAt = elapsed()

    /** [lastActiveAt] before the latest [onUserInteraction], which a leave arrives with. */
    private var activeBeforeInteraction = lastActiveAt
    private var leave: Leave? = null
    private var dreamStartedAt: Duration? = null

    /** Decides before anything of the resumed UI draws, judging the time away as background
     *  (playback then exempts nothing), then counts the return as activity. While the profiles
     *  are unknown the time away and any screen-off are kept for the next [check]. */
    fun onStart() {
        val decided = check()
        started = true
        leave = null
        dreamStartedAt = null
        if (decided) {
            screenWentOff = false
            lastActiveAt = elapsed()
        }
        away.value = null
    }

    fun onStop() {
        if (playing && started) lastActiveAt = elapsed()
        started = false
        publishAwayIfStopped()
    }

    fun onUserInteraction() {
        activeBeforeInteraction = lastActiveAt
        lastActiveAt = elapsed()
    }

    /** `Activity.onUserLeaveHint`, which always follows an [onUserInteraction] (the leave's
     *  own, so it counts as activity unless the screensaver caused it). */
    fun onUserLeaveHint() {
        leave = Leave(at = elapsed(), activeBefore = activeBeforeInteraction)
        takeBackScreensaverLeave()
    }

    /** The system's `ACTION_DREAMING_STARTED`; it can arrive before or after the leave. */
    fun onDreamingStarted() {
        dreamStartedAt = elapsed()
        takeBackScreensaverLeave()
    }

    private fun takeBackScreensaverLeave() {
        val lastLeave = leave ?: return
        val dreamAt = dreamStartedAt ?: return
        if ((dreamAt - lastLeave.at).absoluteValue <= SCREENSAVER_LEAVE_WINDOW) {
            lastActiveAt = lastLeave.activeBefore
            leave = null
            publishAwayIfStopped()
        }
    }

    private fun publishAwayIfStopped() {
        if (!started) away.value = lastActiveAt
    }

    /** Playback only keeps a session active while KE is in the foreground. */
    fun onPlayingChanged(isPlaying: Boolean) {
        if (started) lastActiveAt = elapsed()
        playing = isPlaying
    }

    fun onScreenOff() {
        screenWentOff = true
        if (started) check()
    }

    /** Applies the rules now; the foreground calls it every [RELOCK_CHECK_INTERVAL]. `false`
     *  when nothing could be decided (the profiles aren't known yet). */
    fun check(): Boolean {
        val lock = sessionLock() ?: return false
        val signals = RelockSignals(elapsed(), lastActiveAt, playingInForeground = playing && started, screenWentOff)
        val decision = relockDecision(lock, signals)
        if (decision > pending.value) {
            relockReason = relockReason(signals).takeIf { decision == RelockDecision.Relock }
            pending.value = decision
        }
        return true
    }

    /** The root gate has shown what [decision] asked for, or opened a profile; the session
     *  counts as active again, and a screen-off before it no longer applies. */
    fun consume() {
        pending.value = RelockDecision.Stay
        relockReason = null
        screenWentOff = false
        lastActiveAt = elapsed()
    }

    private data class Leave(val at: Duration, val activeBefore: Duration)
}

/** How far apart the screensaver's start and the leave it causes may land. A real leave (HOME)
 *  normally can't come this close to it: HOME is user activity, which holds the screensaver off
 *  for its whole timeout, and the screensaver covers KE, so no leave follows it. A screensaver
 *  started by hand right after HOME takes that HOME back too; a wrong take-back only re-locks
 *  sooner, never later. */
internal val SCREENSAVER_LEAVE_WINDOW: Duration = 30.seconds
