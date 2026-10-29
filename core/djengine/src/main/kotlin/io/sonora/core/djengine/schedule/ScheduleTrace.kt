package io.sonora.core.djengine.schedule

import io.sonora.core.djmodel.AudioId
import io.sonora.core.djmodel.SchedulingInput

/** What [DjAnalysisQueue] decided about one track. */
enum class ScheduleDecision {
    /** The track entered the look-ahead window and waits for the worker. */
    QUEUED,

    /** The worker began with the track and is identifying it (hash or memo). */
    IDENTIFYING,

    /** The track turned out to be analysed already: no analysis runs. */
    SKIPPED_ANALYSED,

    /** The track could not be identified; it is reported failed. */
    IDENTIFY_FAILED,

    /** The analysis of the track began. */
    STARTED,

    /** The running analysis of the track was cancelled. */
    CANCELLED,

    /** The analysis of the track failed. */
    FAILED,
}

/**
 * What [DjAnalysisQueue] tells its observer while DJ mode is on, so the device logs can measure how long a queue change
 * takes to become work: each new [InputChanged] and every [Decision] since.
 */
sealed interface ScheduleTrace {

    /** A new input reached the queue; [Decision.elapsedMs] counts from the latest of these. */
    data class InputChanged(val input: SchedulingInput) : ScheduleTrace

    /**
     * [decision] about [uri], [elapsedMs] after the latest input. [audioId] once known; [detail] is the reason of a
     * cancellation or failure, or [HELD_BY_LOAD] for a queued background track the device load holds back.
     */
    data class Decision(
        val uri: String,
        val decision: ScheduleDecision,
        val elapsedMs: Long,
        val audioId: AudioId? = null,
        val detail: String? = null,
    ) : ScheduleTrace

    companion object {
        const val HELD_BY_LOAD: String = "held_by_load"
        const val PREEMPTED: String = "preempted"
        const val LEFT_WINDOW: String = "left_window"
        const val LOAD: String = "load"

        /** A queued request: the user asked for a new attempt at a failed track. */
        const val RETRY: String = "retry"

        /** The user asked for the analysis to run again and replace the stored one. */
        const val REANALYSE: String = "reanalyse"
    }
}
