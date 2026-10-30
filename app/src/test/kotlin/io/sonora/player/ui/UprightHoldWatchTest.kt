package io.sonora.player.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shell's wiring of [UprightHold]: it follows Now Playing opening and closing from the sheet it started with. */
class UprightHoldWatchTest {

    @Test
    fun `swiping a sideways Now Playing away starts the hold`() {
        val watch = UprightHoldWatch(wasOpen = false)
        assertFalse(watch.onChange(isOpen = true, landscape = true, visualCollectionList = false))
        assertTrue(watch.onChange(isOpen = false, landscape = true, visualCollectionList = false))
    }

    @Test
    fun `a recreated activity with Now Playing open starts no hold on its first report`() {
        // The first run of the effect reports the restored state itself.
        val watch = UprightHoldWatch(wasOpen = true)
        assertFalse(watch.onChange(isOpen = true, landscape = true, visualCollectionList = false))
        assertTrue(watch.onChange(isOpen = false, landscape = true, visualCollectionList = false))
    }

    @Test
    fun `a recreated activity with Now Playing closed starts no hold`() {
        val watch = UprightHoldWatch(wasOpen = false)
        assertFalse(watch.onChange(isOpen = false, landscape = true, visualCollectionList = false))
    }

    @Test
    fun `closing upright or over a visual collection list starts no hold`() {
        val upright = UprightHoldWatch(wasOpen = true)
        assertFalse(upright.onChange(isOpen = false, landscape = false, visualCollectionList = false))
        val visualCollection = UprightHoldWatch(wasOpen = true)
        assertFalse(visualCollection.onChange(isOpen = false, landscape = true, visualCollectionList = true))
    }

    @Test
    fun `each close is judged on its own`() {
        val watch = UprightHoldWatch(wasOpen = false)
        watch.onChange(isOpen = true, landscape = true, visualCollectionList = false)
        assertTrue(watch.onChange(isOpen = false, landscape = true, visualCollectionList = false))
        // Closed twice in a row (no reopening in between): nothing closes the second time.
        assertFalse(watch.onChange(isOpen = false, landscape = true, visualCollectionList = false))
        watch.onChange(isOpen = true, landscape = false, visualCollectionList = false)
        assertFalse(watch.onChange(isOpen = false, landscape = false, visualCollectionList = false))
    }
}
