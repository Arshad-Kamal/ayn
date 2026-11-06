package com.arshad.ayn.core.access

/**
 * Typed playback/streaming failure taxonomy (house media3.md PL-6), consumed at the session
 * layer (M3.5). Transport/segment failures stay `IOException`s so `Loader` +
 * `LoadErrorHandlingPolicy` can retry them; this taxonomy classifies what the retry machinery
 * must NOT swallow and what the user sees.
 */
sealed interface PlaybackFailure {
    /** The stream cannot continue (playability denied, format gone). Do not retry as-is. */
    data class ProtocolFatal(val reason: String) : PlaybackFailure

    /** Transient failure with a bounded retry budget (server pressure, incomplete segment).
     *  The retry count lives with the session's budget state (M4.9 `PlaybackRetrySession`),
     *  never on the failure itself. */
    data class Recoverable(val reason: String) : PlaybackFailure

    /** Integrity/attestation challenge or a player reload; needs a new session (a new resolve), then retry. */
    data class Attestation(val reason: String) : PlaybackFailure
}
