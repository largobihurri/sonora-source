package io.sonora.core.djengine.beats

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class TempoPriorTest {

    @Test
    fun `the default range is 70 to 180 and the reference range 70 to 210`() {
        assertThat(TempoPrior.DEFAULT.minBpm).isEqualTo(70.0)
        assertThat(TempoPrior.DEFAULT.maxBpm).isEqualTo(180.0)
        assertThat(TempoPrior.REFERENCE_WIDE.maxBpm).isEqualTo(210.0)
    }

    @Test
    fun `candidates are the octave multiples inside the range`() {
        assertThat(TempoPrior.DEFAULT.candidates(256.0)).containsExactly(128.0)
        assertThat(TempoPrior.DEFAULT.candidates(60.0)).containsExactly(120.0)
        assertThat(TempoPrior.DEFAULT.candidates(90.0)).containsExactly(90.0, 180.0).inOrder()
        assertThat(TempoPrior.DEFAULT.candidates(200.0)).containsExactly(100.0)
    }

    @Test
    fun `fold picks the multiple the preference likes best`() {
        assertThat(TempoPrior.DEFAULT.fold(240.0)).isEqualTo(120.0)
        assertThat(TempoPrior.DEFAULT.fold(45.0)).isEqualTo(90.0)
        // 90 and 180 both fit; 90 is closer to 120 in octaves.
        assertThat(TempoPrior.DEFAULT.fold(180.0)).isEqualTo(90.0)
        assertThat(TempoPrior.DEFAULT.fold(150.0)).isEqualTo(150.0)
    }

    @Test
    fun `an in-range tempo is its own best fold unless its double is preferred`() {
        assertThat(TempoPrior.DEFAULT.fold(128.0)).isEqualTo(128.0)
        assertThat(TempoPrior.DEFAULT.fold(75.0)).isEqualTo(150.0)
    }

    @Test
    fun `weight peaks at the preferred tempo and falls off in octaves`() {
        val prior = TempoPrior.DEFAULT
        assertThat(prior.weight(120.0)).isEqualTo(1.0)
        assertThat(prior.weight(60.0)).isEqualTo(prior.weight(240.0))
        assertThat(prior.weight(90.0)).isGreaterThan(prior.weight(180.0))
        assertThat(prior.weight(240.0)).isLessThan(prior.weight(100.0))
    }

    @Test
    fun `a range narrower than an octave may have no multiple and then the closest one is used`() {
        val narrow = TempoPrior(minBpm = 100.0, maxBpm = 110.0)
        assertThat(narrow.candidates(130.0)).isEmpty()
        // 130 is 0.24 octaves above 110, 65 is 0.6 octaves below 100.
        assertThat(narrow.fold(130.0)).isEqualTo(130.0)
    }

    @Test
    fun `the range must be valid`() {
        assertThrows(IllegalArgumentException::class.java) { TempoPrior(0.0, 100.0) }
        assertThrows(IllegalArgumentException::class.java) { TempoPrior(100.0, 90.0) }
        assertThrows(IllegalArgumentException::class.java) { TempoPrior(70.0, 180.0, preferredBpm = 0.0) }
        assertThrows(IllegalArgumentException::class.java) { TempoPrior(70.0, 180.0, preferenceWidthOctaves = 0.0) }
    }
}
