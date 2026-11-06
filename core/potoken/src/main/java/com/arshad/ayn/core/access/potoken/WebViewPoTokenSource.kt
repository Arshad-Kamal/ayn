package com.arshad.ayn.core.access.potoken

import android.content.Context
import java.util.concurrent.Executors
import android.util.Log
import com.arshad.ayn.core.access.PoToken
import com.arshad.ayn.core.access.PoTokenSource
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.newpipe.NewPipeAccess
import com.arshad.ayn.core.access.sabr.webPoTokenBytes
import com.github.libretube.api.poToken.BotguardService
import com.github.libretube.api.poToken.PoTokenGenerator
import kotlinx.coroutines.CancellationException
import org.schabi.newpipe.extractor.services.youtube.PoTokenResult

/**
 * [PoTokenSource] on WebView BotGuard minting. The minting machinery is the vendored
 * LibreTube implementation (THIRD-PARTY.md entry 3); this adapter is the app-facing seam.
 *
 * Mints the **web client** token only (upstream behavior); the other client paths return null
 * until playability demands them. Blocking by contract (see [PoTokenSource]): the generator
 * hops to the main thread internally for WebView work. Thread-safe: minting is serialized
 * inside the generator and the cache is its own.
 */
class WebViewPoTokenSource(
    context: Context,
    botguardService: BotguardService = OkHttpBotguardService(),
) : PoTokenSource {

    private val appContext: Context = context.applicationContext
    private val generatorDelegate: Lazy<PoTokenGenerator> = lazy {
        // The vendored minting reads the extractor's process-global transport; install ours only
        // if none is present so a lazy mint can never clobber an installed transport.
        NewPipeAccess.ensureTransportPresent()
        PoTokenGenerator(appContext, botguardService)
    }
    private val generator by generatorDelegate

    override fun webClientPoToken(videoId: VideoId): PoToken? {
        val result = guardedMint(videoId) ?: return null
        val visitorData = result.visitorData ?: return null
        val playerToken = result.playerRequestPoToken ?: return null
        val streamingToken = result.streamingDataPoToken ?: return null
        return PoToken(visitorData, playerToken, streamingToken)
    }

    override fun webEmbedClientPoToken(videoId: VideoId): PoToken? = null

    override fun androidClientPoToken(videoId: VideoId): PoToken? = null

    override fun iosClientPoToken(videoId: VideoId): PoToken? = null

    /**
     * Mint a fresh streaming-token for [videoId] (the minter's own attestation-recovery retry is
     * included) and return its bytes, for a `SabrClient`'s `webPoTokenMint` seam. Playback
     * doesn't wire it: an attestation stop re-resolves instead (`SabrBridge`). The seam's
     * contract is null-on-failure: a mint failure must not throw through the lambda into the
     * SABR load path, where it would surface as a generic load error.
     */
    fun mintStreamingPoToken(videoId: VideoId): ByteArray? =
        guardedMint(videoId)?.streamingDataPoToken?.let(::streamingTokenBytes)

    /** Null-on-failure for both seams ([PoTokenSource] contracts); cancellation flows through. */
    private fun guardedMint(videoId: VideoId): PoTokenResult? =
        try {
            generator.getWebClientPoToken(videoId.value)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "mint failed: ${e::class.java.simpleName}")
            null
        }

    /**
     * Cached streaming-token bytes for a `SabrClient`'s `webPoTokenCache` seam (not wired by
     * playback, as above). Null until the first successful mint; never mints (use
     * [mintStreamingPoToken]).
     */
    fun cachedStreamingPoToken(): ByteArray? =
        generator.getCachedWebClientPoToken()?.streamingDataPoToken?.let(::streamingTokenBytes)

    /**
     * Teardown: close the minting WebView and drop cached tokens. Safe when never minted (the
     * generator stays uncreated); a later mint recreates it through the lazy generator. The close
     * hops off the caller's thread: the vendored `close()` takes the minting monitor, which an
     * in-flight mint can hold across a main-thread WebView wait — running it on main (service
     * `onDestroy`) could deadlock, so it executes on a dedicated daemon thread and simply waits
     * out any in-flight mint.
     */
    fun close() {
        if (generatorDelegate.isInitialized()) closeExecutor.execute { generator.close() }
    }

    private val closeExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ke-potoken-close").apply { isDaemon = true }
    }

}

private const val TAG = "KePoToken"

/** [webPoTokenBytes], logged when the text is not base64url (the seams are null-on-failure). */
private fun streamingTokenBytes(token: String): ByteArray? =
    webPoTokenBytes(token).also { if (it == null) Log.w(TAG, "streaming token is not base64url") }
