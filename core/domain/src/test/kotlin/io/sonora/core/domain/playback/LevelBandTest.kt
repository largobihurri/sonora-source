package io.sonora.core.domain.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LevelBandTest {

    @Test
    fun `the bands tile 20 Hz to 16 kHz without gaps, low to high`() {
        val bands = LevelBand.entries

        assertThat(bands.first().lowHz).isEqualTo(20.0)
        assertThat(bands.last().highHz).isEqualTo(16_000.0)
        bands.zipWithNext().forEach { (low, high) -> assertThat(high.lowHz).isEqualTo(low.highHz) }
        bands.forEach { assertThat(it.highHz).isGreaterThan(it.lowHz) }
        assertThat(PlaybackBandLevels.BAND_COUNT).isEqualTo(4)
    }
}
