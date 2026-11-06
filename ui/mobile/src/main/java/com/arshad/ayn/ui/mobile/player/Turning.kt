package com.arshad.ayn.ui.mobile.player

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.provider.Settings
import android.view.OrientationEventListener
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/** A way to hold the screen: upright or sideways. The watch page holds one regardless of the
 *  sensor (full screen holds landscape, exit or Back sideways holds portrait, Q39); [postureAt]
 *  reads the way the phone itself is held. */
internal enum class Hold { Landscape, Portrait }

/** The activity's orientation for the watch page: portrait unless it shows (Q13: only the player
 *  turns); shown, the [hold] if any, else the sensor as the user's rotation lock allows. */
internal fun watchOrientation(expanded: Boolean, hold: Hold?): Int = when {
    !expanded || hold == Hold.Portrait -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    hold == Hold.Landscape -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    else -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
}

/** How the phone is held at [degrees] ([OrientationEventListener]'s): upright, sideways, or `null`
 *  while flat, upside down or between. */
internal fun postureAt(degrees: Int): Hold? = when {
    degrees == OrientationEventListener.ORIENTATION_UNKNOWN -> null
    degrees <= POSTURE_SLACK || degrees >= 360 - POSTURE_SLACK -> Hold.Portrait
    degrees in (90 - POSTURE_SLACK)..(90 + POSTURE_SLACK) || degrees in (270 - POSTURE_SLACK)..(270 + POSTURE_SLACK) -> Hold.Landscape
    else -> null
}

/** A hold lets go once the phone is held its way with auto-rotate on: from then the sensor turns
 *  the page again. With auto-rotate off it stays (the sensor would only snap back to portrait). */
internal fun holdLetsGo(hold: Hold, posture: Hold?, autoRotate: Boolean): Boolean = autoRotate && posture == hold

private const val POSTURE_SLACK = 25

/** Whether the screen is turned sideways. */
@Composable
internal fun isLandscape(): Boolean = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

/**
 * Turns the activity for the watch page ([watchOrientation]) and back to portrait as it leaves;
 * while a [hold] is set, watches how the phone is held and calls [onLetGo] with it when it may go
 * ([holdLetsGo]): the caller clears it only if it is still the hold, a listener's late event
 * never clearing the next one. Sideways while [expanded], the system bars hide (a swipe shows them a moment).
 */
@Composable
internal fun WatchTurning(expanded: Boolean, hold: Hold?, onLetGo: (Hold) -> Unit) {
    val activity = LocalActivity.current ?: return
    LaunchedEffect(activity, expanded, hold) { activity.requestedOrientation = watchOrientation(expanded, hold) }
    DisposableEffect(activity) { onDispose { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT } }
    val context = LocalContext.current
    val letGo by rememberUpdatedState(onLetGo)
    DisposableEffect(context, hold) {
        if (hold == null) return@DisposableEffect onDispose { }
        // Called on the main thread (the listener registers without a handler).
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(degrees: Int) {
                if (holdLetsGo(hold, postureAt(degrees), autoRotateOn(context))) letGo(hold)
            }
        }
        if (listener.canDetectOrientation()) listener.enable()
        onDispose { listener.disable() }
    }
    val immersive = expanded && isLandscape()
    DisposableEffect(activity, immersive) {
        val bars = activity.window.insetsController
        if (immersive) {
            bars?.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars?.hide(WindowInsets.Type.systemBars())
        }
        onDispose { if (immersive) bars?.show(WindowInsets.Type.systemBars()) }
    }
}

private fun autoRotateOn(context: Context): Boolean = Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
