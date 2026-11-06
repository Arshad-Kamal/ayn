package com.arshad.ayn.core.store

import android.content.Context
import android.os.Looper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Device settings in the `device` preferences (M7-D9): defaults, writes another instance reads,
 *  the flow's changes, lenient reads, and the neighbours left alone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreferencesDeviceSettingsTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    private fun newStore(io: CoroutineDispatcher = UnconfinedTestDispatcher()) = PreferencesDeviceSettings(context, io)

    @Test
    fun aDeviceWithNothingStoredHasTheMockupDefaults() = runTest {
        assertEquals(
            DeviceSettings(maxVideoHeight = 2160, frameRateMatching = true, interfaceScalePercent = 100, clockInPlayer = true, heroOnHome = true, backgroundAudio = true),
            newStore().settings().first(),
        )
    }

    @Test
    fun anUpdateIsWhatAnotherInstanceReads() = runTest {
        newStore().update { it.copy(maxVideoHeight = 1080, heroOnHome = false) }
        newStore().update { it.copy(interfaceScalePercent = 125, backgroundAudio = false) }

        assertEquals(
            DeviceSettings(maxVideoHeight = 1080, interfaceScalePercent = 125, heroOnHome = false, backgroundAudio = false),
            newStore().settings().first(),
        )
    }

    @Test
    fun theFlowEmitsTheCurrentSettingsThenEachChange() = runTest {
        val store = newStore(UnconfinedTestDispatcher(testScheduler))
        val seen = mutableListOf<DeviceSettings>()
        val collecting = launch(UnconfinedTestDispatcher(testScheduler)) { store.settings().collect { seen += it } }

        store.update { it.copy(clockInPlayer = false) }
        shadowOf(Looper.getMainLooper()).idle()
        collecting.cancel()

        assertEquals(listOf(DeviceSettings(), DeviceSettings(clockInPlayer = false)), seen)
    }

    @Test
    fun aChangeToAnotherDeviceValueDoesntEmit() = runTest {
        val seen = mutableListOf<DeviceSettings>()
        val collecting = launch(UnconfinedTestDispatcher(testScheduler)) { newStore(UnconfinedTestDispatcher(testScheduler)).settings().collect { seen += it } }

        PreferencesLastUsedProfile(context, UnconfinedTestDispatcher()).set(5)
        shadowOf(Looper.getMainLooper()).idle()
        collecting.cancel()

        assertEquals(listOf(DeviceSettings()), seen)
    }

    @Test
    fun storedValuesThisBuildDoesntOfferReadAsTheirDefaults() = runTest {
        context.getSharedPreferences(DEVICE_PREFERENCES, Context.MODE_PRIVATE).edit()
            .putInt("maxVideoHeight", 4320)
            .putInt("interfaceScalePercent", 20)
            .putBoolean("frameRateMatching", false)
            .commit()

        assertEquals(DeviceSettings(frameRateMatching = false), newStore().settings().first())
    }

    @Test
    fun anUpdateLeavesTheLastUsedProfileAlone() = runTest {
        val lastUsed = PreferencesLastUsedProfile(context, UnconfinedTestDispatcher())
        lastUsed.set(5)

        newStore().update { it.copy(frameRateMatching = false) }

        assertEquals(5L, lastUsed.get())
    }
}
