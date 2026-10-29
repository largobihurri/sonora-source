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
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_USER, screenOrientation(nowPlayingOpen = true, tab, libraryCoverFlow = false))
        }
    }

    @Test
    fun `a Library list with a Cover Flow follows the sensor, even with the rotation lock on`() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_SENSOR,
            screenOrientation(nowPlayingOpen = false, TopLevelRoute.Library, libraryCoverFlow = true),
        )
    }

    @Test
    fun `Now Playing over a Cover Flow list still honours the rotation lock`() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_FULL_USER,
            screenOrientation(nowPlayingOpen = true, TopLevelRoute.Library, libraryCoverFlow = true),
        )
    }

    @Test
    fun `every other screen stays upright`() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT,
            screenOrientation(nowPlayingOpen = false, TopLevelRoute.Library, libraryCoverFlow = false),
        )
        // The Library keeps its list underneath another tab: only the Library tab itself may turn.
        for (tab in TopLevelRoute.entries - TopLevelRoute.Library) {
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT, screenOrientation(nowPlayingOpen = false, tab, libraryCoverFlow = true))
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT, screenOrientation(nowPlayingOpen = false, tab, libraryCoverFlow = false))
        }
    }

    @Test
    fun `with gravity mode on, an open Now Playing holds the window portrait, whatever the lock or the tab`() {
        for (tab in TopLevelRoute.entries) {
            for (coverFlow in listOf(true, false)) {
                assertEquals(
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                    screenOrientation(nowPlayingOpen = true, tab, coverFlow, uprightHold = false, gravity = true),
                )
            }
        }
    }

    @Test
    fun `gravity mode leaves every screen but an open Now Playing to its own rule`() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_SENSOR,
            screenOrientation(nowPlayingOpen = false, TopLevelRoute.Library, libraryCoverFlow = true, gravity = true),
        )
        for (tab in TopLevelRoute.entries - TopLevelRoute.Library) {
            assertEquals(
                ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT,
                screenOrientation(nowPlayingOpen = false, tab, libraryCoverFlow = true, gravity = true),
            )
        }
    }

    @Test
    fun `in gravity mode a sideways destination turns the window sideways under the open Now Playing`() {
        // The sideways detent latched the Cover Flow: the window follows the phone's side, even with the lock on, so
        // the Library composes its Cover Flow beneath and a drag reveals it.
        for (tab in TopLevelRoute.entries) {
            assertEquals(
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
                screenOrientation(nowPlayingOpen = true, tab, libraryCoverFlow = true, gravity = true, gravitySideways = true),
            )
        }
        assertEquals(LibraryCoverFlowMode.Covered, libraryCoverFlowMode(landscape = true, TopLevelRoute.Library, libraryCoverFlow = true, nowPlayingOpen = true))
        // The upright hold still wins.
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT,
            screenOrientation(nowPlayingOpen = true, TopLevelRoute.Library, libraryCoverFlow = true, uprightHold = true, gravity = true, gravitySideways = true),
        )
        // Only gravity mode's open Now Playing reads it.
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_FULL_USER,
            screenOrientation(nowPlayingOpen = true, TopLevelRoute.Library, libraryCoverFlow = true, gravitySideways = true),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT,
            screenOrientation(nowPlayingOpen = false, TopLevelRoute.Search, libraryCoverFlow = false, gravity = true, gravitySideways = true),
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
                    for (coverFlow in listOf(true, false)) {
                        assertEquals(
                            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
                            screenOrientation(nowPlayingOpen, tab, coverFlow, windowClass = windowClass),
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
                screenOrientation(nowPlayingOpen = true, tab, libraryCoverFlow = true, uprightHold = true, gravity = true, gravitySideways = true, windowClass = pad6Landscape),
            )
            assertEquals(
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
                screenOrientation(nowPlayingOpen = false, tab, libraryCoverFlow = false, uprightHold = true, windowClass = pad6Portrait),
            )
        }
    }

    @Test
    fun `a phone held sideways keeps every phone rule`() {
        // Wide, but its smallest side is 320 dp: not a large screen.
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_USER, screenOrientation(nowPlayingOpen = true, TopLevelRoute.Library, libraryCoverFlow = false, windowClass = phoneSideways))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR, screenOrientation(nowPlayingOpen = false, TopLevelRoute.Library, libraryCoverFlow = true, windowClass = phoneSideways))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT, screenOrientation(nowPlayingOpen = false, TopLevelRoute.Search, libraryCoverFlow = false, windowClass = phoneSideways))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, screenOrientation(nowPlayingOpen = true, TopLevelRoute.Library, libraryCoverFlow = false, gravity = true, windowClass = phoneSideways))
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
        // Opened over a sideways Cover Flow: sideways on that very frame, no portrait request in between.
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
        // Sideways: closed in a sideways window, exactly as the landscape flow (Cover Flow list, else the upright hold).
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR, orientationAfterGravityClosing(TopLevelRoute.Library, libraryCoverFlow = true, GravityDestination.Sideways))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT, orientationAfterGravityClosing(TopLevelRoute.Library, libraryCoverFlow = false, GravityDestination.Sideways))
        // Upright (or upside down): the portrait screen, held until the phone reads upright, even over a Cover Flow list
        // with the phone tilted between detents.
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT, orientationAfterGravityClosing(TopLevelRoute.Library, libraryCoverFlow = true, GravityDestination.Upright))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT, orientationAfterGravityClosing(TopLevelRoute.Search, libraryCoverFlow = false, GravityDestination.Upright))
    }

    @Test
    fun `only gravity mode holds a closing upright Now Playing over a Cover Flow list`() {
        assertTrue(closesGravityUpright(gravity = true, GravityDestination.Upright))
        assertFalse(closesGravityUpright(gravity = true, GravityDestination.Sideways))
        // Gravity off, a latched destination left from an earlier session must not hold anything.
        assertFalse(closesGravityUpright(gravity = false, GravityDestination.Upright))
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_SENSOR,
            orientationAfterGravityClosing(TopLevelRoute.Library, libraryCoverFlow = true, GravityDestination.Upright, gravity = false),
        )
    }

    /**
     * What the app asks for right after a gravity mode Now Playing latched to [destination] heads closed over [tab], as
     * SonoraRoot decides it: the window was sideways exactly when the destination was, and gravity mode stays on.
     */
    private fun orientationAfterGravityClosing(
        tab: TopLevelRoute,
        libraryCoverFlow: Boolean,
        destination: GravityDestination,
        gravity: Boolean = true,
    ): Int {
        val landscape = destination == GravityDestination.Sideways
        val hold = UprightHold.startsOnClose(wasOpen = true, isOpen = false, landscape, coverFlowList(tab, libraryCoverFlow), closesGravityUpright(gravity, destination))
        return screenOrientation(nowPlayingOpen = false, tab, libraryCoverFlow, hold, gravity, gravitySideways = false)
    }

    // Transitions: what the app asks for right after Now Playing heads closed over [tab], as SonoraRoot decides it.

    private fun orientationAfterClosing(tab: TopLevelRoute, libraryCoverFlow: Boolean, landscape: Boolean): Int {
        val hold = UprightHold.startsOnClose(wasOpen = true, isOpen = false, landscape, coverFlowList(tab, libraryCoverFlow))
        return screenOrientation(nowPlayingOpen = false, tab, libraryCoverFlow, hold)
    }

    @Test
    fun `dismissing a sideways Now Playing over a Cover Flow list shows the Cover Flow straight away`() {
        // Songs, Albums, Artists or Playlists on the Library tab: no upright hold, the sensor keeps the phone sideways.
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR, orientationAfterClosing(TopLevelRoute.Library, libraryCoverFlow = true, landscape = true))
        assertEquals(LibraryCoverFlowMode.Shown, libraryCoverFlowMode(landscape = true, TopLevelRoute.Library, libraryCoverFlow = true, nowPlayingOpen = false))
    }

    @Test
    fun `Now Playing opened from the Cover Flow's button turns with the phone and returns to the Cover Flow`() {
        // Open: Now Playing follows the rotation lock and covers the Cover Flow.
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_USER, screenOrientation(nowPlayingOpen = true, TopLevelRoute.Library, libraryCoverFlow = true))
        assertEquals(LibraryCoverFlowMode.Covered, libraryCoverFlowMode(landscape = true, TopLevelRoute.Library, libraryCoverFlow = true, nowPlayingOpen = true))
        // Dismissed sideways: back to the Cover Flow.
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR, orientationAfterClosing(TopLevelRoute.Library, libraryCoverFlow = true, landscape = true))
    }

    @Test
    fun `closing Now Playing from the rotated albums Cover Flow follows the same rule`() {
        val choice = NowPlayingRotation.ALBUM_COVER_FLOW
        // The overlay goes as soon as Now Playing heads closed...
        assertFalse(showsAlbumCoverFlow(choice, landscape = true, nowPlayingOpen = false, dismissed = false))
        // ...then a Cover Flow list shows its own Cover Flow, any other screen holds upright.
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR, orientationAfterClosing(TopLevelRoute.Library, libraryCoverFlow = true, landscape = true))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT, orientationAfterClosing(TopLevelRoute.Library, libraryCoverFlow = false, landscape = true))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT, orientationAfterClosing(TopLevelRoute.Search, libraryCoverFlow = true, landscape = true))
    }

    @Test
    fun `with the rotation lock on, Now Playing closes upright and a Cover Flow list still follows the sensor`() {
        // The lock kept Now Playing portrait, so closing it holds nothing.
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR, orientationAfterClosing(TopLevelRoute.Library, libraryCoverFlow = true, landscape = false))
        for (tab in TopLevelRoute.entries) {
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT, orientationAfterClosing(tab, libraryCoverFlow = false, landscape = false))
        }
    }

    @Test
    fun `dismissing a sideways Now Playing over any other screen holds the app upright`() {
        // A pushed album, artist or playlist page, the Library home, Settings or Folders.
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT, orientationAfterClosing(TopLevelRoute.Library, libraryCoverFlow = false, landscape = true))
        // Other tabs, even with a Cover Flow list waiting in the Library.
        for (tab in TopLevelRoute.entries - TopLevelRoute.Library) {
            for (coverFlow in listOf(true, false)) {
                assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT, orientationAfterClosing(tab, coverFlow, landscape = true))
                assertEquals(LibraryCoverFlowMode.None, libraryCoverFlowMode(landscape = true, tab, coverFlow, nowPlayingOpen = false))
            }
        }
    }

    @Test
    fun `a recreated activity neither starts nor ends a hold on its own`() {
        // SonoraRoot starts wasOpen from the restored sheet, so the first check compares a state with itself.
        for (open in listOf(true, false)) {
            for (landscape in listOf(true, false)) {
                for (coverFlowList in listOf(true, false)) {
                    assertFalse(UprightHold.startsOnClose(wasOpen = open, isOpen = open, landscape, coverFlowList))
                }
            }
        }
    }
}
