package com.arshad.ayn.ui.tv.common

import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Test

/** Light values are hand-worked from the mockups' `--c` (TEST-ORACLE-01). */
class PictureLightTest {

    @Test
    fun aPicturesAverageBrightensToALight() {
        val grey = lightFromAverage(90, 90, 90)
        val blue = lightFromAverage(20, 30, 84)
        assertEquals(listOf(210, 210, 210), listOf(grey.red, grey.green, grey.blue).map { (it * 255).roundToInt() })
        assertEquals(listOf(50, 75, 210), listOf(blue.red, blue.green, blue.blue).map { (it * 255).roundToInt() })
    }

    @Test
    fun aLightKeepsEveryChannelWithinItsRange() {
        val red = lightFromAverage(120, 2, 0)
        val black = lightFromAverage(0, 0, 0)
        assertEquals(listOf(210, 45, 45), listOf(red.red, red.green, red.blue).map { (it * 255).roundToInt() })
        assertEquals(listOf(210, 210, 210), listOf(black.red, black.green, black.blue).map { (it * 255).roundToInt() })
    }
}
