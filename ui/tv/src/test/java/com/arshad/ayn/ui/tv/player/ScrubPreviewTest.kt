package com.arshad.ayn.ui.tv.player

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.request.SuccessResult
import com.arshad.ayn.core.access.Chapter
import com.arshad.ayn.core.access.Segment
import com.arshad.ayn.core.access.SegmentCategory
import com.arshad.ayn.core.access.Storyboard
import com.arshad.ayn.ui.tv.theme.color
import com.arshad.ayn.ui.tv.theme.KeColors
import com.arshad.ayn.ui.tv.theme.KeTheme
import java.util.Collections
import kotlin.math.abs
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The scrub bar measured on the render (1 dp = 1 px here). The focused bar's preview is a
 * 200×112.5 dp (16:9) box standing above the bar, whatever the video's shape: every sprite page is
 * served as solid [Page] colour, so the drawn frame is the [Page] run, and a wide frame fills the
 * box while a square one sits centred inside it on black. A SponsorBlock segment lies over the
 * track in its category's colour, across its span only. A frame whose page hasn't landed shows a
 * blank cell, and the focused bar loads the pages either side of the frame's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScrubPreviewTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @After
    fun tearDown() {
        SingletonImageLoader.reset()
    }

    @Test
    fun aWideFrameFillsThePreviewAboveTheBar() {
        val pixels = renderFocusedBar(frameWidth = 160, frameHeight = 90)

        val frame = pageRun(pixels)
        assertAbout(IntSize(200, 112), frame.size)
        assertTrue("the preview overlaps the bar (bottom row ${frame.bottom})", frame.bottom < barTop(pixels))
    }

    @Test
    fun aSquareFrameSitsCentredInTheSixteenByNinePreviewOnBlack() {
        val pixels = renderFocusedBar(frameWidth = 90, frameHeight = 90)

        val frame = pageRun(pixels)
        assertAbout(IntSize(112, 112), frame.size)
        val sideBar = (200 - 112) / 2
        assertTrue("no black left of the frame", isColor(pixels[frame.left - sideBar / 2, frame.midY], Color.Black))
        assertTrue("no black right of the frame", isColor(pixels[frame.right + sideBar / 2, frame.midY], Color.Black))
    }

    @Test
    fun aSegmentLiesOverTheTrackInItsCategorysColourAcrossItsSpan() {
        val sponsor = Segment(SegmentCategory.Sponsor, startMs = 100_000, endMs = 140_000)
        renderBar(board = { null }, segments = listOf(sponsor))
        val pixels = capture()

        val row = barTop(pixels) + 10
        assertTrue("no sponsor colour inside the segment", isColor(pixels[xAt(pixels, 120_000), row], SegmentCategory.Sponsor.color, 0.05f))
        assertFalse("sponsor colour before the segment", isColor(pixels[xAt(pixels, 90_000), row], SegmentCategory.Sponsor.color, 0.05f))
        assertFalse("sponsor colour after the segment", isColor(pixels[xAt(pixels, 150_000), row], SegmentCategory.Sponsor.color, 0.05f))
    }

    @Test
    fun aChapterCutGoesThroughASegment() {
        val sponsor = Segment(SegmentCategory.Sponsor, startMs = 100_000, endMs = 140_000)
        renderBar(board = { null }, segments = listOf(sponsor), chapters = listOf(Chapter("Opening", 0), Chapter("The read", 120)))
        val pixels = capture()

        val row = barTop(pixels) + 10
        assertFalse("no cut at the chapter", isColor(pixels[xAt(pixels, 120_000), row], SegmentCategory.Sponsor.color, 0.05f))
        assertTrue(isColor(pixels[xAt(pixels, 115_000), row], SegmentCategory.Sponsor.color, 0.05f))
        assertTrue(isColor(pixels[xAt(pixels, 125_000), row], SegmentCategory.Sponsor.color, 0.05f))
    }

    @Test
    fun aFrameWhosePageIsStillLoadingShowsABlankCellNotThePreviousPagesFrame() {
        val secondPage = CompletableDeferred<Unit>()
        serve(twoPageColours, gates = mapOf(pageUrl(1) to secondPage))
        val scrub = ScrubState()
        focusAndAwaitFirstPage(renderBar({ twoPageBoard() }, segments = emptyList(), scrub = scrub))

        compose.runOnIdle { scrub.targetMs = SECOND_PAGE_MS }
        compose.waitForIdle()
        val loading = columnAt(capture(), SECOND_PAGE_MS)
        assertFalse("the first page's frame shows for the second page's time", loading.any { isColor(it, Page) })
        assertTrue("no blank cell while the page loads", loading.any { isColor(it, KeColors.Surface2) })

        secondPage.complete(Unit)
        compose.waitUntil(timeoutMillis = 5_000) { columnAt(capture(), SECOND_PAGE_MS).any { isColor(it, SecondPage) } }
    }

    @Test
    fun aPagePrefetchedOnFocusShowsAtOnceWhenTheScrubReachesIt() {
        val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
        val requested = serve(twoPageColours, gates)
        val scrub = ScrubState()
        focusAndAwaitFirstPage(renderBar({ twoPageBoard() }, segments = emptyList(), scrub = scrub))
        compose.waitForIdle()
        assertTrue("page 1 wasn't prefetched on focus", pageUrl(1) in requested)
        gates[pageUrl(1)] = CompletableDeferred() // a load from here on never lands

        compose.runOnIdle { scrub.targetMs = SECOND_PAGE_MS }
        compose.waitForIdle()
        val column = columnAt(capture(), SECOND_PAGE_MS)
        assertTrue("the prefetched page's frame isn't shown", column.any { isColor(it, SecondPage) })
        assertFalse("a blank cell for a prefetched page", column.any { isColor(it, KeColors.Surface2) })
        assertEquals(1, requested.count { it == pageUrl(1) })
    }

    @Test
    fun theFocusedBarLoadsThePagesEitherSideOfTheFramesPage() {
        val requested = serve(emptyMap())
        val focus = renderBar({ fourPageBoard() }, segments = emptyList(), positionMs = THIRD_PAGE_MS)
        compose.waitForIdle()
        assertEquals("unfocused, only the frame's page", listOf(pageUrl(2)), requested.toList())

        compose.runOnIdle { focus.requestFocus() }
        compose.waitForIdle()
        assertEquals("each page once", listOf(pageUrl(1), pageUrl(2), pageUrl(3)), requested.sorted())
    }

    @Test
    fun aPageLeavingTheWindowIsDroppedAndLoadedAgainOnReturn() {
        val requested = serve(emptyMap())
        val scrub = ScrubState()
        val focus = renderBar({ fourPageBoard() }, segments = emptyList(), scrub = scrub, positionMs = THIRD_PAGE_MS)
        compose.runOnIdle { focus.requestFocus() }
        compose.waitForIdle()

        compose.runOnIdle { scrub.targetMs = SECOND_FOUR_PAGE_MS }
        compose.waitForIdle()
        compose.runOnIdle { scrub.targetMs = THIRD_PAGE_MS }
        compose.waitForIdle()

        assertEquals("page 3 wasn't dropped when it left the window", 2, requested.count { it == pageUrl(3) })
        assertEquals("page 2 never left the window", 1, requested.count { it == pageUrl(2) })
    }

    @Test
    fun aNewBoardNeverShowsTheOldBoardsPage() {
        val nextFirstPage = CompletableDeferred<Unit>()
        serve(twoPageColours + (pageUrl(0, "next") to SecondPage), gates = mapOf(pageUrl(0, "next") to nextFirstPage))
        var board by mutableStateOf(twoPageBoard())
        focusAndAwaitFirstPage(renderBar({ board }, segments = emptyList()))

        compose.runOnIdle { board = twoPageBoard().copy(pageUrls = listOf(pageUrl(0, "next"), pageUrl(1, "next"))) }
        compose.waitForIdle()
        val loading = headColumn(capture())
        assertFalse("the old board's page shows for the new board", loading.any { isColor(it, Page) })
        assertTrue("no blank cell while the new board's page loads", loading.any { isColor(it, KeColors.Surface2) })

        nextFirstPage.complete(Unit)
        compose.waitUntil(timeoutMillis = 5_000) { headColumn(capture()).any { isColor(it, SecondPage) } }
    }

    private class Run(val left: Int, val right: Int, val top: Int, val bottom: Int) {
        val size = IntSize(right - left + 1, bottom - top + 1)
        val midY = (top + bottom) / 2
    }

    private fun renderFocusedBar(frameWidth: Int, frameHeight: Int): PixelMap {
        serve(mapOf(pageUrl(0) to Page), frame = IntSize(frameWidth, frameHeight))
        val board = Storyboard(
            pageUrls = listOf(pageUrl(0)),
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            frameCount = GRID * GRID,
            frameDurationMs = DURATION_MS.toInt() / (GRID * GRID),
            framesPerPageX = GRID,
            framesPerPageY = GRID,
        )
        focusAndAwaitFirstPage(renderBar({ board }, segments = emptyList()))
        return capture()
    }

    private fun focusAndAwaitFirstPage(focus: FocusRequester) {
        compose.runOnIdle { focus.requestFocus() }
        compose.waitUntil(timeoutMillis = 5_000) { headColumn(capture()).any { isColor(it, Page) } }
    }

    private fun renderBar(
        board: () -> Storyboard?,
        segments: List<Segment>,
        chapters: List<Chapter> = emptyList(),
        scrub: ScrubState = ScrubState(),
        positionMs: Long = POSITION_MS,
    ): FocusRequester {
        val focus = FocusRequester()
        compose.setContent {
            KeTheme {
                Box(Modifier.fillMaxSize().background(KeColors.Ground)) {
                    ScrubBar(
                        times = ScrubTimes(positionMs = { positionMs }, bufferedMs = { positionMs }, durationMs = { DURATION_MS }),
                        chapters = chapters,
                        segments = segments,
                        storyboard = board(),
                        scrub = scrub,
                        focusRequester = focus,
                        canFocus = true,
                        onSeekTo = {},
                        modifier = Modifier.align(Alignment.BottomStart).padding(INSET.dp),
                    )
                }
            }
        }
        return focus
    }

    private fun capture(): PixelMap = compose.onRoot().captureToImage().toPixelMap()

    private fun xAt(pixels: PixelMap, positionMs: Long): Int = (INSET + positionMs.toFloat() / DURATION_MS * (pixels.width - 2 * INSET)).toInt()

    /** The preview is centred over the head, [POSITION_MS] along the bar inside the [INSET]. */
    private fun headX(pixels: PixelMap): Int = xAt(pixels, POSITION_MS)

    private fun headColumn(pixels: PixelMap): List<Color> = columnAt(pixels, POSITION_MS)

    /** The column through the preview while the scrub is at [positionMs]. */
    private fun columnAt(pixels: PixelMap, positionMs: Long): List<Color> = (0 until pixels.height).map { pixels[xAt(pixels, positionMs), it] }

    /** The bar's top row: it is ScrubHeight (20 dp) tall, [INSET] above the bottom edge. */
    private fun barTop(pixels: PixelMap): Int = pixels.height - INSET - 20

    /** The page colour's run through the column above the head, and across its middle row. */
    private fun pageRun(pixels: PixelMap): Run {
        val x = headX(pixels)
        val rows = (0 until pixels.height).filter { isColor(pixels[x, it], Page) }
        val midY = (rows.first() + rows.last()) / 2
        val columns = (0 until pixels.width).filter { isColor(pixels[it, midY], Page) }
        return Run(left = columns.first(), right = columns.last(), top = rows.first(), bottom = rows.last())
    }

    /** Two 5×5 pages of 16:9 frames over [DURATION_MS]: [POSITION_MS] is on page 0,
     *  [SECOND_PAGE_MS] on page 1. */
    private fun twoPageBoard() = Storyboard(
        pageUrls = listOf(pageUrl(0), pageUrl(1)),
        frameWidth = 160,
        frameHeight = 90,
        frameCount = 2 * GRID * GRID,
        frameDurationMs = DURATION_MS.toInt() / (2 * GRID * GRID),
        framesPerPageX = GRID,
        framesPerPageY = GRID,
    )

    /** Four pages over [DURATION_MS]: [SECOND_FOUR_PAGE_MS] is on page 1, [THIRD_PAGE_MS] on page 2. */
    private fun fourPageBoard() = twoPageBoard().copy(
        pageUrls = (0 until 4).map { pageUrl(it) },
        frameCount = 4 * GRID * GRID,
        frameDurationMs = DURATION_MS.toInt() / (4 * GRID * GRID),
    )

    private fun pageUrl(index: Int, video: String = "test") = "https://i.ytimg.com/sb/$video/M$index.jpg"

    private val twoPageColours get() = mapOf(pageUrl(0) to Page, pageUrl(1) to SecondPage)

    /** Serves each page URL as a solid page in its colour from [colours] (else [Page]), [GRID] by
     *  [GRID] frames of [frame]; a page with a gate waits for it. Returns the URLs asked for, in
     *  order. */
    private fun serve(
        colours: Map<String, Color>,
        gates: Map<String, CompletableDeferred<Unit>> = emptyMap(),
        frame: IntSize = IntSize(160, 90),
    ): List<String> {
        val requested = Collections.synchronizedList(mutableListOf<String>())
        SingletonImageLoader.setUnsafe(
            ImageLoader.Builder(compose.activity)
                .components {
                    add(
                        object : Interceptor {
                            override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
                                val url = chain.request.data as String
                                requested += url
                                gates[url]?.await()
                                val page = Bitmap.createBitmap(frame.width * GRID, frame.height * GRID, Bitmap.Config.ARGB_8888)
                                page.eraseColor((colours[url] ?: Page).toArgb())
                                return SuccessResult(page.asImage(), chain.request, DataSource.MEMORY)
                            }
                        },
                    )
                }
                .build(),
        )
        return requested
    }

    private fun isColor(c: Color, expected: Color, tolerance: Float = 0.02f): Boolean =
        abs(c.red - expected.red) < tolerance && abs(c.green - expected.green) < tolerance && abs(c.blue - expected.blue) < tolerance

    /** Within the hairline border and anti-aliasing. */
    private fun assertAbout(expected: IntSize, actual: IntSize) {
        assertTrue(
            "expected about $expected, was $actual",
            abs(expected.width - actual.width) <= 3 && abs(expected.height - actual.height) <= 3,
        )
    }

    private companion object {
        val Page = Color.Green
        val SecondPage = Color.Blue
        const val GRID = 5
        const val INSET = 58
        const val POSITION_MS = 60_000L
        const val DURATION_MS = 200_000L
        const val SECOND_PAGE_MS = 150_000L
        const val SECOND_FOUR_PAGE_MS = 75_000L
        const val THIRD_PAGE_MS = 125_000L
    }
}
