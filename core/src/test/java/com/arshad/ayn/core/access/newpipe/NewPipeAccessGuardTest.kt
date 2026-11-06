package com.arshad.ayn.core.access.newpipe

import com.arshad.ayn.core.access.AccessException
import com.arshad.ayn.core.access.AccessFailure
import com.arshad.ayn.core.access.BrowseKiosk
import com.arshad.ayn.core.access.VideoId
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ExtractionException

/**
 * The seam's failure wall (M4.2 review): no vendored exception type crosses, cancellation
 * flows through unwrapped (KT-CONC-03), and Android API-band LinkageErrors fail the call,
 * not the process (a tripwire).
 */
class NewPipeAccessGuardTest {

    private class ThrowingDownloader(private val failure: Throwable) : Downloader() {
        override fun execute(request: Request): Response = throw failure
    }

    /** Every request answers 200 with [body]: YouTube reached, its reply unusable. */
    private class AnsweringDownloader(private val body: String) : Downloader() {
        override fun execute(request: Request): Response = Response(200, "OK", emptyMap(), body, request.url())
    }

    @Test
    fun vendoredExtractionFailuresSurfaceAsAccessExceptionWithCause() {
        val access = NewPipeAccess(downloader = ThrowingDownloader(ExtractionException("vendored parse broke")))

        try {
            runBlocking { access.search(FIXED_SEARCH_QUERY) }
            fail("expected AccessException")
        } catch (e: AccessException) {
            assertEquals("extraction failed for search \"$FIXED_SEARCH_QUERY\"", e.message)
            assertTrue(
                "the vendored failure must survive in the cause chain",
                generateSequence<Throwable>(e) { it.cause }.any { it is ExtractionException },
            )
        }
    }

    @Test
    fun uncheckedRuntimeFailuresSurfaceAsAccessException() {
        val access = NewPipeAccess(downloader = ThrowingDownloader(RuntimeException("unchecked")))

        try {
            runBlocking { access.channelFeed("https://www.youtube.com/channel/UCuAXFkgsw1L7xaCfnd5JJOw") }
            fail("expected AccessException")
        } catch (e: AccessException) {
            assertTrue("the failure cause must be preserved", e.cause != null)
        }
    }

    @Test
    fun linkageErrorsFailTheCallNotTheProcess() {
        val access = NewPipeAccess(downloader = ThrowingDownloader(NoSuchMethodError("missing platform method")))

        try {
            runBlocking { access.browse(BrowseKiosk.LIVE) }
            fail("expected AccessException")
        } catch (e: AccessException) {
            assertEquals("extraction incompatible with this device for kiosk live", e.message)
            assertTrue("the failure cause must be preserved", e.cause != null)
        }
    }

    @Test
    fun unreachableTransportReadsAsOffline() {
        val access = NewPipeAccess(downloader = ThrowingDownloader(UnknownHostException("www.youtube.com")))

        val thrown = assertThrows(AccessException::class.java) { runBlocking { access.browse(BrowseKiosk.LIVE) } }

        assertEquals(AccessFailure.OFFLINE, thrown.failure)
    }

    @Test
    fun unparseableAnswerReadsAsUnreadable() {
        val access = NewPipeAccess(downloader = AnsweringDownloader("<html>Something went wrong</html>"))

        val thrown = assertThrows(AccessException::class.java) { runBlocking { access.browse(BrowseKiosk.LIVE) } }

        assertEquals(AccessFailure.UNREADABLE, thrown.failure)
    }

    /** The video call reads YouTube's refusals; an answer it can't parse is still unreadable. */
    @Test
    fun unparseableVideoPageReadsAsUnreadable() {
        val access = NewPipeAccess(downloader = AnsweringDownloader("<html>Something went wrong</html>"))

        val thrown = assertThrows(AccessException::class.java) { runBlocking { access.video(VideoId(FIXED_VIDEO_ID)) } }

        assertEquals(AccessFailure.UNREADABLE, thrown.failure)
    }

    @Test
    fun malformedUrlInTheReplyReadsAsUnreadable() {
        // MalformedURLException is an IOException, raised by the vendored parsers on reply URLs.
        val wrapped = ExtractionException("could not parse url", MalformedURLException("no protocol: //x"))

        assertEquals(AccessFailure.UNREADABLE, AccessException("extraction failed", wrapped).failure)
    }

    @Test
    fun ioFailureWrappedByTheVendoredTreeStillReadsAsOffline() {
        val wrapped = ExtractionException("could not fetch", SocketTimeoutException("read timed out"))

        assertEquals(AccessFailure.OFFLINE, AccessException("extraction failed", wrapped).failure)
    }

    @Test
    fun cancellationCrossesTheWallUnwrapped() {
        val access = NewPipeAccess(downloader = ThrowingDownloader(CancellationException("stopping")))

        try {
            runBlocking { access.search(FIXED_SEARCH_QUERY) }
            fail("expected CancellationException to escape")
        } catch (e: CancellationException) {
            // expected: the wall rethrows cancellation instead of wrapping it (KT-CONC-03)
        }
    }
}
