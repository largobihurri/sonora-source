package io.sonora.feature.dj.waveform

import io.sonora.core.domain.playback.ClockSample
import io.sonora.core.domain.playback.PlaybackClock
import io.sonora.core.domain.playback.PositionSmoother

/**
 * The position the DJ screen draws at, from the player's per-frame [clock] when it can be trusted, else from the slow
 * position the UI already has (the media controller's, refreshed every 250 ms).
 *
 * The clock is trusted when [PlaybackClock.read] succeeds, reports a position, and is on the queue item the screen
 * shows: right after a track change the player can run ahead of the UI state, and drawing the new track's position
 * over the old track's waveform would be wrong. The items match when [ClockSample.queueItemId] equals the screen's id,
 * or when the clock reads [ClockSample.NO_ITEM] and the screen's id is negative: the queue gives entries queued
 * without an item id (by another controller) ids counting down from -1, while the clock reads every such entry as
 * NO_ITEM. Any fallback restarts the [smoother], so a later good reading is taken as it is instead of slewed from a
 * stale prediction.
 *
 * With [stats], every playing frame is counted for the `SonoraDjClock` log ([DjClockStats]): the read, why it failed,
 * the smoother's snap and the drawn position. Owns one [ClockSample], so reading allocates nothing. Main thread only
 * (the clock's own rule).
 */
class DjPositionSource(
    private val clock: PlaybackClock,
    private val smoother: PositionSmoother = PositionSmoother(),
    val stats: DjClockStats? = null,
) {
    private val sample = ClockSample()

    /** The position (ms) to draw the playing frame timed [frameNanos] at. */
    fun frame(frameNanos: Long, itemId: Long, fallbackMs: Long): Double {
        val drawn = if (readFor(itemId, stats, frameNanos)) {
            smoother.advance(frameNanos, sample).also { stats?.onSnap(smoother.lastSnap) }
        } else {
            smoother.reset()
            fallbackMs.toDouble()
        }
        stats?.onFrame(frameNanos, drawn)
        return drawn
    }

    /** One exact read for a still (paused) screen: the clock's position as it is, or [fallbackMs]. Not counted. */
    fun still(itemId: Long, fallbackMs: Long): Double {
        smoother.reset()
        val ok = readFor(itemId, counts = null, frameNanos = 0L)
        return if (ok) sample.positionMs.toDouble() else fallbackMs.toDouble()
    }

    /** Forgets the smoothing (the screen starts again). */
    fun reset() {
        smoother.reset()
    }

    private fun readFor(itemId: Long, counts: DjClockStats?, frameNanos: Long): Boolean {
        if (!clock.read(sample)) {
            counts?.onReadFailed(clock.lastFailure)
            return false
        }
        val clockItem = sample.queueItemId
        if (clockItem != itemId && !(clockItem == ClockSample.NO_ITEM && itemId < 0)) {
            counts?.onItemMismatch(uiItem = itemId, clockItem = clockItem)
            return false
        }
        if (sample.positionMs < 0) {
            counts?.onNoPosition()
            return false
        }
        counts?.onReadOk(sample.positionMs, sample.speed, frameNanos)
        return true
    }
}
