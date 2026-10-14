package io.github.zz9pa.amplifree.ui

import io.github.zz9pa.amplifree.ui.screens.masterDisplay
import io.github.zz9pa.amplifree.ui.screens.scaledDisplay
import org.junit.Assert.assertEquals
import org.junit.Test

class MasterDisplayTest {
    @Test fun wholeNumbersHaveNoDecimal() {
        assertEquals("0", masterDisplay(0f))
        assertEquals("9", masterDisplay(9f / 11f))
        assertEquals("10", masterDisplay(10f / 11f))
        assertEquals("11", masterDisplay(1f))
    }

    @Test fun otherValuesShowOneDecimal() {
        assertEquals("5.5", masterDisplay(0.5f))
        assertEquals("1.8", masterDisplay(0.16f))
    }

    @Test fun outOfRangeIsClamped() {
        assertEquals("11", masterDisplay(1.3f))
        assertEquals("0", masterDisplay(-0.2f))
    }

    @Test fun ordinaryKnobsUseAZeroToTenDial() {
        assertEquals("0", scaledDisplay(0f, 10f))
        assertEquals("10", scaledDisplay(1f, 10f))
        assertEquals("6.3", scaledDisplay(0.63f, 10f))
    }
}
