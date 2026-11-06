package com.arshad.ayn.ui.tv.common

import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.arshad.ayn.core.access.BrandingLookup
import com.arshad.ayn.core.access.BrandingSource
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.access.dearrow.RecordedBranding
import com.arshad.ayn.core.store.FakeProfileSettingsStore
import com.arshad.ayn.core.store.ProfileSettings
import com.arshad.ayn.feature.CardBranding
import com.arshad.ayn.ui.tv.installOfflineImageLoader
import com.arshad.ayn.ui.tv.testBranding
import com.arshad.ayn.ui.tv.theme.KeTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import java.util.Collections
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.arshad.ayn.core.store.ProfileRules
import com.arshad.ayn.core.filter.ContentFilter

/**
 * A video card's face (M7-D11, `b3-home` dearrow-wait) with DeArrow on its recorded buckets: the
 * title held while DeArrow is waited for, then DeArrow's; YouTube's at once with DeArrow off.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp")
class CardFaceTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Before
    fun setUp() {
        installOfflineImageLoader(compose.activity)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun show(branding: CardBranding) {
        compose.setContent {
            KeTheme {
                CompositionLocalProvider(LocalCardBranding provides branding, LocalContentFilter provides OPEN_FILTER) {
                    VideoCard(RICK, meta = "Rick Astley", focusKey = "rick", onClick = {})
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun aCardHoldsItsTitleUntilDeArrowAnswersThenShowsDeArrows() {
        val gate = CompletableDeferred<Unit>()
        val recorded = RecordedBranding.source()
        show(testBranding(scope, BrandingSource { id, original -> gate.await(); recorded.branding(id, original) }))

        compose.onNodeWithText(RICK.title).assertDoesNotExist()
        compose.onNodeWithText(RecordedBranding.VIDEO_TITLE).assertDoesNotExist()
        compose.onNodeWithText("Rick Astley").assertExists()

        gate.complete(Unit)
        compose.waitForIdle()

        compose.onNodeWithText(RecordedBranding.VIDEO_TITLE).assertExists()
        compose.onNodeWithText(RICK.title).assertDoesNotExist()
    }

    @Test
    fun withDeArrowOffACardShowsYouTubesTitleAndAsksNothing() {
        val settings = FakeProfileSettingsStore(selectedId = 1)
        settings.byProfile.value = mapOf(1L to ProfileSettings(dearrowTitles = false, dearrowThumbnails = false))
        show(CardBranding({ _, _ -> error("DeArrow asked with both switches off") }, settings, settings.selected.filterNotNull(), scope))

        compose.onNodeWithText(RICK.title).assertExists()
    }

    /** M7-D8: a wait that ran out before the settings were read keeps YouTube's; nothing swaps late. */
    @Test
    fun settingsReadAfterTheWaitLeaveYouTubesTitleInPlace() {
        val settings = FakeProfileSettingsStore(selectedId = null)
        show(CardBranding(RecordedBranding.source(), settings, settings.selected.filterNotNull(), scope))

        compose.mainClock.advanceTimeBy(1_600)
        compose.onNodeWithText(RICK.title).assertExists()

        settings.selected.value = 1
        compose.waitForIdle()

        compose.onNodeWithText(RICK.title).assertExists()
        compose.onNodeWithText(RecordedBranding.VIDEO_TITLE).assertDoesNotExist()
    }

    @Test
    fun switchingDeArrowsTitlesOffShowsYouTubesTitleAgain() {
        val settings = FakeProfileSettingsStore(selectedId = 1)
        show(CardBranding(RecordedBranding.source(), settings, settings.selected.filterNotNull(), scope))
        compose.onNodeWithText(RecordedBranding.VIDEO_TITLE).assertExists()

        settings.byProfile.value = mapOf(1L to ProfileSettings(dearrowTitles = false))
        compose.waitForIdle()

        compose.onNodeWithText(RICK.title).assertExists()
    }

    /** PERF P9d D2: a row's cards past the screen edge are looked up before they compose. */
    @Test
    fun aRowLooksUpItsCardsPastTheEdgeBeforeTheyShow() {
        val asked = Collections.synchronizedList(mutableListOf<VideoId>())
        val videos = numberedVideos(1..12)
        val branding = testBranding(scope, keepingSource(asked))
        compose.setContent {
            KeTheme {
                CompositionLocalProvider(LocalCardBranding provides branding, LocalContentFilter provides OPEN_FILTER) {
                    PrefetchFaces(videos) { videos }
                    CardRow(title = "Row", itemWidth = 200.dp, itemSpacing = 20.dp) { _ ->
                        items(videos, key = { it.id.value }) { VideoCard(it, meta = it.uploaderName.orEmpty(), focusKey = it.id.value, onClick = {}) }
                    }
                }
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("Channel 12").assertDoesNotExist()
        assertEquals(videos.map { it.id }.toSet(), asked.toSet())
        assertEquals(videos.size, asked.size)
    }

    /** A list that grows (Search's next page) has its new videos looked up. */
    @Test
    fun aListGrowingLooksUpItsNewVideos() {
        val asked = Collections.synchronizedList(mutableListOf<VideoId>())
        val branding = testBranding(scope, keepingSource(asked))
        val list = mutableStateOf(numberedVideos(1..3))
        compose.setContent {
            CompositionLocalProvider(LocalCardBranding provides branding) {
                val videos = list.value
                PrefetchFaces(videos) { videos }
            }
        }
        compose.waitForIdle()

        list.value = numberedVideos(1..6)
        compose.waitForIdle()

        assertEquals(numberedVideos(1..6).map { it.id }, asked.toList())
    }

    @Test
    fun aVideoDeArrowHasNothingForShowsYouTubesTitle() {
        show(testBranding(scope, BrandingSource { _, _ -> BrandingLookup.None }))

        compose.onNodeWithText(RICK.title).assertExists()
    }
}

/** As the recorded playlist page lists it. */
private val RICK = VideoItem(
    id = RecordedBranding.VIDEO,
    title = "Rick Astley - Never Gonna Give You Up (Official Video) (4K Remaster)",
    uploaderName = "Rick Astley",
    uploaderUrl = null,
    durationSeconds = 213,
    viewCount = 1_700_000_000,
    textualUploadDate = null,
    uploadDate = null,
    thumbnailUrl = "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
    isLive = false,
)

private fun numberedVideos(range: IntRange): List<VideoItem> =
    range.map { RICK.copy(id = VideoId("v%010d".format(it)), uploaderName = "Channel $it") }

/** Answers nothing for every video, recording each ask in [asked] and keeping its answer, as
 *  DeArrowBranding does. */
private fun keepingSource(asked: MutableList<VideoId>): BrandingSource = object : BrandingSource {
    override suspend fun branding(videoId: VideoId, originalThumbnailUrl: String?): BrandingLookup {
        asked += videoId
        return BrandingLookup.None
    }

    override fun cached(videoId: VideoId, originalThumbnailUrl: String?): BrandingLookup? =
        BrandingLookup.None.takeIf { videoId in asked }
}

/** A filter that hides nothing, as an open profile's does. */
private val OPEN_FILTER = ContentFilter.of(ProfileSettings(), ProfileRules())
