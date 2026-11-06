// MODIFIED (YouTube KE, 2026-09-27): severed LibreTube app couplings — RetrofitInstance botguard
// HTTP replaced by the injected [BotguardService] seam (see BotguardService.kt); USER_AGENT is a
// local constant; BuildConfig debug guards replaced by DEBUG_LOG and raw token values removed
// from log messages (security core SEC-LOG). See THIRD-PARTY.md entry 3.
// MODIFIED (YouTube KE, 2026-10-02): a WebViewClient handles render-process loss (unhandled, it
// crashes or kills the app); close() is idempotent and fails pending mints; a closed WebView
// reports expired so the generator recreates it; init and mint waits are bounded and fail as
// PoTokenException; pending mints are keyed per request in a concurrent map (two mints for one
// video id used to overwrite each other).
// MODIFIED (YouTube KE, 2026-10-23): BotGuard runs the homepage's challenge with the page's
// EVENT_ID as yt.config_ (was the jnn Create challenge, whose tokens GVS refuses).
package com.github.libretube.api.poToken

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.MainThread
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class PoTokenWebView private constructor(
    context: Context,
    private val botguardService: BotguardService,
    // MODIFIED: cancellable, so every resume can check it is still pending (main thread only).
    private val generatorContinuation: CancellableContinuation<PoTokenWebView>
) {
    private val webView = WebView(context)
    // MODIFIED: keyed per request, touched from the caller, main and JavaBridge threads.
    private val poTokenContinuations = ConcurrentHashMap<String, CancellableContinuation<String>>()
    private val nextRequestKey = AtomicLong()
    /** MODIFIED: set on the main thread once the WebView is destroyed or its renderer is gone. */
    @Volatile private var closed = false
    private val exceptionHandler = CoroutineExceptionHandler { context, exception ->
        onInitializationError(exception)
    }
    private lateinit var expirationInstant: Instant

    //region Initialization
    init {
        webView.settings.apply {
            //noinspection SetJavaScriptEnabled we want to use JavaScript!
            javaScriptEnabled = true
            safeBrowsingEnabled = false
            userAgentString = BOTGUARD_USER_AGENT
            blockNetworkLoads = true // the WebView does not need internet access
        }

        // MODIFIED: the platform crashes the app on a renderer crash, and kills it with a
        // reclaimed renderer, unless a client reports the loss as handled.
        webView.webViewClient = object : WebViewClient() {
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                onRenderProcessGone()
                return true
            }
        }

        // so that we can run async functions and get back the result
       webView.addJavascriptInterface(this, JS_INTERFACE)
     }

    /**
     * Must be called right after instantiating [PoTokenWebView] to perform the actual
     * initialization. This will asynchronously go through all the steps needed to load BotGuard,
     * run it, and obtain an `integrityToken`.
     */
    private fun loadHtmlAndObtainBotguard(context: Context) {
        if (DEBUG_LOG) {
            Log.d(TAG, "loadHtmlAndObtainBotguard() called")
        }

        CoroutineScope(Dispatchers.IO).launch(exceptionHandler) {
            try {
                val html = context.assets.open("po_token.html").bufferedReader().use { it.readText() }
                withContext(Dispatchers.Main) {
                    webView.loadDataWithBaseURL(
                        "https://www.youtube.com",
                        html.replaceFirst(
                            "</script>",
                            // calls downloadAndRunBotguard() when the page has finished loading
                            "\n$JS_INTERFACE.downloadAndRunBotguard()</script>"
                        ),
                        "text/html",
                        "utf-8",
                        null,
                    )
                }
            } catch (e: Exception) {
                onInitializationError(e)
            }
        }
    }

    /**
     * Called during initialization by the JavaScript snippet appended to the HTML page content in
     * [loadHtmlAndObtainBotguard] after the WebView content has been loaded.
     */
    @JavascriptInterface
    fun downloadAndRunBotguard() {
        if (DEBUG_LOG) {
            Log.d(TAG, "downloadAndRunBotguard() called")
        }

        CoroutineScope(Dispatchers.IO).launch(exceptionHandler) {
            val challenge = parseHomepageChallenge(botguardService.get(HOMEPAGE_URL))
            val challengeData = challenge.runBotGuardArgument(botguardService.get(challenge.interpreterUrl))
            withContext(Dispatchers.Main) {
                if (closed) return@withContext // MODIFIED: closed or timed out during the downloads
                webView.evaluateJavascript(
                    """try {
                             window.yt = { config_: ${challenge.ytConfig} }
                             data = $challengeData
                             runBotGuard(data).then(function (result) {
                                 this.webPoSignalOutput = result.webPoSignalOutput
                                 $JS_INTERFACE.onRunBotguardResult(result.botguardResponse)
                             }, function (error) {
                                 $JS_INTERFACE.onJsInitializationError(error + "\n" + error.stack)
                             })
                         } catch (error) {
                             $JS_INTERFACE.onJsInitializationError(error + "\n" + error.stack)
                         }""",
                    null
                )
            }
        }
    }

    /**
     * Called during initialization by the JavaScript snippets from either
     * [downloadAndRunBotguard] or [onRunBotguardResult].
     */
    @JavascriptInterface
    fun onJsInitializationError(error: String) {
        if (DEBUG_LOG) {
            Log.e(TAG, "Initialization error from JavaScript: $error")
        }
        onInitializationError(PoTokenException(error))
    }

    /**
     * Called during initialization by the JavaScript snippet from [downloadAndRunBotguard] after
     * obtaining the BotGuard execution output [botguardResponse].
     */
    @JavascriptInterface
    fun onRunBotguardResult(botguardResponse: String) {
        CoroutineScope(Dispatchers.IO).launch(exceptionHandler) {
            val response = makeBotguardServiceRequest(
                "https://www.youtube.com/api/jnn/v1/GenerateIT",
                listOf(REQUEST_KEY, botguardResponse)
            )
            val (integrityToken, expirationTimeInSeconds) = parseIntegrityTokenData(response)

            // leave 10 minutes of margin just to be sure
            expirationInstant = Instant.now().plusSeconds(expirationTimeInSeconds - 600)

            withContext(Dispatchers.Main) {
                webView.evaluateJavascript(
                    "this.integrityToken = $integrityToken"
                ) {
                    if (DEBUG_LOG) {
                        Log.d(TAG, "initialization finished, expiration=${expirationTimeInSeconds}s")
                    }
                    if (generatorContinuation.isActive) generatorContinuation.resume(this@PoTokenWebView)
                }
            }
        }
    }
    //endregion

    //region Obtaining poTokens
    suspend fun generatePoToken(identifier: String): String {
        if (DEBUG_LOG) {
            Log.d(TAG, "generatePoToken() called with identifier $identifier")
        }
        // MODIFIED: bounded wait (a lost JavaScript callback used to hang the caller forever);
        // the JS callbacks carry a per-request key, while the token still binds to [identifier].
        return withTimeoutOrNull(MINT_TIMEOUT_MS) { suspendCancellableCoroutine { continuation ->
            val requestKey = nextRequestKey.incrementAndGet().toString()
            poTokenContinuations[requestKey] = continuation
            continuation.invokeOnCancellation { poTokenContinuations.remove(requestKey) }
            val u8Identifier = stringToU8(identifier)

            Handler(Looper.getMainLooper()).post {
                if (closed) {
                    poTokenContinuations.remove(requestKey)?.resumeWithException(PoTokenException("closed"))
                    return@post
                }
                webView.evaluateJavascript(
                    // MODIFIED: the key is emitted as a proper JS string literal (was the raw
                    // identifier spliced into the script — CWE-94 at the interpreter boundary).
                    """try {
                        identifier = ${Json.encodeToString(requestKey)}
                        u8Identifier = $u8Identifier
                        poTokenU8 = obtainPoToken(webPoSignalOutput, integrityToken, u8Identifier)
                        poTokenU8String = ""
                        for (i = 0; i < poTokenU8.length; i++) {
                            if (i != 0) poTokenU8String += ","
                            poTokenU8String += poTokenU8[i]
                        }
                        $JS_INTERFACE.onObtainPoTokenResult(identifier, poTokenU8String)
                    } catch (error) {
                        $JS_INTERFACE.onObtainPoTokenError(identifier, error + "\n" + error.stack)
                    }""",
                ) {}
            }
        } } ?: throw PoTokenException("poToken generation timed out")
    }

    /**
     * Called by the JavaScript snippet from [generatePoToken] when an error occurs in calling the
     * JavaScript `obtainPoToken()` function. MODIFIED: called with the request key.
     */
    @JavascriptInterface
    fun onObtainPoTokenError(requestKey: String, error: String) {
        if (DEBUG_LOG) {
            Log.e(TAG, "obtainPoToken error from JavaScript: $error")
        }
        poTokenContinuations.remove(requestKey)?.resumeWithException(PoTokenException(error))
    }

    /**
     * Called by the JavaScript snippet from [generatePoToken] with the request key (MODIFIED: was
     * the original identifier) and the result of the JavaScript `obtainPoToken()` function.
     */
    @JavascriptInterface
    fun onObtainPoTokenResult(requestKey: String, poTokenU8: String) {
        if (DEBUG_LOG) {
            Log.d(TAG, "Generated poToken (before decoding): requestKey=$requestKey")
        }
        val poToken = try {
            u8ToBase64(poTokenU8)
        } catch (t: Throwable) {
            poTokenContinuations.remove(requestKey)?.resumeWithException(t)
            return
        }

        if (DEBUG_LOG) {
            Log.d(TAG, "Generated poToken: requestKey=$requestKey")
        }
        poTokenContinuations.remove(requestKey)?.resume(poToken)
    }

    fun isExpired(): Boolean {
        // MODIFIED: a closed WebView (renderer gone, failed init) can only be replaced.
        return closed || Instant.now().isAfter(expirationInstant)
    }
    //endregion

    //region Utils
    /**
     * Makes a POST request to [url] with the given [data] through the [botguardService] (which
     * sets the headers). This is supposed to be used only during initialization. Returns the
     * response body as a String.
     */
    private suspend fun makeBotguardServiceRequest(url: String, data: List<String>): String =
        botguardService.post(url, data)

    /**
     * Handles any error happening during initialization, releasing resources and sending the error
     * to [generatorContinuation].
     */
    private fun onInitializationError(error: Throwable) {
        CoroutineScope(Dispatchers.Main).launch {
            close()
            if (generatorContinuation.isActive) generatorContinuation.resumeWithException(error)
        }
    }

    /**
     * MODIFIED: the renderer is gone, so the WebView can only be destroyed; everything waiting
     * on it fails, and [isExpired] makes the generator build a new one.
     */
    @MainThread
    private fun onRenderProcessGone() {
        if (closed) return
        Log.w(TAG, "render process gone")
        closed = true
        webView.destroy()
        val error = PoTokenException("render process gone")
        failPendingMints(error)
        if (generatorContinuation.isActive) generatorContinuation.resumeWithException(error)
    }

    private fun failPendingMints(error: Throwable) {
        poTokenContinuations.keys.toList().forEach { key ->
            poTokenContinuations.remove(key)?.resumeWithException(error)
        }
    }

    /**
     * Releases all [webView] resources. MODIFIED: idempotent, and pending mints fail.
     */
    @MainThread
    fun close() {
        if (closed) return
        closed = true
        failPendingMints(PoTokenException("closed"))
        with(webView) {
            clearHistory()
            // clears RAM cache and disk cache (globally for all WebViews)
            clearCache(true)

            // ensures that the WebView isn't doing anything when destroying it
            loadUrl("about:blank")

            onPause()
            removeAllViews()
            destroy()
        }
    }
    //endregion

    companion object {
        private val TAG = PoTokenWebView::class.simpleName
        private const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
        private const val HOMEPAGE_URL = "https://www.youtube.com/"
        private const val DEBUG_LOG = false // MODIFIED: was BuildConfig.DEBUG
        private val JS_INTERFACE = PoTokenWebView::class.simpleName!!

        // MODIFIED: ceilings for a lost callback, not measured latencies.
        private const val INIT_TIMEOUT_MS = 30_000L
        private const val MINT_TIMEOUT_MS = 10_000L

        suspend fun newPoTokenGenerator(
            context: Context,
            botguardService: BotguardService,
        ): PoTokenWebView {
            // MODIFIED: bounded wait; a timed-out generator's WebView is closed.
            return withTimeoutOrNull(INIT_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    val mainHandler = Handler(Looper.getMainLooper())
                    var poTokenWebView: PoTokenWebView? = null // main thread only
                    mainHandler.post {
                        if (!continuation.isActive) return@post
                        poTokenWebView = PoTokenWebView(context, botguardService, continuation)
                            .also { it.loadHtmlAndObtainBotguard(context) }
                    }
                    continuation.invokeOnCancellation { mainHandler.post { poTokenWebView?.close() } }
                }
            } ?: throw PoTokenException("BotGuard initialization timed out")
        }
    }
}


class PoTokenException(message: String) : Exception(message)