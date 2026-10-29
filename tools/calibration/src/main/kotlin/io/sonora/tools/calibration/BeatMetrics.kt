package io.sonora.tools.calibration

import io.sonora.core.djmodel.GridBeat
import java.util.TreeMap

/** A beat at [timeMs] with its position in the bar, 1..4 (1 = downbeat). */
data class BarBeat(val timeMs: Double, val beatInBar: Int) {
    init {
        require(beatInBar in 1..BeatMetrics.BEATS_PER_BAR) { "beatInBar must be 1..${BeatMetrics.BEATS_PER_BAR}: $beatInBar" }
    }
}

/** The beats of a [io.sonora.core.djmodel.DjBeatGrid] (for example `grid.beats(durationMs)`) as [BarBeat]s. */
fun List<GridBeat>.toBarBeats(): List<BarBeat> = map { BarBeat(it.timeMs, it.beatInBar) }

/**
 * The outcome of matching our beats against the reference beats.
 *
 * [offsetsMs] holds `ours - reference` for every matched pair, in reference order: positive means our beat is late.
 */
class BeatScore(
    val matched: Int,
    val oursCount: Int,
    val referenceCount: Int,
    val offsetsMs: DoubleArray,
) {
    /** Matched beats over our beats; 0 when we have none. */
    val precision: Double get() = if (oursCount == 0) 0.0 else matched.toDouble() / oursCount

    /** Matched beats over the reference beats; 0 when there are none. */
    val recall: Double get() = if (referenceCount == 0) 0.0 else matched.toDouble() / referenceCount

    /** Harmonic mean of [precision] and [recall]; 0 when nothing matched. */
    val fMeasure: Double
        get() {
            val p = precision
            val r = recall
            return if (p + r == 0.0) 0.0 else 2 * p * r / (p + r)
        }

    /** Count, mean, median and p95 of the signed [offsetsMs]. */
    val offsets: Summary by lazy(LazyThreadSafetyMode.NONE) { Stats.summarize(offsetsMs) }

    override fun toString(): String = "BeatScore(F=$fMeasure, P=$precision, R=$recall, matched=$matched)"
}

/** Share of the reference downbeats that our downbeats hit. */
class DownbeatScore(val matched: Int, val oursCount: Int, val referenceCount: Int) {
    /** Reference downbeats matched by one of our downbeats; 0 when the reference has none. */
    val accuracy: Double get() = if (referenceCount == 0) 0.0 else matched.toDouble() / referenceCount
}

/**
 * Agreement of the bar position of beats that match in time.
 *
 * [agreement] is the fraction of [matchedBeats] with the same position in the bar. [bestShift] (0..3) is the number
 * of positions to add to our position (wrapping 4 to 1) that agrees with the reference most often, and
 * [shiftedAgreement] the agreement after that shift: a [shiftedAgreement] near 1 with a [bestShift] other than 0 is a
 * constant bar-phase error (the grid is right, the "1" is on the wrong beat).
 */
class BarPhaseAgreement(
    val matchedBeats: Int,
    val agreement: Double,
    val bestShift: Int,
    val shiftedAgreement: Double,
)

/**
 * Beat-tracking metrics between our beats and the reference beats, in milliseconds.
 *
 * - F-measure with a +-70 ms window, as in the MIREX audio beat tracking task and `mir_eval.beat.f_measure` (Raffel
 *   et al., "mir_eval: a transparent implementation of common MIR metrics", ISMIR 2014; Davies, Degara and Plumbley,
 *   "Evaluation methods for musical audio beat tracking algorithms", QMUL tech. report C4DM-TR-09-06, 2009). An
 *   estimate matches a reference beat when `|ours - reference| <= window`, each beat is used at most once, and the
 *   matching has the maximum possible number of pairs.
 * - Offsets: signed `ours - reference` times of the matched pairs, the number the calibration uses to measure decoder
 *   or analyser latency per file format.
 */
object BeatMetrics {

    /** The MIREX beat tolerance. */
    const val DEFAULT_TOLERANCE_MS: Double = 70.0

    internal const val BEATS_PER_BAR: Int = 4

    /**
     * Scores [ours] against [reference]. Inputs need not be sorted. Beats before [minBeatTimeMs] are dropped from both
     * (`mir_eval.beat.trim_beats` does this with 5000 ms by default; here the default is to keep every beat).
     * Empty sequences score 0, as in `mir_eval`.
     */
    fun score(
        ours: DoubleArray,
        reference: DoubleArray,
        toleranceMs: Double = DEFAULT_TOLERANCE_MS,
        minBeatTimeMs: Double = 0.0,
    ): BeatScore {
        val o = prepare(ours, minBeatTimeMs)
        val r = prepare(reference, minBeatTimeMs)
        val pairs = match(o, r, toleranceMs)
        val offsets = DoubleArray(pairs.size / 2) { o[pairs[2 * it]] - r[pairs[2 * it + 1]] }
        return BeatScore(pairs.size / 2, o.size, r.size, offsets)
    }

    /** Pools the signed offsets of every score by format key (for example `mp3`, `flac`) into one summary per key. */
    fun offsetSummaryByFormat(scores: Iterable<Pair<String, BeatScore>>): Map<String, Summary> {
        val pooled = TreeMap<String, MutableList<Double>>()
        for ((format, score) in scores) {
            pooled.getOrPut(format) { ArrayList() }.addAll(score.offsetsMs.toList())
        }
        val out = LinkedHashMap<String, Summary>()
        for ((format, offsets) in pooled) out[format] = Stats.summarize(offsets.toDoubleArray())
        return out
    }

    /**
     * Downbeat accuracy: the fraction of the reference downbeats (position 1) matched one to one by one of our
     * downbeats within [toleranceMs].
     */
    fun downbeats(
        ours: List<BarBeat>,
        reference: List<BarBeat>,
        toleranceMs: Double = DEFAULT_TOLERANCE_MS,
    ): DownbeatScore {
        val o = downbeatTimes(ours)
        val r = downbeatTimes(reference)
        return DownbeatScore(match(o, r, toleranceMs).size / 2, o.size, r.size)
    }

    /** See [BarPhaseAgreement]. Beats are matched in time first (all beats, one to one, within [toleranceMs]). */
    fun barPhase(
        ours: List<BarBeat>,
        reference: List<BarBeat>,
        toleranceMs: Double = DEFAULT_TOLERANCE_MS,
    ): BarPhaseAgreement {
        val o = ours.sortedBy { it.timeMs }
        val r = reference.sortedBy { it.timeMs }
        val pairs = match(DoubleArray(o.size) { o[it].timeMs }, DoubleArray(r.size) { r[it].timeMs }, toleranceMs)
        val count = pairs.size / 2
        if (count == 0) return BarPhaseAgreement(0, 0.0, 0, 0.0)

        val agreeing = IntArray(BEATS_PER_BAR)
        for (k in 0 until count) {
            val ourPosition = o[pairs[2 * k]].beatInBar - 1
            val referencePosition = r[pairs[2 * k + 1]].beatInBar - 1
            // The shift that maps our position onto the reference one.
            agreeing[Math.floorMod(referencePosition - ourPosition, BEATS_PER_BAR)]++
        }
        var bestShift = 0
        for (shift in 1 until BEATS_PER_BAR) if (agreeing[shift] > agreeing[bestShift]) bestShift = shift
        return BarPhaseAgreement(
            matchedBeats = count,
            agreement = agreeing[0].toDouble() / count,
            bestShift = bestShift,
            shiftedAgreement = agreeing[bestShift].toDouble() / count,
        )
    }

    private fun downbeatTimes(beats: List<BarBeat>): DoubleArray =
        beats.filter { it.beatInBar == 1 }.map { it.timeMs }.sorted().toDoubleArray()

    private fun prepare(times: DoubleArray, minBeatTimeMs: Double): DoubleArray =
        times.filter { it >= minBeatTimeMs }.sorted().toDoubleArray()

    /**
     * Maximum one-to-one matching of two sorted sequences with a common window, as flat `[ours, reference]` index
     * pairs in reference order. Because every window has the same width, sweeping both sequences once and pairing the
     * first estimate that can still reach the current reference beat is optimal (it is the same cardinality
     * `mir_eval.util.match_events` finds with Hopcroft-Karp, although the pairs chosen may differ when several
     * maximum matchings exist).
     */
    private fun match(ours: DoubleArray, reference: DoubleArray, toleranceMs: Double): IntArray {
        val pairs = ArrayList<Int>()
        var i = 0
        var j = 0
        while (i < reference.size && j < ours.size) {
            when {
                ours[j] < reference[i] - toleranceMs -> j++
                ours[j] > reference[i] + toleranceMs -> i++
                else -> {
                    pairs += j
                    pairs += i
                    i++
                    j++
                }
            }
        }
        return pairs.toIntArray()
    }
}
