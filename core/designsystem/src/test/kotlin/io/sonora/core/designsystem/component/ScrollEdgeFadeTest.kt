package io.sonora.core.designsystem.component

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ScrollEdgeFadeTest {

    private val ramp = 60f

    @Test
    fun `an edge with nothing scrolled under it does not fade`() {
        assertThat(scrollEdgeFadeStrength(hidden = 0f, ramp = ramp)).isEqualTo(0f)
    }

    @Test
    fun `the fade grows with the content slid under the edge`() {
        assertThat(scrollEdgeFadeStrength(hidden = 15f, ramp = ramp)).isEqualTo(0.25f)
        assertThat(scrollEdgeFadeStrength(hidden = 30f, ramp = ramp)).isEqualTo(0.5f)
    }

    @Test
    fun `the fade is full once a ramp of content lies under the edge`() {
        assertThat(scrollEdgeFadeStrength(hidden = 60f, ramp = ramp)).isEqualTo(1f)
        assertThat(scrollEdgeFadeStrength(hidden = Float.POSITIVE_INFINITY, ramp = ramp)).isEqualTo(1f)
    }

    @Test
    fun `without a ramp any hidden content fades the edge fully`() {
        assertThat(scrollEdgeFadeStrength(hidden = 1f, ramp = 0f)).isEqualTo(1f)
        assertThat(scrollEdgeFadeStrength(hidden = 0f, ramp = 0f)).isEqualTo(0f)
    }

    @Test
    fun `a lazy list at its first item hides only that item's scrolled part`() {
        assertThat(lazyHiddenBefore(firstVisibleIndex = 0, firstVisibleOffset = 0)).isEqualTo(0f)
        assertThat(lazyHiddenBefore(firstVisibleIndex = 0, firstVisibleOffset = 24)).isEqualTo(24f)
    }

    @Test
    fun `a lazy list past its first item hides a whole item at least`() {
        assertThat(lazyHiddenBefore(firstVisibleIndex = 3, firstVisibleOffset = 0)).isEqualTo(Float.POSITIVE_INFINITY)
    }

    @Test
    fun `a lazy list whose last item is not shown hides content after the viewport`() {
        val hidden = lazyHiddenAfter(lastVisibleIndex = 7, itemCount = 20, lastVisibleEnd = 900, viewportEnd = 880, afterContentPadding = 0)
        assertThat(hidden).isEqualTo(Float.POSITIVE_INFINITY)
    }

    @Test
    fun `a lazy list showing its last item hides only what runs past the viewport, padding included`() {
        assertThat(lazyHiddenAfter(lastVisibleIndex = 19, itemCount = 20, lastVisibleEnd = 900, viewportEnd = 880, afterContentPadding = 10))
            .isEqualTo(30f)
        assertThat(lazyHiddenAfter(lastVisibleIndex = 19, itemCount = 20, lastVisibleEnd = 870, viewportEnd = 880, afterContentPadding = 10))
            .isEqualTo(0f)
    }

    @Test
    fun `an empty lazy list hides nothing`() {
        assertThat(lazyHiddenAfter(lastVisibleIndex = -1, itemCount = 0, lastVisibleEnd = 0, viewportEnd = 880, afterContentPadding = 0)).isEqualTo(0f)
    }
}
