package com.arshad.ayn.core.access.newpipe

import com.arshad.ayn.core.access.AccessException
import com.arshad.ayn.core.access.AccessFailure
import com.arshad.ayn.core.access.NChallengeSolver
import com.arshad.ayn.core.access.PoTokenSource
import com.arshad.ayn.core.access.StreamKind
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoMetadata
import com.arshad.ayn.core.access.VideoRefusal
import com.arshad.ayn.core.access.YouTubeRestrict
import com.arshad.ayn.core.access.sabr.requirePlayable
import com.arshad.ayn.core.access.sabr.sabrSessionFor
import com.arshad.ayn.core.access.sabr.sabrStreamSetFrom
import com.arshad.ayn.core.access.sabr.webPoTokenBytes
import com.arshad.ayn.core.store.DEFAULT_MAX_VIDEO_HEIGHT
import com.github.libretube.player.parser.SabrClientIdentity
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import org.schabi.newpipe.extractor.services.youtube.YoutubeMwebClient

/**
 * A made-for-kids video VISIONOS refuses resolves through MWEB (spec 12 KP-D29–D31), on the
 * recorded `video-made-for-kids-mweb` answers: its formats stream over SABR (every recorded format
 * is behind a `signatureCipher`), the SABR URL's `n` is solved, and the session speaks as MWEB with
 * the token the player request carried. Expected values come from the recorded payload.
 */
class NewPipeAccessMwebFixtureTest {

    @Before
    fun coldPlayerCode() {
        YoutubeJavaScriptPlayerManager.clearAllCaches() // the flow's own base.js, not another test's
    }

    @After
    fun uninstallSolver() {
        YoutubeJavaScriptPlayerManager.setThrottlingParameterSolver(null)
        YoutubeJavaScriptPlayerManager.clearAllCaches()
    }

    @Test
    fun madeForKidsVideoStreamsOverSabrWithItsNSolved() {
        assumePlayerCode()
        val solver = FixedNChallengeSolver()

        val metadata = video(solver = solver)

        assertEquals(listOf(RECORDED_SABR_N), solver.challenges)
        assertEquals("the player code is prepared beside the mint", 1, solver.prepares)
        val sabrUrl = metadata.serverAbrStreamingUrl!!
        assertTrue(sabrUrl, sabrUrl.contains("n=${FixedNChallengeSolver.SOLVED_N}&"))
        assertFalse(sabrUrl, sabrUrl.contains(RECORDED_SABR_N))
        assertFalse(metadata.ustreamerConfig.isNullOrBlank())
    }

    @Test
    fun cipheredFormatsAllBecomeTrustedSabrCandidates() {
        assumePlayerCode()
        val metadata = video()

        assertTrue(metadata.streams.all { it.url.startsWith("sabr://") })
        val videoOnly = metadata.streams.filter { it.kind == StreamKind.VIDEO_ONLY }.mapNotNull { it.itag }
        assertTrue(videoOnly.toString(), videoOnly.containsAll(RECORDED_VP9_ITAGS))
        val audio = metadata.streams.filter { it.kind == StreamKind.AUDIO }.mapNotNull { it.itag }
        assertTrue(audio.toString(), audio.contains(RECORDED_OPUS_ITAG))
        val set = sabrStreamSetFrom(metadata, DEFAULT_MAX_VIDEO_HEIGHT)
        requirePlayable(set, MWEB_VIDEO_ID) // the solved SABR URL passes the endpoint trust check
        assertEquals(1080, set.videoStreams.maxOf { it.height ?: 0 })
    }

    @Test
    fun sessionSpeaksAsMwebWithThePlayerRequestsToken() {
        assumePlayerCode()
        val metadata = video()

        val client = metadata.webStreamingClient!!
        assertEquals(MWEB_PLACEHOLDER_PO_TOKEN, client.poToken)
        assertFalse("toString leaves the token out", client.toString().contains(MWEB_PLACEHOLDER_PO_TOKEN))
        val session = sabrSessionFor(metadata)
        val mweb = SabrClientIdentity(
            clientName = 2,
            clientVersion = client.clientVersion,
            deviceMake = "Apple",
            deviceModel = "iPad",
            osName = "iPadOS",
            osVersion = "16.7.10",
            userAgent = YoutubeMwebClient.USER_AGENT,
        )
        assertEquals(mweb, session.identity)
        assertArrayEquals(webPoTokenBytes(MWEB_PLACEHOLDER_PO_TOKEN), session.poToken)
    }

    @Test
    fun restrictedModeHeaderRidesTheMwebPlayerRequest() {
        assumePlayerCode()
        val downloader = RestrictRecordingDownloader(FixtureDownloader(MWEB_VIDEO_FLOW))
        val access = NewPipeAccess(FixedWebPoTokenSource(PLACEHOLDER_WEB_PO_TOKEN), FixedNChallengeSolver(), downloader)

        runBlocking(YouTubeRestrict.context(YouTubeRestrict.STRICT)) { access.video(VideoId(MWEB_VIDEO_ID)) }

        assertEquals(YouTubeRestrict.STRICT, downloader.restrictByKey[MWEB_PLAYER_KEY])
    }

    @Test
    fun anEarlierPagesFailedPlayerCodeFetchDoesNotBlockMweb() {
        assumePlayerCode()
        // The `video` flow holds no player code: its WEB request's fetch fails and is stored.
        runBlocking { NewPipeAccess(downloader = FixtureDownloader("video")).video(VideoId(FIXED_VIDEO_ID)) }
        assertThrows(ParsingException::class.java) { YoutubeJavaScriptPlayerManager.getSignatureTimestamp(FIXED_VIDEO_ID) }

        val metadata = video()

        assertTrue(metadata.webStreamingClient != null)
    }

    @Test
    fun failedMintFailsAsUnreadableNotARefusal() {
        assumePlayerCode()
        assertUnreadable(failure(tokens = FixedWebPoTokenSource(null)))
    }

    @Test
    fun unsolvedNFailsAsUnreadableNotOffline() {
        assumePlayerCode()
        assertUnreadable(failure(solver = FixedNChallengeSolver(solveFailure = IllegalStateException("no answer"))))
    }

    @Test
    fun unpreparedPlayerFailsAsUnreadableNotOffline() {
        assumePlayerCode()
        assertUnreadable(failure(solver = FixedNChallengeSolver(prepareFailure = IllegalStateException("no worker"))))
    }

    @Test
    fun withoutASolverTheMadeForKidsRefusalStands() {
        val failure = failure(solver = null)

        assertEquals(AccessFailure.UNAVAILABLE, failure.failure)
        assertEquals(VideoRefusal.MadeForKids, failure.refusal)
    }

    @Test
    fun otherRefusalsDoNotTryMweb() {
        val solver = FixedNChallengeSolver()
        val access = NewPipeAccess(FixedWebPoTokenSource(PLACEHOLDER_WEB_PO_TOKEN), solver, FixtureDownloader(AGE_RESTRICTED_VIDEO_FLOW))

        val failure = assertThrows(AccessException::class.java) { runBlocking { access.video(VideoId(AGE_RESTRICTED_VIDEO_ID)) } }

        assertEquals(AccessFailure.UNAVAILABLE, failure.failure)
        assertEquals(0, solver.prepares)
    }

    private fun assumePlayerCode() = assumeTrue("no player code in this tree", playerCodeRecorded(MWEB_VIDEO_FLOW))

    private fun video(
        tokens: PoTokenSource = FixedWebPoTokenSource(PLACEHOLDER_WEB_PO_TOKEN),
        solver: NChallengeSolver? = FixedNChallengeSolver(),
    ): VideoMetadata = runBlocking {
        NewPipeAccess(tokens, solver, FixtureDownloader(MWEB_VIDEO_FLOW)).video(VideoId(MWEB_VIDEO_ID))
    }

    private fun failure(
        tokens: PoTokenSource = FixedWebPoTokenSource(PLACEHOLDER_WEB_PO_TOKEN),
        solver: NChallengeSolver? = FixedNChallengeSolver(),
    ): AccessException = assertThrows(AccessException::class.java) { video(tokens, solver) }

    private fun assertUnreadable(failure: AccessException) {
        assertEquals(AccessFailure.UNREADABLE, failure.failure)
        assertNull(failure.refusal)
    }

    /** Remembers the Restricted Mode value each request was made under (the transport reads it the same way). */
    private class RestrictRecordingDownloader(private val delegate: Downloader) : Downloader() {
        val restrictByKey = ConcurrentHashMap<String, String>()

        override fun execute(request: Request): Response {
            restrictByKey[FixtureKeys.of(request)] = YouTubeRestrict.current() ?: "none"
            return delegate.execute(request)
        }
    }

    private companion object {
        /** Read from the recorded MWEB answer's `serverAbrStreamingUrl`. */
        const val RECORDED_SABR_N = "rwv-gtHLSmRJ7nSPVKe"
        val RECORDED_VP9_ITAGS = listOf(248, 247, 244, 243, 242, 278)
        const val RECORDED_OPUS_ITAG = 251
        const val MWEB_PLAYER_KEY = "www.youtube.com_youtubei_v1_player__MWEB"
    }
}
