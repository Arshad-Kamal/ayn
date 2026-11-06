package com.arshad.ayn.ui.mobile.settings

import androidx.annotation.DrawableRes
import com.arshad.ayn.core.lock.LockKind
import com.arshad.ayn.core.sync.SyncDevice
import com.arshad.ayn.core.sync.SyncService
import com.arshad.ayn.core.sync.SyncStatus
import com.arshad.ayn.feature.format.metaLine
import com.arshad.ayn.feature.settings.COULDNT_SYNC
import com.arshad.ayn.feature.settings.DRIVE_SERVICE_FOLDER
import com.arshad.ayn.feature.settings.LAUNCH_NOTICE_MS
import com.arshad.ayn.feature.settings.LaunchNotice
import com.arshad.ayn.feature.settings.SYNC_DIDNT_WORK
import com.arshad.ayn.feature.settings.SYNC_ISNT_WORKING
import com.arshad.ayn.feature.settings.SYNC_OWNER_ONLY
import com.arshad.ayn.feature.settings.SyncTime
import com.arshad.ayn.feature.settings.SyncToast
import com.arshad.ayn.feature.settings.SyncWords
import com.arshad.ayn.feature.settings.launchReason
import com.arshad.ayn.feature.settings.ownerToTell
import com.arshad.ayn.feature.settings.retriesItself
import com.arshad.ayn.feature.settings.syncToastText
import com.arshad.ayn.feature.settings.thisDeviceHint
import com.arshad.ayn.ui.mobile.R
import com.arshad.ayn.ui.mobile.common.NoticeLine

// The phone's Settings › Sync copy (`m8-sync-phone` v1–v3); text both UIs show:
// feature/settings/SyncText.kt. Lines the mockup doesn't draw follow its pattern (M8.7b).

/** The phone names itself as the mockup does ("before they leave this phone"). */
internal val PhoneSyncWords = SyncWords(here = "this phone", both = "phone and TV", otherKind = "TV")

/** Google Drive's service row (`m8-sync-phone` 3). */
internal const val PHONE_DRIVE_HINT = "Sign in with Google. $DRIVE_SERVICE_FOLDER"

internal const val GOOGLE_SIGN_IN_TITLE = "Sign in with Google"
internal const val GOOGLE_SIGN_IN_BODY =
    "Google’s page opens in your browser. Enter this code there, choose your account and allow Ayn to make an Ayn sync folder in your Drive."

/** The button that copies the code and opens Google's [page] ("google.com/device"). */
internal fun copyAndOpen(page: String): String = "Copy code and open $page"

internal const val COPY_CODE = "Copy code"

internal const val PHONE_NEXTCLOUD_ADDRESS_LEDE = "The address you open Nextcloud at. Next, its sign-in page opens in your browser."

internal const val NEXTCLOUD_SIGN_IN_TITLE = "Sign in to Nextcloud"

/** Nextcloud's sign-in in the browser: [server], then Grant access. */
internal fun nextcloudSignInBody(server: String): String =
    "Its sign-in page opens in your browser. Sign in to $server as you always do, then choose Grant access. Ayn gets its own " +
        "app password, which you can remove in Nextcloud’s Security settings."

internal const val PHONE_NEXTCLOUD_WAITING = "Waiting for you to grant access · the sign-in works for 20 minutes"
internal const val OPEN_SIGN_IN = "Open the sign-in page"
internal const val CHANGE_ADDRESS = "Change address"

/** No app on the phone took the sign-in page. */
internal const val NO_BROWSER = "No app on this phone opens web pages, so the sign-in page can’t open"

/** Turn off's sheet confirms with this (`m8-sync-phone` 8). */
internal const val TURN_OFF_SHORT = "Turn off"

internal fun syncGateHint(kind: LockKind): String = "$SYNC_OWNER_ONLY · ${erasesHint(kind)} · Back cancels"

/** This phone's row: its model, then its sync ("Pixel 7 Pro · synced just now"). */
internal fun thisPhoneHint(status: SyncStatus, time: SyncTime): String =
    metaLine(status.deviceId?.let { SyncDevice(it).name }, thisDeviceHint(status, time).replaceFirstChar { it.lowercaseChar() })

/** A service's icon. */
@get:DrawableRes
internal val SyncService.icon: Int
    get() = when (this) {
        SyncService.NEXTCLOUD -> R.drawable.ic_cloud
        SyncService.WEBDAV -> R.drawable.ic_server
        SyncService.GOOGLE_DRIVE -> R.drawable.ic_gdrive
    }

/** The launch snackbar over Home (`m8-sync-phone` 13–15): Fix only for the owner, and only for a
 *  failure Ayn doesn't retry by itself. */
internal fun launchNoticeLine(notice: LaunchNotice): NoticeLine {
    val reason = launchReason(notice.failure, notice.account)
    return when {
        notice.failure.retriesItself -> NoticeLine(COULDNT_SYNC, "$reason, Ayn tries again", action = null, shownMs = LAUNCH_NOTICE_MS)
        notice.isOwner -> NoticeLine(SYNC_DIDNT_WORK, reason, action = "Fix", shownMs = LAUNCH_NOTICE_MS)
        else -> NoticeLine(SYNC_ISNT_WORKING, "inform ${ownerToTell(notice)}", action = null, shownMs = LAUNCH_NOTICE_MS)
    }
}

internal fun syncToastLine(toast: SyncToast): NoticeLine {
    val (text, muted) = syncToastText(toast, PhoneSyncWords)
    return NoticeLine(text, muted, action = null, warn = toast is SyncToast.TurnedOff && toast.filesLeft)
}
