package io.sonora.player.ui

import android.content.pm.ActivityInfo
import androidx.compose.runtime.snapshots.Snapshot
import io.sonora.core.designsystem.layout.WindowClass
import io.sonora.feature.nowplaying.GravityDestination
import io.sonora.core.domain.model.NowPlayingRotation
import io.sonora.player.navigation.TopLevelRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenOrientationTest {

    @Test
    fun `Now Playing turns with the phone, following the user's rotation lock`() {
        for (tab in TopLevelRoute.entries) {
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_USER, screenOrientation(nowPlayingOpen = true, tab, libraryVisualCollection = false))
        }
    }

    @Test
    fun `a Library list with a visual collection follows the sensor, even with the rotation lock on`() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_SENSOR,
            screenOrientation(nowPlayingOpen = false, TopLevelRoute.Library, libraryVisualCollection = true),
        )
    }

    @Test
    fun `Now Playing over a visual collection list still honours the rotation lock`() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_FULL_USER,
            screenOrientation(nowPlayingOpen = true, TopLevelRoute.Library, libraryVisualCollection = true),
        )
    }

    @Test
    fun `every other screen stays upright`() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT,
            screenOrientation(nowPlayingOpen = false, TopLevelRoute.Library, libraryVisualCollection = false),
        )
        // The Library keeps its list underneath another tab: only the Library tab itself may turn.
        for (tab in TopLevelRoute.entries - TopLevelRoute.Library) {
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT, screenOrientation(nowPlayingOpen = false, tab, libraryVisualCollection = true))
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT, screenOrientation(nowPlayingOpen = false, tab, libraryVisualCollection = false))
        }
    }

    @Test
    fun `with gravity mode on, an open Now Playing holds the window portrait, whatever the lock or the tab`() {
        for (tab in TopLevelRoute.entries) {
            for (visualCollection in listOf(true, false)) {
                assertEquals(
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                    screenOrientation(nowPlayingOpen = true, tab, visualCollection, uprightHold = false, gravity = true),
                )
            }
        }
    }

    @Test
    fun `gravity mode leaves every screen but an open Now Playing to its own rule`() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_SENSOR,
            screenOrientation(nowPlayingOpen = false, TopLevelRoute.Library, libraryVisualCollection = true, gravity = true),
        )
        for (tab in TopLevelRoute.entries - TopLevelRoute.Library) {
            assertEquals(
                ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT,
                screenOrientation(nowPlayingOpen = false, tab, libraryVisualCollection = true, gravity = true),
            )
        }
    }

    @Test
    fun `in gravity mode a sideways destination turns the window sideways under the open Now Playing`() {
        // The sideways detent latched the visual collection: the window follows the phone's side, even with the lock on, so
        // the Library composes its visual collection beneath and a drag reveals it.
        for (tab in TopLevelRoute.entries) {
            assertEquals(
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
                screenOrientation(nowPlayingOpen = true, tab, libraryVisualCollection = true, gravity = true, gravitySideways = true),
            )
        }
        assertEquals(LibraryVisualCollectionMode.Covered, libraryVisualCollectionMode(landscape = true, TopLevelRoute.Library, libraryVisualCollection = true, nowPlayingOpen = true))
        // The upright hold still wins.
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT,
            screenOrientation(nowPlayingOpen = true, TopLevelRoute.Library, libraryVisualCollection = true, uprightHold = true, gravity = true, gravitySideways = true),
        )
        // Only gravity mode's open Now Playing reads it.
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_FULL_USER,
            screenOrientation(nowPlayingOpen = true, TopLevelRoute.Library, libraryVisualCollection = true, gravitySideways = true),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT,
            screenOrientation(nowPlayingOpen = false, TopLevelRoute.Search, libraryVisualCollection = false, gravity = true, gravitySideways = true),
        )
    }

    // Large screens (tablets): no lock on any screen, on every Android version.

    private val pad6Landscape = WindowClass.of(widthDp = 1152, heightDp = 720)
    private val pad6Portrait = WindowClass.of(widthDp = 720, heightDp = 1152)
    private val phoneSideways = WindowClass.of(widthDp = 704, heightDp = 320)

    @Test
    fun `a large screen asks for no orientation on any screen`() {
        for (windowClass in listOf(pad6Landscape, pad6Portrait)) {
            for (tab in TopLevelRoute.entries) {
                for (nowPlayingOpen in listOf(true, false)) {
                    for (visualCollection in listOf(true, false)) {
                        assertEquals(
                            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
                            screenOrientation(nowPlayingOpen, tab, visualCollection, windowClass = windowClass),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `a large screen ignores the upright hold and gravity mode`() {
        for (tab in TopLevelRoute.entries) {
            assertEquals(
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
                screenOrientation(nowPlayingOpen = true, tab, libraryVisualCollection = true, uprightHold = true, gravity = true, gravitySideways = true, windowClass = pad6Landscape),
            )
            assertEquals(
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
                screenOrientation(nowPlayingOpen = false, tab, libraryVisualCollection = false, uprightHold = true, windowClass = pad6Portrait),
            )
        }
    }

    @Test
    fun `a phone held sideways keeps every phone rule`() {
        // Wide, but its smallest side is 320 dp: not a large screen.
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_USER, screenOrientation(nowPlayingOpen = true, TopLevelRoute.Library, libraryVisualCollection = false, windowClass = phoneSideways))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR, screenOrientation(nowPlayingOpen = false, TopLevelRoute.Library, libraryVisualCollection = true, windowClass = phoneSideways))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT, screenOrientation(nowPlayingOpen = false, TopLevelRoute.Search, libraryVisualCollection = false, windowClass = phoneSideways))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, screenOrientation(nowPlayingOpen = true, TopLevelRoute.Library, libraryVisualCollection = false, gravity = true, windowClass = phoneSideways))
    }

    @Test
    fun `gravity mode is never on in a large window`() {
        assertTrue(gravityEnabled(setting = true, WindowClass.Phone))
        assertTrue(gravityEnabled(setting = true, phoneSideways))
        assertFalse(gravityEnabled(setting = true, pad6Landscape))
        assertFalse(gravityEnabled(setting = true, pad6Portrait))
        // Off in Settings stays off everywhere.
        assertFalse(gravityEnabled(setting = false, WindowClass.Phone))
        assertFalse(gravityEnabled(setting = false, pad6Landscape))
    }

    @Test
    fun `a detent caught mid-drag turns the window only once the finger lets go`() {
        val side = GravityWindowSide()
        assertFalse(side.sideways(latched = false, dragging = false))
        // Opened over a sideways visual collection: sideways on that very frame, no portrait request in between.
        assertTrue(side.sideways(latched = true, dragging = false))
        // A drag starts sideways; the phone is turned upright under the finger: the window stays sideways.
        assertTrue(side.sideways(latched = true, dragging = true))
        assertTrue(side.sideways(latched = false, dragging = true))
        // Released: the latched side applies.
        assertFalse(side.sideways(latched = false, dragging = false))
        assertFalse(side.sideways(latched = true, dragging = true))
    }

    @Test
    fun `a composition thrown away leaves the window side as it was`() {
        val side = GravityWindowSide()
        assertFalse(side.sideways(latched = false, dragging = false))
        // SonoraRoot asks during composition, which runs in a snapshot: an abandoned one must not have turned the window.
        val abandoned = Snapshot.takeMutableSnapshot()
        abandoned.enter { assertTrue(side.sideways(latched = true, dragging = false)) }
        abandoned.dispose()
        assertFalse(side.sideways(latched = true, dragging = true))
        // Applied, it does.
        val applied = Snapshot.takeMutableSnapshot()
        applied.enter { side.sideways(latched = true, dragging = false) }
        applied.apply().check()
        applied.dispose()
        assertTrue(side.sideways(latched = false, dragging = true))
    }

    @Test
    fun `closing a gravity mode Now Playing leads where its destination latched`() {
        // Sideways: closed in a sideways window, exactly as the landscape flow (Visual collection list, else the upright hold).
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR, orientationAfterGravityClosing(TopLevelRoute.Library, libraryVisualCollection = true, GravityDestination.Sideways))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT, orientationAfterGravityClosing(TopLevelRoute.Library, libraryVisualCollection = false, GravityDestination.Sideways))
        // Upright (or upside down): the portrait screen, held until the phone reads upright, even over a visual collection list
        // with the phone tilted between detents.
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT, orientationAfterGravityClosing(TopLevelRoute.Library, libraryVisualCollection = true, GravityDestination.Upright))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT, orientationAfterGravityClosing(TopLevelRoute.Search, libraryVisualCollection = false, GravityDestination.Upright))
    }

    @Test
    fun `only gravity mode holds a closing upright Now Playing over a visual collection list`() {
        assertTrue(closesGravityUpright(gravity = true, GravityDestination.Upright))
        assertFalse(closesGravityUpright(gravity = true, GravityDestination.Sideways))
        // Gravity off, a latched destination left from an earlier session must not hold anything.
        assertFalse(closesGravityUpright(gravity = false, GravityDestination.Upright))
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_SENSOR,
            orientationAfterGravityClosing(TopLevelRoute.Library, libraryVisualCollection = true, GravityDestination.Upright, gravity = false),
        )
    }

    /**
     * What the app asks for right after a gravity mode Now Playing latched to [destination] heads closed over [tab], as
     * SonoraRoot decides it: the window was sideways exactly when the destination was, and gravity mode stays on.
     */
    private fun orientationAfterGravityClosing(
        tab: TopLevelRoute,
        libraryVisualCollection: Boolean,
        destination: GravityDestination,
        gravity: Boolean = true,
    ): Int {
        val landscape = destination == GravityDestination.Sideways
        val hold = UprightHold.startsOnClose(wasOpen = true, isOpen = false, landscape, visualCollectionList(tab, libraryVisualCollection), closesGravityUpright(gravity, destination))
        return screenOrientation(nowPlayingOpen = false, tab, libraryVisualCollection, hold, gravity, gravitySideways = false)
    }

    // Transitions: what the app asks for right after Now Playing heads closed over [tab], as SonoraRoot decides it.

    private fun orientationAfterClosing(tab: TopLevelRoute, libraryVisualCollection: Boolean, landscape: Boolean): Int {
        val hold = UprightHold.startsOnClose(wasOpen = true, isOpen = false, landscape, visualCollectionList(tab, libraryVisualCollection))
        return screenOrientation(nowPlayingOpen = false, tab, libraryVisualCollection, hold)
    }

    @Test
    fun `dismissing a sideways Now Playing over a visual collection list shows the visual collection straight away`() {
        // Songs, Albums, Artists or Playlists on the Library tab: no upright hold, the sensor keeps the phone sideways.
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR, orientationAfterClosing(TopLevelRoute.Library, libraryVisualCollection = true, landscape = true))
        assertEquals(LibraryVisualCollectionMode.Shown, libraryVisualCollectionMode(landscape = true, TopLevelRoute.Library, libraryVisualCollection = true, nowPlayingOpen = false))
    }

    @Test
    fun `Now Playing opened from the visual collection's button turns with the phone and returns to the visual collection`() {
        // Open: Now Playing follows the rotation lock and covers the visual collection.
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_USER, screenOrientation(nowPlayingOpen = true, TopLevelRoute.Library, libraryVisualCollection = true))
        assertEquals(LibraryVisualCollectionMode.Covered, libraryVisualCollectionMode(landscape = true, TopLevelRoute.Library, libraryVisualCollection = true, nowPlayingOpen = true))
        // Dismissed sideways: back to the visual collection.
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR, orientationAfterClosing(TopLevelRoute.Library, libraryVisualCollection = true, landscape = true))
    }

    @Test
    fun `closing Now Playing from the rotated albums visual collection follows the same rule`() {
        val choice = NowPlayingRotation.ALBUM_VISUAL_COLLECTION
        // The overlay goes as soon as Now Playing heads closed...
        assertFalse(showsAlbumVisualCollection(choice, landscape = true, nowPlayingOpen = false, dismissed = false))
        // ...then a visual collection list shows its own visual collection, any other screen holds upright.
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR, orientationAfterClosing(TopLevelRoute.Library, libraryVisualCollection = true, landscape = true))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT, orientationAfterClosing(TopLevelRoute.Library, libraryVisualCollection = false, landscape = true))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT, orientationAfterClosing(TopLevelRoute.Search, libraryVisualCollection = true, landscape = true))
    }

    @Test
    fun `with the rotation lock on, Now Playing closes upright and a visual collection list still follows the sensor`() {
        // The lock kept Now Playing portrait, so closing it holds nothing.
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR, orientationAfterClosing(TopLevelRoute.Library, libraryVisualCollection = true, landscape = false))
        for (tab in TopLevelRoute.entries) {
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT, orientationAfterClosing(tab, libraryVisualCollection = false, landscape = false))
        }
    }

    @Test
    fun `dismissing a sideways Now Playing over any other screen holds the app upright`() {
        // A pushed album, artist or playlist page, the Library home, Settings or Folders.
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT, orientationAfterClosing(TopLevelRoute.Library, libraryVisualCollection = false, landscape = true))
        // Other tabs, even with a visual collection list waiting in the Library.
        for (tab in TopLevelRoute.entries - TopLevelRoute.Library) {
            for (visualCollection in listOf(true, false)) {
                assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT, orientationAfterClosing(tab, visualCollection, landscape = true))
                assertEquals(LibraryVisualCollectionMode.None, libraryVisualCollectionMode(landscape = true, tab, visualCollection, nowPlayingOpen = false))
            }
        }
    }

    @Test
    fun `a recreated activity neither starts nor ends a hold on its own`() {
        // SonoraRoot starts wasOpen from the restored sheet, so the first check compares a state with itself.
        for (open in listOf(true, false)) {
            for (landscape in listOf(true, false)) {
                for (visualCollectionList in listOf(true, false)) {
                    assertFalse(UprightHold.startsOnClose(wasOpen = open, isOpen = open, landscape, visualCollectionList))
                }
            }
        }
    }
}
