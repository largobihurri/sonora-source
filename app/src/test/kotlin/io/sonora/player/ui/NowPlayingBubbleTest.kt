package io.sonora.player.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingBubbleTest {

    @Test
    fun `opened from the Cover Flow's capsule, Now Playing grows out of that capsule while the Cover Flow is there`() {
        for (mode in listOf(LibraryCoverFlowMode.Covered, LibraryCoverFlowMode.Shown)) {
            assertEquals(NowPlayingBubble.CoverFlow, nowPlayingBubble(openedFromCoverFlow = true, mode, gravity = false))
        }
    }

    @Test
    fun `without a Cover Flow, Now Playing grows out of the mini player`() {
        assertEquals(NowPlayingBubble.MiniPlayer, nowPlayingBubble(openedFromCoverFlow = false, LibraryCoverFlowMode.None, gravity = false))
        // Opened from the Cover Flow and then turned upright: it shrinks back into the mini player that is there now.
        assertEquals(NowPlayingBubble.MiniPlayer, nowPlayingBubble(openedFromCoverFlow = true, LibraryCoverFlowMode.None, gravity = false))
    }

    @Test
    fun `over a Cover Flow it did not come from, Now Playing is the ordinary sheet`() {
        for (mode in listOf(LibraryCoverFlowMode.Covered, LibraryCoverFlowMode.Shown)) {
            assertEquals(NowPlayingBubble.None, nowPlayingBubble(openedFromCoverFlow = false, mode, gravity = false))
        }
    }

    @Test
    fun `in gravity mode the sheet keeps sliding toward the floor instead of growing out of the mini player`() {
        assertEquals(NowPlayingBubble.None, nowPlayingBubble(openedFromCoverFlow = false, LibraryCoverFlowMode.None, gravity = true))
        assertEquals(NowPlayingBubble.CoverFlow, nowPlayingBubble(openedFromCoverFlow = true, LibraryCoverFlowMode.Shown, gravity = true))
    }

    @Test
    fun `a cross-fading Now Playing takes touches only while it is heading open`() {
        assertTrue(nowPlayingTakesTouch(crossFades = true, headingOpen = true))
        assertFalse(nowPlayingTakesTouch(crossFades = true, headingOpen = false))
        assertTrue(nowPlayingTakesTouch(crossFades = false, headingOpen = true))
        // A moving sheet or bubble keeps its touch: a finger may catch it on its way out.
        assertTrue(nowPlayingTakesTouch(crossFades = false, headingOpen = false))
    }
}
