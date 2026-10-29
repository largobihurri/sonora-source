package io.sonora.player.wear.remote

import io.sonora.core.watchsync.remote.RemoteLimits
import kotlin.math.abs

/**
 * Turns the crown's volume steps into few, small messages: steps wait up to [intervalMs] and go out together, opposite steps
 * cancel, one message carries at most [maxPerMessage], and at most [maxPending] steps are kept waiting, so a long spin cannot
 * queue up a ramp that keeps going after the crown stops. The first step after a pause goes out at once. Not thread safe.
 */
class VolumeStepCoalescer(
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    private val maxPerMessage: Int = RemoteLimits.MAX_VOLUME_STEPS_PER_MESSAGE,
    private val maxPending: Int = MAX_PENDING,
) {
    private var pending = 0
    private var lastSentAt: Long? = null

    val hasPending: Boolean get() = pending != 0

    /** Adds the crown's [steps] (negative lowers). */
    fun add(steps: Int) {
        pending = (pending + steps).coerceIn(-maxPending, maxPending)
    }

    /** The steps to send at [now]: none while the last message is less than the interval old. */
    fun take(now: Long): Int {
        if (pending == 0) return 0
        val last = lastSentAt
        if (last != null && now - last < intervalMs) return 0
        val steps = pending.coerceIn(-maxPerMessage, maxPerMessage)
        pending -= steps
        lastSentAt = now
        return steps
    }

    fun clear() {
        pending = 0
        lastSentAt = null
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 120L
        const val MAX_PENDING = 12
    }
}
