package io.sonora.core.djengine.beats

import io.sonora.core.djmodel.BeatActivations
import io.sonora.core.djmodel.BeatCandidates
import kotlin.math.abs
import kotlin.math.exp

/**
 * Tuning of [ActivationPeaks]. The defaults are the reference "minimal" post-processor of Beat This! (Foscarin, Schlüter
 * and Widmer, "Beat This! Accurate beat tracking without DBN postprocessing", ISMIR 2024; MIT code, `Postprocessor`
 * with `type="minimal"`): a 7-frame max-pool and a logit threshold of 0.
 *
 * @property poolFrames odd width of the max-pool window: a frame is a peak when it is the largest within
 *   `poolFrames / 2` frames on each side (values outside the track count as minus infinity).
 * @property threshold a peak is kept only when its logit is strictly above this value (0 = probability 0.5).
 * @property downbeatWindowFrames half-width, in frames, of the window around a beat in which the strongest downbeat
 *   logit is read for its downbeat probability.
 */
data class PeakParams(
    val poolFrames: Int = 7,
    val threshold: Float = 0f,
    val downbeatWindowFrames: Int = 3,
) {
    init {
        require(poolFrames >= 1 && poolFrames % 2 == 1) { "poolFrames must be odd and positive: $poolFrames" }
        require(!threshold.isNaN()) { "threshold must not be NaN" }
        require(downbeatWindowFrames >= 0) { "downbeatWindowFrames must not be negative: $downbeatWindowFrames" }
    }

    /** Frames on each side of a frame covered by the max-pool. */
    internal val poolRadius: Int get() = poolFrames / 2
}

/**
 * Turns the Beat This! beat and downbeat LOGITS (see [BeatActivations]: raw model outputs, 0 is the decision threshold,
 * the probability is `sigmoid(logit)`) into beat and downbeat CANDIDATES. Nothing here decides the tempo: the model can
 * fire about twice per click or mark most clicks as downbeats, so the grid fit (D1.10) chooses the octave and the
 * phase from these candidates.
 *
 * Picking follows the Beat This! "minimal" post-processor: a frame is a peak when it equals the maximum of its
 * [PeakParams.poolFrames]-wide neighbourhood and is above the threshold; peaks on neighbouring frames (a plateau of
 * equal logits) merge into one at their centre. Two things are added: [candidates] refines every isolated peak below
 * the frame grid by a parabola through the peak and its two neighbours, and it reads a downbeat probability per beat.
 * [minimal] returns the unrefined reference result, frame-quantised, for parity checks.
 */
object ActivationPeaks {

    /**
     * Beat candidates in ms with sub-frame refinement, the downbeat times (the beats that coincide with a downbeat
     * peak) and, per beat, the downbeat probability (see [BeatCandidates.downbeatProbabilities]).
     *
     * The refinement fits a parabola through the logits at the peak frame and its two neighbours and moves the peak to
     * the vertex, by at most half a frame. It is exact for a parabolic top and biased by a fraction of a millisecond
     * for a Gaussian bump of one to two frames sigma. Merged plateaus are not refined: their centre is already the best
     * estimate, and a flat top has no curvature to fit.
     */
    fun candidates(activations: BeatActivations, params: PeakParams = PeakParams()): BeatCandidates {
        val beat = activations.beat()
        val downbeat = activations.downbeat()
        val frameMs = 1000.0 / activations.framesPerSecond

        val beatFrames = refinedPeakFrames(beat, params)
        val beatsMs = DoubleArray(beatFrames.size) { beatFrames[it] * frameMs }
        val downbeatFrames = refinedPeakFrames(downbeat, params)
        val downbeatsMs = snapToBeats(DoubleArray(downbeatFrames.size) { downbeatFrames[it] * frameMs }, beatsMs)
        val probabilities = DoubleArray(beatsMs.size) {
            sigmoid(windowMax(downbeat, Math.round(beatFrames[it]).toInt(), params.downbeatWindowFrames))
        }
        return BeatCandidates(beatsMs, downbeatsMs, probabilities)
    }

    /**
     * The reference "minimal" post-processor: unrefined peaks, peaks at most one frame apart replaced by their running
     * mean (as the reference does), each downbeat moved to the nearest beat and duplicates dropped. The downbeat
     * probabilities are unknown (`NaN`).
     */
    fun minimal(activations: BeatActivations, params: PeakParams = PeakParams()): BeatCandidates {
        val frameMs = 1000.0 / activations.framesPerSecond
        val beatsMs = referenceMergedPeaks(activations.beat(), params).map { it * frameMs }.toDoubleArray()
        val downbeatMs = referenceMergedPeaks(activations.downbeat(), params).map { it * frameMs }.toDoubleArray()
        return BeatCandidates(beatsMs, snapToBeats(downbeatMs, beatsMs))
    }

    /**
     * The bar phase of [candidates] for a metre of [beatsPerBar] beats: the position `0 until beatsPerBar` such that the
     * beats at indices `phase, phase + beatsPerBar, ...` are the bar starts, chosen where the downbeat probability is
     * highest. Each position is scored by the MEAN probability of its beats rather than the sum, so that a track whose
     * beat count is not a multiple of [beatsPerBar] does not favour the positions with one more beat; unknown (`NaN`)
     * probabilities are skipped. Ties and "nothing known" give 0. The result is the index of the first downbeat within
     * the candidates.
     */
    fun barPhase(candidates: BeatCandidates, beatsPerBar: Int = 4): Int {
        require(beatsPerBar >= 1) { "beatsPerBar must be positive: $beatsPerBar" }
        val probabilities = candidates.downbeatProbabilities()
        val sums = DoubleArray(beatsPerBar)
        val counts = IntArray(beatsPerBar)
        for (i in probabilities.indices) {
            val p = probabilities[i]
            if (p.isNaN()) continue
            sums[i % beatsPerBar] += p
            counts[i % beatsPerBar]++
        }
        var best = 0
        var bestScore = Double.NEGATIVE_INFINITY
        for (phase in 0 until beatsPerBar) {
            if (counts[phase] == 0) continue
            val score = sums[phase] / counts[phase]
            if (score > bestScore) {
                bestScore = score
                best = phase
            }
        }
        return best
    }

    // --- peak picking ---------------------------------------------------------------------------------------------

    /** Frames that equal the max of their pool window and exceed the threshold, in increasing order. */
    private fun pickPeaks(logits: FloatArray, params: PeakParams): IntArray {
        val radius = params.poolRadius
        val peaks = IntArray(logits.size)
        var count = 0
        for (i in logits.indices) {
            val v = logits[i]
            if (!(v > params.threshold)) continue
            var isMax = true
            var j = maxOf(0, i - radius)
            val end = minOf(logits.size - 1, i + radius)
            while (j <= end) {
                if (logits[j] > v) {
                    isMax = false
                    break
                }
                j++
            }
            if (isMax) peaks[count++] = i
        }
        return peaks.copyOf(count)
    }

    /**
     * Groups peaks on consecutive frames (a plateau of equal values), takes the centre of each group, and refines a
     * lone peak by parabolic interpolation. Returns fractional frame positions, strictly increasing.
     */
    private fun refinedPeakFrames(logits: FloatArray, params: PeakParams): DoubleArray {
        val peaks = pickPeaks(logits, params)
        val out = DoubleArray(peaks.size)
        var count = 0
        var i = 0
        while (i < peaks.size) {
            var j = i
            while (j + 1 < peaks.size && peaks[j + 1] - peaks[j] <= 1) j++
            out[count++] = if (i == j) refine(logits, peaks[i]) else (peaks[i] + peaks[j]) / 2.0
            i = j + 1
        }
        return out.copyOf(count)
    }

    /** Vertex of the parabola through frames `i - 1..i + 1`, within half a frame of [i]; [i] itself at the edges. */
    private fun refine(logits: FloatArray, i: Int): Double {
        if (i == 0 || i == logits.size - 1) return i.toDouble()
        val left = logits[i - 1].toDouble()
        val centre = logits[i].toDouble()
        val right = logits[i + 1].toDouble()
        val curvature = left - 2 * centre + right
        if (!(curvature < 0)) return i.toDouble()
        val offset = 0.5 * (left - right) / curvature
        return i + offset.coerceIn(-0.5, 0.5)
    }

    /** The reference's `deduplicate_peaks(peaks, 1)`: the running mean absorbs the next peak while it is within 1 frame. */
    private fun referenceMergedPeaks(logits: FloatArray, params: PeakParams): List<Double> {
        val peaks = pickPeaks(logits, params)
        if (peaks.isEmpty()) return emptyList()
        val merged = ArrayList<Double>()
        var mean = peaks[0].toDouble()
        var members = 1
        for (k in 1 until peaks.size) {
            val next = peaks[k]
            if (next - mean <= 1) {
                members++
                mean += (next - mean) / members
            } else {
                merged.add(mean)
                mean = next.toDouble()
                members = 1
            }
        }
        merged.add(mean)
        return merged
    }

    /** Moves every downbeat to its nearest beat; the result is strictly increasing (duplicates are dropped). */
    private fun snapToBeats(downbeatsMs: DoubleArray, beatsMs: DoubleArray): DoubleArray {
        if (beatsMs.isEmpty()) return DoubleArray(0)
        val snapped = java.util.TreeSet<Double>()
        for (d in downbeatsMs) {
            var nearest = beatsMs[0]
            for (b in beatsMs) if (abs(b - d) < abs(nearest - d)) nearest = b
            snapped.add(nearest)
        }
        return snapped.toDoubleArray()
    }

    // --- probabilities --------------------------------------------------------------------------------------------

    /** Largest finite logit within [halfWidth] frames of [centre], or `NaN` when there is none. */
    private fun windowMax(logits: FloatArray, centre: Int, halfWidth: Int): Double {
        var best = Double.NaN
        for (j in maxOf(0, centre - halfWidth)..minOf(logits.size - 1, centre + halfWidth)) {
            val v = logits[j].toDouble()
            if (v.isFinite() && (best.isNaN() || v > best)) best = v
        }
        return best
    }

    private fun sigmoid(logit: Double): Double = if (logit.isNaN()) Double.NaN else 1.0 / (1.0 + exp(-logit))
}
