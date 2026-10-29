package io.sonora.core.playback.loop

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import io.sonora.core.djmodel.DjAnalysis
import io.sonora.core.djmodel.DjModeSettings
import io.sonora.core.djmodel.DjQuantizeSettings
import io.sonora.core.djmodel.DjTrackAnalyses
import io.sonora.core.djmodel.loop.LoopQuantizer
import io.sonora.core.domain.playback.AbLoop
import io.sonora.core.dubmodel.DubModeSettings
import io.sonora.core.domain.playback.ClockSample
import io.sonora.core.domain.playback.DjLoopController
import io.sonora.core.domain.playback.DjLoopState
import io.sonora.core.domain.playback.LoopClockSource
import io.sonora.core.playback.QueueMediaId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The DJ loop of the session's player (the local ExoPlayer, never Cast's), driven from the DJ screen. The buttons become
 * [LoopRequest]s that the audio thread resolves at the frame being heard ([LoopAudioSink]); this class keeps what the
 * audio thread cannot know: the effective beat grid of the track and Quantize ([LoopContext]), when the loop must go
 * (a seek, another song, DJ mode off), and what the screen shows ([loop]).
 *
 * - **State.** [loop] follows the audio thread's snapshots (the times are the playback clock's: the same time the
 *   waveform is drawn in), and [available] is false whenever the stream playing cannot host loops.
 * - **Clears** on a seek that did not come from here and on a change of song. A seek this class makes itself (below)
 *   keeps the loop.
 * - **Not in memory.** When Loop Out comes after the audio at In has left the capture memory, the sink asks for a seek
 *   to In ([LoopHubListener]'s `onNeedsSeek`); the armed loop then captures and wraps by itself.
 * - **Too long.** A loop that does not fit the capture memory (about 170 s of 48 kHz stereo, 85 s of float) falls back
 *   to a seek based loop like the A-B loop ([AbLoop]): it jumps back to In just before Out. That one has the gap of a
 *   seek and is not sample-accurate.
 *
 * - **DUB mode.** The DUB layout keeps the DJ deck's audio services: loops, the grid and Quantize stay on while either
 *   DJ mode or DUB mode is ([deckOn]); the two layouts are never on together.
 *
 * Main thread, except the [LoopHubListener] callbacks and [readInto], which are safe from any thread.
 *
 * The clock reads the loop being heard through [readInto] ([io.sonora.core.playback.clock.LivePlaybackClock]).
 */
@Singleton
class LoopController @Inject constructor(
    private val analyses: DjTrackAnalyses,
    private val quantize: DjQuantizeSettings,
    private val djMode: DjModeSettings,
    private val dubMode: DubModeSettings,
) : DjLoopController, LoopClockSource, Player.Listener {

    internal val hub = LoopHub()

    private val hubListener = object : LoopHubListener {
        override fun onSnapshot(snapshot: LoopSnapshot) = publish()

        override fun onNeedsSeek(inMs: Double) = needsSeek(inMs)

        override fun onTooLong(inMs: Double, outMs: Double) = tooLong(inMs, outMs)

        override fun onRefused(refusal: LoopRefusal) = Unit
    }

    private val state = MutableStateFlow(DjLoopState.NONE)
    private val publishLock = Any()
    override val loop: StateFlow<DjLoopState> = state.asStateFlow()

    /** Whether a loop (or a mark for one) should hold the playback as it is: no crossfade into the next song meanwhile. */
    internal val holdsPlayback: Flow<Boolean> =
        state.map { it.markingIn || it.armed || it.looping }.distinctUntilChanged()

    // Written on the main thread, read by `publish` from the playback thread too.
    @Volatile
    private var player: Player? = null
    private var scope: CoroutineScope? = null
    private var watching: Job? = null
    private var settings: Job? = null

    /** Seeks this class asked for and has not heard back yet: they are not the user's. */
    private var ownSeeks = 0
    private var lastNeedsSeekMs: Long? = null

    /** The seek based loop, running or left (Exit keeps it for Reloop), in the clock's ms. */
    @Volatile
    private var seekLoop: Pair<Long, Long>? = null

    @Volatile
    private var seekLoopJob: Job? = null

    override fun readInto(into: ClockSample) = hub.readInto(into)

    /** Whether the deck's audio services run: in DJ mode, and in DUB mode, which keeps them. */
    private val deckOn: Flow<Boolean> = combine(djMode.enabled, dubMode.enabled) { dj, dub -> dj || dub }.distinctUntilChanged()

    /** Starts serving [player] (the service's local player), with work running on [scope] (the main thread). */
    fun attach(player: Player, scope: CoroutineScope) {
        detach()
        this.player = player
        this.scope = scope
        hub.listener = hubListener
        player.addListener(this)
        DjLoopLog.v { "attached; DJ mode ${if (djMode.enabled.value) "on" else "off"}" }
        settings = scope.launch {
            deckOn.collect { on ->
                val wasOn = hub.enabled
                hub.enabled = on
                DjLoopLog.v { "DJ mode ${if (on) "on" else "off"}" }
                if (wasOn && !on) clearAll()
                publish()
            }
        }
        watch(player.currentMediaItem)
    }

    /** Lets go of the player; the loop goes with it. */
    fun detach() {
        if (player != null) DjLoopLog.v { "detached" }
        player?.removeListener(this)
        watching?.cancel()
        settings?.cancel()
        seekLoopJob?.cancel()
        watching = null
        settings = null
        seekLoopJob = null
        seekLoop = null
        player = null
        scope = null
        ownSeeks = 0
        hub.listener = null
        hub.enabled = false
        hub.context = LoopContext.NONE
        while (hub.poll() != null) Unit
        state.value = DjLoopState.NONE
    }

    // --- The buttons ----------------------------------------------------------------------------------------------

    override fun loopIn() {
        dropSeekLoop()
        send(LoopRequest.MarkIn)
    }

    override fun loopOut() = send(LoopRequest.MarkOut)

    override fun beatLoop(beats: Double) {
        dropSeekLoop()
        send(LoopRequest.BeatLoop(beats))
    }

    override fun halve() = send(LoopRequest.HalveLoop)

    override fun double() = send(LoopRequest.DoubleLoop)

    override fun exit() {
        if (seekLoopJob != null) {
            seekLoopJob?.cancel()
            seekLoopJob = null
            publish()
            return
        }
        send(LoopRequest.ExitLoop)
    }

    override fun reloop() {
        val remembered = seekLoop
        if (remembered != null && seekLoopJob == null) {
            startSeekLoop(remembered.first, remembered.second)
            return
        }
        send(LoopRequest.Reloop)
    }

    override fun clear() = clearAll()

    override fun engage(gridInMs: Double, gridOutMs: Double) {
        dropSeekLoop()
        send(LoopRequest.Engage(gridInMs, gridOutMs))
    }

    private fun send(request: LoopRequest) {
        if (player == null || !hub.enabled) return
        hub.post(request)
    }

    private fun clearAll() {
        dropSeekLoop()
        if (player != null) hub.post(LoopRequest.ClearLoop)
    }

    // --- The player ------------------------------------------------------------------------------------------------

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        clearAll()
        watch(mediaItem)
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (reason != Player.DISCONTINUITY_REASON_SEEK) return
        if (ownSeeks > 0) {
            ownSeeks--
            return
        }
        clearAll()
    }

    /** Follows the grid of the song playing, and Quantize, while DJ mode is on. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun watch(item: MediaItem?) {
        watching?.cancel()
        val uri = item?.let { (it.requestMetadata.mediaUri ?: it.localConfiguration?.uri)?.toString() }
        val scope = scope
        if (uri == null || scope == null) {
            hub.context = LoopContext.NONE
            return
        }
        watching = scope.launch {
            deckOn
                .flatMapLatest { on ->
                    if (on) combine(analyses.analysisOf(uri), quantize.quantize, ::contextOf) else flowOf(LoopContext.NONE)
                }
                .collect { hub.context = it }
        }
    }

    private fun contextOf(analysis: DjAnalysis?, quantize: Boolean): LoopContext {
        val quantizer = analysis?.let { LoopQuantizer(it.effectiveGrid, it.durationMs.toDouble()) }
        return LoopContext(quantizer, quantize)
    }

    // --- The audio thread -------------------------------------------------------------------------------------------

    private fun needsSeek(inMs: Double) {
        val scope = scope ?: return
        scope.launch {
            val player = player ?: return@launch
            val now = System.nanoTime() / NANOS_PER_MS
            // A seek that does not bring the loop's start back into memory must not repeat for ever.
            if (lastNeedsSeekMs?.let { now - it < NEEDS_SEEK_GUARD_MS } == true) return@launch
            lastNeedsSeekMs = now
            ownSeeks++
            player.seekTo((inMs - SEEK_PRE_ROLL_MS).roundToLong().coerceAtLeast(0L))
        }
    }

    private fun tooLong(inMs: Double, outMs: Double) {
        val scope = scope ?: return
        scope.launch { startSeekLoop(inMs.roundToLong(), outMs.roundToLong()) }
    }

    // --- The seek based loop (fallback) -----------------------------------------------------------------------------

    private fun startSeekLoop(inMs: Long, outMs: Long) {
        val player = player ?: return
        val scope = scope ?: return
        seekLoopJob?.cancel()
        seekLoop = inMs to outMs
        val itemId = player.currentMediaItem?.mediaId?.let { QueueMediaId.parse(it).itemId }
        val span = AbLoop(enabled = true, startMs = inMs, endMs = outMs, itemId = itemId)
        seekLoopJob = scope.launch {
            while (isActive) {
                when (val step = span.nextStep(player.currentPosition)) {
                    is AbLoop.Step.SeekTo -> {
                        ownSeeks++
                        player.seekTo(step.positionMs)
                        delay(AbLoop.MIN_POLL_MS)
                    }
                    is AbLoop.Step.Wait -> delay(step.delayMs)
                }
            }
        }
        publish()
    }

    private fun dropSeekLoop() {
        val had = seekLoop != null || seekLoopJob != null
        seekLoopJob?.cancel()
        seekLoopJob = null
        seekLoop = null
        if (had) publish()
    }

    // --- What the screen shows --------------------------------------------------------------------------------------

    /**
     * Works the state out again from the hub and the player. Called from the playback thread (the sink published) and from
     * the main thread (DJ mode, the seek based loop), so it is one critical section: whichever call runs last has read the
     * newest of what the other published, and the state never goes back to an older reading that nothing would correct.
     */
    private fun publish() = synchronized(publishLock) {
        val snapshot = hub.snapshot
        val seeking = seekLoop
        val next = if (seeking != null) {
            DjLoopState(
                inMs = seeking.first.toDouble(),
                outMs = seeking.second.toDouble(),
                looping = seekLoopJob != null,
                armed = false,
                available = hub.enabled && player != null,
                // The seek based loop lives in the player's time, not the grid's: it cannot be saved.
            )
        } else {
            val marking = snapshot.markedInMs != null
            DjLoopState(
                inMs = snapshot.markedInMs ?: snapshot.loopInMs,
                outMs = if (marking) null else snapshot.loopOutMs,
                looping = snapshot.engaged,
                armed = snapshot.armed,
                available = snapshot.available && hub.enabled && player != null,
                gridInMs = snapshot.markedInGridMs ?: snapshot.loopInGridMs,
                gridOutMs = if (marking) null else snapshot.loopOutGridMs,
            )
        }
        if (next.available != state.value.available) {
            DjLoopLog.v { "loops available=${next.available} (sink hosts=${snapshot.available}, DJ mode=${hub.enabled}, player=${player != null})" }
        }
        state.value = next
    }

    private companion object {
        /** How far before In the seek lands, so In is certainly in the audio that is then captured. */
        const val SEEK_PRE_ROLL_MS = 120L
        const val NEEDS_SEEK_GUARD_MS = 1_000L
        const val NANOS_PER_MS = 1_000_000L
    }
}
