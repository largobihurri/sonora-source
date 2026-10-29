package io.sonora.player.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TabStripMotionTest {

    // Four tabs 100 px wide with 10 px gaps, as laid out left to right: centres at 50, 160, 270, 380.
    private val ltr = listOf(0f..100f, 110f..210f, 220f..320f, 330f..430f)

    // The same bar in a right-to-left layout: the first tab sits at the right.
    private val rtl = listOf(330f..430f, 220f..320f, 110f..210f, 0f..100f)

    private fun pages(mask: Int): List<Int> = (0 until 32).filter { mask and (1 shl it) != 0 }

    @Test
    fun `a finger on a tab's centre puts the strip on that tab`() {
        assertEquals(0f, stripPositionAt(50f, ltr), 1e-6f)
        assertEquals(1f, stripPositionAt(160f, ltr), 1e-6f)
        assertEquals(3f, stripPositionAt(380f, ltr), 1e-6f)
    }

    @Test
    fun `between two centres the strip sits proportionally between their tabs`() {
        assertEquals(0.5f, stripPositionAt(105f, ltr), 1e-6f)
        assertEquals(2.25f, stripPositionAt(297.5f, ltr), 1e-6f)
    }

    @Test
    fun `past the outer centres the strip stops at the first and last tabs`() {
        assertEquals(0f, stripPositionAt(10f, ltr), 0f)
        assertEquals(0f, stripPositionAt(-80f, ltr), 0f)
        assertEquals(3f, stripPositionAt(425f, ltr), 0f)
        assertEquals(3f, stripPositionAt(900f, ltr), 0f)
    }

    @Test
    fun `right-to-left bars map the finger the mirrored way`() {
        assertEquals(0f, stripPositionAt(380f, rtl), 1e-6f)
        assertEquals(0.5f, stripPositionAt(325f, rtl), 1e-6f)
        assertEquals(3f, stripPositionAt(50f, rtl), 1e-6f)
        assertEquals(3f, stripPositionAt(-20f, rtl), 0f)
    }

    @Test
    fun `without measured tabs the finger maps nowhere`() {
        assertEquals(0f, stripPositionAt(120f, emptyList()), 0f)
        assertEquals(0f, stripPositionAt(120f, listOf(0f..100f)), 0f)
    }

    @Test
    fun `finger speed converts to tabs per second through the cell pitch`() {
        // 110 px between centres: 550 px/s is five tabs a second.
        assertEquals(5f, stripVelocity(550f, ltr), 1e-5f)
        assertEquals(-2f, stripVelocity(-220f, ltr), 1e-5f)
        // Right to left, moving the finger left goes to later tabs.
        assertEquals(5f, stripVelocity(-550f, rtl), 1e-5f)
        assertEquals(0f, stripVelocity(550f, listOf(0f..100f)), 0f)
    }

    @Test
    fun `a slow release settles on the nearest tab`() {
        assertEquals(1, stripSettleTarget(position = 1.3f, velocity = 0f, count = 4))
        assertEquals(2, stripSettleTarget(position = 1.6f, velocity = 0.5f, count = 4))
        assertEquals(3, stripSettleTarget(position = 3f, velocity = 0f, count = 4))
    }

    @Test
    fun `a quick release carries on to the tab the strip is heading to`() {
        assertEquals(2, stripSettleTarget(position = 1.3f, velocity = 8f, count = 4))
        assertEquals(1, stripSettleTarget(position = 1.7f, velocity = -8f, count = 4))
    }

    @Test
    fun `a release never skips past the next tab`() {
        assertEquals(2, stripSettleTarget(position = 1.3f, velocity = 80f, count = 4))
        assertEquals(1, stripSettleTarget(position = 1.7f, velocity = -80f, count = 4))
        // On a tab's centre the finger has picked that tab, however fast it moved.
        assertEquals(1, stripSettleTarget(position = 1f, velocity = 80f, count = 4))
    }

    @Test
    fun `a release never settles past the ends`() {
        assertEquals(3, stripSettleTarget(position = 2.9f, velocity = 50f, count = 4))
        assertEquals(0, stripSettleTarget(position = 0.1f, velocity = -50f, count = 4))
        assertEquals(3, stripSettleTarget(position = 3.4f, velocity = 5f, count = 4))
        assertEquals(0, stripSettleTarget(position = -0.4f, velocity = -5f, count = 4))
        assertEquals(0, stripSettleTarget(position = 1.5f, velocity = 0f, count = 0))
    }

    @Test
    fun `at rest only the shown tab is composed`() {
        assertEquals(listOf(0), pages(stripPageMask(position = 0f, heading = 0, count = 4)))
        assertEquals(listOf(3), pages(stripPageMask(position = 3f, heading = 3, count = 4)))
    }

    @Test
    fun `mid-switch the two tabs in the viewport are composed`() {
        assertEquals(listOf(1, 2), pages(stripPageMask(position = 1.4f, heading = 2, count = 4)))
        assertEquals(listOf(1, 2), pages(stripPageMask(position = 1.4f, heading = 1, count = 4)))
    }

    @Test
    fun `while moving the next tab ahead is composed before it slides in`() {
        // Library -> Export: leaving Library, Search is visible and Converter is ready.
        assertEquals(listOf(0, 1), pages(stripPageMask(position = 0f, heading = 3, count = 4)))
        assertEquals(listOf(0, 1, 2), pages(stripPageMask(position = 0.3f, heading = 3, count = 4)))
        assertEquals(listOf(2, 3), pages(stripPageMask(position = 2f, heading = 3, count = 4)))
        // Back towards Library.
        assertEquals(listOf(1, 2, 3), pages(stripPageMask(position = 2.5f, heading = 0, count = 4)))
    }

    @Test
    fun `nothing beyond the destination is composed`() {
        assertEquals(listOf(1, 2), pages(stripPageMask(position = 1.5f, heading = 2, count = 4)))
        assertEquals(listOf(2, 3), pages(stripPageMask(position = 2.5f, heading = 3, count = 4)))
    }

    @Test
    fun `positions outside the strip clamp to its ends`() {
        assertEquals(listOf(0), pages(stripPageMask(position = -0.3f, heading = 0, count = 3)))
        assertEquals(listOf(2), pages(stripPageMask(position = 2.4f, heading = 2, count = 3)))
        assertEquals(listOf(2), pages(stripPageMask(position = 2.4f, heading = 7, count = 3)))
        assertEquals(emptyList<Int>(), pages(stripPageMask(position = 0f, heading = 0, count = 0)))
    }

    @Test
    fun `the pill sits on the tab the strip shows`() {
        assertEquals(110f, stripPillStart(1f, ltr), 1e-4f)
        assertEquals(210f, stripPillEnd(1f, ltr), 1e-4f)
    }

    @Test
    fun `between tabs the pill slides with the strip`() {
        assertEquals(55f, stripPillStart(0.5f, ltr), 1e-4f)
        assertEquals(155f, stripPillEnd(0.5f, ltr), 1e-4f)
        assertEquals(302.5f, stripPillStart(2.75f, ltr), 1e-4f)
        // Right to left the pill moves towards the left edge.
        assertEquals(275f, stripPillStart(0.5f, rtl), 1e-4f)
        assertEquals(375f, stripPillEnd(0.5f, rtl), 1e-4f)
    }

    @Test
    fun `the pill never leaves the bar`() {
        assertEquals(0f, stripPillStart(-0.5f, ltr), 0f)
        assertEquals(430f, stripPillEnd(3.6f, ltr), 0f)
        assertEquals(0f, stripPillStart(1f, emptyList()), 0f)
        assertEquals(0f, stripPillEnd(1f, emptyList()), 0f)
    }

    @Test
    fun `release speed towards the tab is kept while the spring can absorb it`() {
        // Stiffness 400: the no-bounce spring absorbs up to 20 tabs/s per tab of distance.
        assertEquals(3f, stripReleaseVelocity(position = 1.5f, target = 2, velocity = 3f, stiffness = 400f), 1e-5f)
        assertEquals(-4f, stripReleaseVelocity(position = 1.5f, target = 1, velocity = -4f, stiffness = 400f), 1e-5f)
    }

    @Test
    fun `release speed that would overshoot the tab is capped`() {
        assertEquals(6f, stripReleaseVelocity(position = 1.7f, target = 2, velocity = 30f, stiffness = 400f), 1e-4f)
        assertEquals(-2f, stripReleaseVelocity(position = 0.1f, target = 0, velocity = -50f, stiffness = 400f), 1e-4f)
        // Already on the tab: nothing left to absorb.
        assertEquals(0f, stripReleaseVelocity(position = 3f, target = 3, velocity = 12f, stiffness = 400f), 0f)
    }

    @Test
    fun `release speed away from the tab is kept, the spring turns it around without overshoot`() {
        assertEquals(-2f, stripReleaseVelocity(position = 1.6f, target = 2, velocity = -2f, stiffness = 400f), 1e-5f)
    }
}
