package com.arshad.ayn.core.access.potoken

import android.content.Context
import android.os.Looper
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import com.github.libretube.api.poToken.BotguardService
import com.github.libretube.api.poToken.PoTokenException
import com.github.libretube.api.poToken.PoTokenWebView
import com.github.libretube.api.poToken.stringToU8
import com.github.libretube.api.poToken.u8ToBase64
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowWebView

/**
 * The vendored minting WebView's lifecycle on Robolectric's `ShadowWebView`: no JavaScript runs,
 * so each test plays the page by calling the `@JavascriptInterface` callbacks itself. The
 * instance is built reflectively (private constructor) with init still pending, the state a
 * renderer loss can hit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PoTokenWebViewTest {

    @Test
    fun twoMintsForOneVideoEachGetTheirOwnToken() {
        val (minter, shadow) = newFixture()
        val first = Mint(minter, VIDEO_ID)
        val firstKey = awaitRequestKey(shadow, previous = null)
        val second = Mint(minter, VIDEO_ID)
        val secondKey = awaitRequestKey(shadow, previous = firstKey)
        assertNotEquals(firstKey, secondKey)
        assertTrue("the token binds to the video id", shadow.lastEvaluatedJavascript.contains(stringToU8(VIDEO_ID)))

        minter.onObtainPoTokenResult(secondKey, "1,2")
        minter.onObtainPoTokenResult(firstKey, "3,4")

        assertEquals(u8ToBase64("3,4"), first.await().getOrThrow())
        assertEquals(u8ToBase64("1,2"), second.await().getOrThrow())
    }

    @Test
    fun renderProcessGoneFailsPendingWorkAndRetiresTheWebView() {
        val (minter, shadow, init) = newFixture()
        val mint = Mint(minter, VIDEO_ID)
        awaitRequestKey(shadow, previous = null)

        val handled = shadow.webViewClient.onRenderProcessGone(webViewOf(minter), GoneDetail)

        assertTrue("reporting the loss as handled keeps the app alive", handled)
        assertTrue(mint.await().exceptionOrNull() is PoTokenException)
        assertTrue(init.isCompleted)
        assertTrue(runCatching { runBlocking { init.await() } }.exceptionOrNull() is PoTokenException)
        assertTrue(shadow.wasDestroyCalled())
        assertTrue("the generator must replace it", minter.isExpired())

        // The generator's recreate path still posts close(): destroy() stays the only call.
        minter.close()
        assertNull("no teardown calls on a WebView whose renderer is gone", shadow.lastLoadedUrl)

        // A late init failure (the unstructured init chain still running) must not resume the
        // finished init again: that would throw on the main thread.
        val uncaught = mutableListOf<Throwable>()
        val previousHandler = Thread.currentThread().uncaughtExceptionHandler
        Thread.currentThread().setUncaughtExceptionHandler { _, e -> uncaught.add(e) }
        try {
            minter.onJsInitializationError("late")
            shadowOf(Looper.getMainLooper()).idle()
        } finally {
            Thread.currentThread().uncaughtExceptionHandler = previousHandler
        }
        assertEquals(emptyList<Throwable>(), uncaught)
    }

    @Test
    fun closeFailsPendingMintsAndLaterMintsFailFast() {
        val (minter, shadow) = newFixture()
        val pending = Mint(minter, VIDEO_ID)
        awaitRequestKey(shadow, previous = null)

        minter.close()

        assertTrue(pending.await().exceptionOrNull() is PoTokenException)
        val late = Mint(minter, VIDEO_ID)
        idleMainUntil { late.done }
        assertTrue(late.await().exceptionOrNull() is PoTokenException)
    }

    @Test
    fun botguardRunsTheHomepageChallengeWithThePagesEventId() {
        val service = FakeBotguardService(
            mapOf(
                "https://www.youtube.com/" to homepage(),
                "https://www.google.com/js/th/WqKOXfWIFxB-XN3hBm5zepers2HYXoMPp6sS78jI_4Y.js" to "var trayride = {};",
            )
        )
        val (minter, shadow) = newFixture(service)

        minter.downloadAndRunBotguard()

        idleMainUntil { shadow.lastEvaluatedJavascript?.contains("runBotGuard(data)") == true }
        val script = shadow.lastEvaluatedJavascript!!
        assertTrue(script.contains("""window.yt = { config_: {"EVENT_ID":"s6TbaoSlOaG-y-8Phv_68AE"} }"""))
        assertTrue("the interpreter fetched from the challenge's URL", script.contains(""""var trayride = {};""""))
        assertTrue(script.contains(""""globalName":"trayride""""))
        assertEquals(service.pages.keys.toList(), service.gets)
    }

    @Test
    fun aFailedHomepageFetchFailsInitAndRetiresTheWebView() {
        val service = FakeBotguardService()
        val (minter, shadow, init) = newFixture(service)

        minter.downloadAndRunBotguard()

        idleMainUntil { init.isCompleted }
        assertTrue(runCatching { runBlocking { init.await() } }.exceptionOrNull() is PoTokenException)
        assertEquals(listOf("https://www.youtube.com/"), service.gets)
        assertTrue(shadow.wasDestroyCalled())
        assertTrue(minter.isExpired())
    }

    private fun homepage(): String =
        javaClass.classLoader!!.getResource("youtube-homepage-attestation.html")!!.readText()

    /** Serves [pages] by URL and records each GET; POSTs answer empty. */
    private class FakeBotguardService(val pages: Map<String, String> = emptyMap()) : BotguardService {
        val gets = java.util.concurrent.CopyOnWriteArrayList<String>()

        override suspend fun post(url: String, data: List<String>): String = ""

        override suspend fun get(url: String): String {
            gets.add(url)
            return pages[url] ?: throw PoTokenException("HTTP 404 for a BotGuard page")
        }
    }

    private data class Fixture(
        val minter: PoTokenWebView,
        val shadow: ShadowWebView,
        val init: Deferred<PoTokenWebView>,
    )

    private fun newFixture(service: BotguardService = FakeBotguardService()): Fixture {
        lateinit var initContinuation: CancellableContinuation<PoTokenWebView>
        val init = CoroutineScope(Dispatchers.Unconfined).async {
            suspendCancellableCoroutine { initContinuation = it }
        }
        val constructor = PoTokenWebView::class.java.getDeclaredConstructor(
            Context::class.java,
            BotguardService::class.java,
            CancellableContinuation::class.java,
        )
        constructor.isAccessible = true
        val minter = constructor.newInstance(
            RuntimeEnvironment.getApplication(),
            service,
            initContinuation,
        )
        return Fixture(minter, shadowOf(webViewOf(minter)), init)
    }

    private fun webViewOf(minter: PoTokenWebView): WebView =
        PoTokenWebView::class.java.getDeclaredField("webView")
            .apply { isAccessible = true }
            .get(minter) as WebView

    /** Runs the main looper until a new mint script is evaluated; returns its request key. */
    private fun awaitRequestKey(shadow: ShadowWebView, previous: String?): String {
        var key: String? = null
        idleMainUntil {
            key = shadow.lastEvaluatedJavascript
                ?.let { KEY_PATTERN.find(it)?.groupValues?.get(1) }
                ?.takeIf { it != previous }
            key != null
        }
        return key!!
    }

    private fun idleMainUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out waiting on the main looper" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }

    /** A mint on its own thread (the real callers block on it); [await] is bounded. */
    private class Mint(minter: PoTokenWebView, videoId: String) {
        @Volatile private var result: Result<String>? = null
        private val thread = Thread {
            result = runCatching { runBlocking { minter.generatePoToken(videoId) } }
        }.apply { start() }

        val done: Boolean get() = result != null

        fun await(): Result<String> {
            thread.join(5_000)
            assertFalse("the mint must complete", thread.isAlive)
            return result!!
        }
    }

    private object GoneDetail : RenderProcessGoneDetail() {
        override fun didCrash(): Boolean = true
        override fun rendererPriorityAtExit(): Int = 0
    }

    private companion object {
        const val VIDEO_ID = "dQw4w9WgXcQ"
        val KEY_PATTERN = Regex("""identifier = "(\d+)"""")
    }
}
