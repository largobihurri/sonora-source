package io.sonora.player.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** All values in dp; x from the start edge, bottom from the screen's bottom edge. */
class BottomChromeFrameTest {

    private val nav = 24f
    private val width = 400f
    private val barWidth = 280f

    private fun frame(collapse: Float, miniPlayer: Boolean = true) =
        bottomChromeFrame(collapse, screenWidth = width, navigationBar = nav, tabBarWidth = barWidth, hasMiniPlayer = miniPlayer)

    @Test
    fun `expanded, the mini player floats above the centred tab bar`() {
        val f = frame(0f)

        assertEquals(ChromeRect(x = 60f, bottom = nav + 8f, width = barWidth, height = 59f), f.tabBar)
        assertEquals(ChromeRect(x = 16f, bottom = nav + 75f, width = 368f, height = 60f), f.miniPlayer)
        assertEquals(nav + 135f, f.height, 0.001f)
    }

    @Test
    fun `the expanded mini player keeps its maximum width on wide screens`() {
        val f = bottomChromeFrame(0f, screenWidth = 900f, navigationBar = nav, tabBarWidth = barWidth, hasMiniPlayer = true)

        assertEquals(520f, f.miniPlayer!!.width, 0.001f)
        assertEquals(190f, f.miniPlayer!!.x, 0.001f)
    }

    @Test
    fun `collapsed, a compact tab pill and the mini player share one low row`() {
        val f = frame(1f)

        assertEquals(ChromeRect(x = 16f, bottom = nav + 4f, width = 52f, height = 52f), f.tabBar)
        assertEquals(ChromeRect(x = 76f, bottom = nav + 4f, width = 308f, height = 52f), f.miniPlayer)
        assertEquals(nav + 56f, f.height, 0.001f)
    }

    @Test
    fun `without a track only the tab bar is laid out`() {
        assertEquals(null, frame(0f, miniPlayer = false).miniPlayer)
        assertEquals(nav + 8f + 59f, frame(0f, miniPlayer = false).height, 0.001f) // the bar's own top
        assertEquals(nav + 56f, frame(1f, miniPlayer = false).height, 0.001f)
    }

    @Test
    fun `the morph moves every edge proportionally`() {
        val f = frame(0.5f)

        assertEquals(ChromeRect(x = 38f, bottom = nav + 6f, width = 166f, height = 55.5f), f.tabBar)
        assertEquals(nav + 95.5f, f.height, 0.001f)
    }

    @Test
    fun `a spring overshoot never takes the frame past either state`() {
        assertEquals(frame(1f), frame(1.08f))
        assertEquals(frame(0f), frame(-0.05f))
    }

    @Test
    fun `content padding follows the chrome's current height plus a gap`() {
        assertEquals(nav + 135f + ChromeGap, frame(0f).contentPadding, 0.001f)
        assertEquals(nav + 56f + ChromeGap, frame(1f).contentPadding, 0.001f)
        assertEquals(nav + 95.5f + ChromeGap, frame(0.5f).contentPadding, 0.001f)
    }
}
