package io.sonora.core.djengine.pipeline

import com.google.common.truth.Truth.assertThat
import io.sonora.core.djmodel.BandWaveform
import io.sonora.core.djmodel.BeatCandidates
import io.sonora.core.djmodel.DjBeatGrid
import io.sonora.core.djmodel.OnsetEnvelope
import io.sonora.core.djmodel.TempoSegment
import io.sonora.core.djengine.beats.BarPhaseResult
import io.sonora.core.djengine.key.ChromaFrames
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * The defaults the D1.20 calibration chose on the rekordbox reference library (see `odd/tasks/dj-mode.md`, D1.20):
 * one constant tempo per track, no onset phase refinement, and the beat model's latency taken off its beats.
 */
class DjAnalysisPipelineDefaultsTest {

    @Test
    fun `the default grid fit keeps one tempo when the model's beats wander`() {
        // 120 to 124 BPM over two minutes: the variable-tempo segmentation (D1.12) would cut it into regions.
        val beats = ArrayList<Double>()
        var t = 500.0
        val count = 240
        for (k in 0 until count) {
            beats += t
            t += 60_000.0 / (120.0 + 4.0 * k / count)
        }
        val candidates = BeatCandidates(beats.toDoubleArray(), beats.filterIndexed { i, _ -> i % 4 == 0 }.toDoubleArray())

        val grid = DjAnalysisPipeline.DEFAULT_GRID_FIT.fit(candidates, t + 500)!!

        assertThat(grid.isConstant).isTrue()
        assertThat(grid.segments.single().bpm).isIn(com.google.common.collect.Range.closed(119.0, 125.0))
    }

    @Test
    fun `the default grid fit moves the model's beats earlier by its latency`() {
        val beats = DoubleArray(120) { 1_000.0 + 500.0 * it }
        val candidates = BeatCandidates(beats, beats.filterIndexed { i, _ -> i % 4 == 0 }.toDoubleArray())

        val grid = DjAnalysisPipeline.DEFAULT_GRID_FIT.fit(candidates, 61_000.0)!!
        val times = grid.beats(61_000.0).map { it.timeMs }

        assertThat(DjAnalysisPipeline.MODEL_LATENCY_MS).isEqualTo(10.0)
        assertThat(grid.segments.single().bpm).isEqualTo(120.0)
        assertThat(times.minOf { abs(it - (1_000.0 - DjAnalysisPipeline.MODEL_LATENCY_MS)) }).isLessThan(0.5)
    }

    @Test
    fun `by default the phase is not refined and the onset sink is never fed`() {
        val modelGrid = DjBeatGrid(listOf(TempoSegment(50.0, 128.0, 2)))
        val onset = CountingSinks(44_100) { OnsetEnvelope(FloatArray(100), 100.0) }
        val pipeline = DjAnalysisPipeline(
            modelFactory = FakeModelFactory { FakeModel() },
            fallbackGrid = CountingSinks<DjBeatGrid?>(44_100) { null }.asStage(),
            resampler = HoldResampler(),
            telemetry = RecordingTelemetry(),
            clock = SteppingClock(),
            waveform = CountingSinks(44_100) { BandWaveform(ByteArray(0), ByteArray(0), ByteArray(0), ByteArray(0), 1) }.asStage(),
            chroma = CountingSinks(44_100) { ChromaFrames(FloatArray(0), 10.0) }.asStage(),
            mel = CountingSinks(22_050) { melOf(400) }.asStage(),
            onset = onset.asStage(),
            gridFit = GridFit { _, _ -> modelGrid },
            keyDetection = KeyDetection { null },
        )
        val source = ArrayPcmSource(FloatArray(44_100), 44_100)

        val analysis = runBlocking { pipeline.run(source, TEST_AUDIO_ID) }

        assertThat(analysis.grid).isEqualTo(modelGrid)
        assertThat(onset.created).isEqualTo(0)
    }

    // D1.29: the bar phase

    private val modelGrid = DjBeatGrid(listOf(TempoSegment(50.0, 128.0, 2)))
    private val loudWaveform = BandWaveform(ByteArray(300) { 100 }, ByteArray(300) { 100 }, ByteArray(300) { 100 }, ByteArray(300) { 100 }, 1)

    private fun pipelineWith(barPhasing: BarPhasing?, fallback: DjBeatGrid? = null, model: Boolean = true): DjAnalysisPipeline = DjAnalysisPipeline(
        modelFactory = FakeModelFactory { if (model) FakeModel() else null },
        fallbackGrid = CountingSinks<DjBeatGrid?>(44_100) { fallback }.asStage(),
        resampler = HoldResampler(),
        telemetry = RecordingTelemetry(),
        clock = SteppingClock(),
        waveform = CountingSinks(44_100) { loudWaveform }.asStage(),
        chroma = CountingSinks(44_100) { ChromaFrames(FloatArray(0), 10.0) }.asStage(),
        mel = CountingSinks(22_050) { melOf(400) }.asStage(),
        onset = null,
        gridFit = GridFit { _, _ -> modelGrid },
        keyDetection = KeyDetection { null },
        barPhasing = barPhasing,
    )

    private fun run(pipeline: DjAnalysisPipeline) = runBlocking { pipeline.run(ArrayPcmSource(FloatArray(44_100), 44_100), TEST_AUDIO_ID) }

    @Test
    fun `the bar phase is decided after the grid fit from the grid, the candidates and the waveform`() {
        val seen = ArrayList<Triple<DjBeatGrid, BeatCandidates, BandWaveform>>()
        val rephased = DjBeatGrid(listOf(TempoSegment(50.0, 128.0, 4)))

        val analysis = run(pipelineWith(BarPhasing { grid, candidates, waveform -> seen += Triple(grid, candidates, waveform); BarPhaseResult(rephased, 0.5) }))

        assertThat(seen).hasSize(1)
        assertThat(seen.single().first).isEqualTo(modelGrid)
        assertThat(seen.single().third).isEqualTo(loudWaveform)
        assertThat(analysis.grid).isEqualTo(rephased)
        assertThat(analysis.waveform).isEqualTo(loudWaveform)
    }

    @Test
    fun `without a bar phasing the fitted grid is the result`() {
        assertThat(run(pipelineWith(null)).grid).isEqualTo(modelGrid)
    }

    @Test
    fun `a grid from the fallback has no model evidence and is not rephased`() {
        val fallbackGrid = DjBeatGrid(listOf(TempoSegment(10.0, 100.0, 1)))
        var calls = 0

        val analysis = run(pipelineWith(BarPhasing { grid, _, _ -> calls++; BarPhaseResult(grid, 0.0) }, fallback = fallbackGrid, model = false))

        assertThat(calls).isEqualTo(0)
        assertThat(analysis.grid).isEqualTo(fallbackGrid)
    }

    @Test
    fun `the default bar phasing decides the phase of the model's candidates, with the start of the track as a prior`() {
        // The model's beats sit 10 ms late (the latency the default grid fit takes off): the grid starts at 0.
        val beats = DoubleArray(120) { 10.0 + 500.0 * it }
        val probabilities = DoubleArray(120) { if (it % 4 == 2) 0.9 else 0.1 }
        val candidates = BeatCandidates(beats, DoubleArray(0), probabilities)
        val grid = DjBeatGrid(listOf(TempoSegment(0.0, 120.0, 1)))

        val result = DjAnalysisPipeline.DEFAULT_BAR_PHASING.rephase(grid, candidates, loudWaveform)

        // Beat 2 is the downbeat, so the first beat is beat 3 of its bar; the loud start at 0 adds 0.3 to beat 0 (0.4 against 0.9).
        assertThat(result.grid.segments.single().beatInBarAtStart).isEqualTo(3)
        assertThat(result.confidence).isWithin(1e-9).of(0.5)
    }

    @Test
    fun `the analyzer version was raised when the bar phase changed the grids`() {
        // Analyses stored with version 1 have the old bar phase; the stored version tells a re-analysis which ones to improve.
        assertThat(io.sonora.core.djengine.DJ_ANALYZER_VERSION).isAtLeast(2)
    }
}
