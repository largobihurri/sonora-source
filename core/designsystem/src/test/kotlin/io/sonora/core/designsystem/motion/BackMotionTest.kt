package io.sonora.core.designsystem.motion

import androidx.activity.BackEventCompat
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BackMotionTest {

    @Test
    fun `swipe edge maps from the back event constants`() {
        assertThat(swipeEdgeOf(BackEventCompat.EDGE_LEFT)).isEqualTo(SwipeEdge.Left)
        assertThat(swipeEdgeOf(BackEventCompat.EDGE_RIGHT)).isEqualTo(SwipeEdge.Right)
        assertThat(swipeEdgeOf(BackEventCompat.EDGE_NONE)).isEqualTo(SwipeEdge.None)
        assertThat(swipeEdgeOf(42)).isEqualTo(SwipeEdge.None)
    }

    @Test
    fun `at rest the top screen covers a parallaxed and dimmed previous screen`() {
        val frame = stackFrame(progress = 0f, edge = SwipeEdge.Left)
        assertThat(frame.topOffset).isEqualTo(0f)
        assertThat(frame.underOffset).isEqualTo(-STACK_PARALLAX)
        assertThat(frame.scrimAlpha).isEqualTo(STACK_MAX_SCRIM)
    }

    @Test
    fun `a swipe from the left edge slides the top screen right`() {
        val frame = stackFrame(progress = 0.5f, edge = SwipeEdge.Left)
        assertThat(frame.topOffset).isEqualTo(0.5f)
        assertThat(frame.underOffset).isWithin(1e-6f).of(-STACK_PARALLAX * 0.5f)
        assertThat(frame.scrimAlpha).isWithin(1e-6f).of(STACK_MAX_SCRIM * 0.5f)
    }

    @Test
    fun `a swipe from the right edge slides the top screen left and mirrors the parallax`() {
        val frame = stackFrame(progress = 0.25f, edge = SwipeEdge.Right)
        assertThat(frame.topOffset).isEqualTo(-0.25f)
        assertThat(frame.underOffset).isWithin(1e-6f).of(STACK_PARALLAX * 0.75f)
    }

    @Test
    fun `button back and push use the left edge motion`() {
        assertThat(stackFrame(0.4f, SwipeEdge.None)).isEqualTo(stackFrame(0.4f, SwipeEdge.Left))
    }

    @Test
    fun `fully dismissed top screen is off screen and reveals the previous one undimmed`() {
        val frame = stackFrame(progress = 1f, edge = SwipeEdge.Left)
        assertThat(frame.topOffset).isEqualTo(1f)
        assertThat(frame.underOffset).isEqualTo(0f)
        assertThat(frame.scrimAlpha).isEqualTo(0f)
    }

    @Test
    fun `a pull from the top moves the page straight down over the previous one, which stays in place`() {
        val frame = stackFrame(progress = 0.4f, edge = SwipeEdge.Top)
        assertThat(frame.vertical).isTrue()
        assertThat(frame.topOffset).isEqualTo(0.4f)
        assertThat(frame.underOffset).isEqualTo(0f)
        assertThat(frame.scrimAlpha).isWithin(1e-6f).of(STACK_MAX_SCRIM * 0.6f)
        assertThat(stackFrame(0.4f, SwipeEdge.Left).vertical).isFalse()
    }

    @Test
    fun `progress outside the unit range is clamped`() {
        assertThat(stackFrame(1.7f, SwipeEdge.Left)).isEqualTo(stackFrame(1f, SwipeEdge.Left))
        assertThat(stackFrame(-0.3f, SwipeEdge.Right)).isEqualTo(stackFrame(0f, SwipeEdge.Right))
    }

    @Test
    fun `the sheet shrinks and moves down with the back gesture`() {
        assertThat(sheetFrame(0f)).isEqualTo(SheetFrame(scale = 1f, offsetFraction = 0f, cornerFraction = 0f))
        val half = sheetFrame(0.5f)
        assertThat(half.scale).isWithin(1e-6f).of(1f - SHEET_MAX_SHRINK / 2)
        assertThat(half.offsetFraction).isWithin(1e-6f).of(SHEET_MAX_OFFSET / 2)
        assertThat(half.cornerFraction).isWithin(1e-6f).of(0.5f)
        assertThat(sheetFrame(3f)).isEqualTo(sheetFrame(1f))
    }

    @Test
    fun `a committed gesture finishes in the remaining share of the full duration`() {
        assertThat(remainingDurationMillis(progress = 0f)).isEqualTo(STACK_DURATION_MILLIS)
        assertThat(remainingDurationMillis(progress = 0.5f)).isEqualTo(STACK_DURATION_MILLIS / 2)
        assertThat(remainingDurationMillis(progress = 1f)).isEqualTo(0)
        assertThat(remainingDurationMillis(progress = 2f)).isEqualTo(0)
    }

    @Test
    fun `only a zero animator duration scale counts as reduced motion`() {
        assertThat(isReducedMotion(0f)).isTrue()
        assertThat(isReducedMotion(0.5f)).isFalse()
        assertThat(isReducedMotion(1f)).isFalse()
    }
}
