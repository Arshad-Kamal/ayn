package com.arshad.ayn.ui.tv.common

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import com.arshad.ayn.feature.profiles.PickKind
import com.arshad.ayn.feature.profiles.PickRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * The activity's file picker (M5-D20): it offers every app that picks files (M5.11k); a pick made
 * while KE's process was gone reaches the next activity, built from nothing but the saved state,
 * with the request it was opened for; a cancelled picker reaches nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FilePickerTest {

    class PickerActivity : ComponentActivity() {
        lateinit var chooseFile: (PickRequest) -> Unit

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            chooseFile = registerFilePicker { request, uri, _ -> picked += request to uri }
        }
    }

    @Before
    fun clearPicked() {
        picked.clear()
    }

    @Test
    fun thePickerOffersEveryAppThatPicksAFileExceptTheTvPlaceholders() {
        val activity = Robolectric.buildActivity(PickerActivity::class.java).setup().get()

        activity.chooseFile(PickRequest(PickKind.Import, profileId = 7))

        val opened = shadowOf(activity).nextStartedActivityForResult.intent
        assertEquals(Intent.ACTION_CHOOSER, opened.action)
        @Suppress("DEPRECATION")
        val pick = opened.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_GET_CONTENT, pick.action)
        assertTrue(Intent.CATEGORY_OPENABLE in pick.categories)
        assertEquals("*/*", pick.type)
        @Suppress("DEPRECATION")
        val startsAt = pick.getParcelableExtra<Uri>(DocumentsContract.EXTRA_INITIAL_URI)
        assertEquals(Uri.parse("content://com.android.providers.downloads.documents/document/downloads"), startsAt)
        @Suppress("DEPRECATION")
        val excluded = opened.getParcelableArrayExtra(Intent.EXTRA_EXCLUDE_COMPONENTS)!!.toSet()
        assertEquals(
            setOf(
                ComponentName.unflattenFromString("com.google.android.tv.frameworkpackagestubs/.Stubs\$DocumentsStub"),
                ComponentName.unflattenFromString("com.google.android.tv.frameworkpackagestubs/.Stubs\$MediaStub"),
            ),
            excluded,
        )
    }

    @Test
    fun aFileManagerBelowTheSystemPickersPriorityIsOfferedAsATargetOfItsOwn() {
        val activity = Robolectric.buildActivity(PickerActivity::class.java).setup().get()
        val answers = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        val packages = shadowOf(activity.packageManager)
        packages.addResolveInfoForIntent(answers, answer("com.android.documentsui", "com.android.documentsui.picker.PickActivity", 100))
        packages.addResolveInfoForIntent(answers, answer("me.zhanghai.android.files", "me.zhanghai.android.files.filelist.FileListActivity", 0))
        packages.addResolveInfoForIntent(answers, answer("com.google.android.tv.frameworkpackagestubs", "com.google.android.tv.frameworkpackagestubs.Stubs\$MediaStub", -1))

        activity.chooseFile(PickRequest(PickKind.Restore, profileId = 7))

        val opened = shadowOf(activity).nextStartedActivityForResult.intent
        @Suppress("DEPRECATION")
        val initial = opened.getParcelableArrayExtra(Intent.EXTRA_INITIAL_INTENTS)!!.map { it as Intent }
        assertEquals(listOf(ComponentName("me.zhanghai.android.files", "me.zhanghai.android.files.filelist.FileListActivity")), initial.map { it.component })
        assertEquals(Intent.ACTION_GET_CONTENT, initial.single().action)
        assertEquals("*/*", initial.single().type)
        assertEquals(false, initial.single().hasExtra(DocumentsContract.EXTRA_INITIAL_URI))
        @Suppress("DEPRECATION")
        val pick = opened.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(null, pick.component)
        assertTrue(pick.hasExtra(DocumentsContract.EXTRA_INITIAL_URI))
    }

    @Test
    fun aPickReachesTheActivityRecreatedFromSavedStateWithItsRequest() {
        val (requestCode, next) = openPickerThenLoseTheProcess(PickRequest(PickKind.Import, profileId = 7))

        next.get().activityResultRegistry.dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(FILE))

        assertEquals(listOf(PickRequest(PickKind.Import, profileId = 7) to FILE), picked)
    }

    @Test
    fun aFirstLaunchRestoreKeepsItsMissingProfile() {
        val (requestCode, next) = openPickerThenLoseTheProcess(PickRequest(PickKind.Restore, profileId = null))

        next.get().activityResultRegistry.dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(FILE))

        assertEquals(listOf(PickRequest(PickKind.Restore, profileId = null) to FILE), picked)
    }

    @Test
    fun aCancelledPickerReachesNothing() {
        val (requestCode, next) = openPickerThenLoseTheProcess(PickRequest(PickKind.Backup, profileId = 7))

        next.get().activityResultRegistry.dispatchResult(requestCode, Activity.RESULT_CANCELED, null)

        assertEquals(emptyList<Pair<PickRequest, Uri>>(), picked)
    }

    /** Opens the picker for [request], saves the activity's state, and builds a new activity from
     *  that state alone (nothing else survives a process death). */
    private fun openPickerThenLoseTheProcess(request: PickRequest): Pair<Int, ActivityController<PickerActivity>> {
        val first = Robolectric.buildActivity(PickerActivity::class.java).setup()
        first.get().chooseFile(request)
        val opened = shadowOf(first.get()).nextStartedActivityForResult
        val saved = Bundle()
        first.saveInstanceState(saved).pause().stop().destroy()
        return opened.requestCode to Robolectric.buildActivity(PickerActivity::class.java).setup(saved)
    }

    private fun answer(packageName: String, activityName: String, priority: Int): ResolveInfo = ResolveInfo().apply {
        this.priority = priority
        isDefault = true
        activityInfo = ActivityInfo().apply {
            this.packageName = packageName
            name = activityName
            applicationInfo = ApplicationInfo().apply { this.packageName = packageName }
        }
    }

    private companion object {
        val FILE: Uri = Uri.parse("content://com.android.providers.downloads.documents/document/42")
        val picked = mutableListOf<Pair<PickRequest, Uri>>()
    }
}
