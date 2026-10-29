package io.sonora.core.designsystem.component

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TranslucentPanelSizingTest {

    private val margin = 30f
    private val maxWidth = 1100f // 420 dp at 2.625x
    private val landscapeMaxWidth = 1470f // 560 dp at 2.625x

    private fun size(area: PxRect, height: TranslucentPanelHeight = TranslucentPanelHeight.Wrap) =
        translucentPanelSize(area, margin, maxWidth, landscapeMaxWidth, height)

    @Test
    fun `on a phone in portrait the panel takes nine tenths of the width and at most four fifths of the height`() {
        val size = size(PxRect(0f, 100f, 1080f, 2300f))
        assertThat(size.width).isEqualTo(972f)
        assertThat(size.maxHeight).isEqualTo(1760f)
    }

    @Test
    fun `on a wide portrait screen the width stops at the maximum`() {
        val size = size(PxRect(0f, 0f, 1600f, 2400f))
        assertThat(size.width).isEqualTo(maxWidth)
        assertThat(size.maxHeight).isEqualTo(1920f)
    }

    @Test
    fun `in landscape the panel can be wider and uses more of the short height`() {
        val size = size(PxRect(150f, 0f, 2250f, 1000f))
        assertThat(size.width).isEqualTo(landscapeMaxWidth)
        assertThat(size.maxHeight).isEqualTo(900f)
    }

    @Test
    fun `the panel always keeps the margin from the edges`() {
        val size = size(PxRect(0f, 0f, 400f, 300f))
        assertThat(size.width).isEqualTo(340f)
        assertThat(size.maxHeight).isEqualTo(240f)
    }

    @Test
    fun `a degenerate area gives an empty panel rather than a negative one`() {
        val size = size(PxRect(0f, 0f, 20f, 20f))
        assertThat(size.width).isEqualTo(0f)
        assertThat(size.maxHeight).isEqualTo(0f)
    }

    @Test
    fun `a wrapping panel has no minimum height`() {
        assertThat(size(PxRect(0f, 100f, 1080f, 2300f)).minHeight).isEqualTo(0f)
        assertThat(size(PxRect(150f, 0f, 2250f, 1000f)).minHeight).isEqualTo(0f)
    }

    @Test
    fun `a tall panel in portrait takes ninety four percent of the height and is never shorter`() {
        val size = size(PxRect(0f, 100f, 1080f, 2300f), TranslucentPanelHeight.Tall)
        assertThat(size.maxHeight).isWithin(0.01f).of(2200f * 0.94f)
        assertThat(size.minHeight).isEqualTo(size.maxHeight)
        assertThat(size.width).isEqualTo(972f)
    }

    @Test
    fun `a tall panel in landscape takes ninety four percent of the height and keeps the width cap`() {
        val size = size(PxRect(150f, 0f, 2250f, 1000f), TranslucentPanelHeight.Tall)
        assertThat(size.maxHeight).isWithin(0.01f).of(940f)
        assertThat(size.minHeight).isEqualTo(size.maxHeight)
        assertThat(size.width).isEqualTo(landscapeMaxWidth)
    }

    @Test
    fun `a tall panel keeps the margin from the edges on a short area`() {
        val size = size(PxRect(0f, 0f, 400f, 60f), TranslucentPanelHeight.Tall)
        assertThat(size.maxHeight).isEqualTo(0f)
        assertThat(size.minHeight).isEqualTo(0f)
        val small = size(PxRect(0f, 0f, 400f, 100f), TranslucentPanelHeight.Tall)
        assertThat(small.maxHeight).isEqualTo(40f)
        assertThat(small.minHeight).isEqualTo(40f)
    }

    @Test
    fun `an expanded panel may grow as tall as a tall one but wraps its content`() {
        val size = size(PxRect(0f, 100f, 1080f, 2300f), TranslucentPanelHeight.Expanded)
        assertThat(size.maxHeight).isWithin(0.01f).of(2200f * 0.94f)
        assertThat(size.minHeight).isEqualTo(0f)
        assertThat(size.width).isEqualTo(972f)
    }

    @Test
    fun `an expanded panel in landscape also grows to ninety four percent and keeps the margin`() {
        assertThat(size(PxRect(150f, 0f, 2250f, 1000f), TranslucentPanelHeight.Expanded).maxHeight).isWithin(0.01f).of(940f)
        assertThat(size(PxRect(0f, 0f, 400f, 100f), TranslucentPanelHeight.Expanded).maxHeight).isEqualTo(40f)
    }

    @Test
    fun `a tall panel on a degenerate area is empty rather than negative`() {
        val size = size(PxRect(0f, 0f, 20f, 20f), TranslucentPanelHeight.Tall)
        assertThat(size.width).isEqualTo(0f)
        assertThat(size.maxHeight).isEqualTo(0f)
        assertThat(size.minHeight).isEqualTo(0f)
    }

    @Test
    fun `the panel is centred in the area`() {
        val area = PxRect(0f, 100f, 1080f, 2300f)
        val (left, top) = translucentPanelOrigin(area, width = 972f, height = 800f)
        assertThat(left).isEqualTo(54f)
        assertThat(top).isEqualTo(800f)
    }

    private val screen = PxRect(0f, 100f, 1080f, 2300f)
    private val sortButton = PxRect(950f, 150f, 1060f, 260f)

    @Test
    fun `a pull-down hangs under its button, its right edge on the button's`() {
        val placed = placePullDown(sortButton, width = 600f, height = 900f, area = screen, margin = 30f, gap = 10f)
        assertThat(placed.left).isEqualTo(450f)
        assertThat(placed.top).isEqualTo(270f)
        assertThat(placed.height).isEqualTo(900f)
        assertThat(placed.below).isTrue()
    }

    @Test
    fun `a pull-down never leaves the screen sideways`() {
        val leftButton = PxRect(10f, 150f, 120f, 260f)
        assertThat(placePullDown(leftButton, 600f, 900f, screen, 30f, 10f).left).isEqualTo(30f)
        val wideButton = PxRect(900f, 150f, 1080f, 260f)
        assertThat(placePullDown(wideButton, 600f, 900f, screen, 30f, 10f).left).isEqualTo(450f)
    }

    @Test
    fun `a pull-down too tall for the room below is cut there and scrolls`() {
        val placed = placePullDown(sortButton, 600f, 3000f, screen, 30f, 10f)
        assertThat(placed.top).isEqualTo(270f)
        assertThat(placed.height).isEqualTo(2000f) // 2300 - 30 - 270
    }

    @Test
    fun `a button near the bottom opens its menu upwards when more room is there`() {
        val lowButton = PxRect(950f, 2100f, 1060f, 2210f)
        val placed = placePullDown(lowButton, 600f, 900f, screen, 30f, 10f)
        assertThat(placed.below).isFalse()
        assertThat(placed.top).isEqualTo(2090f - 900f)
        assertThat(placed.height).isEqualTo(900f)
    }

    @Test
    fun `the pull-down may use the larger of the room above and below`() {
        assertThat(pullDownMaxHeight(sortButton, screen, 30f, 10f)).isEqualTo(2000f)
    }
}
