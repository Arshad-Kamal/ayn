package com.arshad.ayn.feature.player

import com.arshad.ayn.core.access.AccessException
import com.arshad.ayn.core.access.AccessFailure
import com.arshad.ayn.core.access.LivePlayable
import com.arshad.ayn.core.access.PlaybackFailure
import com.arshad.ayn.core.access.VideoMetadata
import com.arshad.ayn.core.access.VideoRefusal
import com.arshad.ayn.core.access.livePlayable
import com.arshad.ayn.core.filter.VideoFiltered
import com.arshad.ayn.core.kids.PlayTimeUp

/**
 * The D8 error-surface mapping (spec `03-tv-ui-mvp.md` § D8 table), pure so every
 * `PlaybackFailure` type → expected UI state is table-tested (the task's verify line).
 * Budgets are named constants (PL-10); the mock pins the recoverable budget at six
 * (m4-09 "Retrying 2 of 6").
 */
object PlayerPolicy {
    /** D8/PL-9: bounded recoverable retries (exhausted → terminal). Mock m4-09's "of 6". */
    const val RECOVERABLE_RETRY_BUDGET = 6

    /** D8/PL-10: bounded attestation refreshes (each a new resolve, so a new SABR session; exhausted → terminal). */
    const val ATTESTATION_RETRY_BUDGET = 3

    /** Terminal reasons carried to the screen (bounded fixed strings, KT-SEC-02). */
    const val TERMINAL_PROTOCOL = "protocol"
    const val TERMINAL_EXHAUSTED = "exhausted"
    const val TERMINAL_ATTESTATION = "attestation"

    /** The video never resolved into a playable stream: YouTube couldn't be reached. */
    const val TERMINAL_RESOLVE_OFFLINE = "resolve-offline"

    /** The video never resolved: YouTube's answer (page or stream manifest) couldn't be used. */
    const val TERMINAL_RESOLVE_UNREADABLE = "resolve-unreadable"

    /** YouTube refused the video (private, removed, age-restricted, region): retrying can't help. */
    const val TERMINAL_UNAVAILABLE = "unavailable"

    /** YouTube refused a made-for-kids video ([VideoRefusal.MadeForKids]). */
    const val TERMINAL_MADE_FOR_KIDS = "made-for-kids"

    /** A premiere or scheduled stream that hasn't begun ([VideoRefusal.NotStarted]); its stop
     *  carries YouTube's sentence as the detail ([resolveStopDetail]). */
    const val TERMINAL_NOT_STARTED = "not-started"

    /** A live broadcast KE can't play: its page names no usable HLS manifest, or a kids profile
     *  asked for it (LIVE-D2, LIVE-D13). */
    const val TERMINAL_LIVE = "live"

    /** LIVE-D3: how far behind the live edge a live item plays (NewPipe, PipePipe and Flow use
     *  10 s); the chrome's edge (LIVE-D23) is the same point. */
    const val LIVE_OFFSET_MS = 10_000L

    /** The session profile's content filters hide the video (KP-D1): retrying can't help. */
    const val TERMINAL_FILTERED = "filtered"

    /** A kids profile's time is up, it's bedtime, or no clock is known yet (KP-D8). */
    const val TERMINAL_TIME_UP = "time-up"
}

/** The resolve's refusal of a live page ([livePlayableOrRefuse]). */
class LiveStreamRefused : Exception("live stream")

/**
 * A live page's playable, judged on the page, not the card (a feed or saved card never says live):
 * its HLS manifest, its variants stopping at [maxShortSide] lines (LIVE-D2, LIVE-D5). A page without
 * a usable one, or any live page on a kids profile (LIVE-D13), is refused before anything is built
 * for it, and its resolve stops as [PlayerPolicy.TERMINAL_LIVE].
 */
fun livePlayableOrRefuse(metadata: VideoMetadata, kidsProfile: Boolean, maxShortSide: Int): LivePlayable {
    if (kidsProfile) throw LiveStreamRefused()
    return livePlayable(metadata, maxShortSide) ?: throw LiveStreamRefused()
}

/** Why a resolve failed, as the stop reason the screen shows. */
fun resolveStopReason(failure: Throwable): String {
    if (failure is LiveStreamRefused) return PlayerPolicy.TERMINAL_LIVE
    if (failure is VideoFiltered) return PlayerPolicy.TERMINAL_FILTERED
    if (failure is PlayTimeUp) return PlayerPolicy.TERMINAL_TIME_UP
    val access = failure as? AccessException
    return when (access?.failure) {
        AccessFailure.OFFLINE -> PlayerPolicy.TERMINAL_RESOLVE_OFFLINE
        AccessFailure.UNAVAILABLE -> when (access.refusal) {
            VideoRefusal.MadeForKids -> PlayerPolicy.TERMINAL_MADE_FOR_KIDS
            is VideoRefusal.NotStarted -> PlayerPolicy.TERMINAL_NOT_STARTED
            null -> PlayerPolicy.TERMINAL_UNAVAILABLE
        }
        // A resolve's video() never answers RATE_LIMITED (the related call alone reads it).
        AccessFailure.UNREADABLE, AccessFailure.RATE_LIMITED, null -> PlayerPolicy.TERMINAL_RESOLVE_UNREADABLE
    }
}

/** What the stop says beyond its reason: YouTube's sentence for a [PlayerPolicy.TERMINAL_NOT_STARTED]
 *  stop (capped at the seam), else null. */
fun resolveStopDetail(failure: Throwable): String? =
    ((failure as? AccessException)?.refusal as? VideoRefusal.NotStarted)?.message

/**
 * The player's error-surface state (D8). [Retrying] and [Refreshing] stay in playback (bounded
 * auto-retry from the kept position); [Terminal] stops playback with its reason.
 */
sealed interface PlayerErrorUiState {
    data object None : PlayerErrorUiState
    data class Retrying(val attempt: Int, val max: Int) : PlayerErrorUiState
    data class Refreshing(val attempt: Int, val max: Int) : PlayerErrorUiState
    data class Terminal(val reason: String) : PlayerErrorUiState
}

/**
 * D8: classify the failure against the attempt count (the number of same-kind retries
 * already spent). Recoverable and Attestation stay in playback within their budgets;
 * protocol-fatal is terminal immediately (no auto-retry).
 */
fun playbackErrorUiState(failure: PlaybackFailure, attemptsSpent: Int): PlayerErrorUiState = when (failure) {
    is PlaybackFailure.Recoverable ->
        if (attemptsSpent < PlayerPolicy.RECOVERABLE_RETRY_BUDGET) {
            PlayerErrorUiState.Retrying(attemptsSpent + 1, PlayerPolicy.RECOVERABLE_RETRY_BUDGET)
        } else {
            PlayerErrorUiState.Terminal(PlayerPolicy.TERMINAL_EXHAUSTED)
        }
    is PlaybackFailure.Attestation ->
        if (attemptsSpent < PlayerPolicy.ATTESTATION_RETRY_BUDGET) {
            PlayerErrorUiState.Refreshing(attemptsSpent + 1, PlayerPolicy.ATTESTATION_RETRY_BUDGET)
        } else {
            PlayerErrorUiState.Terminal(PlayerPolicy.TERMINAL_ATTESTATION)
        }
    is PlaybackFailure.ProtocolFatal -> PlayerErrorUiState.Terminal(PlayerPolicy.TERMINAL_PROTOCOL)
}
