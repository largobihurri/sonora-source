package io.sonora.player.ui

import io.sonora.core.domain.model.RepeatMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniPlayerGesturesTest {

    private val width = 300f
    private val fling = 1_000f

    @Test
    fun `no axis is chosen until the finger moves past the touch slop`() {
        val lock = AxisLock(slop = 10f)
        assertNull(lock.add(3f, 4f)) // 5 px
        assertNull(lock.add(2f, 1f)) // ~7 px in total
    }

    @Test
    fun `the dominant axis after the slop wins and stays locked`() {
        val horizontal = AxisLock(slop = 10f)
        assertEquals(DragAxis.Horizontal, horizontal.add(-12f, 5f))
        assertEquals(DragAxis.Horizontal, horizontal.add(0f, 80f)) // locked: later vertical motion doesn't switch

        val vertical = AxisLock(slop = 10f)
        assertNull(vertical.add(4f, -4f))
        assertEquals(DragAxis.Vertical, vertical.add(2f, -9f)) // total (6, -13)
    }

    @Test
    fun `the movement inside the slop is kept so the drag follows the finger exactly`() {
        val lock = AxisLock(slop = 10f)
        lock.add(0f, -6f)
        assertEquals(DragAxis.Vertical, lock.add(1f, -15f))
        assertEquals(1f, lock.totalX, 0f)
        assertEquals(-21f, lock.totalY, 0f)
    }

    @Test
    fun `a purely vertical flick never locks horizontal`() {
        // Emulator: `input swipe 300 2057 300 1900 80` must drive the sheet, not change track.
        val lock = AxisLock(slop = 21f)
        val axes = listOf(-40f, -40f, -40f, -37f).map { lock.add(0f, it) }
        assertEquals(listOf(DragAxis.Vertical, DragAxis.Vertical, DragAxis.Vertical, DragAxis.Vertical), axes)
    }

    @Test
    fun `a diagonal tie counts as vertical so the sheet keeps priority`() {
        assertEquals(DragAxis.Vertical, AxisLock(slop = 10f).add(10f, 10f))
    }

    @Test
    fun `the content follows the finger in an allowed direction`() {
        assertEquals(-120f, swipeOffset(-120f, width, canNext = true, canPrevious = false), 1e-4f)
        assertEquals(90f, swipeOffset(90f, width, canNext = false, canPrevious = true), 1e-4f)
        assertEquals(-width, swipeOffset(-900f, width, canNext = true, canPrevious = true), 1e-4f)
    }

    @Test
    fun `a blocked direction resists like a rubber band`() {
        val resisted = swipeOffset(-150f, width, canNext = false, canPrevious = true)
        assertTrue(resisted < 0f)
        assertTrue(-resisted < 150f)
        val further = swipeOffset(-600f, width, canNext = false, canPrevious = true)
        assertTrue(further < resisted) // still moves, but less and less
        assertTrue(-further < width)
        assertEquals(0f, rubberBand(0f, width), 0f)
    }

    private fun outcome(offset: Float, velocity: Float = 0f, canNext: Boolean = true, canPrevious: Boolean = true) =
        swipeOutcome(offset, velocity, width, fling, canNext, canPrevious)

    @Test
    fun `a swipe past the distance commits left to next and right to previous`() {
        assertEquals(SwipeOutcome.Next, outcome(-120f))
        assertEquals(SwipeOutcome.Previous, outcome(120f))
        assertEquals(SwipeOutcome.SpringBack, outcome(-60f))
        assertEquals(SwipeOutcome.SpringBack, outcome(60f))
    }

    @Test
    fun `a fling commits a short swipe but not one against the offset`() {
        assertEquals(SwipeOutcome.Next, outcome(-20f, velocity = -fling))
        assertEquals(SwipeOutcome.Previous, outcome(20f, velocity = fling))
        assertEquals(SwipeOutcome.SpringBack, outcome(-200f, velocity = fling)) // changed their mind
    }

    @Test
    fun `nothing commits without a next or previous track`() {
        assertEquals(SwipeOutcome.SpringBack, outcome(-200f, canNext = false))
        assertEquals(SwipeOutcome.SpringBack, outcome(-20f, velocity = -fling, canNext = false))
        assertEquals(SwipeOutcome.SpringBack, outcome(200f, canPrevious = false))
    }

    @Test
    fun `skips follow the queue position and repeat all wraps`() {
        assertEquals(MiniPlayerSkips(canNext = true, canPrevious = false), miniPlayerSkips(3, 0, RepeatMode.OFF))
        assertEquals(MiniPlayerSkips(canNext = true, canPrevious = true), miniPlayerSkips(3, 1, RepeatMode.OFF))
        assertEquals(MiniPlayerSkips(canNext = false, canPrevious = true), miniPlayerSkips(3, 2, RepeatMode.ONE))
        assertEquals(MiniPlayerSkips(canNext = true, canPrevious = true), miniPlayerSkips(3, 2, RepeatMode.ALL))
        assertEquals(MiniPlayerSkips(canNext = false, canPrevious = false), miniPlayerSkips(1, 0, RepeatMode.OFF))
        assertEquals(MiniPlayerSkips(canNext = false, canPrevious = false), miniPlayerSkips(0, -1, RepeatMode.ALL))
    }
}
