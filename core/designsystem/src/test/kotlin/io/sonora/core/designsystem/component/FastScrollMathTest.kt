package io.sonora.core.designsystem.component

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FastScrollMathTest {

    // 80 items of 50 px in a 1,000 px viewport with 200 px of padding: 4,200 px of content, 3,200 px to scroll.
    private val extent = FastScrollExtent(itemCount = 80, averageItemPx = 50f, paddingPx = 200f, viewportPx = 1_000f)

    @Test
    fun `the scroll fraction is how far the first visible item is along the scrollable range`() {
        assertThat(FastScrollMath.scrollFraction(0, 0, extent)).isEqualTo(0f)
        assertThat(FastScrollMath.scrollFraction(32, 0, extent)).isWithin(1e-4f).of(0.5f)
        assertThat(FastScrollMath.scrollFraction(31, 50, extent)).isWithin(1e-4f).of(0.5f)
        assertThat(FastScrollMath.scrollFraction(64, 0, extent)).isWithin(1e-4f).of(1f)
    }

    @Test
    fun `the scroll fraction is clamped, and zero when nothing scrolls or the list is empty`() {
        assertThat(FastScrollMath.scrollFraction(99, 40, extent)).isEqualTo(1f)
        assertThat(FastScrollMath.scrollFraction(0, -10, extent)).isEqualTo(0f)
        val fits = extent.copy(itemCount = 10) // 700 px of content in a 1,000 px viewport
        assertThat(FastScrollMath.scrollFraction(3, 0, fits)).isEqualTo(0f)
        assertThat(FastScrollMath.scrollFraction(0, 0, extent.copy(itemCount = 0))).isEqualTo(0f)
        assertThat(FastScrollMath.scrollFraction(5, 0, extent.copy(averageItemPx = 0f))).isEqualTo(0f)
    }

    @Test
    fun `a fraction maps to the item and the offset into it`() {
        assertThat(FastScrollMath.scrollTarget(0f, extent)).isEqualTo(FastScrollTarget(0, 0))
        assertThat(FastScrollMath.scrollTarget(0.5f, extent)).isEqualTo(FastScrollTarget(32, 0))
        assertThat(FastScrollMath.scrollTarget(0.51f, extent)).isEqualTo(FastScrollTarget(32, 32)) // 1,632 px
        // The very end is the last item pushed a viewport and a row past its top, so the list clamps on its true end.
        assertThat(FastScrollMath.scrollTarget(1f, extent)).isEqualTo(FastScrollTarget(79, 1_000 + 50))
    }

    @Test
    fun `a fraction and its target agree both ways`() {
        for (fraction in listOf(0.1f, 0.33f, 0.77f, 0.999f)) {
            val target = FastScrollMath.scrollTarget(fraction, extent)
            assertThat(FastScrollMath.scrollFraction(target.index, target.offset, extent)).isWithin(1f / 3_200f).of(fraction)
        }
    }

    @Test
    fun `targets are clamped to the list, and the top for an empty or fitting list`() {
        assertThat(FastScrollMath.scrollTarget(2f, extent)).isEqualTo(FastScrollTarget(79, 1_050))
        assertThat(FastScrollMath.scrollTarget(-1f, extent)).isEqualTo(FastScrollTarget(0, 0))
        assertThat(FastScrollMath.scrollTarget(0.5f, extent.copy(itemCount = 0))).isEqualTo(FastScrollTarget(0, 0))
        assertThat(FastScrollMath.scrollTarget(0.5f, extent.copy(itemCount = 10))).isEqualTo(FastScrollTarget(0, 0))
        // A huge item past the end still lands on the last item.
        assertThat(FastScrollMath.scrollTarget(1f, FastScrollExtent(3, 10_000f, 0f, 1_000f)).index).isEqualTo(2)
    }

    // A two-column grid like the album grid: a full-span header of 300 px (with its gap), then 100 tiles in 50 rows of
    // 200 px (averageItemPx is per item, so 100 px): 10,300 px of content in a 1,000 px viewport, 9,300 px to scroll.
    private val grid = FastScrollExtent(
        itemCount = 101, averageItemPx = 100f, paddingPx = 0f, viewportPx = 1_000f, lanes = 2, leadingItems = 1, leadingPx = 300f,
    )
    private val rowPx = 200f

    // Where the top of the target's row sits: the header, then whole rows, plus the offset into it.
    private fun gridPosition(target: FastScrollTarget) =
        if (target.index < grid.leadingItems) target.offset.toFloat()
        else grid.leadingPx + (target.index - grid.leadingItems) / grid.lanes * rowPx + target.offset

    @Test
    fun `a grid target is the header, then the first lane of a row with the offset running the whole row`() {
        assertThat(FastScrollMath.scrollTarget(150f / 9_300f, grid)).isEqualTo(FastScrollTarget(0, 150)) // inside the header
        assertThat(FastScrollMath.scrollTarget(3_720f / 9_300f, grid)).isEqualTo(FastScrollTarget(35, 20)) // row 17 + 20
        assertThat(FastScrollMath.scrollTarget(4_650f / 9_300f, grid)).isEqualTo(FastScrollTarget(43, 150)) // row 21 + 150
        assertThat(FastScrollMath.scrollTarget(4_710f / 9_300f, grid)).isEqualTo(FastScrollTarget(45, 10)) // row 22 + 10
    }

    @Test
    fun `dragging a grid in small steps never moves it back`() {
        var previous = 0f
        var fraction = 0f
        while (fraction < 0.9999f) {
            val target = FastScrollMath.scrollTarget(fraction, grid)
            if (target.index >= grid.leadingItems) assertThat((target.index - grid.leadingItems) % grid.lanes).isEqualTo(0)
            val position = gridPosition(target)
            // Forward only, and in steps as small as the finger's: no sawtooth inside a row, no jump between rows.
            assertThat(position).isAtLeast(previous)
            assertThat(position - previous).isAtMost(3f)
            previous = position
            fraction += 0.00005f
        }
    }

    @Test
    fun `a grid with a header and bottom padding ends exactly on its last row and starts at its top`() {
        assertThat(FastScrollMath.scrollTarget(0f, grid)).isEqualTo(FastScrollTarget(0, 0))
        val end = FastScrollMath.scrollTarget(1f, grid)
        assertThat(end.index).isEqualTo(99) // the first tile of the last row: header, then 49 rows of two
        assertThat(end.offset).isAtLeast(1_000 + rowPx.toInt()) // past the row's end, so Compose clamps on the true end
        assertThat(FastScrollMath.scrollTarget(2f, grid)).isEqualTo(end)
        // A last row with one tile is still a whole row: 100 tiles then 101 (an odd count) is 51 rows.
        val odd = grid.copy(itemCount = 102)
        assertThat(odd.rangePx).isEqualTo(9_500f)
        assertThat(FastScrollMath.scrollTarget(1f, odd).index).isEqualTo(101)
        // Bottom padding is part of the range, and the viewport is not.
        assertThat(grid.copy(paddingPx = 170f).rangePx).isEqualTo(9_470f)
        // Never past the last item, even when a row is taller than the whole list.
        val huge = FastScrollExtent(itemCount = 3, averageItemPx = 10_000f, paddingPx = 0f, viewportPx = 1_000f, lanes = 2)
        assertThat(FastScrollMath.scrollTarget(1f, huge).index).isEqualTo(2)
    }

    @Test
    fun `a grid's scroll fraction counts the header then whole rows, and agrees with its target`() {
        assertThat(FastScrollMath.scrollFraction(0, 100, grid)).isWithin(1e-4f).of(100f / 9_300f)
        assertThat(FastScrollMath.scrollFraction(1, 0, grid)).isWithin(1e-4f).of(300f / 9_300f)
        assertThat(FastScrollMath.scrollFraction(3, 0, grid)).isWithin(1e-4f).of(500f / 9_300f)
        assertThat(FastScrollMath.scrollFraction(3, 50, grid)).isWithin(1e-4f).of(550f / 9_300f)
        // The second tile of a row is the same row as the first.
        assertThat(FastScrollMath.scrollFraction(2, 0, grid)).isWithin(1e-4f).of(300f / 9_300f)
        for (fraction in listOf(0.01f, 0.1f, 0.33f, 0.77f, 0.999f)) {
            val target = FastScrollMath.scrollTarget(fraction, grid)
            assertThat(FastScrollMath.scrollFraction(target.index, target.offset, grid)).isWithin(1f / 9_300f).of(fraction)
        }
    }

    // The album grid at the top of the list: the header (300 px with its gap to the first row) and two rows of tiles.
    private val topOfGrid = listOf(
        GridItemBounds(0, 0, 284, fullSpan = true),
        GridItemBounds(1, 300, 184, fullSpan = false), GridItemBounds(2, 300, 184, fullSpan = false),
        GridItemBounds(3, 500, 184, fullSpan = false), GridItemBounds(4, 500, 184, fullSpan = false),
        GridItemBounds(5, 700, 184, fullSpan = false), GridItemBounds(6, 700, 184, fullSpan = false),
    )

    @Test
    fun `a grid's extent reads the header apart from the tile rows`() {
        val measured = FastScrollMath.gridExtent(itemCount = 101, lanes = 2, items = topOfGrid, paddingPx = 170f, viewportPx = 1_000f)
        assertThat(measured.leadingItems).isEqualTo(1)
        assertThat(measured.leadingPx).isEqualTo(300f) // the header's top to the first tile row's top
        assertThat(measured.averageItemPx).isEqualTo(100f) // a 200 px row pitch over two lanes
        assertThat(measured.lanes).isEqualTo(2)
        assertThat(measured.rangePx).isEqualTo(300f + 50 * 200f + 170f - 1_000f)
    }

    @Test
    fun `a grid scrolled past its header measures its rows alone`() {
        val scrolled = topOfGrid.drop(1).map { it.copy(top = it.top - 600) }
        val measured = FastScrollMath.gridExtent(itemCount = 101, lanes = 2, items = scrolled, paddingPx = 170f, viewportPx = 1_000f)
        assertThat(measured.leadingItems).isEqualTo(0)
        assertThat(measured.leadingPx).isEqualTo(0f)
        assertThat(measured.averageItemPx).isEqualTo(100f)
    }

    @Test
    fun `a grid with a single visible row falls back to that row's height`() {
        val one = listOf(GridItemBounds(1, 0, 184, false), GridItemBounds(2, 0, 184, false))
        assertThat(FastScrollMath.gridExtent(101, 2, one, 0f, 1_000f).averageItemPx).isEqualTo(92f)
        assertThat(FastScrollMath.gridExtent(101, 2, emptyList(), 0f, 1_000f).averageItemPx).isEqualTo(0f)
    }

    @Test
    fun `one lane is a plain list`() {
        val list = extent.copy(lanes = 1)
        assertThat(list).isEqualTo(extent)
        assertThat(FastScrollMath.scrollTarget(0.51f, list)).isEqualTo(FastScrollTarget(32, 32))
        assertThat(FastScrollMath.scrollFraction(31, 50, list)).isWithin(1e-4f).of(0.5f)
    }

    @Test
    fun `the thumb travels the track minus its own height, and back`() {
        assertThat(FastScrollMath.thumbOffset(0f, trackPx = 1_000f, thumbPx = 200f)).isEqualTo(0f)
        assertThat(FastScrollMath.thumbOffset(0.5f, trackPx = 1_000f, thumbPx = 200f)).isEqualTo(400f)
        assertThat(FastScrollMath.thumbOffset(1f, trackPx = 1_000f, thumbPx = 200f)).isEqualTo(800f)
        assertThat(FastScrollMath.fractionAtThumbOffset(400f, trackPx = 1_000f, thumbPx = 200f)).isEqualTo(0.5f)
        assertThat(FastScrollMath.fractionAtThumbOffset(800f, trackPx = 1_000f, thumbPx = 200f)).isEqualTo(1f)
    }

    @Test
    fun `thumb positions are clamped, and a track no taller than the thumb stays at the top`() {
        assertThat(FastScrollMath.thumbOffset(1.5f, trackPx = 1_000f, thumbPx = 200f)).isEqualTo(800f)
        assertThat(FastScrollMath.thumbOffset(-1f, trackPx = 1_000f, thumbPx = 200f)).isEqualTo(0f)
        assertThat(FastScrollMath.fractionAtThumbOffset(-50f, trackPx = 1_000f, thumbPx = 200f)).isEqualTo(0f)
        assertThat(FastScrollMath.fractionAtThumbOffset(5_000f, trackPx = 1_000f, thumbPx = 200f)).isEqualTo(1f)
        assertThat(FastScrollMath.thumbOffset(0.5f, trackPx = 150f, thumbPx = 200f)).isEqualTo(0f)
        assertThat(FastScrollMath.fractionAtThumbOffset(10f, trackPx = 200f, thumbPx = 200f)).isEqualTo(0f)
    }

    @Test
    fun `with the extent measured once, nearby fractions land on nearby positions`() {
        // Re-measuring the extent in a region of taller rows sends the same fraction somewhere else entirely;
        // a drag keeps the extent it measured when the thumb was grabbed, so small finger moves stay small.
        val a = FastScrollMath.scrollTarget(0.5f, extent)
        val b = FastScrollMath.scrollTarget(0.5005f, extent)
        val px = { t: FastScrollTarget -> t.index * extent.averageItemPx + t.offset }
        assertThat(px(b) - px(a)).isWithin(1f).of(1.6f)
    }

    @Test
    fun `a gap wider than the viewport jumps and a gap within it scrolls`() {
        assertThat(FastScrollMath.shouldJump(2_500f, 2_000f)).isTrue()
        assertThat(FastScrollMath.shouldJump(-2_500f, 2_000f)).isTrue()
        assertThat(FastScrollMath.shouldJump(1_900f, 2_000f)).isFalse()
        assertThat(FastScrollMath.shouldJump(-2_000f, 2_000f)).isFalse()
        assertThat(FastScrollMath.shouldJump(0f, 2_000f)).isFalse()
        // Without a measured viewport there is nothing to scroll across: any gap jumps, no gap does not.
        assertThat(FastScrollMath.shouldJump(5f, 0f)).isTrue()
        assertThat(FastScrollMath.shouldJump(0f, 0f)).isFalse()
    }

    @Test
    fun `a pixel position maps like its fraction, and the ends are the true ends`() {
        assertThat(FastScrollMath.targetAtPx(1_632f, extent)).isEqualTo(FastScrollTarget(32, 32))
        assertThat(FastScrollMath.targetAtPx(-50f, extent)).isEqualTo(FastScrollTarget(0, 0))
        assertThat(FastScrollMath.targetAtPx(3_200f, extent)).isEqualTo(FastScrollMath.scrollTarget(1f, extent))
        assertThat(FastScrollMath.targetAtPx(9_999f, extent)).isEqualTo(FastScrollMath.scrollTarget(1f, extent))
    }

    // 10 viewports of range: the last viewport is the end zone.
    private val tall = FastScrollExtent(itemCount = 1_000, averageItemPx = 100f, paddingPx = 0f, viewportPx = 1_000f)

    @Test
    fun `a glide goal is the plain position away from the ends and runs past them at the ends`() {
        assertThat(tall.rangePx).isEqualTo(99_000f)
        assertThat(FastScrollMath.glideGoalPx(0.5f, tall, 0.5f)).isEqualTo(49_500f)
        // The very ends reach a viewport beyond the estimate, so a list whose real length differs still gets to its end.
        assertThat(FastScrollMath.glideGoalPx(1f, tall, 0.5f)).isEqualTo(99_000f + 1_000f)
        assertThat(FastScrollMath.glideGoalPx(0f, tall, 0.5f)).isEqualTo(-1_000f)
        // Just inside the last viewport of the estimate the goal already leans past it.
        assertThat(FastScrollMath.glideGoalPx(98_500f / 99_000f, tall, 0.5f)).isGreaterThan(98_500f)
        assertThat(FastScrollMath.glideGoalPx(500f / 99_000f, tall, 0.5f)).isLessThan(500f)
    }

    @Test
    fun `the glide goal never runs backwards as the fraction grows, and has no jumps`() {
        var previous = FastScrollMath.glideGoalPx(0f, tall, 0.5f)
        var fraction = 0f
        while (fraction <= 1f) {
            val goal = FastScrollMath.glideGoalPx(fraction, tall, 0.5f)
            assertThat(goal).isAtLeast(previous)
            assertThat(goal - previous).isAtMost(2f * 99_000f * 0.0001f + 1f) // at most twice the plain step: the zone doubles it
            previous = goal
            fraction += 0.0001f
        }
    }

    @Test
    fun `a list shorter than two viewports still has ends that reach past the estimate`() {
        val short = FastScrollExtent(itemCount = 20, averageItemPx = 100f, paddingPx = 0f, viewportPx = 1_000f) // 2,000 px: 1,000 to scroll
        assertThat(FastScrollMath.glideGoalPx(1f, short, 0.5f)).isEqualTo(2_000f) // 1,000 of range plus the 1,000 viewport of slack
        assertThat(FastScrollMath.glideGoalPx(0f, short, 0.5f)).isEqualTo(-1_000f)
        assertThat(FastScrollMath.glideGoalPx(0.5f, short, 0.5f)).isEqualTo(500f)
        assertThat(FastScrollMath.glideGoalPx(0.5f, short.copy(itemCount = 5), 0.5f)).isEqualTo(0f) // nothing scrolls
    }

    @Test
    fun `a drag that starts at an end moves the list with the first finger pixels, like one from mid-list`() {
        // QA round 4: from the very top, Albumes needed 54 px (444 ms) of finger before the list moved, Canciones 22
        // px; mid-list 11-13 px. The start of the range was stretched toward a viewport before the top, so a drag
        // leaving the top first had to climb back from -1,000 px to 0.
        val step = 1f / 99_000f // one px of list
        assertThat(FastScrollMath.glideGoalPx(0f, tall, 0f)).isEqualTo(0f)
        assertThat(FastScrollMath.glideGoalPx(300 * step, tall, 0f)).isWithin(0.5f).of(300f)
        assertThat(FastScrollMath.glideGoalPx(1f, tall, 1f)).isEqualTo(99_000f)
        assertThat(FastScrollMath.glideGoalPx(1f - 300 * step, tall, 1f)).isWithin(0.5f).of(99_000f - 300f)
        // Still aimed past the far end, so the list reaches its real end whatever the estimate was.
        assertThat(FastScrollMath.glideGoalPx(1f, tall, 0f)).isEqualTo(99_000f + 1_000f)
        assertThat(FastScrollMath.glideGoalPx(0f, tall, 1f)).isEqualTo(-1_000f)
    }

    @Test
    fun `the glide goal at the grab is where the list is, wherever the grab is`() {
        for (start in listOf(0f, 200f / 99_000f, 0.3f, 0.5f, 1f - 200f / 99_000f, 1f)) {
            assertThat(FastScrollMath.glideGoalPx(start, tall, start)).isWithin(0.5f).of(start * 99_000f)
            // And it stays monotone and continuous around it.
            var previous = FastScrollMath.glideGoalPx(0f, tall, start)
            for (i in 1..2_000) {
                val goal = FastScrollMath.glideGoalPx(i / 2_000f, tall, start)
                assertThat(goal).isAtLeast(previous)
                // No jumps: at most the stretch's slope (a viewport of slack over the 200 px left to an end, 6x).
                assertThat(goal - previous).isAtMost(6 * 99_000f / 2_000f + 0.5f)
                previous = goal
            }
        }
    }

    @Test
    fun `a held thumb's fraction moves with the finger and reaches both ends of the track`() {
        val travel = 1_000f
        // Grabbed halfway with the thumb halfway, 100 px of finger is a tenth of the track either way.
        assertThat(FastScrollMath.dragFraction(0.5f, 500f, 600f, travel)).isWithin(1e-6f).of(0.6f)
        assertThat(FastScrollMath.dragFraction(0.5f, 500f, 400f, travel)).isWithin(1e-6f).of(0.4f)
        assertThat(FastScrollMath.dragFraction(0.5f, 500f, 500f, travel)).isEqualTo(0.5f) // no jump at the grab
        // The finger at or past an end of the track puts the list at that end.
        assertThat(FastScrollMath.dragFraction(0.3f, 300f, 1_000f, travel)).isEqualTo(1f)
        assertThat(FastScrollMath.dragFraction(0.3f, 300f, 5_000f, travel)).isEqualTo(1f)
        assertThat(FastScrollMath.dragFraction(0.3f, 300f, 0f, travel)).isEqualTo(0f)
        assertThat(FastScrollMath.dragFraction(0.3f, 300f, -5_000f, travel)).isEqualTo(0f)
        // Moving beyond an end of the track (the finger past it) changes nothing.
        assertThat(FastScrollMath.dragFraction(0.7f, 1_200f, 1_300f, travel)).isEqualTo(0.7f)
        assertThat(FastScrollMath.dragFraction(0.3f, 100f, 0f, 0f)).isEqualTo(0.3f) // no travel: a track no taller than the thumb
    }

    @Test
    fun `when the track grows under a held finger the list does not move, and the thumb still reaches the new end with it`() {
        // The bottom chrome minimising mid-drag lengthens the travel from 1,000 to 1,250 px under a thumb at 400 px.
        assertThat(FastScrollMath.dragFraction(0.4f, 400f, 400f, 1_250f)).isEqualTo(0.4f)
        // The rest of the way, 850 px of finger on the longer track, shares out the remaining 0.6.
        var fraction = 0.4f
        var top = 400f
        while (top < 1_250f) {
            fraction = FastScrollMath.dragFraction(fraction, top, top + 10f, 1_250f)
            top += 10f
            assertThat(fraction).isAtMost(1f)
        }
        assertThat(fraction).isEqualTo(1f)
        // Halfway there it is halfway through what was left.
        assertThat(FastScrollMath.dragFraction(0.4f, 400f, 825f, 1_250f)).isWithin(1e-6f).of(0.7f)
    }
}
