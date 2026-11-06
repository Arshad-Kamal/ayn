package com.arshad.ayn.ui.mobile.common

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arshad.ayn.core.filter.ContentFilter
import com.arshad.ayn.core.store.DeviceSettings
import com.arshad.ayn.core.store.DeviceSettingsStore
import com.arshad.ayn.feature.CardBranding
import kotlinx.coroutines.flow.catch

/** This phone's own settings (Hero on Home, M6-D10); the defaults where none is provided (tests). */
internal val LocalDeviceSettings = staticCompositionLocalOf { DeviceSettings() }

/** The shell's DeArrow faces (M7-D11); `null` (tests without one) shows YouTube's everywhere. */
internal val LocalCardBranding = staticCompositionLocalOf<CardBranding?> { null }

/** The open profile's content filter (KP-D1) for lists already on screen (KP-D19); `null` (not read
 *  yet, tests) hides nothing. */
internal val LocalContentFilter = compositionLocalOf<ContentFilter?> { null }

/** [content] with [store]'s settings as [LocalDeviceSettings], composed once the first read lands so
 *  Home never starts on the defaults and changes under the user; a failed read gives the defaults. */
@Composable
internal fun ProvideDeviceSettings(store: DeviceSettingsStore, content: @Composable () -> Unit) {
    val flow = remember(store) {
        store.settings().catch { e ->
            Log.w(LOG_TAG, "device settings read failed: ${e.javaClass.simpleName}")
            emit(DeviceSettings())
        }
    }
    val settings = flow.collectAsStateWithLifecycle(initialValue = null).value ?: return
    CompositionLocalProvider(LocalDeviceSettings provides settings, content = content)
}

private const val LOG_TAG = "AynSettings"
