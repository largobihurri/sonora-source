package io.sonora.player.ui

import io.sonora.core.designsystem.layout.WindowClass
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemBarsTest {

    @Test
    fun `a phone keeps the system's navigation bar contrast scrim, upright or sideways`() {
        assertTrue(navigationContrastEnforced(WindowClass.Phone))
        assertTrue(navigationContrastEnforced(WindowClass.of(widthDp = 704, heightDp = 320)))
    }

    @Test
    fun `a large screen draws edge to edge under the navigation bar, with no scrim over a tinted page`() {
        assertFalse(navigationContrastEnforced(WindowClass.of(widthDp = 720, heightDp = 1152)))
        assertFalse(navigationContrastEnforced(WindowClass.of(widthDp = 1152, heightDp = 720)))
    }
}
