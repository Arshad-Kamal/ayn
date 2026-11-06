package com.arshad.ayn.ui.mobile.search

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.viewmodel.compose.viewModel
import com.arshad.ayn.core.access.ItemsPage
import com.arshad.ayn.core.access.SearchExtractor
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.access.newpipe.CHANNEL_SEARCH_FLOW
import com.arshad.ayn.core.access.newpipe.CHANNEL_SEARCH_QUERY
import com.arshad.ayn.core.access.newpipe.FIXED_SEARCH_QUERY
import com.arshad.ayn.core.access.newpipe.RecordedChannelSearch
import com.arshad.ayn.core.access.newpipe.SUGGESTIONS_FLOW
import com.arshad.ayn.core.access.recorded
import com.arshad.ayn.core.filter.ContentFilter
import com.arshad.ayn.core.filter.blockingVideos
import com.arshad.ayn.core.lock.FakeProfileLocks
import com.arshad.ayn.core.lock.LockKind
import com.arshad.ayn.core.store.FakeFilterStore
import com.arshad.ayn.core.store.FakePlaylistStore
import com.arshad.ayn.core.store.FakeProfileSettingsStore
import com.arshad.ayn.core.store.FakeProfileStore
import com.arshad.ayn.core.store.FakeWatchStore
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.ProfileSettings
import com.arshad.ayn.core.store.ProfileTint
import com.arshad.ayn.core.store.WatchEntry
import com.arshad.ayn.feature.ShortsList
import com.arshad.ayn.feature.library.MenuBlocking
import com.arshad.ayn.feature.library.VideoMenuViewModel
import com.arshad.ayn.feature.library.marks
import com.arshad.ayn.feature.listingKey
import com.arshad.ayn.feature.search.SearchViewModel
import com.arshad.ayn.ui.mobile.common.LocalContentFilter
import com.arshad.ayn.ui.mobile.installOfflineImageLoader
import com.arshad.ayn.ui.mobile.shell.MobileShell
import com.arshad.ayn.ui.mobile.shell.SearchKey
import com.arshad.ayn.ui.mobile.shell.SettingsKey
import com.arshad.ayn.ui.mobile.shell.ShellOpening
import com.arshad.ayn.ui.mobile.shell.ShellTopBar
import com.arshad.ayn.ui.mobile.shell.TabKey
import com.arshad.ayn.ui.mobile.theme.AynTheme
import java.net.UnknownHostException
import java.time.Duration
import kotlin.time.TimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
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
 * The phone's Search (`m6-search`, M6-D13) through the shell, the real [SearchViewModel] and
 * recorded payloads (the "lofi hip hop" search and its suggestions, the channel-led "veritasium"
 * search): opened from the pill, suggestions, compact rows in YouTube's order with their watch bars,
 * the leading channel, the Shorts shelf, the statements and their buttons, Back closing Search,
 * the pill reopening it empty, and a kids profile's Search.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class SearchPageTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val extractor = SearchExtractor()
    private val settings = FakeProfileSettingsStore(selectedId = ARSHAD.id)
    private val watch = FakeWatchStore()
    private val filter = MutableStateFlow<ContentFilter?>(null)
    private val lofi = recorded("search") { search(FIXED_SEARCH_QUERY) }
    private val played = mutableListOf<VideoItem>()
    private val playedShorts = mutableListOf<Pair<VideoItem, ShortsList>>()
    private val channels = mutableListOf<String>()

    @Before
    fun setUp() {
        installOfflineImageLoader(compose.activity)
    }

    private fun launch(profile: Profile = ARSHAD, showShorts: Boolean = false) {
        settings.byProfile.value = mapOf(profile.id to ProfileSettings(showShorts = showShorts))
        settings.selected.value = profile.id
        compose.setContent {
            val shownFilter by filter.collectAsState()
            AynTheme {
                CompositionLocalProvider(LocalContentFilter provides shownFilter) {
                    MobileShell(ShellOpening(profile, onSwitchProfile = {})) { chrome ->
                        entry<TabKey> { key ->
                            Column {
                                ShellTopBar(chrome)
                                Text("${key.tab.label} page")
                            }
                        }
                        entry<SettingsKey> {}
                        entry<SearchKey> {
                            SearchPage(
                                viewModel = viewModel { SearchViewModel(extractor, settings) },
                                menu = viewModel {
                                    VideoMenuViewModel(watch, FakePlaylistStore(), settings, MenuBlocking(FakeFilterStore(), FakeProfileStore(), FakeProfileLocks(), TimeSource.Monotonic))
                                },
                                chrome = chrome,
                                marks = remember { watch.history().map { it.marks() } },
                                onPlay = { video, _ -> played += video },
                                onPlayShort = { short, run -> playedShorts += short to run },
                                onOpenChannel = { channels += it },
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        openSearch(if (profile.isKids) "Search what’s approved" else "Search YouTube")
    }

    private fun openSearch(pill: String) {
        compose.onNode(hasText(pill) and hasClickAction()).performClick()
        compose.waitForIdle()
    }

    private fun field(): SemanticsNodeInteraction = compose.onNode(hasSetTextAction())

    private fun fieldText(): String = field().fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    private fun search(query: String) {
        field().performTextInput(query)
        field().performImeAction()
        compose.waitForIdle()
    }

    private fun click(label: String) {
        compose.onNode(hasText(label) and hasClickAction() and !isSelectable()).performClick()
        compose.waitForIdle()
    }

    private fun systemBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    /** Past the ViewModel's suggestion debounce, which waits on the main looper. */
    private fun afterDebounce() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        compose.waitForIdle()
    }

    /** Where each key sits in the results list (it scrolls up and down; the Shorts shelf sideways). */
    private fun indexOf(key: Any): Int =
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).fetchSemanticsNode().config[SemanticsProperties.IndexForKey](key)

    private val lofiVideos: List<VideoItem> get() = lofi.items.filterIsInstance<VideoItem>()

    @Test
    fun thePillOpensSearchOnTheEmptyFocusedFieldWithItsHint() {
        launch()

        field().assertIsFocused()
        assertEquals("", fieldText())
        compose.onNodeWithText("Search YouTube").assertExists()
        compose.onNodeWithText("Type, or tap the keyboard’s mic and say it.").assertExists()
    }

    @Test
    fun typingShowsYouTubesSuggestionsAndTappingOneSearchesIt() {
        val typed = "lofi hip"
        extractor.suggestionAnswers[typed] = recorded(SUGGESTIONS_FLOW) { suggestions(typed) }
        extractor.answers[FIXED_SEARCH_QUERY] = lofi
        launch()

        field().performTextInput(typed)
        afterDebounce()
        compose.onNodeWithText("Type, or tap the keyboard’s mic and say it.").assertDoesNotExist()
        click(FIXED_SEARCH_QUERY)

        compose.onNodeWithText(lofiVideos.first().title).assertExists()
        assertEquals(listOf(0, 1, 2), lofi.items.take(3).map { indexOf(it.listingKey) })
    }

    @Test
    fun clearEmptiesTheFieldAndBringsTheHintBack() {
        launch()
        field().performTextInput("lofi")

        compose.onNode(hasContentDescription("Clear search")).performClick()
        compose.waitForIdle()

        assertEquals("", fieldText())
        compose.onNodeWithText("Type, or tap the keyboard’s mic and say it.").assertExists()
    }

    @Test
    fun whileTheSearchRunsRowsWaitAsSkeletonsThenTheAnswerLands() {
        val gate = CompletableDeferred<Unit>()
        extractor.searchGate = gate
        extractor.answers[FIXED_SEARCH_QUERY] = lofi
        launch()
        search(FIXED_SEARCH_QUERY)

        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithText(lofiVideos.first().title).assertDoesNotExist()
        gate.complete(Unit)
        compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodes(hasText(lofiVideos.first().title)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun backOnTheAnswerClosesSearchToTheTabAndThePillReopensItEmpty() {
        extractor.answers[FIXED_SEARCH_QUERY] = lofi
        launch()
        search(FIXED_SEARCH_QUERY)

        systemBack()

        compose.onNodeWithText("Home page").assertExists()
        compose.onNodeWithText(lofiVideos.first().title).assertDoesNotExist()
        openSearch("Search YouTube")
        assertEquals("", fieldText())
        compose.onNodeWithText("Type, or tap the keyboard’s mic and say it.").assertExists()
    }

    @Test
    fun theArrowClosesSearch() {
        launch()

        compose.onNode(hasContentDescription("Close Search")).performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Home page").assertExists()
    }

    @Test
    fun aTapOnThePillOverTheAnswerEditsWithTheQuerySelectedAndSearchingItAgainKeepsTheAnswer() {
        extractor.answers[FIXED_SEARCH_QUERY] = lofi
        launch()
        search(FIXED_SEARCH_QUERY)

        click(FIXED_SEARCH_QUERY)

        field().assertIsFocused()
        assertEquals(FIXED_SEARCH_QUERY, fieldText())
        assertEquals(TextRange(0, FIXED_SEARCH_QUERY.length), field().fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
        compose.onNodeWithText("Typing replaces it", substring = true).assertExists()
        extractor.answers.clear()
        field().performImeAction()
        compose.waitForIdle()
        compose.onNodeWithText(lofiVideos.first().title).assertExists()
    }

    @Test
    fun aChannelYouTubePutsFirstLeadsTheAnswerAndATapOpensIt() {
        extractor.answers[CHANNEL_SEARCH_QUERY] = recorded(CHANNEL_SEARCH_FLOW) { search(CHANNEL_SEARCH_QUERY) }
        launch()
        search(CHANNEL_SEARCH_QUERY)

        compose.onNodeWithText("21M subscribers").assertExists()
        compose.onNodeWithText(RecordedChannelSearch.FIRST_VIDEO_TITLE).assertExists()
        compose.onNodeWithText("Veritasium\n10M views · ${RecordedChannelSearch.FIRST_VIDEO_AGE}").assertExists()
        compose.onNode(hasText(RecordedChannelSearch.CHANNEL_NAME) and hasClickAction()).performClick()

        assertEquals(listOf(RecordedChannelSearch.CHANNEL_URL), channels)
    }

    @Test
    fun withShowShortsOnTheShelfFollowsTheFirstResultAndATapPlaysAShort() {
        extractor.answers[FIXED_SEARCH_QUERY] = lofi
        launch(showShorts = true)
        search(FIXED_SEARCH_QUERY)

        assertEquals(listOf(0, 1, 2), listOf(lofi.items[0].listingKey, "shorts", lofi.items[1].listingKey).map(::indexOf))
        val short = lofi.shorts.first()
        compose.onNode(hasText(short.title) and hasClickAction()).performClick()

        val (played, run) = playedShorts.single()
        assertEquals(short.id, played.id)
        assertEquals(lofi.shorts.map { it.id }, run.shorts.value.map { it.id })
    }

    @Test
    fun aShortMenusPlayPlaysItAsAShort() {
        extractor.answers[FIXED_SEARCH_QUERY] = lofi
        launch(showShorts = true)
        search(FIXED_SEARCH_QUERY)
        val short = lofi.shorts.first()

        compose.onNode(hasText(short.title) and hasClickAction()).performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNode(hasText("Play") and hasClickAction()).performClick()
        compose.waitForIdle()

        assertEquals(short.id, playedShorts.single().first.id)
        assertTrue(played.isEmpty())
    }

    @Test
    fun withShowShortsOffTheAnswerHasNoShelf() {
        extractor.answers[FIXED_SEARCH_QUERY] = lofi
        launch(showShorts = false)
        search(FIXED_SEARCH_QUERY)

        compose.onNodeWithText("Shorts").assertDoesNotExist()
        assertEquals(1, indexOf(lofi.items[1].listingKey))
    }

    @Test
    fun aRowPlaysOnTapAndAPartWatchedOneShowsHowFar() {
        val first = lofiVideos.first()
        watch.entries.value = listOf(WatchEntry(first.id, first.title, null, null, null, 30_000, 120_000, watched = false, 1, 1))
        extractor.answers[FIXED_SEARCH_QUERY] = lofi
        launch()
        search(FIXED_SEARCH_QUERY)

        val bars = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).fetchSemanticsNodes()
        assertEquals(listOf(0.25f), bars.map { it.config[SemanticsProperties.ProgressBarRangeInfo].current })
        compose.onNode(hasText(first.title) and hasClickAction()).performClick()
        assertEquals(listOf(first), played)
    }

    @Test
    fun aRowsMoreOpensTheCardMenu() {
        extractor.answers[FIXED_SEARCH_QUERY] = lofi
        launch()
        search(FIXED_SEARCH_QUERY)

        compose.onAllNodes(hasContentDescription("More"))[0].performClick()
        compose.waitForIdle()

        compose.onNode(hasText("Save to playlist") and hasClickAction()).assertExists()
    }

    @Test
    fun aLongPressOnARowOpensTheCardMenu() {
        extractor.answers[FIXED_SEARCH_QUERY] = lofi
        launch()
        search(FIXED_SEARCH_QUERY)

        compose.onNode(hasText(lofiVideos.first().title) and hasClickAction()).performTouchInput { longClick() }
        compose.waitForIdle()

        compose.onNode(hasText("Save to playlist") and hasClickAction()).assertExists()
    }

    @Test
    fun aLongPressOnAShortOpensTheCardMenu() {
        extractor.answers[FIXED_SEARCH_QUERY] = lofi
        launch(showShorts = true)
        search(FIXED_SEARCH_QUERY)

        compose.onNode(hasText(lofi.shorts.first().title) and hasClickAction()).performTouchInput { longClick() }
        compose.waitForIdle()

        compose.onNode(hasText("Save to playlist") and hasClickAction()).assertExists()
    }

    @Test
    fun aBlockedShortLeavesTheShelf() {
        extractor.answers[FIXED_SEARCH_QUERY] = lofi
        launch(showShorts = true)
        search(FIXED_SEARCH_QUERY)
        val short = lofi.shorts.first()
        compose.onNodeWithText(short.title).assertExists()

        filter.value = blockingVideos(short.id.value)
        compose.waitForIdle()

        compose.onNodeWithText(short.title).assertDoesNotExist()
    }

    @Test
    fun aBlockTakesItsRowOutAndBlockingEveryResultShowsNoResults() {
        extractor.answers[FIXED_SEARCH_QUERY] = lofi
        launch()
        search(FIXED_SEARCH_QUERY)

        filter.value = blockingVideos(lofiVideos.first().id.value)
        compose.waitForIdle()
        compose.onNodeWithText(lofiVideos.first().title).assertDoesNotExist()
        compose.onNodeWithText(lofiVideos[1].title).assertExists()

        filter.value = blockingVideos(*lofiVideos.map { it.id.value }.toTypedArray())
        compose.waitForIdle()
        compose.onNodeWithText("Nothing matched that search").assertExists()
    }

    @Test
    fun noResultsSaysSoAndEditSearchReturnsToTypingWithTheQuerySelected() {
        extractor.answers["zzqxjvkw"] = ItemsPage(items = emptyList(), next = null)
        launch()
        search("zzqxjvkw")

        compose.onNodeWithText("No results").assertExists()
        compose.onNodeWithText("Nothing matched that search").assertExists()
        click("Edit search")

        field().assertIsFocused()
        assertEquals(TextRange(0, "zzqxjvkw".length), field().fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
    }

    @Test
    fun offlineKeepsTheSearchAndTryAgainSearchesIt() {
        extractor.failure = UnknownHostException("www.youtube.com")
        launch()
        search(FIXED_SEARCH_QUERY)

        compose.onNodeWithText("No connection to YouTube").assertExists()
        compose.onNodeWithText("Your search is kept. Check that the phone is online and try again.").assertExists()
        extractor.answers[FIXED_SEARCH_QUERY] = lofi
        click("Try again")

        compose.onNodeWithText(lofiVideos.first().title).assertExists()
    }

    @Test
    fun anUnreadableReplySaysYouTubeChangedSomething() {
        launch()
        search(FIXED_SEARCH_QUERY)

        compose.onNodeWithText("YouTube changed something").assertExists()
        compose.onNodeWithText("Search didn’t go through").assertExists()
        compose.onNode(hasText("Try again") and hasClickAction()).assertExists()
        compose.onNode(hasText("Edit search") and hasClickAction()).assertExists()
    }

    @Test
    fun aKidsProfilesSearchSaysWhatItLooksThroughItsRowsHaveNoMenuAndNoMatchAsksAGrownUp() {
        extractor.answers[FIXED_SEARCH_QUERY] = lofi
        extractor.answers["trucks"] = ItemsPage(items = emptyList(), next = null)
        launch(ZAINAB)

        compose.onNodeWithText("Only what’s approved for Zainab").assertExists()
        compose.onNodeWithText("Nothing is asked of YouTube, so there are no suggestions.").assertExists()
        search(FIXED_SEARCH_QUERY)
        compose.onNodeWithText(lofiVideos.first().title).assertExists()
        assertTrue(compose.onAllNodes(hasContentDescription("More")).fetchSemanticsNodes().isEmpty())
        compose.onNode(hasText(lofiVideos.first().title) and hasClickAction()).performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNode(hasText("Save to playlist")).assertDoesNotExist()

        click(FIXED_SEARCH_QUERY)
        field().performTextInput("trucks")
        field().performImeAction()
        compose.waitForIdle()
        compose.onNodeWithText("No match").assertExists()
        compose.onNodeWithText("Nothing approved matches “trucks”").assertExists()
        compose.onNodeWithText("Ask a grown-up to approve more.").assertExists()
    }

    private companion object {
        val ARSHAD = Profile(1, "Arshad", ProfileTint.Amber, isOwner = true, lock = LockKind.Sequence)
        val ZAINAB = Profile(4, "Zainab", ProfileTint.Violet, isOwner = false, lock = null, isKids = true)
    }
}
