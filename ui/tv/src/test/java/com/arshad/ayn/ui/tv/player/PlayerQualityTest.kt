package com.arshad.ayn.ui.tv.player

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.arshad.ayn.core.access.ChannelExtractor
import com.arshad.ayn.feature.nav.PlayerRoute
import com.arshad.ayn.feature.player.EnigmaCard
import com.arshad.ayn.feature.player.FakePlaybackService
import com.arshad.ayn.feature.player.PlayerPolicy
import com.arshad.ayn.feature.player.selected
import com.arshad.ayn.feature.player.vp9Ladder
import com.arshad.ayn.ui.tv.TestShell
import com.arshad.ayn.ui.tv.dpadSelect
import com.arshad.ayn.ui.tv.installOfflineImageLoader
import com.arshad.ayn.ui.tv.press
import com.arshad.ayn.ui.tv.pressBack
import com.arshad.ayn.ui.tv.waitFor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The player's Quality (Q60, `b3-player-panels` quality, qualityheld, qualityheldchoice): the
 * heights a video offers, and a hold through the real shell and a real `MediaController` on an
 * in-process session, which hands the override to the player on its own track group.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp")
class PlayerQualityTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var service: FakePlaybackService
    private lateinit var navController: NavHostController

    @Before
    fun setUp() {
        installOfflineImageLoader(compose.activity)
        service = FakePlaybackService(compose.activity)
    }

    @After
    fun tearDown() {
        service.release()
    }

    @Test
    fun `HD opens the Quality sheet on Auto with every height, and BACK returns to HD`() {
        play()
        compose.onNode(qualityControl).dpadSelect()
        compose.waitForIdle()

        compose.onNodeWithText("Just for this video. Settings\u00A0›\u00A0Playback sets the most Ayn plays.").assertExists()
        compose.onNode(row("Auto")).assertIsFocused().assert(hasText("Now 2160p · adapts to the connection"))
        compose.onNode(row("2160p")).assert(hasText("4K"))
        compose.onNode(row("1080p")).assert(hasText("Full HD"))
        listOf("1440p", "720p", "480p", "360p", "240p", "144p").forEach { compose.onNode(row(it)).assertExists() }

        compose.pressBack()

        compose.onNodeWithText("Auto").assertDoesNotExist()
        compose.onNode(qualityControl).assertIsFocused()
        assertTrue(service.player.trackSelection.overrides.isEmpty())
    }

    @Test
    fun `a pick holds this video at that height on the player's own group and closes every panel`() {
        play()
        compose.onNode(moreControl).dpadSelect()
        compose.onNode(row("Quality")).dpadSelect()
        compose.waitForIdle()
        compose.onNode(row("Auto")).assertIsFocused()

        compose.onNode(row("1080p")).dpadSelect()
        compose.waitFor { service.player.trackSelection.overrides.isNotEmpty() }

        val override = service.player.trackSelection.overrides.values.single()
        assertEquals(vp9Ladder, override.mediaTrackGroup)
        assertEquals(listOf(2), override.trackIndices)
        compose.onNodeWithText("Playing now").assertDoesNotExist()
        compose.onNodeWithText("Auto").assertDoesNotExist()
        compose.onNode(moreControl).assertIsFocused()
    }

    @Test
    fun `a held height reads in the Playback sheet, and the Quality sheet reopens on it`() {
        playHolding1080p()
        compose.runOnUiThread {
            service.player.pictureSize = VideoSize(1920, 1080)
            service.player.ready()
        }
        compose.onNode(moreControl).dpadSelect()
        compose.waitForIdle()

        compose.onNode(row("Quality")).assertIsFocused().assert(hasText("1080p · VP9")).assert(hasText("Held for this video"))
        compose.onNode(row("Quality")).dpadSelect()
        compose.waitForIdle()

        compose.onNode(row("1080p")).assertIsFocused()
        compose.onNode(row("Auto")).assert(hasText("Adapts to the connection"))
    }

    @Test
    fun `Auto lets the video adapt again`() {
        playHolding1080p()
        compose.onNode(qualityControl).dpadSelect()
        compose.waitForIdle()

        compose.onNode(row("Auto")).dpadSelect()
        compose.waitFor { service.player.trackSelection.overrides.isEmpty() }

        compose.onNode(qualityControl).assertIsFocused().dpadSelect()
        compose.waitForIdle()
        compose.onNode(row("Auto")).assertIsFocused().assert(hasText("Now 2160p · adapts to the connection"))
    }

    @Test
    fun `BACK from Quality returns to the Playback sheet on its Quality row`() {
        play()
        compose.onNode(moreControl).dpadSelect()
        compose.onNode(row("Quality")).dpadSelect()
        compose.waitForIdle()

        compose.pressBack()

        compose.onNodeWithText("Playing now").assertExists()
        compose.onNode(row("Quality")).assertIsFocused()
    }

    @Test
    fun `the next video starts at Auto`() {
        playHolding1080p()

        compose.press(AndroidKeyEvent.KEYCODE_DPAD_DOWN) // the Related row
        compose.press(AndroidKeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitFor { service.player.requested.size == 2 }

        assertTrue(service.player.trackSelection.overrides.isEmpty())
    }

    @Test
    fun `Try again on the same video keeps its hold`() {
        playHolding1080p()

        compose.runOnUiThread { service.stop(PlayerPolicy.TERMINAL_EXHAUSTED, positionMs = 60_000) }
        compose.onNode(button("Try again from 1:00")).dpadSelect()
        compose.waitFor { service.player.requested.size == 2 }

        assertEquals(listOf(2), service.player.trackSelection.overrides.values.single().trackIndices)
    }

    private val qualityControl = hasContentDescription("Quality") and hasClickAction()
    private val moreControl = hasContentDescription("More") and hasClickAction()

    /** A sheet row by its label (the chrome's round controls carry theirs as a description). */
    private fun row(label: String): SemanticsMatcher = hasText(label) and hasClickAction()

    private fun button(label: String): SemanticsMatcher = (hasText(label) or hasContentDescription(label)) and hasClickAction()

    /** Opens the player and lets the stream land with [vp9Ladder], playing 1:00 in. */
    private fun play() {
        service.player.tracks = selected(vp9Ladder)
        compose.setContent {
            navController = rememberNavController()
            TestShell(extractor = ChannelExtractor(), playerSession = { service.session.token }, navController = navController)
        }
        compose.waitForIdle()
        compose.runOnUiThread { navController.navigate(PlayerRoute(EnigmaCard)) }
        compose.waitFor { service.player.requested.isNotEmpty() && service.player.isPlayWhenReady }
        compose.runOnUiThread { service.player.ready(60_000) }
        compose.waitForIdle()
    }

    /** Plays, then holds 1080p from the chrome's HD: the chrome is back on HD. */
    private fun playHolding1080p() {
        play()
        compose.onNode(qualityControl).dpadSelect()
        compose.waitForIdle()
        compose.onNode(row("1080p")).dpadSelect()
        compose.waitFor { service.player.trackSelection.overrides.isNotEmpty() }
    }
}
