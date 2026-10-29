package io.sonora.core.djengine.beats

import io.sonora.core.djmodel.DjBeatGrid
import io.sonora.core.djmodel.OnsetEnvelope
import io.sonora.core.djmodel.TempoSegment
import io.sonora.core.domain.analysis.BeatGrid
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Puts a beat grid on the transients: moves each tempo segment by the constant time shift that makes its beats fall
 * on the peaks of a low-band [OnsetEnvelope] (see [OnsetEnvelopeSink]). A beat model's beats sit ten to twenty
 * milliseconds after the kick attack; a DJ grid needs the beat on the attack.
 *
 * ## Per segment
 * For a segment with beat times `t_k` (from its start to the next segment's start, or the end of the envelope; the
 * first segment also keeps the beats before its start, down to time 0), the score of a shift `s` is
 * `J(s) = sum_k env(t_k + s)`, the envelope read with cubic (Catmull-Rom) interpolation between frames, a
 * matched filter for a train of onsets. `J` is scanned for `s` in `[-W, W]` every [SCAN_STEP_MS] (0.25 ms) and the
 * best scan point is refined by a parabola through it and its two neighbours, so the result is a fraction of a
 * millisecond even though the envelope has a frame every 2 ms. The window is `W = min(maxShiftMs, beatLength / 2)`:
 * a segment never moves by more than half a beat, so it can never lock onto the neighbouring beat's onset.
 *
 * ## When it does nothing
 * - **No clear peak.** The mean envelope at the grid beats for the best shift, `J(s*) / n`, must be at least
 *   `minContrast` times the mean level of the envelope over the segment (what a grid in random phase would score).
 *   A flat, noisy or silent envelope scores about 1 (or 0 for silence, which has no level at all), a kick on every beat
 *   scores tens; the default [DEFAULT_MIN_CONTRAST] of 3 leaves a wide margin on both sides of synthetic and noise
 *   material and is a calibration knob for real tracks.
 * - **Too few beats.** A segment with fewer than [MIN_BEATS] beats has too little to fit. It, and any segment that
 *   failed the contrast test, **inherits the shift of the previous segment** (of the next one for leading ones), so
 *   a per-beat grid (many one-beat segments) moves as a whole instead of jittering. If no segment is refined, the
 *   same [DjBeatGrid] instance is returned.
 *
 * ## Keeping the grid valid
 * A segment after the first must start on a beat of the one before ([DjBeatGrid]). The start of segment `i` moves by
 * its shift `s_i`; the segment before it keeps its beat count up to that start, so its tempo becomes
 * `60000 * beats / (new boundary - new start)`: when two neighbouring shifts differ by `d` over `n` beats the tempo of
 * the earlier segment changes by `d / (n * beatLength)` (a few thousandths for a few milliseconds over tens of
 * beats), and its beats drift linearly from one shift to the other. If that would change a tempo by more than
 * [MAX_TEMPO_CHANGE] (or reverse a segment), the later segment takes the earlier one's shift instead. The last
 * segment keeps its tempo. If the first segment's shift would put its first beat before 0 ms, the start moves
 * forward by whole beats and `beatInBarAtStart` advances with it.
 *
 * Only the phase is refined, never the tempo of a constant grid; tempo drift inside a segment is for the
 * variable-tempo segmentation.
 */
object PhaseRefiner {

    /** The default search window: a beat model is off by 10..25 ms, so 40 ms leaves margin. */
    const val DEFAULT_MAX_SHIFT_MS: Double = 40.0

    /** The default ratio of the mean envelope at the beats to the mean envelope level below which the grid is kept. */
    const val DEFAULT_MIN_CONTRAST: Double = 3.0

    /** Segments with fewer beats than this are not fitted on their own. */
    const val MIN_BEATS: Int = 3

    /** Step of the shift scan before the parabolic refinement. */
    const val SCAN_STEP_MS: Double = 0.25

    /** The largest relative tempo change that keeping a boundary on a beat may cause. */
    const val MAX_TEMPO_CHANGE: Double = 0.05

    private const val EPSILON = 1e-9
    private const val MS_PER_MINUTE = 60_000.0

    /**
     * Returns [grid] with each segment shifted onto the onsets of [envelope], by at most [maxShiftMs] and half a
     * beat; [grid] itself when nothing was refined.
     *
     * @param minContrast see "When it does nothing"; must be positive.
     */
    fun refine(
        grid: DjBeatGrid,
        envelope: OnsetEnvelope,
        maxShiftMs: Double = DEFAULT_MAX_SHIFT_MS,
        minContrast: Double = DEFAULT_MIN_CONTRAST,
    ): DjBeatGrid {
        require(maxShiftMs.isFinite() && maxShiftMs > 0) { "maxShiftMs must be positive: $maxShiftMs" }
        require(minContrast.isFinite() && minContrast > 0) { "minContrast must be positive: $minContrast" }
        val values = envelope.values()
        if (values.size < MIN_ENVELOPE_FRAMES) return grid
        val framesPerSecond = envelope.framesPerSecond
        val durationMs = values.size * 1000.0 / framesPerSecond

        val segments = grid.segments
        val estimates = DoubleArray(segments.size) { i ->
            estimateShift(segments, i, values, framesPerSecond, durationMs, maxShiftMs, minContrast)
        }
        if (estimates.all { it.isNaN() }) return grid
        val shifts = inherit(estimates)
        return rebuild(segments, shifts)
    }

    /** The best shift in ms of segment [index], or `NaN` when it has too few beats or no clear peak. */
    private fun estimateShift(
        segments: List<TempoSegment>,
        index: Int,
        env: FloatArray,
        framesPerSecond: Double,
        durationMs: Double,
        maxShiftMs: Double,
        minContrast: Double,
    ): Double {
        val segment = segments[index]
        val length = segment.beatLengthMs
        val endMs = segments.getOrNull(index + 1)?.startMs ?: durationMs
        val firstIndex = if (index == 0) ceil(-segment.startMs / length - EPSILON).toLong().coerceAtMost(0) else 0L
        val times = ArrayList<Double>()
        var k = firstIndex
        while (true) {
            val time = segment.startMs + k * length
            if (time >= endMs - EPSILON || time >= durationMs) break
            times += time * framesPerSecond / 1000.0 // in frames
            k++
        }
        if (times.size < MIN_BEATS) return Double.NaN

        val window = min(maxShiftMs, length / 2)
        val frameMs = 1000.0 / framesPerSecond
        val steps = floor(window / SCAN_STEP_MS).toInt()
        val scores = DoubleArray(2 * steps + 1)
        var bestIndex = steps
        for (j in scores.indices) {
            val shiftFrames = (j - steps) * SCAN_STEP_MS / frameMs
            var sum = 0.0
            for (t in times) sum += interpolate(env, t + shiftFrames)
            scores[j] = sum
            // Strictly better, or equal and closer to no shift.
            if (sum > scores[bestIndex] || (sum == scores[bestIndex] && abs(j - steps) < abs(bestIndex - steps))) bestIndex = j
        }
        val best = scores[bestIndex]
        if (best <= 0) return Double.NaN

        // The mean level of the envelope over the stretch the beats cover is what a grid in random phase would score.
        val fromFrame = (times.first() - length / 2 / frameMs).coerceAtLeast(0.0).toInt()
        val toFrame = (times.last() + length / 2 / frameMs).coerceAtMost(env.size - 1.0).toInt()
        var level = 0.0
        for (f in fromFrame..toFrame) level += env[f]
        level /= (toFrame - fromFrame + 1)
        val contrast = if (level > 0) best / times.size / level else Double.POSITIVE_INFINITY
        if (contrast < minContrast) return Double.NaN

        var shift = (bestIndex - steps) * SCAN_STEP_MS
        if (bestIndex in 1 until scores.size - 1) {
            val below = scores[bestIndex - 1]
            val above = scores[bestIndex + 1]
            val curvature = below - 2 * best + above
            if (curvature < 0) shift += (0.5 * (below - above) / curvature).coerceIn(-1.0, 1.0) * SCAN_STEP_MS
        }
        return shift.coerceIn(-window, window)
    }

    /** Catmull-Rom interpolation of [env] at the fractional frame [position]; 0 outside the envelope. */
    private fun interpolate(env: FloatArray, position: Double): Double {
        if (position < 0 || position > env.size - 1) return 0.0
        val i = floor(position).toInt()
        val f = position - i
        val y1 = env[i].toDouble()
        val y0 = if (i > 0) env[i - 1].toDouble() else y1
        val y2 = if (i + 1 < env.size) env[i + 1].toDouble() else y1
        val y3 = if (i + 2 < env.size) env[i + 2].toDouble() else y2
        return y1 + 0.5 * f * (y2 - y0 + f * (2 * y0 - 5 * y1 + 4 * y2 - y3 + f * (3 * (y1 - y2) + y3 - y0)))
    }

    /** Every `NaN` takes the shift of the previous segment, the leading ones that of the first estimate, else 0. */
    private fun inherit(estimates: DoubleArray): DoubleArray {
        val out = estimates.copyOf()
        val firstKnown = out.indexOfFirst { !it.isNaN() }
        for (i in out.indices) {
            if (out[i].isNaN()) out[i] = if (i == 0 || i < firstKnown) out[firstKnown] else out[i - 1]
        }
        return out
    }

    private fun rebuild(segments: List<TempoSegment>, requested: DoubleArray): DjBeatGrid {
        val count = segments.size
        val shifts = requested.copyOf()
        val starts = DoubleArray(count)
        var firstBeatInBar = segments[0].beatInBarAtStart
        // Whole beats between a segment's start and the next one's, in the input grid.
        val beatsBetween = IntArray(count) { i ->
            if (i == count - 1) 0 else ((segments[i + 1].startMs - segments[i].startMs) / segments[i].beatLengthMs).roundToInt()
        }

        starts[0] = segments[0].startMs + shifts[0]
        if (starts[0] < 0) {
            val rolled = ceil(-starts[0] / segments[0].beatLengthMs - EPSILON).toInt()
            if (count == 1 || rolled < beatsBetween[0]) {
                starts[0] += rolled * segments[0].beatLengthMs
                firstBeatInBar = Math.floorMod(firstBeatInBar - 1 + rolled, BeatGrid.BEATS_PER_BAR) + 1
                beatsBetween[0] -= rolled
            } else {
                shifts[0] = -segments[0].startMs
                starts[0] = 0.0
            }
        }

        val bpms = DoubleArray(count) { segments[it].bpm }
        for (i in 0 until count - 1) {
            var next = segments[i + 1].startMs + shifts[i + 1]
            var span = next - starts[i]
            var bpm = MS_PER_MINUTE * beatsBetween[i] / span
            if (span <= 0 || abs(bpm / segments[i].bpm - 1) > MAX_TEMPO_CHANGE) {
                // The two shifts are too far apart for one segment to absorb: move the later segment with this one.
                // (Rolling the first start forward by whole beats leaves its beats where they are: same shift.)
                shifts[i + 1] = shifts[i]
                next = segments[i + 1].startMs + shifts[i + 1]
                span = next - starts[i]
                bpm = MS_PER_MINUTE * beatsBetween[i] / span
            }
            starts[i + 1] = next
            bpms[i] = bpm
        }
        return DjBeatGrid(
            List(count) { i ->
                TempoSegment(starts[i], bpms[i], if (i == 0) firstBeatInBar else segments[i].beatInBarAtStart)
            },
        )
    }
}

private const val MIN_ENVELOPE_FRAMES = 4
