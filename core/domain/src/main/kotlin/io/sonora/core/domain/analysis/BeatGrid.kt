package io.sonora.core.domain.analysis

import kotlin.math.ceil
import kotlin.math.floor

/**
 * A constant-tempo beat grid in 4/4, as the analyser produces it and as Mixxx and the Pioneer players treat a track
 * with a steady tempo: [beatCount] beats [beatLengthMs] apart, starting at [firstBeatMs] (the earliest beat at or after
 * the start of the track), with the first beat at position [firstBeatInBar] (1..4, 1 = downbeat) of its bar.
 *
 * Four numbers instead of a list keep the grid compact to cache; [beats] expands it to the per-beat list that the
 * ANLZ beat grid (`PQTZ`) needs.
 */
data class BeatGrid(
    val bpm: Double,
    val firstBeatMs: Double,
    val firstBeatInBar: Int,
    val beatCount: Int,
) {
    init {
        require(bpm.isFinite() && bpm > 0) { "bpm must be positive: $bpm" }
        require(firstBeatMs.isFinite() && firstBeatMs >= 0) { "firstBeatMs must be zero or more: $firstBeatMs" }
        require(firstBeatInBar in 1..BEATS_PER_BAR) { "firstBeatInBar must be 1..$BEATS_PER_BAR: $firstBeatInBar" }
        require(beatCount >= 0) { "beatCount must not be negative: $beatCount" }
    }

    val beatLengthMs: Double get() = MS_PER_MINUTE / bpm

    /** Position in its bar (1..4) of the beat [index] beats after the first one; any index, also outside the grid. */
    fun beatInBarAt(index: Int): Int = Math.floorMod(firstBeatInBar - 1 + index, BEATS_PER_BAR) + 1

    /** @throws IndexOutOfBoundsException unless `index` is in `0 until beatCount`. */
    fun beatAt(index: Int): Beat {
        if (index !in 0 until beatCount) throw IndexOutOfBoundsException("Beat $index of $beatCount")
        return Beat(firstBeatMs + index * beatLengthMs, beatInBarAt(index))
    }

    /** Every beat of the grid, in order. */
    fun beats(): List<Beat> = List(beatCount, ::beatAt)

    companion object {
        const val BEATS_PER_BAR: Int = 4
        private const val MS_PER_MINUTE = 60_000.0

        /**
         * The grid at [bpm] through an anchor beat at [anchorMs] (any time, also before the start or after the first
         * beat) that sits at position [anchorBeatInBar] of its bar, covering every beat in `[0, durationMs)`.
         */
        fun spanning(bpm: Double, anchorMs: Double, anchorBeatInBar: Int, durationMs: Double): BeatGrid {
            require(bpm.isFinite() && bpm > 0) { "bpm must be positive: $bpm" }
            require(anchorMs.isFinite()) { "anchorMs must be finite: $anchorMs" }
            require(anchorBeatInBar in 1..BEATS_PER_BAR) { "anchorBeatInBar must be 1..$BEATS_PER_BAR: $anchorBeatInBar" }
            require(durationMs.isFinite() && durationMs >= 0) { "durationMs must be zero or more: $durationMs" }
            val beatLength = MS_PER_MINUTE / bpm
            val beatsBack = floor(anchorMs / beatLength).toLong()
            val firstMs = (anchorMs - beatsBack * beatLength).coerceAtLeast(0.0)
            val firstInBar = Math.floorMod(anchorBeatInBar - 1 - beatsBack, BEATS_PER_BAR.toLong()).toInt() + 1
            val count = if (firstMs >= durationMs) 0 else ceil((durationMs - firstMs) / beatLength).toInt()
            return BeatGrid(bpm, firstMs, firstInBar, count)
        }
    }
}

/** One beat of a [BeatGrid]: its time from the start of the track and its position in the bar (1..4). */
data class Beat(val timeMs: Double, val beatInBar: Int)
