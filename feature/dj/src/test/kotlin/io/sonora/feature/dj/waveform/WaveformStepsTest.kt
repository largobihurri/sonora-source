package io.sonora.feature.dj.waveform

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WaveformStepsTest {

    @Test
    fun `zooming in shows a shorter span, down to 4 seconds`() {
        assertThat(WaveformZoom.SECONDS_32.zoomedIn()).isEqualTo(WaveformZoom.SECONDS_16)
        assertThat(WaveformZoom.SECONDS_8.zoomedIn()).isEqualTo(WaveformZoom.SECONDS_4)
        assertThat(WaveformZoom.SECONDS_4.zoomedIn()).isNull()
    }

    @Test
    fun `zooming out shows a longer span, up to 32 seconds`() {
        assertThat(WaveformZoom.SECONDS_4.zoomedOut()).isEqualTo(WaveformZoom.SECONDS_8)
        assertThat(WaveformZoom.SECONDS_16.zoomedOut()).isEqualTo(WaveformZoom.SECONDS_32)
        assertThat(WaveformZoom.SECONDS_32.zoomedOut()).isNull()
    }

    @Test
    fun `a skip moves by the step inside the track`() {
        assertThat(SkipSeek.skipped(30_000, SkipSeek.STEP_MS, 200_000)).isEqualTo(40_000)
        assertThat(SkipSeek.skipped(30_000, -SkipSeek.STEP_MS, 200_000)).isEqualTo(20_000)
    }

    @Test
    fun `a skip stops at the start and the end`() {
        assertThat(SkipSeek.skipped(5_000, -SkipSeek.STEP_MS, 200_000)).isEqualTo(0)
        assertThat(SkipSeek.skipped(195_000, SkipSeek.STEP_MS, 200_000)).isEqualTo(200_000)
    }

    @Test
    fun `a skip in a track of unknown length only stops at the start`() {
        assertThat(SkipSeek.skipped(30_000, SkipSeek.STEP_MS, 0)).isEqualTo(40_000)
        assertThat(SkipSeek.skipped(3_000, -SkipSeek.STEP_MS, 0)).isEqualTo(0)
    }

    @Test
    fun `the skip step is ten seconds`() {
        assertThat(SkipSeek.STEP_MS).isEqualTo(10_000)
    }
}
