package com.arshad.ayn.ui.tv.player

import android.os.Looper
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.arshad.ayn.core.store.DeviceSettings
import com.arshad.ayn.feature.player.DisplayModeHold
import com.arshad.ayn.ui.tv.common.LocalDeviceSettings
import java.time.Duration
import java.util.function.Consumer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.internal.DisplayConfig
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDisplayManager

/**
 * D9 AFR's mode wait on a display that offers 60 and 24 Hz, plus 48 or 25 Hz in single cases
 * (Robolectric's display shadows): the
 * window asks for the matching mode (48 fps: the 24 Hz one, Q26), playback holds until the
 * display reports it and the TV's settle passes, and the original mode comes back when the
 * player leaves.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DisplayRateTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val playback = FakePlayback()
    private val hold = DisplayModeHold(scope, playback)

    @Before
    fun setUp() {
        ShadowDisplayManager.setSupportedModes(Display.DEFAULT_DISPLAY, mode(Mode60, 60f), mode(Mode24, 24f))
        reportMode(Mode60)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `a 24 fps video asks for the 24 Hz mode and plays once it is reported and settled`() {
        compose.setContent { DisplayRateEffect(contentFps = 23.976f, hold = hold) }
        compose.waitForIdle()

        assertEquals(Mode24, preferredMode())
        assertEquals(1, playback.pauses)

        hold.play() // the screen's start, during the switch
        idle(Duration.ofSeconds(1))
        assertEquals("waits for the display's report", 0, playback.plays)

        reportMode(Mode24)
        idle(Duration.ofMillis(DisplayModeHold.SETTLE_MS - 1))
        assertEquals("waits for the settle", 0, playback.plays)
        idle(Duration.ofMillis(1))
        assertEquals(1, playback.plays)
    }

    @Test
    fun `a nearby rate after a switch keeps the switched mode and holds nothing more`() {
        var fps by mutableStateOf(24f)
        compose.setContent { DisplayRateEffect(contentFps = fps, hold = hold) }
        compose.waitForIdle()
        reportMode(Mode24)

        fps = 23.976f
        compose.waitForIdle()

        assertEquals(Mode24, preferredMode())
        assertEquals(1, playback.pauses)
    }

    @Test
    fun `a rate the display shows, while an earlier switch is in flight, pins the shown mode`() {
        var fps by mutableStateOf(24f)
        compose.setContent { DisplayRateEffect(contentFps = fps, hold = hold) }
        compose.waitForIdle()
        assertEquals(Mode24, preferredMode())

        fps = 60f
        compose.waitForIdle()

        assertEquals(Mode60, preferredMode())
    }

    @Test
    fun `a rate the display already shows asks for nothing and holds nothing`() {
        compose.setContent { DisplayRateEffect(contentFps = 59.94f, hold = hold) }
        compose.waitForIdle()

        assertEquals(0, preferredMode())
        assertEquals(0, playback.pauses)
    }

    @Test
    fun `with the TV's frame-rate matching off a 24 fps video asks for nothing and holds nothing`() {
        compose.setContent {
            CompositionLocalProvider(LocalDeviceSettings provides DeviceSettings(frameRateMatching = false)) {
                DisplayRateEffect(contentFps = 23.976f, hold = hold)
            }
        }
        compose.waitForIdle()

        assertEquals(0, preferredMode())
        assertEquals(0, playback.pauses)
    }

    @Test
    fun `a 48 fps video with no 48 Hz mode asks for the 24 Hz mode`() {
        compose.setContent { DisplayRateEffect(contentFps = 47.952f, hold = hold) }
        compose.waitForIdle()

        assertEquals(Mode24, preferredMode())
        assertEquals(1, playback.pauses)
    }

    @Test
    fun `a 48 fps video pins a 24 Hz mode already showing`() {
        compose.runOnUiThread { compose.activity.window.attributes = compose.activity.window.attributes.apply { preferredDisplayModeId = Mode60 } }
        reportMode(Mode24)

        compose.setContent { DisplayRateEffect(contentFps = 48f, hold = hold) }
        compose.waitForIdle()

        assertEquals(Mode24, preferredMode())
        assertEquals(0, playback.pauses)
    }

    @Test
    fun `a switch to 48 fps while the 24 Hz switch settles still waits for the display`() {
        var fps by mutableStateOf(24f)
        compose.setContent { DisplayRateEffect(contentFps = fps, hold = hold) }
        compose.waitForIdle()

        fps = 48f
        compose.waitForIdle()
        assertEquals(Mode24, preferredMode())
        idle(Duration.ofMillis(DisplayModeHold.SETTLE_MS))
        assertEquals("waits for the display's report", 0, playback.plays)

        reportMode(Mode24)
        idle(Duration.ofMillis(DisplayModeHold.SETTLE_MS))
        assertEquals(1, playback.plays)
    }

    @Test
    fun `a 48 Hz mode wins over the 24 Hz one for a 48 fps video`() {
        ShadowDisplayManager.setSupportedModes(Display.DEFAULT_DISPLAY, mode(Mode60, 60f), mode(Mode24, 24f), mode(Mode48, 48f))
        reportMode(Mode60)

        compose.setContent { DisplayRateEffect(contentFps = 48f, hold = hold) }
        compose.waitForIdle()

        assertEquals(Mode48, preferredMode())
    }

    @Test
    fun `another rate with no matching mode keeps the current one, even with a half-rate mode`() {
        ShadowDisplayManager.setSupportedModes(Display.DEFAULT_DISPLAY, mode(Mode60, 60f), mode(Mode24, 24f), mode(Mode25, 25f))
        reportMode(Mode60)

        compose.setContent { DisplayRateEffect(contentFps = 50f, hold = hold) }
        compose.waitForIdle()

        assertEquals(0, preferredMode())
        assertEquals(0, playback.pauses)
    }

    @Test
    fun `leaving the player gives the window its original mode back`() {
        compose.runOnUiThread { compose.activity.window.attributes = compose.activity.window.attributes.apply { preferredDisplayModeId = Mode60 } }
        var showing by mutableStateOf(true)
        compose.setContent { if (showing) DisplayRateEffect(contentFps = 24f, hold = hold) }
        compose.waitForIdle()
        assertEquals(Mode24, preferredMode())

        showing = false
        compose.waitForIdle()

        assertEquals(Mode60, preferredMode())
    }

    private fun preferredMode(): Int = compose.activity.window.attributes.preferredDisplayModeId

    private fun idle(duration: Duration) {
        shadowOf(Looper.getMainLooper()).idleFor(duration)
    }

    private class FakePlayback : DisplayModeHold.Playback {
        override var playWhenReady: Boolean = true
        override val canResume: Boolean = true
        var pauses = 0
        var plays = 0

        override fun pause() {
            pauses++
            playWhenReady = false
        }

        override fun play() {
            plays++
            playWhenReady = true
        }
    }

    private companion object {
        const val Mode60 = 1
        const val Mode24 = 2
        const val Mode48 = 3
        const val Mode25 = 4

        /** `Display.Mode`'s id-taking constructor is hidden from the SDK stubs. */
        fun mode(id: Int, rate: Float): Display.Mode =
            Display.Mode::class.java
                .getDeclaredConstructor(Int::class.java, Int::class.java, Int::class.java, Float::class.java)
                .newInstance(id, 3840, 2160, rate)

        /** The display reports [id] as active (Robolectric's own, package-private, change hook). */
        fun reportMode(id: Int) {
            ShadowDisplayManager::class.java
                .getDeclaredMethod("changeDisplay", Int::class.java, Consumer::class.java)
                .apply { isAccessible = true }
                .invoke(null, Display.DEFAULT_DISPLAY, Consumer<DisplayConfig> { it.modeId = id })
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
