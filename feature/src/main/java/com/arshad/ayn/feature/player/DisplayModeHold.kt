package com.arshad.ayn.feature.player

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * D9 hold (M3-DISPLAY-01): playback holds across a display-mode switch, because a non-seamless
 * switch re-plugs HDMI on the Shield and the becoming-noisy broadcast pauses whatever plays
 * through it. A request pauses a playing [playback]; the switch settles once the display reports
 * the requested mode (capped at [MODE_REPORT_TIMEOUT_MS]) and [SETTLE_MS] passes for the TV's
 * resync, and playback resumes then if it is owed and [Playback.canResume] holds. The screen
 * starts and pauses playback through [play] and [pause], so a start asked for while a switch
 * settles waits for it: the order of the request and the start never matters. A request made
 * while a switch settles carries the hold over and restarts the wait (the content rate can
 * change mid-start: an adaptive selection, or the first tracks read before the new item
 * resolved), so the player is never left paused by its own hold. Cancelling [scope] (the player
 * left) drops the hold without resuming.
 */
class DisplayModeHold(private val scope: CoroutineScope, private val playback: Playback) {

    /** The held player, as the hold sees it. */
    interface Playback {
        val playWhenReady: Boolean

        /** False while the app is in the background: a hold ending then leaves playback paused. */
        val canResume: Boolean

        fun pause()

        fun play()
    }

    private var resumeOwed = false
    private var settling: Job? = null

    /** The screen's start: plays now, or once a settling switch ends. */
    fun play() {
        if (settling?.isActive == true) resumeOwed = true else playback.play()
    }

    /** The screen's pause: a start owed by a settling switch is dropped too. */
    fun pause() {
        resumeOwed = false
        playback.pause()
    }

    /** A mode switch was requested; [awaitModeReported] returns once the display reports it. */
    fun onModeRequested(awaitModeReported: suspend () -> Unit) {
        if (playback.playWhenReady) {
            resumeOwed = true
            playback.pause()
        }
        settle(awaitModeReported)
    }

    /** The content rate changed but needs no switch: a settling switch still ends first. */
    fun onModeKept() {
        if (settling?.isActive == true) settle {}
    }

    private fun settle(awaitModeReported: suspend () -> Unit) {
        settling?.cancel()
        settling = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val modeActive = withTimeoutOrNull(MODE_REPORT_TIMEOUT_MS) { awaitModeReported() } != null
            delay(SETTLE_MS)
            val resume = resumeOwed && playback.canResume
            resumeOwed = false
            Log.i(LOG_TAG, "release: modeActive=$modeActive resume=$resume")
            if (resume) playback.play()
        }
    }

    companion object {
        /** The TV's resync window after the mode is reported (M3-DISPLAY-01's ~2 s). */
        const val SETTLE_MS = 2_000L

        /** A mode the display never reports (switch refused or not signalled) stops waiting here. */
        const val MODE_REPORT_TIMEOUT_MS = 5_000L

        /** Field breadcrumbs for AFR (device passes read the switch timing from these). */
        const val LOG_TAG = "KeAfr"
    }
}
