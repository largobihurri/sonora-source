package io.sonora.feature.dj.waveform

import io.sonora.core.djmodel.DjBeatGrid

/**
 * The beats of a [DjBeatGrid] over one track, built once into flat arrays so the scrolling waveform can find the beats
 * of the visible window without allocating: [visible] is a binary search and a copy.
 *
 * Times are in milliseconds from the track start; the bar position is 1..4 (1 = downbeat). The beats are exactly those
 * of [DjBeatGrid.beats] over `[0, durationMs)`, so the grid's own rules apply (the first segment's beats run back to
 * the track start; each segment keeps its own bar position).
 *
 * A phrase is [BARS_PER_PHRASE] bars, counted on across tempo segments (as rekordbox and CDJs count bars from the
 * start of the grid) from the first downbeat of the track, which is the bar of its phrase that the grid's first
 * segment says ([io.sonora.core.djmodel.TempoSegment.barInPhraseAtStart], 1 for an analysed grid, whose first downbeat
 * starts a phrase; a grid edit puts the phrase start where the user set it). See [DjBeatGrid.phraseStarts].
 */
class BeatIndex(grid: DjBeatGrid, durationMs: Double) {

    private val times: DoubleArray
    private val bars: ByteArray
    private val phraseStarts: BooleanArray

    init {
        val beats = grid.beats(durationMs)
        times = DoubleArray(beats.size) { beats[it].timeMs }
        bars = ByteArray(beats.size) { beats[it].beatInBar.toByte() }
        phraseStarts = grid.phraseStarts(beats)
    }

    /** Number of beats in the track. */
    val count: Int get() = times.size

    /** The time of beat [index] in ms. */
    fun timeAt(index: Int): Double = times[index]

    /** The position (1..4) of beat [index] in its bar. */
    fun barPositionAt(index: Int): Int = bars[index].toInt()

    /** Whether beat [index] is the first of its bar. */
    fun isDownbeat(index: Int): Boolean = bars[index].toInt() == 1

    /** Whether beat [index] is the first of a phrase of [BARS_PER_PHRASE] bars. */
    fun isPhraseStart(index: Int): Boolean = phraseStarts[index]

    /**
     * Copies the beats in `[startMs, endMs)` into [outTimes] and [outBars], and their phrase flags into [outPhrases]
     * when given (the same slot for the same beat), and returns how many were written. When the window holds more beats
     * than the arrays, only the first ones are written. Allocation free.
     */
    fun visible(
        startMs: Double,
        endMs: Double,
        outTimes: DoubleArray,
        outBars: ByteArray,
        outPhrases: BooleanArray? = null,
    ): Int {
        if (!(endMs > startMs)) return 0
        val capacity = minOf(outTimes.size, outBars.size, outPhrases?.size ?: Int.MAX_VALUE)
        var i = firstAtOrAfter(startMs)
        var written = 0
        while (i < times.size && written < capacity && times[i] < endMs) {
            outTimes[written] = times[i]
            outBars[written] = bars[i]
            if (outPhrases != null) outPhrases[written] = phraseStarts[i]
            written++
            i++
        }
        return written
    }

    /** The index of the first beat with time >= [ms] (`count` when none). */
    private fun firstAtOrAfter(ms: Double): Int {
        var low = 0
        var high = times.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (times[mid] < ms) low = mid + 1 else high = mid
        }
        return low
    }

    companion object {
        /** Bars in a phrase: the waveform marks the first downbeat of each. */
        const val BARS_PER_PHRASE: Int = DjBeatGrid.BARS_PER_PHRASE
    }
}
