package com.arshad.ayn.feature.profiles

import com.arshad.ayn.core.kids.KidsClock
import com.arshad.ayn.core.kids.KidsTime
import com.arshad.ayn.core.kids.stopped
import com.arshad.ayn.core.lock.OwnerPass
import com.arshad.ayn.core.store.Profile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Time's up and bedtime on the gate (KP-D8, D9, D26): an open kids shell closes when its time
 * ([kidsClock]) stops and reopens as it was when the time runs again. From the statement, the
 * owner's lock ([ownerAsk], on the kid's tries) gives More time, or Switch profile hands its pass
 * to [leave]. Reads the gate's state through [state] and changes the screen through [show].
 * Main thread, like its host ViewModel.
 */
internal class TimeStoppedSteps(
    private val scope: CoroutineScope,
    private val kidsClock: KidsClock,
    private val entry: LockEntryInput,
    private val ownerAsk: OwnerFromKids,
    private val state: () -> GateUiState,
    private val show: (GateScreen) -> Unit,
    private val leave: suspend (OwnerPass) -> Unit,
) {
    /** The owner's pass for More time, held while its amount is picked. */
    private var moreTimePass: OwnerPass? = null

    /** An open kids shell closes when its time stops, and Time's up gives way to it again when the
     *  time runs (bedtime's end, the next day, More time). */
    fun onTime(time: KidsTime) {
        when (val screen = state().screen) {
            is GateScreen.Open -> if (screen.profile.isKids && time.stopped) show(GateScreen.TimeStopped(screen.profile, time))
            is GateScreen.TimeStopped -> when {
                !time.stopped -> reopen(screen.kid)
                time != screen.time -> show(screen.copy(time = time))
            }
            else -> Unit
        }
    }

    /** More time: the owner's lock, then the amount. */
    fun onMoreTime() {
        askOnStatement(StoppedAsk.MoreTime, opened = ::pickMoreTime)
    }

    /** Switch profile: the owner's lock, then out to the picker as the rail's Leave does
     *  (unpinned, playback ended, the owner focused). */
    fun onSwitchProfile() {
        askOnStatement(StoppedAsk.SwitchProfile, opened = leave)
    }

    /** [minutes] more today ([MORE_TIME_MINUTES]); once saved the kid's shell reopens. */
    fun onMoreTimePick(minutes: Int) {
        val stopped = state().screen as? GateScreen.TimeStopped ?: return
        val pick = stopped.step as? StoppedStep.Pick ?: return
        val pass = moreTimePass ?: return
        if (pick.adding || minutes !in MORE_TIME_MINUTES) return
        show(stopped.copy(step = pick.copy(adding = true, failed = false)))
        scope.launch {
            val added = kidsClock.addMoreTime(pass, minutes)
            // The time running again may have reopened the shell already.
            val still = state().screen as? GateScreen.TimeStopped ?: return@launch
            when {
                added -> reopen(still.kid)
                still.step is StoppedStep.Pick -> show(still.copy(step = still.step.copy(adding = false, failed = true)))
            }
        }
    }

    /** BACK in More time or Switch profile: the unlock erases first, then each returns to Time's
     *  up (More time's pass goes). Ignored while the grant saves. BACK on Time's up itself closes
     *  Ayn (the UI's). */
    fun onBack() {
        val stopped = state().screen as? GateScreen.TimeStopped ?: return
        when (val step = stopped.step) {
            StoppedStep.Statement -> return
            is StoppedStep.Unlock -> if (entry.back()) return
            is StoppedStep.Pick -> if (step.adding) return
        }
        backToStatement(stopped)
    }

    /** Touch Back in More time or Switch profile (the phone), whose pad has its own erase key:
     *  back to Time's up even with presses entered (they are dropped). Ignored while a try is
     *  checked or the grant saves. */
    fun onLeave() {
        val stopped = state().screen as? GateScreen.TimeStopped ?: return
        when (val step = stopped.step) {
            StoppedStep.Statement -> return
            is StoppedStep.Unlock -> if (step.entry.checking) return
            is StoppedStep.Pick -> if (step.adding) return
        }
        backToStatement(stopped)
    }

    private fun backToStatement(stopped: GateScreen.TimeStopped) {
        entry.clear()
        moreTimePass = null
        show(stopped.copy(step = StoppedStep.Statement))
    }

    /** The kid's shell as it was left: the same opening, so it resumes. */
    private fun reopen(kid: Profile) {
        entry.clear()
        moreTimePass = null
        show(GateScreen.Open(state().profiles.firstOrNull { it.id == kid.id } ?: kid))
    }

    /** Only from Time's up's statement: the owner's lock for [ask], whose pass goes to [opened]. */
    private fun askOnStatement(ask: StoppedAsk, opened: suspend (OwnerPass) -> Unit) {
        val stopped = state().screen as? GateScreen.TimeStopped ?: return
        if (stopped.step != StoppedStep.Statement) return
        val owner = ownerAmong(state().profiles) ?: return
        show(stopped.copy(step = StoppedStep.Unlock(owner, ask)))
        ownerAsk.ask(stopped.kid, owner, asking = { it.stoppedAsk == ask }, opened = opened)
    }

    private suspend fun pickMoreTime(pass: OwnerPass) {
        entry.clear()
        val tomorrow = kidsClock.limitTomorrow()
        val stopped = state().screen as? GateScreen.TimeStopped ?: return
        if (stopped.stoppedAsk != StoppedAsk.MoreTime) return
        moreTimePass = pass
        show(stopped.copy(step = StoppedStep.Pick(tomorrow)))
    }
}

/** What the owner's lock showing on Time's up is asked for, if one shows. */
private val GateScreen.stoppedAsk: StoppedAsk?
    get() = ((this as? GateScreen.TimeStopped)?.step as? StoppedStep.Unlock)?.ask
