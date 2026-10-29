package io.sonora.tools.calibration

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.analysis.MusicalKey
import org.junit.Test

class KeyMetricsTest {

    private val ref = MusicalKey.C_MAJOR

    @Test
    fun `same key scores one`() {
        assertThat(KeyMetrics.mirexScore(MusicalKey.C_MAJOR, ref)).isEqualTo(1.0)
        assertThat(KeyMetrics.mirexScore(MusicalKey.A_MINOR, MusicalKey.A_MINOR)).isEqualTo(1.0)
    }

    @Test
    fun `a perfect fifth above scores one half`() {
        assertThat(KeyMetrics.mirexScore(MusicalKey.G_MAJOR, ref)).isEqualTo(0.5)
        // E minor is a fifth above A minor.
        assertThat(KeyMetrics.mirexScore(MusicalKey.E_MINOR, MusicalKey.A_MINOR)).isEqualTo(0.5)
        // Wrap-around: F# major is a fifth above B major.
        assertThat(KeyMetrics.mirexScore(MusicalKey.F_SHARP_MAJOR, MusicalKey.B_MAJOR)).isEqualTo(0.5)
    }

    @Test
    fun `a fifth below scores zero by default and one half when allowed`() {
        assertThat(KeyMetrics.mirexScore(MusicalKey.F_MAJOR, ref)).isEqualTo(0.0)
        assertThat(KeyMetrics.mirexScore(MusicalKey.F_MAJOR, ref, allowDescendingFifths = true)).isEqualTo(0.5)
        assertThat(KeyMetrics.mirexScore(MusicalKey.D_MINOR, MusicalKey.A_MINOR, allowDescendingFifths = true))
            .isEqualTo(0.5)
    }

    @Test
    fun `the relative key scores 0 point 3 in both directions`() {
        // Reference C major, estimate A minor, and the other way round.
        assertThat(KeyMetrics.mirexScore(MusicalKey.A_MINOR, MusicalKey.C_MAJOR)).isEqualTo(0.3)
        assertThat(KeyMetrics.mirexScore(MusicalKey.C_MAJOR, MusicalKey.A_MINOR)).isEqualTo(0.3)
        // E flat major with C minor.
        assertThat(KeyMetrics.mirexScore(MusicalKey.C_MINOR, MusicalKey.E_FLAT_MAJOR)).isEqualTo(0.3)
        assertThat(KeyMetrics.mirexScore(MusicalKey.E_FLAT_MAJOR, MusicalKey.C_MINOR)).isEqualTo(0.3)
    }

    @Test
    fun `the parallel key scores 0 point 2`() {
        assertThat(KeyMetrics.mirexScore(MusicalKey.C_MINOR, MusicalKey.C_MAJOR)).isEqualTo(0.2)
        assertThat(KeyMetrics.mirexScore(MusicalKey.C_MAJOR, MusicalKey.C_MINOR)).isEqualTo(0.2)
        assertThat(KeyMetrics.mirexScore(MusicalKey.F_SHARP_MAJOR, MusicalKey.F_SHARP_MINOR)).isEqualTo(0.2)
    }

    @Test
    fun `anything else scores zero`() {
        assertThat(KeyMetrics.mirexScore(MusicalKey.D_MAJOR, ref)).isEqualTo(0.0)
        assertThat(KeyMetrics.mirexScore(MusicalKey.F_SHARP_MAJOR, ref)).isEqualTo(0.0)
        assertThat(KeyMetrics.mirexScore(MusicalKey.E_MINOR, ref)).isEqualTo(0.0)
        // A fifth with the wrong mode is not a fifth: G minor against C major.
        assertThat(KeyMetrics.mirexScore(MusicalKey.G_MINOR, ref)).isEqualTo(0.0)
    }

    @Test
    fun `a missing key on either side scores zero`() {
        assertThat(KeyMetrics.mirexScore(null, ref)).isEqualTo(0.0)
        assertThat(KeyMetrics.mirexScore(ref, null)).isEqualTo(0.0)
        assertThat(KeyMetrics.mirexScore(null, null)).isEqualTo(0.0)
    }

    @Test
    fun `tonic and mode follow the qm-dsp index`() {
        assertThat(KeyMetrics.tonic(MusicalKey.C_MAJOR)).isEqualTo(0)
        assertThat(KeyMetrics.tonic(MusicalKey.B_MAJOR)).isEqualTo(11)
        assertThat(KeyMetrics.tonic(MusicalKey.C_MINOR)).isEqualTo(0)
        assertThat(KeyMetrics.tonic(MusicalKey.A_MINOR)).isEqualTo(9)
        assertThat(KeyMetrics.tonic(MusicalKey.B_MINOR)).isEqualTo(11)
    }

    @Test
    fun `mean score over pairs`() {
        val mean = KeyMetrics.meanScore(
            listOf(
                MusicalKey.C_MAJOR to MusicalKey.C_MAJOR, // 1.0
                MusicalKey.G_MAJOR to MusicalKey.C_MAJOR, // 0.5
                MusicalKey.D_MAJOR to MusicalKey.C_MAJOR, // 0.0
                null to MusicalKey.C_MAJOR, // 0.0
            ),
        )
        assertThat(mean).isWithin(1e-12).of(0.375)
        assertThat(KeyMetrics.meanScore(emptyList())).isNaN()
    }
}
