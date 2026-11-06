package com.arshad.ayn.ui.mobile.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.arshad.ayn.core.access.ChannelItem
import com.arshad.ayn.core.access.newpipe.CHANNEL_SEARCH_FLOW
import com.arshad.ayn.core.access.newpipe.CHANNEL_SEARCH_QUERY
import com.arshad.ayn.core.access.newpipe.RecordedChannelSearch
import com.arshad.ayn.core.access.recorded
import com.arshad.ayn.core.lock.LockKind
import com.arshad.ayn.core.lock.LockSecret
import com.arshad.ayn.core.store.Bedtime
import com.arshad.ayn.core.store.Profile
import com.arshad.ayn.core.store.ProfileTint
import com.arshad.ayn.core.store.canonicalChannelUrl
import com.arshad.ayn.ui.mobile.installOfflineImageLoader
import com.arshad.ayn.ui.mobile.library.TestStores
import com.arshad.ayn.ui.mobile.shell.MobileShell
import com.arshad.ayn.ui.mobile.shell.SettingsKey
import com.arshad.ayn.ui.mobile.shell.SettingsPage
import com.arshad.ayn.ui.mobile.shell.SettingsPageKey
import com.arshad.ayn.ui.mobile.shell.ShellNav
import com.arshad.ayn.ui.mobile.shell.ShellOpening
import com.arshad.ayn.ui.mobile.shellPages
import com.arshad.ayn.ui.mobile.theme.AynTheme
import java.time.DayOfWeek
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Adding a kids profile on the phone (`m6-kids` 21–26) from Settings › Profiles through the shell's
 * real page wiring on the stores' fakes: the owner's lock (or a first one), the name, approving
 * channels (the owner's subscriptions and Find a channel on the recorded channel search), the time
 * rules, and Add writing it all, or saying it didn't.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class KidsAddPagesTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val stores = TestStores()
    private lateinit var nav: ShellNav

    @Before
    fun setUp() {
        installOfflineImageLoader(compose.activity)
        stores.locks.ownerId = ARSHAD.id
        stores.locks.known += listOf(ARSHAD.id, MIRA.id)
        runBlocking { stores.subscriptions.subscribe(BLUEY) }
    }

    private fun open(owner: Profile = ARSHAD) {
        stores.people.rows.value = listOf(owner, MIRA)
        if (owner.lock == LockKind.Pin) stores.locks.locks[owner.id] = LockSecret.Pin("1234")
        stores.settings.selected.value = owner.id
        stores.profileSession.select(owner.id)
        val models = stores.models()
        compose.setContent {
            AynTheme {
                MobileShell(ShellOpening(owner, onSwitchProfile = {})) { chrome ->
                    nav = chrome.nav
                    shellPages(chrome, models)
                }
            }
        }
        compose.runOnUiThread {
            nav.open(SettingsKey)
            nav.open(SettingsPageKey(SettingsPage.Profiles))
        }
        compose.waitForIdle()
        click("Add a profile")
        compose.onNode(hasText("Kids profile") and isSelectable()).performClick()
        compose.waitForIdle()
    }

    private fun click(label: String) {
        compose.onNode(hasText(label) and hasClickAction()).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun digits(pin: String) {
        pin.forEach { compose.onNode(hasText(it.toString()) and hasClickAction()).performClick() }
        compose.waitForIdle()
    }

    /** A button at a page's foot, outside its scrolling part. */
    private fun press(label: String) {
        compose.onNode(hasText(label) and hasClickAction()).performClick()
        compose.waitForIdle()
    }

    /** The cover's back arrow (the last one: the page under it has its own). */
    private fun backArrow() {
        compose.onAllNodes(hasContentDescription("Back") and hasClickAction()).onLast().performClick()
        compose.waitForIdle()
    }

    private fun toStepTwo() {
        digits("1234")
        compose.onNodeWithText("Step 1 of 3").assertExists()
        compose.onNode(hasSetTextAction()).performTextInput("Zainab")
        click("Continue")
        compose.onNodeWithText("Which channels can Zainab watch?").assertExists()
    }

    @Test
    fun theOwnersLockThenNameChannelsAndTimeAddTheKidsProfile() {
        open()
        compose.onNodeWithText("Adding a kids profile").assertExists()
        compose.onNodeWithText("Only the owner adds profiles · ⌫ erases a digit · Back cancels").assertExists()
        toStepTwo()
        compose.onNodeWithText("Approved for Zainab   None yet").assertExists()

        compose.onNode(hasText("Bluey") and isToggleable()).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Approved for Zainab   1 channel").assertExists()
        press("Continue")

        compose.onNodeWithText("When can Zainab watch?").assertExists()
        compose.onNodeWithText("Daily limit   Mon to Fri 1 h · Sat and Sun 1 h 30 min").assertExists()
        compose.onAllNodes(hasText("1 h 30") and hasContentDescription("1 h 30 min")).assertCountEquals(2)
        compose.onNode(hasContentDescription("Saturday") and hasClickAction()).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("How long Zainab can watch on Saturdays.").assertExists()
        compose.onNode(hasText("2 h") and isSelectable()).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Daily limit   Set per day").assertExists()
        compose.onNode(hasContentDescription("Bedtime starts") and hasClickAction()).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Ayn doesn’t open for Zainab from 7:30 PM to 7:00 AM.").assertExists()
        compose.onNode(hasText("7:00 AM") and isSelectable()).assertDoesNotExist()
        compose.onNode(hasText("8:00 PM") and isSelectable()).performScrollTo().performClick()
        compose.waitForIdle()
        press("Add Zainab")

        val zainab = stores.people.rows.value!!.last()
        assertEquals("Zainab", zainab.name)
        assertTrue(zainab.isKids)
        assertEquals(listOf(canonicalChannelUrl(BLUEY.url)), stores.filters.approved.value[zainab.id]?.map { it.channelUrl })
        val restrictions = stores.filters.rulesById.value[zainab.id]?.restrictions
        assertEquals(120, restrictions?.dailyLimitMinutes?.get(DayOfWeek.SATURDAY))
        assertEquals(Bedtime(startMinute = 20 * 60, endMinute = 7 * 60), restrictions?.bedtime)
        compose.onNodeWithText("Zainab added · the profile opens from Who’s watching?").assertExists()
        compose.onNodeWithText("What kind of profile?").assertDoesNotExist()
        compose.onNodeWithText("Kids").assertExists()
    }

    @Test
    fun findAChannelApprovesARecordedSearchResultAndSaysWhenYouTubeIsOutOfReach() {
        stores.search.answers[CHANNEL_SEARCH_QUERY] = recorded(CHANNEL_SEARCH_FLOW) { search(CHANNEL_SEARCH_QUERY) }
        open()
        toStepTwo()
        click("Find a channel")
        compose.onNodeWithText("Approve for Zainab").assertExists()

        compose.onNode(hasSetTextAction()).performTextInput("nowhere")
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.waitForIdle()
        compose.onNodeWithText("Ayn couldn’t reach YouTube. Check the connection, then tap the search key again.").assertExists()

        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNode(hasSetTextAction()).performTextInput(CHANNEL_SEARCH_QUERY)
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.waitForIdle()
        compose.onNodeWithText("Channels for “$CHANNEL_SEARCH_QUERY”").assertExists()
        compose.onNodeWithText("21M subscribers").assertExists()
        compose.onNode(hasText(RecordedChannelSearch.CHANNEL_NAME) and hasClickAction()).performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Approve for Zainab").assertDoesNotExist()
        compose.onNodeWithText("Approved for Zainab   1 channel").assertExists()
        compose.onNode(hasText(RecordedChannelSearch.CHANNEL_NAME) and isToggleable()).assertExists()
    }

    @Test
    fun anOwnerWithoutALockSetsOneFirst() {
        open(ARSHAD.copy(lock = null))
        compose.onNodeWithText("Lock your profile first").assertExists()
        press("Lock my profile")
        compose.onNodeWithText("Choose a lock").assertExists()
        compose.onNode(hasText("4-digit PIN") and isSelectable()).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("New lock · step 1 of 2").assertExists()

        digits("2468")
        digits("2468")

        assertEquals(LockKind.Pin, stores.locks.locks[ARSHAD.id]?.kind)
        compose.onNodeWithText("Step 1 of 3").assertExists()
    }

    @Test
    fun touchBackOnTheOwnersLockWithADigitEnteredReturnsToTheKindSheet() {
        open()
        digits("1")

        backArrow()

        compose.onNodeWithText("Adding a kids profile").assertDoesNotExist()
        compose.onNodeWithText("What kind of profile?").assertExists()
    }

    @Test
    fun aFailedAddSaysNothingWasSavedAndKeepsNoProfile() {
        open()
        toStepTwo()
        press("Continue")
        stores.filters.failWrites = true

        press("Add Zainab")

        compose.onNodeWithText("Zainab wasn’t added. Nothing was saved. Tap Add Zainab to try again.").assertExists()
        assertEquals(listOf(ARSHAD, MIRA), stores.people.rows.value)
    }

    private companion object {
        val ARSHAD = Profile(1, "Arshad", ProfileTint.Amber, isOwner = true, lock = LockKind.Pin)
        val MIRA = Profile(2, "Mira", ProfileTint.Rose, isOwner = false, lock = null)
        val BLUEY = ChannelItem(
            name = "Bluey",
            url = "https://www.youtube.com/channel/UCVzLLZkDuFGAE2BGdBuBNBg",
            avatarUrl = null,
            subscriberCount = -1,
            description = null,
            verified = false,
        )
    }
}
