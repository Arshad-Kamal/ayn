package com.arshad.ayn

import android.app.Activity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class RecentsPreviewTest {
    /** API 30 is the Shield's: only the framework's hidden switch exists there, so a wrong name or
     *  signature would leave a locked profile's screen to the recents snapshot again. Robolectric
     *  doesn't apply the hidden-API policy: whether the runtime allows the call is a device check. */
    @Test
    @Config(sdk = [30, 31, 32])
    fun `API 30 to 32 reach the framework's preview screenshot switch`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        assertTrue(activity.setRecentsPreviewEnabled(false))
        assertTrue(activity.setRecentsPreviewEnabled(true))
    }

    @Test
    @Config(sdk = [33])
    fun `API 33 and later use the public switch`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        assertTrue(activity.setRecentsPreviewEnabled(false))
    }
}
