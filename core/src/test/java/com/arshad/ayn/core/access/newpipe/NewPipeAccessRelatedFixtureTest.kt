package com.arshad.ayn.core.access.newpipe

import com.arshad.ayn.core.access.AccessException
import com.arshad.ayn.core.access.AccessFailure
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException

/**
 * The related call (FY.2) on recorded WEB `next` answers: the request the video page makes, so
 * its recordings replay under the same fixture key. Its reader mirrors the vendored page reader
 * (two readers of one shape, a tripwire); the parity test is that tripwire's
 * teeth. Goldens are read from the raw payloads into `RecordedCorpus.kt` (TEST-ORACLE-01).
 */
class NewPipeAccessRelatedFixtureTest {

    @Test
    fun related_isOneNextRequestForTheVideo() {
        val capturing = CapturingDownloader(FixtureDownloader(CHAPTERS_VIDEO_FLOW))

        val related = related(capturing, CHAPTERS_VIDEO_ID)

        assertEquals(RecordedRelatedVideos.CHAPTERS_VIDEO_IDS, related.map { it.id.value })
        val youtubei = capturing.requests.filter { "youtubei" in it.url() }
        assertEquals(listOf(WEB_NEXT_KEY), youtubei.map { FixtureKeys.of(it) })
    }

    /** The call's own live recording: every video lockup, in order, each with its channel and length. */
    @Test
    fun related_onItsOwnRecordedAnswer_listsEveryVideoWithItsChannelAndLength() {
        val related = related(FixtureDownloader(RELATED_FLOW), CHAPTERS_VIDEO_ID)

        assertEquals(RecordedRelatedVideos.RELATED_FLOW_IDS, related.map { it.id.value })
        assertEquals(RecordedRelatedVideos.RELATED_FLOW_FIRST_DURATION_SECONDS, related.first().durationSeconds)
        assertTrue(related.all { !it.uploaderUrl.isNullOrBlank() && it.durationSeconds > 0 })
    }

    /** Fixture replay ignores bodies, so the request itself is compared with the page's. */
    @Test
    fun related_sendsTheVideoPagesOwnNextRequest() {
        val page = CapturingDownloader(FixtureDownloader(CHAPTERS_VIDEO_FLOW))
        val call = CapturingDownloader(FixtureDownloader(CHAPTERS_VIDEO_FLOW))

        runBlocking { NewPipeAccess(downloader = page).video(VideoId(CHAPTERS_VIDEO_ID)) }
        related(call, CHAPTERS_VIDEO_ID)

        assertEquals(nextBody(page), nextBody(call))
    }

    /** Both readers on each recorded page: its videos, its live stream (marked) and its mix (dropped). */
    @Test
    fun related_listsWhatTheVideoPageListsOnEveryRecordedAnswer() {
        val pages = listOf(
            Triple(CHAPTERS_VIDEO_FLOW, CHAPTERS_VIDEO_ID, RecordedRelatedVideos.CHAPTERS_VIDEO_IDS.size),
            Triple(LIVE_VIDEO_FLOW, RecordedLiveVideo.ID, RecordedRelatedVideos.LIVE_PAGE_COUNT),
            Triple(STORYBOARDS_VIDEO_FLOW, FIXED_VIDEO_ID, RecordedRelatedVideos.STORYBOARDS_PAGE_VIDEO_COUNT),
        )

        pages.forEach { (flow, id, count) ->
            val related = related(FixtureDownloader(flow), id)
            val pageList = runBlocking { NewPipeAccess(downloader = FixtureDownloader(flow)).video(VideoId(id)) }.relatedVideos

            assertEquals("$flow count", count, related.size)
            assertEquals("$flow list", pageList.withDatesAsPresence(), related.withDatesAsPresence())
        }
    }

    @Test
    fun related_dropsAnUnreadableVideoAlone() {
        val unreadable = CorruptingDownloader(FixtureDownloader(CHAPTERS_VIDEO_FLOW), UNDATED_RELATED_LOCKUP, onlyKey = WEB_NEXT_KEY)

        val related = related(unreadable, CHAPTERS_VIDEO_ID)

        assertEquals(emptyList<Pair<String, String>>(), unreadable.unusedReplacements())
        assertEquals(RecordedRelatedVideos.CHAPTERS_VIDEO_IDS.drop(1), related.map { it.id.value })
    }

    @Test
    fun related_withoutAList_failsAsUnreadable() {
        val withoutList = CorruptingDownloader(FixtureDownloader(CHAPTERS_VIDEO_FLOW), NO_RELATED_LIST, onlyKey = WEB_NEXT_KEY)

        assertFailsAs(AccessFailure.UNREADABLE, withoutList)
        assertEquals(emptyList<Pair<String, String>>(), withoutList.unusedReplacements())
    }

    @Test
    fun related_withNoReadableVideo_failsAsUnreadable() {
        val untitled = CorruptingDownloader(
            FixtureDownloader(CHAPTERS_VIDEO_FLOW),
            "\"lockupMetadataViewModel\":{\"title\":{\"content\"" to "\"lockupMetadataViewModel\":{\"title\":{\"contentGone\"",
            onlyKey = WEB_NEXT_KEY,
        )

        assertFailsAs(AccessFailure.UNREADABLE, untitled)
        assertEquals(emptyList<Pair<String, String>>(), untitled.unusedReplacements())
    }

    /** Every video's length badge gone: nothing For you can use reads as a reshaped answer, not an empty list. */
    @Test
    fun related_withNoUsableVideo_failsAsUnreadable() {
        val lengthless = CorruptingDownloader(
            FixtureDownloader(CHAPTERS_VIDEO_FLOW),
            "\"thumbnailBadgeViewModel\"" to "\"thumbnailBadgeViewModelGone\"",
            onlyKey = WEB_NEXT_KEY,
        )

        assertFailsAs(AccessFailure.UNREADABLE, lengthless)
        assertEquals(emptyList<Pair<String, String>>(), lengthless.unusedReplacements())
    }

    @Test
    fun related_overYouTubesLimit_failsAsRateLimited() {
        assertFailsAs(AccessFailure.RATE_LIMITED, AnsweringDownloader(FixtureDownloader(CHAPTERS_VIDEO_FLOW), ::isNext, ::rateLimited))
    }

    @Test
    fun related_onAnotherHttpError_failsAsUnreadable() {
        assertFailsAs(AccessFailure.UNREADABLE, AnsweringDownloader(FixtureDownloader(CHAPTERS_VIDEO_FLOW), ::isNext) { Response(503, "test", emptyMap(), "", it.url()) })
    }

    /** RATE_LIMITED is the related call's alone (`Extractor` contract). */
    @Test
    fun anotherCall_overYouTubesLimit_staysUnreadable() {
        val access = NewPipeAccess(downloader = AnsweringDownloader(FixtureDownloader("search"), { "youtubei/v1/search" in it.url() }, ::rateLimited))

        try {
            runBlocking { access.search(FIXED_SEARCH_QUERY) }
            fail("a 429 must fail the search")
        } catch (e: AccessException) {
            assertEquals(AccessFailure.UNREADABLE, e.failure)
        }
    }

    private fun assertFailsAs(expected: AccessFailure, downloader: Downloader) {
        try {
            related(downloader, CHAPTERS_VIDEO_ID)
            fail("the related call must fail")
        } catch (e: AccessException) {
            assertEquals(expected, e.failure)
        }
    }

    /** An age in hours ("Streamed 8h ago") is parsed against each reader's own clock, so upload
     *  dates are compared by presence; the text they come from is compared whole. */
    private fun List<VideoItem>.withDatesAsPresence(): List<VideoItem> =
        map { it.copy(uploadDate = it.uploadDate?.let { Instant.fromEpochMilliseconds(0) }) }

    private fun related(downloader: Downloader, id: String): List<VideoItem> = runBlocking {
        NewPipeAccess(downloader = downloader).related(VideoId(id))
    }

    private fun nextBody(downloader: CapturingDownloader): String =
        downloader.requests.single { FixtureKeys.of(it) == WEB_NEXT_KEY }.dataToSend()!!.toString(Charsets.UTF_8)

    private fun isNext(request: Request): Boolean = FixtureKeys.of(request) == WEB_NEXT_KEY

    /** The transport's HTTP 429 as [OkHttpDownloader] throws it (OkHttpDownloaderTest proves the throw);
     *  on the one request only, so a cold JVM's client-version bootstrap still replays. */
    private fun rateLimited(request: Request): Response = throw ReCaptchaException("reCaptcha challenge requested", request.url())
}
