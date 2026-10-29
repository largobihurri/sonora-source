package io.sonora.core.watchsync.remote

import kotlin.math.abs
import kotlin.math.sign

/**
 * The phone's guard on the remote volume: one message moves at most [maxStepsPerMessage] steps, and raising is held to
 * [maxRaisePerWindow] steps in any [windowMs], so even a runaway or hostile sender cannot ramp the volume up. Lowering is
 * never held back. Not thread safe: call it from one thread (the phone's main thread).
 */
class VolumeStepLimiter(
    private val maxStepsPerMessage: Int = RemoteLimits.MAX_VOLUME_STEPS_PER_MESSAGE,
    private val maxRaisePerWindow: Int = RemoteLimits.MAX_VOLUME_RAISE_PER_WINDOW,
    private val windowMs: Long = RemoteLimits.VOLUME_WINDOW_MS,
    private val now: () -> Long,
) {
    private val raises = ArrayDeque<Long>()

    /** How many of the [steps] asked for may be applied now (same sign, never more in size). */
    fun allow(steps: Int): Int {
        if (steps == 0) return 0
        val capped = minOf(abs(steps), maxStepsPerMessage)
        if (steps < 0) return -capped
        val time = now()
        while (raises.isNotEmpty() && time - raises.first() >= windowMs) raises.removeFirst()
        val granted = minOf(capped, maxRaisePerWindow - raises.size).coerceAtLeast(0)
        repeat(granted) { raises.addLast(time) }
        return granted * steps.sign
    }
}
