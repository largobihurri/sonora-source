package io.sonora.tools.calibration.runner

import io.sonora.core.djmodel.DjBeatGrid
import io.sonora.core.rekordbox.anlz.AnlzBeat
import io.sonora.tools.calibration.BarBeat

/**
 * The rekordbox beat grid of one track as the calibration compares it: every beat of the `PQTZ` tag of the `.DAT`
 * (D1.15 found that `PQT2`'s per-beat words are not beat numbers, so `PQTZ` is the per-beat truth), with its position
 * in the bar, the tempo held by the most beats ([dominantBpm]) and whether the tempo changes ([isVariable]).
 */
class ReferenceGrid(val beats: List<BarBeat>, val dominantBpm: Double, val isVariable: Boolean) {

    /** The beat times in ms, in order. */
    fun beatTimesMs(): DoubleArray = DoubleArray(beats.size) { beats[it].timeMs }

    companion object {

        /** The grid of the `PQTZ` [beats] (time in ms, tempo in hundredths of a BPM per beat). */
        fun fromAnlz(beats: List<AnlzBeat>): ReferenceGrid {
            val bars = beats.map { BarBeat(it.timeMs.toDouble(), it.beatInBar) }
            if (beats.isEmpty()) return ReferenceGrid(bars, Double.NaN, isVariable = false)
            val counts = beats.groupingBy { it.tempo }.eachCount()
            // Most beats wins; a tie goes to the slower tempo so the result does not depend on the map order.
            val dominant = counts.entries.sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key }).first().key
            return ReferenceGrid(bars, dominant / HUNDREDTHS, isVariable = counts.size > 1)
        }

        /** The tempo of the segment of [grid] that holds the most beats in `[0, durationMs)`. */
        fun dominantBpm(grid: DjBeatGrid, durationMs: Double): Double {
            val counts = LinkedHashMap<Double, Int>()
            for (beat in grid.beats(durationMs)) counts[beat.bpm] = (counts[beat.bpm] ?: 0) + 1
            if (counts.isEmpty()) return grid.segments.first().bpm
            return counts.entries.maxWith(compareBy<Map.Entry<Double, Int>> { it.value }.thenByDescending { it.key }).key
        }

        private const val HUNDREDTHS = 100.0
    }
}
