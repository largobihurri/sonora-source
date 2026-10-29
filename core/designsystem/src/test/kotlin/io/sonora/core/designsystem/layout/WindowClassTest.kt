package io.sonora.core.designsystem.layout

import com.google.common.truth.Truth.assertThat
import io.sonora.core.designsystem.layout.WidthClass.COMPACT
import io.sonora.core.designsystem.layout.WidthClass.EXPANDED
import io.sonora.core.designsystem.layout.WidthClass.MEDIUM
import org.junit.Test

class WindowClassTest {

    @Test
    fun `the width class follows the Material breakpoints at 600 and 840 dp`() {
        assertThat(WindowClass.of(widthDp = 0, heightDp = 0).widthClass).isEqualTo(COMPACT)
        assertThat(WindowClass.of(widthDp = 599, heightDp = 1000).widthClass).isEqualTo(COMPACT)
        assertThat(WindowClass.of(widthDp = 600, heightDp = 1000).widthClass).isEqualTo(MEDIUM)
        assertThat(WindowClass.of(widthDp = 839, heightDp = 1000).widthClass).isEqualTo(MEDIUM)
        assertThat(WindowClass.of(widthDp = 840, heightDp = 1000).widthClass).isEqualTo(EXPANDED)
        assertThat(WindowClass.of(widthDp = 1280, heightDp = 800).widthClass).isEqualTo(EXPANDED)
    }

    @Test
    fun `a window is large when its smallest side reaches 600 dp`() {
        assertThat(WindowClass.of(widthDp = 599, heightDp = 1000).isLarge).isFalse()
        assertThat(WindowClass.of(widthDp = 600, heightDp = 1000).isLarge).isTrue()
        assertThat(WindowClass.of(widthDp = 1000, heightDp = 599).isLarge).isFalse()
        assertThat(WindowClass.of(widthDp = 1000, heightDp = 600).isLarge).isTrue()
    }

    @Test
    fun `landscape means wider than tall`() {
        assertThat(WindowClass.of(widthDp = 800, heightDp = 600).isLandscape).isTrue()
        assertThat(WindowClass.of(widthDp = 600, heightDp = 800).isLandscape).isFalse()
        assertThat(WindowClass.of(widthDp = 700, heightDp = 700).isLandscape).isFalse()
    }

    @Test
    fun `the realme GT 7 Pro is compact and not large upright`() {
        // 1080 x 2376 px at density 540 (3.375 px per dp) is 320 x 704 dp.
        val upright = WindowClass.of(widthDp = 320, heightDp = 704)
        assertThat(upright).isEqualTo(WindowClass(COMPACT, isLarge = false, isLandscape = false))
        assertThat(upright).isEqualTo(WindowClass.Phone)
    }

    @Test
    fun `the realme GT 7 Pro sideways is wide but never large`() {
        val sideways = WindowClass.of(widthDp = 704, heightDp = 320)
        assertThat(sideways.widthClass).isEqualTo(MEDIUM)
        assertThat(sideways.isLarge).isFalse()
        assertThat(sideways.isLandscape).isTrue()
    }

    @Test
    fun `a phone is never large, however wide it turns`() {
        // A big phone sideways reaches the expanded width class but its smallest side stays under 600 dp.
        val bigPhone = WindowClass.of(widthDp = 932, heightDp = 430)
        assertThat(bigPhone.widthClass).isEqualTo(EXPANDED)
        assertThat(bigPhone.isLarge).isFalse()
    }

    @Test
    fun `the Xiaomi Pad 6 is large in both orientations`() {
        // 2880 x 1800 px at 400 dpi (2.5 px per dp) is 1152 x 720 dp.
        val landscape = WindowClass.of(widthDp = 1152, heightDp = 720)
        assertThat(landscape).isEqualTo(WindowClass(EXPANDED, isLarge = true, isLandscape = true))
        val portrait = WindowClass.of(widthDp = 720, heightDp = 1152)
        assertThat(portrait).isEqualTo(WindowClass(MEDIUM, isLarge = true, isLandscape = false))
    }

    @Test
    fun `a tablet window resized into a narrow split follows the window, not the screen`() {
        // Half of the Pad 6 in landscape: 576 x 720 dp is no longer large.
        val split = WindowClass.of(widthDp = 576, heightDp = 720)
        assertThat(split.widthClass).isEqualTo(COMPACT)
        assertThat(split.isLarge).isFalse()
    }

    @Test
    fun `negative sizes read as an empty window`() {
        assertThat(WindowClass.of(widthDp = -1, heightDp = -5)).isEqualTo(WindowClass.Phone)
    }
}
