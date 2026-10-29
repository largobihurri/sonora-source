package io.sonora.core.designsystem.component

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Pixels on the Realme (1080 × 2376) from the 2026-10-05 device check (`G5-seq1-cancel.png`). */
class OverlayAreaTest {

    private val width = 1080f
    private val height = 2376f
    private val statusBar = 131f

    /** The keyboard's top edge in that screenshot; the inset is measured from the window's bottom. */
    private val keyboardTop = 1494f

    @Test
    fun `a host filling the window loses every inset`() {
        val area = overlayArea(PxRect(0f, 0f, width, height), width, height, EdgeInsets(0f, statusBar, 0f, height - keyboardTop))
        assertThat(area).isEqualTo(PxRect(0f, statusBar, width, keyboardTop))
    }

    @Test
    fun `a host below the status bar and above the keyboard loses nothing to them`() {
        // The tag editor's host: under the sheet's header (375 px) down to the keyboard's top.
        val host = PxRect(0f, 375f, width, keyboardTop)
        val area = overlayArea(host, width, height, EdgeInsets(0f, statusBar, 0f, height - keyboardTop))
        assertThat(area).isEqualTo(PxRect(0f, 0f, width, host.height))
    }

    @Test
    fun `an inset takes from a host only as far as it reaches into it`() {
        // The host starts 50 px down, inside the status bar; it ends 200 px above the window's bottom, inside the
        // 882 px keyboard.
        val host = PxRect(0f, 50f, width, height - 200f)
        val area = overlayArea(host, width, height, EdgeInsets(0f, statusBar, 0f, 882f))
        assertThat(area).isEqualTo(PxRect(0f, statusBar - 50f, width, host.height - (882f - 200f)))
    }

    @Test
    fun `side insets take from a host reaching the window's sides`() {
        // Landscape with a 100 px cutout on the left and a 48 px navigation bar on the right.
        val area = overlayArea(PxRect(0f, 0f, height, width), height, width, EdgeInsets(100f, 0f, 48f, 0f))
        assertThat(area).isEqualTo(PxRect(100f, 0f, height - 48f, width))
    }

    @Test
    fun `a host away from the sides keeps its full width`() {
        val host = PxRect(200f, 0f, height - 200f, width)
        val area = overlayArea(host, height, width, EdgeInsets(100f, 0f, 48f, 0f))
        assertThat(area).isEqualTo(PxRect(0f, 0f, host.width, width))
    }
}
