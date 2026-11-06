package com.arshad.ayn.core.access.sabr

import com.arshad.ayn.core.access.PlaybackFailure
import com.github.libretube.player.parser.SabrFailure
import java.io.IOException
import java.util.concurrent.CancellationException

/**
 * Session-layer consumption of the typed failure taxonomy (PL-6, house media3.md M3-PIPE-06):
 * map any playback-path throwable onto [PlaybackFailure] with bounded reason strings only.
 *
 * The SABR chain wraps its failures in an `IOException` carrying the typed cause
 * (`SabrDataSource`'s transport contract), so the cause chain is walked for the type — never
 * for message text (typed errors: `SabrFailure`). Untyped failures are judged by what the first
 * `IOException` in the chain caught (its direct cause: `SabrDataSource`'s wrapper, or Media3's
 * `Loader.UnexpectedLoaderException` for a non-IO throwable escaping a load), never the deepest
 * root: a `RuntimeException` there is a bug in the chain that no retry fixes, except a
 * cancellation (an interrupted fetch); anything else is transport, HTTP or framing, so
 * recoverable. Deeper causes are platform detail: Android's `UnknownHostException` carries
 * `GaiException`, itself a `RuntimeException`.
 */
fun classifyPlaybackFailure(e: Throwable): PlaybackFailure {
    val chain = causeChain(e)
    val sabrFailure = chain.filterIsInstance<SabrFailure>().firstOrNull()
    if (sabrFailure != null) {
        return when (sabrFailure) {
            // Both controls recover by session-level refresh then retry (the PlaybackFailure
            // definitions); the reason tells the session layer what to refresh. Reasons stay
            // fixed strings — the typed failure carries any server-controlled values.
            is SabrFailure.AttestationRequired -> PlaybackFailure.Attestation("attestation required")
            is SabrFailure.PlayerReloadRequested -> PlaybackFailure.Attestation("player reload")
            is SabrFailure.ProtocolError -> PlaybackFailure.ProtocolFatal("sabr error")
        }
    }
    val io = chain.filterIsInstance<IOException>().firstOrNull()
        ?: return PlaybackFailure.ProtocolFatal(e::class.java.simpleName)
    val caught = io.cause
    if (caught is RuntimeException && caught !is CancellationException) {
        return PlaybackFailure.ProtocolFatal(caught::class.java.simpleName)
    }
    return PlaybackFailure.Recoverable(io::class.java.simpleName)
}

/** Bounded cause-class summary for logs (never raw remote messages, KT-SEC-02). */
fun causeChainClasses(e: Throwable): String =
    causeChain(e).joinToString(">") { it::class.java.simpleName }

/** [e] and its causes, outermost first, bounded and safe against a self-cause. */
fun causeChain(e: Throwable): List<Throwable> =
    generateSequence(e) { it.cause?.takeIf { cause -> cause !== it } }
        .take(CAUSE_DEPTH_LIMIT)
        .toList()

private const val CAUSE_DEPTH_LIMIT = 8
