package io.sonora.core.designsystem.component

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlin.math.abs
import org.junit.Test

class ThumbDragTest {

    // The QA phone: density 3.5, an 80 dp thumb box, the bottom chrome (mini player and tab bar) 80 dp taller expanded
    // than minimised, a 54,000 px list.
    private val density = 3.5f
    private val baseTravel = 1_800f
    private val chromePx = 80f * density
    private val range = 54_000f
    private val frame = 16_666_667L

    /**
     * The app's bottom chrome as the thumb sees it (`ChromeCollapse` and `BottomChromeState`): it minimises after 24 dp
     * of list travel toward the end and expands after 12 dp toward the start, each count forgotten on a change, and the
     * track's bottom follows it over ~300 ms. Minimised, the track is [chromePx] longer.
     */
    private inner class Chrome {
        var collapsed = false
        var progress = 0f
        private var towardEnd = 0f
        private var towardStart = 0f

        val travel get() = baseTravel + chromePx * progress

        fun onFastScroll(deltaPx: Float) {
            if (deltaPx > 0f) {
                towardEnd += deltaPx
                towardStart = (towardStart - deltaPx).coerceAtLeast(0f)
            } else if (deltaPx < 0f) {
                towardStart -= deltaPx
                towardEnd = (towardEnd + deltaPx).coerceAtLeast(0f)
            }
            if (!collapsed && towardEnd >= 24f * density) change(true)
            if (collapsed && towardStart >= 12f * density) change(false)
        }

        private fun change(collapse: Boolean) {
            collapsed = collapse
            towardEnd = 0f
            towardStart = 0f
        }

        fun advance(nanos: Long) {
            val step = nanos / 300_000_000f
            progress = if (collapsed) (progress + step).coerceAtMost(1f) else (progress - step).coerceAtLeast(0f)
        }
    }

    /** Where the finger, the thumb and the list were on one frame of a drag. */
    private data class Sample(val fingerTop: Float, val thumbTop: Float, val travel: Float, val fraction: Float)

    /**
     * Drags of [distances] (px of finger, positive toward the end) at 400 px/s, 60 frames a second, from [startFraction],
     * with 5 s at rest between them (the chrome settles). Each drag starts where the idle thumb is. Returns every frame.
     */
    private fun drags(startFraction: Float, distances: List<Float>, chrome: Chrome = Chrome()): List<List<Sample>> {
        var fraction = startFraction
        return distances.map { distance ->
            chrome.advance(5_000_000_000L)
            val startTop = fraction * chrome.travel
            val drag = ThumbDrag(fraction, startTop)
            val step = 400f * frame / 1e9f * if (distance > 0f) 1f else -1f
            val frames = (abs(distance) / abs(step)).toInt()
            List(frames) { i ->
                val delta = step * (i + 1)
                val before = drag.fraction
                drag.move(delta, chrome.travel)
                chrome.onFastScroll((drag.fraction - before) * range)
                chrome.advance(frame)
                Sample(startTop + delta, drag.thumbTop(chrome.travel), chrome.travel, drag.fraction)
            }.also { fraction = it.last().fraction }
        }
    }

    @Test
    fun `the thumb stays under the finger when a drag turns the other way from the last one`() {
        // QA round 4, Albumes: D D U U D U D D with 5 s between drags left the pill +106, 0, -174, 0, +92, -126, +86, 0
        // px off the finger: every drag against the previous one collapses or expands the chrome under the finger.
        val sequence = listOf(300f, 300f, -300f, -300f, 300f, -300f, 300f, 300f)
        for (start in listOf(0.3f, 0.5f)) {
            for ((i, drag) in drags(start, sequence).withIndex()) {
                for (sample in drag) {
                    val under = sample.fingerTop.coerceIn(0f, sample.travel)
                    assertWithMessage("drag $i from $start").that(abs(sample.thumbTop - under)).isAtMost(2f)
                }
            }
        }
    }

    @Test
    fun `a drag moves the list only with the finger, in its direction, without a jump when the track changes`() {
        val sequence = listOf(300f, -300f, 300f, -300f)
        for (drag in drags(0.5f, sequence)) {
            for ((a, b) in drag.zipWithNext()) {
                val finger = b.fingerTop - a.fingerTop
                val list = (b.fraction - a.fraction) * range
                assertThat(list * finger).isAtLeast(0f) // same direction, or still
                // About range / travel (30 px of list per finger pixel), give or take what the track change takes up.
                assertThat(abs(list)).isAtMost(abs(finger) * range / baseTravel * 1.5f)
            }
        }
    }

    @Test
    fun `the thumb reaches the end of a track that grows under it exactly when the list reaches its end`() {
        val chrome = Chrome()
        val down = drags(0.4f, listOf(baseTravel), chrome).single()
        val atEnd = down.first { it.fraction == 1f }
        assertThat(atEnd.thumbTop).isWithin(2f).of(atEnd.travel)
        assertThat(chrome.collapsed).isTrue()
        // And back to the top over a track that shrinks again.
        val up = drags(1f, listOf(-2 * baseTravel), chrome).single()
        val atTop = up.first { it.fraction == 0f }
        assertThat(atTop.thumbTop).isWithin(2f).of(0f)
        for ((a, b) in up.zipWithNext()) assertThat(b.fraction).isAtMost(a.fraction)
    }

    @Test
    fun `no jump at the grab`() {
        val drag = ThumbDrag(0.37f, 0.37f * baseTravel)
        drag.move(0f, baseTravel + chromePx) // the track changed, the finger did not move
        assertThat(drag.fraction).isEqualTo(0.37f)
        assertThat(drag.thumbTop(baseTravel + chromePx)).isEqualTo(0.37f * baseTravel)
    }
}
