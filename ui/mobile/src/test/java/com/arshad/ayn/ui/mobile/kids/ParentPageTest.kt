package com.arshad.ayn.ui.mobile.kids

import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.arshad.ayn.core.access.ItemsPage
import com.arshad.ayn.core.access.ServerClock
import com.arshad.ayn.core.access.SubscriptionsExtractor
import com.arshad.ayn.core.access.VideoId
import com.arshad.ayn.core.access.VideoItem
import com.arshad.ayn.core.lock.LockKind
import com.arshad.ayn.core.lock.LockSecret
import com.arshad.ayn.core.lock.OwnerPass
import com.arshad.ayn.core.store.ApprovedVideo
import com.arshad.ayn.core.store.Bedtime
import com.arshad.ayn.core.store.BlockedVideo
import com.arshad.ayn.core.store.DayTime
import com.arshad.ayn.core.store.FakeKidsReviewKeeps
import com.arshad.ayn.core.store.FakeKidsTimeStore
import com.arshad.ayn.core.store.FakeSubscriptionStore
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.ProfileRestrictions
import com.arshad.ayn.core.store.ProfileRules
import com.arshad.ayn.core.store.ProfileTint
import com.arshad.ayn.core.store.StoredRule
import com.arshad.ayn.core.store.Subscription
import com.arshad.ayn.feature.kids.ParentReviewViewModel
import com.arshad.ayn.feature.kids.ParentViewModel
import com.arshad.ayn.feature.settings.DEFAULT_DAILY_LIMITS
import com.arshad.ayn.feature.subscriptions.SubscriptionsFeed
import com.arshad.ayn.ui.mobile.common.ACTION_NOTICE_MS
import com.arshad.ayn.ui.mobile.installOfflineImageLoader
import com.arshad.ayn.ui.mobile.library.TestStores
import com.arshad.ayn.ui.mobile.library.TestTime
import com.arshad.ayn.ui.mobile.library.TestZone
import com.arshad.ayn.ui.mobile.library.watched
import com.arshad.ayn.ui.mobile.shell.MobileShell
import com.arshad.ayn.ui.mobile.shell.ParentKey
import com.arshad.ayn.ui.mobile.shell.ShellNav
import com.arshad.ayn.ui.mobile.shell.ShellOpening
import com.arshad.ayn.ui.mobile.shell.Tab
import com.arshad.ayn.ui.mobile.shell.TabKey
import com.arshad.ayn.ui.mobile.shellPages
import com.arshad.ayn.ui.mobile.theme.AynTheme
import java.io.IOException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.time.Duration.Companion.days
import kotlin.time.TimeSource
import kotlin.time.TestTimeSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A kids profile's Parent page on the phone (`m6-kids` 13–20) through the kid's shell, its menu and
 * the real page wiring on the stores' fakes: the owner's lock, the rows, each sheet's change, Leave,
 * and the review's states, Keep, Block and its Undo. Today is Saturday, October 3 2026.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class ParentPageTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val stores = TestStores()
    private val feeds = SubscriptionsExtractor()
    private val kidsTime = FakeKidsTimeStore()
    private val keeps = FakeKidsReviewKeeps()
    private val leaves = mutableListOf<OwnerPass>()
    private lateinit var nav: ShellNav

    @Before
    fun setUp() {
        installOfflineImageLoader(compose.activity)
        stores.people.rows.value = listOf(ARSHAD, ZAINAB)
        stores.locks.ownerId = ARSHAD.id
        stores.locks.kids += ZAINAB.id
        stores.locks.locks[ARSHAD.id] = LockSecret.Pin("1234")
        stores.profileSession.select(ZAINAB.id)
        stores.filters.rulesById.value = mapOf(
            ZAINAB.id to ProfileRules(
                ProfileRestrictions(dailyLimitMinutes = DEFAULT_DAILY_LIMITS, bedtime = Bedtime(19 * 60 + 30, 7 * 60)),
                rules = listOf(StoredRule(PEPPA, byOwner = true, addedAtMillis = 0)),
                kids = true,
            ),
        )
        stores.filters.approved.value = mapOf(ZAINAB.id to listOf(channel(BLUEY_URL, "Bluey"), channel(RACHEL_URL, "Ms Rachel")))
        kidsTime.daysById.value = mapOf(ZAINAB.id to mapOf(LocalDate.of(2026, 10, 3) to DayTime(usedSeconds = 42 * 60L)))
        feeds.feeds[BLUEY_URL] = ItemsPage(listOf(SPIDERS, ELMO), next = null)
    }

    private fun open() {
        val parent = ParentModels(
            page = { kidId, leave -> ParentViewModel(kidId, stores.people, stores.locks, stores.filters, leave, TimeSource.Monotonic) },
            review = {
                ParentReviewViewModel(
                    stores.profileSession, stores.filters, kidsTime, stores.watch, FakeSubscriptionStore(BLUEY_URL, RACHEL_URL),
                    SubscriptionsFeed(feeds, TestTime.clock), keeps, ServerClock(TestTimeSource()), TestTime.clock, TestZone,
                )
            },
        )
        val models = stores.models(parent)
        compose.setContent {
            AynTheme {
                MobileShell(ShellOpening(ZAINAB, onSwitchProfile = {}, owner = ARSHAD, onLeaveKids = { leaves += it })) { chrome ->
                    nav = chrome.nav
                    shellPages(chrome, models)
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Zainab’s profile menu").performClick()
        compose.waitForIdle()
        tap("Parent")
    }

    private fun click(label: String) {
        compose.onNode(hasText(label) and hasClickAction()).performScrollTo().performClick()
        compose.waitForIdle()
    }

    /** Something outside a scrolling list (the menu's rows, the notice's Undo). */
    private fun tap(label: String) {
        compose.onNode(hasText(label) and hasClickAction()).performClick()
        compose.waitForIdle()
    }

    private fun digits(pin: String) {
        pin.forEach { compose.onNode(hasText(it.toString()) and hasClickAction()).performClick() }
        compose.waitForIdle()
    }

    private fun backArrow() {
        compose.onAllNodes(hasContentDescription("Back") and hasClickAction()).onLast().performClick()
        compose.waitForIdle()
    }

    private fun restrictions(): ProfileRestrictions = stores.filters.rulesById.value.getValue(ZAINAB.id).restrictions

    @Test
    fun theKidsMenuOpensTheOwnersLockThenThePagesRows() {
        open()
        compose.onNodeWithText("The Parent page needs Arshad’s lock. Enter Arshad’s PIN.").assertExists()
        compose.onNodeWithText("⌫ erases a digit · Back returns to Zainab’s Home").assertExists()

        digits("1234")

        compose.onNodeWithText("Zainab’s profile · opened with Arshad’s lock").assertExists()
        compose.onNodeWithText("Bluey, Ms Rachel").assertExists()
        compose.onNodeWithText("1 h · weekends 1 h 30 min").assertExists()
        compose.onNodeWithText("7:30 PM – 7:00 AM").assertExists()
        compose.onNodeWithText("42 min today").assertExists()
        compose.onNodeWithText("Time used this week, what Zainab watched, 2 new videos from Zainab’s channels").assertExists()
        compose.onNodeWithText("Back closes the Parent page; Zainab’s profile stays on, and only your lock leaves it.").performScrollTo().assertExists()
    }

    /** The pass goes when Ayn leaves the screen: coming back asks for the lock again. */
    @Test
    fun theLockIsAskedAgainAfterAynLeavesTheScreen() {
        open()
        digits("1234")
        compose.onNodeWithText("Zainab’s profile · opened with Arshad’s lock").assertExists()

        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()

        compose.onNodeWithText("The Parent page needs Arshad’s lock. Enter Arshad’s PIN.").assertExists()
    }

    @Test
    fun touchBackOnTheLockWithADigitEnteredReturnsToTheKidsHome() {
        open()
        digits("1")

        backArrow()

        assertEquals(TabKey(Tab.Home), nav.current)
    }

    @Test
    fun theSheetsRemoveAndChangeWhatTheOwnerSet() {
        open()
        digits("1234")

        click("Approved channels")
        compose.onNodeWithText("Zainab sees these channels’ videos, new ones too. Remove takes one away.").assertExists()
        compose.onNode(hasContentDescription("Remove Bluey")).performClick()
        compose.waitForIdle()
        assertEquals(listOf(RACHEL_URL), stores.filters.approved.value.getValue(ZAINAB.id).map { it.channelUrl })

        compose.onNode(hasContentDescription("Remove Ms Rachel")).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Zainab sees these channels’ videos, new ones too. Remove takes one away.").assertDoesNotExist()

        click("Approved videos")
        compose.onNode(hasContentDescription("Remove Peppa at the beach")).performClick()
        compose.waitForIdle()
        assertTrue(stores.filters.rulesById.value.getValue(ZAINAB.id).rules.isEmpty())

        click("Daily limits")
        click("Saturday")
        compose.onNodeWithText("15-minute steps up to 4 h. A change counts from today.").assertExists()
        compose.onNode(hasText("2 h") and isSelectable()).performClick()
        compose.waitForIdle()
        assertEquals(120, restrictions().dailyLimitMinutes[DayOfWeek.SATURDAY])
        compose.onNodeWithText("Time counts while Zainab’s profile is in use. Tap a day to change it in 15-minute steps.").assertExists()
    }

    @Test
    fun bedtimeTurnsOffAndAPickedStartTurnsItBackOn() {
        open()
        digits("1234")
        click("Bedtime")
        compose.onNodeWithText("Ayn doesn’t open for Zainab from 7:30 PM to 7:00 AM.").assertExists()

        compose.onNode(hasText("Bedtime") and isToggleable()).performClick()
        compose.waitForIdle()
        assertNull(restrictions().bedtime)
        compose.onNodeWithText("Off: Ayn opens for Zainab at any time, within the daily limit.").assertExists()

        click("Starts")
        compose.onNode(hasText("8:00 PM") and isSelectable()).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(Bedtime(20 * 60, 7 * 60), restrictions().bedtime)
    }

    @Test
    fun leaveHandsTheOwnersPassToTheGate() {
        open()
        digits("1234")

        click("Leave Zainab’s profile")

        assertEquals(1, leaves.size)
    }

    @Test
    fun theReviewShowsTheWeekTodayAndNewUploadsToKeepOrBlockWithUndo() {
        open()
        digits("1234")
        click("Review")
        compose.onNodeWithText("Zainab · week of September 28").assertExists()
        compose.onNodeWithText("42 min of 1 h 30 min today").assertExists()
        compose.onNodeWithText("Nothing yet today.").assertExists()
        compose.onNodeWithText("2 · allowed unless you block one").assertExists()

        compose.onNode(hasContentDescription("Keep ${SPIDERS.title}")).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(setOf(SPIDERS.id.value), keeps.keptById[ZAINAB.id])
        compose.onNodeWithText(SPIDERS.title).assertDoesNotExist()

        compose.onNode(hasContentDescription("Block ${ELMO.title}")).performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue(stores.filters.rulesById.value.getValue(ZAINAB.id).rules.any { it.rule == BlockedVideo(ELMO.id.value, ELMO.title, ELMO.uploaderUrl) })
        compose.onNodeWithText("${ELMO.title} blocked · in Zainab").assertExists()
        compose.onNodeWithText(NOTHING_NEW).assertExists()

        tap("Undo")
        assertTrue(stores.filters.rulesById.value.getValue(ZAINAB.id).rules.none { it.rule is BlockedVideo })
        compose.onNodeWithText(ELMO.title).assertExists()

        backArrow()
        compose.onNodeWithText("Approved channels").assertExists()
    }

    /** A second block's notice gets its own time, so its Undo outlives the first one's. */
    @Test
    fun aSecondBlockKeepsItsUndoForItsOwnTime() {
        open()
        digits("1234")
        click("Review")
        compose.mainClock.autoAdvance = false

        compose.onNode(hasContentDescription("Block ${SPIDERS.title}")).performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(ACTION_NOTICE_MS - 2_000)
        compose.onNode(hasContentDescription("Block ${ELMO.title}")).performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(3_000)

        compose.onNodeWithText("${ELMO.title} blocked · in Zainab").assertExists()
        tap("Undo")
        assertTrue(stores.filters.rulesById.value.getValue(ZAINAB.id).rules.none { it.rule == BlockedVideo(ELMO.id.value, ELMO.title, ELMO.uploaderUrl) })
    }

    /** Today's five latest videos, then how many more; each bar reads out its day. */
    @Test
    fun watchedTodayListsFiveThenCountsTheRest() {
        stores.watch.entries.value = (1..6).map { n ->
            watched("watchedvid$n", "Bluey episode $n", "Bluey", LocalDateTime.of(2026, 10, 3, 10 + n, 0), 60_000, 600_000)
        }.reversed()
        open()
        digits("1234")
        click("Review")
        compose.onNodeWithContentDescription("Today, 42 min watched, limit 1 h 30 min").assertExists()

        (2..6).forEach { compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Bluey episode $it")) }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("and 1 more today"))
        compose.onNodeWithText("Bluey episode 1").assertDoesNotExist()
    }

    @Test
    fun theReviewSaysWhenChannelsDontAnswerAndTriesAgain() {
        feeds.failures[BLUEY_URL] = IOException("offline")
        feeds.failures[RACHEL_URL] = IOException("offline")
        open()
        digits("1234")
        click("Review")
        compose.onNodeWithText("Ayn couldn’t reach Zainab’s channels. Check the connection, then try again.").performScrollTo().assertExists()

        feeds.failures.remove(BLUEY_URL)
        click("Try again")

        compose.onNodeWithText(ELMO.title).performScrollTo().assertExists()
        compose.onNodeWithText("1 of Zainab’s channels didn’t answer; their videos aren’t here.").performScrollTo().assertExists()
    }

    private companion object {
        val ARSHAD = Profile(1, "Arshad", ProfileTint.Amber, isOwner = true, lock = LockKind.Pin)
        val ZAINAB = Profile(4, "Zainab", ProfileTint.Violet, isOwner = false, lock = null, isKids = true)
        const val BLUEY_URL = "https://www.youtube.com/channel/UCVzLLZkDuFGAE2BGdBuBNBg"
        const val RACHEL_URL = "https://www.youtube.com/channel/UCG2CL6EUjG8TVT1Tpl9nJdg"
        const val NOTHING_NEW = "Nothing new in the last 7 days."
        val PEPPA = ApprovedVideo("peppa000001", "Peppa at the beach", "Peppa Pig", null, null, 300)
        val SPIDERS = upload("lu4EUrwnXjs", "Bluey has to BEWARE of spiders in the creek!", daysAgo = 1)
        val ELMO = upload("ZEuzViYEC0s", "Elmo’s Halloween Festival!", daysAgo = 3)

        fun channel(url: String, name: String) = Subscription(url, name, avatarUrl = null, subscribedAtMillis = 0, updatedAtMillis = 0)

        fun upload(id: String, title: String, daysAgo: Int) = VideoItem(
            VideoId(id), title, "Bluey", BLUEY_URL, 600, -1, null, TestTime.clock.now() - daysAgo.days, null, false,
        )
    }
}
