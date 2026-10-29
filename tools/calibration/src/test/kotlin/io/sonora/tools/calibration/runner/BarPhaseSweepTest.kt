package io.sonora.tools.calibration.runner

import com.google.common.truth.Truth.assertThat
import io.sonora.tools.calibration.BarPhaseAgreement
import io.sonora.tools.calibration.BeatScore
import io.sonora.tools.calibration.DownbeatScore
import io.sonora.tools.calibration.OctaveClass
import org.junit.Test

class BarPhaseSweepTest {

    private fun result(path: String, f: Double, shift: Int, downbeat: Double = if (shift == 0) 1.0 else 0.0): TrackResult {
        val matched = Math.round(f * 100).toInt()
        val beat = BeatScore(matched, 100, 100, doubleArrayOf(0.0))
        return TrackResult(
            path = path, format = "wav", config = "x", referenceBpm = 128.0, referenceVariable = false, ourBpm = 128.0,
            octave = OctaveClass.EXACT, segments = 1, beat = beat, beatTrimmed = beat,
            downbeat = DownbeatScore((downbeat * 10).toInt(), 10, 10),
            barPhase = BarPhaseAgreement(100, if (shift == 0) 1.0 else 0.0, shift, 1.0),
            referenceKey = null, ourKey = null, keyScore = Double.NaN, bandOrder = null, previewBandOrder = null,
            waveformCorrelations = emptyMap(), waveformLagMs = Double.NaN, analysisSeconds = 1.0, audioSeconds = 100.0, temperatureC = 60.0,
        )
    }

    @Test
    fun `a variant is scored by its downbeats and the right bar phases of the accurate-beat tracks`() {
        val baseline = listOf(result("a", 0.99, shift = 0), result("b", 0.95, shift = 2), result("c", 0.5, shift = 1))

        val score = BarPhaseSweep.score("base", baseline, baseline)

        assertThat(score.accurateTracks).isEqualTo(2)
        assertThat(score.rightPhase).isEqualTo(1)
        assertThat(score.downbeatAccuracy).isWithin(1e-9).of(1.0 / 3)
        assertThat(score.fixed).isEqualTo(0)
        assertThat(score.broken).isEqualTo(0)
    }

    @Test
    fun `fixed and broken tracks are counted against the baseline on the accurate-beat tracks only`() {
        val baseline = listOf(result("a", 0.99, shift = 0), result("b", 0.95, shift = 2), result("c", 0.95, shift = 3), result("d", 0.4, shift = 1))
        val variant = listOf(result("a", 0.99, shift = 2), result("b", 0.95, shift = 0), result("c", 0.95, shift = 0), result("d", 0.4, shift = 0))

        val score = BarPhaseSweep.score("variant", variant, baseline)

        assertThat(score.rightPhase).isEqualTo(2)
        assertThat(score.fixed).isEqualTo(2)
        assertThat(score.broken).isEqualTo(1)
    }

    @Test
    fun `the variants start with the old and the new defaults and vary one setting at a time`() {
        val names = BarPhaseSweep.variants.map { it.name }

        assertThat(names.first()).isEqualTo(CalibrationConfig.PRE_D1_29.name)
        assertThat(names[1]).isEqualTo(CalibrationConfig.BASELINE.name)
        assertThat(names.toSet()).hasSize(names.size)
        assertThat(BarPhaseSweep.variants.first().barPhase).isNull()
        assertThat(BarPhaseSweep.variants.drop(1).all { it.barPhase != null }).isTrue()
    }

    @Test
    fun `confidence quartiles say how often the phase was right in each fourth`() {
        val rows = (1..8).map { i -> (i / 10.0) to (i % 2 == 0) }

        val quartiles = BarPhaseSweep.confidenceQuartiles(rows)

        assertThat(quartiles).hasSize(4)
        assertThat(quartiles.map { it.tracks }).containsExactly(2, 2, 2, 2)
        assertThat(quartiles.map { it.right }).containsExactly(1, 1, 1, 1)
        assertThat(quartiles.first().minConfidence).isWithin(1e-9).of(0.1)
        assertThat(quartiles.last().maxConfidence).isWithin(1e-9).of(0.8)
    }

    @Test
    fun `the markdown lists every variant and the confidence quartiles`() {
        val baseline = listOf(result("a", 0.99, shift = 0), result("b", 0.95, shift = 2))
        val text = BarPhaseSweep.markdown(
            scores = listOf(BarPhaseSweep.score("pre", baseline, baseline)),
            confidence = listOf(0.1 to true, 0.2 to false, 0.3 to true, 0.4 to true),
        )

        assertThat(text).contains("## Bar phase")
        assertThat(text).containsMatch("\\| pre +\\| +0\\.500 ")
        assertThat(text).contains("## Confidence")
    }
}
