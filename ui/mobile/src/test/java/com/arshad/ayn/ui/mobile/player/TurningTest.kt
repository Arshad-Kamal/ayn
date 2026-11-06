package com.arshad.ayn.ui.mobile.player

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The turning rules (Q39): what the activity asks for, how the phone is held, when a hold lets go. */
class TurningTest {
    @Test
    fun onlyTheShownWatchPageTurnsAndAHoldFixesItsWay() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, watchOrientation(expanded = false, hold = null))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, watchOrientation(expanded = false, hold = Hold.Landscape))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_USER, watchOrientation(expanded = true, hold = null))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, watchOrientation(expanded = true, hold = Hold.Landscape))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, watchOrientation(expanded = true, hold = Hold.Portrait))
    }

    @Test
    fun thePostureIsUprightOrSidewaysNearTheAxesAndNothingBetween() {
        assertEquals(Hold.Portrait, postureAt(0))
        assertEquals(Hold.Portrait, postureAt(25))
        assertEquals(Hold.Portrait, postureAt(340))
        assertEquals(Hold.Landscape, postureAt(90))
        assertEquals(Hold.Landscape, postureAt(65))
        assertEquals(Hold.Landscape, postureAt(285))
        assertEquals(Hold.Landscape, postureAt(115))
        assertEquals(Hold.Portrait, postureAt(335))
        assertNull("past the slack", postureAt(116))
        assertNull("between", postureAt(45))
        assertNull("upside down", postureAt(180))
        assertNull("flat", postureAt(-1))
    }

    @Test
    fun aHoldLetsGoOnlyWhenThePhoneIsHeldItsWayWithAutoRotateOn() {
        assertTrue(holdLetsGo(Hold.Landscape, Hold.Landscape, autoRotate = true))
        assertFalse(holdLetsGo(Hold.Landscape, Hold.Portrait, autoRotate = true))
        assertFalse(holdLetsGo(Hold.Landscape, null, autoRotate = true))
        assertFalse("the sensor would snap it back", holdLetsGo(Hold.Landscape, Hold.Landscape, autoRotate = false))
        assertTrue(holdLetsGo(Hold.Portrait, Hold.Portrait, autoRotate = true))
    }
}
