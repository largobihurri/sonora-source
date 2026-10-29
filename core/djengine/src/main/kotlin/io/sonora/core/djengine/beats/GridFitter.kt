package io.sonora.core.djengine.beats

import io.sonora.core.djmodel.BeatCandidates
import io.sonora.core.djmodel.DjBeatGrid
import io.sonora.core.djmodel.TempoSegment
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.round

/**
 * Knobs of [GridFitter.fitConstant], with defaults the calibration harness can tune.
 *
 * - [matchToleranceFraction]: how far (as a fraction of the beat length) a detection may sit from the fitted line and
 *   still count as that beat; 0.12 is 56 ms at 128 BPM. Detections half a beat away (the model firing twice per click)
 *   are far outside it.
 * - [snapToleranceMs]: the BPM is snapped to a whole number when doing so moves the beats, over the whole track, by no
 *   more than this many ms (studio tracks are whole-BPM; a fitted 127.998 is noise).
 * - [minBeats]: fewer detections than this give no grid.
 * - [minCoverage]: the share of the beat slots between the first and the last matched detection that must have one;
 *   below it the detections show no steady pulse.
 * - [halfBeatSupport]: when the bars do not confirm the chosen tempo, it is doubled (if the double is in the prior's
 *   range) once the half-beat slots hold at least this many detections per detection on a beat slot. Above 1 it
 *   practically never doubles.
 *
 * The rest are for [GridFitter.fit], which looks for tempo changes after the constant fit:
 * - [driftToleranceMs] and [driftFraction]: the track is taken to change tempo when more than [driftFraction] of its
 *   beats sit further than [driftToleranceMs] from the best constant line (a few misplaced detections do not count).
 * - [regionToleranceMs] and [regionConfirmBeats]: a constant-tempo region ends when [regionConfirmBeats] beats in a
 *   row land further than [regionToleranceMs] from where the region's own line predicts them. [driftToleranceMs] and
 *   [regionToleranceMs] are floors: both rise with the noise of the detections (4 and 2.5 standard deviations of
 *   their timing), so a jittery tracker is not cut into segments by its jitter.
 * - [minRegionBeats]: a region with fewer beats is noise, not a tempo (it is dropped, or merged into a neighbour).
 * - [regionSnapToleranceMs]: the whole-BPM snap of a region of a variable-tempo grid; tighter than
 *   [snapToleranceMs] because a ramp is made of regions whose tempos are not whole numbers.
 */
class GridFitOptions(
    val matchToleranceFraction: Double = 0.12,
    val snapToleranceMs: Double = 8.0,
    val minBeats: Int = 8,
    val minCoverage: Double = 0.3,
    val driftToleranceMs: Double = 25.0,
    val driftFraction: Double = 0.05,
    val regionToleranceMs: Double = 4.0,
    val regionConfirmBeats: Int = 3,
    val minRegionBeats: Int = 5,
    val regionSnapToleranceMs: Double = 2.0,
    val halfBeatSupport: Double = 0.5,
) {
    init {
        require(matchToleranceFraction > 0 && matchToleranceFraction < 0.5) { "matchToleranceFraction must be in (0, 0.5)" }
        require(snapToleranceMs >= 0) { "snapToleranceMs must not be negative" }
        require(minBeats >= 3) { "minBeats must be at least 3" }
        require(minCoverage in 0.0..1.0) { "minCoverage must be in 0..1" }
        require(driftToleranceMs > 0) { "driftToleranceMs must be positive" }
        require(driftFraction in 0.0..1.0) { "driftFraction must be in 0..1" }
        require(regionToleranceMs > 0) { "regionToleranceMs must be positive" }
        require(regionConfirmBeats >= 1) { "regionConfirmBeats must be at least 1" }
        require(minRegionBeats >= 3) { "minRegionBeats must be at least 3" }
        require(regionSnapToleranceMs >= 0) { "regionSnapToleranceMs must not be negative" }
        require(halfBeatSupport > 0) { "halfBeatSupport must be positive" }
    }
}

/**
 * Fits one constant-tempo beat grid to the beat and downbeat detections of a track.
 *
 * The method follows the idea of Mixxx's constant-region beat grid (find the stretch where beats keep one inter-beat
 * interval, fit a line to it), written here from the idea rather than the code:
 *
 * 1. The dominant inter-beat interval comes from a log-spaced histogram of the gaps between consecutive detections
 *    (twin detections a few ms apart are merged first). Because a tracker may fire at half or twice the true rate,
 *    the interval is only known up to a power of two.
 * 2. The octave is chosen by [TempoPrior]: the multiples inside its range, each scored by the prior's preference and
 *    boosted when it agrees with a quarter of the typical downbeat spacing (a bar is four beats) and the downbeats are
 *    sparse enough to be bar starts (the small model marks most plain clicks as downbeats; those say nothing). A candidate whose
 *    grid does not explain the detections is dropped for the next one. A tempo the bars do not confirm is doubled when
 *    the detections fill its half beats too (see [GridFitOptions.halfBeatSupport]).
 * 3. A grid line `time = offset + interval * index` is grown outward from the best-supported stretch of detections:
 *    each detection is assigned to the beat slot the line predicts and accepted when it is within the tolerance, so
 *    detections between beats (doubled detections), spurious ones and gaps in the detections never reach the fit.
 *    When that line covers too little of the track, the line with the most detections over the whole track, searched
 *    a fraction of a percent either side of the interval, gets a second chance.
 * 4. The line is polished with an iteratively reweighted (Tukey biweight) least squares over all detections.
 * 5. The tempo is snapped to a whole BPM when the whole-number grid stays within [GridFitOptions.snapToleranceMs] of
 *    the fit over the track, and otherwise rounded to 0.01 BPM; the phase is refitted for the final tempo.
 * 6. The grid starts at the first beat at or after 0 ms. The bar position of that beat is voted by the downbeats that
 *    lie on the grid, soft evidence: the mean downbeat probability of the grid beats in each phase when the candidates
 *    carry probabilities, otherwise the count of listed downbeats (a bar phase of 1 when there is no evidence).
 *
 * The detections carry no confidence in [BeatCandidates], so the fit decides from their timing alone.
 */
object GridFitter {

    /**
     * The constant grid for [candidates] over a track of [durationMs], or `null` when there are too few detections
     * (fewer than [GridFitOptions.minBeats] inside the track) or they show no steady pulse. The result has one segment.
     */
    fun fitConstant(
        candidates: BeatCandidates,
        durationMs: Double,
        prior: TempoPrior = TempoPrior.DEFAULT,
        options: GridFitOptions = GridFitOptions(),
    ): DjBeatGrid? {
        require(durationMs.isFinite() && durationMs > 0) { "durationMs must be positive: $durationMs" }
        val allBeats = candidates.beatsMs()
        val allProbabilities = candidates.downbeatProbabilities()
        val inside = allBeats.indices.filter { allBeats[it] in 0.0..durationMs }
        val beats = DoubleArray(inside.size) { allBeats[inside[it]] }
        val probabilities = DoubleArray(inside.size) { allProbabilities[inside[it]] }
        if (beats.size < options.minBeats) return null
        val downbeats = candidates.downbeatsMs().filter { it in 0.0..durationMs }.toDoubleArray()

        val coarse = coarseInterval(beats, prior) ?: return null
        val barBeat = if (downbeats.size * 2 <= beats.size) barBeatInterval(downbeats) else null
        for (bpm in octaveOrder(MS_PER_MINUTE / coarse, barBeat, prior)) {
            val fit = fitLine(beats, downbeats, MS_PER_MINUTE / bpm, options) ?: continue
            val faster = if (barsAgree(bpm, barBeat)) null else fasterOctave(fit, beats, downbeats, bpm, prior, options)
            return buildGrid(faster ?: fit, BarEvidence(beats, probabilities, downbeats), durationMs, options)
        }
        return null
    }

    /**
     * The beat grid of [candidates] over a track of [durationMs], with tempo changes when the track has them, or `null`
     * when [fitConstant] finds no grid.
     *
     * It fits the constant grid first and returns it as it is when it explains the beats. Only when the beats drift
     * away from it (see [GridFitOptions.driftToleranceMs]) does it cut the beats into constant-tempo regions and give
     * each region its own [TempoSegment]; see [VariableTempo] for how. A track with one tempo always gives exactly the
     * [fitConstant] grid.
     */
    fun fit(
        candidates: BeatCandidates,
        durationMs: Double,
        prior: TempoPrior = TempoPrior.DEFAULT,
        options: GridFitOptions = GridFitOptions(),
    ): DjBeatGrid? {
        val constant = fitConstant(candidates, durationMs, prior, options) ?: return null
        return VariableTempo.segment(candidates, durationMs, constant, options) ?: constant
    }

    // ---- Step 1: the dominant interval ----

    /** Smallest and largest interval (ms) the histogram looks at, wide enough to see half and double the range's tempos. */
    private fun intervalBounds(prior: TempoPrior): Pair<Double, Double> =
        MS_PER_MINUTE / (prior.maxBpm * OCTAVE_SLACK) to MS_PER_MINUTE / (prior.minBpm / OCTAVE_SLACK)

    private fun coarseInterval(times: DoubleArray, prior: TempoPrior): Double? {
        val first = histogramPeak(times, prior) ?: return null
        val merged = mergeTwins(times, first)
        return if (merged.size == times.size) first else histogramPeak(merged, prior) ?: first
    }

    /** Drops each detection that follows the previous kept one by less than a third of [interval]. */
    private fun mergeTwins(times: DoubleArray, interval: Double): DoubleArray {
        val out = ArrayList<Double>(times.size)
        for (t in times) if (out.isEmpty() || t - out.last() >= TWIN_FRACTION * interval) out += t
        return out.toDoubleArray()
    }

    /** The most common gap between consecutive detections (ms), or `null` when no gap is common enough. */
    private fun histogramPeak(times: DoubleArray, prior: TempoPrior): Double? {
        val (low, high) = intervalBounds(prior)
        val binCount = ceil(ln(high / low) / LN_BIN_STEP).toInt() + 1
        val counts = DoubleArray(binCount)
        for (i in 1 until times.size) {
            val gap = times[i] - times[i - 1]
            if (gap < low || gap >= high) continue
            counts[(ln(gap / low) / LN_BIN_STEP).toInt()] += 1.0
        }
        var best = -1
        var bestScore = 0.0
        for (b in 0 until binCount) {
            val score = counts[b] * 2 + counts.getOrElse(b - 1) { 0.0 } + counts.getOrElse(b + 1) { 0.0 }
            if (score > bestScore) {
                bestScore = score
                best = b
            }
        }
        if (best < 0 || bestScore < MIN_PEAK_SCORE) return null
        // The bin only locates the peak; the gaps around it, re-centred on their median a few times, give its position.
        var center = low * exp((best + 0.5) * LN_BIN_STEP)
        repeat(PEAK_REFINE_PASSES) {
            val near = ArrayList<Double>()
            for (i in 1 until times.size) {
                val gap = times[i] - times[i - 1]
                if (abs(gap / center - 1) <= PEAK_REFINE_FRACTION) near += gap
            }
            if (near.isNotEmpty()) center = median(near.toDoubleArray())
        }
        return center
    }

    // ---- Step 2: the octave ----

    /**
     * The tempos to try, best first: the octave multiples of [bpm] by prior weight, boosted when they agree with
     * [barBeat], the bar-length hint (`null` when the downbeats give none).
     */
    private fun octaveOrder(bpm: Double, barBeat: Double?, prior: TempoPrior): List<Double> {
        val options = prior.candidates(bpm).ifEmpty { listOf(prior.fold(bpm)) }
        return options.sortedByDescending { tempo -> prior.weight(tempo) * if (barsAgree(tempo, barBeat)) BAR_HINT_BOOST else 1.0 }
    }

    /** Whether [tempo] has the beat length of the bar-length hint [barBeat]. */
    private fun barsAgree(tempo: Double, barBeat: Double?): Boolean =
        barBeat != null && abs(MS_PER_MINUTE / tempo / barBeat - 1) <= BAR_HINT_TOLERANCE

    /**
     * The line at twice [bpm] when the detections also fill the half beats of [fit]: at least
     * [GridFitOptions.halfBeatSupport] times as many half-beat slots hold a detection as beat slots do. A track the
     * prior would rather hear at half its tempo (178 as 89 BPM) has the model's beats on both, and the slow line simply
     * ignores every other one. It is not asked when the bars agree with [bpm] (they say the detections between its
     * beats are doubled ones), nor when nearly every beat of [fit] and no half beat is marked as a downbeat (how the
     * small model doubles a click track). `null` keeps [fit]: twice [bpm] is above the prior's range, the half beats
     * are mostly empty, or no line fits at the faster tempo.
     */
    private fun fasterOctave(
        fit: LineFit,
        times: DoubleArray,
        downbeats: DoubleArray,
        bpm: Double,
        prior: TempoPrior,
        options: GridFitOptions,
    ): LineFit? {
        if (bpm * 2 > prior.maxBpm) return null
        val half = fit.interval / 2
        val tolerance = options.matchToleranceFraction * half
        val size = max(times.size, downbeats.size)
        val slot = IntArray(size)
        val matched = DoubleArray(size)
        val halfBeats = assign(times, fit.offset + half, fit.interval, tolerance, slot, matched)
        val beats = assign(times, fit.offset, fit.interval, tolerance, slot, matched)
        if (halfBeats < options.halfBeatSupport * beats) return null
        // A click track the small model doubles: it marks nearly every click as a downbeat and no detection between.
        val downbeatsOnBeats = assign(downbeats, fit.offset, fit.interval, tolerance, slot, matched)
        val downbeatsOnHalfBeats = assign(downbeats, fit.offset + half, fit.interval, tolerance, slot, matched)
        if (downbeatsOnBeats >= CLICK_DOWNBEAT_SHARE * beats && downbeatsOnHalfBeats * 4 < downbeatsOnBeats) return null
        return fitLine(times, downbeats, half, options)
    }

    /** A quarter of the typical gap between downbeats (ms), or `null` with fewer than four downbeats. */
    private fun barBeatInterval(downbeats: DoubleArray): Double? {
        if (downbeats.size < MIN_DOWNBEATS_FOR_HINT) return null
        val gaps = DoubleArray(downbeats.size - 1) { downbeats[it + 1] - downbeats[it] }
        return median(gaps) / BEATS_PER_BAR
    }

    // ---- Steps 3 and 4: the line ----

    /** `time = offset + interval * index` over the matched detections (`indices`, `times`, same length). */
    private class LineFit(
        val offset: Double,
        val interval: Double,
        val indices: IntArray,
        val times: DoubleArray,
    ) {
        val firstIndex: Int get() = indices.first()
        val lastIndex: Int get() = indices.last()
    }

    private fun fitLine(times: DoubleArray, downbeats: DoubleArray, nominal: Double, options: GridFitOptions): LineFit? {
        val tolerance = options.matchToleranceFraction * nominal
        val seed = findSeed(times, downbeats, nominal, tolerance)
        val grown = GrowingLine(times.size, nominal)
        grown.add(0, times[seed], 0.0)
        for (j in seed + 1 until times.size) grown.offer(times[j], tolerance, 1)
        grown.rewindToSeed()
        for (j in seed - 1 downTo 0) grown.offer(times[j], tolerance, -1)

        val interval = grown.interval()
        return polish(times, grown.offset(interval), interval, tolerance, options)
            ?: globalLine(times, nominal, tolerance)?.let { (offset, global) -> polish(times, offset, global, tolerance, options) }
    }

    /**
     * The line at about [nominal] spacing that matches the most detections over the whole track, as `offset` and
     * `interval`, or `null` when none matches [MIN_INLIERS]. The second chance of [fitLine]: a line grown from a
     * jittery stretch can start a fraction of a percent off and lose the beat before the rest of the track corrects
     * it (a 150 BPM track grown at 150.24 matched one beat slot in five). The intervals tried are [GLOBAL_SEARCH]
     * either side of [nominal], in steps that drift by half a [tolerance] over the detections' span; each takes the
     * phase whose window holds the most detections.
     */
    private fun globalLine(times: DoubleArray, nominal: Double, tolerance: Double): Pair<Double, Double>? {
        val beatsSpanned = max((times.last() - times.first()) / nominal, 1.0)
        val step = tolerance / 2 / beatsSpanned
        val steps = ceil(nominal * GLOBAL_SEARCH / step).toInt()
        val bins = IntArray(PHASE_BINS)
        val window = ceil(tolerance / nominal * PHASE_BINS).toInt()
        var bestHits = 0
        var best: Pair<Double, Double>? = null
        for (k in -steps..steps) {
            val interval = nominal + k * step
            bins.fill(0)
            for (t in times) bins[((t / interval).mod(1.0) * PHASE_BINS).toInt().coerceIn(0, PHASE_BINS - 1)]++
            for (b in 0 until PHASE_BINS) {
                var hits = 0
                for (d in -window..window) hits += bins[(b + d).mod(PHASE_BINS)]
                if (hits > bestHits) {
                    bestHits = hits
                    best = (b + 0.5) / PHASE_BINS * interval to interval
                }
            }
        }
        return if (bestHits >= MIN_INLIERS) best else null
    }

    /**
     * [REWEIGHT_PASSES] of robust refitting from the line `offset + interval * index`, then the detections the final
     * line and weights keep, or `null` when too few match or they cover less than [GridFitOptions.minCoverage].
     */
    private fun polish(times: DoubleArray, startOffset: Double, startInterval: Double, tolerance: Double, options: GridFitOptions): LineFit? {
        var interval = startInterval
        var offset = startOffset
        val slot = IntArray(times.size)
        val matched = DoubleArray(times.size)
        val weights = DoubleArray(times.size)
        var count = 0
        repeat(REWEIGHT_PASSES) {
            count = assign(times, offset, interval, tolerance, slot, matched)
            if (count < MIN_INLIERS) return null
            robustWeights(slot, matched, count, offset, interval, weights)
            val line = weightedLine(slot, matched, weights, count) ?: return null
            offset = line[0]
            interval = line[1]
        }
        // Keep the detections the final line and weights accept.
        count = assign(times, offset, interval, tolerance, slot, matched)
        if (count < MIN_INLIERS) return null
        robustWeights(slot, matched, count, offset, interval, weights)
        val keep = (0 until count).filter { weights[it] > 0 }
        if (keep.size < MIN_INLIERS) return null
        val indices = IntArray(keep.size) { slot[keep[it]] }
        val inliers = DoubleArray(keep.size) { matched[keep[it]] }
        val coverage = keep.size.toDouble() / (indices.last() - indices.first() + 1)
        if (coverage < options.minCoverage) return null
        return LineFit(offset, interval, indices, inliers)
    }

    /**
     * The detection that starts the longest clean run of beats at [nominal] spacing: for each one, how many of the next
     * dozen beat slots hold a detection near where the nominal spacing puts them. A detection on a downbeat wins a tie:
     * a tracker that fires between the beats as well as on them gives two equally regular lines half a beat apart, and
     * the downbeats, which sit on real beats, say which one is the beat. Earliest wins what is still tied.
     */
    internal fun findSeed(times: DoubleArray, downbeats: DoubleArray, nominal: Double, tolerance: Double): Int {
        var best = 0
        var bestScore = -1
        for (i in times.indices) {
            var mask = 0L
            var j = i + 1
            while (j < times.size && times[j] - times[i] < (SEED_HORIZON_BEATS + 0.5) * nominal) {
                val gap = times[j] - times[i]
                val n = round(gap / nominal).toInt()
                if (n >= 1 && abs(gap - n * nominal) <= tolerance) mask = mask or (1L shl n)
                j++
            }
            val onDownbeat = if (hasNear(downbeats, times[i], tolerance)) 1 else 0
            val score = java.lang.Long.bitCount(mask) * 2 + onDownbeat
            if (score > bestScore) {
                bestScore = score
                best = i
            }
        }
        return best
    }

    /** Whether the sorted [values] hold one within [tolerance] of [x]. */
    internal fun hasNear(values: DoubleArray, x: Double, tolerance: Double): Boolean {
        if (values.isEmpty()) return false
        var index = java.util.Arrays.binarySearch(values, x)
        if (index < 0) index = -index - 1
        return (index < values.size && values[index] - x <= tolerance) || (index > 0 && x - values[index - 1] <= tolerance)
    }

    /**
     * Assigns each detection of [times] to its beat slot on `offset + interval * slot`, keeping, per slot, the one
     * closest to the line, and only if it is within [tolerance]. Fills [slot] and [matched]; returns how many.
     */
    private fun assign(
        times: DoubleArray,
        offset: Double,
        interval: Double,
        tolerance: Double,
        slot: IntArray,
        matched: DoubleArray,
    ): Int {
        var count = 0
        var lastResidual = 0.0
        for (t in times) {
            val n = round((t - offset) / interval).toInt()
            val residual = abs(t - (offset + interval * n))
            if (residual > tolerance) continue
            if (count > 0 && slot[count - 1] == n) {
                if (residual < lastResidual) {
                    matched[count - 1] = t
                    lastResidual = residual
                }
                continue
            }
            slot[count] = n
            matched[count] = t
            lastResidual = residual
            count++
        }
        return count
    }

    /** Tukey biweights of the residuals of the first [count] matches against the line (0 beyond the cut-off). */
    internal fun robustWeights(
        slot: IntArray,
        matched: DoubleArray,
        count: Int,
        offset: Double,
        interval: Double,
        weights: DoubleArray,
    ) {
        val residuals = DoubleArray(count) { abs(matched[it] - (offset + interval * slot[it])) }
        val scale = max(MAD_TO_SIGMA * median(residuals), MIN_SCALE_MS)
        val cutoff = TUKEY_C * scale
        for (i in 0 until count) {
            val u = residuals[i] / cutoff
            weights[i] = if (u < 1) (1 - u * u).pow(2) else 0.0
        }
    }

    /** Weighted least squares of `time = offset + interval * slot`; `[offset, interval]`, or `null` when degenerate. */
    internal fun weightedLine(slot: IntArray, matched: DoubleArray, weights: DoubleArray, count: Int): DoubleArray? {
        var sw = 0.0
        var sn = 0.0
        var st = 0.0
        val refSlot = slot[0]
        val refTime = matched[0]
        for (i in 0 until count) {
            val w = weights[i]
            sw += w
            sn += w * (slot[i] - refSlot)
            st += w * (matched[i] - refTime)
        }
        if (sw <= 0) return null
        val meanN = sn / sw
        val meanT = st / sw
        var snn = 0.0
        var snt = 0.0
        for (i in 0 until count) {
            val dn = slot[i] - refSlot - meanN
            snn += weights[i] * dn * dn
            snt += weights[i] * dn * (matched[i] - refTime - meanT)
        }
        if (snn <= 0) return null
        val interval = snt / snn
        val offset = refTime + meanT - interval * (refSlot + meanN)
        return doubleArrayOf(offset, interval)
    }

    /**
     * A line grown one detection at a time from a seed (slot 0): the interval stays the nominal one until the
     * detections are a handful and span [MIN_SPAN_FOR_SLOPE] beats, then follows their least squares fit (within 5 %
     * of nominal). Over a short span a few milliseconds of jitter tilt the slope: six intro beats 10-20 ms off set a
     * 138 BPM line 1 % slow, and it lost the beat within a few bars of a 12-minute track.
     */
    private class GrowingLine(capacity: Int, private val nominal: Double) {
        private val slots = IntArray(capacity)
        private val times = DoubleArray(capacity)
        private var count = 0
        private var sn = 0.0
        private var st = 0.0
        private var snn = 0.0
        private var snt = 0.0
        private var lastSlot = 0
        private var lastResidual = 0.0
        private var lastIndex = 0
        private var minSlot = Int.MAX_VALUE
        private var maxSlot = Int.MIN_VALUE

        fun add(slot: Int, time: Double, residual: Double) {
            minSlot = minOf(minSlot, slot)
            maxSlot = maxOf(maxSlot, slot)
            slots[count] = slot
            times[count] = time
            sn += slot
            st += time
            snn += slot.toDouble() * slot
            snt += slot * time
            lastIndex = count
            lastSlot = slot
            lastResidual = residual
            count++
        }

        private fun replaceLast(slot: Int, time: Double, residual: Double) {
            val oldSlot = slots[lastIndex]
            val oldTime = times[lastIndex]
            sn += slot - oldSlot
            st += time - oldTime
            snn += slot.toDouble() * slot - oldSlot.toDouble() * oldSlot
            snt += slot * time - oldSlot * oldTime
            slots[lastIndex] = slot
            times[lastIndex] = time
            lastResidual = residual
        }

        /** Makes the seed (the first detection added) the last one again, for growing the other way. */
        fun rewindToSeed() {
            lastIndex = 0
            lastSlot = 0
            lastResidual = 0.0
        }

        fun interval(): Double {
            if (count < MIN_FOR_SLOPE || maxSlot - minSlot < MIN_SPAN_FOR_SLOPE) return nominal
            val denominator = count * snn - sn * sn
            if (denominator <= 0) return nominal
            val slope = (count * snt - sn * st) / denominator
            return if (abs(slope / nominal - 1) > MAX_INTERVAL_DRIFT) nominal else slope
        }

        fun offset(interval: Double): Double = (st - interval * sn) / count

        /** Offers [time], moving in direction [dir] (+1 forward, -1 backward) from the seed. */
        fun offer(time: Double, tolerance: Double, dir: Int) {
            val interval = interval()
            val offset = offset(interval)
            val n = round((time - offset) / interval).toInt()
            val residual = abs(time - (offset + interval * n))
            if (residual > tolerance) return
            val ahead = (n - lastSlot) * dir
            if (ahead > 0) add(n, time, residual) else if (ahead == 0 && residual < lastResidual) replaceLast(n, time, residual)
        }
    }

    // ---- Steps 5 and 6: tempo, phase, bars ----

    private fun buildGrid(fit: LineFit, bars: BarEvidence, durationMs: Double, options: GridFitOptions): DjBeatGrid {
        val spanBeats = max((fit.lastIndex - fit.firstIndex).toDouble(), durationMs / fit.interval)
        val bpm = snapBpm(fit.interval, spanBeats, options.snapToleranceMs)
        val interval = MS_PER_MINUTE / bpm

        val offset = robustOffset(fit, interval)
        val firstIndex = ceil((-START_SLACK_MS - offset) / interval).toInt()
        val start = max(0.0, offset + firstIndex * interval)

        val barPhase = barPhase(bars, offset, interval, options.matchToleranceFraction * interval) ?: Math.floorMod(firstIndex, BEATS_PER_BAR)
        val beatInBar = Math.floorMod(firstIndex - barPhase, BEATS_PER_BAR) + 1
        return DjBeatGrid(listOf(TempoSegment(start, bpm, beatInBar)))
    }

    /**
     * The tempo (BPM) of a line with beat length [interval] ms over [spanBeats] beats: the whole number when its grid
     * moves the beats by at most [toleranceMs] over the span, otherwise the fit rounded to 0.01.
     */
    internal fun snapBpm(interval: Double, spanBeats: Double, toleranceMs: Double): Double {
        val fitted = MS_PER_MINUTE / interval
        val whole = round(fitted)
        val snapped = whole >= 1 && abs(MS_PER_MINUTE / whole - interval) * spanBeats <= toleranceMs
        return if (snapped) whole else round(fitted * 100) / 100
    }

    /** The offset of the line with a fixed [interval]: the Tukey-weighted mean of `time - interval * index`. */
    private fun robustOffset(fit: LineFit, interval: Double): Double = robustOffset(fit.indices, fit.times, interval)

    /** The same for matched [times] at beat [slots]. */
    internal fun robustOffset(slots: IntArray, times: DoubleArray, interval: Double): Double {
        val count = slots.size
        val residuals = DoubleArray(count) { times[it] - interval * slots[it] }
        var offset = median(residuals)
        repeat(OFFSET_PASSES) {
            val deviations = DoubleArray(count) { abs(residuals[it] - offset) }
            val cutoff = TUKEY_C * max(MAD_TO_SIGMA * median(deviations), MIN_SCALE_MS)
            var sw = 0.0
            var sum = 0.0
            for (i in 0 until count) {
                val u = deviations[i] / cutoff
                if (u >= 1) continue
                val w = (1 - u * u).pow(2)
                sw += w
                sum += w * residuals[i]
            }
            if (sw > 0) offset = sum / sw
        }
        return offset
    }

    /** What the candidates say about bars: every beat with its downbeat probability (`NaN` unknown), and the listed downbeats. */
    internal class BarEvidence(val beats: DoubleArray, val probabilities: DoubleArray, val downbeats: DoubleArray)

    /**
     * The slot index modulo the bar length at which bars start (slots are counted from the line's origin), or `null`
     * when there is no evidence on the grid. With probabilities: the phase whose grid beats have the highest mean
     * downbeat probability (the model marks many plain beats as downbeats, so only the contrast between phases counts).
     * Without: the phase most listed downbeats on the grid fall in.
     */
    internal fun barPhase(bars: BarEvidence, offset: Double, interval: Double, tolerance: Double): Int? {
        val sums = DoubleArray(BEATS_PER_BAR)
        val counts = IntArray(BEATS_PER_BAR)
        for (i in bars.beats.indices) {
            val p = bars.probabilities[i]
            if (p.isNaN()) continue
            val n = round((bars.beats[i] - offset) / interval).toInt()
            if (abs(bars.beats[i] - (offset + interval * n)) > tolerance) continue
            sums[Math.floorMod(n, BEATS_PER_BAR)] += p
            counts[Math.floorMod(n, BEATS_PER_BAR)]++
        }
        if (counts.all { it > 0 }) {
            return (0 until BEATS_PER_BAR).maxByOrNull { sums[it] / counts[it] - it * TIE_EPSILON }
        }
        val votes = IntArray(BEATS_PER_BAR)
        for (d in bars.downbeats) {
            val n = round((d - offset) / interval).toInt()
            if (abs(d - (offset + interval * n)) <= tolerance) votes[Math.floorMod(n, BEATS_PER_BAR)]++
        }
        val best = votes.indices.maxByOrNull { votes[it] * BEATS_PER_BAR - it } ?: return null
        return if (votes[best] == 0) null else best
    }

    internal fun median(values: DoubleArray): Double {
        val sorted = values.sortedArray()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    internal const val MS_PER_MINUTE = 60_000.0
    internal const val BEATS_PER_BAR = 4

    /** Makes the lowest phase win a tie in the probability vote. */
    private const val TIE_EPSILON = 1e-12

    /** The histogram reaches this factor beyond the range, so half and double tempos of its ends are seen. */
    private const val OCTAVE_SLACK = 2.5
    private val LN_BIN_STEP = ln(1.01)
    private const val MIN_PEAK_SCORE = 6.0
    private const val PEAK_REFINE_FRACTION = 0.05
    private const val PEAK_REFINE_PASSES = 4
    private const val TWIN_FRACTION = 0.3

    private const val BAR_HINT_TOLERANCE = 0.04
    private const val BAR_HINT_BOOST = 2.0
    private const val MIN_DOWNBEATS_FOR_HINT = 4

    private const val SEED_HORIZON_BEATS = 12
    private const val MIN_FOR_SLOPE = 6
    private const val MIN_SPAN_FOR_SLOPE = 16
    private const val MAX_INTERVAL_DRIFT = 0.05
    private const val GLOBAL_SEARCH = 0.005
    private const val PHASE_BINS = 64
    private const val CLICK_DOWNBEAT_SHARE = 0.9
    private const val REWEIGHT_PASSES = 5
    private const val MIN_INLIERS = 6
    private const val MAD_TO_SIGMA = 1.4826
    private const val MIN_SCALE_MS = 1.0
    private const val TUKEY_C = 4.685
    private const val OFFSET_PASSES = 4

    /** A beat up to this far before 0 ms still counts as the beat at 0. */
    internal const val START_SLACK_MS = 1.0
}
