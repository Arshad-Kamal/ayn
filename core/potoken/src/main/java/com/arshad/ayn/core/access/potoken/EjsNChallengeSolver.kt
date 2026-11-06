package com.arshad.ayn.core.access.potoken

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.arshad.ayn.core.access.NChallengeSolver
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * [NChallengeSolver] on yt-dlp EJS 0.8.0 (THIRD-PARTY.md entry 5; spec 12 KP-D27, KP-D30), run in
 * a Web Worker of one WebView kept between calls. The worker keeps the EJS-preprocessed player of
 * the player code it last prepared, so a solve against that code is one short worker run.
 *
 * Calls block, run one at a time (an extraction asks once per made-for-kids play) and never on
 * the main thread, which the WebView needs. Every wait is bounded. A timeout, a dead renderer or a
 * worker that failed to load drops the WebView, and the next call starts a new one. Failures throw
 * [IllegalStateException], never an `IOException`, which the seam would read as no connection.
 */
class EjsNChallengeSolver(context: Context) : NChallengeSolver {

    private val appContext: Context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val requests = Any()
    private val host = AtomicReference<Host?>()
    private val scripts: Scripts by lazy { Scripts(asset(LIB_ASSET), asset(CORE_ASSET), asset(PAGE_ASSET)) }

    override fun prepare(playerCode: String) {
        synchronized(requests) { prepareLocked(keyOf(playerCode), playerCode) }
    }

    override fun solve(playerCode: String, challenge: String): String = synchronized(requests) {
        val key = keyOf(playerCode)
        val started = SystemClock.elapsedRealtime()
        var reply = send(Request(OP_SOLVE, key, challenge = challenge), SOLVE_TIMEOUT_MS)
        if (reply.unprepared) {
            prepareLocked(key, playerCode)
            reply = send(Request(OP_SOLVE, key, challenge = challenge), SOLVE_TIMEOUT_MS)
        }
        val value = reply.value ?: error("EJS has no answer for n: ${reply.error}")
        check(N_VALUE.matches(value)) { "EJS answered something that isn't an n value" }
        Log.i(TAG, "solved n in ${SystemClock.elapsedRealtime() - started} ms")
        value
    }

    /** Drops the WebView; the next call starts a new one. Any thread; never waits. */
    fun close() {
        host.getAndSet(null)?.let { closing -> main.post { closing.destroy("closed") } }
    }

    private fun prepareLocked(key: String, playerCode: String) {
        val started = SystemClock.elapsedRealtime()
        val reply = send(Request(OP_PREPARE, key, player = playerCode), PREPARE_TIMEOUT_MS)
        check(reply.error == null) { "EJS could not prepare the player: ${reply.error}" }
        Log.i(TAG, "prepared the player in ${SystemClock.elapsedRealtime() - started} ms")
    }

    private fun send(request: Request, timeoutMs: Long): Reply {
        check(Looper.myLooper() != Looper.getMainLooper()) { "the EJS solver blocks; it can't run on the main thread" }
        val current = liveHost()
        val exchange = current.start(request)
        val outcome = exchange.await(timeoutMs) ?: run {
            drop(current)
            error("EJS gave no reply in $timeoutMs ms")
        }
        val json = outcome.getOrElse {
            drop(current)
            error("the EJS WebView failed: ${it.message}")
        }
        return parseReply(json)
    }

    private fun liveHost(): Host {
        host.get()?.takeIf { it.alive }?.let { return it }
        return newHost().also(host::set)
    }

    private fun newHost(): Host {
        val pageScripts = scripts // read off the main thread
        val created = CompletableFuture<Host>()
        main.post {
            try {
                created.complete(Host(pageScripts))
            } catch (e: RuntimeException) {
                created.completeExceptionally(e)
            }
        }
        val started = try {
            created.get(START_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: ExecutionException) {
            throw IllegalStateException("the EJS WebView could not be created", e.cause)
        } catch (e: TimeoutException) {
            throw IllegalStateException("the EJS WebView was not created in $START_TIMEOUT_MS ms", e)
        }
        if (!started.loaded.await(START_TIMEOUT_MS, TimeUnit.MILLISECONDS) || !started.alive) {
            main.post { started.destroy("did not start") }
            error("the EJS page did not start")
        }
        return started
    }

    private fun drop(dropped: Host) {
        host.compareAndSet(dropped, null)
        main.post { dropped.destroy("dropped") }
    }

    private fun asset(path: String): String =
        try {
            appContext.assets.open(path).bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            // Not chained: the seam reads an IOException anywhere in the cause chain as no connection.
            throw IllegalStateException("the EJS asset $path can't be read: ${e.message}")
        }

    /** One WebView running [PAGE_ASSET]; built and destroyed on the main thread. */
    @SuppressLint("SetJavaScriptEnabled")
    private inner class Host(private val pageScripts: Scripts) {
        private val webView = WebView(appContext)
        val loaded = CountDownLatch(1)

        @Volatile
        var alive: Boolean = true
            private set

        @Volatile
        private var pending: Exchange? = null

        /** Main thread only. */
        private var destroyed = false

        init {
            webView.settings.javaScriptEnabled = true
            webView.settings.blockNetworkLoads = true // the solver needs no network
            webView.webViewClient = object : WebViewClient() {
                // Unhandled, a lost renderer crashes or kills the app.
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    destroy("renderer gone")
                    return true
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
            }
            webView.addJavascriptInterface(Bridge(), JS_INTERFACE)
            // A web origin, not the opaque one of a data URL: the page builds its worker from a Blob URL.
            webView.loadDataWithBaseURL(BASE_URL, pageScripts.page, "text/html", "utf-8", null)
        }

        fun start(request: Request): Exchange {
            val exchange = Exchange(request)
            pending = exchange
            main.post {
                if (alive) webView.evaluateJavascript("keEjsSend()", null) else exchange.fail("closed")
            }
            return exchange
        }

        /** Main thread. */
        fun destroy(reason: String) {
            if (destroyed) return
            destroyed = true
            alive = false
            pending?.fail(reason)
            pending = null
            loaded.countDown()
            webView.destroy()
        }

        /** The page's view of the pending request, and its way back. Called on the JavaBridge thread. */
        inner class Bridge {
            @JavascriptInterface
            fun lib(): String = pageScripts.lib

            @JavascriptInterface
            fun core(): String = pageScripts.core

            @JavascriptInterface
            fun op(): String = pending?.request?.op.orEmpty()

            @JavascriptInterface
            fun key(): String = pending?.request?.key.orEmpty()

            @JavascriptInterface
            fun player(): String = pending?.request?.player.orEmpty()

            @JavascriptInterface
            fun challenge(): String = pending?.request?.challenge.orEmpty()

            @JavascriptInterface
            fun ready() {
                loaded.countDown()
            }

            @JavascriptInterface
            fun reply(json: String) {
                pending?.complete(json)
            }

            /** The worker failed to load: this WebView can't solve anything. */
            @JavascriptInterface
            fun failed(message: String) {
                alive = false
                pending?.fail(message.take(MAX_MESSAGE_CHARS))
                loaded.countDown()
            }
        }
    }

    private class Scripts(val lib: String, val core: String, val page: String)

    private class Request(val op: String, val key: String, val player: String = "", val challenge: String = "")

    /** One request's reply (the worker's JSON) or the WebView's failure, whichever comes first. */
    private class Exchange(val request: Request) {
        private val outcome = AtomicReference<Result<String>?>()
        private val done = CountDownLatch(1)

        fun complete(json: String) {
            settle(Result.success(json))
        }

        fun fail(reason: String) {
            settle(Result.failure(IllegalStateException(reason)))
        }

        /** `null` when nothing came within [timeoutMs]. */
        fun await(timeoutMs: Long): Result<String>? =
            if (done.await(timeoutMs, TimeUnit.MILLISECONDS)) outcome.get() else null

        private fun settle(result: Result<String>) {
            if (outcome.compareAndSet(null, result)) done.countDown()
        }
    }

    private class Reply(val unprepared: Boolean, val value: String?, val error: String?)

    private fun parseReply(json: String): Reply {
        check(json.length <= MAX_REPLY_CHARS) { "EJS replied with ${json.length} characters" }
        val reply: JsonObject = try {
            Json.parseToJsonElement(json).jsonObject
        } catch (e: SerializationException) {
            throw IllegalStateException("EJS replied with something that isn't JSON", e)
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException("EJS replied with something that isn't a JSON object", e)
        }
        return Reply(
            unprepared = reply["unprepared"]?.jsonPrimitive?.booleanOrNull == true,
            value = reply["value"]?.jsonPrimitive?.contentOrNull,
            error = reply["error"]?.jsonPrimitive?.contentOrNull?.take(MAX_MESSAGE_CHARS),
        )
    }

    private companion object {
        const val TAG = "KeEjs"
        const val JS_INTERFACE = "KeEjs"
        const val BASE_URL = "https://www.youtube.com/"
        const val LIB_ASSET = "ejs/yt.solver.lib.min.js"
        const val CORE_ASSET = "ejs/yt.solver.core.min.js"
        const val PAGE_ASSET = "ejs/solver.html"
        const val OP_PREPARE = "prepare"
        const val OP_SOLVE = "solve"

        // Measured on bp34 (KP.1a): preprocess 0.65 s, solve from it 0.08 s; generous for a slower TV.
        const val START_TIMEOUT_MS = 15_000L
        const val PREPARE_TIMEOUT_MS = 30_000L
        const val SOLVE_TIMEOUT_MS = 10_000L
        const val MAX_REPLY_CHARS = 4_096
        const val MAX_MESSAGE_CHARS = 200

        /** An `n` answer goes into a URL's query: URL-safe characters only. */
        val N_VALUE = Regex("[A-Za-z0-9_-]{1,64}")

        /** The cache key of one player code (the fork holds one code per process). */
        fun keyOf(playerCode: String): String = "${playerCode.length}:${playerCode.hashCode()}"
    }
}
