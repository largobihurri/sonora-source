package io.sonora.core.djanalysis

import dagger.Lazy
import io.sonora.core.djanalysis.di.DjAnalysisScope
import io.sonora.core.djengine.schedule.DjAnalysisQueue
import io.sonora.core.djmodel.AnalysisStatus
import io.sonora.core.djmodel.DjAnalysisBudget
import io.sonora.core.djmodel.DjAnalysisControl
import io.sonora.core.djmodel.DjAnalysisStatusSource
import io.sonora.core.djmodel.DjModeSettings
import io.sonora.core.domain.port.PlaybackController
import io.sonora.core.dubmodel.DubModeSettings
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext

/**
 * Runs the DJ analysis for the life of the process: a [DjAnalysisQueue] on the app-scoped [scope] (no WorkManager: the
 * work is tied to what is playing now), fed by [SchedulingInputs.flow] from the [settings] flag, the player and the
 * device load, with the [runner]'s callbacks.
 *
 * How hard the analysis works is the user's chosen performance ([DjModeSettings.performance]) stepped down by the device
 * load ([io.sonora.core.djmodel.DjAnalysisLimits]): the scheduler's look-ahead follows it here, and the limits in force
 * are published to [limits] for the analysing thread and the beat model's session.
 *
 * Nothing runs while both DJ mode and DUB mode ([dubEnabled], whose echo follows the beat grid) are off (the default):
 * the player and the device load are not subscribed, and the runner (and with it the database, the decoder and the
 * beat model) is not even created until the first track is identified.
 *
 * While DJ mode is on, every scheduler input and decision is logged to [log] ([ScheduleTraceLines]), with the time since
 * the input that caused it, so a device run shows where a queue change waits before its analysis starts.
 */
@Singleton
class DjAnalysisCoordinator internal constructor(
    private val settings: DjModeSettings,
    private val playback: Lazy<PlaybackController>,
    private val load: DeviceLoadSource,
    private val runner: Lazy<DjAnalysisRunner>,
    private val scope: CoroutineScope,
    private val log: DjAnalysisLog,
    private val limits: DjAnalysisLimitsHolder = DjAnalysisLimitsHolder(),
    private val dubEnabled: Flow<Boolean> = flowOf(false),
    private val monotonicNanos: () -> Long = System::nanoTime,
) : DjAnalysisStatusSource, DjAnalysisControl {

    @Inject
    constructor(
        settings: DjModeSettings,
        playback: Lazy<PlaybackController>,
        load: DeviceLoadSource,
        runner: Lazy<DjAnalysisRunner>,
        @DjAnalysisScope scope: CoroutineScope,
        limits: DjAnalysisLimitsHolder,
        dub: Lazy<DubModeSettings>,
    ) : this(settings, playback, load, runner, scope, AndroidDjAnalysisLog, limits, flow { emitAll(dub.get().enabled) })

    private val queue: DjAnalysisQueue by lazy {
        DjAnalysisQueue(
            input = SchedulingInputs.flow(analysisWanted(), playerState(), load.load(), settings.performance, limits::publish),
            identify = { uri -> runner.get().identify(uri) },
            isAnalysed = { id -> runner.get().isAnalysed(id) },
            analyze = { uri, id, onProgress -> runner.get().analyze(uri, id, onProgress) },
            reanalyze = { uri, id, onProgress -> runner.get().reanalyze(uri, id, onProgress) },
            scope = scope,
            // The inputs already carry the look-ahead of the performance in force; this only has to admit the widest.
            upNextLimit = DjAnalysisBudget.MAX_LOOK_AHEAD,
            trace = { log.info(ScheduleTraceLines.line(it)) },
            monotonicNanos = monotonicNanos,
        )
    }

    /** The analysis state per track uri, for the DJ screens (D2); starts the queue if [start] was not called. */
    override val status: StateFlow<Map<String, AnalysisStatus>> get() = queue.status

    /** Gives a failed track one new attempt (D2.11); ignored while DJ mode is off. */
    override fun retry(uri: String) {
        queue.retry(uri)
    }

    /** Analyses a track of the window again and replaces its stored analysis (D2.11); ignored while DJ mode is off. */
    override fun reanalyse(uri: String) {
        queue.reanalyse(uri)
    }

    /** Whether a layout that uses the analysis is on: DJ mode, or DUB mode (its echo follows the beat grid). */
    private fun analysisWanted(): Flow<Boolean> = combine(settings.enabled, dubEnabled) { dj, dub -> dj || dub }

    /** The player's state; the controller is created (if nothing created it yet) on the main thread, as Media3 wants. */
    private fun playerState() = flow { emitAll(withContext(Dispatchers.Main) { playback.get() }.state) }

    /** Starts following the DJ-mode flag; call once when the process starts. Idempotent. */
    fun start() {
        queue
    }
}
