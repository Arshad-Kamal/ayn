package com.arshad.ayn.feature

import com.arshad.ayn.core.access.Branding
import com.arshad.ayn.core.access.BrandingLookup
import com.arshad.ayn.core.access.BrandingSource
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.access.dearrow.RecordedBranding
import com.arshad.ayn.core.filter.ContentFilter
import com.arshad.ayn.core.store.Keyword
import com.arshad.ayn.core.store.ProfileRules
import com.arshad.ayn.core.store.StoredRule
import com.arshad.ayn.core.store.FakeProfileSettingsStore
import com.arshad.ayn.core.store.ProfileSettings
import com.arshad.ayn.core.store.ProfileSettingsState
import com.arshad.ayn.core.store.ProfileSettingsStore
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CardBranding] over the real [com.arshad.ayn.core.access.dearrow.DeArrowBranding] on DeArrow's
 * recorded buckets ([RecordedBranding]); a gate in front of it holds an answer back where the wait
 * is under test.
 */
class CardBrandingTest {
    private val buckets = mutableListOf<String>()
    private val settings = FakeProfileSettingsStore(selectedId = 1)
    private val session = settings.selected.filterNotNull()

    @Test
    fun withBothSwitchesOnACardShowsDeArrowsTitleAndFrame() = runTest {
        val face = branding().face(RICK)

        assertEquals(
            CardFace(title = RecordedBranding.VIDEO_TITLE, thumbnailUrl = RICK_FRAME, titleFromDeArrow = true),
            face,
        )
    }

    @Test
    fun anAnswerOnceFetchedIsKnownWithoutAnotherRequest() = runTest {
        val branding = branding()
        val face = branding.face(RICK)

        assertEquals(face, branding.known(RICK))
        assertEquals(listOf("5f6b"), buckets)
    }

    @Test
    fun nothingIsKnownBeforeDeArrowIsAsked() = runTest {
        val branding = branding()
        runCurrent()

        assertNull(branding.known(RICK))
    }

    @Test
    fun withBothSwitchesOffYouTubesFaceIsKnownAndNothingIsAsked() = runTest {
        settings.byProfile.value = mapOf(1L to ProfileSettings(dearrowTitles = false, dearrowThumbnails = false))
        val branding = branding()
        runCurrent()

        assertEquals(CardFace.original(RICK), branding.known(RICK))
        assertEquals(CardFace.original(RICK), branding.face(RICK))
        assertEquals(emptyList<String>(), buckets)
    }

    @Test
    fun titlesOnlyKeepsYouTubesPicture() = runTest {
        settings.byProfile.value = mapOf(1L to ProfileSettings(dearrowThumbnails = false))

        assertEquals(CardFace(RecordedBranding.VIDEO_TITLE, RICK.thumbnailUrl, titleFromDeArrow = true), branding().face(RICK))
    }

    @Test
    fun thumbnailsOnlyKeepsYouTubesTitle() = runTest {
        settings.byProfile.value = mapOf(1L to ProfileSettings(dearrowTitles = false))

        assertEquals(CardFace(RICK.title, RICK_FRAME, titleFromDeArrow = false), branding().face(RICK))
    }

    @Test
    fun aVideoDeArrowHasNothingForShowsYouTubes() = runTest {
        val video = video(RecordedBranding.NO_SUBMISSIONS)

        assertEquals(CardFace.original(video), branding().face(video))
    }

    @Test
    fun aFailedLookupShowsYouTubes() = runTest {
        val failing = CardBranding({ _, _ -> BrandingLookup.Failed }, settings, session, backgroundScope)

        assertEquals(CardFace.original(RICK), failing.face(RICK))
    }

    @Test
    fun anAnswerSlowerThanTheWaitShowsYouTubesAtOneAndAHalfSeconds() = runTest {
        val gate = CompletableDeferred<Unit>()
        val branding = CardBranding(gated(gate), settings, session, backgroundScope)

        val face = branding.face(RICK)

        assertEquals(CardFace.original(RICK), face)
        assertEquals(1_500L, currentTime)
    }

    /** M7-D11: the timed-out wait doesn't cancel the lookup; its answer is there for the next ask. */
    @Test
    fun anAnswerLandingAfterTheWaitIsKnownNextTime() = runTest {
        val gate = CompletableDeferred<Unit>()
        val branding = CardBranding(gated(gate), settings, session, backgroundScope)
        branding.face(RICK)

        gate.complete(Unit)
        runCurrent()

        assertEquals(RecordedBranding.VIDEO_TITLE, branding.known(RICK)?.title)
    }

    @Test
    fun aCardLeavingBeforeTheAnswerStillLeavesItKnown() = runTest {
        val gate = CompletableDeferred<Unit>()
        val branding = CardBranding(gated(gate), settings, session, backgroundScope)
        val asking = async { branding.face(RICK) }
        advanceTimeBy(500.milliseconds)
        asking.cancel()

        gate.complete(Unit)
        runCurrent()

        assertEquals(RecordedBranding.VIDEO_TITLE, branding.known(RICK)?.title)
    }

    @Test
    fun asksForAVideoBeingLookedUpShareTheLookup() = runTest {
        val source = HeldSource()
        val branding = CardBranding(source, settings, session, backgroundScope)
        val first = async { branding.face(RICK) }
        val second = async { branding.face(RICK) }
        runCurrent()

        answerAll(source)

        assertEquals(first.await(), second.await())
        assertEquals("FOUND ${RICK.id.value}", first.await().title)
        assertEquals(listOf(RICK.id), source.asked)
    }

    @Test
    fun aPrefetchedVideoIsKnownWhenItsCardAsks() = runTest {
        val source = HeldSource()
        val branding = CardBranding(source, settings, session, backgroundScope)
        val videos = (1..3).map { numbered(it) }

        branding.prefetch(videos)
        answerAll(source)

        assertEquals(videos.map { it.id }, source.asked)
        videos.forEach { assertEquals("FOUND ${it.id.value}", branding.known(it)?.title) }
        assertEquals("FOUND ${videos[0].id.value}", branding.face(videos[0]).title)
        assertEquals(3, source.asked.size)
    }

    @Test
    fun aCardAskingWhileItsPrefetchRunsJoinsIt() = runTest {
        val source = HeldSource()
        val branding = CardBranding(source, settings, session, backgroundScope)
        branding.prefetch(listOf(RICK))
        val card = async { branding.face(RICK) }
        runCurrent()

        answerAll(source)

        assertEquals("FOUND ${RICK.id.value}", card.await().title)
        assertEquals(listOf(RICK.id), source.asked)
    }

    /** A known or already asked video takes no request and none of the four places. */
    @Test
    fun prefetchSkipsVideosAlreadyKnownOrBeingLookedUp() = runTest {
        val source = HeldSource()
        val branding = CardBranding(source, settings, session, backgroundScope)
        val known = numbered(1)
        val asking = numbered(2)
        val fresh = (3..6).map { numbered(it) }
        launch { branding.face(known) }
        answerAll(source)
        launch { branding.face(asking) }
        runCurrent()

        branding.prefetch(listOf(known, asking) + fresh)
        runCurrent()

        assertEquals(listOf(known.id, asking.id) + fresh.map { it.id }, source.asked)
        assertEquals(5, source.running)
    }

    @Test
    fun prefetchRunsFourAtOnceAndStartsThirtyPerList() = runTest {
        val source = HeldSource()
        val branding = CardBranding(source, settings, session, backgroundScope)
        val videos = (1..40).map { numbered(it) }
        val prefetching = launch { branding.prefetch(videos) }
        runCurrent()
        assertEquals(4, source.running)

        // A card's own ask doesn't wait for one of the four.
        val card = async { branding.face(videos[35]) }
        runCurrent()
        assertEquals(videos[35].id, source.asked.last())
        assertEquals(5, source.running)

        repeat(10) {
            if (prefetching.isActive) {
                answerAll(source)
                assertTrue(source.running <= 4)
            }
        }

        assertFalse(prefetching.isActive)
        assertEquals("FOUND ${videos[35].id.value}", card.await().title)
        assertEquals(videos.take(4).map { it.id } + videos[35].id + videos.subList(4, 30).map { it.id }, source.asked)
    }

    @Test
    fun aListLeavingStartsNoMoreAndThoseStartedStillLand() = runTest {
        val source = HeldSource()
        val branding = CardBranding(source, settings, session, backgroundScope)
        val videos = (1..6).map { numbered(it) }
        val prefetching = launch { branding.prefetch(videos) }
        runCurrent()

        prefetching.cancel()
        answerAll(source)

        assertEquals(videos.take(4).map { it.id }, source.asked)
        videos.take(4).forEach { assertEquals("FOUND ${it.id.value}", branding.known(it)?.title) }
        // The leaving list's places are free again.
        backgroundScope.launch { branding.prefetch((7..12).map { numbered(it) }) }
        runCurrent()
        assertEquals(4, source.running)
    }

    @Test
    fun withBothSwitchesOffPrefetchAsksNothing() = runTest {
        settings.byProfile.value = mapOf(1L to ProfileSettings(dearrowTitles = false, dearrowThumbnails = false))
        val source = HeldSource()
        val branding = CardBranding(source, settings, session, backgroundScope)

        branding.prefetch(listOf(RICK))

        assertEquals(emptyList<VideoId>(), source.asked)
    }

    @Test
    fun aPrefetchWaitingForTheSettingsAsksNothingWhenTheyAreOff() = runTest {
        settings.selected.value = null
        val source = HeldSource()
        val branding = CardBranding(source, settings, session, backgroundScope)
        val prefetching = launch { branding.prefetch(listOf(RICK)) }
        runCurrent()

        settings.byProfile.value = mapOf(1L to ProfileSettings(dearrowTitles = false, dearrowThumbnails = false))
        settings.selected.value = 1
        runCurrent()

        assertFalse(prefetching.isActive)
        assertEquals(emptyList<VideoId>(), source.asked)
    }

    @Test
    fun switchesTurnedOffMidListStopThePrefetch() = runTest {
        val source = HeldSource()
        val branding = CardBranding(source, settings, session, backgroundScope)
        val videos = (1..6).map { numbered(it) }
        val prefetching = launch { branding.prefetch(videos) }
        runCurrent()

        settings.byProfile.value = mapOf(1L to ProfileSettings(dearrowTitles = false, dearrowThumbnails = false))
        answerAll(source)

        assertFalse(prefetching.isActive)
        assertEquals(videos.take(4).map { it.id }, source.asked)
    }

    @Test
    fun aRecordedAnswerPrefetchedIsTheCardsFace() = runTest {
        val branding = branding()

        branding.prefetch(listOf(RICK))
        runCurrent()

        assertEquals(CardFace(RecordedBranding.VIDEO_TITLE, RICK_FRAME, titleFromDeArrow = true), branding.known(RICK))
        assertEquals(listOf("5f6b"), buckets)
    }

    @Test
    fun prefetchLooksAtTheFirst120VideosOnly() = runTest {
        val source = HeldSource()
        val branding = CardBranding(source, settings, session, backgroundScope)
        val videos = (1..121).map { numbered(it) }
        (1..120).forEach { launch { branding.face(videos[it - 1]) } }
        answerAll(source)
        source.asked.clear()

        branding.prefetch(videos)
        runCurrent()

        assertEquals(emptyList<VideoId>(), source.asked)
    }

    /** PAF-D13: a look-ahead while the session's switches are unread asks nothing and gives up
     *  after the 1.5 s wait. */
    @Test
    fun aLookAheadBeforeTheSettingsAreReadAsksNothingAndEndsAfterOneAndAHalfSeconds() = runTest {
        settings.selected.value = null
        val branding = branding()

        branding.lookAhead(RICK.id)

        assertEquals(1_500L, currentTime)
        assertEquals(emptyList<String>(), buckets)
    }

    /** One 1.5 s budget from the ask: settings read late leave the lookup only what remains. */
    @Test
    fun theWaitForTheSettingsCountsTowardsTheOneAndAHalfSeconds() = runTest {
        settings.selected.value = null
        val gate = CompletableDeferred<Unit>()
        val branding = CardBranding(gated(gate), settings, session, backgroundScope)
        val asking = async { branding.face(RICK) }
        advanceTimeBy(1_000.milliseconds)

        settings.selected.value = 1
        val face = asking.await()

        assertEquals(CardFace.original(RICK), face)
        assertEquals(1_500L, currentTime)
    }

    @Test
    fun settingsNotReadWithinTheWaitShowYouTubes() = runTest {
        settings.selected.value = null
        val branding = branding()

        assertEquals(CardFace.original(RICK), branding.face(RICK))
        assertEquals(1_500L, currentTime)
        assertEquals(emptyList<String>(), buckets)
    }

    @Test
    fun unreadableSettingsShowYouTubesAndAskNothing() = runTest {
        val unreadable = object : ProfileSettingsStore {
            override fun settings(): Flow<ProfileSettingsState> = flow { throw IOException("disk") }
            override suspend fun update(profileId: Long, transform: (ProfileSettings) -> ProfileSettings): Boolean = false
        }
        val branding = CardBranding(RecordedBranding.source(onRequest = { buckets += it }), unreadable, session, backgroundScope)
        runCurrent()

        assertEquals(CardFace.original(RICK), branding.known(RICK))
        assertEquals(emptyList<String>(), buckets)
    }

    @Test
    fun switchingTitlesOffShowsYouTubesTitleAgain() = runTest {
        val branding = branding()
        branding.face(RICK)

        settings.byProfile.value = mapOf(1L to ProfileSettings(dearrowTitles = false))
        runCurrent()

        assertEquals(CardFace(RICK.title, RICK_FRAME, titleFromDeArrow = false), branding.known(RICK))
    }

    /** The new profile's settings are a Room read away: until it lands, the old profile's switches decide nothing. */
    @Test
    fun afterAProfileSwitchNothingIsKnownUntilTheNewProfilesSettingsAreRead() = runTest {
        val selected = MutableStateFlow(1L)
        val read = MutableStateFlow(ProfileSettingsState(1, ProfileSettings(dearrowTitles = false, dearrowThumbnails = false)))
        val lagging = object : ProfileSettingsStore {
            override fun settings(): Flow<ProfileSettingsState> = read
            override suspend fun update(profileId: Long, transform: (ProfileSettings) -> ProfileSettings): Boolean = false
        }
        val branding = CardBranding(RecordedBranding.source(onRequest = { buckets += it }), lagging, selected, backgroundScope)
        runCurrent()
        assertEquals(CardFace.original(RICK), branding.known(RICK))

        selected.value = 2
        runCurrent()
        assertNull(branding.known(RICK))

        read.value = ProfileSettingsState(2, ProfileSettings())
        assertEquals(RecordedBranding.VIDEO_TITLE, branding.face(RICK).title)
    }

    /** KP-D11: DeArrow is off on a kids profile whatever its switches say. */
    @Test
    fun aKidsProfileShowsYouTubesFaceAndAsksNothing() = runTest {
        val kids = object : ProfileSettingsStore {
            override fun settings(): Flow<ProfileSettingsState> = MutableStateFlow(
                ProfileSettingsState(1, ProfileSettings(dearrowTitles = true, dearrowThumbnails = true), kids = true),
            )
            override suspend fun update(profileId: Long, transform: (ProfileSettings) -> ProfileSettings): Boolean = false
        }
        val branding = CardBranding(RecordedBranding.source(onRequest = { buckets += it }), kids, session, backgroundScope)
        runCurrent()

        assertEquals(CardFace.original(RICK), branding.known(RICK))
        assertEquals(CardFace.original(RICK), branding.face(RICK))
        assertEquals(emptyList<String>(), buckets)
    }

    /** KP-D20: the video passed the filter by YouTube's title; DeArrow's is checked at the card. */
    @Test
    fun aDeArrowTitleTheFilterHidesShowsYouTubesWithDeArrowsPicture() {
        val filter = ContentFilter.of(ProfileSettings(), ProfileRules(rules = listOf(StoredRule(Keyword("prank"), byOwner = false, addedAtMillis = 0))))
        val face = CardFace("The PRANK that went wrong", RICK_FRAME, titleFromDeArrow = true)

        assertEquals(CardFace(RICK.title, RICK_FRAME, titleFromDeArrow = false), face.checkedBy(filter, RICK))
        assertEquals(face.copy(title = "A calm afternoon"), face.copy(title = "A calm afternoon").checkedBy(filter, RICK))
        assertEquals(CardFace(RICK.title, RICK_FRAME, titleFromDeArrow = false), face.checkedBy(null, RICK))
    }

    private fun TestScope.branding(): CardBranding =
        CardBranding(RecordedBranding.source(onRequest = { buckets += it }), settings, session, backgroundScope)

    /**
     * A source whose every lookup waits until [release] (each answers a title naming the video),
     * keeping its answers as DeArrowBranding does; [asked] in order, [running] the lookups waiting.
     */
    private class HeldSource : BrandingSource {
        val asked = mutableListOf<VideoId>()
        var running = 0
        private var gate = CompletableDeferred<Unit>()
        private val answered = mutableMapOf<VideoId, BrandingLookup>()

        override suspend fun branding(videoId: VideoId, originalThumbnailUrl: String?): BrandingLookup {
            asked += videoId
            running++
            try {
                gate.await()
            } finally {
                running--
            }
            return BrandingLookup.Found(Branding("FOUND ${videoId.value}", null)).also { answered[videoId] = it }
        }

        override fun cached(videoId: VideoId, originalThumbnailUrl: String?): BrandingLookup? = answered[videoId]

        /** Lets every lookup waiting now answer; later ones wait for the next call. */
        fun release() {
            gate.complete(Unit)
            gate = CompletableDeferred()
        }
    }

    /** The lookups started so far reach [source] and answer, and what follows runs. */
    private fun TestScope.answerAll(source: HeldSource) {
        runCurrent()
        source.release()
        runCurrent()
    }

    /** The recorded source, each lookup held until [gate] opens. */
    private fun gated(gate: CompletableDeferred<Unit>): BrandingSource {
        val recorded = RecordedBranding.source(onRequest = { buckets += it })
        return object : BrandingSource {
            override suspend fun branding(videoId: VideoId, originalThumbnailUrl: String?): BrandingLookup {
                gate.await()
                return recorded.branding(videoId, originalThumbnailUrl)
            }

            override fun cached(videoId: VideoId, originalThumbnailUrl: String?): BrandingLookup? =
                recorded.cached(videoId, originalThumbnailUrl)
        }
    }
}

private fun video(id: VideoId): VideoItem = VideoItem(
    id = id,
    title = "YouTube's title for ${id.value}",
    uploaderName = "Uploader",
    uploaderUrl = null,
    durationSeconds = 213,
    viewCount = 1,
    textualUploadDate = null,
    uploadDate = null,
    thumbnailUrl = "https://i.ytimg.com/vi/${id.value}/hqdefault.jpg",
    isLive = false,
)

private val RICK = video(RecordedBranding.VIDEO)

private fun numbered(n: Int): VideoItem = video(VideoId("v%010d".format(n)))

/** The recorded frame, falling back to [RICK]'s own picture. */
private val RICK_FRAME = "https://dearrow-thumb.ajay.app/api/v1/getThumbnail?videoID=dQw4w9WgXcQ" +
    "&time=${RecordedBranding.VIDEO_FRAME_SECONDS}&redirectUrl=https%3A%2F%2Fi.ytimg.com%2Fvi%2FdQw4w9WgXcQ%2Fhqdefault.jpg"
