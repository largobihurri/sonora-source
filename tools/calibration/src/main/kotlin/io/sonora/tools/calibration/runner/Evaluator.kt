package io.sonora.tools.calibration.runner

import io.sonora.core.djengine.beats.BarPhaseOptions
import io.sonora.core.djengine.beats.BarPhaseResult
import io.sonora.core.djengine.beats.BarPhaser
import io.sonora.core.djengine.beats.GridFitOptions
import io.sonora.core.djengine.beats.GridFitter
import io.sonora.core.djengine.beats.PhaseRefiner
import io.sonora.core.djengine.beats.TempoPrior
import io.sonora.core.djengine.key.FrameWeighting
import io.sonora.core.djengine.key.KeyClassifier
import io.sonora.core.djengine.key.KeyProfile
import io.sonora.core.djengine.pipeline.DjAnalysisPipeline
import io.sonora.core.djmodel.BeatCandidates
import io.sonora.core.djmodel.DjBeatGrid
import io.sonora.core.djmodel.KeyEstimate
import io.sonora.core.domain.analysis.MusicalKey
import io.sonora.tools.calibration.BandOrderResult
import io.sonora.tools.calibration.BarBeat
import io.sonora.tools.calibration.BarPhaseAgreement
import io.sonora.tools.calibration.BeatMetrics
import io.sonora.tools.calibration.BeatScore
import io.sonora.tools.calibration.DownbeatScore
import io.sonora.tools.calibration.KeyMetrics
import io.sonora.tools.calibration.OctaveClass
import io.sonora.tools.calibration.TempoMetrics
import io.sonora.tools.calibration.WaveformMetrics
import kotlin.math.floor
import kotlin.math.pow

/**
 * What rekordbox says about one track, read from its export: the `export.pdb` tempo ([pdbBpm]) and key, the `PQTZ`
 * beat [grid], and the waveform planes: `PWV7`/`PWV6` 3-band planes in FILE order (byte 0, 1, 2 of each column, so
 * the band order can be tested, not assumed), the `PWV5` colour detail heights, the `PWV3` monochrome detail heights
 * and the six `PWV4` colour preview channels. Missing tags are `null`.
 */
data class ReferenceTrack(
    val path: String,
    val format: String,
    val pdbBpm: Double,
    val grid: ReferenceGrid,
    val keyName: String?,
    val key: MusicalKey?,
    val threeBandDetail: List<ByteArray>?,
    val threeBandPreview: List<ByteArray>?,
    val colorDetailHeight: ByteArray?,
    val monoDetailHeight: ByteArray?,
    val colorPreview: List<ByteArray>?,
)

/** One track scored under one [config]; see [Evaluator.evaluate]. */
data class TrackResult(
    val path: String,
    val format: String,
    val config: String,
    val referenceBpm: Double,
    val referenceVariable: Boolean,
    /** The tempo of our segment with the most beats; `NaN` without a grid. */
    val ourBpm: Double,
    /** Our tempo against the reference's; `null` without a grid. */
    val octave: OctaveClass?,
    /** Tempo segments of our grid; 0 without a grid. */
    val segments: Int,
    val beat: BeatScore,
    /** [beat] without the first 5 s on both sides, as `mir_eval.beat.trim_beats` scores, for published numbers. */
    val beatTrimmed: BeatScore,
    val downbeat: DownbeatScore,
    val barPhase: BarPhaseAgreement,
    val referenceKey: MusicalKey?,
    val ourKey: MusicalKey?,
    /** MIREX key score; `NaN` when the reference has no key. */
    val keyScore: Double,
    /** Our low/mid/high against the `PWV7` planes (file order), `null` without `PWV7`. */
    val bandOrder: BandOrderResult?,
    /** The same against `PWV6`. */
    val previewBandOrder: BandOrderResult?,
    /** Further Pearson correlations, keyed `<tag>.<what>~<our plane>`. */
    val waveformCorrelations: Map<String, Double>,
    /** Our waveform's time offset against rekordbox's (positive: ours late), from the detail waveforms; `NaN` without one. */
    val waveformLagMs: Double,
    val analysisSeconds: Double,
    val audioSeconds: Double,
    val temperatureC: Double,
    /** The model's raw beat detections (before any grid) against the reference beats; `null` without model output. */
    val modelBeat: BeatScore? = null,
    /** The model's raw downbeat detections against the reference downbeats; `null` without model output. */
    val modelDownbeat: DownbeatScore? = null,
)

/**
 * The analysis parameters the tuning round varies. [BASELINE] is exactly what the pipeline does by default (since
 * D1.20: the model latency taken off, one constant tempo, no phase refinement), so evaluating a capture under it
 * reproduces the grid and key of a pipeline with the same defaults; [PRE_D1_20] is what it did before the calibration. [biasMs] shifts our beats before scoring
 * (a measurement of what a constant transient bias would do); [levelExponent] reshapes our linear waveform bytes
 * (`255 * (v / 255) ^ e`) before the waveform correlations, standing in for a [io.sonora.core.djengine.waveform.BandLevelCurve] exponent.
 */
data class CalibrationConfig(
    val name: String,
    val prior: TempoPrior = TempoPrior.DEFAULT,
    val fitOptions: GridFitOptions = GridFitOptions(),
    val refine: Boolean = false,
    val maxShiftMs: Double = PhaseRefiner.DEFAULT_MAX_SHIFT_MS,
    val minContrast: Double = PhaseRefiner.DEFAULT_MIN_CONTRAST,
    val biasMs: Double = 0.0,
    val keyProfile: KeyProfile = KeyProfile.EDMA,
    val weighting: FrameWeighting = FrameWeighting.UNIFORM,
    val levelExponent: Double = 1.0,
    /** `false` fits the constant grid only ([GridFitter.fitConstant]), never the tempo segments of D1.12. */
    val variableTempo: Boolean = false,
    /** Taken off the model's beats before the grid fit, as [DjAnalysisPipeline.DEFAULT_GRID_FIT] does. */
    val modelLatencyMs: Double = DjAnalysisPipeline.MODEL_LATENCY_MS,
    /** How the bar phase is decided after the grid fit ([BarPhaser], D1.29); `null` keeps the grid fit's own vote. */
    val barPhase: BarPhaseOptions? = BarPhaseOptions(),
) {
    companion object {
        val BASELINE: CalibrationConfig = CalibrationConfig("baseline")

        /** The pipeline defaults before D1.20: tempo segments (D1.12), onset phase refinement (D1.11), no latency. */
        val PRE_D1_20: CalibrationConfig = CalibrationConfig(
            name = "pre-D1.20 defaults",
            refine = true,
            variableTempo = true,
            modelLatencyMs = 0.0,
            barPhase = null,
        )

        /** The pipeline defaults before D1.29: the bar phase is the grid fit's mean-probability vote, nothing else. */
        val PRE_D1_29: CalibrationConfig = CalibrationConfig(name = "pre-D1.29 defaults", barPhase = null)
    }
}

/** Scores a [TrackCapture] against its [ReferenceTrack] with the `:tools:calibration` metrics (D1.19). */
object Evaluator {

    /** The window of the waveform time-base search: +-30 columns, +-200 ms. */
    const val MAX_LAG_COLUMNS: Int = 30

    /** Our grid for [capture] under [config]: the grid fit (D1.10/D1.12), the bar phase (D1.29) and, when configured, the phase refinement (D1.11). */
    fun grid(capture: TrackCapture, config: CalibrationConfig): DjBeatGrid? = gridWithConfidence(capture, config)?.first

    /** [grid] with the confidence of its bar phase ([BarPhaseResult.confidence]); 0 when the config does not rephase. */
    fun gridWithConfidence(capture: TrackCapture, config: CalibrationConfig): Pair<DjBeatGrid, Double>? {
        val captured = capture.candidates ?: return null
        if (capture.durationMs <= 0) return null
        val candidates = if (config.modelLatencyMs == 0.0) captured else captured.shiftedBy(-config.modelLatencyMs)
        val fitted = if (config.variableTempo) {
            GridFitter.fit(candidates, capture.durationMs, config.prior, config.fitOptions)
        } else {
            GridFitter.fitConstant(candidates, capture.durationMs, config.prior, config.fitOptions)
        } ?: return null
        val phased = config.barPhase?.let { BarPhaser.rephase(fitted, candidates, capture.waveform, it) } ?: BarPhaseResult(fitted, 0.0)
        val envelope = capture.envelope
        val grid = if (config.refine && envelope != null) {
            PhaseRefiner.refine(phased.grid, envelope, config.maxShiftMs, config.minContrast)
        } else {
            phased.grid
        }
        return grid to phased.confidence
    }

    /** Our key for [capture] under [config]. */
    fun key(capture: TrackCapture, config: CalibrationConfig): KeyEstimate? =
        capture.chroma?.let { KeyClassifier.classify(it, config.keyProfile, config.weighting) }

    fun evaluate(
        capture: TrackCapture,
        reference: ReferenceTrack,
        config: CalibrationConfig,
        analysisSeconds: Double,
        temperatureC: Double,
        includeWaveform: Boolean = true,
    ): TrackResult = evaluate(
        capture, reference, config, grid(capture, config), key(capture, config), analysisSeconds, temperatureC, includeWaveform,
    )

    /**
     * As above with the grid and key already computed. Without [includeWaveform] the waveform comparisons are skipped
     * (no band order, no correlations, `NaN` lag), which the grid and key sweeps of the tuning round do not need.
     */
    fun evaluate(
        capture: TrackCapture,
        reference: ReferenceTrack,
        config: CalibrationConfig,
        grid: DjBeatGrid?,
        key: KeyEstimate?,
        analysisSeconds: Double,
        temperatureC: Double,
        includeWaveform: Boolean = true,
    ): TrackResult {
        val ours = grid?.beats(capture.durationMs)?.map { BarBeat(it.timeMs + config.biasMs, it.beatInBar) } ?: emptyList()
        val referenceBpm = reference.grid.dominantBpm.takeUnless { it.isNaN() } ?: reference.pdbBpm
        val ourBpm = grid?.let { ReferenceGrid.dominantBpm(it, capture.durationMs) } ?: Double.NaN
        val octave = if (grid != null && referenceBpm > 0) TempoMetrics.classifyOctave(ourBpm, referenceBpm) else null

        val waveform = capture.waveform
        if (!includeWaveform) {
            return result(capture, reference, config, grid, key, ours, referenceBpm, ourBpm, octave, null, null, emptyMap(), Double.NaN, analysisSeconds, temperatureC)
        }
        val low = reshape(waveform.low(), config.levelExponent)
        val mid = reshape(waveform.mid(), config.levelExponent)
        val high = reshape(waveform.high(), config.levelExponent)
        val peak = reshape(waveform.peak(), config.levelExponent)
        val ourBands = listOf(low, mid, high)
        val correlations = LinkedHashMap<String, Double>()
        reference.colorDetailHeight?.let { correlations["pwv5.height~peak"] = WaveformMetrics.correlation(peak, it) }
        reference.monoDetailHeight?.let { correlations["pwv3.height~peak"] = WaveformMetrics.correlation(peak, it) }
        reference.colorPreview?.forEachIndexed { channel, plane ->
            for ((name, ourPlane) in listOf("low" to low, "mid" to mid, "high" to high, "peak" to peak)) {
                correlations["pwv4.c$channel~$name"] = WaveformMetrics.correlation(ourPlane, plane)
            }
        }
        // The time base is measured on the unshaped overall peak against the detail height (PWV5, else PWV3).
        val lagReference = reference.colorDetailHeight ?: reference.monoDetailHeight
        val lagMs = lagReference?.let { WaveformLag.columnsToMs(WaveformLag.lagColumns(waveform.peak(), it, MAX_LAG_COLUMNS)) }
            ?: Double.NaN

        return result(
            capture, reference, config, grid, key, ours, referenceBpm, ourBpm, octave,
            reference.threeBandDetail?.let { WaveformMetrics.bandOrder(ourBands, it) },
            reference.threeBandPreview?.let { WaveformMetrics.bandOrder(ourBands, it) },
            correlations, lagMs, analysisSeconds, temperatureC,
        )
    }

    @Suppress("LongParameterList")
    private fun result(
        capture: TrackCapture,
        reference: ReferenceTrack,
        config: CalibrationConfig,
        grid: DjBeatGrid?,
        key: KeyEstimate?,
        ours: List<BarBeat>,
        referenceBpm: Double,
        ourBpm: Double,
        octave: OctaveClass?,
        bandOrder: BandOrderResult?,
        previewBandOrder: BandOrderResult?,
        correlations: Map<String, Double>,
        lagMs: Double,
        analysisSeconds: Double,
        temperatureC: Double,
    ): TrackResult {
        val ourTimes = DoubleArray(ours.size) { ours[it].timeMs }
        val referenceTimes = reference.grid.beatTimesMs()
        val candidates = capture.candidates
        return TrackResult(
            path = capture.path,
            format = capture.format,
            config = config.name,
            referenceBpm = referenceBpm,
            referenceVariable = reference.grid.isVariable,
            ourBpm = ourBpm,
            octave = octave,
            segments = grid?.segments?.size ?: 0,
            beat = BeatMetrics.score(ourTimes, referenceTimes),
            beatTrimmed = BeatMetrics.score(ourTimes, referenceTimes, minBeatTimeMs = TRIM_MS),
            downbeat = BeatMetrics.downbeats(ours, reference.grid.beats),
            barPhase = BeatMetrics.barPhase(ours, reference.grid.beats),
            referenceKey = reference.key,
            ourKey = key?.key,
            keyScore = if (reference.key == null) Double.NaN else KeyMetrics.mirexScore(key?.key, reference.key),
            bandOrder = bandOrder,
            previewBandOrder = previewBandOrder,
            waveformCorrelations = correlations,
            waveformLagMs = lagMs,
            analysisSeconds = analysisSeconds,
            audioSeconds = capture.durationMs / MS_PER_SECOND,
            temperatureC = temperatureC,
            modelBeat = candidates?.let { BeatMetrics.score(it.beatsMs(), referenceTimes) },
            modelDownbeat = candidates?.let { c -> BeatMetrics.downbeats(c.downbeatsMs().map { BarBeat(it, 1) }, reference.grid.beats) },
        )
    }

    private fun BeatCandidates.shiftedBy(deltaMs: Double): BeatCandidates {
        val beats = beatsMs()
        val downbeats = downbeatsMs()
        for (i in beats.indices) beats[i] += deltaMs
        for (i in downbeats.indices) downbeats[i] += deltaMs
        return BeatCandidates(beats, downbeats, downbeatProbabilities())
    }

    /** [plane] (linear levels) bent to `255 * (v / 255) ^ exponent`, rounded half up; the same plane for exponent 1. */
    fun reshape(plane: ByteArray, exponent: Double): ByteArray {
        require(exponent.isFinite() && exponent > 0) { "exponent must be positive: $exponent" }
        if (exponent == 1.0) return plane
        return ByteArray(plane.size) {
            val v = (plane[it].toInt() and 0xFF) / MAX_LEVEL
            floor(MAX_LEVEL * v.pow(exponent) + 0.5).toInt().coerceIn(0, 255).toByte()
        }
    }

    private const val TRIM_MS = 5_000.0
    private const val MS_PER_SECOND = 1_000.0
    private const val MAX_LEVEL = 255.0
}
