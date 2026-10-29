package io.sonora.player.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class UnderSidebarTintTest {

    @Test
    fun `the strip covers what the sidebar leaves free at the start edge`() {
        assertEquals(StripSpan(start = 0f, width = 664f), underSidebarStrip(insetPx = 664f, windowPx = 2304f, rtl = false))
    }

    @Test
    fun `in a right-to-left layout it is at the right edge`() {
        assertEquals(StripSpan(start = 1640f, width = 664f), underSidebarStrip(insetPx = 664f, windowPx = 2304f, rtl = true))
    }

    @Test
    fun `it follows the sidebar's reveal and is empty once the sidebar is gone`() {
        assertEquals(StripSpan(0f, 332f), underSidebarStrip(332f, 2304f, rtl = false))
        assertEquals(StripSpan(0f, 0f), underSidebarStrip(0f, 2304f, rtl = false))
    }

    @Test
    fun `it never reaches past the window`() {
        assertEquals(StripSpan(0f, 100f), underSidebarStrip(400f, 100f, rtl = false))
        assertEquals(StripSpan(0f, 0f), underSidebarStrip(-5f, 100f, rtl = false))
    }
}
