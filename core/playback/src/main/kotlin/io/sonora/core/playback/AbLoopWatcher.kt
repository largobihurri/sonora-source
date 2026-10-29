package io.sonora.core.playback

import androidx.media3.common.C
import androidx.media3.common.Player
import io.sonora.core.domain.playback.AbLoop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Runs the [AbLoop] on the attached player: taps and nudges read its position, duration and queue entry; while both
 * points are set and it plays, its position is checked on [scope] ([AbLoop.nextStep]) and it jumps back to A just
 * before B. A seek outside the span from any controller, or a change of entry, clears the points. Main thread only.
 */
internal class AbLoopWatcher(private val scope: CoroutineScope) : Player.Listener {
    private val _loop = MutableStateFlow(AbLoop())
    val loop: StateFlow<AbLoop> = _loop.asStateFlow()

    private var player: Player? = null
    private var watching: Job? = null
    private var allowed = true

    fun attach(player: Player) {
        detach()
        this.player = player
        player.addListener(this)
        update { it.onItem(player.itemId()) }
    }

    /** Forgets the player (released or disconnected); the points go with it, the mode stays. */
    fun detach() {
        player?.removeListener(this)
        player = null
        update { it.cleared() }
    }

    /**
     * Whether the A-B loop may be used at all. DJ mode has its own sample-accurate loops, so the A-B loop is switched off
     * there: its points and mode go, and its taps do nothing until it is allowed again.
     */
    fun setAllowed(allowed: Boolean) {
        this.allowed = allowed
        if (!allowed) update { AbLoop() }
    }

    fun toggle() {
        if (allowed) update { it.toggled() }
    }

    fun tap() {
        if (!allowed) return
        val player = player ?: return
        update { it.tapped(player.currentPosition.coerceAtLeast(0L), player.knownDuration(), player.itemId()) }
    }

    fun moveStart(positionMs: Long) {
        if (!allowed) return
        val player = player ?: return
        update { it.withStart(positionMs, player.knownDuration()) }
    }

    fun moveEnd(positionMs: Long) {
        if (!allowed) return
        val player = player ?: return
        update { it.withEnd(positionMs, player.knownDuration()) }
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        // The loop's own jump lands on A, inside the span, so it keeps the loop.
        if (reason == Player.DISCONTINUITY_REASON_SEEK) update { it.afterSeek(newPosition.positionMs) }
    }

    override fun onEvents(player: Player, events: Player.Events) = update { it.onItem(player.itemId()) }

    private inline fun update(change: (AbLoop) -> AbLoop) {
        _loop.value = change(_loop.value)
        follow()
    }

    /** Checks the position while the loop is set and playing; stops otherwise. */
    private fun follow() {
        val player = player
        if (player == null || !_loop.value.isLooping || !player.isPlaying) {
            watching?.cancel()
            watching = null
            return
        }
        if (watching?.isActive == true) return
        watching = scope.launch {
            while (isActive) {
                when (val step = _loop.value.nextStep(player.currentPosition)) {
                    is AbLoop.Step.SeekTo -> {
                        player.seekTo(step.positionMs)
                        delay(AbLoop.MIN_POLL_MS) // never spin, even if the position read back lags the seek
                    }
                    is AbLoop.Step.Wait -> delay(step.delayMs)
                }
            }
        }
    }

    private fun Player.knownDuration(): Long = duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L

    private fun Player.itemId(): Long? = currentMediaItem?.mediaId?.let { QueueMediaId.parse(it).itemId }
}
