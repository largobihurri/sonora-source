package io.sonora.core.djengine.key

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class KeyProfileTest {

    private fun KeyProfile.sum(minor: Boolean): Double = (0 until KeyProfile.DEGREES).sumOf { weight(minor, it) }

    @Test
    fun `edma vectors sum to one as in the paper`() {
        assertThat(KeyProfile.EDMA.sum(minor = false)).isWithin(0.005).of(1.0)
        assertThat(KeyProfile.EDMA.sum(minor = true)).isWithin(0.005).of(1.0)
    }

    @Test
    fun `edma has the tonic and the fifth as its two largest degrees`() {
        for (minor in listOf(false, true)) {
            val ranked = (0 until KeyProfile.DEGREES).sortedByDescending { KeyProfile.EDMA.weight(minor, it) }
            assertThat(ranked.take(2)).containsExactly(0, 7).inOrder()
        }
    }

    @Test
    fun `edma minor favours the subtonic over the leading tone`() {
        // Faraldo et al.: the EDM profiles raise the subtonic (degree 10) and lower the leading tone (degree 11).
        assertThat(KeyProfile.EDMA.weight(minor = true, degree = 10)).isGreaterThan(KeyProfile.EDMA.weight(true, 11))
        assertThat(KeyProfile.TEMPERLEY.weight(minor = true, degree = 10)).isLessThan(KeyProfile.TEMPERLEY.weight(true, 11))
    }

    @Test
    fun `temperley divided by 38 point 5 gives the curve of the paper`() {
        assertThat(KeyProfile.TEMPERLEY.weight(false, 0) / 38.5).isWithin(0.0005).of(0.130)
        assertThat(KeyProfile.TEMPERLEY.weight(false, 10) / 38.5).isWithin(0.0005).of(0.039)
        assertThat(KeyProfile.TEMPERLEY.weight(true, 3) / 38.5).isWithin(0.0005).of(0.117)
        assertThat(KeyProfile.TEMPERLEY.sum(minor = false)).isWithin(1e-9).of(38.5)
        assertThat(KeyProfile.TEMPERLEY.sum(minor = true)).isWithin(1e-9).of(38.5)
    }

    @Test
    fun `krumhansl kessler has the published probe tone ratings`() {
        assertThat(KeyProfile.KRUMHANSL_KESSLER.weight(false, 0)).isEqualTo(6.35)
        assertThat(KeyProfile.KRUMHANSL_KESSLER.weight(true, 3)).isEqualTo(5.38)
        assertThat(KeyProfile.KRUMHANSL_KESSLER.weight(true, 11)).isEqualTo(3.17)
    }

    @Test
    fun `a profile copies its arrays and validates its shape`() {
        val major = DoubleArray(12) { 1.0 }
        val profile = KeyProfile("x", "X", major, DoubleArray(12))
        major[0] = 99.0

        assertThat(profile.weight(false, 0)).isEqualTo(1.0)
        assertThrows(IllegalArgumentException::class.java) { KeyProfile("x", "X", DoubleArray(11), DoubleArray(12)) }
        assertThrows(IllegalArgumentException::class.java) { KeyProfile("", "X", major, DoubleArray(12)) }
        assertThrows(IllegalArgumentException::class.java) { profile.weight(false, 12) }
    }
}
