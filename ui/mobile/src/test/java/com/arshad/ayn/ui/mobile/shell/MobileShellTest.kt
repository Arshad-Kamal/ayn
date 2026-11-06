package com.arshad.ayn.ui.mobile.shell

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import com.arshad.ayn.core.lock.LockKind
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.ProfileTint
import com.arshad.ayn.ui.mobile.theme.AynTheme
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import com.arshad.ayn.core.kids.FakeKidsClock
import com.arshad.ayn.core.kids.KidsTime
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The phone shell over Navigation3: a saved back stack per tab that survives recreation,
 * Back unwinding the tab then returning Home, a popped page's ViewModel cleared while another
 * tab's stays, the shown tab's re-tap scrolling its page up, and the profile menu's actions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class MobileShellTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var switches = 0
    private lateinit var nav: ShellNav
    private val cleared = mutableListOf<Int>()
    private val kidsClock = FakeKidsClock()

    private fun shell(profile: Profile = ARSHAD) {
        compose.setContent { Shell(profile) }
    }

    @Composable
    private fun Shell(profile: Profile) {
        AynTheme {
            MobileShell(
                ShellOpening(profile, onSwitchProfile = { switches++ }, owner = ARSHAD),
                kidsClock = kidsClock.takeIf { profile.isKids },
            ) { chrome ->
                nav = chrome.nav
                entry<TabKey> { key ->
                    val list = rememberLazyListState()
                    ScrollsToTop(list, chrome.nav.reselected == key.tab, chrome.nav::onScrolledToTop)
                    Column {
                        ShellTopBar(chrome)
                        TextButton(onClick = { chrome.nav.open(Page(1)) }) { Text("Open ${key.tab.label} 1") }
                        LazyColumn(state = list) { items(60, key = { it }) { Text("${key.tab.label} row $it") } }
                    }
                }
                entry<Page> { page ->
                    viewModel { PageModel { cleared += page.n } }
                    var taps by rememberSaveable { mutableIntStateOf(0) }
                    Column {
                        Text("Page ${page.n} tapped $taps")
                        TextButton(onClick = { taps++ }) { Text("Tap") }
                        TextButton(onClick = { chrome.nav.open(Page(page.n + 1)) }) { Text("Open ${page.n + 1}") }
                    }
                }
                entry<SettingsKey> { Text("Settings page") }
                entry<SearchKey> { Text("Search page") }
            }
        }
    }

    private fun tab(label: String): SemanticsNodeInteraction = compose.onNode(hasText(label) and isSelectable())

    private fun click(label: String) {
        compose.onNode(hasText(label) and hasClickAction()).performClick()
        compose.waitForIdle()
    }

    private fun systemBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test
    fun eachTabKeepsItsStackAndBothSurviveRecreation() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { Shell(ARSHAD) }
        click("Open Home 1")
        click("Tap")
        tab("Library").performClick()
        click("Open Library 1")
        click("Tap")
        tab("Subscriptions").performClick()
        tab("Library").performClick()
        compose.onNode(hasText("Page 1 tapped 1")).assertExists()
        click("Open 2")

        restoration.emulateSavedInstanceStateRestore()

        tab("Library").assertIsSelected()
        compose.onNode(hasText("Page 2 tapped 0")).assertExists()
        systemBack()
        compose.onNode(hasText("Page 1 tapped 1")).assertExists()
        tab("Home").performClick()
        compose.onNode(hasText("Page 1 tapped 1")).assertExists()
    }

    @Test
    fun backUnwindsTheTabThenReturnsHome() {
        shell()
        tab("Subscriptions").performClick()
        click("Open Subscriptions 1")

        systemBack()
        compose.onNode(hasText("Subscriptions row 0")).assertExists()
        systemBack()

        tab("Home").assertIsSelected()
        compose.onNode(hasText("Home row 0")).assertExists()
        systemBack()
        assertTrue(compose.activity.isFinishing)
    }

    @Test
    fun aPoppedPageLosesItsViewModelWhileAnotherTabKeepsItsOwn() {
        shell()
        click("Open Home 1")
        tab("Library").performClick()
        click("Open Library 1")
        click("Open 2")

        systemBack()

        assertEquals(listOf(2), cleared)
        tab("Home").performClick()
        compose.onNode(hasText("Page 1 tapped 0")).assertExists()
        assertEquals(listOf(2), cleared)
    }

    @Test
    fun tappingTheShownTabScrollsItsPageToTheTop() {
        shell()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(50)
        compose.onNode(hasText("Home row 0")).assertDoesNotExist()

        tab("Home").performClick()
        compose.waitForIdle()
        compose.onNode(hasText("Home row 0")).assertExists()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(50)

        tab("Home").performClick()
        compose.waitForIdle()

        compose.onNode(hasText("Home row 0")).assertExists()
    }

    @Test
    fun theBarHasThreeTabsAndThePillOpensSearchOverTheShownTab() {
        shell()
        assertEquals(listOf("Home", "Subscriptions", "Library"), compose.onAllNodes(isSelectable()).fetchSemanticsNodes().map { it.text() })
        tab("Library").performClick()

        click("Search YouTube")

        compose.onNode(hasText("Search page")).assertExists()
        tab("Library").assertIsSelected()
        systemBack()
        compose.onNode(hasText("Library row 0")).assertExists()
    }

    @Test
    fun searchOpenedTwiceBeforeItCoversTheTabIsOnePageSoOneBackReturns() {
        shell()

        compose.runOnUiThread {
            nav.open(SearchKey)
            nav.open(SearchKey)
        }
        compose.waitForIdle()
        systemBack()

        compose.onNode(hasText("Home row 0")).assertExists()
        compose.onNode(hasText("Search page")).assertDoesNotExist()
    }

    @Test
    fun aKidsProfilesPillSearchesWhatIsApproved() {
        shell(ZAINAB)
        compose.onNode(hasText("Search what’s approved") and hasClickAction()).assertExists()
        compose.onNode(hasText("Search YouTube")).assertDoesNotExist()
    }

    /** `m6-kids` 2–3: one page with no bar, the time left under the pill; the kid's menu holds
     *  Parent and Leave behind the owner's lock, and no Settings or Switch profile. */
    @Test
    fun aKidsShellHasNoBarItsTimeLeftAndAMenuOfParentAndLeave() {
        kidsClock.time.value = KidsTime.Left(47.minutes + 30.seconds)
        shell(ZAINAB)

        assertEquals(0, compose.onAllNodes(isSelectable()).fetchSemanticsNodes().size)
        compose.onNode(hasText("48 min left today")).assertExists()
        compose.onNodeWithContentDescription("Zainab’s profile menu").performClick()
        compose.waitForIdle()
        compose.onNode(hasText("Kids profile · 48 min left today")).assertExists()
        compose.onNode(hasText("Opens Who’s watching? · needs Arshad’s lock")).assertExists()
        compose.onNode(hasText("What Zainab watched, Zainab’s channels and time · needs Arshad’s lock")).assertExists()
        compose.onNode(hasText("Settings")).assertDoesNotExist()
        compose.onNode(hasText("Switch profile")).assertDoesNotExist()
        compose.onNode(hasText("Lock and switch profile")).assertDoesNotExist()
        click("Leave Zainab’s profile")

        assertEquals(1, switches)
    }

    /** A day without a limit has no time-left chip, and the menu says only what the profile is. */
    @Test
    fun aKidWithoutALimitHasNoTimeLeft() {
        kidsClock.time.value = KidsTime.Unlimited
        shell(ZAINAB)

        compose.onNode(hasText("left today", substring = true)).assertDoesNotExist()
        compose.onNodeWithContentDescription("Zainab’s profile menu").performClick()
        compose.waitForIdle()
        compose.onNode(hasText("Kids profile")).assertExists()
    }

    /** `m6-kids` 7: the 5-minute note stays its 6 s, then is consumed. */
    @Test
    fun aTimeNoteStaysSixSecondsThenIsConsumed() {
        compose.mainClock.autoAdvance = false
        shell(ZAINAB)
        compose.runOnUiThread { kidsClock.warning.value = 5 }
        compose.mainClock.advanceTimeBy(5_900)
        compose.onNode(hasText("5 minutes left today")).assertExists()
        assertEquals(5, kidsClock.warning.value)

        compose.mainClock.advanceTimeBy(600)

        assertNull(kidsClock.warning.value)
    }

    private fun SemanticsNode.text(): String = config[SemanticsProperties.Text].joinToString { it.text }

    @Test
    fun aLockedProfilesMenuLocksAndSwitches() {
        shell(ARSHAD)
        compose.onNodeWithContentDescription("Arshad’s profile menu").performClick()
        compose.waitForIdle()
        compose.onNode(hasText("Owner · locked with a sequence")).assertExists()

        click("Lock and switch profile")

        assertEquals(1, switches)
        compose.onNode(hasText("Lock and switch profile")).assertDoesNotExist()
    }

    @Test
    fun anUnlockedProfilesMenuOffersSwitchAndOpensSettings() {
        shell(YURI)
        compose.onNodeWithContentDescription("Yuri’s profile menu").performClick()
        compose.waitForIdle()
        compose.onNode(hasText("Lock and switch profile")).assertDoesNotExist()
        compose.onNode(hasText("Switch profile")).assertExists()

        click("Settings")

        compose.onNode(hasText("Settings page")).assertExists()
        assertEquals(0, switches)
    }

    @Serializable
    data class Page(val n: Int) : NavKey

    class PageModel(private val onCleared: () -> Unit) : ViewModel() {
        override fun onCleared() = onCleared.invoke()
    }

    private companion object {
        val ARSHAD = Profile(1, "Arshad", ProfileTint.Amber, isOwner = true, lock = LockKind.Sequence)
        val YURI = Profile(3, "Yuri", ProfileTint.Teal, isOwner = false, lock = null)
        val ZAINAB = Profile(4, "Zainab", ProfileTint.Violet, isOwner = false, lock = null, isKids = true)
    }
}
