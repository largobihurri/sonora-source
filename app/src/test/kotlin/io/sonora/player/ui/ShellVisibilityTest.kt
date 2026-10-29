package io.sonora.player.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellVisibilityTest {

    @Test
    fun `a settled open sheet over a track hides the chrome`() {
        assertEquals(
            ShellVisibility(nowPlaying = true, chrome = false),
            shellVisibility(sheetShown = true, sheetCovers = true, hasTrack = true, atTopLevel = true),
        )
    }

    @Test
    fun `a sheet being dragged or animating keeps the chrome underneath`() {
        assertEquals(
            ShellVisibility(nowPlaying = true, chrome = true),
            shellVisibility(sheetShown = true, sheetCovers = false, hasTrack = true, atTopLevel = true),
        )
    }

    @Test
    fun `a shown sheet without a track keeps the chrome visible`() {
        assertEquals(
            ShellVisibility(nowPlaying = false, chrome = true),
            shellVisibility(sheetShown = true, sheetCovers = true, hasTrack = false, atTopLevel = true),
        )
    }

    @Test
    fun `a hidden sheet shows the chrome on top-level routes only`() {
        assertEquals(
            ShellVisibility(nowPlaying = false, chrome = true),
            shellVisibility(sheetShown = false, sheetCovers = false, hasTrack = true, atTopLevel = true),
        )
        assertEquals(
            ShellVisibility(nowPlaying = false, chrome = false),
            shellVisibility(sheetShown = false, sheetCovers = false, hasTrack = true, atTopLevel = false),
        )
    }

    @Test
    fun `the tab screens are not drawn under a Now Playing that rests open and opaque`() {
        assertFalse(tabContentDrawn(nowPlayingShown = true, sheetCovers = true, nowPlayingOpaque = true))
    }

    @Test
    fun `the tab screens are drawn whenever anything of them can show`() {
        // Moving sheet, no sheet, a track-less sheet, or Now Playing still cross-fading in (reduced motion): what is beneath shows.
        assertTrue(tabContentDrawn(nowPlayingShown = true, sheetCovers = false, nowPlayingOpaque = true))
        assertTrue(tabContentDrawn(nowPlayingShown = false, sheetCovers = true, nowPlayingOpaque = true))
        assertTrue(tabContentDrawn(nowPlayingShown = true, sheetCovers = true, nowPlayingOpaque = false))
        assertTrue(tabContentDrawn(nowPlayingShown = false, sheetCovers = false, nowPlayingOpaque = true))
    }
}
