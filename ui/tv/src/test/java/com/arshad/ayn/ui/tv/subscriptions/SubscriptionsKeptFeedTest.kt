package com.arshad.ayn.ui.tv.subscriptions

import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.lifecycle.Lifecycle
import com.arshad.ayn.core.FixedClock
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.access.newpipe.RECORDED_CHANNEL_URL
import com.arshad.ayn.core.access.newpipe.RecordedChannel
import com.arshad.ayn.core.access.recorded
import com.arshad.ayn.core.store.FakeChannelFeedStore
import com.arshad.ayn.core.store.FakeSubscriptionStore
import com.arshad.ayn.core.store.StoredChannelFeed
import com.arshad.ayn.feature.subscriptions.SubscriptionsFeed
import com.arshad.ayn.core.access.SubscriptionsExtractor
import com.arshad.ayn.ui.tv.TestShell
import com.arshad.ayn.ui.tv.assertPlayerShows
import com.arshad.ayn.ui.tv.dpadSelect
import com.arshad.ayn.ui.tv.installOfflineImageLoader
import com.arshad.ayn.ui.tv.pressBack
import java.util.TimeZone
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The kept feed through the real shell (PERF P9c): Subscriptions shows it at once, a fresh feed
 * that arrives after a key press waits, BACK from the player never lands it, and the next arrival
 * from the rail (focus finding the page again though the card it remembered is gone) or the app's
 * return to the foreground does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp")
class SubscriptionsKeptFeedTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val extractor = SubscriptionsExtractor()
    private val kept = FakeChannelFeedStore()
    private val zone = TimeZone.getDefault()

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        installOfflineImageLoader(compose.activity)
        extractor.feeds[RECORDED_CHANNEL_URL] = recorded("channel-feed") { channelFeed(RECORDED_CHANNEL_URL) }
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(zone)
    }

    @Test
    fun aFreshFeedHeldAfterAKeyLandsOnTheNextRailArrivalNotOnBackFromThePlayer() {
        val gate = CompletableDeferred<Unit>()
        extractor.feedGate = gate
        kept.keep(RECORDED_CHANNEL_URL, StoredChannelFeed(listOf(KEPT_VIDEO), emptyList(), RecordedChannel.FEED_CLOCK - 1.hours))
        open()
        compose.onNodeWithText(KEPT_TITLE).assertExists()
        // The feed's one card holds focus (arrival); its title sits under the clickable picture.
        val keptCard = compose.onNode(isFocused() and hasClickAction()).fetchSemanticsNode().boundsInRoot.center

        compose.onNode(isFocused()).dpadSelect()
        compose.waitForIdle()
        compose.assertPlayerShows()
        gate.complete(Unit)
        compose.waitForIdle()
        compose.pressBack()

        compose.onNodeWithText(KEPT_TITLE).assertExists()
        val focused = compose.onNode(isFocused()).fetchSemanticsNode().boundsInRoot.center
        assertEquals("BACK returns to the kept card", keptCard, focused)

        toRail()
        rail("Home").dpadSelect()
        compose.waitForIdle()
        toRail()
        rail("Subscriptions").dpadSelect()
        compose.waitForIdle()

        compose.onNodeWithText(KEPT_TITLE).assertDoesNotExist()
        compose.onNodeWithText("Last week").assertExists()
        compose.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()
        compose.onNode(isFocused() and hasClickAction()).assertExists()
        rail("Subscriptions").assertIsNotFocused()
    }

    @Test
    fun aFreshFeedHeldAfterAKeyLandsWhenTheAppReturnsToTheForeground() {
        val gate = CompletableDeferred<Unit>()
        extractor.feedGate = gate
        kept.keep(RECORDED_CHANNEL_URL, StoredChannelFeed(listOf(KEPT_VIDEO), emptyList(), RecordedChannel.FEED_CLOCK - 1.hours))
        open()
        compose.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionDown) }
        gate.complete(Unit)
        compose.waitForIdle()
        compose.onNodeWithText(KEPT_TITLE).assertExists()

        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()

        compose.onNodeWithText(KEPT_TITLE).assertDoesNotExist()
        compose.onNodeWithText("Last week").assertExists()
    }

    private fun open() {
        val clock = FixedClock(RecordedChannel.FEED_CLOCK)
        compose.setContent {
            val writes = rememberCoroutineScope()
            val feed = remember { SubscriptionsFeed(extractor, clock, channelFeeds = kept, writeScope = writes) }
            TestShell(extractor, feed, remember { FakeSubscriptionStore(RECORDED_CHANNEL_URL) })
        }
        compose.waitForIdle()
        rail("Home").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        rail("Subscriptions").dpadSelect()
        compose.waitForIdle()
    }

    /** The page holds focus with the rail closed: LEFT from the first column opens it. */
    private fun toRail() {
        compose.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionLeft) }
        compose.waitForIdle()
    }

    // Pages compose before the rail, so the rail's item is the last match.
    private fun rail(label: String) = compose.onAllNodes(hasText(label) and hasClickAction()).onLast()

    private companion object {
        const val KEPT_TITLE = "A video only the kept feed has"
        val KEPT_VIDEO = VideoItem(
            id = VideoId("keptkeptkep"),
            title = KEPT_TITLE,
            uploaderName = "Channel 1",
            uploaderUrl = RECORDED_CHANNEL_URL,
            durationSeconds = -1,
            viewCount = -1,
            textualUploadDate = null,
            uploadDate = RecordedChannel.FEED_CLOCK - 30.hours,
            thumbnailUrl = null,
            isLive = false,
        )
    }
}
