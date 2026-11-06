package com.arshad.ayn.ui.tv.player

import android.app.Activity
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player
import com.arshad.ayn.feature.player.DisplayModeHold
import com.arshad.ayn.ui.tv.common.LocalDeviceSettings
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The screen's [DisplayModeHold] over [player]. Its scope is main-confined (the hold's state and
 * the player are touched from the main thread only, M3-PLAYER-03) and is cancelled when the
 * screen leaves, dropping any hold without resuming. Resuming needs the app in the foreground
 * (D1/TVU-9).
 */
@Composable
internal fun rememberDisplayModeHold(player: Player): DisplayModeHold {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope { Dispatchers.Main.immediate }
    return remember(player, lifecycle, scope) {
        DisplayModeHold(
            scope,
            object : DisplayModeHold.Playback {
                override val playWhenReady: Boolean get() = player.playWhenReady
                override val canResume: Boolean get() = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

                override fun pause() = player.pause()

                override fun play() = player.play()
            },
        )
    }
}

/**
 * D9 AFR (M3-DISPLAY-01/02): the app owns the display plane. Once the content rate is known,
 * request the display mode whose refresh rate matches it (fractional-tolerant: 59.94 ≈ 60) at the
 * highest resolution (`preferredDisplayModeId`); keep the current mode when its rate already
 * matches. 48 fps with no 48 Hz mode (the Shield) takes the 24 Hz family: every other frame at
 * an even cadence (Q26). The window's original preference is taken once and restored when the player leaves,
 * never between rates, so a rate change mid-video can't leave a stale preference behind. Media3's
 * frame-rate signalling is off (the service's builder). Playback holds across each switch through
 * [hold]. With the TV's frame-rate matching off nothing is asked for (M7-D15).
 */
@Composable
internal fun DisplayRateEffect(contentFps: Float?, hold: DisplayModeHold) {
    val view = LocalView.current
    val window = (view.context as? Activity)?.window
    DisposableEffect(window) {
        val original = window?.attributes?.preferredDisplayModeId
        onDispose {
            if (window != null && original != null) window.attributes = window.attributes.apply { preferredDisplayModeId = original }
        }
    }
    if (!LocalDeviceSettings.current.frameRateMatching || contentFps == null || contentFps <= 0f) return
    LaunchedEffect(contentFps, hold, view) {
        val display = view.display
        val displayManager = view.context.getSystemService(DisplayManager::class.java)
        if (window == null || display == null || displayManager == null) return@LaunchedEffect
        val target = display.modeAt(contentFps)
            ?: if (abs(contentFps - HIGH_FRAME_RATE_FILM) < RATE_EPSILON) display.modeAt(contentFps / 2) else null
        val keep = target?.takeIf { it.modeId == display.mode.modeId }
        if (keep != null || target == null) {
            // Pin what shows, so an earlier rate's request doesn't switch it under the video.
            if (keep != null && window.attributes.preferredDisplayModeId != 0) {
                window.attributes = window.attributes.apply { preferredDisplayModeId = keep.modeId }
            }
            hold.onModeKept()
            return@LaunchedEffect
        }
        Log.i(DisplayModeHold.LOG_TAG, "request: ${display.mode.modeId} -> ${target.modeId} (${target.refreshRate} Hz)")
        hold.onModeRequested { displayManager.awaitMode(display.displayId, target.modeId) }
        window.attributes = window.attributes.apply { preferredDisplayModeId = target.modeId }
    }
}

/** The current mode if its rate is [rate], else the highest-resolution mode at [rate]. */
private fun Display.modeAt(rate: Float): Display.Mode? =
    mode.takeIf { abs(it.refreshRate - rate) < RATE_EPSILON }
        ?: supportedModes
            .filter { mode -> abs(mode.refreshRate - rate) < RATE_EPSILON }
            .maxByOrNull { mode -> mode.physicalWidth }

/** Resumes once [displayId] reports [modeId] as its active mode (at once if it already does). */
private suspend fun DisplayManager.awaitMode(displayId: Int, modeId: Int): Unit =
    suspendCancellableCoroutine { continuation ->
        fun reportsTarget(): Boolean = getDisplay(displayId)?.mode?.modeId == modeId
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(id: Int) = Unit
            override fun onDisplayRemoved(id: Int) = Unit
            override fun onDisplayChanged(id: Int) {
                if (id == displayId && reportsTarget() && continuation.isActive) {
                    unregisterDisplayListener(this)
                    continuation.resume(Unit)
                }
            }
        }
        registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        continuation.invokeOnCancellation { unregisterDisplayListener(listener) }
        if (reportsTarget()) {
            unregisterDisplayListener(listener)
            continuation.resume(Unit)
        }
    }

/** 59.94 vs 60 tolerance: exact fractional rates, never integer-rounded (M3-DISPLAY-01). */
private const val RATE_EPSILON = 0.5f

private const val HIGH_FRAME_RATE_FILM = 48f
