package com.arshad.ayn.core.access.sabr

import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.upstream.Loader
import com.arshad.ayn.core.access.PlaybackFailure
import com.github.libretube.player.parser.SabrFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Pins the PL-6 taxonomy mapping the session layer consumes, on the chains Media3 delivers to
 * `onPlayerError`: a source error wraps the SABR data source's `IOException`, which carries the
 * typed `SabrFailure` or the untyped cause; a non-IO throwable from a load arrives inside
 * `Loader.UnexpectedLoaderException`; anything else is an unexpected player error.
 */
@OptIn(UnstableApi::class)
class PlaybackFailureClassifierTest {

    @Test
    fun attestationClassifiesAsAttestation() {
        val error = sabrSourceError(SabrFailure.AttestationRequired())

        assertEquals(PlaybackFailure.Attestation("attestation required"), classifyPlaybackFailure(error))
    }

    @Test
    fun playerReloadClassifiesAsAttestationRecovery() {
        val error = sabrSourceError(SabrFailure.PlayerReloadRequested())

        assertEquals(PlaybackFailure.Attestation("player reload"), classifyPlaybackFailure(error))
    }

    @Test
    fun protocolErrorClassifiesAsProtocolFatal() {
        val error = sabrSourceError(SabrFailure.ProtocolError("3"))

        assertEquals(PlaybackFailure.ProtocolFatal("sabr error"), classifyPlaybackFailure(error))
    }

    @Test
    fun transientTransportCauseClassifiesAsRecoverable() {
        val error = sabrSourceError(java.net.UnknownHostException("dns"))

        assertEquals(PlaybackFailure.Recoverable("IOException"), classifyPlaybackFailure(error))
    }

    @Test
    fun untypedHttpFailureStaysRecoverable() {
        val error = sabrSourceError(Exception("HTTP request failed: 503"))

        assertEquals(PlaybackFailure.Recoverable("IOException"), classifyPlaybackFailure(error))
    }

    @Test
    fun runtimeBugUnderTheDataSourceWrapperClassifiesAsProtocolFatal() {
        val error = sabrSourceError(NullPointerException())

        assertEquals(PlaybackFailure.ProtocolFatal("NullPointerException"), classifyPlaybackFailure(error))
    }

    @Test
    fun runtimeBugTheDataSourceCaughtIsFatalWhateverItsRoot() {
        val error = sabrSourceError(IllegalStateException(IOException()))

        assertEquals(PlaybackFailure.ProtocolFatal("IllegalStateException"), classifyPlaybackFailure(error))
    }

    @Test
    fun cancelledFetchStaysRecoverable() {
        val error = sabrSourceError(java.util.concurrent.CancellationException())

        assertEquals(PlaybackFailure.Recoverable("IOException"), classifyPlaybackFailure(error))
    }

    @Test
    fun runtimeBugThrownOutsideTheDataSourceClassifiesAsProtocolFatal() {
        val error = ExoPlaybackException.createForSource(
            Loader.UnexpectedLoaderException(IllegalStateException()),
            PlaybackException.ERROR_CODE_UNSPECIFIED,
        )

        assertEquals(PlaybackFailure.ProtocolFatal("IllegalStateException"), classifyPlaybackFailure(error))
    }

    @Test
    fun unexpectedPlayerErrorClassifiesAsProtocolFatal() {
        val error = ExoPlaybackException.createForUnexpected(IllegalStateException())

        assertEquals(PlaybackFailure.ProtocolFatal("ExoPlaybackException"), classifyPlaybackFailure(error))
    }

    @Test
    fun causeChainClassesIsBoundedAndRawFree() {
        val error = sabrSourceError(IllegalStateException("secret-remote-text"))

        val summary = causeChainClasses(error)

        assertEquals("ExoPlaybackException>IOException>IllegalStateException", summary)
        assertTrue("raw messages must stay out of the summary", !summary.contains("secret-remote-text"))
    }
}
