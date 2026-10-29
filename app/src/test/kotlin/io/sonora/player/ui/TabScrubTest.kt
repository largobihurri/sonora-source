package io.sonora.player.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TabScrubTest {

    @Test
    fun `scrubbing emits only when the finger reaches another tab`() {
        val scrub = TabScrub(startIndex = 0)
        val emitted = listOf(0, 0, 1, 1, 2, 1, 1, 0).mapNotNull(scrub::moveTo)
        assertEquals(listOf(1, 2, 1, 0), emitted)
    }

    @Test
    fun `staying on the pressed tab emits nothing and a miss is ignored`() {
        val scrub = TabScrub(startIndex = 1)
        assertNull(scrub.moveTo(1))
        assertNull(scrub.moveTo(-1))
        assertEquals(2, scrub.moveTo(2))
    }

    // Four 100 px cells: centres at 50, 150, 250 and 350.
    private val bounds = listOf(0f..100f, 100f..200f, 200f..300f, 300f..400f)

    @Test
    fun `a scrub starts where the strip is, not under the finger`() {
        // The finger crossed the slop at the right edge of the first tab's cell (its absolute position: 0.5).
        assertEquals(0f, scrubPosition(x = 100f, startX = 100f, startPosition = 0f, bounds = bounds), 1e-4f)
    }

    @Test
    fun `a scrub moves the strip in proportion to the finger's travel towards each end`() {
        // From x = 100 the first centre is 50 px away (strip 0) and the last 250 px away (strip 3).
        assertEquals(1.2f, scrubPosition(x = 200f, startX = 100f, startPosition = 0f, bounds = bounds), 1e-4f)
        assertEquals(1.4f, scrubPosition(x = 150f, startX = 100f, startPosition = 1f, bounds = bounds), 1e-4f)
        assertEquals(0.5f, scrubPosition(x = 75f, startX = 100f, startPosition = 1f, bounds = bounds), 1e-4f)
    }

    // Four tabs as on the phone: centres at 200, 426, 652 and 878 (pitch 226).
    private val phone = listOf(95f..305f, 321f..531f, 547f..757f, 773f..983f)
    private val slop = 22f

    @Test
    fun `a scrub started on the last tab reaches the first tab with the finger on its centre`() {
        val startX = 878f - slop
        assertEquals(0f, scrubPosition(x = 200f, startX = startX, startPosition = 3f, bounds = phone), 1e-4f)
        assertEquals(0f, scrubPosition(x = 150f, startX = startX, startPosition = 3f, bounds = phone), 1e-4f)
        assertEquals(0f, scrubPosition(x = 0f, startX = startX, startPosition = 3f, bounds = phone), 1e-4f)
    }

    @Test
    fun `a scrub started on the first tab reaches the last tab with the finger on its centre`() {
        val startX = 200f + slop
        assertEquals(3f, scrubPosition(x = 878f, startX = startX, startPosition = 0f, bounds = phone), 1e-4f)
        assertEquals(3f, scrubPosition(x = 950f, startX = startX, startPosition = 0f, bounds = phone), 1e-4f)
        assertEquals(3f, scrubPosition(x = 1080f, startX = startX, startPosition = 0f, bounds = phone), 1e-4f)
    }

    @Test
    fun `a scrub never jumps when it starts, from any tab`() {
        for (startPosition in listOf(0f, 1f, 1.4f, 2.5f, 3f)) {
            for (startX in listOf(222f, 300f, 540f, 700f, 856f)) {
                assertEquals(startPosition, scrubPosition(startX, startX, startPosition, phone), 1e-4f)
            }
        }
    }

    @Test
    fun `the strip position never decreases as the finger moves right`() {
        for (startPosition in listOf(0f, 1.4f, 3f)) {
            for (startX in listOf(222f, 540f, 856f)) {
                var previous = Float.NEGATIVE_INFINITY
                var x = -50f
                while (x <= 1100f) {
                    val position = scrubPosition(x, startX, startPosition, phone)
                    assertTrue("x=$x startX=$startX start=$startPosition: $position < $previous", position >= previous)
                    assertTrue(position in 0f..3f)
                    previous = position
                    x += 7f
                }
            }
        }
    }

    @Test
    fun `a scrub mirrors in a right-to-left bar`() {
        // The first tab is at the right: centres at 878, 652, 426 and 200.
        val rtl = phone.reversed()
        val startX = 878f - slop
        assertEquals(3f, scrubPosition(x = 200f, startX = startX, startPosition = 0f, bounds = rtl), 1e-4f)
        assertEquals(0f, scrubPosition(x = startX, startX = startX, startPosition = 0f, bounds = rtl), 1e-4f)
    }

    @Test
    fun `a scrub started at or past the end centre holds where it is instead of dividing by zero`() {
        // The finger crossed the slop on the last centre itself and again beyond it.
        assertEquals(2f, scrubPosition(x = 878f, startX = 878f, startPosition = 2f, bounds = phone), 1e-4f)
        assertEquals(2f, scrubPosition(x = 950f, startX = 900f, startPosition = 2f, bounds = phone), 1e-4f)
        // Going back the other way still reaches the first tab on its centre.
        assertEquals(0f, scrubPosition(x = 200f, startX = 878f, startPosition = 2f, bounds = phone), 1e-4f)
    }

    @Test
    fun `fewer than two measured tabs leave the strip where it started`() {
        assertEquals(0.4f, scrubPosition(x = 300f, startX = 100f, startPosition = 0.4f, bounds = emptyList()), 1e-4f)
        assertEquals(0f, scrubPosition(x = 300f, startX = 100f, startPosition = 0f, bounds = listOf(0f..100f)), 1e-4f)
    }
}
