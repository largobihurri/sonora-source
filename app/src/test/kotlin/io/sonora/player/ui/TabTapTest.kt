package io.sonora.player.ui

import io.sonora.player.navigation.TopLevelRoute
import org.junit.Assert.assertEquals
import org.junit.Test

class TabTapTest {

    @Test
    fun `tapping the selected tab at its root scrolls its list to the top, as on iOS`() {
        for (tab in TopLevelRoute.entries) {
            assertEquals(TabTap.ScrollToTop, tabTap(selected = tab, tapped = tab, tappedDepth = 1, tappedBackEnabled = true))
        }
    }

    @Test
    fun `tapping the selected tab with screens pushed on it goes back to its root, as Back does`() {
        // E.g. Settings, or an album page, pushed on the Library: tapping Library returns to the Library itself.
        for (tab in TopLevelRoute.entries) {
            for (depth in 2..4) {
                assertEquals(TabTap.PopToRoot, tabTap(selected = tab, tapped = tab, tappedDepth = depth, tappedBackEnabled = true))
            }
        }
    }

    @Test
    fun `tapping another tab only switches to it, keeping the screens pushed on it`() {
        for (selected in TopLevelRoute.entries) {
            for (tapped in TopLevelRoute.entries - selected) {
                for (depth in 1..3) {
                    for (backEnabled in listOf(true, false)) {
                        assertEquals(TabTap.Switch, tabTap(selected, tapped, tappedDepth = depth, tappedBackEnabled = backEnabled))
                    }
                }
            }
        }
    }

    @Test
    fun `tapping the selected tab while Back is held above its stack leaves the stack as Back leaves it`() {
        // E.g. the Now Playing sheet open over the Library owns Back: neither Back nor a tab tap pops the Library.
        for (tab in TopLevelRoute.entries) {
            for (depth in 2..4) {
                assertEquals(TabTap.Held, tabTap(selected = tab, tapped = tab, tappedDepth = depth, tappedBackEnabled = false))
            }
            // At its root there is nothing to pop: the list still scrolls to the top.
            assertEquals(TabTap.ScrollToTop, tabTap(selected = tab, tapped = tab, tappedDepth = 1, tappedBackEnabled = false))
        }
    }
}
