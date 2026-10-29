package io.sonora.feature.dj

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DjTimeLabelsTest {

    @Test
    fun `at the start the remaining time is the whole track`() {
        assertThat(DjTimeLabels.of(0.0, 200_000)).isEqualTo(DjTimeLabels("0:00", "-3:20"))
    }

    @Test
    fun `elapsed and remaining always add up to the shown length`() {
        // 83.9999 s elapsed shows 1:23; 200 whole seconds less 83 leaves 117 s.
        assertThat(DjTimeLabels.of(83_999.9, 200_500)).isEqualTo(DjTimeLabels("1:23", "-1:57"))
    }

    @Test
    fun `a position past the end is held at the end`() {
        assertThat(DjTimeLabels.of(250_000.0, 200_000)).isEqualTo(DjTimeLabels("3:20", "-0:00"))
    }

    @Test
    fun `a negative or unknown position reads as the start`() {
        assertThat(DjTimeLabels.of(-500.0, 200_000)).isEqualTo(DjTimeLabels("0:00", "-3:20"))
        assertThat(DjTimeLabels.of(Double.NaN, 200_000)).isEqualTo(DjTimeLabels("0:00", "-3:20"))
    }

    @Test
    fun `an unknown length shows the elapsed time and nothing remaining`() {
        assertThat(DjTimeLabels.of(65_000.0, 0)).isEqualTo(DjTimeLabels("1:05", "-0:00"))
    }

    @Test
    fun `an hour long mix shows hours`() {
        assertThat(DjTimeLabels.of(3_725_000.0, 7_200_000)).isEqualTo(DjTimeLabels("1:02:05", "-57:55"))
    }

    @Test
    fun `the second of a position is floored and clamped into the track`() {
        assertThat(DjTimeLabels.secondOf(1_999.0, 200_000)).isEqualTo(1)
        assertThat(DjTimeLabels.secondOf(-1.0, 200_000)).isEqualTo(0)
        assertThat(DjTimeLabels.secondOf(Double.NaN, 200_000)).isEqualTo(0)
        assertThat(DjTimeLabels.secondOf(999_999.0, 200_000)).isEqualTo(200)
        assertThat(DjTimeLabels.secondOf(999_999.0, 0)).isEqualTo(999)
    }
}
