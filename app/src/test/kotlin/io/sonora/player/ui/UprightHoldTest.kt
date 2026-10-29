package io.sonora.player.ui

import android.content.pm.ActivityInfo
import android.view.OrientationEventListener
import io.sonora.player.navigation.TopLevelRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UprightHoldTest {

    @Test
    fun `closing a sideways Now Playing over a screen without a Cover Flow holds the app upright`() {
        assertTrue(UprightHold.startsOnClose(wasOpen = true, isOpen = false, landscape = true, coverFlowList = false))
    }

    @Test
    fun `closing a sideways Now Playing over a Cover Flow list holds nothing`() {
        assertFalse(UprightHold.startsOnClose(wasOpen = true, isOpen = false, landscape = true, coverFlowList = true))
    }

    @Test
    fun `closing a gravity mode Now Playing headed upright over a Cover Flow list holds the portrait screen`() {
        assertTrue(UprightHold.startsOnClose(wasOpen = true, isOpen = false, landscape = false, coverFlowList = true, gravityUpright = true))
        // Elsewhere the portrait screen stays upright anyway, and a sideways window is the landscape flow's.
        assertFalse(UprightHold.startsOnClose(wasOpen = true, isOpen = false, landscape = false, coverFlowList = false, gravityUpright = true))
        assertFalse(UprightHold.startsOnClose(wasOpen = true, isOpen = true, landscape = false, coverFlowList = true, gravityUpright = true))
    }

    @Test
    fun `closing an upright Now Playing, or no change, holds nothing`() {
        for (coverFlowList in listOf(true, false)) {
            assertFalse(UprightHold.startsOnClose(wasOpen = true, isOpen = false, landscape = false, coverFlowList))
            assertFalse(UprightHold.startsOnClose(wasOpen = true, isOpen = true, landscape = true, coverFlowList))
            assertFalse(UprightHold.startsOnClose(wasOpen = false, isOpen = false, landscape = true, coverFlowList))
            assertFalse(UprightHold.startsOnClose(wasOpen = false, isOpen = true, landscape = true, coverFlowList))
        }
    }

    @Test
    fun `upright readings are within 30 degrees of the natural orientation`() {
        assertTrue(UprightHold.isUpright(degrees = 0))
        assertTrue(UprightHold.isUpright(degrees = 20))
        assertTrue(UprightHold.isUpright(degrees = 345))
        assertFalse(UprightHold.isUpright(degrees = 90))
        assertFalse(UprightHold.isUpright(degrees = 270))
        assertFalse(UprightHold.isUpright(degrees = 50))
        assertFalse(UprightHold.isUpright(degrees = 180))
        // Lying (nearly) flat the listener cannot tell an orientation.
        assertFalse(UprightHold.isUpright(degrees = OrientationEventListener.ORIENTATION_UNKNOWN))
    }

    @Test
    fun `the hold ends once the phone has read upright for half a second`() {
        val dwell = UprightDwell()
        assertFalse(dwell.releasesAt(degrees = 0, nowMs = 1_000))
        assertFalse(dwell.releasesAt(degrees = 10, nowMs = 1_200))
        assertFalse(dwell.releasesAt(degrees = 350, nowMs = 1_400))
        assertTrue(dwell.releasesAt(degrees = 5, nowMs = 1_500))
    }

    @Test
    fun `a single upright reading from a sideways phone keeps the hold`() {
        val dwell = UprightDwell()
        assertFalse(dwell.releasesAt(degrees = 90, nowMs = 0))
        assertFalse(dwell.releasesAt(degrees = 25, nowMs = 200)) // a jolt
        assertFalse(dwell.releasesAt(degrees = 85, nowMs = 400))
        assertFalse(dwell.releasesAt(degrees = 90, nowMs = 5_000))
    }

    @Test
    fun `a sideways, tilted, flat or unknown reading restarts the wait`() {
        for (interruption in listOf(90, 270, 60, 180, OrientationEventListener.ORIENTATION_UNKNOWN)) {
            val dwell = UprightDwell()
            assertFalse(dwell.releasesAt(degrees = 0, nowMs = 0))
            assertFalse(dwell.releasesAt(degrees = 0, nowMs = 300))
            assertFalse(dwell.releasesAt(degrees = interruption, nowMs = 400))
            assertFalse("after $interruption", dwell.releasesAt(degrees = 0, nowMs = 600))
            assertFalse("after $interruption", dwell.releasesAt(degrees = 0, nowMs = 1_000))
            assertTrue("after $interruption", dwell.releasesAt(degrees = 0, nowMs = 1_100))
        }
    }

    @Test
    fun `a phone lying flat or wobbling around upright never ends the hold`() {
        val dwell = UprightDwell()
        val readings = listOf(10, -1, 20, 340, -1, 15, -1, 0, 75, 5, -1, 355)
        readings.forEachIndexed { index, degrees ->
            assertFalse("reading $index", dwell.releasesAt(degrees, nowMs = index * 200L))
        }
    }

    @Test
    fun `while held, every screen stays upright, Cover Flow and Now Playing included`() {
        for (tab in TopLevelRoute.entries) {
            for (nowPlaying in listOf(true, false)) {
                for (coverFlow in listOf(true, false)) {
                    for (gravity in listOf(true, false)) {
                        assertEquals(
                            ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT,
                            screenOrientation(nowPlaying, tab, coverFlow, uprightHold = true, gravity = gravity),
                        )
                    }
                }
            }
        }
    }
}
