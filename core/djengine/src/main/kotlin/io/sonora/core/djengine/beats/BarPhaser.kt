package io.sonora.core.djengine.beats

import io.sonora.core.djmodel.BandWaveform
import io.sonora.core.djmodel.BeatCandidates
import io.sonora.core.djmodel.DjBeatGrid
import io.sonora.core.djmodel.TempoSegment
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Tuning of [BarPhaser]. The defaults were chosen on the 150 reference tracks of the D1.20 calibration (D1.29): every
 * value in a wide range around them gives the same result, see the D1.29 entry of `odd/tasks/dj-mode.md`.
 *
 * @property introWeight how much a track that starts sounding on a beat adds to that beat's position, on the scale of
 *   the mean downbeat probability (0..1). A prior, not a verdict: a model that is sure of another position (a mean
 *   probability lead above this) is never overruled. 0 switches the prior off.
 * @property introWindowMs the prior applies only when the track is already sounding this soon after its first
 *   sample: a track that starts with a pause, a fade or a pickup says nothing about where its bars begin.
 * @property introLevelFraction "sounding" means a waveform column above this share of the track's loud level (the
 *   75th percentile of the overall peak plane).
 * @property introOffBeatFraction the first sound must sit this close (as a share of the beat length) to a grid beat
 *   to be taken as that beat.
 * @property matchToleranceFraction how far (share of the beat length) a detection may sit from a grid beat and still
 *   vote for it; the grid fit's own tolerance ([GridFitOptions.matchToleranceFraction]).
 */
class BarPhaseOptions(
    val introWeight: Double = 0.3,
    val introWindowMs: Double = 100.0,
    val introLevelFraction: Double = 0.2,
    val introOffBeatFraction: Double = 0.25,
    val matchToleranceFraction: Double = 0.12,
) {
    init {
        require(introWeight >= 0) { "introWeight must not be negative: $introWeight" }
        require(introWindowMs >= 0) { "introWindowMs must not be negative: $introWindowMs" }
        require(introLevelFraction > 0 && introLevelFraction <= 1) { "introLevelFraction must be in (0, 1]: $introLevelFraction" }
        require(introOffBeatFraction > 0 && introOffBeatFraction <= 0.5) { "introOffBeatFraction must be in (0, 0.5]: $introOffBeatFraction" }
        require(matchToleranceFraction > 0 && matchToleranceFraction < 0.5) { "matchToleranceFraction must be in (0, 0.5): $matchToleranceFraction" }
    }
}

/**
 * A grid with its bar phase (re)decided, and how sure that decision is.
 *
 * @property confidence 0..1: the lead of the chosen bar position over the runner-up on the scale of the mean downbeat
 *   probability (see [BarPhaser]). 0 when nothing could be decided. It measures how clearly the evidence prefers one
 *   position, NOT the chance of being right: on the 150 reference tracks a confident model was wrong about as often
 *   as an unsure one (a model that is consistently wrong about the bar is consistent), so use it to mark grids the
 *   evidence barely separates, never as a probability.
 */
class BarPhaseResult(val grid: DjBeatGrid, val confidence: Double)

/**
 * Decides which beat of a constant-tempo grid starts the bars (the "1"), from two kinds of evidence:
 *
 * 1. **The model's downbeat probabilities** (see [BeatCandidates.downbeatProbabilities]): the mean probability of the
 *    grid beats in each of the four bar positions, as [GridFitter] votes. The bar position with the highest mean wins.
 * 2. **The start of the track** (a prior): tracks made for DJs mostly start on a bar line, so when the audio is already
 *    sounding at the very start of the file ([BarPhaseOptions.introWindowMs]) and that first sound is on a grid beat,
 *    that beat's position gets [BarPhaseOptions.introWeight] added to its mean. On the 150 reference tracks the first
 *    sound was on the reference's bar start in 31 of the 33 tracks that start sounding within 30 ms, while a sound that
 *    starts later was a coin flip, so only the first case is used.
 *
 * Only the position of the bar changes: beat times and tempo are those of the grid fit, so beat and tempo scores
 * cannot move. Grids with tempo changes and candidates without downbeat probabilities are returned as they are.
 *
 * Tried and rejected on the 150 reference tracks (D1.29): voting with log-odds instead of probabilities (same result),
 * a vote per bar then a majority (worse), and a half-bar tie-break from the kick energy on the beat (no signal).
 */
object BarPhaser {

    /**
     * [grid] with the bar phase decided from [candidates] (the beats and downbeat probabilities the grid was fitted to,
     * in the same time base as [grid]) and [waveform] (for the start of the track).
     */
    fun rephase(
        grid: DjBeatGrid,
        candidates: BeatCandidates,
        waveform: BandWaveform,
        options: BarPhaseOptions = BarPhaseOptions(),
    ): BarPhaseResult {
        val segment = grid.segments.singleOrNull() ?: return BarPhaseResult(grid, 0.0)
        val interval = segment.beatLengthMs
        val scores = meanProbabilityByPosition(candidates, segment.startMs, interval, options.matchToleranceFraction * interval)
            ?: return BarPhaseResult(grid, 0.0)
        introPosition(waveform, segment.startMs, interval, options)?.let { scores[it] += options.introWeight }

        var best = 0
        for (position in 1 until BEATS_PER_BAR) if (scores[position] - position * TIE_EPSILON > scores[best] - best * TIE_EPSILON) best = position
        val runnerUp = (0 until BEATS_PER_BAR).filter { it != best }.maxOf { scores[it] }
        val confidence = (scores[best] - runnerUp).coerceIn(0.0, 1.0)

        // Beat k of the segment is beat floorMod(first - 1 + k, 4) + 1 of its bar; the bar starts at k = best.
        val first = Math.floorMod(-best, BEATS_PER_BAR) + 1
        if (first == segment.beatInBarAtStart) return BarPhaseResult(grid, confidence)
        return BarPhaseResult(DjBeatGrid(listOf(TempoSegment(segment.startMs, segment.bpm, first))), confidence)
    }

    /**
     * The time (ms) of the first sound of the track: the first column of the overall peak plane above
     * [levelFraction] of its 75th percentile, or `null` for a silent or empty waveform.
     */
    fun introStartMs(waveform: BandWaveform, levelFraction: Double): Double? {
        val peak = waveform.peak()
        if (peak.isEmpty()) return null
        val histogram = IntArray(LEVELS)
        for (b in peak) histogram[b.toInt() and 0xFF]++
        val rank = (peak.size * LOUD_PERCENTILE).toInt()
        var below = 0
        var loud = 0
        while (loud < LEVELS - 1 && below + histogram[loud] <= rank) {
            below += histogram[loud]
            loud++
        }
        if (loud == 0) return null
        val threshold = levelFraction * loud
        for (i in peak.indices) {
            if ((peak[i].toInt() and 0xFF) > threshold) return i * MS_PER_SECOND / BandWaveform.COLUMNS_PER_SECOND
        }
        return null
    }

    /** The mean downbeat probability of the detections on each bar position, or `null` when a position has none. */
    private fun meanProbabilityByPosition(candidates: BeatCandidates, startMs: Double, interval: Double, tolerance: Double): DoubleArray? {
        val beats = candidates.beatsMs()
        val probabilities = candidates.downbeatProbabilities()
        val sums = DoubleArray(BEATS_PER_BAR)
        val counts = IntArray(BEATS_PER_BAR)
        for (i in beats.indices) {
            val p = probabilities[i]
            if (p.isNaN()) continue
            val k = ((beats[i] - startMs) / interval).roundToLong()
            if (abs(beats[i] - (startMs + k * interval)) > tolerance) continue
            val position = Math.floorMod(k, BEATS_PER_BAR.toLong()).toInt()
            sums[position] += p
            counts[position]++
        }
        if (counts.any { it == 0 }) return null
        return DoubleArray(BEATS_PER_BAR) { sums[it] / counts[it] }
    }

    /** The bar position the start of the track points at, or `null` when it points at none (see [BarPhaseOptions]). */
    private fun introPosition(waveform: BandWaveform, startMs: Double, interval: Double, options: BarPhaseOptions): Int? {
        if (options.introWeight == 0.0) return null
        val introMs = introStartMs(waveform, options.introLevelFraction) ?: return null
        if (introMs > options.introWindowMs) return null
        val beats = (introMs - startMs) / interval
        val k = beats.roundToLong()
        if (abs(beats - k) > options.introOffBeatFraction) return null
        return Math.floorMod(k, BEATS_PER_BAR.toLong()).toInt()
    }

    private const val BEATS_PER_BAR = GridFitter.BEATS_PER_BAR
    private const val LEVELS = 256
    private const val LOUD_PERCENTILE = 0.75
    private const val MS_PER_SECOND = 1_000.0

    /** Makes the earliest position win a tie, as in [GridFitter]. */
    private const val TIE_EPSILON = 1e-12
}
