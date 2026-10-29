package io.sonora.player.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shell's bottom chrome as screens drive it: scrolling through the root nested scroll connection, screens reported
 * by their identity. Pixels at density 1 (collapses after 24 px toward the end, expands after 12 px toward the start).
 */
class BottomChromeStateTest {

    private val chrome = BottomChromeState(collapseAfterPx = 24f, expandAfterPx = 12f)

    private fun towardEnd(px: Float) {
        chrome.onPostScroll(consumed = Offset(0f, -px), available = Offset.Zero, source = NestedScrollSource.UserInput)
    }

    private fun towardStart(px: Float) {
        chrome.onPostScroll(consumed = Offset(0f, px), available = Offset.Zero, source = NestedScrollSource.UserInput)
    }

    @Test
    fun `scrolling toward the end collapses it and back toward the start expands it`() {
        towardEnd(23f)
        assertFalse(chrome.collapsed)
        towardEnd(1f)
        assertTrue(chrome.collapsed)
        towardStart(12f)
        assertFalse(chrome.collapsed)
    }

    @Test
    fun `pulling at the top expands it`() {
        towardEnd(30f)
        assertTrue(chrome.collapsed)
        chrome.onPostScroll(consumed = Offset.Zero, available = Offset(0f, 5f), source = NestedScrollSource.UserInput)
        assertFalse(chrome.collapsed)
    }

    @Test
    fun `a header scrolled off and back into its list keeps the travel`() {
        chrome.onScreenShown("settings")
        towardEnd(20f)
        // The header's lazy item left and came back: the same screen.
        chrome.onScreenShown("settings")
        towardEnd(4f)
        assertTrue(chrome.collapsed)
    }

    @Test
    fun `a new screen forgets the previous screen's travel and keeps the size`() {
        chrome.onScreenShown("library")
        towardEnd(20f)
        chrome.onScreenShown("settings")
        towardEnd(4f)
        assertFalse(chrome.collapsed)

        towardEnd(30f)
        assertTrue(chrome.collapsed)
        chrome.onScreenShown("hidden items")
        assertTrue(chrome.collapsed)
    }

    @Test
    fun `a screen leaving forgets its travel`() {
        chrome.onScreenShown("album")
        towardEnd(20f)
        chrome.onScreenLeft("album")
        towardEnd(4f)
        assertFalse(chrome.collapsed)
    }

    @Test
    fun `the screen shown again after it left counts as a change`() {
        chrome.onScreenShown("album")
        chrome.onScreenLeft("album")
        towardEnd(20f)
        chrome.onScreenShown("album")
        towardEnd(4f)
        assertFalse(chrome.collapsed)
    }

    @Test
    fun `expand forgets the travel`() {
        towardEnd(20f)
        chrome.expand()
        towardEnd(4f)
        assertFalse(chrome.collapsed)
    }
}
