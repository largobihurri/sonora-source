package io.sonora.core.djanalysis

import io.sonora.core.djengine.schedule.DjAnalysisQueue
import io.sonora.core.djmodel.DeviceLoad
import io.sonora.core.djmodel.DjAnalysisBudget
import io.sonora.core.djmodel.DjAnalysisLimits
import io.sonora.core.djmodel.SchedulingInput
import io.sonora.core.domain.conversion.PerformanceMode
import io.sonora.core.domain.model.PlaybackState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** The part of the player the scheduler looks at: the playing track's uri and the next distinct uris of the queue. */
data class PlayerWindow(val playingUri: String?, val upNextUris: List<String>)

/** Builds the scheduler's [SchedulingInput] from the DJ-mode flag, the player and the device load. */
object SchedulingInputs {

    /**
     * The playing uri and, in play order, the next [limit] distinct uris after it that are not the playing one (what
     * [DjAnalysisQueue] looks at with the same limit). Only the entries it needs are read, so a long queue costs
     * nothing on the player's frequent position updates.
     */
    fun window(state: PlaybackState, limit: Int = DjAnalysisBudget.of(PerformanceMode.DEFAULT).lookAhead): PlayerWindow {
        require(limit >= 0) { "limit must not be negative: $limit" }
        val playing = state.currentTrack?.uri?.takeIf { it.isNotEmpty() }
        val next = LinkedHashSet<String>()
        val queue = state.queue
        var index = state.currentIndex + 1
        while (next.size < limit && index in queue.indices) {
            val uri = queue[index].track.uri
            if (uri.isNotEmpty() && uri != playing) next += uri
            index++
        }
        return PlayerWindow(playing, next.toList())
    }

    /**
     * What the scheduler gets: while [djModeOn] emits `false`, one input with DJ mode off and nothing else (the player,
     * the device load and the performance are not even subscribed, so DJ mode costs nothing while it is off); while it
     * is on, the [playback] window and the [load], emitted again only when one of them changes (not on every position
     * update).
     *
     * The window's look-ahead is the one of the [performance] chosen, stepped down by the [load] ([DjAnalysisLimits]),
     * so a faster mode looks further ahead and low resources only at the playing track. [onLimits] is told the limits in
     * force before the input that carries them is emitted, for the threads that run the analysis.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun flow(
        djModeOn: Flow<Boolean>,
        playback: Flow<PlaybackState>,
        load: Flow<DeviceLoad>,
        performance: Flow<PerformanceMode> = flowOf(PerformanceMode.DEFAULT),
        onLimits: (DjAnalysisLimits) -> Unit = {},
    ): Flow<SchedulingInput> = djModeOn.distinctUntilChanged().flatMapLatest { on ->
        if (!on) {
            flowOf(SchedulingInput(djModeOn = false, playingUri = null, upNextUris = emptyList()))
        } else {
            // The window is read once with the widest look-ahead and narrowed below, so a position update is filtered
            // here and a mode change needs no new read of the player.
            val widest = playback.map { window(it, DjAnalysisBudget.MAX_LOOK_AHEAD) }.distinctUntilChanged()
            combine(widest, performance.distinctUntilChanged(), load.distinctUntilChanged()) { window, chosen, deviceLoad ->
                val limits = DjAnalysisLimits.of(chosen, deviceLoad)
                onLimits(limits)
                SchedulingInput(
                    djModeOn = true,
                    playingUri = window.playingUri,
                    upNextUris = window.upNextUris.take(limits.budget.lookAhead),
                    load = deviceLoad,
                )
            }
        }
    }.distinctUntilChanged()
}
