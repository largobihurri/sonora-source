package io.sonora.feature.equalizer

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EqualizerMetricsTest {

    @Test
    fun `on a phone in portrait the curve and sliders shrink to fill the panel without scrolling`() {
        // The QA phone: a 515 dp panel, about 350 dp of switches, labels, Reset and hint.
        val metrics = equalizerMetrics(availableHeight = 515f, fixedHeight = 350f)

        assertThat(metrics.scrolls).isFalse()
        assertThat(metrics.curveHeight + metrics.trackHeight).isWithin(0.01f).of(165f)
        assertThat(metrics.curveHeight).isAtLeast(EQUALIZER_CURVE_MIN_HEIGHT)
        assertThat(metrics.trackHeight).isAtLeast(EQUALIZER_TRACK_MIN_HEIGHT)
        assertThat(metrics.trackHeight).isGreaterThan(metrics.curveHeight)
    }

    @Test
    fun `with room to spare both keep their full size and the panel wraps them`() {
        val metrics = equalizerMetrics(availableHeight = 900f, fixedHeight = 350f)

        assertThat(metrics.curveHeight).isEqualTo(EQUALIZER_CURVE_MAX_HEIGHT)
        assertThat(metrics.trackHeight).isEqualTo(EQUALIZER_TRACK_MAX_HEIGHT)
        assertThat(metrics.scrolls).isFalse()
    }

    @Test
    fun `just above the minimums the curve gives way so the sliders keep theirs`() {
        // A 3:5 share of 153 dp would leave the tracks under their minimum: the curve takes what remains.
        val metrics = equalizerMetrics(availableHeight = 350f + 153f, fixedHeight = 350f)

        assertThat(metrics.trackHeight).isEqualTo(EQUALIZER_TRACK_MIN_HEIGHT)
        assertThat(metrics.curveHeight).isWithin(0.01f).of(153f - EQUALIZER_TRACK_MIN_HEIGHT)
        assertThat(metrics.scrolls).isFalse()
    }

    @Test
    fun `when even the smallest sizes do not fit they stay at their minimum and the panel scrolls`() {
        // Landscape: a 270 dp panel.
        val metrics = equalizerMetrics(availableHeight = 270f, fixedHeight = 350f)

        assertThat(metrics.curveHeight).isEqualTo(EQUALIZER_CURVE_MIN_HEIGHT)
        assertThat(metrics.trackHeight).isEqualTo(EQUALIZER_TRACK_MIN_HEIGHT)
        assertThat(metrics.scrolls).isTrue()
    }

    @Test
    fun `exactly the smallest sizes fit without scrolling`() {
        val flex = EQUALIZER_CURVE_MIN_HEIGHT + EQUALIZER_TRACK_MIN_HEIGHT
        val metrics = equalizerMetrics(availableHeight = 350f + flex, fixedHeight = 350f)

        assertThat(metrics.scrolls).isFalse()
        assertThat(metrics.curveHeight).isEqualTo(EQUALIZER_CURVE_MIN_HEIGHT)
        assertThat(metrics.trackHeight).isEqualTo(EQUALIZER_TRACK_MIN_HEIGHT)
    }

    @Test
    fun `four bands share the width with full size knobs`() {
        val columns = bandColumns(bandCount = 4, availableWidth = 238f)

        assertThat(columns.scrolls).isFalse()
        assertThat(columns.columnWidth).isWithin(0.01f).of(59.5f)
        assertThat(columns.knobWidth).isEqualTo(26f)
        assertThat(columns.knobHeight).isEqualTo(14f)
        assertThat(columns.compactLabels).isFalse()
    }

    @Test
    fun `eleven bands fit a phone with a visible gap between knobs`() {
        val columns = bandColumns(bandCount = 11, availableWidth = 238f)

        assertThat(columns.scrolls).isFalse()
        assertThat(columns.columnWidth * 11).isWithin(0.01f).of(238f)
        assertThat(columns.columnWidth - columns.knobWidth).isAtLeast(BAND_KNOB_GAP)
        assertThat(columns.knobHeight).isLessThan(columns.knobWidth)
        assertThat(columns.compactLabels).isTrue()
    }

    @Test
    fun `twenty bands scroll with a half column peeking at the edge`() {
        val columns = bandColumns(bandCount = 20, availableWidth = 238f)

        assertThat(columns.scrolls).isTrue()
        assertThat(columns.columnWidth).isIn(com.google.common.collect.Range.closed(30f, 40f))
        val shown = 238f / columns.columnWidth
        assertThat(shown - shown.toInt()).isWithin(0.001f).of(0.5f)
        assertThat(columns.columnWidth - columns.knobWidth).isAtLeast(BAND_KNOB_GAP)
    }

    @Test
    fun `eleven bands on a narrow screen scroll rather than squeeze`() {
        assertThat(bandColumns(bandCount = 11, availableWidth = 200f).scrolls).isTrue()
    }

    @Test
    fun `knobs never touch at any width or band count`() {
        for (count in listOf(4, 11, 20)) {
            for (width in 150..700 step 10) {
                val columns = bandColumns(count, width.toFloat())
                assertThat(columns.columnWidth - columns.knobWidth).isAtLeast(BAND_KNOB_GAP)
                assertThat(columns.knobWidth).isAtMost(26f)
                assertThat(columns.knobHeight).isAtMost(columns.knobWidth)
            }
        }
    }
}
