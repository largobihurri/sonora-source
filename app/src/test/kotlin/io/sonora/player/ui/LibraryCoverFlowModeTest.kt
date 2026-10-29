package io.sonora.player.ui

import io.sonora.player.navigation.TopLevelRoute
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryCoverFlowModeTest {

    @Test
    fun `sideways on a Cover Flow list with Now Playing closed, the Cover Flow is on screen`() {
        assertEquals(LibraryCoverFlowMode.Shown, libraryCoverFlowMode(landscape = true, TopLevelRoute.Library, libraryCoverFlow = true, nowPlayingOpen = false))
    }

    @Test
    fun `under a sideways Now Playing, open or dragged, the Cover Flow is already what lies beneath`() {
        // nowPlayingOpen is where the sheet is headed: true while it is open and while a finger drags it down.
        assertEquals(LibraryCoverFlowMode.Covered, libraryCoverFlowMode(landscape = true, TopLevelRoute.Library, libraryCoverFlow = true, nowPlayingOpen = true))
    }

    @Test
    fun `upright there is no Cover Flow, whatever Now Playing does`() {
        for (open in listOf(true, false)) {
            for (coverFlow in listOf(true, false)) {
                assertEquals(LibraryCoverFlowMode.None, libraryCoverFlowMode(landscape = false, TopLevelRoute.Library, coverFlow, open))
            }
        }
    }

    @Test
    fun `a Library screen without a Cover Flow never shows one`() {
        for (open in listOf(true, false)) {
            assertEquals(LibraryCoverFlowMode.None, libraryCoverFlowMode(landscape = true, TopLevelRoute.Library, libraryCoverFlow = false, open))
        }
    }

    @Test
    fun `other tabs never show the Library's Cover Flow`() {
        for (tab in TopLevelRoute.entries - TopLevelRoute.Library) {
            for (open in listOf(true, false)) {
                assertEquals(LibraryCoverFlowMode.None, libraryCoverFlowMode(landscape = true, tab, libraryCoverFlow = true, open))
            }
        }
    }
}
