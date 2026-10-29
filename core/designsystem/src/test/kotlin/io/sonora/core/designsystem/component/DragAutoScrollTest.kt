package io.sonora.core.designsystem.component

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DragAutoScrollTest {

    // A 1000 px viewport with 100 px edge zones and a 1500 px/s top speed.
    private fun speed(probe: Float) = edgeScrollSpeed(probe, viewportStart = 0f, viewportEnd = 1000f, edge = 100f, maxSpeed = 1500f)

    @Test
    fun `a row held away from the edges does not scroll`() {
        assertThat(speed(100f)).isEqualTo(0f)
        assertThat(speed(500f)).isEqualTo(0f)
        assertThat(speed(900f)).isEqualTo(0f)
    }

    @Test
    fun `a row held in the top zone scrolls toward the start, faster the deeper it is`() {
        assertThat(speed(75f)).isEqualTo(-375f)
        assertThat(speed(50f)).isEqualTo(-750f)
        assertThat(speed(0f)).isEqualTo(-1500f)
    }

    @Test
    fun `a row held in the bottom zone scrolls toward the end, faster the deeper it is`() {
        assertThat(speed(950f)).isEqualTo(750f)
        assertThat(speed(1000f)).isEqualTo(1500f)
    }

    @Test
    fun `past the edge the speed stays at its maximum`() {
        assertThat(speed(-400f)).isEqualTo(-1500f)
        assertThat(speed(1600f)).isEqualTo(1500f)
    }

    @Test
    fun `a viewport shorter than two zones splits between them`() {
        // 120 px viewport: each zone is 60 px, so its middle scrolls neither way.
        assertThat(edgeScrollSpeed(60f, 0f, 120f, edge = 100f, maxSpeed = 1500f)).isEqualTo(0f)
        assertThat(edgeScrollSpeed(30f, 0f, 120f, edge = 100f, maxSpeed = 1500f)).isEqualTo(-750f)
    }

    @Test
    fun `an empty viewport or zone never scrolls`() {
        assertThat(edgeScrollSpeed(0f, 0f, 0f, edge = 100f, maxSpeed = 1500f)).isEqualTo(0f)
        assertThat(edgeScrollSpeed(0f, 0f, 1000f, edge = 0f, maxSpeed = 1500f)).isEqualTo(0f)
    }

    @Test
    fun `each frame scrolls the distance its duration covers, so the speed is frame-rate independent`() {
        val sixtyHz = edgeScrollStep(-1200f, frameNanos = 16_666_667L)
        val oneTwentyHz = edgeScrollStep(-1200f, frameNanos = 8_333_333L)

        assertThat(sixtyHz).isWithin(0.01f).of(-20f)
        assertThat(oneTwentyHz * 2).isWithin(0.01f).of(sixtyHz)
    }

    @Test
    fun `a stalled frame scrolls no further than a capped frame, and a backwards clock not at all`() {
        assertThat(edgeScrollStep(1200f, frameNanos = 1_000_000_000L)).isEqualTo(edgeScrollStep(1200f, MAX_FRAME_NANOS))
        assertThat(edgeScrollStep(1200f, frameNanos = -5L)).isEqualTo(0f)
    }
}
