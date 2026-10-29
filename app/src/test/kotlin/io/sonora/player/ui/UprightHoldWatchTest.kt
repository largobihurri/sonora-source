package io.sonora.player.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shell's wiring of [UprightHold]: it follows Now Playing opening and closing from the sheet it started with. */
class UprightHoldWatchTest {

    @Test
    fun `swiping a sideways Now Playing away starts the hold`() {
        val watch = UprightHoldWatch(wasOpen = false)
        assertFalse(watch.onChange(isOpen = true, landscape = true, coverFlowList = false))
        assertTrue(watch.onChange(isOpen = false, landscape = true, coverFlowList = false))
    }

    @Test
    fun `a recreated activity with Now Playing open starts no hold on its first report`() {
        // The first run of the effect reports the restored state itself.
        val watch = UprightHoldWatch(wasOpen = true)
        assertFalse(watch.onChange(isOpen = true, landscape = true, coverFlowList = false))
        assertTrue(watch.onChange(isOpen = false, landscape = true, coverFlowList = false))
    }

    @Test
    fun `a recreated activity with Now Playing closed starts no hold`() {
        val watch = UprightHoldWatch(wasOpen = false)
        assertFalse(watch.onChange(isOpen = false, landscape = true, coverFlowList = false))
    }

    @Test
    fun `closing upright or over a Cover Flow list starts no hold`() {
        val upright = UprightHoldWatch(wasOpen = true)
        assertFalse(upright.onChange(isOpen = false, landscape = false, coverFlowList = false))
        val coverFlow = UprightHoldWatch(wasOpen = true)
        assertFalse(coverFlow.onChange(isOpen = false, landscape = true, coverFlowList = true))
    }

    @Test
    fun `each close is judged on its own`() {
        val watch = UprightHoldWatch(wasOpen = false)
        watch.onChange(isOpen = true, landscape = true, coverFlowList = false)
        assertTrue(watch.onChange(isOpen = false, landscape = true, coverFlowList = false))
        // Closed twice in a row (no reopening in between): nothing closes the second time.
        assertFalse(watch.onChange(isOpen = false, landscape = true, coverFlowList = false))
        watch.onChange(isOpen = true, landscape = false, coverFlowList = false)
        assertFalse(watch.onChange(isOpen = false, landscape = false, coverFlowList = false))
    }
}
