package com.arshad.ayn.ui.tv.common

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arshad.ayn.core.store.DeviceSettings
import com.arshad.ayn.core.store.DeviceSettingsStore
import kotlinx.coroutines.flow.catch

/** The TV's own settings (M7-D9), read before the UI composes; the defaults where none is provided
 *  (previews, screen tests). */
internal val LocalDeviceSettings = staticCompositionLocalOf { DeviceSettings() }

/**
 * [content] with [store]'s settings as [LocalDeviceSettings], composed only once the first read
 * lands, so no screen starts on the defaults and changes under the user. A read that fails gives
 * the defaults, as the playback service takes them. The interface size scales [LocalDensity]:
 * every dp and sp grows or shrinks together, and the screen holds fewer or more of them
 * (CMP-UX-12; read the window's size with [screenWidth], never the configuration's).
 */
@Composable
internal fun ProvideDeviceSettings(store: DeviceSettingsStore, content: @Composable () -> Unit) {
    val flow = remember(store) {
        store.settings().catch { e ->
            Log.w(LOG_TAG, "device settings read failed: ${e.javaClass.simpleName}")
            emit(DeviceSettings())
        }
    }
    val settings = flow.collectAsStateWithLifecycle(initialValue = null).value ?: return
    val base = LocalDensity.current
    val scale = settings.interfaceScalePercent / 100f
    val density = remember(base, scale) { Density(base.density * scale, base.fontScale) }
    CompositionLocalProvider(LocalDeviceSettings provides settings, LocalDensity provides density, content = content)
}

private const val LOG_TAG = "KeSettings"
