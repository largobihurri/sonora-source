package io.sonora.core.djanalysis

import io.sonora.core.djmodel.DeviceLoad
import io.sonora.core.djmodel.DjAnalysisBudgetSource
import io.sonora.core.djmodel.DjAnalysisLimits
import io.sonora.core.djmodel.DjAnalysisPauseSource
import io.sonora.core.domain.conversion.PerformanceMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The limits in force for the analysis, written by [DjAnalysisCoordinator] as the chosen performance and the device load
 * change and read by the thread that analyses ([AnalysisThrottle]) and by the beat model's session factory. Before the
 * coordinator has published anything they are the default mode on a cool device.
 */
class DjAnalysisLimitsHolder : DjAnalysisBudgetSource, DjAnalysisPauseSource {

    @Volatile
    private var limits: DjAnalysisLimits = DjAnalysisLimits.of(PerformanceMode.DEFAULT, DeviceLoad())

    private val paused = MutableStateFlow(limits.pauseNow)

    override val pausedByHeat: StateFlow<Boolean> = paused

    override fun current(): DjAnalysisLimits = limits

    fun publish(limits: DjAnalysisLimits) {
        this.limits = limits
        paused.value = limits.pauseNow
    }
}

/**
 * What the analysing thread does to stay within the [limits]: [beginTrack] gives the thread the budget's priority (the
 * beat model's session, created next on that thread, and the threads it starts inherit it) and [checkpoint], called
 * between chunks, waits while the budget pauses on heat and the device is hot. Both read the limits at that moment, so
 * a mode chosen in Settings applies to the next track and to the next chunk.
 *
 * @param setPriority applies a nice value to the calling thread (`Process.setThreadPriority` on Android).
 * @param sleep blocks the calling thread for the given milliseconds; only the dedicated analysis thread waits here.
 */
class AnalysisThrottle(
    private val limits: DjAnalysisBudgetSource,
    private val setPriority: (Int) -> Unit,
    private val sleep: (Long) -> Unit = Thread::sleep,
) {

    /** Call on the analysing thread when a track's analysis starts. */
    fun beginTrack() {
        setPriority(limits.current().budget.threadPriority)
    }

    /** Runs [checkCancelled], then, while the limits say wait, sleeps [POLL_MS] and runs it again (so a cancel ends the wait). */
    fun checkpoint(checkCancelled: () -> Unit) {
        checkCancelled()
        while (limits.current().pauseNow) {
            sleep(POLL_MS)
            checkCancelled()
        }
    }

    companion object {
        /** How often a waiting analysis looks at the device again. */
        const val POLL_MS: Long = 500

        /** No limits: the default priority is left alone and the analysis never waits (tests, tools). */
        val UNLIMITED: AnalysisThrottle = AnalysisThrottle(
            limits = { DjAnalysisLimits.of(PerformanceMode.DEFAULT, DeviceLoad()) },
            setPriority = {},
        )
    }
}
