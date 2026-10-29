package io.sonora.feature.dub.controls

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FaderMappingTest {

    @Test
    fun `the bottom of the fader is minus infinity and the top is plus 6 dB`() {
        assertThat(FaderMapping.dbOf(0.0)).isEqualTo(Double.NEGATIVE_INFINITY)
        assertThat(FaderMapping.gainOf(0.0)).isEqualTo(0.0)
        assertThat(FaderMapping.dbOf(1.0)).isWithin(1e-9).of(6.0)
    }

    @Test
    fun `the detent position is exactly unity`() {
        assertThat(FaderMapping.dbOf(FaderMapping.UNITY_POSITION)).isEqualTo(0.0)
        assertThat(FaderMapping.gainOf(FaderMapping.UNITY_POSITION)).isEqualTo(1.0)
        assertThat(FaderMapping.positionOf(0.0)).isEqualTo(FaderMapping.UNITY_POSITION)
    }

    @Test
    fun `positionOf inverts dbOf and clamps the range`() {
        for (db in listOf(-60.0, -24.0, -12.0, -3.5, 0.0, 3.0, 6.0)) {
            assertThat(FaderMapping.dbOf(FaderMapping.positionOf(db))).isWithin(1e-9).of(db)
        }
        assertThat(FaderMapping.positionOf(Double.NEGATIVE_INFINITY)).isEqualTo(0.0)
        assertThat(FaderMapping.positionOf(12.0)).isEqualTo(1.0)
        assertThat(FaderMapping.positionOf(Double.NaN)).isEqualTo(FaderMapping.UNITY_POSITION)
    }

    @Test
    fun `the level rises all the way up the fader`() {
        var last = Double.NEGATIVE_INFINITY
        for (step in 1..100) {
            val db = FaderMapping.dbOf(step / 100.0)
            assertThat(db).isGreaterThan(last)
            last = db
        }
    }

    @Test
    fun `half way to the detent is about minus 18 dB`() {
        assertThat(FaderMapping.dbOf(FaderMapping.UNITY_POSITION / 2)).isWithin(0.1).of(-18.06)
    }

    @Test
    fun `a drag holds at 0 dB inside the detent and ticks once when it reaches it`() {
        val drag = DetentDrag(FaderMapping.axis)
        drag.start(0.5)
        val ticks = mutableListOf<Boolean>()
        repeat(12) { ticks += drag.dragBy(0.025) }
        assertThat(ticks.count { it }).isEqualTo(1)
        drag.start(FaderMapping.UNITY_POSITION)
        drag.dragBy(FaderMapping.DETENT_ZONE / 2)
        assertThat(drag.value).isEqualTo(FaderMapping.UNITY_POSITION)
        assertThat(FaderMapping.dbOf(drag.value)).isEqualTo(0.0)
    }

    @Test
    fun `a drag reaches both ends and ignores NaN`() {
        val drag = DetentDrag(FaderMapping.axis)
        drag.start(0.5)
        drag.dragBy(-2.0)
        assertThat(drag.value).isEqualTo(0.0)
        drag.dragBy(Double.NaN)
        assertThat(drag.value).isEqualTo(0.0)
        drag.dragBy(5.0)
        assertThat(drag.value).isWithin(1e-12).of(1.0)
        assertThat(drag.end()).isWithin(1e-12).of(1.0)
        assertThat(drag.end()).isNull()
    }

    @Test
    fun `a cancelled drag gives no value and a drag that never started does not move`() {
        val drag = DetentDrag(FaderMapping.axis)
        assertThat(drag.dragBy(0.1)).isFalse()
        drag.start(0.2)
        assertThat(drag.dragging).isTrue()
        drag.cancel()
        assertThat(drag.dragging).isFalse()
        assertThat(drag.end()).isNull()
    }
}
