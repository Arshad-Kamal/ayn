package com.arshad.ayn.ui.tv.kids

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onLast
import com.arshad.ayn.core.access.HomeExtractor
import com.arshad.ayn.core.access.ServerClock
import com.arshad.ayn.core.lock.FakeProfileLocks
import com.arshad.ayn.core.store.FakeFilterStore
import com.arshad.ayn.core.store.FakeKidsReviewKeeps
import com.arshad.ayn.core.store.FakeKidsTimeStore
import com.arshad.ayn.core.store.FakeProfileStore
import com.arshad.ayn.core.store.FakeSubscriptionStore
import com.arshad.ayn.core.store.FakeWatchStore
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.ProfileSession
import com.arshad.ayn.core.store.ProfileTint
import com.arshad.ayn.feature.kids.ParentReviewViewModel
import com.arshad.ayn.feature.kids.ParentViewModel
import com.arshad.ayn.feature.subscriptions.SubscriptionsFeed
import com.arshad.ayn.ui.tv.dpadSelect
import com.arshad.ayn.ui.tv.pressBack
import com.arshad.ayn.ui.tv.shell.ShellTime
import com.arshad.ayn.ui.tv.theme.KeTheme
import kotlin.time.TimeSource
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Parent page's sheets (`kp-kids` parent) over in-memory stores, the owner without a lock so
 * the page opens straight away: a day's or bedtime bound's choices close back to the row that
 * opened them, and a list opened from the page starts on its first row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp")
class ParentPageTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun row(label: String): SemanticsMatcher = hasText(label) and hasClickAction()

    @Before
    fun launch() {
        val filters = FakeFilterStore()
        val parent = ParentViewModel(
            KID.id,
            FakeProfileStore(listOf(OWNER, KID)),
            FakeProfileLocks().apply {
                ownerId = OWNER.id
                kids += KID.id
            },
            filters,
            leave = {},
            TimeSource.Monotonic,
        )
        val review = ParentReviewViewModel(
            ProfileSession().apply { select(KID.id) },
            filters,
            FakeKidsTimeStore(),
            FakeWatchStore(),
            FakeSubscriptionStore(),
            SubscriptionsFeed(HomeExtractor()),
            FakeKidsReviewKeeps(),
            ServerClock(TimeSource.Monotonic),
        )
        compose.setContent {
            KeTheme {
                ParentPage(
                    parent = parent,
                    review = { review },
                    time = ShellTime(),
                    onNothingToFocus = {},
                    focusHeldElsewhere = { false },
                    onClose = {},
                    onRailShade = {},
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun aDaysChoicesCloseBackToThatDay() {
        compose.onNode(row("Daily limits")).dpadSelect()
        compose.waitForIdle()
        compose.onNode(row("Monday")).assertIsFocused()

        compose.onNode(row("Wednesday")).dpadSelect()
        compose.waitForIdle()
        compose.pressBack()
        compose.onNode(row("Wednesday")).assertIsFocused()

        compose.onNode(row("Wednesday")).dpadSelect()
        compose.waitForIdle()
        compose.onNode(row("15 min")).dpadSelect()
        compose.waitForIdle()
        compose.onNode(row("Wednesday")).assertIsFocused()
    }

    @Test
    fun aListOpenedFromThePageStartsOnItsFirstRow() {
        compose.onNode(row("Daily limits")).dpadSelect()
        compose.waitForIdle()
        compose.onNode(row("Friday")).dpadSelect()
        compose.waitForIdle()
        compose.pressBack()
        compose.pressBack()
        compose.onNode(row("Daily limits")).assertIsFocused()

        compose.onNode(row("Daily limits")).dpadSelect()
        compose.waitForIdle()
        compose.onNode(row("Monday")).assertIsFocused()
    }

    @Test
    fun aDayOpenedBeforeDoesNotFollowIntoBedtime() {
        compose.onNode(row("Daily limits")).dpadSelect()
        compose.waitForIdle()
        compose.onNode(row("Friday")).dpadSelect()
        compose.waitForIdle()
        compose.pressBack()
        compose.pressBack()

        compose.onNode(row("Bedtime")).dpadSelect()
        compose.waitForIdle()
        compose.onAllNodes(row("Bedtime")).onLast().assertIsFocused()
    }

    @Test
    fun aBedtimeBoundsChoicesCloseBackToThatBound() {
        compose.onNode(row("Bedtime")).dpadSelect()
        compose.waitForIdle()
        compose.onNode(row("Ends")).dpadSelect()
        compose.waitForIdle()
        compose.pressBack()
        compose.onNode(row("Ends")).assertIsFocused()

        compose.onNode(row("Starts")).dpadSelect()
        compose.waitForIdle()
        compose.onNode(row("8:00 PM")).dpadSelect()
        compose.waitForIdle()
        compose.onNode(row("Starts")).assertIsFocused()
    }

    private companion object {
        val OWNER = Profile(1, "Arshad", ProfileTint.Amber, isOwner = true, lock = null)
        val KID = Profile(4, "Zainab", ProfileTint.Violet, isOwner = false, lock = null, isKids = true)
    }
}
