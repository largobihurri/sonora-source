package io.sonora.feature.dub.controls

import com.google.common.truth.Truth.assertThat
import kotlin.math.sqrt
import org.junit.Test

class CornerMappingTest {

    @Test
    fun `a corner runs from 20 Hz to 20 kHz on a log map`() {
        assertThat(CornerMapping.hzOf(0f)).isWithin(1e-3f).of(20f)
        assertThat(CornerMapping.hzOf(1f)).isWithin(0.5f).of(20_000f)
        assertThat(CornerMapping.hzOf(0.5f)).isWithin(0.05f).of(sqrt(20f * 20_000f))
    }

    @Test
    fun `positionOf inverts hzOf and clamps the range`() {
        for (hz in listOf(20f, 80f, 250f, 1_200f, 5_000f, 20_000f)) {
            assertThat(CornerMapping.hzOf(CornerMapping.positionOf(hz))).isWithin(hz * 1e-4f).of(hz)
        }
        assertThat(CornerMapping.positionOf(5f)).isEqualTo(0f)
        assertThat(CornerMapping.positionOf(90_000f)).isEqualTo(1f)
        assertThat(CornerMapping.hzOf(Float.NaN)).isWithin(1e-3f).of(20f)
    }

    @Test
    fun `a drag moves the corner along the log map and stops at the ends`() {
        assertThat(CornerMapping.dragBy(20f, 0.5f)).isWithin(0.05f).of(sqrt(20f * 20_000f))
        assertThat(CornerMapping.dragBy(1_000f, 3f)).isWithin(0.5f).of(20_000f)
        assertThat(CornerMapping.dragBy(1_000f, -3f)).isWithin(1e-3f).of(20f)
        assertThat(CornerMapping.dragBy(1_000f, Float.NaN)).isEqualTo(1_000f)
    }

    @Test
    fun `the high-pass stays at or below the low-pass and the low-pass at or above the high-pass`() {
        assertThat(CornerMapping.clampHighPass(hz = 900f, lowPassHz = 500f)).isEqualTo(500f)
        assertThat(CornerMapping.clampHighPass(hz = 300f, lowPassHz = 500f)).isEqualTo(300f)
        assertThat(CornerMapping.clampLowPass(hz = 100f, highPassHz = 250f)).isEqualTo(250f)
        assertThat(CornerMapping.clampLowPass(hz = 40_000f, highPassHz = 250f)).isEqualTo(20_000f)
    }

    @Test
    fun `a corner at the end of its range is off`() {
        assertThat(CornerMapping.isHighPassOff(20f)).isTrue()
        assertThat(CornerMapping.isHighPassOff(21f)).isFalse()
        assertThat(CornerMapping.isLowPassOff(20_000f)).isTrue()
        assertThat(CornerMapping.isLowPassOff(19_000f)).isFalse()
    }

    @Test
    fun `the five ways default to the design's corners, adjacent corners equal`() {
        assertThat(DubWay.entries.map { it.number }).containsExactly(1, 2, 3, 4, 5).inOrder()
        assertThat(DubWay.entries.map { it.defaultHighPassHz }).containsExactly(20f, 80f, 250f, 1_200f, 5_000f).inOrder()
        assertThat(DubWay.entries.map { it.defaultLowPassHz }).containsExactly(80f, 250f, 1_200f, 5_000f, 20_000f).inOrder()
        assertThat(CornerMapping.isHighPassOff(DubWay.SUB.defaultHighPassHz)).isTrue()
        assertThat(CornerMapping.isLowPassOff(DubWay.TOP.defaultLowPassHz)).isTrue()
    }
}
