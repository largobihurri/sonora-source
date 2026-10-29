package io.sonora.tools.calibration.runner

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.analysis.MusicalKey
import io.sonora.tools.calibration.BandOrderCandidate
import io.sonora.tools.calibration.BandOrderResult
import io.sonora.tools.calibration.BarPhaseAgreement
import io.sonora.tools.calibration.BeatScore
import io.sonora.tools.calibration.DownbeatScore
import io.sonora.tools.calibration.OctaveClass
import org.junit.Test
import java.util.Locale

class CalibrationReportTest {

    @Test
    fun `csv has a header and one quoted row per track`() {
        val csv = CalibrationReport.csv(listOf(result(path = "Contents/A, \"B\"/c.mp3")))
        val lines = csv.trimEnd().lines()

        assertThat(lines).hasSize(2)
        assertThat(lines[0]).startsWith("path,format,config,ref_bpm,ref_variable,our_bpm,octave,segments,f,precision,recall,f_trim,")
        assertThat(lines[1]).startsWith("\"Contents/A, \"\"B\"\"/c.mp3\",mp3,baseline,128.00,false,128.00,EXACT,1,")
        assertThat(lines[1].split(',').size).isAtLeast(lines[0].split(',').size)
    }

    @Test
    fun `csv numbers use a dot in any locale and NaN is empty`() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
        try {
            val csv = CalibrationReport.csv(listOf(result(ourBpm = Double.NaN, octave = null, segments = 0)))
            val row = csv.trimEnd().lines()[1]
            assertThat(row).contains(",128.00,false,,,0,")
            assertThat(row).doesNotContain("128,00")
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `aggregates count accuracy 1 and 2 with a missing grid as a miss`() {
        val aggregates = CalibrationReport.aggregates(
            listOf(
                result(octave = OctaveClass.EXACT, f = 1.0),
                result(octave = OctaveClass.HALF, f = 0.5),
                result(octave = null, ourBpm = Double.NaN, segments = 0, f = 0.0),
            ),
        )
        assertThat(aggregates.tracks).isEqualTo(3)
        assertThat(aggregates.withGrid).isEqualTo(2)
        assertThat(aggregates.acc1).isWithin(1e-9).of(1.0 / 3)
        assertThat(aggregates.acc2).isWithin(1e-9).of(2.0 / 3)
        assertThat(aggregates.meanF).isWithin(1e-9).of(0.5)
    }

    @Test
    fun `bar phase aggregates count the accurate-beat tracks whose bar phase is right`() {
        val aggregates = CalibrationReport.aggregates(
            listOf(
                result(f = 0.99, barPhase = BarPhaseAgreement(100, 1.0, 0, 1.0)),
                result(f = 0.95, barPhase = BarPhaseAgreement(100, 0.0, 2, 1.0)),
                result(f = 0.91, barPhase = BarPhaseAgreement(100, 0.25, 3, 0.5)),
                // Beats too far off to judge the bar phase: not in the subset.
                result(f = 0.5, barPhase = BarPhaseAgreement(100, 1.0, 0, 1.0)),
                // Exactly 0.9 is not above it.
                result(f = 0.9, barPhase = BarPhaseAgreement(100, 1.0, 0, 1.0)),
            ),
        )
        assertThat(aggregates.beatAccurateTracks).isEqualTo(3)
        assertThat(aggregates.beatAccurateRightPhase).isEqualTo(1)
    }

    @Test
    fun `the summary reports the bar phase of the accurate-beat tracks`() {
        val text = CalibrationReport.markdown(
            title = "t",
            results = listOf(result(f = 0.99), result(f = 0.95, barPhase = BarPhaseAgreement(100, 0.0, 2, 1.0))),
            notes = emptyList(),
        )
        assertThat(text).containsMatch("bar phase right on tracks with F above 0\\.9 +\\| +1 of 2")
    }

    @Test
    fun `key aggregates skip tracks without a reference key`() {
        val aggregates = CalibrationReport.aggregates(
            listOf(
                result(referenceKey = MusicalKey.A_MINOR, ourKey = MusicalKey.A_MINOR, keyScore = 1.0),
                result(referenceKey = MusicalKey.A_MINOR, ourKey = MusicalKey.C_MAJOR, keyScore = 0.3),
                result(referenceKey = null, ourKey = MusicalKey.C_MAJOR, keyScore = Double.NaN),
            ),
        )
        assertThat(aggregates.keyTracks).isEqualTo(2)
        assertThat(aggregates.keyMirex).isWithin(1e-9).of(0.65)
        assertThat(aggregates.keyExact).isWithin(1e-9).of(0.5)
    }

    @Test
    fun `the file order names which of our bands each stored plane holds`() {
        // Our low is plane 2, our mid plane 0, our high plane 1: the file stores mid, high, low.
        assertThat(CalibrationReport.fileOrder(listOf(2, 0, 1))).isEqualTo("mid, high, low")
        assertThat(CalibrationReport.fileOrder(listOf(0, 1, 2))).isEqualTo("low, mid, high")
    }

    @Test
    fun `band order votes rank the stored orders by mean correlation`() {
        val votes = CalibrationReport.bandOrderVotes(
            listOf(
                result(bandOrder = order(best = listOf(2, 0, 1), bestMean = 0.8)),
                result(bandOrder = order(best = listOf(2, 0, 1), bestMean = 0.7)),
                result(bandOrder = order(best = listOf(0, 1, 2), bestMean = 0.5)),
                result(bandOrder = null),
            ),
        ) { it.bandOrder }
        assertThat(votes.first().fileOrder).isEqualTo("mid, high, low")
        assertThat(votes.first().wins).isEqualTo(2)
        assertThat(votes.first().tracks).isEqualTo(3)
        assertThat(votes.sumOf { it.wins }).isEqualTo(3)
    }

    @Test
    fun `the band matrix recovers each band's correlation with each stored byte`() {
        val m = 0.0
        val candidates = listOf(listOf(0, 1, 2), listOf(0, 2, 1), listOf(1, 0, 2), listOf(1, 2, 0), listOf(2, 0, 1), listOf(2, 1, 0)).map { a ->
            // matrix[i][j] = 0.1 * (3 * i + j): every pairing reads its cells off the same matrix.
            val correlations = a.mapIndexed { i, j -> 0.1 * (3 * i + j) + m }
            BandOrderCandidate(a, correlations, correlations.average())
        }.sortedByDescending { it.meanCorrelation }
        val matrix = CalibrationReport.bandMatrix(listOf(result(bandOrder = BandOrderResult(candidates)))) { it.bandOrder }
        assertThat(matrix[0][0]).isWithin(1e-9).of(0.0)
        assertThat(matrix[1][2]).isWithin(1e-9).of(0.5)
        assertThat(matrix[2][1]).isWithin(1e-9).of(0.7)
    }

    @Test
    fun `markdown has the summary the per-format offsets and the band order verdict`() {
        val text = CalibrationReport.markdown(
            title = "D1.20 calibration",
            results = listOf(
                result(path = "a.mp3", format = "mp3", offsets = doubleArrayOf(20.0, 22.0, 24.0), bandOrder = order(listOf(2, 0, 1), 0.8)),
                result(path = "b.wav", format = "wav", offsets = doubleArrayOf(-1.0, 1.0), bandOrder = order(listOf(2, 0, 1), 0.7)),
            ),
            notes = listOf("Five tracks, harness check."),
        )
        assertThat(text).startsWith("# D1.20 calibration")
        assertThat(text).contains("Five tracks, harness check.")
        assertThat(text).contains("## Summary")
        assertThat(text).contains("## Beat offsets per format")
        assertThat(text).containsMatch("\\| mp3 +\\| +3 \\| +22\\.0")
        assertThat(text).contains("## Band order")
        assertThat(text).contains("mid, high, low")
        assertThat(text).contains("byte 0")
        assertThat(text).contains("## Tracks")
    }

    @Test
    fun `comparison lists each configuration with its aggregates`() {
        val text = CalibrationReport.comparison(
            listOf(
                "baseline" to CalibrationReport.aggregates(listOf(result(f = 0.5))),
                "wide-prior" to CalibrationReport.aggregates(listOf(result(f = 0.9))),
            ),
        )
        assertThat(text).contains("baseline")
        assertThat(text).contains("wide-prior")
        assertThat(text).contains("0.900")
    }

    private fun order(best: List<Int>, bestMean: Double): BandOrderResult {
        val permutations = listOf(listOf(0, 1, 2), listOf(0, 2, 1), listOf(1, 0, 2), listOf(1, 2, 0), listOf(2, 0, 1), listOf(2, 1, 0))
        val ranked = listOf(BandOrderCandidate(best, listOf(bestMean, bestMean, bestMean), bestMean)) +
            permutations.filter { it != best }.map { BandOrderCandidate(it, listOf(0.1, 0.1, 0.1), 0.1) }
        return BandOrderResult(ranked)
    }

    private fun result(
        path: String = "Contents/a.mp3",
        format: String = "mp3",
        ourBpm: Double = 128.0,
        octave: OctaveClass? = OctaveClass.EXACT,
        segments: Int = 1,
        f: Double = 1.0,
        offsets: DoubleArray = doubleArrayOf(1.0),
        referenceKey: MusicalKey? = MusicalKey.A_MINOR,
        ourKey: MusicalKey? = MusicalKey.A_MINOR,
        keyScore: Double = 1.0,
        bandOrder: BandOrderResult? = null,
        barPhase: BarPhaseAgreement = BarPhaseAgreement(100, 1.0, 0, 1.0),
    ): TrackResult {
        // A score with the requested F: matched m of n on both sides gives P = R = F = m / n.
        val n = 100
        val matched = Math.round(f * n).toInt()
        val beat = BeatScore(matched, n, n, offsets)
        return TrackResult(
            path = path,
            format = format,
            config = "baseline",
            referenceBpm = 128.0,
            referenceVariable = false,
            ourBpm = ourBpm,
            octave = octave,
            segments = segments,
            beat = beat,
            beatTrimmed = beat,
            downbeat = DownbeatScore(10, 10, 10),
            barPhase = barPhase,
            referenceKey = referenceKey,
            ourKey = ourKey,
            keyScore = keyScore,
            bandOrder = bandOrder,
            previewBandOrder = null,
            waveformCorrelations = mapOf("pwv5.height~peak" to 0.8),
            waveformLagMs = 0.0,
            analysisSeconds = 20.0,
            audioSeconds = 300.0,
            temperatureC = 70.0,
        )
    }
}
