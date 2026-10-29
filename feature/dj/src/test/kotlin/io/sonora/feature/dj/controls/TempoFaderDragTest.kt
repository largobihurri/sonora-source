package io.sonora.feature.dj.controls

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TempoFaderDragTest {

    private val zone = TempoFaderMath.DETENT_ZONE

    @Test
    fun `a touch never jumps, the drag starts where the fader is`() {
        val drag = TempoFaderDrag()

        drag.start(0.5)

        assertThat(drag.dragging).isTrue()
        assertThat(drag.fraction).isWithin(1e-12).of(0.5)
    }

    @Test
    fun `the fader moves by the finger's travel only`() {
        val drag = TempoFaderDrag()
        drag.start(0.5)

        drag.dragBy(0.1)
        drag.dragBy(0.05)

        val expected = TempoFaderMath.effectiveOf(TempoFaderMath.rawOf(0.5) + 0.15)
        assertThat(drag.fraction).isWithin(1e-12).of(expected)
    }

    @Test
    fun `the fader stops at the ends and turns back at once`() {
        val drag = TempoFaderDrag()
        drag.start(0.9)

        drag.dragBy(5.0)
        assertThat(drag.fraction).isEqualTo(1.0)

        drag.dragBy(-0.1)
        assertThat(drag.fraction).isWithin(1e-12).of(TempoFaderMath.effectiveOf(0.9))
        drag.dragBy(-10.0)
        assertThat(drag.fraction).isEqualTo(-1.0)
    }

    @Test
    fun `0 percent is sticky, the fader holds there and ticks once on the way in`() {
        val drag = TempoFaderDrag()
        drag.start(0.2)
        val toCentre = TempoFaderMath.rawOf(0.2)

        val ticked = drag.dragBy(-toCentre)
        assertThat(ticked).isTrue()
        assertThat(drag.fraction).isEqualTo(0.0)

        assertThat(drag.dragBy(zone * 0.9)).isFalse()
        assertThat(drag.fraction).isEqualTo(0.0)
        assertThat(drag.dragBy(-zone * 1.8)).isFalse()
        assertThat(drag.fraction).isEqualTo(0.0)
    }

    @Test
    fun `leaving the detent takes more travel than the zone and then moves smoothly`() {
        val drag = TempoFaderDrag()
        drag.start(0.0)

        drag.dragBy(zone)
        assertThat(drag.fraction).isEqualTo(0.0)
        drag.dragBy(0.01)
        assertThat(drag.fraction).isGreaterThan(0.0)
        assertThat(drag.fraction).isLessThan(0.011)
    }

    @Test
    fun `a fast move straight across 0 ticks`() {
        val drag = TempoFaderDrag()
        drag.start(0.3)

        assertThat(drag.dragBy(-0.8)).isTrue()
        assertThat(drag.fraction).isLessThan(0.0)
    }

    @Test
    fun `starting at 0 does not tick`() {
        val drag = TempoFaderDrag()
        drag.start(0.0)

        assertThat(drag.dragBy(0.01)).isFalse()
    }

    @Test
    fun `a release reports the fraction once and ends the drag`() {
        val drag = TempoFaderDrag()
        drag.start(-0.25)
        drag.dragBy(-0.1)

        val released = drag.end()

        assertThat(released).isWithin(1e-12).of(TempoFaderMath.effectiveOf(TempoFaderMath.rawOf(-0.25) - 0.1))
        assertThat(drag.dragging).isFalse()
        assertThat(drag.end()).isNull()
    }

    @Test
    fun `a cancel ends the drag without a value`() {
        val drag = TempoFaderDrag()
        drag.start(0.4)

        drag.cancel()

        assertThat(drag.dragging).isFalse()
        assertThat(drag.end()).isNull()
    }

    @Test
    fun `moves outside a drag are ignored`() {
        val drag = TempoFaderDrag()

        assertThat(drag.dragBy(0.5)).isFalse()
        assertThat(drag.dragging).isFalse()
    }
}
