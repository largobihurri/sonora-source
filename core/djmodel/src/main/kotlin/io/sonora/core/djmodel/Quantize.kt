package io.sonora.core.djmodel

import kotlin.math.abs
import kotlin.math.floor

/** Snapping a time to the beat grid, for the cues and jumps of the DJ screen while quantize is on. */
object Quantize {

    /**
     * [positionMs] moved to the nearest point of [grid] that is a multiple of [resolutionBeats] beats from the start of
     * its bar (1 = every beat, 0.5 = half beats, 4 = bar starts); halfway goes to the later point. Without a grid, for a
     * position that is not a number or a resolution that is not positive, [positionMs] comes back unchanged. The result
     * is never negative, and never past [durationMs] when that is positive (0 means unknown).
     *
     * Each tempo segment of the grid snaps on its own beats, and the first beat of the next segment is a candidate for
     * the end of the previous one, so a tempo change is never straddled.
     */
    fun snap(positionMs: Double, grid: DjBeatGrid?, resolutionBeats: Double = 1.0, durationMs: Double = 0.0): Double {
        if (grid == null || !positionMs.isFinite() || !(resolutionBeats > 0.0)) return positionMs
        val segments = grid.segments
        var index = 0
        while (index + 1 < segments.size && positionMs >= segments[index + 1].startMs) index++
        val segment = segments[index]
        val next = segments.getOrNull(index + 1)
        val beat = segment.beatLengthMs
        val step = beat * resolutionBeats
        // The start of the bar the segment's first beat is in, so whole-bar resolutions land on downbeats.
        val origin = segment.startMs - (segment.beatInBarAtStart - 1) * beat
        val lowerBound = if (index == 0) 0.0 else segment.startMs
        val upperBound = if (durationMs > 0.0) durationMs else Double.POSITIVE_INFINITY
        val below = floor((positionMs - origin) / step)
        var best = Double.NaN
        fun consider(time: Double) {
            if (time < lowerBound || time > upperBound) return
            if (next != null && time > next.startMs) return
            if (best.isNaN()) {
                best = time
                return
            }
            val distance = abs(time - positionMs)
            val bestDistance = abs(best - positionMs)
            if (distance < bestDistance || (distance == bestDistance && time > best)) best = time
        }
        consider(origin + below * step)
        consider(origin + (below + 1) * step)
        if (index > 0) consider(segment.startMs)
        if (next != null) consider(next.startMs)
        return if (best.isNaN()) positionMs else best
    }
}
