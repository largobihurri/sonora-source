package io.sonora.player.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TabSlideTest {

    @Test
    fun `going to a tab on the right slides the current screen left and the new one in from the right`() {
        // At the start of a 0 -> 1 switch the strip sits at 0.
        assertEquals(0f, tabSlideOffset(index = 0, position = 0f, rtl = false), 0f)
        assertEquals(1f, tabSlideOffset(index = 1, position = 0f, rtl = false), 0f)
        // Half way both moved together, no fade.
        assertEquals(-0.5f, tabSlideOffset(index = 0, position = 0.5f, rtl = false), 1e-6f)
        assertEquals(0.5f, tabSlideOffset(index = 1, position = 0.5f, rtl = false), 1e-6f)
    }

    @Test
    fun `going to a tab on the left slides the opposite way`() {
        assertEquals(-1f, tabSlideOffset(index = 0, position = 1f, rtl = false), 0f)
        assertEquals(0.25f, tabSlideOffset(index = 1, position = 0.75f, rtl = false), 1e-6f)
    }

    @Test
    fun `right-to-left layouts mirror the direction`() {
        // In RTL the next tab sits to the left in the bar, so its screen enters from the left.
        assertEquals(-1f, tabSlideOffset(index = 1, position = 0f, rtl = true), 0f)
        assertEquals(0.5f, tabSlideOffset(index = 0, position = 0.5f, rtl = true), 1e-6f)
    }
}
