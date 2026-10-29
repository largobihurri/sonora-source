package io.sonora.core.djstore

import io.sonora.core.djmodel.Accelerator
import io.sonora.core.djmodel.AnalysisProvenance
import io.sonora.core.djmodel.AudioId
import io.sonora.core.djmodel.BandWaveform
import io.sonora.core.djmodel.BeatSource
import io.sonora.core.djmodel.DjAnalysis
import io.sonora.core.djmodel.DjBeatGrid
import io.sonora.core.djmodel.KeyEstimate
import io.sonora.core.djmodel.TempoSegment
import io.sonora.core.domain.analysis.MusicalKey
import java.util.Random

/** A deterministic audio id: the hash is [n] in 64 hex digits. */
fun audioId(n: Int): AudioId = AudioId.of(AudioId.PAYLOAD_V1, "%064x".format(n))

/** A waveform of [columns] columns whose bytes come from [seed] (fixed seed: the same arguments, the same planes). */
fun waveform(columns: Int, seed: Long, version: Int = 1): BandWaveform {
    val random = Random(seed)
    fun plane() = ByteArray(columns).also(random::nextBytes)
    return BandWaveform(plane(), plane(), plane(), plane(), version)
}

/** A grid of one steady segment, or of several when [segments] > 1 (each one a whole number of beats long). */
fun grid(bpm: Double = 124.0, firstBeatMs: Double = 83.25, segments: Int = 1): DjBeatGrid {
    val list = ArrayList<TempoSegment>()
    var start = firstBeatMs
    var tempo = bpm
    for (i in 0 until segments) {
        list += TempoSegment(start, tempo, beatInBarAtStart = (i % 4) + 1)
        // 64 beats later, on a beat of this segment, the next one starts at a slightly different tempo.
        start += 64 * 60_000.0 / tempo
        tempo += 0.5
    }
    return DjBeatGrid(list)
}

/** An analysis with every optional part set: a model-made grid, a key, a waveform and the model's provenance. */
fun analysis(
    seed: Long = 1,
    columns: Int = 600,
    segments: Int = 1,
    analysedAtEpochMs: Long = 1_700_000_000_000L + seed,
    analyzerVersion: Int = 1,
): DjAnalysis = DjAnalysis(
    durationMs = columns * 1000L / BandWaveform.COLUMNS_PER_SECOND,
    grid = grid(bpm = 120.0 + seed % 40 + 0.07, segments = segments),
    key = KeyEstimate(MusicalKey.G_SHARP_MINOR, strength = 0.8125, method = "edma"),
    waveform = waveform(columns, seed),
    provenance = AnalysisProvenance(
        analyzerVersion = analyzerVersion,
        beatSource = BeatSource.MODEL,
        modelId = "beat-this-small-1",
        accelerator = Accelerator.NPU,
        analysedAtEpochMs = analysedAtEpochMs,
    ),
)

/** An analysis with nothing optional: no grid, no key, the CPU fallback beat source and no model. */
fun bareAnalysis(seed: Long = 100, columns: Int = 150): DjAnalysis = DjAnalysis(
    durationMs = columns * 1000L / BandWaveform.COLUMNS_PER_SECOND,
    grid = null,
    key = null,
    waveform = waveform(columns, seed, version = 2),
    provenance = AnalysisProvenance(analyzerVersion = 3, beatSource = BeatSource.QM_DSP, analysedAtEpochMs = 42L),
)
