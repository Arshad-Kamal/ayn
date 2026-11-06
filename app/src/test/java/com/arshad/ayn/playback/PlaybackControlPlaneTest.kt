package com.arshad.ayn.playback

import android.net.Uri
import android.os.Bundle
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.source.BehindLiveWindowException
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import com.arshad.ayn.core.access.AccessException
import com.arshad.ayn.core.access.AccessFailure
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.access.livePlayable
import com.arshad.ayn.core.access.newpipe.CHAPTERS_VIDEO_FLOW
import com.arshad.ayn.core.access.newpipe.CHAPTERS_VIDEO_ID
import com.arshad.ayn.core.access.newpipe.FixtureDownloader
import com.arshad.ayn.core.access.newpipe.NewPipeAccess
import com.arshad.ayn.core.access.newpipe.MADE_FOR_KIDS_VIDEO_FLOW
import com.arshad.ayn.core.access.newpipe.MADE_FOR_KIDS_VIDEO_ID
import com.arshad.ayn.core.access.newpipe.PRIVATE_VIDEO_FLOW
import com.arshad.ayn.core.access.newpipe.PRIVATE_VIDEO_ID
import com.arshad.ayn.core.access.newpipe.UPCOMING_VIDEO_FLOW
import com.arshad.ayn.core.access.newpipe.UPCOMING_VIDEO_ID
import com.arshad.ayn.core.access.newpipe.UPCOMING_VIDEO_REASON
import com.arshad.ayn.core.access.newpipe.RecordedChaptersVideo
import com.arshad.ayn.core.access.newpipe.LIVE_VIDEO_FLOW
import com.arshad.ayn.core.access.newpipe.RecordedLiveVideo
import com.arshad.ayn.core.access.sabr.attestationRequiredFailure
import com.arshad.ayn.core.store.PositionSave
import com.arshad.ayn.core.store.ProfileSession
import com.arshad.ayn.feature.player.PlaybackContract
import com.arshad.ayn.feature.player.PlaybackDressing
import com.arshad.ayn.feature.player.PlayerPolicy
import com.arshad.ayn.feature.player.livePlayableOrRefuse
import com.arshad.ayn.feature.player.playbackRequest
import java.io.IOException
import java.net.UnknownHostException
import java.time.Duration
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
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

/**
 * [PlaybackControlPlane] in an in-process session over an inert ExoPlayer, driven through real
 * `MediaController`s the way the player screen drives them. Resolves are deferreds the test
 * completes by hand. Playback failures are injected through [PlaybackControlPlane.onPlaybackFailure]
 * from an IDLE player, the state a failed ExoPlayer reports them in. Position saves and the media
 * ids of the sources the player builds are recorded in call order; the profile session starts on
 * [OWNER].
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackControlPlaneTest {

    private val context = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val releaseActions = mutableListOf<() -> Unit>()

    private val resolves = mutableMapOf<String, CompletableDeferred<MediaItem>>()
    private val cancelledResolves = mutableListOf<String>()
    private val shortResolves = mutableListOf<String>()
    private val uncancellableResolves = mutableSetOf<String>()
    private val sourcesCreated = mutableListOf<String>()
    private val player = inertExoPlayer(context, ItemKeepingSourceFactory { sourcesCreated += it.mediaId })
    private val profiles = ProfileSession().apply { select(OWNER) }
    private val saves = mutableListOf<PositionSave>()
    private val savedWhilePlaying = mutableListOf<Boolean>()
    private val controlPlane = PlaybackControlPlane(player, scope, ::fakeResolve, profiles::currentId) {
        saves += it
        savedWhilePlaying += player.isPlaying
    }
    private val session = MediaSession.Builder(context, player).setId("app").setCallback(controlPlane).build()
        .also { controlPlane.attach(it) }
    private val extrasUpdates = mutableListOf<String>()

    @After
    fun tearDown() {
        releaseActions.asReversed().forEach { it() }
        scope.cancel()
        session.release()
        player.release()
    }

    @Test
    fun aRequestResolvesIntoATitledItemThatPlays() {
        val controller = connect()

        request(controller, VIDEO_A)
        resolve(VIDEO_A)
        idleUntil("title visible") { controller.mediaMetadata.title != null }

        assertEquals(TITLE, controller.mediaMetadata.title.toString())
        assertEquals(VIDEO_A, player.currentMediaItem?.mediaId)
        assertTrue(player.playWhenReady)
    }

    @Test
    fun aPauseSentDuringTheResolveLandsAfterItSoTheVideoArrivesPaused() {
        val controller = connect()
        request(controller, VIDEO_A)

        controller.pause() // the screen's ON_STOP (Home) mid-resolve
        resolve(VIDEO_A)
        idleUntil("item set") { player.mediaItemCount == 1 }
        idleMain()

        assertEquals(VIDEO_A, player.currentMediaItem?.mediaId)
        assertFalse(player.playWhenReady)
    }

    /** The recorded chapters video's page, resolved: the controller sees its dressing only once
     *  the resolve lands; the item it set itself (masked) carries none, which is how the screen
     *  knows the previous video's tracks are still showing. */
    @Test
    fun aResolvedItemCarriesItsPagesDressingToTheController() {
        val metadata = runBlocking {
            NewPipeAccess(downloader = FixtureDownloader(CHAPTERS_VIDEO_FLOW)).video(VideoId(CHAPTERS_VIDEO_ID))
        }
        val controller = connect()
        controller.setMediaItem(playbackRequest(CHAPTERS_VIDEO_ID, short = false))
        idleUntil("resolve requested") { CHAPTERS_VIDEO_ID in resolves }
        assertNull(PlaybackDressing.fromBundle(controller.currentMediaItem?.mediaMetadata?.extras))

        resolves.getValue(CHAPTERS_VIDEO_ID).complete(resolvedItem(metadata, playable = Unit))
        idleUntil("dressing visible") { PlaybackDressing.fromBundle(controller.currentMediaItem?.mediaMetadata?.extras) != null }

        val dressing = PlaybackDressing.fromBundle(controller.currentMediaItem?.mediaMetadata?.extras)!!
        assertEquals(RecordedChaptersVideo.UPLOADER_AVATAR_URL, dressing.uploaderAvatarUrl)
        assertEquals(RecordedChaptersVideo.CHAPTERS, dressing.chapters)
        assertEquals(metadata.storyboards.last(), dressing.storyboard)
        assertEquals(metadata.relatedVideos.inMillis(), dressing.relatedVideos)
        assertEquals(RecordedChaptersVideo.SUBSCRIBER_COUNT, dressing.subscriberCount)
        assertEquals(RecordedChaptersVideo.LIKE_COUNT, dressing.likeCount)
        assertEquals(metadata.description, dressing.description)
        assertEquals("Veritasium", controller.mediaMetadata.artist.toString())
        assertEquals(requireNotNull(metadata.thumbnailUrl), controller.mediaMetadata.artworkUri?.toString())
    }

    @Test
    fun aResolveFailureStopsWithItsKindAndTheNextRequestClearsIt() {
        val controller = connect()
        request(controller, VIDEO_A)

        resolves.getValue(VIDEO_A).completeExceptionally(
            AccessException("extraction failed", IOException("no route"), AccessFailure.OFFLINE),
        )
        idleUntil("stop visible") { stopReason(controller) != null }
        assertEquals(PlayerPolicy.TERMINAL_RESOLVE_OFFLINE, stopReason(controller))
        assertEquals(0, player.mediaItemCount)

        request(controller, VIDEO_A)
        idleUntil("stop cleared") { stopReason(controller) == null }
        assertNull(stopReason(controller))
    }

    @Test
    fun theLastAppControllerLeavingMidResolveCancelsItAndNothingPlays() {
        val controller = connect()
        request(controller, VIDEO_A)

        controller.pause() // the screen pauses before it releases
        controller.release()
        idleUntil("resolve cancelled") { VIDEO_A in cancelledResolves }
        idleMain()

        assertEquals(0, player.mediaItemCount)
        assertFalse(player.playWhenReady)
    }

    @Test
    fun theLastAppControllerLeavingPausesAPlayingItem() {
        val controller = connect()
        request(controller, VIDEO_A)
        resolve(VIDEO_A)
        idleUntil("item playing") { player.mediaItemCount == 1 && player.playWhenReady }

        controller.release()
        idleUntil("paused") { !player.playWhenReady }

        assertFalse(player.playWhenReady)
    }

    @Test
    fun anotherAppControllerLeavingKeepsTheResolve() {
        val screen = connect()
        val other = connect()
        request(screen, VIDEO_A)

        other.release()
        idleMain()
        resolve(VIDEO_A)
        idleUntil("item playing") { player.mediaItemCount == 1 }

        assertFalse(VIDEO_A in cancelledResolves)
        assertTrue(player.playWhenReady)
    }

    @Test
    fun aNewRequestEndsTheCurrentItemBeforeItsResolveLands() {
        val controller = connect()
        request(controller, VIDEO_A)
        resolve(VIDEO_A)
        idleUntil("first item playing") { player.mediaItemCount == 1 }

        request(controller, VIDEO_B)

        assertEquals(0, player.mediaItemCount)
    }

    @Test
    fun aSecondScreensRequestSupersedesAPendingResolve() {
        val leaving = connect()
        request(leaving, VIDEO_A)
        val arriving = connect()

        request(arriving, VIDEO_B)
        resolve(VIDEO_B)
        idleUntil("second item playing") { player.mediaItemCount == 1 }
        idleMain()

        assertTrue(VIDEO_A in cancelledResolves)
        assertEquals(VIDEO_B, player.currentMediaItem?.mediaId)
        assertTrue(player.playWhenReady)
    }

    @Test
    fun aSupersededResolveThatFailsLatePublishesNothing() {
        val leaving = connect()
        uncancellableResolves += VIDEO_A
        request(leaving, VIDEO_A)
        val arriving = connect(listener = extrasRecorder())
        request(arriving, VIDEO_B)

        resolves.getValue(VIDEO_A).completeExceptionally(IOException("extraction failed late"))
        idleMain()
        resolve(VIDEO_B)
        idleUntil("second item playing") { player.mediaItemCount == 1 }
        idleMain()

        assertNull(stopReason(arriving))
        assertFalse(extrasUpdates.any { it.startsWith(STOP) })
        assertEquals(VIDEO_B, player.currentMediaItem?.mediaId)
    }

    @Test
    fun aLeavingScreensQueuedPauseNeverReachesTheNextVideo() {
        val leaving = connect()
        uncancellableResolves += VIDEO_A
        request(leaving, VIDEO_A)
        leaving.pause() // queued behind the pending resolve of A
        val arriving = connect(listener = extrasRecorder())

        request(arriving, VIDEO_B)
        resolve(VIDEO_B)
        idleUntil("second item playing") { player.mediaItemCount == 1 }
        failFromIdle(networkFailure()) // B spends a retry before A's late return
        resolves.getValue(VIDEO_A).complete(resolvedItem(VIDEO_A)) // the superseded extraction returns late
        idleMain()
        failFromIdle(networkFailure())
        idleMain()
        assertTrue("the leaving screen's pause never reached B", player.playWhenReady)
        val savesBefore = saves.size
        leaving.release()
        arriving.release() // the last screen leaving saves the current video's place
        idleUntil("left") { saves.size > savesBefore }

        assertEquals("the late return never takes over B's saves", setOf(VIDEO_B), saves.drop(savesBefore).map { it.videoId.value }.toSet())
        assertEquals(VIDEO_B, player.currentMediaItem?.mediaId)
        val budget = PlayerPolicy.RECOVERABLE_RETRY_BUDGET
        assertEquals(
            "the late return must not re-arm B's budgets",
            listOf("${PlaybackContract.RETRY_RECOVERABLE} 1/$budget", "${PlaybackContract.RETRY_RECOVERABLE} 2/$budget"),
            extrasUpdates.filter { it.startsWith(PlaybackContract.RETRY_RECOVERABLE) },
        )
    }

    @Test
    fun theNextScreenStartsWithoutTheLastVideosStop() {
        val leaving = connect()
        request(leaving, VIDEO_A)
        resolves.getValue(VIDEO_A).completeExceptionally(IOException("extraction failed"))
        idleUntil("stop visible") { stopReason(leaving) != null }

        leaving.release()
        idleMain()
        val arriving = connect()

        assertNull(stopReason(arriving))
    }

    @Test
    fun recoverableFailuresReportRetryProgressThenStopAsExhausted() {
        val controller = connect(listener = extrasRecorder())
        request(controller, VIDEO_A)
        resolve(VIDEO_A)
        idleUntil("item playing") { player.mediaItemCount == 1 }

        repeat(PlayerPolicy.RECOVERABLE_RETRY_BUDGET) {
            failFromIdle(networkFailure())
            assertNotEquals("each retry re-prepares", Player.STATE_IDLE, player.playbackState)
        }
        failFromIdle(networkFailure())
        idleUntil("stop visible") { stopReason(controller) != null }

        val expectedProgress = (1..PlayerPolicy.RECOVERABLE_RETRY_BUDGET)
            .map { "${PlaybackContract.RETRY_RECOVERABLE} $it/${PlayerPolicy.RECOVERABLE_RETRY_BUDGET}" }
        assertEquals(expectedProgress, extrasUpdates.filter { it.startsWith(PlaybackContract.RETRY_RECOVERABLE) })
        assertEquals("$STOP ${PlayerPolicy.TERMINAL_EXHAUSTED}", extrasUpdates.last())
        assertFalse(player.playWhenReady)
    }

    /** M5-D22: the refused SABR session is never prepared again; a new resolve builds a new one. */
    @Test
    fun anAttestationFailureResolvesTheVideoAgainIntoANewSourceWhereItStopped() {
        val controller = connect(listener = extrasRecorder())
        playing(controller, VIDEO_A)
        player.seekTo(KEPT_POSITION_MS)
        idleMain()

        failAttestation(VIDEO_A)
        assertEquals("the refused session stays down", Player.STATE_IDLE, player.playbackState)
        resolve(VIDEO_A)
        idleUntil("prepared again") { player.playbackState != Player.STATE_IDLE }

        assertEquals(listOf(VIDEO_A, VIDEO_A), sourcesCreated)
        assertEquals(listOf("${PlaybackContract.RETRY_ATTESTATION} 1/${PlayerPolicy.ATTESTATION_RETRY_BUDGET}"),
            extrasUpdates.filter { it.startsWith(PlaybackContract.RETRY_ATTESTATION) })
        assertEquals(KEPT_POSITION_MS, player.currentPosition)
        assertTrue(player.playWhenReady)
    }

    @Test
    fun attestationRefreshesSpendOneBudgetAcrossTheirResolvesThenStop() {
        val controller = connect(listener = extrasRecorder())
        playing(controller, VIDEO_A)
        val budget = PlayerPolicy.ATTESTATION_RETRY_BUDGET

        repeat(budget) {
            failAttestation(VIDEO_A)
            resolve(VIDEO_A)
            idleUntil("prepared again") { player.playbackState != Player.STATE_IDLE }
        }
        resolves.remove(VIDEO_A)
        failFromIdle(attestationRequiredFailure())
        idleUntil("stop visible") { stopReason(controller) != null }

        assertEquals((1..budget).map { "${PlaybackContract.RETRY_ATTESTATION} $it/$budget" },
            extrasUpdates.filter { it.startsWith(PlaybackContract.RETRY_ATTESTATION) })
        assertEquals(PlayerPolicy.TERMINAL_ATTESTATION, stopReason(controller))
        assertFalse("no resolve past the budget", VIDEO_A in resolves)
        assertFalse(player.playWhenReady)
    }

    @Test
    fun aRefreshThatCannotResolveStopsWithTheResolvesKindAndKeepsThePlace() {
        val controller = connect()
        playing(controller, VIDEO_A)
        player.seekTo(KEPT_POSITION_MS)
        idleMain()
        failAttestation(VIDEO_A)

        resolves.getValue(VIDEO_A).completeExceptionally(
            AccessException("extraction failed", IOException("no route"), AccessFailure.OFFLINE),
        )
        idleUntil("stop visible") { stopReason(controller) != null }

        assertEquals(PlayerPolicy.TERMINAL_RESOLVE_OFFLINE, stopReason(controller))
        assertEquals(KEPT_POSITION_MS, controller.sessionExtras.getLong(PlaybackContract.EXTRA_POSITION_MS, -1L))
        assertFalse(player.playWhenReady)
    }

    @Test
    fun aRefreshThatCannotResolveKeepsASeekMadeDuringItForRetry() {
        val controller = connect()
        playing(controller, VIDEO_A)
        failAttestation(VIDEO_A)
        controller.seekTo(RESUME_MS)
        idleUntil("seeked") { player.currentPosition == RESUME_MS }

        resolves.getValue(VIDEO_A).completeExceptionally(IOException("extraction failed"))
        idleUntil("stop visible") { stopReason(controller) != null }
        request(controller, VIDEO_A) // Try again
        resolve(VIDEO_A)
        idleUntil("item set again") { player.mediaItemCount == 1 && player.playbackState != Player.STATE_IDLE }

        assertEquals(RESUME_MS, player.currentPosition)
    }

    @Test
    fun aPauseDuringTheRefreshHoldsWhenItLands() {
        val controller = connect()
        playing(controller, VIDEO_A)
        failAttestation(VIDEO_A)

        controller.pause()
        idleUntil("paused") { !player.playWhenReady }
        resolve(VIDEO_A)
        idleUntil("prepared again") { player.playbackState != Player.STATE_IDLE }
        idleMain()

        assertFalse(player.playWhenReady)
    }

    @Test
    fun aSeekDuringTheRefreshIsWhereItLands() {
        val controller = connect()
        playing(controller, VIDEO_A)
        failAttestation(VIDEO_A)

        controller.seekTo(RESUME_MS)
        idleUntil("seeked") { player.currentPosition == RESUME_MS }
        resolve(VIDEO_A)
        idleUntil("prepared again") { player.playbackState != Player.STATE_IDLE }

        assertEquals(RESUME_MS, player.currentPosition)
    }

    /** The refused session prepared again meanwhile (a controller's play) fails again: the
     *  refresh in flight replaces it, so that failure spends nothing and restarts nothing. */
    @Test
    fun aFailureDuringTheRefreshSpendsNothing() {
        val controller = connect(listener = extrasRecorder())
        playing(controller, VIDEO_A)
        failAttestation(VIDEO_A)

        failFromIdle(attestationRequiredFailure())
        resolve(VIDEO_A)
        idleUntil("prepared again") { player.playbackState != Player.STATE_IDLE }

        assertFalse("the refresh wasn't restarted", VIDEO_A in cancelledResolves)
        assertEquals(listOf("${PlaybackContract.RETRY_ATTESTATION} 1/${PlayerPolicy.ATTESTATION_RETRY_BUDGET}"),
            extrasUpdates.filter { it.startsWith(PlaybackContract.RETRY_ATTESTATION) })
    }

    @Test
    fun aRefreshOutrunByANewRequestNeverLandsWhenItsExtractionReturnsLate() {
        val controller = connect()
        uncancellableResolves += VIDEO_A
        playing(controller, VIDEO_A)
        failAttestation(VIDEO_A)

        request(controller, VIDEO_B)
        resolves.getValue(VIDEO_A).complete(resolvedItem(VIDEO_A))
        idleMain()
        resolve(VIDEO_B)
        idleUntil("B playing") { player.currentMediaItem?.mediaId == VIDEO_B && player.isPlaying }
        idleMain()

        assertEquals(listOf(VIDEO_A, VIDEO_B), sourcesCreated)
        assertEquals(VIDEO_B, player.currentMediaItem?.mediaId)
    }

    @Test
    fun theScreenLeavingMidRefreshEndsItSoALateExtractionNeverLands() {
        val controller = connect()
        uncancellableResolves += VIDEO_A
        playing(controller, VIDEO_A)
        failAttestation(VIDEO_A)

        controller.release()
        idleMain()
        resolves.getValue(VIDEO_A).complete(resolvedItem(VIDEO_A))
        idleMain()

        assertEquals(listOf(VIDEO_A), sourcesCreated)
        assertEquals(Player.STATE_IDLE, player.playbackState)
    }

    @Test
    fun endingTheSessionMidRefreshEndsItSoALateExtractionNeverLands() {
        val controller = connect()
        uncancellableResolves += VIDEO_A
        playing(controller, VIDEO_A)
        failAttestation(VIDEO_A)

        controlPlane.endSession()
        resolves.getValue(VIDEO_A).complete(resolvedItem(VIDEO_A))
        idleMain()

        assertEquals(listOf(VIDEO_A), sourcesCreated)
        assertEquals(0, player.mediaItemCount)
    }

    @Test
    fun aRetryClearsOnceThePlayerIsReadyAgain() {
        val controller = connect(listener = extrasRecorder())
        request(controller, VIDEO_A)
        resolve(VIDEO_A)
        idleUntil("item playing") { player.mediaItemCount == 1 }

        failFromIdle(networkFailure())
        idleUntil("ready again") { player.playbackState == Player.STATE_READY }
        idleUntil("retry cleared") { extrasUpdates.lastOrNull() == "" }

        assertEquals(listOf("${PlaybackContract.RETRY_RECOVERABLE} 1/${PlayerPolicy.RECOVERABLE_RETRY_BUDGET}", ""), extrasUpdates)
        assertTrue(controller.sessionExtras.isEmpty)
    }

    @Test
    fun aStopClearsWhenAMediaKeyResumesPlayback() {
        val controller = connect()
        request(controller, VIDEO_A)
        resolve(VIDEO_A)
        idleUntil("item playing") { player.mediaItemCount == 1 }
        failFromIdle(fatalFailure())
        idleUntil("stop visible") { stopReason(controller) != null }

        controller.play() // the play-button path prepares the stopped item again
        idleUntil("ready again") { player.playbackState == Player.STATE_READY }
        idleUntil("stop cleared") { stopReason(controller) == null }

        assertNull(stopReason(controller))
    }

    @Test
    fun aStopAndAFailedRetryOfItBothCarryTheKeptPosition() {
        val controller = connect()
        request(controller, VIDEO_A)
        resolve(VIDEO_A)
        idleUntil("item playing") { player.mediaItemCount == 1 }
        player.seekTo(KEPT_POSITION_MS)
        failFromIdle(fatalFailure())
        idleUntil("stop visible") { stopReason(controller) != null }
        assertEquals(KEPT_POSITION_MS, controller.sessionExtras.getLong(PlaybackContract.EXTRA_POSITION_MS, -1L))

        request(controller, VIDEO_A)
        idleUntil("stop cleared") { stopReason(controller) == null }
        resolves.getValue(VIDEO_A).completeExceptionally(AccessException("offline", IOException("no route"), AccessFailure.OFFLINE))
        idleUntil("stop visible again") { stopReason(controller) != null }

        assertEquals(KEPT_POSITION_MS, controller.sessionExtras.getLong(PlaybackContract.EXTRA_POSITION_MS, -1L))
    }

    /** The recorded private video's refusal, through the resolve, stops as unavailable. */
    @Test
    fun aRefusedVideoStopsAsUnavailable() {
        val controller = stoppedByRecordedRefusal(PRIVATE_VIDEO_FLOW, PRIVATE_VIDEO_ID)

        assertEquals(PlayerPolicy.TERMINAL_UNAVAILABLE, stopReason(controller))
        assertEquals(0L, controller.sessionExtras.getLong(PlaybackContract.EXTRA_POSITION_MS, -1L))
        assertNull(controller.sessionExtras.getString(PlaybackContract.EXTRA_STOP_DETAIL))
    }

    /** The recorded made-for-kids refusal, through the resolve, stops as made-for-kids. */
    @Test
    fun aMadeForKidsVideoStopsAsMadeForKids() {
        val controller = stoppedByRecordedRefusal(MADE_FOR_KIDS_VIDEO_FLOW, MADE_FOR_KIDS_VIDEO_ID)

        assertEquals(PlayerPolicy.TERMINAL_MADE_FOR_KIDS, stopReason(controller))
        assertNull(controller.sessionExtras.getString(PlaybackContract.EXTRA_STOP_DETAIL))
    }

    /** The recorded not-yet-started stream, through the resolve, stops as not started with
     *  YouTube's sentence in the session extras. */
    @Test
    fun anUpcomingStreamStopsAsNotStartedWithYouTubesSentence() {
        val controller = stoppedByRecordedRefusal(UPCOMING_VIDEO_FLOW, UPCOMING_VIDEO_ID)

        assertEquals(PlayerPolicy.TERMINAL_NOT_STARTED, stopReason(controller))
        assertEquals(UPCOMING_VIDEO_REASON, controller.sessionExtras.getString(PlaybackContract.EXTRA_STOP_DETAIL))
    }

    private fun stoppedByRecordedRefusal(flow: String, id: String): MediaController {
        val refusal = runCatching {
            runBlocking { NewPipeAccess(downloader = FixtureDownloader(flow)).video(VideoId(id)) }
        }.exceptionOrNull()!!
        val controller = connect()
        request(controller, VIDEO_A)

        resolves.getValue(VIDEO_A).completeExceptionally(refusal)
        idleUntil("stop visible") { stopReason(controller) != null }
        return controller
    }

    /** LIVE-D2: the recorded live page without its HLS manifest, through the resolve, stops as
     *  live, with no place kept. */
    @Test
    fun aLivePageWithoutAManifestStopsAsLive() {
        val page = runBlocking { NewPipeAccess(downloader = FixtureDownloader(LIVE_VIDEO_FLOW)).video(VideoId(RecordedLiveVideo.ID)) }
        val refusal = runCatching { livePlayableOrRefuse(page.copy(hlsUrl = null), kidsProfile = false, maxShortSide = 1080) }.exceptionOrNull()!!
        val controller = connect()
        request(controller, VIDEO_A)

        resolves.getValue(VIDEO_A).completeExceptionally(refusal)
        idleUntil("stop visible") { stopReason(controller) != null }

        assertEquals(PlayerPolicy.TERMINAL_LIVE, stopReason(controller))
        assertEquals(0L, controller.sessionExtras.getLong(PlaybackContract.EXTRA_POSITION_MS, -1L))
    }

    /** A page with no avatar, chapters, storyboards, related videos, Details extras, channel or
     *  caption tracks is still dressed: only a masked item isn't. */
    @Test
    fun aPageWithNothingExtraStillReachesTheControllerDressed() {
        val metadata = runBlocking {
            NewPipeAccess(downloader = FixtureDownloader(CHAPTERS_VIDEO_FLOW)).video(VideoId(CHAPTERS_VIDEO_ID))
        }.copy(
            uploaderAvatarUrl = null, chapters = emptyList(), storyboards = emptyList(), relatedVideos = emptyList(),
            uploaderSubscriberCount = null, likeCount = null, description = null,
            uploaderName = null, uploaderUrl = null, captionTracks = emptyList(),
        )
        val controller = connect()
        controller.setMediaItem(playbackRequest(CHAPTERS_VIDEO_ID, short = false))
        idleUntil("resolve requested") { CHAPTERS_VIDEO_ID in resolves }

        resolves.getValue(CHAPTERS_VIDEO_ID).complete(resolvedItem(metadata, playable = Unit))
        idleUntil("title visible") { controller.mediaMetadata.title != null }

        assertEquals(PlaybackDressing(null, emptyList(), null), PlaybackDressing.fromBundle(controller.currentMediaItem?.mediaMetadata?.extras))
    }

    @Test
    fun aRetryAfterAStopResumesFromTheKeptPosition() {
        val controller = connect()
        request(controller, VIDEO_A)
        resolve(VIDEO_A)
        idleUntil("item playing") { player.mediaItemCount == 1 }
        player.seekTo(KEPT_POSITION_MS)
        failFromIdle(fatalFailure())
        idleUntil("stop visible") { stopReason(controller) != null }

        request(controller, VIDEO_A)
        resolve(VIDEO_A)
        idleUntil("item playing again") { player.mediaItemCount == 1 }

        assertEquals(KEPT_POSITION_MS, player.currentPosition)
    }

    @Test
    fun aRequestedStartIsWhereTheVideoStarts() {
        val controller = connect()

        request(controller, VIDEO_A, startMs = RESUME_MS)
        resolve(VIDEO_A)
        idleUntil("item set") { player.mediaItemCount == 1 }

        assertEquals(RESUME_MS, player.currentPosition)
    }

    @Test
    fun aKeptRetryPositionWinsOverTheRequestedStart() {
        val controller = connect()
        request(controller, VIDEO_A)
        resolve(VIDEO_A)
        idleUntil("item playing") { player.mediaItemCount == 1 }
        player.seekTo(KEPT_POSITION_MS)
        failFromIdle(fatalFailure())
        idleUntil("stop visible") { stopReason(controller) != null }

        request(controller, VIDEO_A, startMs = RESUME_MS)
        resolve(VIDEO_A)
        idleUntil("item set again") { player.mediaItemCount == 1 }

        assertEquals(KEPT_POSITION_MS, player.currentPosition)
    }

    @Test
    fun aNewRequestSavesTheOutgoingVideosPlaceBeforeClearingIt() {
        val controller = connect()
        playing(controller, VIDEO_A)
        controller.pause()
        idleUntil("paused") { !player.isPlaying }
        player.seekTo(KEPT_POSITION_MS) // paused, so no stop-moving save records it
        idleMain()
        val savesBefore = saves.size

        request(controller, VIDEO_B)

        assertEquals(
            listOf(PositionSave(OWNER, VideoId(VIDEO_A), KEPT_POSITION_MS, DURATION_MS, ended = false)),
            saves.drop(savesBefore),
        )
    }

    @Test
    fun aVideoKeepsTheProfileItWasAskedUnderWhenTheSessionSwitchesBeforeItLands() {
        val controller = connect()
        request(controller, VIDEO_A)

        profiles.select(OTHER)
        resolve(VIDEO_A)
        idleUntil("A playing") { player.currentMediaItem?.mediaId == VIDEO_A && player.isPlaying }
        controller.pause()
        idleUntil("paused") { !player.isPlaying }

        assertEquals(setOf(OWNER), saves.map { it.profileId }.toSet())
    }

    @Test
    fun aProfileSwitchWhileAVideoPlaysLeavesItsSavesWithItsProfile() {
        val controller = connect()
        playing(controller, VIDEO_A)

        profiles.select(OTHER)
        controller.pause()
        idleUntil("paused") { !player.isPlaying }
        playing(controller, VIDEO_B)
        controller.pause()
        idleUntil("paused again") { !player.isPlaying }

        assertEquals(setOf(OWNER), saves.filter { it.videoId.value == VIDEO_A }.map { it.profileId }.toSet())
        assertEquals(setOf(OTHER), saves.filter { it.videoId.value == VIDEO_B }.map { it.profileId }.toSet())
    }

    /** Before a sync: a paused seek's place lands, the item stays, and an unchanged place isn't
     *  written again (it would stamp the video watched now and set off another sync). */
    @Test
    fun aSyncSavesAMovedPlaceOnceAndKeepsTheItem() {
        val controller = connect()
        playing(controller, VIDEO_A)
        controller.pause()
        idleUntil("paused") { !player.isPlaying }
        player.seekTo(KEPT_POSITION_MS) // paused, so no stop-moving save records it
        idleMain()
        val savesBefore = saves.size

        controlPlane.saveMovedPlace()
        controlPlane.saveMovedPlace()
        idleMain()

        assertEquals(
            listOf(PositionSave(OWNER, VideoId(VIDEO_A), KEPT_POSITION_MS, DURATION_MS, ended = false)),
            saves.drop(savesBefore),
        )
        assertTrue(controlPlane.holdsItem())
    }

    @Test
    fun endingTheSessionSavesThePlaceThenStopsAndClearsTheItem() {
        val controller = connect()
        playing(controller, VIDEO_A)
        controller.pause()
        idleUntil("paused") { !player.isPlaying }
        player.seekTo(KEPT_POSITION_MS) // paused, so only the end's own save records it
        idleMain()
        val savesBefore = saves.size
        assertTrue(controlPlane.holdsItem())

        controlPlane.endSession()
        profiles.select(OTHER)
        idleMain()

        assertFalse(controlPlane.holdsItem())

        assertEquals(
            listOf(PositionSave(OWNER, VideoId(VIDEO_A), KEPT_POSITION_MS, DURATION_MS, ended = false)),
            saves.drop(savesBefore),
        )
        assertEquals(0, player.mediaItemCount)
        assertEquals(Player.STATE_IDLE, player.playbackState)
        assertEquals(0, controller.mediaItemCount)
    }

    @Test
    fun endingTheSessionMidResolveCancelsItSoNothingLands() {
        val controller = connect()
        request(controller, VIDEO_A)

        controlPlane.endSession()
        idleMain()

        assertEquals(listOf(VIDEO_A), cancelledResolves)
        assertEquals(0, player.mediaItemCount)
        assertEquals(emptyList<PositionSave>(), saves)
    }

    @Test
    fun aKeptRetryPositionDoesntOutliveTheSession() {
        val controller = connect()
        request(controller, VIDEO_A)
        resolve(VIDEO_A)
        idleUntil("item playing") { player.mediaItemCount == 1 }
        player.seekTo(KEPT_POSITION_MS)
        failFromIdle(fatalFailure())
        idleUntil("stop visible") { stopReason(controller) != null }

        controlPlane.endSession()
        idleUntil("stop cleared") { stopReason(controller) == null }
        request(controller, VIDEO_A, startMs = RESUME_MS)
        resolve(VIDEO_A)
        idleUntil("item set again") { player.mediaItemCount == 1 }

        assertEquals(RESUME_MS, player.currentPosition)
    }

    @Test
    fun aPlayingVideoSavesItsPlaceEveryTenSeconds() {
        val controller = connect()
        playing(controller, VIDEO_A)
        val before = saves.size

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))

        // Under load the playback thread can lag the jumped clock and stall, which saves too;
        // the tick is the one save made while still playing.
        val ticks = saves.drop(before).filterIndexed { i, _ -> savedWhilePlaying[before + i] }
        assertEquals(1, ticks.size)
        val tick = ticks.single()
        assertEquals(VIDEO_A, tick.videoId.value)
        // Not its position: how far ExoPlayer's playback thread has caught up with the jumped clock
        // varies with load (exact positions are pinned by the outgoing-save test).
        assertFalse(tick.ended)
    }

    @Test
    fun aShortResolvesAsAShortRepeatsAndSavesItsStart() {
        val controller = connect()

        playing(controller, VIDEO_A, short = true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        controller.pause()
        idleUntil("paused") { !player.isPlaying }
        controller.release()
        idleMain()

        assertEquals(listOf(VIDEO_A), shortResolves)
        assertEquals(Player.REPEAT_MODE_ONE, player.repeatMode)
        // Saved as its start: History moves it up, and no resume point is left (Q39).
        assertTrue(saves.isNotEmpty())
        assertTrue(saves.all { it.videoId.value == VIDEO_A && it.positionMs == 0L && !it.ended })
    }

    @Test
    fun anAttestationRefreshOfAShortResolvesItAsAShortAgain() {
        val controller = connect()
        playing(controller, VIDEO_A, short = true)

        failAttestation(VIDEO_A)
        resolve(VIDEO_A)
        idleUntil("prepared again") { player.playbackState != Player.STATE_IDLE }

        assertEquals(listOf(VIDEO_A, VIDEO_A), shortResolves)
        assertEquals(Player.REPEAT_MODE_ONE, player.repeatMode)
    }

    @Test
    fun aVideoAfterAShortPlaysOnceAndSavesWhereItIs() {
        val controller = connect()
        playing(controller, VIDEO_A, short = true)

        playing(controller, VIDEO_B)
        controller.pause()
        idleUntil("paused") { !player.isPlaying }

        assertEquals(listOf(VIDEO_A), shortResolves)
        assertEquals(Player.REPEAT_MODE_OFF, player.repeatMode)
        assertTrue(saves.filter { it.videoId.value == VIDEO_A }.all { it.positionMs == 0L })
        assertTrue(saves.any { it.videoId.value == VIDEO_B })
    }

    @Test
    fun aPausedVideoStopsSavingOnTheTick() {
        val controller = connect()
        playing(controller, VIDEO_A)
        controller.pause()
        idleUntil("paused") { !player.isPlaying }
        val before = saves.size

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))

        assertEquals(before, saves.size)
    }

    @Test
    fun reachingTheEndSavesTheVideoAsEnded() {
        val controller = connect()
        playing(controller, VIDEO_A)

        player.seekTo(DURATION_MS - 1_000)
        idleUntil("ended") {
            // ExoPlayer's work loop runs on the Robolectric clock: let it play the last second.
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            player.playbackState == Player.STATE_ENDED
        }

        assertTrue(saves.any { it.videoId.value == VIDEO_A && it.ended })
    }

    /** LIVE-D20: a live stream that ends while watched is saved at its start, never as watched. */
    @Test
    fun aLiveStreamThatEndsIsSavedButNotAsWatched() {
        val controller = connect()
        playingLive(controller, VIDEO_A)

        player.seekTo(DURATION_MS - 1_000)
        idleUntil("ended") {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            player.playbackState == Player.STATE_ENDED
        }

        assertTrue(saves.isNotEmpty())
        assertTrue(saves.all { it == PositionSave(OWNER, VideoId(VIDEO_A), positionMs = 0, durationMs = null, ended = false) })
    }

    /** LIVE-D23: a live item plays at 1× whatever speed its requester set (a profile's default
     *  speed is set before the resolve says live); a video keeps the requester's speed. */
    @Test
    fun aLiveItemPlaysAtNormalSpeed() {
        val controller = connect()
        controller.setPlaybackSpeed(1.5f)
        playing(controller, VIDEO_A)
        assertEquals(1.5f, player.playbackParameters.speed)

        playingLive(controller, VIDEO_B)

        assertEquals(1f, player.playbackParameters.speed)
    }

    /** LIVE-D4: READY ends a rejoin, so a later fall behind the window rejoins free again. */
    @Test
    fun aLiveStreamRejoinsTheWindowAgainAfterRecovering() {
        val controller = connect(extrasRecorder())
        playingLive(controller, VIDEO_A)

        repeat(2) {
            failFromIdle(behindLiveWindow())
            idleUntil("ready again") { player.playbackState == Player.STATE_READY }
        }

        assertTrue(extrasUpdates.none { it.startsWith(PlaybackContract.RETRY_RECOVERABLE) })
    }

    /** LIVE-D4: each recovered incident re-arms a live stream's budgets, so seven blips over a
     *  long stream never stop it (a video's budget of six would). */
    @Test
    fun aRecoveredLiveStreamReArmsItsBudgets() {
        val controller = connect(extrasRecorder())
        playingLive(controller, VIDEO_A)

        repeat(PlayerPolicy.RECOVERABLE_RETRY_BUDGET + 1) {
            failFromIdle(networkFailure())
            idleUntil("ready again") { player.playbackState == Player.STATE_READY }
        }

        assertNull(stopReason(controller))
        assertEquals("${PlaybackContract.RETRY_RECOVERABLE} 1/${PlayerPolicy.RECOVERABLE_RETRY_BUDGET}", extrasUpdates.filter { it.isNotEmpty() }.distinct().single())
    }

    /** LIVE-D4: a Retry after a stop opens a live item at its default position, not the kept one. */
    @Test
    fun aRetryAfterAStopOnALiveItemIgnoresTheKeptPosition() {
        val controller = connect()
        request(controller, VIDEO_A)
        resolves.getValue(VIDEO_A).complete(liveItem(VIDEO_A))
        idleUntil("item playing") { player.mediaItemCount == 1 }
        player.seekTo(KEPT_POSITION_MS)
        failFromIdle(fatalFailure())
        idleUntil("stop visible") { stopReason(controller) != null }

        request(controller, VIDEO_A)
        resolves.getValue(VIDEO_A).complete(liveItem(VIDEO_A))
        idleUntil("item playing again") { player.mediaItemCount == 1 && stopReason(controller) == null }

        assertTrue(player.currentPosition < KEPT_POSITION_MS)
    }

    /** The live 403 rule is live only: a video's 403 stays an ordinary recoverable retry. */
    @Test
    fun aForbiddenFetchOnAVideoIsAnOrdinaryRetry() {
        val controller = connect(extrasRecorder())
        playing(controller, VIDEO_A)

        failFromIdle(forbidden())
        idleUntil("retry shown") { extrasUpdates.any { it.isNotEmpty() } }

        assertEquals("${PlaybackContract.RETRY_RECOVERABLE} 1/${PlayerPolicy.RECOVERABLE_RETRY_BUDGET}", extrasUpdates.first { it.isNotEmpty() })
    }

    @Test
    fun theScreenLeavingSavesWhereAPausedSeekMovedTheVideo() {
        val controller = connect()
        playing(controller, VIDEO_A)
        controller.pause()
        idleUntil("paused") { !player.isPlaying }
        player.seekTo(KEPT_POSITION_MS)
        idleMain()

        controller.release()
        idleUntil("saved") { saves.last().positionMs == KEPT_POSITION_MS }

        assertEquals(VIDEO_A, saves.last().videoId.value)
    }

    @Test
    fun aResolveThatFailsSavesNothing() {
        val controller = connect()
        request(controller, VIDEO_A)

        resolves.getValue(VIDEO_A).completeExceptionally(IOException("extraction failed"))
        idleUntil("stop visible") { stopReason(controller) != null }
        controller.release()
        idleMain()

        assertEquals(emptyList<PositionSave>(), saves)
    }

    @Test
    fun requestsThatAreNotOneVideoIdLeaveThePlayerAlone() {
        val controller = connect()
        request(controller, VIDEO_A)
        resolve(VIDEO_A)
        idleUntil("item playing") { player.mediaItemCount == 1 }

        controller.setMediaItem(MediaItem.Builder().setMediaId("not a video id").build())
        controller.setMediaItems(listOf(playbackRequest(VIDEO_A, short = false), playbackRequest(VIDEO_B, short = false)))
        controller.addMediaItem(MediaItem.Builder().setMediaId(VIDEO_B).setUri("ke://watch/$VIDEO_B").build())
        idleMain()

        assertEquals(listOf(VIDEO_A), resolves.keys.toList())
        assertEquals(1, player.mediaItemCount)
        assertEquals(VIDEO_A, player.currentMediaItem?.mediaId)
        assertNull(stopReason(controller))
    }

    private suspend fun fakeResolve(videoId: VideoId, short: Boolean): MediaItem {
        val id = videoId.value
        if (short) shortResolves += id
        val deferred = CompletableDeferred<MediaItem>().also { resolves[id] = it }
        try {
            // Production extraction blocks uninterruptibly; an uncancellable resolve stands in.
            return if (id in uncancellableResolves) withContext(NonCancellable) { deferred.await() } else deferred.await()
        } catch (e: CancellationException) {
            cancelledResolves += id
            throw e
        }
    }

    private fun connect(listener: MediaController.Listener = object : MediaController.Listener {}): MediaController =
        connectController(context, session, listener).also { releaseActions += { it.release() } }

    /** The screen's request: set the video as the item (from [startMs] when given), prepare,
     *  play; waits for the resolve. */
    private fun request(controller: MediaController, videoId: String, startMs: Long? = null, short: Boolean = false) {
        resolves.remove(videoId)
        val item = playbackRequest(videoId, short)
        if (startMs != null) controller.setMediaItem(item, startMs) else controller.setMediaItem(item)
        controller.prepare()
        controller.play()
        idleUntil("resolve of $videoId requested") { resolves.containsKey(videoId) }
    }

    private fun resolve(videoId: String) {
        resolves.getValue(videoId).complete(resolvedItem(videoId))
    }

    /** Requests and resolves [videoId] and waits until it plays. */
    private fun playing(controller: MediaController, videoId: String, short: Boolean = false) {
        request(controller, videoId, short = short)
        resolve(videoId)
        idleUntil("$videoId playing") { player.currentMediaItem?.mediaId == videoId && player.isPlaying }
    }

    /** Requests [videoId], resolves it as a live item and waits until it plays. */
    private fun playingLive(controller: MediaController, videoId: String) {
        request(controller, videoId)
        resolves.getValue(videoId).complete(liveItem(videoId))
        idleUntil("$videoId playing") { player.currentMediaItem?.mediaId == videoId && player.isPlaying }
    }

    /** An attestation failure of the playing [videoId], waiting until its refresh asks for a resolve. */
    private fun failAttestation(videoId: String) {
        resolves.remove(videoId)
        failFromIdle(attestationRequiredFailure())
        idleUntil("refresh resolve of $videoId requested") { videoId in resolves }
    }

    /** A failed ExoPlayer reports its error from IDLE, at the failure position. */
    private fun failFromIdle(error: PlaybackException) {
        player.stop()
        controlPlane.onPlaybackFailure(error)
        idleMain()
    }

    private fun stopReason(controller: MediaController): String? =
        controller.sessionExtras.getString(PlaybackContract.EXTRA_STOP_REASON)

    /** Records each session-extras update as "kind attempt/max", "stop reason", or "" when cleared. */
    private fun extrasRecorder() = object : MediaController.Listener {
        override fun onExtrasChanged(controller: MediaController, extras: Bundle) {
            val kind = extras.getString(PlaybackContract.EXTRA_RETRY_KIND)
            val stop = extras.getString(PlaybackContract.EXTRA_STOP_REASON)
            extrasUpdates += when {
                kind != null -> "$kind ${extras.getInt(PlaybackContract.EXTRA_ATTEMPT)}/${extras.getInt(PlaybackContract.EXTRA_MAX)}"
                stop != null -> "$STOP $stop"
                else -> ""
            }
        }
    }

    private companion object {
        const val VIDEO_A = "aaaaaaaaaaa"
        const val VIDEO_B = "bbbbbbbbbbb"
        const val TITLE = "Resolved title"
        const val KEPT_POSITION_MS = 30_000L
        const val RESUME_MS = 45_000L
        const val DURATION_MS = 600_000L // ItemKeepingSourceFactory's silence
        const val OWNER = 1L
        const val OTHER = 2L
        const val STOP = "stop"

        /** The cards as the dressing carries them: an approximate upload instant ("5 hr ago",
         *  parsed to the nanosecond) travels in whole milliseconds. */
        fun List<VideoItem>.inMillis(): List<VideoItem> =
            map { video -> video.copy(uploadDate = video.uploadDate?.let { Instant.fromEpochMilliseconds(it.toEpochMilliseconds()) }) }

        fun resolvedItem(videoId: String): MediaItem = MediaItem.Builder()
            .setMediaId(videoId)
            .setUri("ke://watch/$videoId")
            .setMediaMetadata(MediaMetadata.Builder().setTitle(TITLE).build())
            .build()

        /** A resolved item tagged live (the plane's live rules key on the tag; the stand-in source
         *  plays silence, so it reaches READY and ENDED as a stream couldn't here). */
        fun liveItem(videoId: String): MediaItem {
            val page = runBlocking { NewPipeAccess(downloader = FixtureDownloader(LIVE_VIDEO_FLOW)).video(VideoId(RecordedLiveVideo.ID)) }
            return MediaItem.Builder()
                .setMediaId(videoId)
                .setUri("ke://watch/$videoId")
                .setTag(livePlayable(page, maxShortSide = 1080))
                .setMediaMetadata(MediaMetadata.Builder().setTitle(TITLE).build())
                .build()
        }

        fun behindLiveWindow() =
            PlaybackException("behind", BehindLiveWindowException(), PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW)

        fun forbidden() = PlaybackException(
            "source error",
            HttpDataSource.InvalidResponseCodeException(403, "Forbidden", null, emptyMap(), DataSpec(Uri.parse("https://rr1.googlevideo.com/videoplayback")), ByteArray(0)),
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        )

        /** The shape ExoPlayer reports for a SABR transport failure. */
        fun networkFailure() = PlaybackException(
            "source error",
            IOException("SABR segment fetch failed", UnknownHostException()),
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        )

        fun fatalFailure() = PlaybackException("renderer", IllegalStateException(), PlaybackException.ERROR_CODE_DECODING_FAILED)
    }
}
