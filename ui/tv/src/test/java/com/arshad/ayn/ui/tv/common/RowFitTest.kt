package com.arshad.ayn.ui.tv.common

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A 960-wide row: slots at 126, 340, 554 (200-wide cards, 214 steps, 58 safe end), so a card
 * fits up to slot 2. Distances are hand-worked from the mockup's track shift.
 */
class RowFitTest {

    private val fit = RowFit(start = 126f, end = 58f, itemWidth = 200f, step = 214f)

    private fun distance(left: Float, size: Float = 200f): Float =
        fit.calculateScrollDistance(offset = left + (200f - size) / 2, size = size, containerSize = 960f)

    @Test
    fun aCardOnASlotInsideTheSafeLineStays() {
        assertEquals(0f, distance(340f), 0.01f)
    }

    @Test
    fun aCardPastTheSafeLineMovesOneWholeStepToTheLastSlot() {
        assertEquals(214f, distance(768f), 0.01f)
    }

    @Test
    fun theRemainingDistanceHoldsMidScroll() {
        assertEquals("the same slot after 68 of 214", 146f, distance(700f), 0.01f)
    }

    @Test
    fun aCardBeforeTheContentColumnMovesBackOneStep() {
        assertEquals(-214f, distance(-88f), 0.01f)
    }

    @Test
    fun theFocusScaleAndANarrowerFocusableDontMoveTheGrid() {
        assertEquals("1.08 scale", 0f, distance(340f, size = 216f), 0.01f)
        assertEquals("74 wide avatar on the slot", 0f, distance(340f, size = 74f), 0.01f)
    }

    @Test
    fun aReturningCardOnTheShownSlotsKeepsTheRowWhereItWas() {
        assertEquals(4, fit.firstShowing(index = 6, first = 4, rowWidth = 960f))
    }

    @Test
    fun aReturningCardAfterTheShownSlotsLandsOnTheLastAndBeforeThemOnTheFirst() {
        assertEquals("card 9 on slot 2", 7, fit.firstShowing(index = 9, first = 4, rowWidth = 960f))
        assertEquals("card 1 on slot 0", 1, fit.firstShowing(index = 1, first = 4, rowWidth = 960f))
    }

    @Test
    fun theRowEndHasRoomForTheLastCardToReachItsSlot() {
        assertEquals(206f, fit.endRoom(960f), 0.01f)
    }
}
