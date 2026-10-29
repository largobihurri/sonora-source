package io.sonora.feature.dub

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.sonora.core.domain.model.PlaybackState
import io.sonora.core.domain.port.PlaybackController
import io.sonora.core.dubmodel.DubDivision
import io.sonora.core.dubmodel.DubMeters
import io.sonora.core.dubmodel.DubPadKind
import io.sonora.core.dubmodel.DubSetup
import io.sonora.core.dubmodel.DubSetupRepository
import io.sonora.core.dubmodel.DubStage
import io.sonora.core.dubmodel.DubStageState
import io.sonora.core.dubmodel.PreampWay
import io.sonora.core.dubmodel.WaySetup
import io.sonora.feature.dub.controls.XyMapping
import io.sonora.feature.dub.controls.XyPosition
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The DUB screen's performance state: never stored, reset every time the screen opens ([DubViewModel.opened]). */
data class DubPerformance(
    val filter: XyPosition = XyMapping.Centre,
    val killed: Set<PreampWay> = emptySet(),
)

/**
 * The DUB screen (design §2, §4, §10): the stored effect setup ([setup], edited here: a drag previews it live and its
 * release stores it), the performance state ([performance]: the filter position and the preamp kills, reset on every
 * [opened]), the stage's state ([stage]: hosted or why not, the echo's clock, DUB STOP ringing) and the output
 * [meters]. Pad edges, the filter, the kills, TAP and STOP go straight to the [DubStage]; touch times arrive in
 * milliseconds of uptime and go on in nanoseconds.
 *
 * The meters are read about 20 times a second only while something plays on a hosted stage and the screen collects
 * them; paused, they read nothing and emit nothing, so the screen asks for no frames.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DubViewModel internal constructor(
    private val dub: DubStage,
    private val setups: DubSetupRepository,
    playback: StateFlow<PlaybackState>,
) : ViewModel() {

    @Inject
    constructor(dub: DubStage, setups: DubSetupRepository, playback: PlaybackController) : this(dub, setups, playback.state)

    val setup: StateFlow<DubSetup> = setups.setup.stateIn(viewModelScope, SharingStarted.Eagerly, DubSetup())

    val stage: StateFlow<DubStageState> = dub.state

    private val performanceState = MutableStateFlow(DubPerformance())
    val performance: StateFlow<DubPerformance> = performanceState.asStateFlow()

    val playing: StateFlow<Boolean> =
        playback.map { it.isPlaying }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, playback.value.isPlaying)

    val meters: Flow<DubMeters> = combine(playing, dub.state) { on, state -> on && state.hosted }
        .distinctUntilChanged()
        .flatMapLatest { live ->
            if (!live) {
                flowOf(DubMeters())
            } else {
                flow {
                    while (true) {
                        emit(dub.meters())
                        delay(METER_INTERVAL_MS)
                    }
                }
            }
        }
        .distinctUntilChanged()

    /** The screen opened: every pad, kill and the filter start released, the stored setup stays. */
    fun opened() {
        performanceState.value = DubPerformance()
        dub.releaseAll()
    }

    // --- Performance ------------------------------------------------------------------------------------------------

    fun onPad(pad: DubPadKind, engaged: Boolean, uptimeMillis: Long) = dub.setPad(pad, engaged, uptimeMillis * NANOS_PER_MS)

    /** The XY pad at [position] (0..1 both ways, the flat detent at X = 0.5): the stage takes X in -1..1. */
    fun onFilter(position: XyPosition) {
        performanceState.update { it.copy(filter = position) }
        dub.setFilter((position.x - CENTRE) * 2f, position.y)
    }

    fun onKill(way: PreampWay, killed: Boolean) {
        performanceState.update { it.copy(killed = if (killed) it.killed + way else it.killed - way) }
        dub.setKilled(way, killed)
    }

    fun onTap(uptimeMillis: Long) = dub.tap(uptimeMillis * NANOS_PER_MS)

    fun onClearTap() = dub.clearTap()

    fun onStop() = dub.stop()

    fun onPlay() = dub.play()

    // --- The stored setup -------------------------------------------------------------------------------------------

    fun onLock(locked: Boolean) = store { it.copy(lock = locked) }

    fun onDivision(division: DubDivision) = store { it.copy(division = division) }

    fun onFeedback(value: Float, final: Boolean) = edit(final) { it.copy(feedback = value) }

    fun onReturn(value: Float, final: Boolean) = edit(final) { it.copy(returnLevel = value) }

    fun onHighPass(way: PreampWay, hz: Float, final: Boolean) = editWay(way, final) { it.withHighPass(hz) }

    fun onLowPass(way: PreampWay, hz: Float, final: Boolean) = editWay(way, final) { it.withLowPass(hz) }

    fun onLevel(way: PreampWay, db: Double, final: Boolean) = editWay(way, final) { it.withLevelDb(db.toFloat()) }

    /** Way [way] back to its default corners at 0 dB. */
    fun onResetWay(way: PreampWay) = editWay(way, final = true) { WaySetup.defaultFor(way) }

    private fun editWay(way: PreampWay, final: Boolean, change: (WaySetup) -> WaySetup) =
        edit(final) { it.copy(preamp = it.preamp.with(way, change(it.preamp[way]))) }

    private fun edit(final: Boolean, change: (DubSetup) -> DubSetup) {
        if (final) store(change) else setups.preview(change(setup.value).clamped())
    }

    /** Applies [change] to the stored setup; a release carries its own final value, so a preview needs no merging. */
    private fun store(change: (DubSetup) -> DubSetup) {
        viewModelScope.launch { setups.update(change) }
    }

    private companion object {
        const val METER_INTERVAL_MS = 50L
        const val NANOS_PER_MS = 1_000_000L
        const val CENTRE = 0.5f
    }
}
