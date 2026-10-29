package io.sonora.player.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pixels at density 1. Nested scroll signs: a negative consumed delta scrolled the content toward its end (the finger
 * moved up), a positive one toward its start; what the content could not take is reported as left over.
 */
class ChromeCollapseTest {

    private val chrome = ChromeCollapse(collapseAfter = 24f, expandAfter = 12f)

    private fun towardEnd(px: Float) = chrome.onScroll(consumed = -px, left = 0f)

    private fun towardStart(px: Float) = chrome.onScroll(consumed = px, left = 0f)

    @Test
    fun `collapses only after a deliberate scroll toward the end`() {
        towardEnd(10f)
        towardEnd(13f)
        assertFalse(chrome.collapsed)

        assertTrue(towardEnd(1f))
        assertTrue(chrome.collapsed)
    }

    @Test
    fun `any deliberate scroll toward the start expands`() {
        towardEnd(40f)
        towardStart(11f)
        assertTrue(chrome.collapsed)

        assertTrue(towardStart(1f))
        assertFalse(chrome.collapsed)
    }

    @Test
    fun `finger jitter neither resets progress nor toggles the state`() {
        // A slow drag with small reversals still collapses after 24 px of net travel.
        towardEnd(20f)
        towardStart(2f)
        towardEnd(6f)
        assertTrue(chrome.collapsed)

        // Once collapsed, a little jitter back does not expand, and more travel toward the end does nothing.
        towardStart(3f)
        towardEnd(1f)
        towardStart(3f)
        assertTrue(chrome.collapsed)
    }

    @Test
    fun `the end of a fling never toggles back and forth`() {
        // A fling toward the end decays in one direction: one change, then nothing.
        val changes = listOf(30f, 18f, 9f, 4f, 1.5f, 0.5f).count { towardEnd(it) }
        assertTrue(chrome.collapsed)
        assertTrue(changes == 1)
    }

    @Test
    fun `after a change the opposite direction needs its full threshold again`() {
        towardEnd(30f)
        towardStart(12f)
        assertFalse(chrome.collapsed)

        towardEnd(23f)
        assertFalse(chrome.collapsed) // the earlier travel toward the end was forgotten
    }

    @Test
    fun `reaching the top expands at once`() {
        towardEnd(60f)
        assertTrue(chrome.onScroll(consumed = 4f, left = 6f))
        assertFalse(chrome.collapsed)
    }

    @Test
    fun `overscroll at the end never collapses`() {
        repeat(10) { chrome.onScroll(consumed = 0f, left = -40f) }
        assertFalse(chrome.collapsed)
    }

    @Test
    fun `content that cannot scroll never collapses`() {
        repeat(10) {
            chrome.onScroll(consumed = 0f, left = -30f)
            chrome.onScroll(consumed = 0f, left = 30f)
        }
        assertFalse(chrome.collapsed)
    }

    @Test
    fun `a rounding leftover is not mistaken for the top`() {
        towardEnd(30f)
        chrome.onScroll(consumed = 2f, left = 0.01f)
        assertTrue(chrome.collapsed)
    }

    @Test
    fun `tapping the compact pill expands`() {
        towardEnd(30f)
        assertTrue(chrome.expand())
        assertFalse(chrome.collapsed)
    }

    @Test
    fun `switching tabs while expanded forgets partial travel`() {
        towardEnd(20f)
        assertFalse(chrome.expand()) // already expanded: no change, but progress is reset
        towardEnd(20f)
        assertFalse(chrome.collapsed)
    }

    @Test
    fun `a screen pushed or popped keeps a collapsed chrome collapsed`() {
        towardEnd(30f)
        assertTrue(chrome.collapsed)

        assertFalse(chrome.onScreenChanged())
        assertTrue(chrome.collapsed)
    }

    @Test
    fun `a screen pushed or popped keeps an expanded chrome expanded and forgets partial travel`() {
        towardEnd(20f)
        assertFalse(chrome.onScreenChanged())
        assertFalse(chrome.collapsed)
        towardEnd(20f)
        assertFalse(chrome.collapsed) // the travel on the previous screen was forgotten
    }
}
