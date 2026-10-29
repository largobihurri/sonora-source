package io.sonora.player.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Widths in dp. The bar splits into equal cells, whatever the tab count. Where the cells land (pill, hit-testing) is
 * measured by layout and covered by [TabScrubTest].
 */
class TabBarGeometryTest {

    /** [count] equal cells with the gap between them fill the bar inside its inset, as the tab row lays them out. */
    private fun assertEqualCells(geometry: TabBarGeometry, count: Int) {
        val content = count * geometry.cellWidth + (count - 1) * TabBarGeometry.GAP
        assertEquals(geometry.barWidth - 2 * TabBarGeometry.INSET, content, 0.001f)
    }

    @Test
    fun `three tabs on a phone keep the compact Apple Music width`() {
        val geometry = tabBarGeometry(tabCount = 3, availableWidth = 360f - 48f)

        assertEquals(3 * TabBarGeometry.PREFERRED_CELL + 2 * TabBarGeometry.GAP + 2 * TabBarGeometry.INSET, geometry.barWidth, 0.001f)
        assertEqualCells(geometry, 3)
        assertFalse(geometry.compactLabels)
    }

    @Test
    fun `four tabs on a narrow phone share the available width equally`() {
        val available = 360f - 48f
        val geometry = tabBarGeometry(tabCount = 4, availableWidth = available)

        assertEquals(available, geometry.barWidth, 0.001f)
        assertEqualCells(geometry, 4)
        assertTrue(geometry.cellWidth >= 64f) // room for "Converter" in the small label size
    }

    @Test
    fun `the bar never exceeds its maximum width and cells shrink labels only when tight`() {
        val wide = tabBarGeometry(tabCount = 4, availableWidth = 900f)
        assertTrue(wide.barWidth <= TabBarGeometry.MAX_WIDTH)
        assertFalse(wide.compactLabels)

        val tight = tabBarGeometry(tabCount = 5, availableWidth = 320f - 48f)
        assertEqualCells(tight, 5)
        assertTrue(tight.compactLabels)
    }

    @Test
    fun `no tabs make an empty bar`() {
        assertEquals(TabBarGeometry(0f, 0f, compactLabels = false), tabBarGeometry(tabCount = 0, availableWidth = 300f))
    }

    @Test
    fun `a narrower preferred cell makes a narrower bar and the default is unchanged`() {
        assertEquals(tabBarGeometry(4, 900f), tabBarGeometry(4, 900f, preferredCell = TabBarGeometry.PREFERRED_CELL))
        val narrow = tabBarGeometry(4, 900f, preferredCell = 76f)
        assertEquals(4 * 76f + 3 * TabBarGeometry.GAP + 2 * TabBarGeometry.INSET, narrow.barWidth, 0.001f)
        assertEqualCells(narrow, 4)
    }
}
