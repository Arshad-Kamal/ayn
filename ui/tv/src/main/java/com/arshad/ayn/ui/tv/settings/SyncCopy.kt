package com.arshad.ayn.ui.tv.settings

import androidx.annotation.DrawableRes
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.arshad.ayn.core.lock.LockKind
import com.arshad.ayn.core.sync.SyncAccount
import com.arshad.ayn.core.sync.SyncService
import com.arshad.ayn.feature.settings.COULDNT_SYNC
import com.arshad.ayn.feature.settings.DRIVE_SERVICE_FOLDER
import com.arshad.ayn.feature.settings.LaunchNotice
import com.arshad.ayn.feature.settings.NEXTCLOUD_SERVICE_HINT
import com.arshad.ayn.feature.settings.SYNC_DIDNT_WORK
import com.arshad.ayn.feature.settings.SYNC_ISNT_WORKING
import com.arshad.ayn.feature.settings.SYNC_OWNER_ONLY
import com.arshad.ayn.feature.settings.SyncToast
import com.arshad.ayn.feature.settings.SyncWords
import com.arshad.ayn.feature.settings.launchReason
import com.arshad.ayn.feature.settings.ownerToTell
import com.arshad.ayn.feature.settings.retriesItself
import com.arshad.ayn.feature.settings.signInAgainHint
import com.arshad.ayn.feature.settings.syncFailureLine
import com.arshad.ayn.feature.settings.syncToastText
import com.arshad.ayn.ui.tv.R
import com.arshad.ayn.ui.tv.common.ToastLine
import com.arshad.ayn.ui.tv.theme.weightSpan

// The TV's Settings › Sync copy (`m8-sync-tv` v1–v3); shared text: feature/settings/SyncText.kt.

/** The TV names itself as the page head does ("on this Shield"). */
internal val TvSyncWords = SyncWords(here = "this Shield", both = "TV and phone", otherKind = "phone")

/** Google Drive's service row: the sign-in happens on the phone. */
internal const val TV_DRIVE_HINT = "Sign in on your phone. $DRIVE_SERVICE_FOLDER"

/** Nextcloud's service row: the sign-in happens on the phone. */
internal const val TV_NEXTCLOUD_HINT = "$NEXTCLOUD_SERVICE_HINT on your phone"

internal const val NEXTCLOUD_ADDRESS_LEDE = "The address you open Nextcloud at in a browser. Next, you’ll sign in on your phone."

internal const val SIGN_IN_ON_PHONE = "Sign in on your phone"

/** Login Flow's steps on the phone, [server] and the button named in bold. */
internal fun nextcloudSteps(server: String): List<AnnotatedString> = listOf(
    AnnotatedString("Scan the code with your phone’s camera"),
    buildAnnotatedString {
        append("Sign in to ")
        withStyle(weightSpan(620)) { append(server) }
        append(" as you always do")
    },
    buildAnnotatedString {
        append("Choose ")
        withStyle(weightSpan(620)) { append("Grant access") }
        append(". Ayn gets its own app password, which you can remove in Nextcloud’s Security settings")
    },
)

/** Google's steps on the phone, its [page] named in bold; the code's own step is the cover's. */
internal fun googleOpenStep(page: String): AnnotatedString = buildAnnotatedString {
    append("Scan the code, or open ")
    withStyle(weightSpan(620)) { append(page) }
}

internal const val GOOGLE_ENTER = "Enter"
internal const val GOOGLE_ALLOW_STEP = "Choose your Google account and allow Ayn to make an Ayn sync folder in your Drive"

/** Sign in again's hint (`m8-sync-tv` error): Drive's names the phone it happens on. */
internal fun tvSignInAgainHint(account: SyncAccount): String =
    if (account.service == SyncService.GOOGLE_DRIVE) "On your phone, as ${account.user}" else signInAgainHint(account)

/** The owner gate's line: only the owner sets up sync, then whose lock it asks for. */
internal fun syncGateLine(ownerName: String, kind: LockKind): String = "$SYNC_OWNER_ONLY. ${enterLock(ownerName, kind)}"

/** A service's icon. */
@get:DrawableRes
internal val SyncService.icon: Int
    get() = when (this) {
        SyncService.NEXTCLOUD -> R.drawable.ic_cloud
        SyncService.WEBDAV -> R.drawable.ic_server
        SyncService.GOOGLE_DRIVE -> R.drawable.ic_gdrive
    }

/** The launch toast's bold part, colon included, then the rest (`m8-sync-tv` notice, noticemira,
 *  noticeoff); anyone else is told to inform the owner, by name. */
internal fun launchNoticeText(notice: LaunchNotice): Pair<String, String> = when {
    notice.failure.retriesItself -> "$COULDNT_SYNC:" to "${launchReason(notice.failure, notice.account)}. Ayn tries again by itself"
    notice.isOwner -> "$SYNC_DIDNT_WORK:" to "${syncFailureLine(notice.failure, notice.account, TvSyncWords)} Fix it in Settings › Sync"
    else -> "$SYNC_ISNT_WORKING:" to "inform ${ownerToTell(notice)}, who can fix it in Settings › Sync"
}

internal fun SyncToast.line(): ToastLine {
    val (text, muted) = syncToastText(this, TvSyncWords)
    val warn = this is SyncToast.TurnedOff && filesLeft
    return ToastLine(if (warn) R.drawable.ic_alert else R.drawable.ic_check, text, muted, warn = warn)
}
