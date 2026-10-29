package io.sonora.feature.equalizer

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The vertical band slider's drag: relative to where it starts, 24 dB over the track height, ±12 dB at most. */
class BandDragTest {

    private val changes = mutableListOf<Float>()
    private val finished = mutableListOf<Float>()
    private var cancelled = 0
    private var gain = 2f
    private val drag = BandDrag({ gain }, { changes += it }, { finished += it }, { cancelled++ })

    @Test
    fun `the gain moves by the finger's travel, never jumping to the touch point`() {
        // Up is a boost: a quarter of a 200 px track upwards is +6 dB.
        drag.start(trackHeightPx = 200f, travelBeforeStartPx = -10f)
        drag.dragBy(-40f)

        assertThat(changes.first()).isWithin(1e-4f).of(3.2f)
        assertThat(drag.gain).isWithin(1e-4f).of(8f)
    }

    @Test
    fun `the gain stops at plus or minus 12 dB and picks up at once on the way back`() {
        drag.start(trackHeightPx = 100f)
        drag.dragBy(-500f)
        assertThat(drag.gain).isEqualTo(12f)

        drag.dragBy(10f)
        assertThat(drag.gain).isWithin(1e-4f).of(9.6f)
    }

    @Test
    fun `a release commits the final gain and a cancel commits nothing`() {
        drag.start(trackHeightPx = 240f)
        drag.dragBy(24f)
        drag.end()
        drag.start(trackHeightPx = 240f)
        drag.dragBy(24f)
        drag.cancel()

        assertThat(finished).containsExactly(-0.4f)
        assertThat(cancelled).isEqualTo(1)
        assertThat(drag.dragging).isFalse()
    }

    @Test
    fun `an end without a drag reports nothing`() {
        drag.end()
        drag.cancel()

        assertThat(finished).isEmpty()
        assertThat(cancelled).isEqualTo(0)
    }
}
