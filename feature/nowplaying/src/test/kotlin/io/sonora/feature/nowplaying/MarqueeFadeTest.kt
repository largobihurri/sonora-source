package io.sonora.feature.nowplaying

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MarqueeFadeTest {

    // 300 px of text in a 200 px container: the gap is a third of the container (67 px), so one pass travels 367 px.
    // At 90 px/s a pass takes ceil(367 / 90 * 1000) = 4078 ms, and a cycle is that plus the 1200 ms rest.
    private val travel = 367f
    private val velocity = 90f
    private val rest = 1200L
    private val pass = 4078L

    private fun offsetAt(elapsed: Long) = marqueeOffsetPx(elapsed, travel, velocity)

    @Test
    fun `the travel is the text plus the gap of a third of the container`() {
        assertThat(marqueeTravelPx(contentPx = 300f, containerPx = 200f)).isEqualTo(367f)
    }

    @Test
    fun `the text rests at the start during the first delay`() {
        assertThat(offsetAt(0)).isEqualTo(0f)
        assertThat(offsetAt(rest)).isEqualTo(0f)
    }

    @Test
    fun `the text moves at the marquee velocity once the delay is over`() {
        assertThat(offsetAt(rest + 1000)).isWithin(0.01f).of(90f)
        assertThat(offsetAt(rest + 2000)).isWithin(0.01f).of(180f)
    }

    @Test
    fun `the text never passes the travel and rests again at the start of the next cycle`() {
        val cycle = rest + pass
        assertThat(offsetAt(cycle - 1)).isAtMost(travel)
        assertThat(offsetAt(cycle - 1)).isGreaterThan(travel - 1f)
        assertThat(offsetAt(cycle)).isEqualTo(0f)
        assertThat(offsetAt(cycle + rest - 1)).isEqualTo(0f)
    }

    @Test
    fun `every cycle repeats the first`() {
        val cycle = rest + pass
        assertThat(offsetAt(cycle + rest + 1000)).isWithin(0.01f).of(90f)
        assertThat(offsetAt(5 * cycle + rest + 2000)).isWithin(0.01f).of(180f)
    }

    @Test
    fun `no travel time or velocity means no movement`() {
        assertThat(marqueeOffsetPx(-5, travel, velocity)).isEqualTo(0f)
        assertThat(marqueeOffsetPx(5000, 0f, velocity)).isEqualTo(0f)
        assertThat(marqueeOffsetPx(5000, travel, 0f)).isEqualTo(0f)
    }

    @Test
    fun `text that fits is not faded on either edge`() {
        assertThat(marqueeEdgeFade(overflowing = false, offsetPx = 100f, travelPx = travel, containerPx = 200f, fadePx = 20f))
            .isEqualTo(EdgeFade(0f, 0f))
    }

    @Test
    fun `at rest an overflowing title has no left fade so it starts aligned with the artist`() {
        val fade = marqueeEdgeFade(overflowing = true, offsetPx = 0f, travelPx = travel, containerPx = 200f, fadePx = 20f)
        assertThat(fade.leftPx).isEqualTo(0f)
        assertThat(fade.rightPx).isEqualTo(20f)
    }

    @Test
    fun `the left fade grows with the scroll until it reaches its full width`() {
        fun left(offset: Float) = marqueeEdgeFade(true, offset, travel, 200f, 20f).leftPx
        assertThat(left(5f)).isEqualTo(5f)
        assertThat(left(12f)).isEqualTo(12f)
        assertThat(left(20f)).isEqualTo(20f)
        assertThat(left(150f)).isEqualTo(20f)
    }

    @Test
    fun `the left fade shrinks again as the next copy of the text settles at the start`() {
        fun left(offset: Float) = marqueeEdgeFade(true, offset, travel, 200f, 20f).leftPx
        assertThat(left(travel - 30f)).isEqualTo(20f)
        assertThat(left(travel - 8f)).isEqualTo(8f)
        assertThat(left(travel)).isEqualTo(0f)
    }

    @Test
    fun `the right fade stays the same through the whole cycle`() {
        listOf(0f, 10f, 150f, travel - 3f, travel).forEach { offset ->
            assertThat(marqueeEdgeFade(true, offset, travel, 200f, 20f).rightPx).isEqualTo(20f)
        }
    }

    @Test
    fun `the two fades never cover more than the container between them`() {
        val fade = marqueeEdgeFade(true, 100f, travel, containerPx = 30f, fadePx = 20f)
        assertThat(fade.leftPx).isEqualTo(15f)
        assertThat(fade.rightPx).isEqualTo(15f)
    }

    @Test
    fun `the gradient stops are the fades as fractions of the container`() {
        val fade = EdgeFade(leftPx = 20f, rightPx = 40f)
        assertThat(fade.leftStop(200f)).isEqualTo(0.1f)
        assertThat(fade.rightStop(200f)).isEqualTo(0.8f)
    }

    @Test
    fun `without a fade the stops sit on the edges`() {
        assertThat(EdgeFade(0f, 0f).leftStop(200f)).isEqualTo(0f)
        assertThat(EdgeFade(0f, 0f).rightStop(200f)).isEqualTo(1f)
    }
}
