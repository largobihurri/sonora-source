package io.sonora.core.djmodel

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Tap tempo: estimates a tempo from the moments the user taps it, as the tap button of a CDJ or of rekordbox does.
 *
 * The estimate is the mean of the recent intervals between taps, after the ones that do not belong are dropped:
 * - only the last [MAX_TAPS] taps count, so the estimate follows a hand that settles into the rhythm;
 * - a pause longer than [RESET_AFTER_MS] starts a new run (the next tap is the first of it);
 * - an interval more than [OUTLIER_PERCENT] percent away from the median interval (a missed beat, a bounce) is ignored;
 * - nothing is proposed before [MIN_TAPS] taps, nor while fewer than [MIN_INTERVALS] intervals agree, nor for a tempo
 *   outside what a [GridEdit] holds.
 *
 * The result is in hundredths of a BPM, the unit of [GridEdit.bpmHundredths]. Pure and single threaded (taps come from
 * the UI thread); the clock is injected so tests drive time: [nanoTime] must be monotonic (on Android,
 * `SystemClock.elapsedRealtimeNanos`), and only differences between its readings matter. Nanoseconds because a
 * millisecond of jitter on a 470 ms interval is a quarter of a BPM.
 */
class TapTempo(private val nanoTime: () -> Long) {

    private val taps = ArrayDeque<Long>(MAX_TAPS + 1)

    /** How many taps the current run holds (at most [MAX_TAPS]). */
    val tapCount: Int get() = taps.size

    /**
     * The tempo the taps so far suggest, in hundredths of a BPM, or `null` while there is not enough to say. It reads
     * the taps, never the clock: it stays after a pause until the next tap starts a new run.
     */
    var bpmHundredths: Int? = null
        private set

    /** Records a tap now and returns the new [bpmHundredths]. */
    fun tap(): Int? {
        val now = nanoTime()
        val last = taps.lastOrNull()
        if (last != null && (now < last || now - last > RESET_AFTER_MS * NANOS_PER_MS)) taps.clear()
        taps.addLast(now)
        while (taps.size > MAX_TAPS) taps.removeFirst()
        bpmHundredths = estimate()
        return bpmHundredths
    }

    /** Forgets every tap. */
    fun reset() {
        taps.clear()
        bpmHundredths = null
    }

    private fun estimate(): Int? {
        if (taps.size < MIN_TAPS) return null
        val intervals = taps.zipWithNext { a, b -> b - a }
        val median = median(intervals.sorted())
        if (median <= 0L) return null
        val agreeing = intervals.filter { abs(it - median) * PERCENT_BASE <= median * OUTLIER_PERCENT }
        if (agreeing.size < MIN_INTERVALS) return null
        val meanNs = agreeing.sum().toDouble() / agreeing.size
        val hundredths = (NANOS_PER_MINUTE_HUNDREDTHS / meanNs).roundToInt()
        return hundredths.takeIf { it in GridEdit.MIN_BPM_HUNDREDTHS..GridEdit.MAX_BPM_HUNDREDTHS }
    }

    private fun median(sorted: List<Long>): Long {
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
    }

    companion object {
        /** The taps the estimate looks at. */
        const val MAX_TAPS: Int = 8

        /** The taps needed before a tempo is proposed. */
        const val MIN_TAPS: Int = 4

        /** The intervals that must agree for a tempo to be proposed. */
        const val MIN_INTERVALS: Int = 2

        /** A pause longer than this starts a new run. */
        const val RESET_AFTER_MS: Long = 2_000

        /** An interval this far (percent) from the median is ignored; exactly this far still counts. */
        const val OUTLIER_PERCENT: Long = 30

        private const val PERCENT_BASE = 100L
        private const val NANOS_PER_MS = 1_000_000L

        /** Nanoseconds in a minute, times 100: dividing it by an interval in nanoseconds gives hundredths of a BPM. */
        private const val NANOS_PER_MINUTE_HUNDREDTHS = 6_000_000_000_000.0
    }
}
