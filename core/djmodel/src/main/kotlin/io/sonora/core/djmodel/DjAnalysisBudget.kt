package io.sonora.core.djmodel

import io.sonora.core.domain.conversion.PerformanceMode
import io.sonora.core.domain.conversion.PerformancePolicy

/**
 * What the DJ analysis may use while it runs in [mode]: the one place where the three performance modes are turned into
 * numbers, read by the scheduler ([lookAhead]), the analysis thread ([threadPriority], [pauseWhileHot]) and the beat
 * model's session ([intraOpThreads]). The analysis result is identical in every mode; only speed and load change.
 *
 * @property mode the mode these numbers belong to: an effective one (see [DjAnalysisLimits.of]), not necessarily the
 *   chosen one.
 * @property intraOpThreads ONNX Runtime intra-op threads of the beat model's session (1..4, what `OrtCpuConfig` takes).
 * @property threadPriority Linux nice value for the analysis thread and the threads its session starts, as
 *   `android.os.Process.setThreadPriority` takes it (higher is gentler).
 * @property lookAhead queue entries after the playing track that are analysed ahead of time; 0 analyses only the track
 *   being played.
 * @property pauseWhileHot whether the analysis waits between chunks while the device is hot ([DeviceLoad.hot]).
 */
data class DjAnalysisBudget(
    val mode: PerformanceMode,
    val intraOpThreads: Int,
    val threadPriority: Int,
    val lookAhead: Int,
    val pauseWhileHot: Boolean,
) {
    companion object {
        /** The largest [lookAhead] of any mode: the scheduler's window never needs more. */
        const val MAX_LOOK_AHEAD: Int = 5

        /** The budget of an already effective [mode]; use [DjAnalysisLimits.of] to apply heat and battery saver. */
        fun of(mode: PerformanceMode): DjAnalysisBudget = when (mode) {
            PerformanceMode.LOW_RESOURCES ->
                DjAnalysisBudget(mode, intraOpThreads = 1, threadPriority = PerformancePolicy.PRIORITY_LOW, lookAhead = 0, pauseWhileHot = true)
            PerformanceMode.NORMAL ->
                DjAnalysisBudget(mode, intraOpThreads = 2, threadPriority = PerformancePolicy.PRIORITY_BACKGROUND, lookAhead = 3, pauseWhileHot = false)
            PerformanceMode.HIGH_PERFORMANCE ->
                DjAnalysisBudget(mode, intraOpThreads = 4, threadPriority = PerformancePolicy.PRIORITY_BACKGROUND, lookAhead = MAX_LOOK_AHEAD, pauseWhileHot = false)
        }
    }
}

/**
 * The [budget] in force right now for a chosen mode and the device [load], and whether the analysis has to wait.
 *
 * The step-down rules are the converter's ([PerformancePolicy.effectiveMode]): one level for moderate heat and one for
 * battery saver, low resources for severe heat.
 */
data class DjAnalysisLimits(val budget: DjAnalysisBudget, val load: DeviceLoad) {

    /** Whether analysis has to wait now: the budget pauses while hot and the device is hot. */
    val pauseNow: Boolean get() = budget.pauseWhileHot && load.hot

    companion object {
        fun of(chosen: PerformanceMode, load: DeviceLoad): DjAnalysisLimits =
            DjAnalysisLimits(DjAnalysisBudget.of(PerformancePolicy.effectiveMode(chosen, load.conditions())), load)
    }
}

/** The limits in force right now, for the threads that do the analysis. */
fun interface DjAnalysisBudgetSource {
    fun current(): DjAnalysisLimits
}
