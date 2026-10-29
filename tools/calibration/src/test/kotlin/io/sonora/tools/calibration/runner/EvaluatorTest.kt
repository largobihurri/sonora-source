package io.sonora.tools.calibration.runner

import com.google.common.truth.Truth.assertThat
import io.sonora.core.djengine.beats.TempoPrior
import io.sonora.core.djengine.key.ChromaFrames
import io.sonora.core.djengine.key.KeyProfile
import io.sonora.core.djengine.pipeline.DjAnalysisPipeline
import io.sonora.core.djmodel.BandWaveform
import io.sonora.core.djmodel.BeatCandidates
import io.sonora.core.domain.analysis.MusicalKey
import io.sonora.core.rekordbox.anlz.AnlzBeat
import io.sonora.tools.calibration.OctaveClass
import org.junit.Test
import kotlin.random.Random

class EvaluatorTest {

    private val durationMs = 60_000.0
    private val beatMs = 60_000.0 / 128

    /** Rekordbox's grid: 128 BPM from 250 ms, bar starting on the first beat. */
    private val referenceBeats = (0 until 127).map { AnlzBeat(it % 4 + 1, 12_800, Math.round(250 + it * beatMs)) }

    /** The baseline without the model latency correction, so perfect candidates score a zero offset. */
    private val exact = CalibrationConfig.BASELINE.copy(name = "exact", modelLatencyMs = 0.0)

    private val random = Random(3)
    private val low = ByteArray(9_000) { random.nextInt(256).toByte() }
    private val mid = ByteArray(9_000) { random.nextInt(256).toByte() }
    private val high = ByteArray(9_000) { random.nextInt(256).toByte() }

    private fun capture(beatOffsetMs: Double = 0.0, tempoFactor: Double = 1.0): TrackCapture {
        val interval = beatMs / tempoFactor
        val count = ((durationMs - 250) / interval).toInt()
        val beats = DoubleArray(count) { 250 + it * interval + beatOffsetMs }
        val downbeats = beats.filterIndexed { i, _ -> i % 4 == 0 }.toDoubleArray()
        return TrackCapture(
            path = "Contents/x.wav",
            format = "wav",
            sampleRateHz = 44_100,
            channelCount = 2,
            durationMs = durationMs,
            candidates = BeatCandidates(beats, downbeats),
            envelope = null,
            chroma = aMinorChroma(),
            waveform = BandWaveform(low, mid, high, ByteArray(low.size) { (maxOf(it % 200, 1)).toByte() }, version = 1),
            pipelineGrid = null,
            pipelineKey = null,
            beatSourceId = "beatthis-small/1",
            stageMs = emptyMap(),
            wallMs = 1_000,
        )
    }

    /** Frames whose 36 bins follow the edma A minor template on the centre bin of each pitch class. */
    private fun aMinorChroma(): ChromaFrames {
        val values = FloatArray(36 * 20)
        for (frame in 0 until 20) for (pc in 0 until 12) {
            // A is pitch class 9: rotate the A-rooted template onto the absolute pitch classes.
            values[frame * 36 + 3 * pc] = KeyProfile.EDMA.weight(minor = true, degree = Math.floorMod(pc - 9, 12)).toFloat()
        }
        return ChromaFrames(values, 10.0)
    }

    private val reference = ReferenceTrack(
        path = "Contents/x.wav",
        format = "wav",
        pdbBpm = 128.0,
        grid = ReferenceGrid.fromAnlz(referenceBeats),
        keyName = "Am",
        key = MusicalKey.A_MINOR,
        // A permuted file order (mid, high, low), so the test shows the evaluator finds the order instead of assuming
        // it. Real PWV7 files store low, mid, high (the D1.20 verdict).
        threeBandDetail = listOf(mid, high, low),
        threeBandPreview = null,
        colorDetailHeight = null,
        monoDetailHeight = null,
        colorPreview = null,
    )

    @Test
    fun `beats on the reference grid score perfectly`() {
        val result = Evaluator.evaluate(capture(), reference, exact, analysisSeconds = 1.0, temperatureC = 60.0)

        assertThat(result.ourBpm).isWithin(0.01).of(128.0)
        assertThat(result.octave).isEqualTo(OctaveClass.EXACT)
        assertThat(result.beat.fMeasure).isWithin(0.02).of(1.0)
        assertThat(result.beat.offsets.median).isWithin(1.0).of(0.0)
        assertThat(result.downbeat.accuracy).isWithin(0.05).of(1.0)
        assertThat(result.barPhase.agreement).isWithin(0.05).of(1.0)
        assertThat(result.keyScore).isEqualTo(1.0)
        assertThat(result.ourKey).isEqualTo(MusicalKey.A_MINOR)
    }

    @Test
    fun `the band order of the stored planes is found`() {
        val result = Evaluator.evaluate(capture(), reference, CalibrationConfig.BASELINE, analysisSeconds = 1.0, temperatureC = 60.0)
        assertThat(result.bandOrder!!.best.assignment).containsExactly(2, 0, 1).inOrder()
        assertThat(CalibrationReport.fileOrder(result.bandOrder!!.best.assignment)).isEqualTo("mid, high, low")
    }

    @Test
    fun `a constant late bias shows as a positive offset and the config bias removes it`() {
        val late = capture(beatOffsetMs = 15.0)
        val plain = Evaluator.evaluate(late, reference, exact, analysisSeconds = 1.0, temperatureC = 60.0)
        assertThat(plain.beat.offsets.median).isWithin(1.0).of(15.0)

        val corrected = Evaluator.evaluate(
            late, reference, exact.copy(name = "bias", biasMs = -15.0), analysisSeconds = 1.0, temperatureC = 60.0,
        )
        assertThat(corrected.beat.offsets.median).isWithin(1.0).of(0.0)
    }

    @Test
    fun `the tempo prior decides the octave of a fast track`() {
        val fastReference = reference.copy(
            pdbBpm = 174.0,
            grid = ReferenceGrid.fromAnlz((0 until 170).map { AnlzBeat(it % 4 + 1, 17_400, Math.round(250 + it * 60_000.0 / 174)) }),
        )
        val beats = DoubleArray(170) { 250 + it * 60_000.0 / 174 }
        val fast = capture().copy(candidates = BeatCandidates(beats, beats.filterIndexed { i, _ -> i % 4 == 0 }.toDoubleArray()))

        val narrow = Evaluator.evaluate(
            fast, fastReference, CalibrationConfig.BASELINE.copy(prior = TempoPrior(70.0, 180.0, preferredBpm = 120.0)), 1.0, 60.0,
        )
        val wide = Evaluator.evaluate(
            fast, fastReference, CalibrationConfig.BASELINE.copy(prior = TempoPrior(70.0, 210.0, preferredBpm = 160.0)), 1.0, 60.0,
        )
        assertThat(narrow.octave).isAnyOf(OctaveClass.EXACT, OctaveClass.HALF)
        assertThat(wide.octave).isEqualTo(OctaveClass.EXACT)
    }

    @Test
    fun `the raw model beats are scored next to the grid`() {
        val result = Evaluator.evaluate(capture(beatOffsetMs = 12.0), reference, CalibrationConfig.BASELINE, 1.0, 60.0)
        assertThat(result.modelBeat!!.fMeasure).isWithin(0.02).of(1.0)
        assertThat(result.modelBeat!!.offsets.median).isWithin(0.5).of(12.0)
        assertThat(result.modelDownbeat!!.accuracy).isWithin(0.05).of(1.0)
    }

    @Test
    fun `the constant-only config never splits the grid`() {
        val result = Evaluator.evaluate(capture(), reference, CalibrationConfig.BASELINE.copy(name = "constant", variableTempo = false), 1.0, 60.0)
        assertThat(result.segments).isEqualTo(1)
    }

    @Test
    fun `the baseline is the pipeline default and takes the model latency off before fitting`() {
        assertThat(CalibrationConfig.BASELINE.modelLatencyMs).isEqualTo(DjAnalysisPipeline.MODEL_LATENCY_MS)
        assertThat(CalibrationConfig.BASELINE.variableTempo).isFalse()
        assertThat(CalibrationConfig.BASELINE.refine).isFalse()
        val result = Evaluator.evaluate(capture(), reference, CalibrationConfig.BASELINE, 1.0, 60.0)
        assertThat(result.beat.offsets.median).isWithin(1.0).of(-DjAnalysisPipeline.MODEL_LATENCY_MS)
        assertThat(result.modelBeat!!.offsets.median).isWithin(0.5).of(0.0)
    }

    @Test
    fun `the pre-calibration config keeps the segmentation and the refinement without a latency`() {
        val before = CalibrationConfig.PRE_D1_20
        assertThat(before.variableTempo).isTrue()
        assertThat(before.refine).isTrue()
        assertThat(before.modelLatencyMs).isEqualTo(0.0)
    }

    @Test
    fun `no candidates means no grid and zero scores`() {
        val result = Evaluator.evaluate(capture().copy(candidates = null), reference, CalibrationConfig.BASELINE, 1.0, 60.0)
        assertThat(result.segments).isEqualTo(0)
        assertThat(result.octave).isNull()
        assertThat(result.beat.fMeasure).isEqualTo(0.0)
        assertThat(result.modelBeat).isNull()
    }

    @Test
    fun `the level exponent reshapes our planes before correlating`() {
        val curved = Evaluator.reshape(byteArrayOf(0, 64, -1), exponent = 0.5)
        assertThat(curved[0].toInt() and 0xFF).isEqualTo(0)
        assertThat(curved[1].toInt() and 0xFF).isEqualTo(128) // sqrt(64 / 255) * 255 = 127.75
        assertThat(curved[2].toInt() and 0xFF).isEqualTo(255)
    }

    // D1.29: the bar phase

    /** Beats from 20 ms on the 128 BPM line whose downbeat probabilities nearly tie positions 0 and 2 (2 slightly ahead). */
    private fun nearlyTiedCapture(soundFromStart: Boolean): TrackCapture {
        val count = ((durationMs - 20) / beatMs).toInt()
        val beats = DoubleArray(count) { 20 + it * beatMs }
        val probabilities = DoubleArray(count) { doubleArrayOf(0.45, 0.1, 0.50, 0.1)[it % 4] }
        val columns = 9_000
        val level = ByteArray(columns) { if (soundFromStart || it > 150) 200.toByte() else 0 }
        return capture().copy(
            candidates = BeatCandidates(beats, DoubleArray(0), probabilities),
            waveform = BandWaveform(level, level, level, level, version = 1),
        )
    }

    private fun firstBeatInBar(capture: TrackCapture, config: CalibrationConfig) = Evaluator.grid(capture, config)!!.segments.single().beatInBarAtStart

    @Test
    fun `the baseline decides the bar phase with the start of the track and the pre-D1_29 config does not`() {
        val tied = nearlyTiedCapture(soundFromStart = true)

        assertThat(CalibrationConfig.BASELINE.barPhase).isNotNull()
        assertThat(CalibrationConfig.PRE_D1_29.barPhase).isNull()
        assertThat(firstBeatInBar(tied, CalibrationConfig.PRE_D1_29)).isEqualTo(3)
        assertThat(firstBeatInBar(tied, CalibrationConfig.BASELINE)).isEqualTo(1)
    }

    @Test
    fun `a track that starts late keeps the vote of the model under every config`() {
        val late = nearlyTiedCapture(soundFromStart = false)

        assertThat(firstBeatInBar(late, CalibrationConfig.PRE_D1_29)).isEqualTo(3)
        assertThat(firstBeatInBar(late, CalibrationConfig.BASELINE)).isEqualTo(3)
    }

    @Test
    fun `the bar phase never moves a beat`() {
        val tied = nearlyTiedCapture(soundFromStart = true)

        val before = Evaluator.grid(tied, CalibrationConfig.PRE_D1_29)!!.segments.single()
        val after = Evaluator.grid(tied, CalibrationConfig.BASELINE)!!.segments.single()

        assertThat(after.startMs).isEqualTo(before.startMs)
        assertThat(after.bpm).isEqualTo(before.bpm)
    }

    @Test
    fun `the confidence of the bar phase comes with the grid`() {
        val tied = nearlyTiedCapture(soundFromStart = true)

        val withPrior = Evaluator.gridWithConfidence(tied, CalibrationConfig.BASELINE)!!
        val withoutPrior = Evaluator.gridWithConfidence(tied, CalibrationConfig.PRE_D1_29)!!

        // 0.45 + 0.3 against 0.50: a lead of 0.25; without the rephasing step there is no confidence to report.
        assertThat(withPrior.second).isWithin(1e-9).of(0.25)
        assertThat(withoutPrior.second).isEqualTo(0.0)
    }
}
