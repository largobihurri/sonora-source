package io.sonora.core.domain.equalizer

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EqualizerResponseTest {

    private fun gains(mode: EqualizerMode, vararg set: Pair<Int, Float>): List<Float> =
        MutableList(mode.bandCount) { 0f }.apply { set.forEach { (band, gain) -> this[band] = gain } }

    @Test
    fun `a flat curve is 0 dB everywhere and needs no preamp`() {
        for (mode in EqualizerMode.entries) {
            val bands = EqualizerLayout.bands(mode)
            val flat = gains(mode)

            for (f in listOf(20.0, 100.0, 1_000.0, 10_000.0, 20_000.0)) {
                assertThat(EqualizerResponse.responseDb(bands, flat, f, 48_000)).isWithin(1e-9).of(0.0)
            }
            assertThat(EqualizerResponse.preampDb(bands, flat, 48_000)).isEqualTo(0.0)
        }
    }

    @Test
    fun `a single peaking band reaches its gain at its centre`() {
        val mode = EqualizerMode.ELEVEN_BANDS
        val bands = EqualizerLayout.bands(mode)

        for (band in 1 until bands.size - 1) {
            val curve = gains(mode, band to 7.5f)
            assertThat(EqualizerResponse.responseDb(bands, curve, bands[band].frequencyHz, 48_000)).isWithin(0.1).of(7.5)
        }
    }

    @Test
    fun `the preamp takes away the curve's highest boost, including overlapping bands`() {
        val mode = EqualizerMode.TWENTY_BANDS
        val bands = EqualizerLayout.bands(mode)
        val single = gains(mode, 10 to 6f)
        val neighbours = gains(mode, 10 to 12f, 11 to 12f)

        assertThat(EqualizerResponse.preampDb(bands, single, 48_000)).isWithin(0.05).of(-6.0)
        // Two boosted neighbours add up between them, so the headroom is larger than either slider.
        assertThat(EqualizerResponse.preampDb(bands, neighbours, 48_000)).isLessThan(-12.0)
        assertThat(-EqualizerResponse.preampDb(bands, neighbours, 48_000))
            .isAtLeast(EqualizerResponse.responseDb(bands, neighbours, 1_200.0, 48_000))
    }

    @Test
    fun `cuts need no preamp`() {
        val mode = EqualizerMode.FOUR_BANDS
        val bands = EqualizerLayout.bands(mode)

        assertThat(EqualizerResponse.preampDb(bands, gains(mode, 0 to -12f, 3 to -6f), 44_100)).isEqualTo(0.0)
    }

    @Test
    fun `at high sample rates the preamp also covers the treble shelf above 22 kHz`() {
        val mode = EqualizerMode.FOUR_BANDS
        val bands = EqualizerLayout.bands(mode)
        val treble = gains(mode, 3 to 12f)

        for (rate in listOf(48_000, 96_000, 192_000)) {
            val nearNyquist = 0.49 * rate
            val highest = EqualizerResponse.responseDb(bands, treble, nearNyquist, rate)
            assertThat(-EqualizerResponse.preampDb(bands, treble, rate)).isAtLeast(highest)
        }
        assertThat(EqualizerResponse.preampDb(bands, treble, 192_000)).isWithin(0.01).of(-12.0)
    }
}
