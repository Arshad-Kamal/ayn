package com.arshad.ayn.ui.mobile.menu

import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.arshad.ayn.core.FixedClock
import com.arshad.ayn.core.access.Branding
import com.arshad.ayn.core.access.BrandingLookup
import com.arshad.ayn.core.access.BrandingSource
import com.arshad.ayn.core.access.BrowseKiosk
import com.arshad.ayn.core.access.HomeExtractor
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.access.newpipe.FEED_NEWEST_VIDEO_FLOW
import com.arshad.ayn.core.access.newpipe.KIOSK_FLOWS
import com.arshad.ayn.core.access.newpipe.RECORDED_CHANNEL_URL
import com.arshad.ayn.core.access.newpipe.RecordedChannel
import com.arshad.ayn.core.access.recorded
import com.arshad.ayn.core.filter.ContentFilter
import com.arshad.ayn.core.lock.FakeProfileLocks
import com.arshad.ayn.core.lock.LockKind
import com.arshad.ayn.core.lock.LockSecret
import com.arshad.ayn.core.lock.Press
import com.arshad.ayn.core.store.BlockedVideo
import com.arshad.ayn.core.store.FakeFetchAttemptLog
import com.arshad.ayn.core.store.FakeFilterStore
import com.arshad.ayn.core.store.FakeForYouStore
import com.arshad.ayn.core.store.FakePlaylistStore
import com.arshad.ayn.core.store.FakeProfileSettingsStore
import com.arshad.ayn.core.store.FakeProfileStore
import com.arshad.ayn.core.store.FakeRelatedListStore
import com.arshad.ayn.core.store.FakeSubscriptionStore
import com.arshad.ayn.core.store.FakeWatchStore
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.ProfileSettings
import com.arshad.ayn.core.store.ProfileTint
import com.arshad.ayn.core.store.RelatedList
import com.arshad.ayn.core.store.WatchEntry
import com.arshad.ayn.core.store.canonicalChannelUrl
import com.arshad.ayn.feature.CardBranding
import com.arshad.ayn.feature.home.homeViewModel
import com.arshad.ayn.feature.library.MenuBlocking
import com.arshad.ayn.feature.library.VideoMenuViewModel
import com.arshad.ayn.feature.subscriptions.SubscriptionsFeed
import com.arshad.ayn.ui.mobile.common.LocalCardBranding
import com.arshad.ayn.ui.mobile.common.LocalContentFilter
import com.arshad.ayn.ui.mobile.common.ACTION_NOTICE_MS
import com.arshad.ayn.ui.mobile.home.HomePage
import com.arshad.ayn.ui.mobile.installOfflineImageLoader
import com.arshad.ayn.ui.mobile.shell.MobileShell
import com.arshad.ayn.ui.mobile.shell.SettingsKey
import com.arshad.ayn.ui.mobile.shell.ShellOpening
import com.arshad.ayn.ui.mobile.shell.Tab
import com.arshad.ayn.ui.mobile.shell.TabKey
import com.arshad.ayn.ui.mobile.theme.AynTheme
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * The card menu on the phone's Home (`m6-home-and-menus` states 9–19, M6-D11) through the real
 * [HomePage], [VideoMenuViewModel] and HomeViewModel over the recorded Rick Astley feed: rows per
 * source and profile, Back stepping out a stage at a time, Save to playlist and a new playlist,
 * Block for all or chosen profiles (the owner's lock for anyone else), a block taking the cards
 * off Home with Undo, Not interested with its Hidden snackbar, and Approve for a kid.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class HomeMenuTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val extractor = HomeExtractor().apply {
        feed = recorded("channel-feed") { channelFeed(RECORDED_CHANNEL_URL) }
        heroPage = recorded(FEED_NEWEST_VIDEO_FLOW) { video(VideoId(RecordedChannel.FEED_ENTRIES.first().first)) }
        live = recorded(KIOSK_FLOWS.getValue(BrowseKiosk.LIVE)) { browse(BrowseKiosk.LIVE) }
    }
    private val watch = FakeWatchStore()

    /** The cards' DeArrow faces; `null` = YouTube's own. */
    private var branding: CardBranding? = null
    private val brandingScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun cancelBranding() {
        brandingScope.cancel()
    }
    private val related = FakeRelatedListStore()
    private val playlists = FakePlaylistStore()
    private val filters = FakeFilterStore()
    private val people = FakeProfileStore(listOf(ARSHAD, MIRA, ZAINAB))
    private val locks = FakeProfileLocks().apply {
        ownerId = ARSHAD.id
        locks[ARSHAD.id] = LockSecret.Sequence(SEQUENCE)
    }

    @Before
    fun setUp() {
        installOfflineImageLoader(compose.activity)
    }

    private fun launch(profile: Profile = ARSHAD) {
        locks.sessionId = profile.id
        val clock = FixedClock(RecordedChannel.FEED_CLOCK)
        val settings = FakeProfileSettingsStore(selectedId = profile.id)
        compose.setContent {
            val filter by remember { filters.rules(profile.id).map { ContentFilter.of(ProfileSettings(), it) } }.collectAsState(initial = null)
            AynTheme {
                CompositionLocalProvider(LocalContentFilter provides filter, LocalCardBranding provides branding) {
                    MobileShell(ShellOpening(profile, onSwitchProfile = {})) { chrome ->
                        entry<TabKey> { key ->
                            if (key.tab == Tab.Home) {
                                HomePage(
                                    viewModel = viewModel {
                                        homeViewModel(
                                            extractor, SubscriptionsFeed(extractor, clock), FakeSubscriptionStore(RECORDED_CHANNEL_URL), watch, playlists,
                                            related, FakeForYouStore(), FakeFetchAttemptLog(Clock.System), settings, compute = Dispatchers.Main,
                                        )
                                    },
                                    menu = viewModel { VideoMenuViewModel(watch, playlists, settings, MenuBlocking(filters, people, locks, TimeSource.Monotonic)) },
                                    chrome = chrome,
                                    onPlay = { _, _ -> },
                                    onOpenChannel = {},
                                )
                            } else {
                                Text("${key.tab.label} page")
                            }
                        }
                        entry<SettingsKey> {}
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun home(): SemanticsNodeInteraction = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))

    private fun row(label: String): SemanticsNodeInteraction = compose.onNode(hasText(label) and hasClickAction())

    private fun exists(text: String, substring: Boolean = false): Boolean =
        compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()

    /** Holds a card down (its long press), Continue watching's Enigma unless said otherwise. */
    private fun holdCard(title: String = ENIGMA) {
        home().performScrollToNode(hasText(title))
        compose.onNode(hasText(title) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick)).performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForIdle()
    }

    /** System Back, reaching the window on top (a sheet or a page over the shell). */
    private fun systemBack() {
        compose.runOnUiThread { (ShadowDialog.getLatestDialog() as ComponentDialog).onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun tap(label: String) {
        row(label).performClick()
        compose.waitForIdle()
    }

    @Test
    fun theOwnersMenuLeadsWithResumeAndOffersEachKidAndBlockButNoForYouRows() {
        continueWatchingEnigma()
        launch()

        holdCard()

        listOf("Resume from 34:51", "Play from the start", "Save to playlist", "Go to channel", "Approve for Zainab", "Block for Zainab", "Block Veritasium", "Block this video")
            .forEach { row(it).assertExists() }
        compose.onNodeWithText("This video, or all of Veritasium").assertExists()
        row("Not interested").assertDoesNotExist()
    }

    @Test
    fun aKidsProfileGetsNoBlockOrApproveRows() {
        continueWatchingEnigma()
        launch(ZAINAB)

        holdCard()

        row("Save to playlist").assertExists()
        listOf("Block this video", "Block Veritasium", "Approve for Zainab").forEach { row(it).assertDoesNotExist() }
    }

    @Test
    fun backStepsFromSaveToPlaylistToTheMenuThenClosesIt() {
        continueWatchingEnigma()
        launch()
        holdCard()
        tap("Save to playlist")
        compose.onNode(hasText("Watch later") and isToggleable()).assertExists()

        systemBack()
        row("Resume from 34:51").assertExists()
        compose.onNode(hasText("Watch later") and isToggleable()).assertDoesNotExist()

        systemBack()
        row("Save to playlist").assertDoesNotExist()
    }

    @Test
    fun aSwitchSavesAtOnce() {
        continueWatchingEnigma()
        launch()
        holdCard()
        tap("Save to playlist")
        val later = compose.onNode(hasText("Watch later") and isToggleable())
        later.assertIsOff()

        later.performClick()
        compose.waitForIdle()

        later.assertIsOn()
        assertTrue(watchLaterIds().contains(ENIGMA_ID))
    }

    @Test
    fun aNewPlaylistIsMadeWithTheVideoInItAndSaveToShowsItOn() {
        continueWatchingEnigma()
        launch()
        holdCard()
        tap("Save to playlist")
        tap("New playlist")
        compose.onNodeWithText("Name the playlist").assertExists()
        compose.onNodeWithText("$ENIGMA goes in first.").assertExists()

        compose.onNode(hasSetTextAction()).performTextInput("Poker and heists")
        tap("Create and save")

        compose.onNodeWithText("Name the playlist").assertDoesNotExist()
        compose.onNode(hasText("Poker and heists") and isToggleable()).assertIsOn()
    }

    /** KP-D19: a block takes the channel's cards off Home at once (the hero here; History's
     *  rows are filtered at the store, outside this page). */
    @Test
    fun blockingAChannelInAllProfilesTakesItsCardsOffHomeAndUndoBringsThemBack() {
        launch()
        compose.onNodeWithText(HERO_TITLE).assertExists()
        compose.onAllNodes(hasContentDescription("More"))[0].performClick()
        compose.waitForIdle()

        tap("Block Rick Astley")
        compose.onNodeWithText("Block in which profiles?").assertExists()
        compose.onNodeWithText("Arshad, Mira and Zainab").assertExists()
        tap("All profiles")

        compose.onNodeWithText("Rick Astley blocked · in all profiles").assertExists()
        assertTrue("the hero left Home", !exists(HERO_TITLE))
        assertEquals(listOf(false, true, true), listOf(ARSHAD, MIRA, ZAINAB).map { p -> filters.rulesById.value[p.id]?.rules.orEmpty().any { it.byOwner } })

        row("Undo").performClick()
        compose.waitForIdle()
        assertTrue("the hero is back", exists(HERO_TITLE))
        assertTrue(filters.rulesById.value.values.all { it.rules.isEmpty() })
    }

    @Test
    fun anotherProfilesAllProfilesAsksForTheOwnersSequenceThenBlocksEverywhere() {
        continueWatchingEnigma()
        launch(MIRA)
        holdCard()
        tap("Block this video")
        compose.onNodeWithText("Arshad, Mira and Zainab. Asks for Arshad’s sequence first").assertExists()

        tap("All profiles")
        compose.onNodeWithText("Enter Arshad’s sequence, then tap OK.").assertExists()
        SEQUENCE.forEach { press -> compose.onNode(hasContentDescription(press.name) and hasClickAction()).performClick() }
        compose.onNode(hasContentDescription("OK") and hasClickAction()).performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Video blocked · in all profiles").assertExists()
        assertEquals(listOf(ARSHAD.id), locks.tries)
    }

    @Test
    fun cancelOnTheOwnersLockReturnsToTheSheetAndBlockingOnlyInYourOwnNeedsNoLock() {
        continueWatchingEnigma()
        launch(MIRA)
        holdCard()
        tap("Block this video")
        tap("All profiles")

        tap("Cancel")
        compose.onNodeWithText("Enter Arshad’s sequence, then tap OK.").assertDoesNotExist()
        compose.onNodeWithText("Block in which profiles?").assertExists()

        tap("Choose profiles")
        compose.onNodeWithText("Your own filter").assertExists()
        tap("Block in 1 profile")

        compose.onNodeWithText("Video blocked · in Mira").assertExists()
        assertTrue(locks.tries.isEmpty())
    }

    @Test
    fun notInterestedHidesTheForYouPickWithASnackbarWhoseUndoBringsItBack() {
        keepForYou(forYouPick("fypick00001", "A For you pick"), forYouPick("fypick00002", "The next pick"))
        launch()
        compose.waitUntil(5_000) { exists("The next pick") }

        holdCard("The next pick")
        compose.onNodeWithText("Because you watched A seed").assertExists()
        tap("Not interested")

        compose.onNodeWithText("Hidden · You won’t see this video in For you").assertExists()
        assertTrue("the pick left Home", !exists("The next pick"))
        row("Undo").performClick()
        compose.waitForIdle()
        assertTrue("the pick is back", exists("The next pick"))
    }

    /** The two snackbars never overlap: opening another menu ends the Hidden one (the hide stands). */
    @Test
    fun openingAnotherMenuEndsTheHiddenSnackbarAndTheHideStands() {
        keepForYou(forYouPick("fypick00001", "A For you pick"), forYouPick("fypick00002", "The next pick"), forYouPick("fypick00003", "A third pick"))
        launch()
        compose.waitUntil(5_000) { exists("A third pick") }
        holdCard("The next pick")
        tap("Not interested")

        holdCard("A third pick")

        assertTrue("the Hidden snackbar ended", !exists("Hidden · You won’t see this video in For you"))
        assertTrue("the pick stays hidden", !exists("The next pick"))
    }

    @Test
    fun theBlockedSnackbarGoesAfterSixSecondsAndTheBlockStands() {
        blockTheHerosChannel()

        compose.mainClock.advanceTimeBy(ACTION_NOTICE_MS + 500)
        compose.waitForIdle()

        assertTrue("the snackbar went", !exists("Rick Astley blocked · in all profiles"))
        assertTrue("the hero stays off Home", !exists(HERO_TITLE))
        assertTrue(filters.rulesById.value.values.any { it.rules.isNotEmpty() })
    }

    @Test
    fun aScrollEndsTheSnackbar() {
        blockTheHerosChannel()

        home().performTouchInput { swipeUp() }
        compose.waitForIdle()

        assertTrue("the snackbar went", !exists("Rick Astley blocked · in all profiles"))
    }

    private fun blockTheHerosChannel() {
        launch()
        compose.onAllNodes(hasContentDescription("More"))[0].performClick()
        compose.waitForIdle()
        tap("Block Rick Astley")
        tap("All profiles")
        compose.onNodeWithText("Rick Astley blocked · in all profiles").assertExists()
    }

    @Test
    fun theHerosMoreOpensItsMenuWithForYousReason() {
        keepForYou(forYouPick("fypick00001", "A For you pick"))
        launch()
        compose.waitUntil(5_000) { exists("A For you pick") }

        compose.onAllNodes(hasContentDescription("More"))[0].performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Because you watched A seed").assertExists()
        row("Don’t recommend this channel").assertExists()
    }

    @Test
    fun approvingTheWholeChannelForAKidSaysSoAndUndoTakesItBack() {
        continueWatchingEnigma()
        launch()
        holdCard()

        tap("Approve for Zainab")
        compose.onNodeWithText("Just this video, or the channel?").assertExists()
        compose.onNodeWithText("Change it any time on Zainab’s Parent page.").assertExists()
        tap("Approve the whole channel")

        compose.onNodeWithText("Approved for Zainab · Veritasium").assertExists()
        assertEquals(listOf(canonicalChannelUrl(VERITASIUM)), filters.approved.value[ZAINAB.id].orEmpty().map { it.channelUrl })

        row("Undo").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { filters.approved.value[ZAINAB.id].orEmpty().isEmpty() }
    }

    /** PAF-D4: the notice names the video as its card showed it. */
    @Test
    fun approvingTheVideoNamesTheTitleItsCardShows() {
        enigmaShownAs(DEARROW_TITLE)
        continueWatchingEnigma()
        launch()
        holdCard(DEARROW_TITLE)

        tap("Approve for Zainab")
        tap("Approve this video")

        compose.onNodeWithText("Approved for Zainab · $DEARROW_TITLE").assertExists()
    }

    /** A blocked video is listed by the title its card showed, from either Block row. */
    @Test
    fun blockingTheVideoKeepsTheTitleItsCardShows() {
        enigmaShownAs(DEARROW_TITLE)
        continueWatchingEnigma()
        launch()
        holdCard(DEARROW_TITLE)

        tap("Block for Zainab")
        assertEquals(listOf(DEARROW_TITLE), filters.rulesById.value[ZAINAB.id]?.rules.orEmpty().map { (it.rule as BlockedVideo).title })
        holdCard(DEARROW_TITLE)
        tap("Block this video")
        tap("All profiles")

        assertEquals(listOf(DEARROW_TITLE), filters.rulesById.value[MIRA.id]?.rules.orEmpty().map { (it.rule as BlockedVideo).title })
    }

    private fun enigmaShownAs(title: String) {
        branding = FakeProfileSettingsStore(selectedId = ARSHAD.id).let { settings ->
            CardBranding(
                BrandingSource { id, _ -> if (id.value == ENIGMA_ID) BrandingLookup.Found(Branding(title, null)) else BrandingLookup.None },
                settings,
                settings.selected.filterNotNull(),
                brandingScope,
            )
        }
    }

    private fun continueWatchingEnigma() {
        watch.entries.value = listOf(WatchEntry(VideoId(ENIGMA_ID), ENIGMA, "Veritasium", VERITASIUM, null, 2_091_000, 2_862_000, watched = false, 1, 1))
    }

    private fun watchLaterIds(): List<String> = runBlocking { playlists.watchLater().first() }.map { it.video.id.value }

    private fun keepForYou(vararg picks: VideoItem) {
        val seedId = VideoId("seed0000001")
        watch.entries.value = listOf(
            WatchEntry(seedId, "A seed", "Seeder", "https://www.youtube.com/channel/UCseed", null, 600_000, 600_000, watched = true, WATCHED_AT, WATCHED_AT),
        )
        related.kept[seedId] = RelatedList(picks.toList(), fetchedAt = RecordedChannel.FEED_CLOCK, failedAt = null)
    }

    private fun forYouPick(id: String, title: String): VideoItem =
        VideoItem(VideoId(id), title, "Picker $id", "https://www.youtube.com/channel/UC$id", 600, 1, null, null, null, isLive = false)

    private companion object {
        val ARSHAD = Profile(1, "Arshad", ProfileTint.Amber, isOwner = true, lock = LockKind.Sequence)
        val MIRA = Profile(2, "Mira", ProfileTint.Rose, isOwner = false, lock = LockKind.Pin)
        val ZAINAB = Profile(3, "Zainab", ProfileTint.Teal, isOwner = false, lock = null, isKids = true)
        val SEQUENCE = listOf(Press.Up, Press.Up, Press.Down, Press.Left, Press.Right, Press.Down)
        const val HERO_TITLE = "Rick Astley - Angels On My Side (Live at The O2, London, April 2026)"
        const val ENIGMA_ID = "JsBZOcqZerk"
        const val ENIGMA = "Enigma"
        const val DEARROW_TITLE = "How the Enigma machine worked"
        const val VERITASIUM = "https://www.youtube.com/channel/UCHnyfMqiRRG1u-2MsSQLbXA"
        val WATCHED_AT = (RecordedChannel.FEED_CLOCK - 1.hours).toEpochMilliseconds()
    }
}
