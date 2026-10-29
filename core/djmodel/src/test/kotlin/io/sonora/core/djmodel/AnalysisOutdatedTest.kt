package io.sonora.core.djmodel

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AnalysisOutdatedTest {

    private fun analysis(version: Int) = DjAnalysis(
        durationMs = 1_000,
        grid = null,
        key = null,
        waveform = BandWaveform.MISSING,
        provenance = AnalysisProvenance(analyzerVersion = version, beatSource = BeatSource.QM_DSP, analysedAtEpochMs = 1),
    )

    @Test
    fun `an analysis from an older analyser is outdated`() {
        assertThat(analysis(version = 1).isOlderThan(currentVersion = 2)).isTrue()
    }

    @Test
    fun `an analysis from the current analyser is not`() {
        assertThat(analysis(version = 2).isOlderThan(currentVersion = 2)).isFalse()
    }

    @Test
    fun `an analysis from a later analyser is not`() {
        assertThat(analysis(version = 3).isOlderThan(currentVersion = 2)).isFalse()
    }
}
