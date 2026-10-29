package io.sonora.core.djengine.mel

import com.google.common.truth.Truth.assertThat
import io.sonora.core.djengine.beats.BeatThisCase
import kotlin.math.abs
import org.junit.Test

class MelFilterbankTest {

    private val bank = MelFilterbank(22_050, 1_024, 128, 30.0, 11_000.0)

    @Test
    fun `the filterbank has 513 bins and 128 bands`() {
        assertThat(bank.binCount).isEqualTo(513)
        assertThat(bank.bandCount).isEqualTo(128)
    }

    @Test
    fun `every weight matches the golden filterbank file`() {
        val golden = BeatThisCase.melFilterbank()
        var worst = 0.0
        for (bin in 0 until 513) {
            for (band in 0 until 128) {
                worst = maxOf(worst, abs(bank.weight(bin, band) - golden[bin * 128 + band]))
            }
        }
        // The file is the float64 reference rounded to float32 (relative 6e-8 on weights of at most 1).
        assertThat(worst).isLessThan(1e-6)
    }

    @Test
    fun `weights are triangles with a peak of at most one and no area normalisation`() {
        for (band in 0 until 128) {
            val weights = DoubleArray(513) { bank.weight(it, band) }
            assertThat(weights.all { it >= 0.0 && it <= 1.0 }).isTrue()
            assertThat(weights.max()).isGreaterThan(0.5)
        }
        // Beyond the 11 kHz edge of the last band and below the 30 Hz edge of the first one there is no weight.
        assertThat((0 until 128).sumOf { bank.weight(0, it) }).isEqualTo(0.0)
        assertThat((0 until 128).sumOf { bank.weight(512, it) }).isEqualTo(0.0)
    }

    @Test
    fun `project equals the dense matrix product`() {
        val magnitudes = DoubleArray(513) { 1.0 + (it * 37 % 11) * 0.25 }
        val bands = DoubleArray(128)

        bank.project(magnitudes, bands)

        for (band in 0 until 128) {
            val expected = (0 until 513).sumOf { magnitudes[it] * bank.weight(it, band) }
            assertThat(abs(bands[band] - expected)).isLessThan(1e-9)
        }
    }
}
