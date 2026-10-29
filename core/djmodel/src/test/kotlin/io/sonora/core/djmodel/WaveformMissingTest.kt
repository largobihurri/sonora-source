package io.sonora.core.djmodel

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WaveformMissingTest {

    private val provenance = AnalysisProvenance(analyzerVersion = 1, beatSource = BeatSource.QM_DSP, analysedAtEpochMs = 5)

    private fun plane(size: Int) = ByteArray(size)

    @Test
    fun `the missing placeholder has no columns`() {
        assertThat(BandWaveform.MISSING.columnCount).isEqualTo(0)
    }

    @Test
    fun `an analysis with the placeholder and some audio needs its waveform`() {
        val restored = DjAnalysis(durationMs = 240_000, grid = null, key = null, waveform = BandWaveform.MISSING, provenance = provenance)

        assertThat(restored.waveformMissing).isTrue()
    }

    @Test
    fun `an analysis with a waveform does not`() {
        val whole = DjAnalysis(1_000, null, null, BandWaveform(plane(150), plane(150), plane(150), plane(150), version = 1), provenance)

        assertThat(whole.waveformMissing).isFalse()
    }

    @Test
    fun `a track without audio has nothing to compute`() {
        assertThat(DjAnalysis(0, null, null, BandWaveform.MISSING, provenance).waveformMissing).isFalse()
    }
}
