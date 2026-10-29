package io.sonora.core.djengine.beats

import io.sonora.core.djengine.beats.GridFitter.BEATS_PER_BAR
import io.sonora.core.djengine.beats.GridFitter.MS_PER_MINUTE
import io.sonora.core.djengine.beats.GridFitter.START_SLACK_MS
import io.sonora.core.djmodel.BeatCandidates
import io.sonora.core.djmodel.DjBeatGrid
import io.sonora.core.djmodel.TempoSegment
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.round

/**
 * Cuts the beats of a track whose tempo changes into constant-tempo regions and builds a [DjBeatGrid] with one
 * [TempoSegment] per region ([GridFitter.fit] calls it after the constant fit).
 *
 * The idea is the one of Mixxx's constant-region search (a beat grid is a chain of regions inside which the beats keep
 * one interval), written here from the idea rather than the code:
 *
 * 1. **A beat per slot.** From the best-supported detection of the constant fit (its seed) a chain of beats is walked
 *    forward and backward. Each step takes the detection about one local interval away, where the local interval is the
 *    median of the last three gaps, so the chain follows a ramp and survives a step of up to about 30 % in one beat.
 *    Detections half a beat away (the model firing twice per click) and spurious ones are not on the chain; a missing
 *    detection leaves a gap of a whole number of slots. The chain gives `(slot, time)` pairs.
 * 2. **Is there drift at all?** A robust line is fitted to the whole chain; when no more than
 *    [GridFitOptions.driftFraction] of the beats sit beyond [GridFitOptions.driftToleranceMs] from it, the constant fit
 *    stands and nothing changes.
 * 3. **Regions.** The chain is walked once with a running least squares line: a beat joins the region when it lands
 *    within [GridFitOptions.regionToleranceMs] of the region's own prediction; a beat that does not is set aside, and when
 *    [GridFitOptions.regionConfirmBeats] in a row do not, the region ends before the first of them and the next one
 *    starts there. An isolated misplaced detection never ends a region.
 * 4. **Clean-up.** A region with fewer than [GridFitOptions.minRegionBeats] beats, or most of whose beats are
 *    inconsistent with its own line, is noise and is dropped; the regions on both sides of a dropped one are merged
 *    when one line explains them both.
 * 5. **Segments.** The first region becomes the first segment as in [GridFitter.fitConstant]. Each later region is
 *    attached to the grid built so far: its segment starts on the beat of the previous segment that fits its own line
 *    best around the change, so the boundary lies on a beat of the previous segment exactly (what [DjBeatGrid]
 *    requires), the region's tempo is the least squares one through that beat, and the bar position of the boundary
 *    beat is carried across. A region whose tempo equals the previous one is not a segment.
 *
 * Phase between regions follows the previous segment's grid, not the detections after the boundary: where the real
 * tempo change leaves a beat that is not on the previous grid (a break, a late drummer), the new segment keeps the
 * boundary on the previous grid and takes up to half a beat of phase error with it.
 */
internal object VariableTempo {

    /**
     * The grid with tempo changes, or `null` when the beats fit the [constant] grid well enough (or there is no
     * clear region structure), in which case the caller keeps the constant grid.
     */
    fun segment(candidates: BeatCandidates, durationMs: Double, constant: DjBeatGrid, options: GridFitOptions): DjBeatGrid? {
        val allBeats = candidates.beatsMs()
        val allProbabilities = candidates.downbeatProbabilities()
        val inside = allBeats.indices.filter { allBeats[it] in 0.0..durationMs }
        val times = DoubleArray(inside.size) { allBeats[inside[it]] }
        val probabilities = DoubleArray(inside.size) { allProbabilities[inside[it]] }
        val downbeats = candidates.downbeatsMs().filter { it in 0.0..durationMs }.toDoubleArray()

        val nominal = constant.segments.single().beatLengthMs
        val chain = trackBeats(times, downbeats, nominal, options) ?: return null
        val noise = noiseMs(chain)
        if (!drifts(chain, max(options.driftToleranceMs, DRIFT_NOISE_FACTOR * noise), options)) return null
        val regionToleranceMs = max(options.regionToleranceMs, REGION_NOISE_FACTOR * noise)
        val regions = regions(chain, regionToleranceMs, options)
        if (regions.size < 2) return null
        val bars = GridFitter.BarEvidence(times, probabilities, downbeats)
        val grid = build(chain, regions, bars, options) ?: return null
        return if (grid.isConstant) null else grid
    }

    // ---- Step 1: the chain ----

    /** The beats found: `slots[i]` is the beat number (the seed is 0) of the detection at `times[i]`. */
    private class Chain(val slots: IntArray, val times: DoubleArray) {
        val size: Int get() = slots.size
    }

    private fun trackBeats(times: DoubleArray, downbeats: DoubleArray, nominal: Double, options: GridFitOptions): Chain? {
        if (times.size < options.minBeats) return null
        val seed = GridFitter.findSeed(times, downbeats, nominal, options.matchToleranceFraction * nominal)
        val n = times.size
        val forward = walk(times, seed, nominal)
        // Walking backward is walking forward over the mirrored time axis.
        val mirrored = DoubleArray(n) { -times[n - 1 - it] }
        val backward = walk(mirrored, n - 1 - seed, nominal)

        val total = backward.index.size + 1 + forward.index.size
        val slots = IntArray(total)
        val out = DoubleArray(total)
        var k = 0
        for (i in backward.index.indices.reversed()) {
            slots[k] = -backward.slot[i]
            out[k++] = times[n - 1 - backward.index[i]]
        }
        slots[k] = 0
        out[k++] = times[seed]
        for (i in forward.index.indices) {
            slots[k] = forward.slot[i]
            out[k++] = times[forward.index[i]]
        }
        return if (total < options.minBeats) null else Chain(slots, out)
    }

    private class Walk(val index: IntArray, val slot: IntArray)

    /**
     * Follows the beats forward from detection [seed]: each step takes the nearest detection to the next slot of the
     * local interval (the median of the last three gaps, starting from [nominal]); with none close enough the walk
     * resumes at the next detection that is not a twin, taking it for the next beat of a new tempo.
     */
    private fun walk(times: DoubleArray, seed: Int, nominal: Double): Walk {
        val indices = ArrayList<Int>()
        val slots = ArrayList<Int>()
        val recent = ArrayList<Double>(RECENT_GAPS + 1)
        recent += nominal
        var last = seed
        var slot = 0
        while (last < times.size - 1) {
            val interval = GridFitter.median(recent.toDoubleArray())
            var best = -1
            var bestSlots = 0
            var bestError = 0.0
            val horizon = (MAX_SKIPPED_BEATS + 0.5) * interval
            var c = last + 1
            while (c < times.size) {
                val gap = times[c] - times[last]
                if (gap > horizon) break
                val k = floor(gap / interval + 0.5).toInt()
                if (k >= 1) {
                    val error = abs(gap - k * interval) / interval
                    if (error <= STEP_TOLERANCE && (best < 0 || k < bestSlots || (k == bestSlots && error < bestError))) {
                        best = c
                        bestSlots = k
                        bestError = error
                    }
                }
                c++
            }
            if (best >= 0) {
                recent += (times[best] - times[last]) / bestSlots
                if (recent.size > RECENT_GAPS) recent.removeAt(0)
            } else {
                var r = last + 1
                while (r < times.size && times[r] - times[last] < RESUME_MIN_GAP * interval) r++
                if (r >= times.size) break
                val gap = times[r] - times[last]
                bestSlots = if (gap < RESUME_ONE_BEAT_MAX_GAP * interval) 1 else round(gap / interval).toInt()
                best = r
                recent.clear()
                recent += gap / bestSlots
            }
            slot += bestSlots
            indices += best
            slots += slot
            last = best
        }
        return Walk(indices.toIntArray(), slots.toIntArray())
    }

    /**
     * How noisy the beat times are (ms, one standard deviation): the robust scale of the second differences of the chain
     * times, which a steady tempo or a ramp cancels and a tempo step affects at one beat only.
     */
    private fun noiseMs(chain: Chain): Double {
        // Only runs of adjacent slots have a meaningful second difference.
        val values = ArrayList<Double>()
        for (i in 1 until chain.size - 1) {
            if (chain.slots[i] - chain.slots[i - 1] == 1 && chain.slots[i + 1] - chain.slots[i] == 1) {
                values += abs(chain.times[i + 1] - 2 * chain.times[i] + chain.times[i - 1])
            }
        }
        if (values.size < MIN_NOISE_SAMPLES) return 0.0
        return GridFitter.median(values.toDoubleArray()) * MAD_TO_SIGMA_ABS / SECOND_DIFFERENCE_GAIN
    }

    // ---- Step 2: drift ----

    private fun drifts(chain: Chain, toleranceMs: Double, options: GridFitOptions): Boolean {
        val line = robustLine(chain.slots, chain.times) ?: return false
        var far = 0
        for (i in 0 until chain.size) {
            if (abs(chain.times[i] - (line.offset + line.interval * chain.slots[i])) > toleranceMs) far++
        }
        return far > options.driftFraction * chain.size
    }

    // ---- Lines ----

    /** `time = offset + interval * slot` over the points with a non-zero weight (`slots`, `times`, same length). */
    private class Line(val offset: Double, val interval: Double, val slots: IntArray, val times: DoubleArray, val total: Int) {
        val keptFraction: Double get() = slots.size.toDouble() / total
        val span: Int get() = slots.last() - slots.first()
    }

    /** A Tukey-weighted least squares line through the points, or `null` when they do not define one. */
    private fun robustLine(slots: IntArray, times: DoubleArray): Line? {
        val count = slots.size
        if (count < 2) return null
        val weights = DoubleArray(count) { 1.0 }
        var line = GridFitter.weightedLine(slots, times, weights, count) ?: return null
        repeat(ROBUST_PASSES) {
            GridFitter.robustWeights(slots, times, count, line[0], line[1], weights)
            line = GridFitter.weightedLine(slots, times, weights, count) ?: return null
        }
        GridFitter.robustWeights(slots, times, count, line[0], line[1], weights)
        val keep = (0 until count).filter { weights[it] > 0 }
        if (keep.size < 2) return null
        return Line(
            line[0],
            line[1],
            IntArray(keep.size) { slots[keep[it]] },
            DoubleArray(keep.size) { times[keep[it]] },
            count,
        )
    }

    // ---- Step 3 and 4: regions ----

    /** A stretch of the chain: points `from until to`. */
    private class Region(val from: Int, val to: Int) {
        val size: Int get() = to - from
    }

    private fun regions(chain: Chain, toleranceMs: Double, options: GridFitOptions): List<Region> {
        val bounds = scan(chain, toleranceMs, options)
        // Drop the noise, remembering where a dropped region separated two kept ones.
        val kept = ArrayList<Region>()
        val separated = ArrayList<Boolean>()
        var dropped = false
        for (i in bounds.indices) {
            val region = Region(bounds[i], bounds.getOrElse(i + 1) { chain.size })
            if (isRegion(chain, region, options)) {
                kept += region
                separated += dropped
                dropped = false
            } else {
                dropped = true
            }
        }
        // Merge across a dropped region when one line explains both sides.
        val merged = ArrayList<Region>()
        for (i in kept.indices) {
            val previous = merged.lastOrNull()
            if (previous != null && separated[i] && explainedByOneLine(chain, previous, kept[i], toleranceMs)) {
                merged[merged.size - 1] = Region(previous.from, kept[i].to)
            } else {
                merged += kept[i]
            }
        }
        return merged
    }

    private fun isRegion(chain: Chain, region: Region, options: GridFitOptions): Boolean {
        if (region.size < options.minRegionBeats) return false
        val line = fitRegion(chain, region) ?: return false
        return line.keptFraction >= MIN_KEPT_FRACTION
    }

    private fun fitRegion(chain: Chain, region: Region): Line? =
        robustLine(chain.slots.copyOfRange(region.from, region.to), chain.times.copyOfRange(region.from, region.to))

    /** Whether one robust line puts at least 95 % of the beats of both regions within the region tolerance. */
    private fun explainedByOneLine(chain: Chain, a: Region, b: Region, toleranceMs: Double): Boolean {
        val slots = chain.slots.copyOfRange(a.from, a.to) + chain.slots.copyOfRange(b.from, b.to)
        val times = chain.times.copyOfRange(a.from, a.to) + chain.times.copyOfRange(b.from, b.to)
        val line = robustLine(slots, times) ?: return false
        var far = 0
        for (i in slots.indices) if (abs(times[i] - (line.offset + line.interval * slots[i])) > toleranceMs) far++
        return far <= MERGE_OUTLIER_FRACTION * slots.size
    }

    /** The chain index where each region starts (the first is 0): running least squares, set-aside beats, confirmation. */
    private fun scan(chain: Chain, toleranceMs: Double, options: GridFitOptions): List<Int> {
        val starts = ArrayList<Int>()
        starts += 0
        var start = 0
        while (true) {
            val end = minOf(chain.size, start + SCAN_INITIAL_BEATS)
            val sums = RunningLine(chain.slots[start], chain.times[start])
            for (i in start until end) sums.add(chain.slots[i], chain.times[i])
            var misses = 0
            var firstMiss = -1
            var split = -1
            var i = end
            while (i < chain.size) {
                val error = abs(chain.times[i] - sums.predict(chain.slots[i]))
                if (error <= toleranceMs) {
                    sums.add(chain.slots[i], chain.times[i])
                    misses = 0
                } else {
                    if (misses == 0) firstMiss = i
                    misses++
                    if (misses >= options.regionConfirmBeats) {
                        split = firstMiss
                        break
                    }
                }
                i++
            }
            if (split < 0) return starts
            starts += split
            start = split
        }
    }

    /** Incremental least squares of `time` on `slot`, relative to the first point for numerical steadiness. */
    private class RunningLine(private val refSlot: Int, private val refTime: Double) {
        private var n = 0
        private var sx = 0.0
        private var sy = 0.0
        private var sxx = 0.0
        private var sxy = 0.0

        fun add(slot: Int, time: Double) {
            val x = (slot - refSlot).toDouble()
            val y = time - refTime
            n++
            sx += x
            sy += y
            sxx += x * x
            sxy += x * y
        }

        fun predict(slot: Int): Double {
            val denominator = n * sxx - sx * sx
            val x = (slot - refSlot).toDouble()
            if (n < 2 || denominator <= 0) return refTime
            val slope = (n * sxy - sx * sy) / denominator
            val intercept = (sy - slope * sx) / n
            return refTime + intercept + slope * x
        }
    }

    // ---- Step 5: segments ----

    private fun build(chain: Chain, regions: List<Region>, bars: GridFitter.BarEvidence, options: GridFitOptions): DjBeatGrid? {
        val lines = regions.map { fitRegion(chain, it) ?: return null }
        val segments = ArrayList<TempoSegment>()

        val first = lines[0]
        val firstBpm = GridFitter.snapBpm(first.interval, first.span.toDouble(), options.regionSnapToleranceMs)
        val firstInterval = MS_PER_MINUTE / firstBpm
        val offset = GridFitter.robustOffset(first.slots, first.times, firstInterval)
        val firstIndex = ceil((-START_SLACK_MS - offset) / firstInterval).toInt()
        val inRegion = restrict(bars, first.times.first() - firstInterval, first.times.last() + firstInterval)
        val phase = GridFitter.barPhase(inRegion, offset, firstInterval, options.matchToleranceFraction * firstInterval)
            ?: Math.floorMod(firstIndex, BEATS_PER_BAR)
        segments += TempoSegment(
            max(0.0, offset + firstIndex * firstInterval),
            firstBpm,
            Math.floorMod(firstIndex - phase, BEATS_PER_BAR) + 1,
        )

        for (r in 1 until regions.size) {
            val line = lines[r]
            val previous = segments.last()
            val interval = previous.beatLengthMs
            val previousEnd = lines[r - 1].times.last()
            val firstTime = line.times.first()
            // Beats of the grid so far around the change; the one the region's own line has a beat closest to wins.
            val low = max(1, ceil((previousEnd - 2 * interval - previous.startMs) / interval).toInt())
            val high = max(low, floor((firstTime - previous.startMs) / interval).toInt())
            val middle = (previousEnd + firstTime) / 2
            var beats = low
            var bestCost = Double.MAX_VALUE
            for (m in low..high) {
                val time = previous.startMs + m * interval
                val position = (time - line.offset) / line.interval
                val cost = abs(position - round(position)) * line.interval + BOUNDARY_PULL * abs(time - middle)
                if (cost < bestCost) {
                    bestCost = cost
                    beats = m
                }
            }
            val boundary = previous.startMs + beats * interval

            // The tempo of the region: least squares through the boundary beat.
            var sxy = 0.0
            var sxx = 0.0
            for (i in line.slots.indices) {
                val x = round((line.times[i] - boundary) / line.interval)
                sxy += x * (line.times[i] - boundary)
                sxx += x * x
            }
            val bpm = GridFitter.snapBpm(if (sxx > 0) sxy / sxx else line.interval, line.span.toDouble(), options.regionSnapToleranceMs)
            // The same tempo as before is the same beats: not a new segment.
            if (bpm == previous.bpm) continue
            val beatInBar = Math.floorMod(previous.beatInBarAtStart - 1 + beats, BEATS_PER_BAR) + 1
            segments += TempoSegment(boundary, bpm, beatInBar)
        }
        return DjBeatGrid(segments)
    }

    /** The evidence about bars that falls in `[from, to]`. */
    private fun restrict(bars: GridFitter.BarEvidence, from: Double, to: Double): GridFitter.BarEvidence {
        val keep = bars.beats.indices.filter { bars.beats[it] in from..to }
        return GridFitter.BarEvidence(
            DoubleArray(keep.size) { bars.beats[keep[it]] },
            DoubleArray(keep.size) { bars.probabilities[keep[it]] },
            bars.downbeats.filter { it in from..to }.toDoubleArray(),
        )
    }

    /** Detections skipped by a step are looked for up to this many beats ahead. */
    private const val MAX_SKIPPED_BEATS = 4

    /** How far (as a fraction of the local interval) the next detection may sit from the expected slot. */
    private const val STEP_TOLERANCE = 0.3

    private const val RECENT_GAPS = 3

    /** After a break, a detection nearer than this (in local intervals) is a twin or a half-beat, not the next beat. */
    private const val RESUME_MIN_GAP = 0.7

    /** After a break, a detection nearer than this is taken as the very next beat of a new tempo; farther ones skip beats. */
    private const val RESUME_ONE_BEAT_MAX_GAP = 1.7

    private const val ROBUST_PASSES = 4

    /** The drift test allows this many standard deviations of noise, and a region this many (see [noiseMs]). */
    private const val DRIFT_NOISE_FACTOR = 4.0
    private const val REGION_NOISE_FACTOR = 2.5
    private const val MIN_NOISE_SAMPLES = 8

    /** The median of the absolute values of a zero-mean Gaussian is 0.6745 of its standard deviation. */
    private const val MAD_TO_SIGMA_ABS = 1.4826

    /** The second difference of white noise of deviation s has deviation s * sqrt(6). */
    private val SECOND_DIFFERENCE_GAIN = Math.sqrt(6.0)

    /** The first beats of a region are taken as they come; the prediction needs a few to be of any use. */
    private const val SCAN_INITIAL_BEATS = 5

    /** A region with fewer of its beats within its own line is inconsistent noise. */
    private const val MIN_KEPT_FRACTION = 0.7

    private const val MERGE_OUTLIER_FRACTION = 0.05

    /** A tie-break for the boundary: milliseconds of mismatch per millisecond away from the middle of the change. */
    private const val BOUNDARY_PULL = 0.001
}
