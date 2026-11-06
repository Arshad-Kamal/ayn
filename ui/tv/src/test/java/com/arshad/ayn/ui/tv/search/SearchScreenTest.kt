package com.arshad.ayn.ui.tv.search

import androidx.compose.ui.semantics.SemanticsNode
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.newpipe.RecordedSearch
import com.arshad.ayn.core.store.FakeProfileSettingsStore
import com.arshad.ayn.core.store.FakeWatchStore
import com.arshad.ayn.core.store.ProfileSettings
import com.arshad.ayn.ui.tv.dpadLongPress
import com.arshad.ayn.ui.tv.playerRoute
import com.arshad.ayn.ui.tv.playerViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.arshad.ayn.ui.tv.TestShell
import com.arshad.ayn.core.filter.blockingVideos
import kotlinx.coroutines.flow.MutableStateFlow
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.core.view.children
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextRange
import com.arshad.ayn.core.access.ItemsPage
import com.arshad.ayn.core.access.newpipe.CHANNEL_SEARCH_FLOW
import com.arshad.ayn.core.access.newpipe.CHANNEL_SEARCH_QUERY
import com.arshad.ayn.core.access.newpipe.FIXED_SEARCH_QUERY
import com.arshad.ayn.core.access.newpipe.RecordedChannelSearch
import com.arshad.ayn.core.access.newpipe.RecordedSuggestions
import com.arshad.ayn.core.access.newpipe.SUGGESTIONS_FLOW
import com.arshad.ayn.core.access.newpipe.SUGGESTION_QUERIES
import com.arshad.ayn.core.access.SearchExtractor
import com.arshad.ayn.ui.tv.RailEdgeDp
import com.arshad.ayn.ui.tv.assertPlayerShows
import com.arshad.ayn.ui.tv.dpadSelect
import com.arshad.ayn.ui.tv.installOfflineImageLoader
import com.arshad.ayn.ui.tv.pressBack
import com.arshad.ayn.core.access.recorded
import java.net.UnknownHostException
import java.time.Duration
import androidx.compose.runtime.remember
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import com.arshad.ayn.core.access.Branding
import com.arshad.ayn.core.access.BrandingLookup
import com.arshad.ayn.core.access.BrandingSource
import com.arshad.ayn.ui.tv.testBranding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Search through the real shell and [com.arshad.ayn.feature.search.SearchViewModel] on
 * recorded payloads (the "lofi hip hop" search and its suggestions, the channel-led "veritasium"
 * search): each `b3-search` state's content and where focus lands. The keyboard itself and spatial
 * D-pad moves are device-only; typing, the IME's Search key, the keyboard's
 * InputConnection calls, arrow keys in the field, select and BACK are driven here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp")
class SearchScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val extractor = SearchExtractor()
    private val settings = FakeProfileSettingsStore(selectedId = 1)
    // The first Short with a resume point, as History's video-style reopening leaves one (Q47).
    private val watch = FakeWatchStore(VideoId(RecordedSearch.PAGE_1_FIRST_SHORT_ID) to 30_000L)
    private lateinit var navController: NavHostController
    private lateinit var focusManager: FocusManager
    private val brandingScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    // DeArrow knows a title for the first Short only (it has no Shorts data; Q40 says never ask).
    private val branding = BrandingSource { id, _ ->
        if (id.value == RecordedSearch.PAGE_1_FIRST_SHORT_ID) BrandingLookup.Found(Branding(DeArrowTitle, null)) else BrandingLookup.None
    }
    private val lofiSearch = recorded("search") { search(FIXED_SEARCH_QUERY) }
    private val contentFilter = MutableStateFlow(blockingVideos())

    @Before
    fun setUp() {
        installOfflineImageLoader(compose.activity)
        compose.setContent {
            navController = rememberNavController()
            focusManager = LocalFocusManager.current
            TestShell(
                extractor,
                watchStore = watch,
                profileSettings = settings,
                branding = remember { testBranding(brandingScope, branding) },
                navController = navController,
                contentFilter = contentFilter,
            )
        }
        compose.waitForIdle()
        // Home's blank slate opens Search (the rail's Search item composes after it).
        compose.onAllNodes(hasText("Search") and hasClickAction()).onFirst().dpadSelect()
        compose.waitForIdle()
    }

    @After
    fun tearDown() {
        brandingScope.cancel()
    }

    private fun field() = compose.onNode(hasSetTextAction())

    private fun button(label: String): SemanticsMatcher = hasText(label) and hasClickAction()

    private fun search(query: String) {
        field().performTextInput(query)
        field().performImeAction()
        compose.waitForIdle()
    }

    /** Past the ViewModel's suggestion debounce, which waits on the main looper. */
    private fun afterDebounce() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        compose.waitForIdle()
    }

    /** The focused field's input connection: the one the keyboard holds for its session. */
    private fun inputConnection(editorInfo: EditorInfo = EditorInfo()): InputConnection {
        var connection: InputConnection? = null
        compose.runOnUiThread {
            connection = compose.activity.window.decorView.textEditor()?.onCreateInputConnection(editorInfo)
        }
        return checkNotNull(connection) { "no text input session" }
    }

    /** The lofi answer, then Edit search. */
    private fun editSearchOnTheAnswer() {
        extractor.answers[FIXED_SEARCH_QUERY] = lofiSearch
        search(FIXED_SEARCH_QUERY)
        compose.onNode(button("Edit search")).dpadSelect()
        compose.waitForIdle()
    }

    private fun fieldText() = field().fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    private fun fieldSelection() = field().fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]

    private fun assertQuerySelected(query: String) {
        field().assertIsFocused()
        assertEquals(query, fieldText())
        assertEquals(TextRange(0, query.length), fieldSelection())
    }

    @Test
    fun arrivingFocusesTheEmptyQueryWithItsHint() {
        field().assertIsFocused()
        compose.onNodeWithText("Search YouTube").assertExists()
        compose.onNodeWithText("Type or press the mic to speak.").assertExists()
    }

    @Test
    fun typingShowsYouTubesSuggestionsAndPickingOneSearchesIt() {
        val typed = SUGGESTION_QUERIES.first()
        extractor.suggestionAnswers[typed] = recorded(SUGGESTIONS_FLOW) { suggestions(typed) }
        extractor.answers[FIXED_SEARCH_QUERY] = lofiSearch

        field().performTextInput(typed)
        afterDebounce()

        compose.onNodeWithText("Type or press the mic to speak.").assertDoesNotExist()
        // YouTube's echo of the typed query is dropped; every other completion is a pill.
        compose.onAllNodes(hasClickAction() and !hasSetTextAction() and hasText(typed, substring = true))
            .assertCountEquals(RecordedSuggestions.LOFI_HIP.size - 1)
        compose.onNode(button(FIXED_SEARCH_QUERY)).dpadSelect()
        compose.waitForIdle()

        compose.onNodeWithText(FIXED_SEARCH_QUERY).assertExists()
        compose.onNode(button("Edit search")).assertExists()
        assertFirstCardFocused()
    }

    @Test
    fun theKeyboardsSearchKeyShowsTheAnswerWithTheFirstCardFocused() {
        extractor.answers[FIXED_SEARCH_QUERY] = lofiSearch

        search(FIXED_SEARCH_QUERY)

        field().assertDoesNotExist()
        compose.onNodeWithText(FIXED_SEARCH_QUERY).assertExists()
        assertFirstCardFocused()
        assertTrue("live cards show their audience", compose.onAllNodesWithText("watching", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun aFocusedRowMovesToTheTopOfTheGrid() {
        extractor.answers[FIXED_SEARCH_QUERY] = lofiSearch
        search(FIXED_SEARCH_QUERY)
        val secondRowFirst = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
            .filter { it.boundsInRoot.top > SecondRowAboveDp && it.boundsInRoot.left > RailEdgeDp }
            .minWith(compareBy({ it.boundsInRoot.top }, { it.boundsInRoot.left }))

        compose.onNode(SemanticsMatcher("that card") { it.id == secondRowFirst.id }).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()

        // The row reaches the top band; it settles up to LowerRowGap below the first row's
        // focused place (TopAlign's known gap, a device-pass check).
        val center = compose.onNode(isFocused()).fetchSemanticsNode().boundsInRoot.center
        assertEquals(FirstCardCenterX, center.x, 0.5f)
        assertEquals(FirstCardCenterY + LowerRowGap / 2, center.y, LowerRowGap / 2 + 0.5f)
    }

    @Test
    fun aChannelLeadsItsAnswerWithItsSubscribersAndTakesFocus() {
        extractor.answers[CHANNEL_SEARCH_QUERY] = recorded(CHANNEL_SEARCH_FLOW) { search(CHANNEL_SEARCH_QUERY) }

        search(CHANNEL_SEARCH_QUERY)

        compose.onNode(button("Channel")).assertIsFocused()
        val names = compose.onAllNodesWithText(RecordedChannelSearch.CHANNEL_NAME).fetchSemanticsNodes()
        assertTrue("the name sits under the tile", names.any { it.boundsInRoot.left == FirstColumnLeft })
        compose.onNodeWithText("21M subscribers").assertExists()
        compose.onNodeWithText(RecordedChannelSearch.FIRST_VIDEO_TITLE).assertExists()
        // 10,090,589 views, abbreviated without rounding up.
        compose.onNodeWithText("10M views · ${RecordedChannelSearch.FIRST_VIDEO_AGE}").assertExists()

        compose.onNode(button("Channel")).dpadSelect()
        compose.waitForIdle()
        compose.onNodeWithText("This channel couldn’t load").assertExists()
        compose.pressBack()
        compose.onNode(button("Channel")).assertIsFocused()
    }

    /** KP-D19 (Q20): a block made while the answer shows takes its card out at once. */
    @Test
    fun aVideoTheFilterNowHidesLeavesTheAnswerAtOnce() {
        extractor.answers[CHANNEL_SEARCH_QUERY] = recorded(CHANNEL_SEARCH_FLOW) { search(CHANNEL_SEARCH_QUERY) }
        search(CHANNEL_SEARCH_QUERY)
        compose.onNodeWithText(RecordedChannelSearch.FIRST_VIDEO_TITLE).assertExists()

        contentFilter.value = blockingVideos(RecordedChannelSearch.FIRST_VIDEO_ID)
        compose.waitForIdle()

        compose.onNodeWithText(RecordedChannelSearch.FIRST_VIDEO_TITLE).assertDoesNotExist()
        compose.onNode(button("Channel")).assertExists()
    }

    @Test
    fun whileSearchingTheQueryHeadsThePageAndNothingTakesFocus() {
        val gate = CompletableDeferred<Unit>()
        extractor.searchGate = gate
        extractor.answers[FIXED_SEARCH_QUERY] = lofiSearch

        search(FIXED_SEARCH_QUERY)

        field().assertDoesNotExist()
        compose.onNodeWithText(FIXED_SEARCH_QUERY).assertExists()
        compose.onAllNodes(isFocused()).assertCountEquals(0)
        gate.complete(Unit)
        compose.waitForIdle()
        assertFirstCardFocused()
    }

    @Test
    fun backOnTheAnswerReopensTheQuerySelected() {
        extractor.answers[FIXED_SEARCH_QUERY] = lofiSearch
        search(FIXED_SEARCH_QUERY)

        compose.pressBack()

        assertQuerySelected(FIXED_SEARCH_QUERY)
        compose.pressBack()
        compose.onNodeWithText("Follow channels to fill Home").assertExists()
    }

    @Test
    fun editSearchOnTheAnswerReopensTheQuerySelected() {
        editSearchOnTheAnswer()

        assertQuerySelected(FIXED_SEARCH_QUERY)
    }

    /** § B3p: the keyboard can't learn one profile's searches or correct them toward another's. */
    @Test
    fun theKeyboardNeitherLearnsNorAutocorrectsTheQuery() {
        editSearchOnTheAnswer()
        val info = EditorInfo()
        inputConnection(info)

        assertTrue(info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
        assertEquals(0, info.inputType and InputType.TYPE_TEXT_FLAG_AUTO_CORRECT)
    }

    @Test
    fun typingAfterEditSearchReplacesTheQueryThoughTheKeyboardMovesTheCursorToTheEnd() {
        editSearchOnTheAnswer()
        val keyboard = inputConnection()
        val end = FIXED_SEARCH_QUERY.length

        // Gboard for TV's session start (B14-2, stack-notes): the cursor to the end, no composing,
        // then the word around the cursor as composing; each held as the whole query selected.
        compose.runOnUiThread {
            keyboard.setSelection(end, end)
            keyboard.finishComposingText()
        }
        compose.waitForIdle()
        assertQuerySelected(FIXED_SEARCH_QUERY)
        compose.runOnUiThread { keyboard.setComposingRegion(0, end) }
        compose.waitForIdle()
        assertQuerySelected(FIXED_SEARCH_QUERY)
        // Its letters arrive as composing text.
        compose.runOnUiThread { keyboard.setComposingText("enigma", 1) }
        compose.waitForIdle()
        assertEquals("enigma", fieldText())

        // Typing ended the hold: the cursor goes where the keyboard puts it.
        compose.runOnUiThread { keyboard.setSelection(2, 2) }
        compose.waitForIdle()
        assertEquals(TextRange(2), fieldSelection())
    }

    @Test
    fun leftAfterEditSearchMovesTheCursorToTheQuerysStart() {
        editSearchOnTheAnswer()

        field().performKeyInput { pressKey(Key.DirectionLeft) }
        compose.waitForIdle()
        field().performTextInput("new ")

        assertEquals("new $FIXED_SEARCH_QUERY", fieldText())
    }

    @Test
    fun rightAfterEditSearchMovesTheCursorToTheQuerysEnd() {
        editSearchOnTheAnswer()

        field().performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()
        field().performTextInput(" live")

        assertEquals("$FIXED_SEARCH_QUERY live", fieldText())
    }

    @Test
    fun backWhileSearchingDropsThatSearchSoTheNextOneRuns() {
        val gate = CompletableDeferred<Unit>()
        extractor.searchGate = gate
        search("lofi")
        compose.pressBack()
        assertQuerySelected("lofi")

        extractor.searchGate = null
        extractor.answers[FIXED_SEARCH_QUERY] = lofiSearch
        search(FIXED_SEARCH_QUERY) // typing replaces the selected query
        gate.complete(Unit)
        compose.waitForIdle()

        compose.onNodeWithText(FIXED_SEARCH_QUERY).assertExists()
        assertFirstCardFocused()
    }

    @Test
    fun noResultsOffersEditSearchWhichReopensTheQuerySelected() {
        extractor.answers["zzqxjvkw"] = ItemsPage(items = emptyList(), next = null)

        search("zzqxjvkw")

        compose.onNodeWithText("Nothing matched that search").assertExists()
        compose.onNode(button("Edit search")).assertIsFocused()
        compose.onNode(button("Edit search")).dpadSelect()
        compose.waitForIdle()
        assertQuerySelected("zzqxjvkw")
    }

    @Test
    fun noConnectionKeepsTheQueryAndTryAgainSearchesItAgain() {
        extractor.failure = UnknownHostException("www.youtube.com")

        search(FIXED_SEARCH_QUERY)

        compose.onNodeWithText("No connection to YouTube").assertExists()
        compose.onNodeWithText("Search didn’t go through").assertExists()
        compose.onNodeWithText(FIXED_SEARCH_QUERY).assertExists()
        compose.onNode(button("Try again")).assertIsFocused()

        extractor.answers[FIXED_SEARCH_QUERY] = lofiSearch
        compose.onNode(button("Try again")).dpadSelect()
        compose.waitForIdle()
        assertFirstCardFocused()
    }

    @Test
    fun anUnreadableReplySaysYouTubeChangedSomething() {
        search(FIXED_SEARCH_QUERY)

        compose.onNodeWithText("YouTube changed something").assertExists()
        compose.onNode(button("Try again")).assertIsFocused()
    }

    @Test
    fun backFromAVideoReturnsFocusToItsCard() {
        extractor.answers[FIXED_SEARCH_QUERY] = lofiSearch
        search(FIXED_SEARCH_QUERY)
        val cards = compose.onAllNodes(hasClickAction() and SemanticsMatcher("in the grid") { it.boundsInRoot.top > AnswerTopDp && it.boundsInRoot.left > RailEdgeDp })
        val opened = cards[1]
        val openedCenter = opened.fetchSemanticsNode().boundsInRoot.center
        opened.dpadSelect()
        compose.waitForIdle()
        compose.assertPlayerShows()

        compose.pressBack()

        val focused = compose.onNode(isFocused()).fetchSemanticsNode().boundsInRoot.center
        assertEquals(openedCenter.x, focused.x, 0.5f)
        assertEquals(openedCenter.y - FocusLift, focused.y, 0.5f)
    }

    /** The shelf's Short cards in reading order: clickable nodes a Short card wide and tall. */
    private fun shortCards(): List<SemanticsNode> = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        .filter { it.boundsInRoot.width in ShortCardWidthDp - 1f..ShortCardWidthDp * 1.1f && it.boundsInRoot.height > ShortCardWidthDp }
        .sortedBy { it.boundsInRoot.left }

    private fun firstShort() = shortCards().first().let { first -> compose.onNode(SemanticsMatcher("the first Short") { it.id == first.id }) }

    private fun searchWithShorts() {
        settings.byProfile.value = mapOf(1L to ProfileSettings(showShorts = true))
        extractor.answers[FIXED_SEARCH_QUERY] = lofiSearch
        search(FIXED_SEARCH_QUERY)
    }

    @Test
    fun withShowShortsOffTheAnswerHasNoShelf() {
        extractor.answers[FIXED_SEARCH_QUERY] = lofiSearch
        search(FIXED_SEARCH_QUERY)

        compose.onNodeWithText("Shorts").assertDoesNotExist()
        assertTrue(shortCards().isEmpty())
    }

    @Test
    fun withShowShortsOnTheShelfFollowsTheFirstRowWithViewsAndNoChannel() {
        searchWithShorts()

        assertFirstCardFocused()
        val heading = compose.onNodeWithText("Shorts").fetchSemanticsNode().boundsInRoot
        assertTrue("the shelf follows the first row", heading.top > FirstCardCenterY + 101f / 2)
        assertTrue("its Shorts sit under its heading", shortCards().first().boundsInRoot.top > heading.bottom)
        assertEquals("the heading starts at the content column", FirstColumnLeft, heading.left, 0.5f)
        assertEquals("so does the first Short", FirstColumnLeft, shortCards().first().boundsInRoot.left, 0.5f)
        val row = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange)).fetchSemanticsNode().boundsInRoot
        assertEquals("the row spans the screen", ScreenWidthDp, row.width, 0.5f)
        compose.onNodeWithText("1.1K views").assertExists()
    }

    @Test
    fun downFromTheFirstRowReachesTheShelfAndDownAgainTheSecondRow() {
        searchWithShorts()

        compose.runOnUiThread { focusManager.moveFocus(FocusDirection.Down) }
        compose.waitForIdle()
        val heading = compose.onNodeWithText("Shorts").fetchSemanticsNode().boundsInRoot
        assertEquals(AnswerTopDp + 15f + ShelfTopDp, heading.top, 1f)
        assertTrue("a Short holds focus", compose.onNode(isFocused()).fetchSemanticsNode().boundsInRoot.height > ShortCardWidthDp)

        compose.runOnUiThread { focusManager.moveFocus(FocusDirection.Down) }
        compose.waitForIdle()
        val focused = compose.onNode(isFocused()).fetchSemanticsNode().boundsInRoot
        assertTrue("a second-row card holds focus", focused.width > 170f && focused.top > AnswerTopDp)
    }

    @Test
    fun aShortShowsYouTubesTitleOnItsCardAndMenuNotDeArrows() {
        searchWithShorts()

        firstShort().dpadLongPress(compose::waitForIdle)

        compose.onNode(button("Play")).assertExists()
        compose.onNodeWithText(DeArrowTitle).assertDoesNotExist()
    }

    @Test
    fun aFocusedShortMovesTheShelfToTheTopWithItsHeading() {
        searchWithShorts()

        firstShort().performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()

        val heading = compose.onNodeWithText("Shorts").fetchSemanticsNode().boundsInRoot
        assertEquals("the heading settles at the grid's top", AnswerTopDp + 15f + ShelfTopDp, heading.top, 1f)
        assertTrue(compose.onNode(isFocused()).fetchSemanticsNode().boundsInRoot.top > heading.bottom)
    }

    @Test
    fun aShortOpensTheShortsPlayerWithTheShelfAsItsRun() {
        searchWithShorts()

        firstShort().dpadSelect()
        compose.waitForIdle()

        assertEquals(true, navController.playerRoute()?.short)
        assertEquals(RecordedSearch.PAGE_1_FIRST_SHORT_ID, navController.playerRoute()?.videoId)
        val run = checkNotNull(navController.playerViewModel().uiState.value.shorts)
        assertEquals(0, run.index)
        assertTrue("the shelf was handed over: more Shorts follow", run.hasNext)
    }

    /** The shelf's first Short opened, the run moved on to Short [last], then BACK. */
    private fun returnFromAShortsRun(last: Int) {
        searchWithShorts()
        firstShort().dpadSelect()
        compose.waitForIdle()
        val player = navController.playerViewModel()
        compose.runOnUiThread { repeat(last) { player.nextShort() } }
        compose.waitForIdle()
        compose.pressBack()
    }

    @Test
    fun backFromAShortsRunFocusesTheLastPlayedShortWithTheShelfScrolledToIt() {
        returnFromAShortsRun(last = 9)

        // Short 9 is past the six shown slots: it comes back on the last one, where D-pad brings it.
        val focused = compose.onNode(isFocused()).fetchSemanticsNode().boundsInRoot
        assertTrue("a Short holds focus", focused.height > ShortCardWidthDp)
        assertEquals(FirstColumnLeft + 5 * ShelfStepDp + ShortCardWidthDp / 2, focused.center.x, 0.5f)
        val title = lofiSearch.shorts[9].title
        assertTrue(
            "it is Short 9",
            compose.onAllNodesWithText(title).fetchSemanticsNodes().any { it.boundsInRoot.center.x in focused.left..focused.right },
        )
    }

    @Test
    fun onceFocusIsBackTheReturnedShortNoLongerOutranksWhereFocusWent() {
        returnFromAShortsRun(last = 9)
        compose.onNode(button("Edit search")).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()

        // To the rail and back into the page: focus goes back to Edit search.
        compose.onAllNodes(button("Search")).onLast().performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        compose.runOnUiThread { focusManager.moveFocus(FocusDirection.Right) }
        compose.waitForIdle()
        compose.onNode(button("Edit search")).assertIsFocused()

        // BACK from a video opened next returns to its card.
        val result = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
            .first { it.boundsInRoot.width > 170f && it.boundsInRoot.top > AnswerTopDp }
        val resultCard = compose.onNode(SemanticsMatcher("a result") { it.id == result.id })
        resultCard.performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        val opened = compose.onNode(isFocused()).fetchSemanticsNode().boundsInRoot.center
        resultCard.dpadSelect()
        compose.waitForIdle()
        compose.assertPlayerShows()
        compose.pressBack()
        val focused = compose.onNode(isFocused()).fetchSemanticsNode().boundsInRoot.center
        assertEquals(opened.x, focused.x, 0.5f)
        assertEquals(opened.y, focused.y, 0.5f)
    }

    @Test
    fun holdingOkOnAShortOffersPlayWithoutResumingAndPlayOpensItsRun() {
        searchWithShorts()

        firstShort().dpadLongPress(compose::waitForIdle)

        compose.onNode(button("Play")).assertExists()
        compose.onNodeWithText("Resume", substring = true).assertDoesNotExist()
        compose.onNode(button("Play from the start")).assertDoesNotExist()
        compose.onNode(button("Play")).dpadSelect()
        compose.waitForIdle()
        assertEquals(true, navController.playerRoute()?.short)
        assertTrue(checkNotNull(navController.playerViewModel().uiState.value.shorts).hasNext)
    }

    /** The first grid slot (mdpi: 1 dp = 1 px): focus scales a card about its centre and lifts it. */
    private fun assertFirstCardFocused() {
        val center = compose.onNode(isFocused()).fetchSemanticsNode().boundsInRoot.center
        assertEquals(FirstCardCenterX, center.x, 0.5f)
        assertEquals(FirstCardCenterY, center.y, 0.5f)
    }

    private companion object {
        const val AnswerTopDp = 75f
        const val SecondRowAboveDp = 200f
        const val LowerRowGap = 7f
        const val FirstColumnLeft = 126f
        const val FocusLift = 3f
        const val ShortCardWidthDp = 115f
        const val ShelfTopDp = 3f
        const val ShelfStepDp = ShortCardWidthDp + 15f
        const val ScreenWidthDp = 960f
        const val DeArrowTitle = "A title DeArrow would give"
        // ContentStart + half a 180 card; answer top 75 + grid top 15 + half the thumb (101.25
        // laid out as 101), less the lift.
        const val FirstCardCenterX = FirstColumnLeft + 90f
        const val FirstCardCenterY = AnswerTopDp + 15f + 101f / 2 - FocusLift
    }
}

/** The view a keyboard would connect to: the one holding the focused text field. */
private fun View.textEditor(): View? =
    if (onCheckIsTextEditor()) this else (this as? ViewGroup)?.children?.firstNotNullOfOrNull { it.textEditor() }
