package io.sonora.player.ui

import io.sonora.player.navigation.TopLevelRoute
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryVisualCollectionModeTest {

    @Test
    fun `sideways on a visual collection list with Now Playing closed, the visual collection is on screen`() {
        assertEquals(LibraryVisualCollectionMode.Shown, libraryVisualCollectionMode(landscape = true, TopLevelRoute.Library, libraryVisualCollection = true, nowPlayingOpen = false))
    }

    @Test
    fun `under a sideways Now Playing, open or dragged, the visual collection is already what lies beneath`() {
        // nowPlayingOpen is where the sheet is headed: true while it is open and while a finger drags it down.
        assertEquals(LibraryVisualCollectionMode.Covered, libraryVisualCollectionMode(landscape = true, TopLevelRoute.Library, libraryVisualCollection = true, nowPlayingOpen = true))
    }

    @Test
    fun `upright there is no visual collection, whatever Now Playing does`() {
        for (open in listOf(true, false)) {
            for (visualCollection in listOf(true, false)) {
                assertEquals(LibraryVisualCollectionMode.None, libraryVisualCollectionMode(landscape = false, TopLevelRoute.Library, visualCollection, open))
            }
        }
    }

    @Test
    fun `a Library screen without a visual collection never shows one`() {
        for (open in listOf(true, false)) {
            assertEquals(LibraryVisualCollectionMode.None, libraryVisualCollectionMode(landscape = true, TopLevelRoute.Library, libraryVisualCollection = false, open))
        }
    }

    @Test
    fun `other tabs never show the Library's visual collection`() {
        for (tab in TopLevelRoute.entries - TopLevelRoute.Library) {
            for (open in listOf(true, false)) {
                assertEquals(LibraryVisualCollectionMode.None, libraryVisualCollectionMode(landscape = true, tab, libraryVisualCollection = true, open))
            }
        }
    }
}
