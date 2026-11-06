package com.arshad.ayn

import android.app.Activity
import android.os.Build

/**
 * Whether the system may keep a screenshot of this activity for recents (M5-D9). It is window
 * manager state, set synchronously, so set from `onPause` it is in place before the leave's
 * transition snapshots the task; a redrawn blank screen can miss that snapshot (the Shield's
 * compositor stalls as the transition starts). API 33 has it public; API 30–32 only as the hidden
 * `setDisablePreviewScreenshots` the public one replaced (unsupported-but-allowed). `false` when it
 * couldn't be set.
 */
internal fun Activity.setRecentsPreviewEnabled(enabled: Boolean): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        setRecentsScreenshotEnabled(enabled)
        true
    } else {
        runCatching {
            Activity::class.java.getMethod("setDisablePreviewScreenshots", Boolean::class.javaPrimitiveType)
                .invoke(this, !enabled)
        }.isSuccess
    }
