package io.sonora.feature.dj.waveform

import kotlin.math.abs

/**
 * Keeps a released scrub on screen until the player gets there. A seek travels through the media session before the
 * player (and so the clock) reports the new position; drawing the clock at once would show the old position for a
 * few frames, a jump back and forth. So after [hold] the drawn position is the target until the reported one comes
 * within [toleranceMs] of it, or [timeoutNanos] passes (a seek that never lands must not freeze the view).
 *
 * Plain fields, allocation-free, main thread only: read in the draw phase with the frame's time.
 */
class SeekHold(
    private val toleranceMs: Double = DEFAULT_TOLERANCE_MS,
    private val timeoutNanos: Long = DEFAULT_TIMEOUT_NANOS,
) {
    /** Whether a target is being held. */
    var active: Boolean = false
        private set

    private var targetMs = 0.0
    private var untilNanos = 0L

    /** Draws [targetMs] from now ([nowNanos], a monotonic clock) until the player reaches it. */
    fun hold(targetMs: Double, nowNanos: Long) {
        this.targetMs = targetMs
        untilNanos = nowNanos + timeoutNanos
        active = true
    }

    /** Drops the held target (a new touch begins). */
    fun cancel() {
        active = false
    }

    /** The position to draw: the held target while the reported [positionMs] has not reached it, else [positionMs]. */
    fun resolve(positionMs: Double, nowNanos: Long): Double {
        if (!active) return positionMs
        if (nowNanos - untilNanos >= 0 || abs(positionMs - targetMs) <= toleranceMs) {
            active = false
            return positionMs
        }
        return targetMs
    }

    companion object {
        /** A reported position this close (ms) to the target means the seek landed. */
        const val DEFAULT_TOLERANCE_MS: Double = 150.0

        /** The longest a target is held (ns). */
        const val DEFAULT_TIMEOUT_NANOS: Long = 600_000_000L
    }
}
