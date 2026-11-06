package com.arshad.ayn.core.access

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arshad.ayn.core.access.newpipe.NewPipeAccess
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device extraction smoke (regression seam for API-band crashes): the vendored
 * extractor compiles against the JDK, so Android API-band calls (e.g.
 * `URLDecoder.decode(String, Charset)`, API 33+) are invisible to lint and only fail at runtime
 * on older platforms. This test is the API-band check the JVM unit suite cannot be. Opt-in only:
 * `KE_SMOKE=1 ./gradlew :core:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class DeviceExtractionSmokeTest {

    @Test
    fun extractsOnDeviceApiBand() {
        assumeTrue(
            "set KE_SMOKE=1 to run network smoke tests",
            InstrumentationRegistry.getArguments().containsKey("KE_SMOKE"),
        )

        val extractor: Extractor = NewPipeAccess()
        val metadata = runBlocking { extractor.video(VideoId(FIXED_VIDEO_ID)) }

        assertTrue("expected stream candidates", metadata.streams.isNotEmpty())
        assertTrue("expected SABR endpoint", !metadata.serverAbrStreamingUrl.isNullOrBlank())
        // The video-page extras degrade silently to absent: an API-band failure shows only here.
        assertTrue("description missing", metadata.description != null)
        assertTrue("storyboards missing", metadata.storyboards.isNotEmpty())
    }

    /**
     * Listing paths, not just video(): item collectors run different extractor code
     * (thumbnails via `YoutubeParsingHelper`): an API-30 crash once lived only there while
     * `video()` stayed green. Covers the app's browse + search entry points (channel tabs
     * share the same collector path).
     */
    @Test
    fun listsOnDeviceApiBand() {
        assumeTrue(
            "set KE_SMOKE=1 to run network smoke tests",
            InstrumentationRegistry.getArguments().containsKey("KE_SMOKE"),
        )

        val extractor: Extractor = NewPipeAccess()
        val kiosk = runBlocking { extractor.browse(BrowseKiosk.TRENDING_MUSIC) }
        assertTrue("kiosk items, got ${kiosk.items.size}", kiosk.items.isNotEmpty())

        val search = runBlocking { extractor.search(FIXED_SEARCH_QUERY) }
        assertTrue("search items, got ${search.items.size}", search.items.isNotEmpty())
    }

    private companion object {
        const val FIXED_VIDEO_ID = "dQw4w9WgXcQ"
        const val FIXED_SEARCH_QUERY = "nasa"
    }
}
