// MODIFIED (YouTube KE, 2026-09-27): context and BotguardService injected (was the LibreTubeApp
// singleton + RetrofitInstance); BuildConfig debug guards replaced by DEBUG_LOG and raw token
// values removed from log messages (SEC-LOG); `poToken` cache field is @Volatile (cross-thread
// read/write from the M3.5 wiring). MODIFIED (2026-09-28): added close() teardown.
// MODIFIED (2026-10-02): a retry recreates only the WebView that failed (concurrent failures no
// longer recreate twice), reuses a replacement, and gives up after close() dropped it. See
// THIRD-PARTY.md entry 3.
package com.github.libretube.api.poToken

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import kotlinx.coroutines.runBlocking
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.services.youtube.InnertubeClientRequestInfo
import org.schabi.newpipe.extractor.services.youtube.PoTokenProvider
import org.schabi.newpipe.extractor.services.youtube.PoTokenResult
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper

private const val DEBUG_LOG = false // MODIFIED: was BuildConfig.DEBUG

class PoTokenGenerator(
    private val context: Context,
    private val botguardService: BotguardService,
) : PoTokenProvider {
    private val TAG = PoTokenGenerator::class.simpleName
    private val supportsWebView by lazy { runCatching { CookieManager.getInstance() }.isSuccess }

    private object WebPoTokenGenLock
    private var webPoTokenVisitorData: String? = null
    private var webPoTokenGenerator: PoTokenWebView? = null

    @Volatile // MODIFIED: written on the minting thread, read from SABR/extractor threads.
    private var poToken: PoTokenResult? = null

    fun getCachedWebClientPoToken(): PoTokenResult? = poToken

    /** MODIFIED (YouTube KE, 2026-09-28): explicit teardown — post the minting WebView's close
     *  to the main thread (the same idiom the recreate path above uses) and drop the cached
     *  token, so the playback owner (M4.8) can release the WebView when playback ends. */
    fun close() {
        synchronized(WebPoTokenGenLock) {
            webPoTokenGenerator?.let { Handler(Looper.getMainLooper()).post { it.close() } }
            webPoTokenGenerator = null
            webPoTokenVisitorData = null
            poToken = null
        }
    }

    override fun getWebClientPoToken(videoId: String): PoTokenResult? {
        if (!supportsWebView) {
            return null
        }

        return getWebClientPoToken(videoId, null)
            .also { poToken = it }
    }

    /**
     * @param failed MODIFIED (was `forceRecreate`): the [webPoTokenGenerator] whose mint just
     * failed, recreated only if it is still current; null on the first attempt
     */
    private fun getWebClientPoToken(videoId: String, failed: PoTokenWebView?): PoTokenResult {
        val (poTokenGenerator, visitorData, hasBeenRecreated) =
            synchronized(WebPoTokenGenLock) {
                if (failed != null && webPoTokenGenerator == null) {
                    throw PoTokenException("closed while minting") // close() ran; don't rebuild
                }
                val shouldRecreate = webPoTokenGenerator == null || webPoTokenGenerator === failed || webPoTokenGenerator!!.isExpired()

                if (shouldRecreate) {
                    val innertubeClientRequestInfo = InnertubeClientRequestInfo.ofWebClient()
                    innertubeClientRequestInfo.clientInfo.clientVersion =
                        YoutubeParsingHelper.getClientVersion()

                    webPoTokenVisitorData = YoutubeParsingHelper.getVisitorDataFromInnertube(
                        innertubeClientRequestInfo,
                        NewPipe.getPreferredLocalization(),
                        NewPipe.getPreferredContentCountry(),
                        YoutubeParsingHelper.getYouTubeHeaders(),
                        YoutubeParsingHelper.YOUTUBEI_V1_URL,
                        null,
                        false
                    )

                    runBlocking {
                        // close the current webPoTokenGenerator on the main thread
                        webPoTokenGenerator?.let { Handler(Looper.getMainLooper()).post { it.close() } }

                        // create a new webPoTokenGenerator
                        webPoTokenGenerator = PoTokenWebView
                            .newPoTokenGenerator(context, botguardService)
                    }
                }

                return@synchronized Triple(
                    webPoTokenGenerator!!,
                    webPoTokenVisitorData!!,
                    shouldRecreate
                )
            }

        val poToken = try {
            // Not using synchronized here, since poTokenGenerator would be able to generate
            // multiple poTokens in parallel if needed. The only important thing is for exactly one
            // visitorData/streaming poToken to be generated before anything else.
            runBlocking {
                poTokenGenerator.generatePoToken(videoId)
            }
        } catch (throwable: Throwable) {
            if (hasBeenRecreated) {
                // the poTokenGenerator has just been recreated (and possibly this is already the
                // second time we try), so there is likely nothing we can do
                throw throwable
            } else {
                // retry, this time recreating the [webPoTokenGenerator] from scratch;
                // this might happen for example if NewPipe goes in the background and the WebView
                // content is lost
                Log.e(TAG, "Failed to obtain poToken, retrying", throwable)
                return getWebClientPoToken(videoId = videoId, failed = poTokenGenerator)
            }
        }


        if (DEBUG_LOG) {
            Log.d(
                TAG, "poToken minted for $videoId" // MODIFIED: no raw token/visitorData in logs
            )
        }

        return PoTokenResult(visitorData, poToken, poToken)
    }

    override fun getWebEmbedClientPoToken(videoId: String?): PoTokenResult? = null

    override fun getAndroidClientPoToken(videoId: String?): PoTokenResult? = null

    override fun getIosClientPoToken(videoId: String?): PoTokenResult? = null
}

