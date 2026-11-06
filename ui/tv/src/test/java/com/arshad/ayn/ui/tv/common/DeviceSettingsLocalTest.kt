package com.arshad.ayn.ui.tv.common

import androidx.activity.ComponentActivity
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.arshad.ayn.core.store.DeviceSettings
import com.arshad.ayn.core.store.DeviceSettingsStore
import com.arshad.ayn.core.store.FakeDeviceSettingsStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The TV's settings reach the UI only once read (M7-D9); a failed read gives the defaults; the
 *  interface size scales the density (Q59). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-xhdpi")
class DeviceSettingsLocalTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun showFrameRateMatching(store: DeviceSettingsStore) {
        compose.setContent {
            ProvideDeviceSettings(store) { BasicText("matching ${LocalDeviceSettings.current.frameRateMatching}") }
        }
        compose.waitForIdle()
    }

    @Test
    fun `nothing composes until the first read, then the stored settings show`() {
        val store = FakeDeviceSettingsStore(initial = null)
        showFrameRateMatching(store)
        compose.onNodeWithText("matching", substring = true).assertDoesNotExist()

        store.current.value = DeviceSettings(frameRateMatching = false)
        compose.waitForIdle()

        compose.onNodeWithText("matching false").assertExists()
    }

    @Test
    fun `at 120 percent the 960 by 540 dp screen holds 800 by 450, and a new size applies at once`() {
        val store = FakeDeviceSettingsStore(DeviceSettings(interfaceScalePercent = 120))
        var width = 0f
        var height = 0f
        compose.setContent {
            ProvideDeviceSettings(store) {
                width = screenWidth().value
                height = screenHeight().value
            }
        }
        compose.waitForIdle()
        assertEquals(800f, width, 0.5f)
        assertEquals(450f, height, 0.5f)

        store.current.value = DeviceSettings(interfaceScalePercent = 80)
        compose.waitForIdle()

        assertEquals(1200f, width, 0.5f)
        assertEquals(675f, height, 0.5f)
    }

    @Test
    fun `a failed read gives the defaults`() {
        val unreadable = object : DeviceSettingsStore {
            override fun settings(): Flow<DeviceSettings> = flow { throw IOException("unreadable") }

            override suspend fun update(transform: (DeviceSettings) -> DeviceSettings) = Unit
        }

        showFrameRateMatching(unreadable)

        compose.onNodeWithText("matching true").assertExists()
    }
}
