package com.arshad.ayn.ui.mobile.common

import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Test

/** Light values are hand-worked from the mockups' `--c` (TEST-ORACLE-01). */
class PictureLightTest {

    private fun channels(red: Int, green: Int, blue: Int): List<Int> =
        lightFromAverage(red, green, blue).let { listOf(it.red, it.green, it.blue) }.map { (it * 255).roundToInt() }

    @Test
    fun aPicturesAverageBrightensToALight() {
        assertEquals(listOf(210, 210, 210), channels(90, 90, 90))
        assertEquals(listOf(50, 75, 210), channels(20, 30, 84))
    }

    @Test
    fun aLightKeepsEveryChannelWithinItsRange() {
        assertEquals(listOf(210, 45, 45), channels(120, 2, 0))
        assertEquals(listOf(210, 210, 210), channels(0, 0, 0))
    }
}
