package io.sonora.core.designsystem.motion

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PageEdgeFadeTest {

    private val width = 1000f

    @Test
    fun `a settled page has hard edges, so it looks as it always did`() {
        assertThat(PageEdgeFade.of(offset = 0f, width = width)).isEqualTo(PageEdgeFade.None)
    }

    @Test
    fun `a page sliding out to the left fades its right edge into the next tab`() {
        val fade = PageEdgeFade.of(offset = -0.5f, width = width)
        assertThat(fade.left).isEqualTo(0f)
        assertThat(fade.right).isWithin(0.01f).of(PageEdgeFade.MAX_FRACTION * width)
    }

    @Test
    fun `a page sliding in from the right fades its left edge into the tab it leaves`() {
        val fade = PageEdgeFade.of(offset = 0.5f, width = width)
        assertThat(fade.left).isWithin(0.01f).of(PageEdgeFade.MAX_FRACTION * width)
        assertThat(fade.right).isEqualTo(0f)
    }

    @Test
    fun `the fade grows with the slide, so the first frames do not jump`() {
        val start = PageEdgeFade.of(offset = -PageEdgeFade.RAMP / 3f, width = width)
        assertThat(start.right).isWithin(0.01f).of(PageEdgeFade.MAX_FRACTION * width / 3f)
        val ramped = PageEdgeFade.of(offset = -PageEdgeFade.RAMP, width = width)
        assertThat(ramped.right).isWithin(0.01f).of(PageEdgeFade.MAX_FRACTION * width)
        assertThat(PageEdgeFade.of(offset = -1f, width = width)).isEqualTo(ramped)
    }

    @Test
    fun `a page with no width has nothing to fade`() {
        assertThat(PageEdgeFade.of(offset = -0.5f, width = 0f)).isEqualTo(PageEdgeFade.None)
    }
}
