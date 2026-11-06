package com.arshad.ayn.core.access

/**
 * Solves YouTube's `n` challenge on stream URLs against the base player script (spec 12 KP-D27,
 * KP-D30): a made-for-kids video's MWEB SABR URL carries one, which the vendored extractor's own
 * solver can no longer read.
 *
 * Blocking by contract (the vendored extractor calls it on the extraction thread, never the main
 * one) and thread-safe. Failures throw, never as an [java.io.IOException]: the seam reads those
 * as no connection.
 */
interface NChallengeSolver {

    /** Readies [playerCode] so a later [solve] against it is fast. */
    fun prepare(playerCode: String)

    /** [challenge]'s answer under [playerCode]. */
    fun solve(playerCode: String, challenge: String): String
}
