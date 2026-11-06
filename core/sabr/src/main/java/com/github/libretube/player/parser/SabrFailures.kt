// ADDED (YouTube KE, 2026-09-27): typed SABR protocol failures (project media3.md PL-6/D4) —
// the protocol controls SabrClient used to signal as bare `Exception` message strings. Typed so
// the session layer classifies by type, never by message text. See THIRD-PARTY.md entry 2.
package com.github.libretube.player.parser

/**
 * A SABR protocol failure (not a transport failure: those stay `IOException`s so `Loader` +
 * `LoadErrorHandlingPolicy` can act — PL-6). Messages stay byte-identical to the strings they
 * replace so existing bounded logs are unchanged.
 */
sealed class SabrFailure(message: String) : Exception(message) {

    /** Integrity/attestation challenge: needs a fresh po-token, then retry. */
    class AttestationRequired : SabrFailure("Attestation required")

    /** Server-requested player reload: needs a fresh session, then retry. */
    class PlayerReloadRequested : SabrFailure("Server requested player reload")

    /** Protocol-fatal SABR error (playability/format gone); do not retry as-is. */
    class ProtocolError(val type: String) : SabrFailure("SABR error: $type")
}
