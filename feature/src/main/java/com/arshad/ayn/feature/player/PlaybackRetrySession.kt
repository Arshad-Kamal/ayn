package com.arshad.ayn.feature.player

/**
 * The control plane's D8 bookkeeping: retry budgets and the position kept for a terminal
 * Retry, scoped to the video that failed. Pure so the state machine is table-tested
 * (`PlaybackRetrySessionTest`); the service's `PlaybackControlPlane` holds the single instance.
 */
class PlaybackRetrySession {
    var recoverableAttempts: Int = 0
        private set
    var attestationAttempts: Int = 0
        private set
    private var keptForVideoId: String? = null
    private var keptPositionMs: Long? = null

    /** A playback-path failure: remember where this video was. */
    fun recordFailure(videoId: String?, positionMs: Long) {
        keptForVideoId = videoId
        keptPositionMs = positionMs.takeIf { it > 0 }
    }

    /** A bounded retry landed: remember the spend (the mapped attempt number). */
    fun spendRecoverable(attempt: Int) {
        recoverableAttempts = attempt
    }

    fun spendAttestation(attempt: Int) {
        attestationAttempts = attempt
    }

    /**
     * A fresh resolve begins: budgets re-arm, and the kept position applies only when
     * it belongs to [videoId] — consumed either way, so it can never leak into the next
     * video. A same-video terminal Retry gets its position back (MED-5).
     */
    fun begin(videoId: String): Long? {
        rearm()
        val position = keptPositionMs.takeIf { keptForVideoId == videoId }
        keptForVideoId = null
        keptPositionMs = null
        return position
    }

    /** Both budgets back to full: a new video, or a live stream that recovered (LIVE-D4). */
    fun rearm() {
        recoverableAttempts = 0
        attestationAttempts = 0
    }

    /** Where a Retry of [videoId] would resume, without consuming it (0 when nothing is kept). */
    fun keptPositionFor(videoId: String): Long = keptPositionMs.takeIf { keptForVideoId == videoId } ?: 0L

    /** Playback reached READY: any pending kept position is moot. */
    fun clearKeptPosition() {
        keptForVideoId = null
        keptPositionMs = null
    }
}
