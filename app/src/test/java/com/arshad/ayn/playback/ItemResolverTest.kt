package com.arshad.ayn.playback

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import com.arshad.ayn.core.access.BrandingLookup
import com.arshad.ayn.core.access.BrandingSource
import com.arshad.ayn.core.access.Segment
import com.arshad.ayn.core.access.SegmentCategory
import com.arshad.ayn.core.access.SegmentLookup
import com.arshad.ayn.core.access.SegmentSource
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoMetadata
import com.arshad.ayn.core.access.dearrow.RecordedBranding
import com.arshad.ayn.core.access.newpipe.CHAPTERS_VIDEO_FLOW
import com.arshad.ayn.core.access.newpipe.CHAPTERS_VIDEO_ID
import com.arshad.ayn.core.access.newpipe.FixtureDownloader
import com.arshad.ayn.core.access.newpipe.NewPipeAccess
import com.arshad.ayn.core.filter.ContentFilter
import com.arshad.ayn.core.filter.VideoFiltered
import com.arshad.ayn.core.kids.KidsTime
import com.arshad.ayn.core.kids.PlayTimeUp
import com.arshad.ayn.core.store.FakeProfileSettingsStore
import com.arshad.ayn.core.store.FakeRelatedListStore
import com.arshad.ayn.core.store.Keyword
import com.arshad.ayn.core.store.ProfileSession
import com.arshad.ayn.core.store.ProfileRules
import com.arshad.ayn.core.store.ProfileSettings
import com.arshad.ayn.core.store.ProfileSettingsState
import com.arshad.ayn.core.store.ProfileSettingsStore
import com.arshad.ayn.core.store.SegmentAction
import com.arshad.ayn.core.store.StoredRule
import com.arshad.ayn.feature.CardBranding
import com.arshad.ayn.feature.player.PlaybackDressing
import java.io.IOException
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [ItemResolver] (M7-D3) on the recorded chapters video's page: the SponsorBlock lookup rides the
 * item's dressing, DeArrow's face (PAF-D13, DeArrow's recorded bucket) the item's title and artwork,
 * and no lookup outcome (failed, thrown, stalled) keeps the video from resolving or, through a real
 * session, from playing.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ItemResolverTest {
    private val asked = mutableListOf<Set<SegmentCategory>>()
    private val kept = mutableListOf<VideoMetadata>()
    private var extracted = 0

    /** DeArrow buckets asked for, in order. */
    private val buckets = mutableListOf<String>()
    private val profileSettings = FakeProfileSettingsStore(selectedId = 1)

    /** Supervised and app-lived, as the container's scope is; unconfined, so a lookup answered
     *  at once is in the cache before the resolve reads it. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val branding = cardBranding(recorded())

    @After
    fun closeAppScope() = appScope.cancel()

    @Test
    fun theLookupsSegmentsRideTheItemsDressing() = runTest {
        val item = resolver(answering(FOUND)).resolve(VIDEO)

        assertEquals(FOUND, dressingOf(item).segments)
        assertEquals(CHAPTERS_VIDEO_ID, item.mediaId)
    }

    @Test
    fun aShortAsksSponsorBlockNothing() = runTest {
        val item = resolver(answering(FOUND)).resolve(VIDEO, short = true)

        assertNull(dressingOf(item).segments)
        assertEquals(emptyList<Set<SegmentCategory>>(), asked)
        assertEquals(CHAPTERS_VIDEO_ID, item.mediaId)
    }

    /** KP-D15, PAF-D6: a profile that doesn't show Shorts refuses a Short before any request; a
     *  video still resolves. */
    @Test
    fun aShortIsRefusedUnresolvedWhileTheProfileDoesntShowShorts() = runTest {
        val hiding = resolver(answering(FOUND), shortsHidden = true)

        assertTrue(runCatching { hiding.resolve(VIDEO, short = true) }.exceptionOrNull() is VideoFiltered)
        assertEquals(0, extracted)
        assertEquals(CHAPTERS_VIDEO_ID, hiding.resolve(VIDEO).mediaId)
    }

    /** KP-D22: Time's up or bedtime refuses before any request. */
    @Test
    fun aStoppedKidsTimeIsRefusedUnresolved() = runTest {
        val stopped = resolver(answering(FOUND), playTime = { KidsTime.TimesUp })

        assertTrue(runCatching { stopped.resolve(VIDEO) }.exceptionOrNull() is PlayTimeUp)
        assertEquals(0, extracted)
    }

    /** KP-D22: a fresh start has no clock; the extraction's own InnerTube answer sets it. */
    @Test
    fun anUnknownTimeResolvesOnceTheExtractionSetsTheClock() = runTest {
        val times = ArrayDeque(listOf(KidsTime.Unknown, KidsTime.Left(10.minutes)))

        val item = resolver(answering(FOUND), playTime = { times.removeFirst() }).resolve(VIDEO)

        assertEquals(CHAPTERS_VIDEO_ID, item.mediaId)
    }

    /** KP-D8: no clock even after the request (no `Date`) plays nothing; nothing is kept. */
    @Test
    fun aTimeStillUnknownAfterExtractionIsRefused() = runTest {
        val unknown = resolver(answering(FOUND), playTime = { KidsTime.Unknown })

        assertTrue(runCatching { unknown.resolve(VIDEO) }.exceptionOrNull() is PlayTimeUp)
        assertEquals(1, extracted)
        assertEquals(emptyList<VideoMetadata>(), kept)
    }

    /** FY-D4: For you keeps every played video's related list. */
    @Test
    fun theExtractedPageGoesToForYou() = runTest {
        resolver(answering(SegmentLookup.None)).resolve(VIDEO)

        assertEquals(listOf(VIDEO), kept.map { it.id })
        assertTrue("the recorded page has related videos", kept.single().relatedVideos.isNotEmpty())
    }

    /** FY-D2: a Short is never a seed, so its list isn't kept. */
    @Test
    fun aShortsPageIsNotKept() = runTest {
        resolver(answering(SegmentLookup.None)).resolve(VIDEO, short = true)

        assertEquals(emptyList<VideoMetadata>(), kept)
    }

    @Test
    fun aKeptListIsWrittenToTheStore() = runTest {
        val store = FakeRelatedListStore()

        keepRelatedList(store, metadata)
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(VIDEO to metadata.relatedVideos), store.recorded)
    }

    /** A store failure must not reach the service's scope (it has no handler) or the resolve. */
    @Test
    fun aKeptListThatCantBeWrittenIsDropped() = runTest {
        val store = FakeRelatedListStore().apply { failWrites = true }

        keepRelatedList(store, metadata)
        testScheduler.advanceUntilIdle()

        assertEquals(emptyList<Pair<VideoId, *>>(), store.recorded)
    }

    @Test
    fun onlyTheCategoriesNotOffAreAskedFor() = runTest {
        resolver(answering(SegmentLookup.None)).resolve(VIDEO)

        assertEquals(listOf(SegmentCategory.entries.toSet() - SegmentCategory.Filler), asked)
    }

    @Test
    fun aFailedLookupStillResolvesAndSaysSo() = runTest {
        val item = resolver(answering(SegmentLookup.Failed)).resolve(VIDEO)

        assertEquals(SegmentLookup.Failed, dressingOf(item).segments)
    }

    @Test
    fun aLookupThatThrowsStillResolvesAsFailed() = runTest {
        val throwing = SegmentSource { _, _ -> throw IllegalStateException("bug") }

        assertEquals(SegmentLookup.Failed, dressingOf(resolver(throwing).resolve(VIDEO)).segments)
    }

    @Test
    fun aSettingsReadThatThrowsStillResolvesAsFailed() = runTest {
        val item = ItemResolver({ ExtractedVideo(metadata, Unit) }, answering(FOUND), { throw IOException("disk") }, kept::add, { false }, { KidsTime.Unlimited }, branding, { OPEN }).resolve(VIDEO)

        assertEquals(SegmentLookup.Failed, dressingOf(item).segments)
    }

    @Test
    fun aStalledLookupCountsAsFailedAfterThreeSeconds() = runTest {
        val stalled = SegmentSource { _, _ -> awaitCancellation() }

        val item = resolver(stalled).resolve(VIDEO)

        assertEquals(SegmentLookup.Failed, dressingOf(item).segments)
        assertEquals(3_000L, testScheduler.currentTime)
    }

    @Test
    fun withSponsorBlockOffNothingIsLookedUp() = runTest {
        val item = resolver(answering(FOUND), ProfileSettings(sponsorBlock = false)).resolve(VIDEO)

        assertNull(dressingOf(item).segments)
        assertEquals(emptyList<Set<SegmentCategory>>(), asked)
    }

    @Test
    fun aFailedExtractionFailsTheResolveAndCancelsTheLookup() = runTest {
        var lookupCancelled = false
        val stalled = SegmentSource { _, _ ->
            try {
                awaitCancellation()
            } finally {
                lookupCancelled = true
            }
        }
        val extraction = CompletableDeferred<ExtractedVideo>()
        val resolve = async { runCatching { ItemResolver({ extraction.await() }, stalled, { ProfileSettings() }, kept::add, { false }, { KidsTime.Unlimited }, branding, { OPEN }).resolve(VIDEO) } }
        runCurrent()

        extraction.completeExceptionally(IOException("offline"))

        assertTrue(resolve.await().exceptionOrNull() is IOException)
        assertTrue(lookupCancelled)
        assertEquals(emptyList<VideoMetadata>(), kept)
        assertEquals("cancelled at once, not by the wait", 0L, testScheduler.currentTime)
    }

    /** M7-D3: the lookup costs no time beyond extraction when it is the quicker of the two. */
    @Test
    fun theLookupRunsBesideExtraction() = runTest {
        val slowLookup = SegmentSource { _, _ ->
            delay(2_000)
            FOUND
        }
        val slowExtraction = ItemResolver(
            extract = {
                delay(2_000)
                ExtractedVideo(metadata, Unit)
            },
            segments = slowLookup,
            settings = { ProfileSettings() },
            keepRelated = kept::add,
            shortsHidden = { false },
            playTime = { KidsTime.Unlimited },
            branding = branding,
            contentFilter = { OPEN },
        )

        val item = slowExtraction.resolve(VIDEO)

        assertEquals(FOUND, dressingOf(item).segments)
        assertEquals(2_000L, testScheduler.currentTime)
    }

    @Test
    fun withEveryCategoryOffNothingIsLookedUp() = runTest {
        val allOff = ProfileSettings(segmentActions = SegmentCategory.entries.associateWith { SegmentAction.Off })

        val item = resolver(answering(FOUND), allOff).resolve(VIDEO)

        assertNull(dressingOf(item).segments)
        assertEquals(emptyList<Set<SegmentCategory>>(), asked)
    }

    /** PAF-D13: the system controls show DeArrow's title and frame, the frame falling back to the
     *  page's own picture. */
    @Test
    fun theSystemControlsShowDeArrowsTitleAndFrame() = runTest {
        val item = resolver(answering(SegmentLookup.None), page = RICK_PAGE).resolve(RecordedBranding.VIDEO)

        assertEquals(RecordedBranding.VIDEO_TITLE, item.mediaMetadata.title)
        val artwork = checkNotNull(item.mediaMetadata.artworkUri)
        assertEquals(RecordedBranding.VIDEO.value, artwork.getQueryParameter("videoID"))
        assertEquals(RecordedBranding.VIDEO_FRAME_SECONDS, artwork.getQueryParameter("time"))
        assertEquals(checkNotNull(RICK_PAGE.thumbnailUrl), artwork.getQueryParameter("redirectUrl"))
    }

    /** KP-D20: the page passed the filter by YouTube's title; DeArrow's is checked before it shows. */
    @Test
    fun aDeArrowTitleTheFilterHidesKeepsYouTubesTitleWithDeArrowsFrame() = runTest {
        val hidingAstley = ContentFilter.of(ProfileSettings(), ProfileRules(rules = listOf(StoredRule(Keyword("astley"), byOwner = false, addedAtMillis = 0))))

        val item = resolver(answering(SegmentLookup.None), page = RICK_PAGE, contentFilter = hidingAstley).resolve(RecordedBranding.VIDEO)

        assertEquals(RICK_PAGE.title, item.mediaMetadata.title)
        assertEquals(RecordedBranding.VIDEO_FRAME_SECONDS, item.mediaMetadata.artworkUri?.getQueryParameter("time"))
    }

    /** KP-D11: a kids profile shows YouTube's face whatever its switches say, and asks DeArrow nothing. */
    @Test
    fun aKidsProfileKeepsYouTubesFaceAndAsksNothing() = runTest {
        val kids = object : ProfileSettingsStore {
            override fun settings(): Flow<ProfileSettingsState> = flowOf(ProfileSettingsState(1, ProfileSettings(dearrowTitles = true, dearrowThumbnails = true), kids = true))
            override suspend fun update(profileId: Long, transform: (ProfileSettings) -> ProfileSettings): Boolean = false
        }

        val item = resolver(answering(SegmentLookup.None), page = RICK_PAGE, branding = cardBranding(recorded(), kids)).resolve(RecordedBranding.VIDEO)

        assertYouTubesFace(item)
        assertEquals(emptyList<String>(), buckets)
    }

    @Test
    fun withBothSwitchesOffYouTubesFaceShowsAndNothingIsAsked() = runTest {
        profileSettings.byProfile.value = mapOf(1L to ProfileSettings(dearrowTitles = false, dearrowThumbnails = false))

        val item = resolver(answering(SegmentLookup.None), page = RICK_PAGE).resolve(RecordedBranding.VIDEO)

        assertYouTubesFace(item)
        assertEquals(emptyList<String>(), buckets)
    }

    @Test
    fun aShortAsksDeArrowNothing() = runTest {
        val item = resolver(answering(SegmentLookup.None), page = RICK_PAGE).resolve(RecordedBranding.VIDEO, short = true)

        assertYouTubesFace(item)
        assertEquals(emptyList<String>(), buckets)
    }

    /** M7-D8: DeArrow gets 1.5 s from the start; the video then plays with YouTube's face. */
    @Test
    fun aStalledLookupKeepsYouTubesFaceAfterOneAndAHalfSeconds() = runTest {
        val stalled = resolver(answering(SegmentLookup.None), page = RICK_PAGE, branding = cardBranding(gated(CompletableDeferred())))

        val item = stalled.resolve(RecordedBranding.VIDEO)

        assertYouTubesFace(item)
        assertEquals(1_500L, testScheduler.currentTime)
    }

    /** A lookup outliving its wait still lands in the cache, and the next play shows its face. */
    @Test
    fun aLookupOutlivingItsWaitShowsOnTheNextPlay() = runTest {
        val gate = CompletableDeferred<Unit>()
        val resolver = resolver(answering(SegmentLookup.None), page = RICK_PAGE, branding = cardBranding(gated(gate)))
        resolver.resolve(RecordedBranding.VIDEO)
        gate.complete(Unit)
        assertEquals("the outliving lookup answered", listOf("5f6b"), buckets)

        val item = resolver.resolve(RecordedBranding.VIDEO)

        assertEquals(RecordedBranding.VIDEO_TITLE, item.mediaMetadata.title)
        assertEquals("answered from the cache", listOf("5f6b"), buckets)
    }

    /** PAF-D13: the lookup runs beside extraction, so a slower extraction waits on nothing more. */
    @Test
    fun aStalledLookupHoldsNothingPastASlowerExtraction() = runTest {
        val slowExtraction = resolver(
            answering(SegmentLookup.None),
            page = RICK_PAGE,
            branding = cardBranding(gated(CompletableDeferred())),
            extractionTime = 2_000,
        )

        val item = slowExtraction.resolve(RecordedBranding.VIDEO)

        assertYouTubesFace(item)
        assertEquals(2_000L, testScheduler.currentTime)
    }

    @Test
    fun aLookupThatThrowsKeepsYouTubesFace() = runTest {
        val throwing = BrandingSource { _, _ -> throw IllegalStateException("bug") }

        val item = resolver(answering(SegmentLookup.None), page = RICK_PAGE, branding = cardBranding(throwing)).resolve(RecordedBranding.VIDEO)

        assertYouTubesFace(item)
    }

    /** An unreadable filter can't clear DeArrow's title, so YouTube's shows; the frame stays. */
    @Test
    fun aFilterReadThatThrowsKeepsYouTubesTitleWithDeArrowsFrame() = runTest {
        val item = ItemResolver(
            extract = { ExtractedVideo(RICK_PAGE, Unit) },
            segments = answering(SegmentLookup.None),
            settings = { ProfileSettings() },
            keepRelated = kept::add,
            shortsHidden = { false },
            playTime = { KidsTime.Unlimited },
            branding = branding,
            contentFilter = { throw IOException("disk") },
        ).resolve(RecordedBranding.VIDEO)

        assertEquals(RICK_PAGE.title, item.mediaMetadata.title)
        assertEquals(RecordedBranding.VIDEO_FRAME_SECONDS, item.mediaMetadata.artworkUri?.getQueryParameter("time"))
    }

    /** The acceptance line "a fetch failure plays the video unchanged", through a real session. */
    @Test
    fun aVideoWhoseLookupFailedPlays() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val player = inertExoPlayer(RuntimeEnvironment.getApplication(), ItemKeepingSourceFactory())
        val profiles = ProfileSession().apply { select(1) }
        val controlPlane = PlaybackControlPlane(player, scope, resolver(answering(SegmentLookup.Failed))::resolve, profiles::currentId) {}
        val session = MediaSession.Builder(RuntimeEnvironment.getApplication(), player).setCallback(controlPlane).build()
            .also { controlPlane.attach(it) }
        val controller = connectController(RuntimeEnvironment.getApplication(), session)
        try {
            controller.setMediaItem(MediaItem.Builder().setMediaId(CHAPTERS_VIDEO_ID).build())
            controller.prepare()
            controller.play()

            idleUntil("playing") { player.isPlaying }
            assertEquals(CHAPTERS_VIDEO_ID, player.currentMediaItem?.mediaId)
            assertEquals(SegmentLookup.Failed, PlaybackDressing.fromBundle(controller.currentMediaItem?.mediaMetadata?.extras)?.segments)
        } finally {
            controller.release()
            scope.cancel()
            session.release()
            player.release()
        }
    }

    private fun resolver(
        segments: SegmentSource,
        settings: ProfileSettings = ProfileSettings(),
        shortsHidden: Boolean = false,
        playTime: () -> KidsTime = { KidsTime.Unlimited },
        page: VideoMetadata = metadata,
        branding: CardBranding = this.branding,
        contentFilter: ContentFilter = OPEN,
        extractionTime: Long = 0,
    ): ItemResolver = ItemResolver(
        extract = {
            extracted++
            delay(extractionTime)
            ExtractedVideo(page, Unit)
        },
        segments = segments,
        settings = { settings },
        keepRelated = kept::add,
        shortsHidden = { shortsHidden },
        playTime = playTime,
        branding = branding,
        contentFilter = { contentFilter },
    )

    /** Over [store] (the session profile 1), in the app-lived scope the container's runs in. */
    private fun cardBranding(source: BrandingSource, store: ProfileSettingsStore = profileSettings): CardBranding =
        CardBranding(source, store, flowOf(1L), appScope)

    private fun recorded(): BrandingSource = RecordedBranding.source(onRequest = { buckets += it })

    /** The recorded source, each lookup held until [gate] opens. */
    private fun gated(gate: CompletableDeferred<Unit>): BrandingSource {
        val recorded = recorded()
        return object : BrandingSource {
            override suspend fun branding(videoId: VideoId, originalThumbnailUrl: String?): BrandingLookup {
                gate.await()
                return recorded.branding(videoId, originalThumbnailUrl)
            }

            override fun cached(videoId: VideoId, originalThumbnailUrl: String?): BrandingLookup? =
                recorded.cached(videoId, originalThumbnailUrl)
        }
    }

    private fun assertYouTubesFace(item: MediaItem) {
        assertEquals(RICK_PAGE.title, item.mediaMetadata.title)
        assertEquals(RICK_PAGE.thumbnailUrl, item.mediaMetadata.artworkUri?.toString())
    }

    private fun answering(lookup: SegmentLookup): SegmentSource = SegmentSource { _, categories ->
        asked += categories
        lookup
    }

    private fun dressingOf(item: MediaItem): PlaybackDressing = checkNotNull(PlaybackDressing.fromBundle(item.mediaMetadata.extras))

    private companion object {
        val VIDEO = VideoId(CHAPTERS_VIDEO_ID)
        val metadata: VideoMetadata by lazy {
            runBlocking { NewPipeAccess(downloader = FixtureDownloader(CHAPTERS_VIDEO_FLOW)).video(VIDEO) }
        }
        val FOUND = SegmentLookup.Found(listOf(Segment(SegmentCategory.Sponsor, 56_208, 134_034)))

        /** The recorded page under the id DeArrow's recorded bucket has a locked title and frame for. */
        val RICK_PAGE: VideoMetadata by lazy { metadata.copy(id = RecordedBranding.VIDEO) }
        val OPEN: ContentFilter = ContentFilter.of(ProfileSettings(), ProfileRules())
    }
}
