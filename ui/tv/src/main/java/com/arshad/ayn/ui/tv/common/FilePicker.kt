package com.arshad.ayn.ui.tv.common

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.arshad.ayn.feature.profiles.PickKind
import com.arshad.ayn.feature.profiles.PickRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Registers the file picker (§ B3p: restores and imports) for this activity and returns what
 * opens it: Android's chooser over every app that picks files, so a TV file manager can stand in
 * for the system picker (M5.11k). Call it in every `onCreate`, after `super.onCreate`: the picker can
 * outlive KE's process, and its result reaches only a launcher registered as the activity is
 * created (M5-D20). The request a pick was opened for is kept with the activity's saved state and
 * goes to [onPicked] with the picked file, with a main-safe reader of the name the picker gives
 * it. A cancelled picker reaches nothing. The request stays until the next launch replaces it, so
 * a second picker opened by a double press still has it.
 */
fun ComponentActivity.registerFilePicker(onPicked: (PickRequest, Uri, suspend () -> String?) -> Unit): (PickRequest) -> Unit {
    var asked: PickRequest? = savedStateRegistry.consumeRestoredStateForKey(ASKED_KEY)?.toPickRequest()
    savedStateRegistry.registerSavedStateProvider(ASKED_KEY) { asked.toBundle() }
    val context = applicationContext
    val launcher = registerForActivityResult(ChooseAFile()) { uri ->
        val request = asked ?: return@registerForActivityResult
        if (uri != null) onPicked(request, uri) { withContext(Dispatchers.IO) { displayName(context, uri) } }
    }
    return { request ->
        asked = request
        launcher.launch("*/*")
    }
}

private const val ASKED_KEY = "ke.filePicker.asked"
private const val KIND = "kind"
private const val PROFILE = "profile"

private fun PickRequest?.toBundle(): Bundle = Bundle().apply {
    if (this@toBundle == null) return@apply
    putString(KIND, kind.name)
    profileId?.let { putLong(PROFILE, it) }
}

private fun Bundle.toPickRequest(): PickRequest? {
    val kind = PickKind.entries.firstOrNull { it.name == getString(KIND) } ?: return null
    return PickRequest(kind, if (containsKey(PROFILE)) getLong(PROFILE) else null)
}

/**
 * `GET_CONTENT` in a chooser. `OPEN_DOCUMENT` and a bare `GET_CONTENT` both open the system
 * picker directly (DocumentsUI answers them at priority 100), whose D-pad focus is unusable on the
 * Shield. The chooser too lists only the answers that share the top priority (Android 11's
 * `ResolverListController.filterLowPriority`), and with one left it opens that one at once, so the
 * apps it would drop are added as initial targets of their own. The system picker still starts at
 * Downloads, where KE saves its backups; the others get no start (Material Files 1.7.5 fails on
 * the system picker's Downloads document). Any type: a wrong file is refused with a reason rather
 * than hidden.
 */
private class ChooseAFile : ActivityResultContracts.GetContent() {
    override fun createIntent(context: Context, input: String): Intent {
        val pick = super.createIntent(context, input).putExtra(DocumentsContract.EXTRA_INITIAL_URI, DownloadsFolder)
        return Intent.createChooser(pick, "Choose a file with")
            .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, AndroidTvStubs)
            .putExtra(Intent.EXTRA_INITIAL_INTENTS, droppedByTheChooser(context, pick))
    }
}

private fun droppedByTheChooser(context: Context, pick: Intent): Array<Intent> {
    val answers = context.packageManager.queryIntentActivities(pick, PackageManager.MATCH_DEFAULT_ONLY)
    val top = answers.maxOfOrNull { it.priority } ?: return emptyArray()
    return answers
        .filter { it.priority < top }
        .map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
        .filter { it !in AndroidTvStubs }
        .map { Intent(pick).setComponent(it).apply { removeExtra(DocumentsContract.EXTRA_INITIAL_URI) } }
        .toTypedArray()
}

private val DownloadsFolder: Uri = DocumentsContract.buildDocumentUri("com.android.providers.downloads.documents", "downloads")

/** Google TV's placeholders for a missing picker: in the chooser they would only show a toast. */
private val AndroidTvStubs: Array<ComponentName> = arrayOf("DocumentsStub", "MediaStub").map {
    ComponentName("com.google.android.tv.frameworkpackagestubs", "com.google.android.tv.frameworkpackagestubs.Stubs\$$it")
}.toTypedArray()

/**
 * The picked file's name, or `null` when its provider doesn't give one (the screen then names it
 * generically). Any app's provider may answer, and Binder carries its runtime exceptions across,
 * so each one counts as no name.
 */
private fun displayName(context: Context, uri: Uri): String? =
    try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (_: RuntimeException) {
        null
    }
