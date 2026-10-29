package io.sonora.feature.settings.duplicates

import com.google.common.truth.Truth.assertThat
import io.sonora.feature.settings.duplicates.ComparisonLabels.Fit
import org.junit.Assert.assertThrows
import org.junit.Test

class ComparisonLabelsTest {

    @Test
    fun `labels wrapped at a space or after a hyphen keep their words whole`() {
        assertThat(ComparisonLabels.wrapsBetweenWords("Frecuencia de muestreo", listOf(0, 11))).isTrue()
        assertThat(ComparisonLabels.wrapsBetweenWords("Frecuencia de muestreo", listOf(0, 11, 14))).isTrue()
        assertThat(ComparisonLabels.wrapsBetweenWords("Lagin-maiztasuna", listOf(0, 6))).isTrue()
        assertThat(ComparisonLabels.wrapsBetweenWords("Format", listOf(0))).isTrue()
    }

    @Test
    fun `a label whose line starts inside a word splits it`() {
        // "Profundida" / "d de bits": the device's 13 sp layout in the 80 dp label column.
        assertThat(ComparisonLabels.wrapsBetweenWords("Profundidad de bits", listOf(0, 10))).isFalse()
    }

    @Test
    fun `the column is as bad as its worst label`() {
        assertThat(ComparisonLabels.worst(listOf(Fit.Fits, Fit.SplitsWord, Fit.Fits))).isEqualTo(Fit.SplitsWord)
        assertThat(ComparisonLabels.worst(listOf(Fit.Fits, Fit.SplitsWord, Fit.Cut))).isEqualTo(Fit.Cut)
        assertThat(ComparisonLabels.worst(listOf(Fit.Fits, Fit.Fits))).isEqualTo(Fit.Fits)
    }

    @Test
    fun `the labels take the largest size at which every word stays whole`() {
        val tried = mutableListOf<Float>()
        val size = ComparisonLabels.fontSize(max = 13f, min = 11f, step = 0.5f) {
            tried += it
            if (it > 12.5f) Fit.SplitsWord else Fit.Fits
        }
        assertThat(size).isEqualTo(12.5f)
        assertThat(tried).containsExactly(13f, 12.5f).inOrder()
    }

    @Test
    fun `a word too wide at every size keeps the largest size that is not cut`() {
        val size = ComparisonLabels.fontSize(max = 13f, min = 11f, step = 0.5f) { if (it > 12f) Fit.Cut else Fit.SplitsWord }
        assertThat(size).isEqualTo(12f)
    }

    @Test
    fun `the smallest size is the floor when every size is cut`() {
        assertThat(ComparisonLabels.fontSize(max = 13f, min = 11f, step = 0.5f) { Fit.Cut }).isEqualTo(11f)
    }

    @Test
    fun `a step that is not positive is refused`() {
        assertThrows(IllegalArgumentException::class.java) { ComparisonLabels.fontSize(max = 13f, min = 11f, step = 0f) { Fit.Fits } }
    }
}
