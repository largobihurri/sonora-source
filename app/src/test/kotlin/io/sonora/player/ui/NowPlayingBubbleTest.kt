package io.sonora.player.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingBubbleTest {

    @Test
    fun `opened from the visual collection's capsule, Now Playing grows out of that capsule while the visual collection is there`() {
        for (mode in listOf(LibraryVisualCollectionMode.Covered, LibraryVisualCollectionMode.Shown)) {
            assertEquals(NowPlayingBubble.VisualCollection, nowPlayingBubble(openedFromVisualCollection = true, mode, gravity = false))
        }
    }

    @Test
    fun `without a visual collection, Now Playing grows out of the mini player`() {
        assertEquals(NowPlayingBubble.MiniPlayer, nowPlayingBubble(openedFromVisualCollection = false, LibraryVisualCollectionMode.None, gravity = false))
        // Opened from the visual collection and then turned upright: it shrinks back into the mini player that is there now.
        assertEquals(NowPlayingBubble.MiniPlayer, nowPlayingBubble(openedFromVisualCollection = true, LibraryVisualCollectionMode.None, gravity = false))
    }

    @Test
    fun `over a visual collection it did not come from, Now Playing is the ordinary sheet`() {
        for (mode in listOf(LibraryVisualCollectionMode.Covered, LibraryVisualCollectionMode.Shown)) {
            assertEquals(NowPlayingBubble.None, nowPlayingBubble(openedFromVisualCollection = false, mode, gravity = false))
        }
    }

    @Test
    fun `in gravity mode the sheet keeps sliding toward the floor instead of growing out of the mini player`() {
        assertEquals(NowPlayingBubble.None, nowPlayingBubble(openedFromVisualCollection = false, LibraryVisualCollectionMode.None, gravity = true))
        assertEquals(NowPlayingBubble.VisualCollection, nowPlayingBubble(openedFromVisualCollection = true, LibraryVisualCollectionMode.Shown, gravity = true))
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
