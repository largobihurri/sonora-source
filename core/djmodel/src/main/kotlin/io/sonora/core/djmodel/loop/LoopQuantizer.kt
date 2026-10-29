package io.sonora.core.djmodel.loop

import io.sonora.core.djmodel.DjBeatGrid
import io.sonora.core.djmodel.TempoSegment
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Snaps loop points to the beats of a track and builds loops from them.
 *
 * [grid] is the effective grid of the track (`DjAnalysis.effectiveGrid`, edits included), constant or with tempo
 * changes; `null` when there is none, and then nothing snaps and only manual loops exist. [durationMs] is the length of
 * the track: no loop ends after it.
 *
 * Beat positions count beats from the first segment's start (negative before it, as the first segment's beats also
 * run backwards). Inside a segment a position maps linearly to time, so a fraction of a beat subdivides the beat
 * interval and a loop of N beats keeps counting beats across a tempo change.
 */
class LoopQuantizer(private val grid: DjBeatGrid?, private val durationMs: Double) {

    private val segments: List<TempoSegment> = grid?.segments.orEmpty()

    /** Whether the track has a beat grid: without one nothing snaps and there are no beat loops. */
    val hasGrid: Boolean get() = segments.isNotEmpty()

    /** Beat position of each segment's start: whole beats of the previous segment, `0` for the first. */
    private val startPositions = LongArray(segments.size).also { positions ->
        for (i in 1 until segments.size) {
            val previous = segments[i - 1]
            positions[i] = positions[i - 1] +
                ((segments[i].startMs - previous.startMs) / previous.beatLengthMs).roundToLong()
        }
    }

    /** Beats since the first segment's start (negative before it) at [timeMs], fractions included; needs a grid. */
    fun beatPositionAt(timeMs: Double): Double {
        check(segments.isNotEmpty()) { "No beat grid" }
        var index = 0
        for (i in 1 until segments.size) {
            if (timeMs >= segments[i].startMs) index = i else break
        }
        val segment = segments[index]
        return startPositions[index] + (timeMs - segment.startMs) / segment.beatLengthMs
    }

    /** Time of the beat position [position] (see [beatPositionAt]); needs a grid. */
    fun timeAtBeatPosition(position: Double): Double {
        check(segments.isNotEmpty()) { "No beat grid" }
        var index = 0
        for (i in 1 until segments.size) {
            if (position >= startPositions[i]) index = i else break
        }
        val segment = segments[index]
        return segment.startMs + (position - startPositions[index]) * segment.beatLengthMs
    }

    /** The beat nearest to [timeMs] inside the track (a tie goes to the later one); [timeMs] itself without a grid. */
    fun snap(timeMs: Double): Double {
        if (segments.isEmpty()) return timeMs
        val position = beatPositionAt(timeMs.coerceAtLeast(0.0))
        var earlier = timeAtBeatPosition(floor(position))
        var later = timeAtBeatPosition(ceil(position))
        // Beats before the start of the track (the first segment runs backwards) or after its end do not count.
        var earlierPosition = floor(position)
        var laterPosition = ceil(position)
        while (earlier < 0.0) {
            earlierPosition += 1
            earlier = timeAtBeatPosition(earlierPosition)
            if (laterPosition < earlierPosition) {
                laterPosition = earlierPosition
                later = earlier
            }
        }
        while (later > durationMs && later > earlier) {
            laterPosition -= 1
            later = timeAtBeatPosition(laterPosition)
        }
        return if (abs(timeMs - earlier) < abs(later - timeMs)) earlier else later
    }

    /**
     * A loop of [beats] beats (1/32..32; fractions subdivide the beat) starting at [startMs], which is snapped to the
     * nearest beat when [quantizeIn]. `null` without a grid, or when the loop would end after the track.
     */
    fun beatLoop(startMs: Double, beats: Double, quantizeIn: Boolean = true): Loop? {
        if (segments.isEmpty() || !(beats > 0.0)) return null
        val start = if (quantizeIn) snap(startMs) else startMs.coerceAtLeast(0.0)
        val end = timeAtBeatPosition(beatPositionAt(start) + beats)
        return build(start, end)
    }

    /**
     * The loop the user marked by hand from [inMs] to [outMs]; both snap to beats when [quantize] and a grid exists
     * (the same beat gives a one-beat loop). Out is held inside the track. `null` when the loop is shorter than
     * [Loop.MIN_LENGTH_MS] or empty.
     */
    fun manualLoop(inMs: Double, outMs: Double, quantize: Boolean = true): Loop? {
        if (!(outMs > inMs)) return null
        if (!quantize || segments.isEmpty()) return build(inMs, outMs.coerceAtMost(durationMs))
        val start = snap(inMs)
        var end = snap(outMs)
        if (end <= start) end = timeAtBeatPosition(beatPositionAt(start) + 1.0)
        return build(start, end.coerceAtMost(durationMs))
    }

    /** [loop] with half its length (in beats when there is a grid), or `null` when that is under the minimum. */
    fun halve(loop: Loop): Loop? {
        val end = if (segments.isEmpty()) {
            loop.inMs + loop.lengthMs / 2
        } else {
            val start = beatPositionAt(loop.inMs)
            timeAtBeatPosition(start + (beatPositionAt(loop.outMs) - start) / 2)
        }
        return build(loop.inMs, end)
    }

    /** [loop] with twice its length, or `null` when it would end after the track. */
    fun double(loop: Loop): Loop? {
        val end = if (segments.isEmpty()) {
            loop.inMs + loop.lengthMs * 2
        } else {
            val start = beatPositionAt(loop.inMs)
            timeAtBeatPosition(start + (beatPositionAt(loop.outMs) - start) * 2)
        }
        return build(loop.inMs, end)
    }

    /** The loop `[start, end)` when it is long enough and inside the track. */
    private fun build(start: Double, end: Double): Loop? {
        if (!start.isFinite() || !end.isFinite() || start < 0.0 || end > durationMs + EPSILON_MS) return null
        if (end - start < Loop.MIN_LENGTH_MS) return null
        return Loop(start, end.coerceAtMost(durationMs))
    }

    private companion object {
        /** Slack for a loop whose Out is the last beat of a track whose duration is a rounded figure. */
        const val EPSILON_MS = 1e-6
    }
}
