package io.sonora.player.wear.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NowPlayingMathTest {

    @Test
    fun `progress is the position over the duration`() {
        assertThat(progressFraction(positionMs = 30_000L, durationMs = 120_000L)).isEqualTo(0.25f)
    }

    @Test
    fun `progress is empty when the duration is unknown or zero`() {
        assertThat(progressFraction(positionMs = 5_000L, durationMs = null)).isEqualTo(0f)
        assertThat(progressFraction(positionMs = 5_000L, durationMs = 0L)).isEqualTo(0f)
    }

    @Test
    fun `progress stays within the ring`() {
        assertThat(progressFraction(positionMs = 130_000L, durationMs = 120_000L)).isEqualTo(1f)
        assertThat(progressFraction(positionMs = -10L, durationMs = 120_000L)).isEqualTo(0f)
    }

    @Test
    fun `volume percent is the level over the maximum`() {
        assertThat(volumePercent(level = 5, max = 15)).isEqualTo(33)
        assertThat(volumePercent(level = 15, max = 15)).isEqualTo(100)
        assertThat(volumePercent(level = 0, max = 15)).isEqualTo(0)
    }

    @Test
    fun `volume percent copes with a zero maximum and out of range levels`() {
        assertThat(volumePercent(level = 3, max = 0)).isEqualTo(100)
        assertThat(volumePercent(level = 20, max = 15)).isEqualTo(100)
        assertThat(volumePercent(level = -1, max = 15)).isEqualTo(0)
    }
}
