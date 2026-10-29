package io.sonora.feature.dj.waveform

import io.sonora.core.djmodel.DjCue
import io.sonora.core.djmodel.DjCues

/**
 * The cues of one track as flat arrays sorted by time (hot cues first at the same time), built once per list of cues
 * so the draw phase of the waveforms finds the visible ones with a binary search and reads them without allocating.
 */
class CueMarkerIndex(cues: List<DjCue>) {

    private val times: DoubleArray
    private val colors: IntArray
    private val labels: CharArray
    private val loopEnds: DoubleArray

    /** The length of the longest saved loop (0 without any): how far back a view must look for a loop that reaches into it. */
    val longestLoopMs: Double

    init {
        val sorted = DjCues.sorted(cues)
        times = DoubleArray(sorted.size) { sorted[it].positionMs }
        colors = IntArray(sorted.size) { OPAQUE or sorted[it].color.rgb }
        labels = CharArray(sorted.size) { sorted[it].label ?: NO_LABEL }
        loopEnds = DoubleArray(sorted.size) { sorted[it].loopEndMs ?: Double.NaN }
        longestLoopMs = loopEnds.indices.maxOfOrNull { if (loopEnds[it].isNaN()) 0.0 else loopEnds[it] - times[it] } ?: 0.0
    }

    val count: Int get() = times.size

    fun timeAt(index: Int): Double = times[index]

    /** Opaque ARGB of the cue's colour. */
    fun colorAt(index: Int): Int = colors[index]

    /** The pad letter A..H of a hot cue, or [NO_LABEL] for a memory cue. */
    fun labelAt(index: Int): Char = labels[index]

    fun isHot(index: Int): Boolean = labels[index] != NO_LABEL

    /** Whether the cue is a saved loop. */
    fun hasLoop(index: Int): Boolean = !loopEnds[index].isNaN()

    /** The end of the saved loop; NaN for a plain cue. */
    fun loopEndAt(index: Int): Double = loopEnds[index]

    /** The index of the first cue at or after [ms] (`count` when none). */
    fun firstAtOrAfter(ms: Double): Int {
        var low = 0
        var high = times.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (times[mid] < ms) low = mid + 1 else high = mid
        }
        return low
    }

    companion object {
        const val NO_LABEL: Char = '\u0000'
        private const val OPAQUE: Int = 0xFF000000.toInt()

        val EMPTY: CueMarkerIndex = CueMarkerIndex(emptyList())
    }
}
