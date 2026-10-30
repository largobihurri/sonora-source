package io.sonora.player.ui

import io.sonora.core.designsystem.component.TranslucentBarHeight
import org.junit.Assert.assertEquals
import org.junit.Test

class DuplicateNoticePlacementTest {
    @Test
    fun `with the sidebar the notice sits under the page bar, clear of its back button`() {
        assertEquals(TranslucentBarHeight.value, duplicateNoticeTop(NavigationChrome.Sidebar, chromeVisible = true), 0f)
    }

    @Test
    fun `on a phone the notice sits under the page bar too`() {
        assertEquals(TranslucentBarHeight.value, duplicateNoticeTop(NavigationChrome.BottomTabBar, chromeVisible = true), 0f)
    }

    @Test
    fun `under the top pill the notice clears the pill, which is taller than the page bar`() {
        assertEquals(TopPillHeight, duplicateNoticeTop(NavigationChrome.TopPill, chromeVisible = true), 0f)
    }

    @Test
    fun `with the top pill hidden the notice still clears the page bar`() {
        assertEquals(TranslucentBarHeight.value, duplicateNoticeTop(NavigationChrome.TopPill, chromeVisible = false), 0f)
    }
}
