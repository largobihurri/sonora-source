package io.sonora.feature.dj.controls

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.playback.PlaybackTempo
import io.sonora.core.domain.playback.TempoRange
import org.junit.Test

class TempoFaderMathTest {

    private fun tempo(bp: Int, range: TempoRange) = PlaybackTempo(speedBasisPoints = bp, range = range)

    private val zone = TempoFaderMath.DETENT_ZONE

    @Test
    fun `normal speed sits at the centre of every range`() {
        for (range in TempoRange.entries) {
            assertThat(TempoFaderMath.fractionOf(tempo(10_000, range))).isEqualTo(0.0)
        }
    }

    @Test
    fun `the ends of a range are the ends of the fader`() {
        for (range in TempoRange.entries) {
            assertThat(TempoFaderMath.fractionOf(tempo(range.maxBp, range))).isEqualTo(1.0)
            assertThat(TempoFaderMath.fractionOf(tempo(range.minBp, range))).isEqualTo(-1.0)
        }
    }

    @Test
    fun `each half of the fader scales on its own, so the wide range keeps 0 percent at the centre`() {
        assertThat(TempoFaderMath.fractionOf(tempo(10_500, TempoRange.TEN))).isWithin(1e-12).of(0.5)
        assertThat(TempoFaderMath.fractionOf(tempo(10_300, TempoRange.SIX))).isWithin(1e-12).of(0.5)
        assertThat(TempoFaderMath.fractionOf(tempo(15_000, TempoRange.WIDE))).isWithin(1e-12).of(0.5)
        assertThat(TempoFaderMath.fractionOf(tempo(7_500, TempoRange.WIDE))).isWithin(1e-12).of(-0.5)
    }

    @Test
    fun `the fraction of a fader tempo leads back to the same tempo in every range`() {
        for (range in TempoRange.entries) {
            for (k in -40..40) {
                val onFader = PlaybackTempo(range = range).withFader(k / 40.0)
                val back = TempoFaderMath.tempoAt(onFader, TempoFaderMath.fractionOf(onFader))
                assertThat(back).isEqualTo(onFader)
            }
        }
    }

    @Test
    fun `a fader position gives the tempo the model's fader gives`() {
        val base = PlaybackTempo(semitones = 3, masterTempo = false, range = TempoRange.SIXTEEN)
        for (fraction in listOf(-1.0, -0.37, 0.0, 0.123, 0.5, 1.0, 2.0, Double.NaN)) {
            assertThat(TempoFaderMath.tempoAt(base, fraction)).isEqualTo(base.withFader(fraction))
        }
    }

    @Test
    fun `the detent zone holds the fader at 0`() {
        assertThat(TempoFaderMath.effectiveOf(0.0)).isEqualTo(0.0)
        assertThat(TempoFaderMath.effectiveOf(zone)).isEqualTo(0.0)
        assertThat(TempoFaderMath.effectiveOf(-zone)).isEqualTo(0.0)
        assertThat(TempoFaderMath.effectiveOf(zone / 2)).isEqualTo(0.0)
    }

    @Test
    fun `beyond the detent the fader moves on without a jump and still reaches both ends`() {
        assertThat(TempoFaderMath.effectiveOf(zone + 1e-9)).isWithin(1e-6).of(0.0)
        assertThat(TempoFaderMath.effectiveOf(1.0)).isEqualTo(1.0)
        assertThat(TempoFaderMath.effectiveOf(-1.0)).isEqualTo(-1.0)
        var previous = -1.0
        for (k in -100..100) {
            val now = TempoFaderMath.effectiveOf(k / 100.0)
            assertThat(now).isAtLeast(previous)
            previous = now
        }
    }

    @Test
    fun `the raw position of a fraction leads back to it`() {
        assertThat(TempoFaderMath.rawOf(0.0)).isEqualTo(0.0)
        for (fraction in listOf(-1.0, -0.5, -0.01, 0.01, 0.25, 0.999, 1.0)) {
            assertThat(TempoFaderMath.effectiveOf(TempoFaderMath.rawOf(fraction))).isWithin(1e-12).of(fraction)
        }
    }

    @Test
    fun `entering the detent from outside hits it`() {
        assertThat(TempoFaderMath.hitsDetent(0.5, 0.02)).isTrue()
        assertThat(TempoFaderMath.hitsDetent(-0.5, -0.02)).isTrue()
        assertThat(TempoFaderMath.hitsDetent(0.5, 0.0)).isTrue()
    }

    @Test
    fun `jumping across the whole detent in one move hits it too`() {
        assertThat(TempoFaderMath.hitsDetent(0.3, -0.3)).isTrue()
        assertThat(TempoFaderMath.hitsDetent(-0.3, 0.3)).isTrue()
    }

    @Test
    fun `moves that stay on one side or leave the detent do not hit it`() {
        assertThat(TempoFaderMath.hitsDetent(0.5, 0.3)).isFalse()
        assertThat(TempoFaderMath.hitsDetent(0.02, 0.5)).isFalse()
        assertThat(TempoFaderMath.hitsDetent(0.02, -0.01)).isFalse()
        assertThat(TempoFaderMath.hitsDetent(0.0, 0.0)).isFalse()
    }

    @Test
    fun `fine mode keeps full gain on the fader and just off it`() {
        assertThat(TempoFaderMath.fineDivisor(0f, halfHeightPx = 24f, stepPx = 40f)).isEqualTo(1)
        assertThat(TempoFaderMath.fineDivisor(24f, halfHeightPx = 24f, stepPx = 40f)).isEqualTo(1)
        assertThat(TempoFaderMath.fineDivisor(63f, halfHeightPx = 24f, stepPx = 40f)).isEqualTo(1)
    }

    @Test
    fun `moving the finger away from the fader divides the gain by 2, 4, then 8`() {
        assertThat(TempoFaderMath.fineDivisor(64f, halfHeightPx = 24f, stepPx = 40f)).isEqualTo(2)
        assertThat(TempoFaderMath.fineDivisor(103f, halfHeightPx = 24f, stepPx = 40f)).isEqualTo(2)
        assertThat(TempoFaderMath.fineDivisor(104f, halfHeightPx = 24f, stepPx = 40f)).isEqualTo(4)
        assertThat(TempoFaderMath.fineDivisor(144f, halfHeightPx = 24f, stepPx = 40f)).isEqualTo(8)
        assertThat(TempoFaderMath.fineDivisor(5_000f, halfHeightPx = 24f, stepPx = 40f)).isEqualTo(8)
    }

    @Test
    fun `fine mode works the same above and below the fader`() {
        assertThat(TempoFaderMath.fineDivisor(-104f, halfHeightPx = 24f, stepPx = 40f)).isEqualTo(4)
        assertThat(TempoFaderMath.fineDivisor(-10f, halfHeightPx = 24f, stepPx = 40f)).isEqualTo(1)
    }

    @Test
    fun `unusable fine mode inputs keep full gain`() {
        assertThat(TempoFaderMath.fineDivisor(Float.NaN, halfHeightPx = 24f, stepPx = 40f)).isEqualTo(1)
        assertThat(TempoFaderMath.fineDivisor(500f, halfHeightPx = 24f, stepPx = 0f)).isEqualTo(1)
    }

    @Test
    fun `the thumb travels from the left end to the right end, right being faster`() {
        assertThat(TempoFaderMath.thumbOffsetPx(-1.0, trackWidthPx = 300f, thumbWidthPx = 60f)).isEqualTo(0f)
        assertThat(TempoFaderMath.thumbOffsetPx(0.0, trackWidthPx = 300f, thumbWidthPx = 60f)).isEqualTo(120f)
        assertThat(TempoFaderMath.thumbOffsetPx(1.0, trackWidthPx = 300f, thumbWidthPx = 60f)).isEqualTo(240f)
        assertThat(TempoFaderMath.thumbOffsetPx(0.5, trackWidthPx = 300f, thumbWidthPx = 60f)).isEqualTo(180f)
    }

    @Test
    fun `the thumb never leaves the track`() {
        assertThat(TempoFaderMath.thumbOffsetPx(3.0, trackWidthPx = 300f, thumbWidthPx = 60f)).isEqualTo(240f)
        assertThat(TempoFaderMath.thumbOffsetPx(-3.0, trackWidthPx = 300f, thumbWidthPx = 60f)).isEqualTo(0f)
        assertThat(TempoFaderMath.thumbOffsetPx(0.5, trackWidthPx = 40f, thumbWidthPx = 60f)).isEqualTo(0f)
    }

    @Test
    fun `a finger travel of half the thumb's travel moves the fader by one half, divided in fine mode`() {
        assertThat(TempoFaderMath.fractionDelta(120f, travelPx = 240f, divisor = 1)).isWithin(1e-12).of(1.0)
        assertThat(TempoFaderMath.fractionDelta(-60f, travelPx = 240f, divisor = 1)).isWithin(1e-12).of(-0.5)
        assertThat(TempoFaderMath.fractionDelta(120f, travelPx = 240f, divisor = 4)).isWithin(1e-12).of(0.25)
    }

    @Test
    fun `no travel, no move`() {
        assertThat(TempoFaderMath.fractionDelta(120f, travelPx = 0f, divisor = 1)).isEqualTo(0.0)
    }

    // --- the vertical fader (tablets, landscape): the CDJ's direction, "-" on top and "+" at the bottom, so pulling down speeds up ---

    @Test
    fun `the vertical thumb travels from the top, the slower end, to the bottom, the faster end`() {
        assertThat(TempoFaderMath.verticalThumbOffsetPx(-1.0, trackHeightPx = 300f, thumbHeightPx = 60f)).isEqualTo(0f)
        assertThat(TempoFaderMath.verticalThumbOffsetPx(0.0, trackHeightPx = 300f, thumbHeightPx = 60f)).isEqualTo(120f)
        assertThat(TempoFaderMath.verticalThumbOffsetPx(1.0, trackHeightPx = 300f, thumbHeightPx = 60f)).isEqualTo(240f)
        assertThat(TempoFaderMath.verticalThumbOffsetPx(0.5, trackHeightPx = 300f, thumbHeightPx = 60f)).isEqualTo(180f)
    }

    @Test
    fun `the vertical thumb never leaves the track and an unusable fraction rests at the centre`() {
        assertThat(TempoFaderMath.verticalThumbOffsetPx(3.0, trackHeightPx = 300f, thumbHeightPx = 60f)).isEqualTo(240f)
        assertThat(TempoFaderMath.verticalThumbOffsetPx(-3.0, trackHeightPx = 300f, thumbHeightPx = 60f)).isEqualTo(0f)
        assertThat(TempoFaderMath.verticalThumbOffsetPx(0.5, trackHeightPx = 40f, thumbHeightPx = 60f)).isEqualTo(0f)
        assertThat(TempoFaderMath.verticalThumbOffsetPx(Double.NaN, trackHeightPx = 300f, thumbHeightPx = 60f)).isEqualTo(120f)
    }

    @Test
    fun `the vertical thumb is the horizontal one turned, with the slower end on top`() {
        for (f in listOf(-1.0, -0.37, 0.0, 0.5, 1.0)) {
            assertThat(TempoFaderMath.verticalThumbOffsetPx(f, 480f, 72f)).isEqualTo(TempoFaderMath.thumbOffsetPx(f, 480f, 72f))
        }
    }

    @Test
    fun `a finger moving down speeds the vertical fader up, half the travel being one half of the fader`() {
        assertThat(TempoFaderMath.verticalFractionDelta(120f, travelPx = 240f, divisor = 1)).isWithin(1e-12).of(1.0)
        assertThat(TempoFaderMath.verticalFractionDelta(-60f, travelPx = 240f, divisor = 1)).isWithin(1e-12).of(-0.5)
        assertThat(TempoFaderMath.verticalFractionDelta(120f, travelPx = 240f, divisor = 4)).isWithin(1e-12).of(0.25)
    }

    @Test
    fun `a vertical drag with no travel, or no finger travel, moves nothing`() {
        assertThat(TempoFaderMath.verticalFractionDelta(120f, travelPx = 0f, divisor = 1)).isEqualTo(0.0)
        assertThat(TempoFaderMath.verticalFractionDelta(0f, travelPx = 240f, divisor = 1) == 0.0).isTrue()
    }

    @Test
    fun `dragging the thumb all the way down reaches the fastest tempo of the range and all the way up the slowest`() {
        for (range in TempoRange.entries) {
            val travel = 300f
            val down = TempoFaderDrag().apply { start(0.0) }
            down.dragBy(TempoFaderMath.verticalFractionDelta(travel, travel, 1))
            assertThat(TempoFaderMath.tempoAt(tempo(10_000, range), down.fraction).speedBasisPoints).isEqualTo(range.maxBp)

            val up = TempoFaderDrag().apply { start(0.0) }
            up.dragBy(TempoFaderMath.verticalFractionDelta(-travel, travel, 1))
            assertThat(TempoFaderMath.tempoAt(tempo(10_000, range), up.fraction).speedBasisPoints).isEqualTo(range.minBp)
        }
    }

    @Test
    fun `a vertical drag keeps the 0 percent detent`() {
        val drag = TempoFaderDrag().apply { start(0.0) }
        // A small move down stays inside the detent zone: the fader holds at exactly 0.
        drag.dragBy(TempoFaderMath.verticalFractionDelta(4f, travelPx = 300f, divisor = 1))
        assertThat(drag.fraction).isEqualTo(0.0)
    }

    @Test
    fun `fine mode on the vertical fader reads the finger's distance sideways from the fader's centre line`() {
        // The same rule as the horizontal fader, fed with the horizontal distance and half the fader's width.
        assertThat(TempoFaderMath.fineDivisor(-104f, halfHeightPx = 36f, stepPx = 40f)).isEqualTo(2)
        assertThat(TempoFaderMath.fineDivisor(20f, halfHeightPx = 36f, stepPx = 40f)).isEqualTo(1)
    }

    @Test
    fun `the vertical track keeps clear of the fader's ends by half the thumb`() {
        assertThat(TempoFaderMath.verticalMarkY(-1.0, trackHeightPx = 300f, thumbHeightPx = 60f)).isEqualTo(30f)
        assertThat(TempoFaderMath.verticalMarkY(0.0, trackHeightPx = 300f, thumbHeightPx = 60f)).isEqualTo(150f)
        assertThat(TempoFaderMath.verticalMarkY(1.0, trackHeightPx = 300f, thumbHeightPx = 60f)).isEqualTo(270f)
    }

    @Test
    fun `a tempo above normal sits low on the vertical fader and one below normal sits high`() {
        for (range in TempoRange.entries) {
            val faster = TempoFaderMath.verticalThumbOffsetPx(TempoFaderMath.fractionOf(tempo(10_300, range)), 300f, 60f)
            val slower = TempoFaderMath.verticalThumbOffsetPx(TempoFaderMath.fractionOf(tempo(9_700, range)), 300f, 60f)
            assertThat(faster).isGreaterThan(120f)
            assertThat(slower).isLessThan(120f)
        }
    }

    @Test
    fun `pulling the vertical fader towards you raises the tempo percent`() {
        val drag = TempoFaderDrag().apply { start(0.0) }
        drag.dragBy(TempoFaderMath.verticalFractionDelta(60f, travelPx = 300f, divisor = 1))
        assertThat(TempoFaderMath.tempoAt(tempo(10_000, TempoRange.TEN), drag.fraction).tempoPercent).isGreaterThan(0.0)
    }
}
