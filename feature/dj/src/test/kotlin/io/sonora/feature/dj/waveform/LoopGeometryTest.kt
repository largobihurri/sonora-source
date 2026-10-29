package io.sonora.feature.dj.waveform

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.playback.DjLoopState
import org.junit.Test

class LoopGeometryTest {

    @Test
    fun `no In means no region`() {
        assertThat(LoopRegion.of(DjLoopState(available = true))).isNull()
    }

    @Test
    fun `a region has the phase the loop is in`() {
        assertThat(LoopRegion.of(DjLoopState(inMs = 1.0, available = true))!!.phase).isEqualTo(LoopPhase.MARKING)
        assertThat(LoopRegion.of(DjLoopState(1.0, 2.0, armed = true, available = true))!!.phase).isEqualTo(LoopPhase.ARMED)
        assertThat(LoopRegion.of(DjLoopState(1.0, 2.0, looping = true, available = true))!!.phase).isEqualTo(LoopPhase.LOOPING)
        assertThat(LoopRegion.of(DjLoopState(1.0, 2.0, available = true))!!.phase).isEqualTo(LoopPhase.EXITED)
    }

    @Test
    fun `a region with its Out ends there, one still being marked ends at the playhead`() {
        val closed = LoopRegion(1_000.0, 3_000.0, LoopPhase.LOOPING)
        val marking = LoopRegion(1_000.0, null, LoopPhase.MARKING)

        assertThat(closed.endAt(positionMs = 1_500.0)).isEqualTo(3_000.0)
        assertThat(marking.endAt(positionMs = 1_500.0)).isEqualTo(1_500.0)
    }

    @Test
    fun `a mark never ends before it starts, as when the playhead is dragged back`() {
        val marking = LoopRegion(1_000.0, null, LoopPhase.MARKING)

        assertThat(marking.endAt(positionMs = 400.0)).isEqualTo(1_000.0)
    }

    @Test
    fun `a time maps to the pixel of the scrolling view, left of the view when before it`() {
        // 0.1 px per ms, the view's left edge at absolute pixel 50.
        assertThat(LoopGeometry.x(1_000.0, leftPx = 50.0, pxPerMs = 0.1)).isEqualTo(50f)
        assertThat(LoopGeometry.x(100.0, leftPx = 50.0, pxPerMs = 0.1)).isEqualTo(-40f)
    }

    @Test
    fun `a span shows when any of it is inside the view`() {
        assertThat(LoopGeometry.visible(-30f, -1f, 300f)).isFalse()
        assertThat(LoopGeometry.visible(-30f, 10f, 300f)).isTrue()
        assertThat(LoopGeometry.visible(100f, 200f, 300f)).isTrue()
        assertThat(LoopGeometry.visible(290f, 500f, 300f)).isTrue()
        assertThat(LoopGeometry.visible(301f, 500f, 300f)).isFalse()
    }

    @Test
    fun `a span is clipped to the view`() {
        assertThat(LoopGeometry.clamped(-30f, 300f)).isEqualTo(0f)
        assertThat(LoopGeometry.clamped(120f, 300f)).isEqualTo(120f)
        assertThat(LoopGeometry.clamped(900f, 300f)).isEqualTo(300f)
    }

    @Test
    fun `an edge shows its handle only while it is inside the view`() {
        assertThat(LoopGeometry.edgeVisible(0f, 300f)).isTrue()
        assertThat(LoopGeometry.edgeVisible(300f, 300f)).isTrue()
        assertThat(LoopGeometry.edgeVisible(-1f, 300f)).isFalse()
        assertThat(LoopGeometry.edgeVisible(301f, 300f)).isFalse()
    }

    @Test
    fun `on the overview the region keeps to the strip and is at least a hairline wide`() {
        // A 10 s loop of a 200 s track on a 400 px strip: 20 px wide from 4 px.
        assertThat(LoopGeometry.overviewLeft(2_000.0, 200_000.0, 400f)).isEqualTo(4f)
        assertThat(LoopGeometry.overviewRight(2_000.0, 12_000.0, 200_000.0, 400f, minWidthPx = 2f)).isEqualTo(24f)
        val left = LoopGeometry.overviewLeft(100_000.0, 200_000.0, 400f)
        val tiny = LoopGeometry.overviewRight(100_000.0, 100_031.0, 200_000.0, 400f, minWidthPx = 2f)
        assertThat(tiny - left).isEqualTo(2f)
        assertThat(LoopGeometry.overviewRight(199_999.0, 220_000.0, 200_000.0, 400f, minWidthPx = 2f)).isEqualTo(400f)
    }

    @Test
    fun `the fill is brightest while the loop plays and fainter the less active it is`() {
        val alphas = listOf(LoopPhase.LOOPING, LoopPhase.ARMED, LoopPhase.MARKING, LoopPhase.EXITED).map(LoopGeometry::fillAlpha)
        assertThat(alphas).isInOrder(Comparator.reverseOrder<Float>())
        assertThat(alphas.last()).isGreaterThan(0f)
    }

    @Test
    fun `the loop colour is an orange-yellow apart from the red bars, the green phrases and the blue and white bands`() {
        fun hue(argb: Int): Double {
            val r = ((argb shr 16) and 0xFF) / 255.0
            val g = ((argb shr 8) and 0xFF) / 255.0
            val b = (argb and 0xFF) / 255.0
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            val d = max - min
            val h = when (max) {
                r -> ((g - b) / d) % 6
                g -> (b - r) / d + 2
                else -> (r - g) / d + 4
            }
            return (h * 60 + 360) % 360
        }
        fun apart(a: Double, b: Double) = minOf(Math.abs(a - b), 360 - Math.abs(a - b))

        val loop = hue(DjWaveformPalette.LOOP_COLOR)
        assertThat(loop).isAtLeast(30.0)
        assertThat(loop).isAtMost(50.0)
        assertThat(apart(loop, hue(DjWaveformPalette.DOWNBEAT_TICK_COLOR))).isAtLeast(25.0)
        assertThat(apart(loop, hue(DjWaveformPalette.PHRASE_COLOR))).isAtLeast(60.0)
        assertThat(apart(loop, hue(DjWaveformPalette.LOW_COLOR))).isAtLeast(100.0)
        assertThat((DjWaveformPalette.LOOP_COLOR ushr 24) and 0xFF).isEqualTo(0xFF)
    }
}
