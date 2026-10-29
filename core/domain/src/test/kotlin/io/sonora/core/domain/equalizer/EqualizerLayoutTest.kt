package io.sonora.core.domain.equalizer

import com.google.common.truth.Truth.assertThat
import kotlin.math.log2
import org.junit.Test

class EqualizerLayoutTest {

    @Test
    fun `four bands are sub-bass, bass, mids and treble`() {
        val bands = EqualizerLayout.bands(EqualizerMode.FOUR_BANDS)

        assertThat(bands.map { it.shape })
            .containsExactly(BandShape.LOW_SHELF, BandShape.PEAKING, BandShape.PEAKING, BandShape.HIGH_SHELF).inOrder()
        assertThat(bands.map { it.frequencyHz }).containsExactly(60.0, 150.0, 1_000.0, 8_000.0).inOrder()
        // The mids band is wide.
        assertThat(bands[2].q).isLessThan(1.0)
    }

    @Test
    fun `eleven bands sit an octave apart from 16 Hz to 16 kHz`() {
        val bands = EqualizerLayout.bands(EqualizerMode.ELEVEN_BANDS)

        assertThat(bands).hasSize(11)
        assertThat(bands.first().frequencyHz).isWithin(1.0).of(16.0)
        assertThat(bands.last().frequencyHz).isWithin(1.0).of(16_000.0)
        bands.zipWithNext().forEach { (a, b) -> assertThat(log2(b.frequencyHz / a.frequencyHz)).isWithin(1e-9).of(1.0) }
        assertThat(bands.map { it.nominalHz }).containsExactly(
            16.0, 31.0, 63.0, 125.0, 250.0, 500.0, 1_000.0, 2_000.0, 4_000.0, 8_000.0, 16_000.0,
        ).inOrder()
    }

    @Test
    fun `twenty bands sit half an octave apart up to 16 kHz`() {
        val bands = EqualizerLayout.bands(EqualizerMode.TWENTY_BANDS)

        assertThat(bands).hasSize(20)
        assertThat(bands[1].frequencyHz).isWithin(0.5).of(31.25)
        assertThat(bands.last().frequencyHz).isWithin(1.0).of(16_000.0)
        bands.zipWithNext().forEach { (a, b) -> assertThat(log2(b.frequencyHz / a.frequencyHz)).isWithin(1e-9).of(0.5) }
        assertThat(bands.map { it.nominalHz }.take(5)).containsExactly(22.0, 31.0, 45.0, 63.0, 90.0).inOrder()
    }

    @Test
    fun `graphic modes have shelves at both ends and peaking bands between`() {
        for (mode in listOf(EqualizerMode.ELEVEN_BANDS, EqualizerMode.TWENTY_BANDS)) {
            val shapes = EqualizerLayout.bands(mode).map { it.shape }

            assertThat(shapes.first()).isEqualTo(BandShape.LOW_SHELF)
            assertThat(shapes.last()).isEqualTo(BandShape.HIGH_SHELF)
            assertThat(shapes.subList(1, shapes.size - 1).toSet()).containsExactly(BandShape.PEAKING)
        }
    }

    @Test
    fun `every mode has as many bands as it says`() {
        for (mode in EqualizerMode.entries) assertThat(EqualizerLayout.bands(mode)).hasSize(mode.bandCount)
        assertThat(EqualizerLayout.MAX_BANDS).isEqualTo(20)
    }
}
