package io.sonora.feature.library.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PullToCloseTest {

    private val height = 1000f
    private val fling = 1000f

    @Test
    fun `a pull starts only on the header while the list is at its top`() {
        assertThat(PullToClose.canStart(firstVisibleIndex = 0, firstVisibleOffset = 0, downY = 300f, headerBottom = 500f)).isTrue()
        assertThat(PullToClose.canStart(firstVisibleIndex = 0, firstVisibleOffset = 12, downY = 300f, headerBottom = 500f)).isFalse()
        assertThat(PullToClose.canStart(firstVisibleIndex = 1, firstVisibleOffset = 0, downY = 300f, headerBottom = 500f)).isFalse()
        assertThat(PullToClose.canStart(firstVisibleIndex = 0, firstVisibleOffset = 0, downY = 620f, headerBottom = 500f)).isFalse()
        assertThat(PullToClose.canStart(firstVisibleIndex = 0, firstVisibleOffset = 0, downY = 300f, headerBottom = null)).isFalse()
    }

    @Test
    fun `the page follows the finger down but never above where it rests`() {
        assertThat(PullToClose.dragged(offset = 20f, dy = 30f)).isEqualTo(50f)
        assertThat(PullToClose.dragged(offset = 20f, dy = -50f)).isEqualTo(0f)
    }

    @Test
    fun `released past a quarter of the height it closes, before that it springs back`() {
        assertThat(PullToClose.shouldClose(offset = 260f, velocity = 0f, height = height, flingVelocity = fling)).isTrue()
        assertThat(PullToClose.shouldClose(offset = 240f, velocity = 0f, height = height, flingVelocity = fling)).isFalse()
    }

    @Test
    fun `a downward fling closes even a short pull, an upward one keeps the page`() {
        assertThat(PullToClose.shouldClose(offset = 50f, velocity = 1500f, height = height, flingVelocity = fling)).isTrue()
        assertThat(PullToClose.shouldClose(offset = 400f, velocity = -1500f, height = height, flingVelocity = fling)).isFalse()
        assertThat(PullToClose.shouldClose(offset = 0f, velocity = 1500f, height = height, flingVelocity = fling)).isFalse()
    }

    @Test
    fun `progress is the share of the height pulled`() {
        assertThat(PullToClose.progress(offset = 250f, height = height)).isEqualTo(0.25f)
        assertThat(PullToClose.progress(offset = 1500f, height = height)).isEqualTo(1f)
        assertThat(PullToClose.progress(offset = 10f, height = 0f)).isEqualTo(0f)
    }
}
