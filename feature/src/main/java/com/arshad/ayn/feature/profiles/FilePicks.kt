package com.arshad.ayn.feature.profiles

import android.net.Uri
import kotlinx.coroutines.flow.StateFlow

/** What a file pick is for (M5-D20). */
enum class PickKind {
    /** First launch's restore, on the gate: no profile exists yet. */
    Restore,

    /** Settings › Backup & restore: Find a backup…, Choose another file. */
    Backup,

    /** Import from YouTube. */
    Import,
}

/** A pick asked for: [kind], by the Settings of [profileId]'s shell (`null` for [PickKind.Restore]). */
data class PickRequest(val kind: PickKind, val profileId: Long?)

/** A file picked for [request]; [fileName] as the picker named it, if it did. */
data class PickedFile(val request: PickRequest, val uri: Uri, val fileName: String?)

/** Hands a profile's Settings the file picked for it, and only while its shell is open (Q29). */
interface SettingsPicks {
    /** The Settings pick waiting for the open shell's profile, if any. */
    val settingsPick: StateFlow<PickedFile?>

    /** Takes [pick]: `false` when it no longer waits. */
    fun takeSettingsPick(pick: PickedFile): Boolean
}
