package io.sonora.core.playback.dub

import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import io.sonora.core.djmodel.DjTrackAnalyses
import io.sonora.core.djmodel.TapTempo
import io.sonora.core.domain.playback.DjLoopController
import io.sonora.core.dubmodel.DubBypass
import io.sonora.core.dubmodel.DubClockSource
import io.sonora.core.dubmodel.DubMeters
import io.sonora.core.dubmodel.DubModeSettings
import io.sonora.core.dubmodel.DubPadKind
import io.sonora.core.dubmodel.DubSetupRepository
import io.sonora.core.dubmodel.DubStage
import io.sonora.core.dubmodel.DubStageState
import io.sonora.core.dubmodel.PreampWay
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The DUB stage of the session's player (the local ExoPlayer), driven from the DUB screen through [DubStage]. It mirrors
 * the DJ loop controller: the settings and the screen's controls become plain values and pad edges on [hub], which
 * the audio thread reads ([DubAudioSink]); this class keeps what the audio thread cannot know.
 *
 * - **Mode.** [DubModeSettings.enabled] drives [DubHub.enabled]. Turning it on or off while the player is prepared
 *   re-prepares the player at the same position, because both the float output and the AudioTrack's buffer size are
 *   only chosen when a stream is configured; turning it off also lets go of every pad and ends a DUB STOP ring out
 *   (the dry signal comes back, no pause or seek follows).
 * - **Settings.** The stored setup ([DubSetupRepository.setup], previews included) and the echo's tempo go to
 *   [DubHub.params] as one snapshot. The tempo is the beat grid's at the current position, or the TAP tempo while one
 *   is set (tapped against what is heard, so divided by the playback speed into source time), or [DubStage.DEFAULT_BPM].
 * - **Bypass.** A session on a remote receiver ([Player.getDeviceInfo] of the session player) bypasses the stage; the
 *   sink reports what it decided for each stream, and [state] says why the stage is not running.
 * - **Release all.** On a pause for audio focus loss, and from the screen, every pad, kill and the filter let go.
 * - **DUB STOP** ([stop], design §3.5): while the stage hosts the stream and the player plays, STOP lets a held DJ loop
 *   go, mutes the dry signal ([DubHub.dryMuted]) and lets the tail ring out with the player running; when the sink
 *   reports the tail over ([DubHubListener.onTailDone]), or after [STOP_CAP_MS] at the latest, the player pauses and
 *   goes back to the position heard when STOP was pressed (the presented position, never the decoder's). Bypassed or
 *   paused, STOP is a plain pause. A pause from anywhere else (headset, notification, focus) is the player's own,
 *   immediate as always; during a ring out it ends it. PLAY during a ring out brings the dry signal back.
 * - **Crossfade.** [holdsCrossfade] is true while DUB mode is on: the stage and its tails belong to one player.
 *
 * Main thread, except the [DubHubListener] callbacks, which post to the attached scope.
 */
@Singleton
class DubController @Inject constructor(
    private val dubMode: DubModeSettings,
    private val setups: DubSetupRepository,
    private val analyses: DjTrackAnalyses,
    private val loops: DjLoopController,
) : DubStage, Player.Listener {

    internal val hub = DubHub()

    private val stageState = MutableStateFlow(DubStageState())
    override val state: StateFlow<DubStageState> = stageState.asStateFlow()

    /** No crossfade into the next song while DUB mode is on. */
    val holdsCrossfade: Flow<Boolean> get() = dubMode.enabled

    private var player: Player? = null
    private var sessionPlayer: Player? = null
    private var scope: CoroutineScope? = null
    private val jobs = ArrayList<Job>()
    private var watching: Job? = null

    private var stopJob: Job? = null
    private var stopItemIndex = 0
    private var stopPositionMs = 0L

    private val gridBpm = MutableStateFlow<Double?>(null)
    private val tapBpm = MutableStateFlow<Double?>(null)
    private var lastTapNanos = 0L
    private val tapTempo = TapTempo { lastTapNanos }

    private val hubListener = object : DubHubListener {
        override fun onHosting(decision: DubHostingDecision) {
            scope?.launch { showHosting(decision) }
        }

        override fun onTailDone() {
            scope?.launch { tailDone() }
        }
    }

    /** Starts serving [player] (the service's local player); [session] is the player the session uses (maybe routed). */
    fun attach(player: Player, scope: CoroutineScope, session: Player = player) {
        detach()
        this.player = player
        this.sessionPlayer = session
        this.scope = scope
        hub.listener = hubListener
        player.addListener(this)
        if (session !== player) session.addListener(this)
        updateRemote(session.deviceInfo)
        jobs += scope.launch {
            var first = true
            dubMode.enabled.collect { on ->
                val changed = hub.enabled != on
                // A ring out still pending would pause the player and seek back after DUB mode is gone.
                if (!on) cancelStop()
                hub.enabled = on
                stageState.update { it.copy(enabled = on, bypass = if (on) it.bypass else null) }
                if (!on) releaseAll()
                if (changed) watch(this@DubController.player?.currentMediaItem)
                if (!first && changed) reprepare()
                first = false
            }
        }
        jobs += scope.launch {
            combine(setups.setup, tempo()) { setup, clock -> setup to clock }.collect { (setup, clock) ->
                hub.params = DubParams.of(setup, clock.first)
                stageState.update { it.copy(bpm = hub.params.bpm, clockSource = clock.second) }
            }
        }
    }

    /** Lets go of the player. */
    fun detach() {
        stopJob?.cancel()
        stopJob = null
        hub.dryMuted = false
        stageState.update { it.copy(stopping = false) }
        player?.removeListener(this)
        sessionPlayer?.let { if (it !== player) it.removeListener(this) }
        jobs.forEach { it.cancel() }
        jobs.clear()
        watching?.cancel()
        watching = null
        player = null
        sessionPlayer = null
        scope = null
        hub.listener = null
        hub.enabled = false
        hub.releaseAll()
    }

    // --- DubStage ---------------------------------------------------------------------------------------------------

    override fun meters(): DubMeters = DubMeters(hub.peak, hub.gainReductionDb)

    override fun setPad(pad: DubPadKind, engaged: Boolean, uptimeNanos: Long) {
        if (!hub.enabled) return
        hub.post(DubEvent(pad, engaged, uptimeNanos))
    }

    override fun setFilter(x: Float, y: Float) {
        hub.filterX = if (x.isNaN()) 0f else x.coerceIn(-1f, 1f)
        hub.filterY = if (y.isNaN()) 0f else y.coerceIn(0f, 1f)
    }

    override fun setKilled(way: PreampWay, killed: Boolean) {
        val bit = 1 shl way.ordinal
        hub.kills = if (killed) hub.kills or bit else hub.kills and bit.inv()
    }

    override fun tap(uptimeNanos: Long) {
        lastTapNanos = uptimeNanos
        val hundredths = tapTempo.tap() ?: return
        val speed = player?.playbackParameters?.speed?.takeIf { it > 0f } ?: 1f
        tapBpm.value = hundredths / HUNDREDTHS / speed
    }

    override fun clearTap() {
        tapTempo.reset()
        tapBpm.value = null
    }

    override fun stop() {
        val player = player ?: return
        val scope = scope
        if (scope == null || !hub.enabled || !stageState.value.hosted || !player.playWhenReady) {
            player.pause()
            return
        }
        if (stopJob != null) return
        loops.loop.value.let { if (it.looping || it.armed || it.markingIn) loops.clear() }
        stopItemIndex = player.currentMediaItemIndex
        stopPositionMs = player.currentPosition
        hub.dryMuted = true
        stageState.update { it.copy(stopping = true) }
        stopJob = scope.launch {
            delay(STOP_CAP_MS)
            finishStop(pause = true)
        }
    }

    override fun play() {
        if (stopJob != null) {
            cancelStop()
            return
        }
        player?.play()
    }

    override fun releaseAll() {
        hub.kills = 0
        hub.filterX = 0f
        hub.releaseAll()
    }

    // --- The player -------------------------------------------------------------------------------------------------

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = watch(mediaItem)

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (playWhenReady) return
        if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) releaseAll()
        // A pause from outside during the ring out: the player already stopped; go back to where STOP was pressed.
        if (stopJob != null) finishStop(pause = false)
    }

    override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) {
        sessionPlayer?.let { updateRemote(it.deviceInfo) }
    }

    private fun updateRemote(info: DeviceInfo) {
        val remote = info.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE
        hub.remoteRouted = remote
        stageState.update {
            when {
                remote -> it.copy(hosted = false, bypass = DubBypass.REMOTE_RECEIVER)
                it.bypass == DubBypass.REMOTE_RECEIVER -> it.copy(bypass = null)
                else -> it
            }
        }
    }

    private fun showHosting(decision: DubHostingDecision) {
        stageState.update {
            when (decision) {
                is DubHostingDecision.Hosted -> it.copy(hosted = true, bypass = null)
                is DubHostingDecision.Bypassed -> it.copy(hosted = false, bypass = if (hub.remoteRouted) DubBypass.REMOTE_RECEIVER else decision.reason)
            }
        }
    }

    private fun tailDone() {
        if (stopJob != null) finishStop(pause = true)
    }

    private fun finishStop(pause: Boolean) {
        val job = stopJob ?: return
        stopJob = null
        job.cancel()
        val player = player
        if (player != null) {
            if (pause) player.pause()
            player.seekTo(stopItemIndex, stopPositionMs)
        }
        hub.dryMuted = false
        stageState.update { it.copy(stopping = false) }
    }

    private fun cancelStop() {
        stopJob?.cancel()
        stopJob = null
        hub.dryMuted = false
        stageState.update { it.copy(stopping = false) }
    }

    /** Plays on at the same position with a newly configured stream (float or not, the DJ buffer or not). */
    private fun reprepare() {
        val player = player ?: return
        if (player.playbackState == Player.STATE_IDLE) return
        player.stop()
        player.prepare()
    }

    // --- The tempo --------------------------------------------------------------------------------------------------

    /**
     * The grid of the song playing, only while DUB mode is on (the beat grid at the position the song is at): with DUB
     * mode off no analysis is read.
     */
    private fun watch(item: MediaItem?) {
        watching?.cancel()
        watching = null
        gridBpm.value = null
        if (!hub.enabled) return
        val uri = item?.let { (it.requestMetadata.mediaUri ?: it.localConfiguration?.uri)?.toString() } ?: return
        val scope = scope ?: return
        watching = scope.launch {
            analyses.analysisOf(uri).collect { analysis ->
                val grid = analysis?.effectiveGrid
                gridBpm.value = grid?.bpmAt((player?.currentPosition ?: 0L).toDouble())
            }
        }
    }

    private fun tempo(): Flow<Pair<Double, DubClockSource>> = combine(gridBpm, tapBpm) { grid, tap ->
        when {
            tap != null -> tap to DubClockSource.TAP
            grid != null && grid > 0.0 -> grid to DubClockSource.GRID
            else -> DubStage.DEFAULT_BPM to DubClockSource.NONE
        }
    }.distinctUntilChanged()

    companion object {
        /** DUB STOP waits at most this long for the tail (the sink's own cap is 8 s of audio; this covers a stall). */
        const val STOP_CAP_MS: Long = 9_000L

        private const val HUNDREDTHS = 100.0
    }
}
