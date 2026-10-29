package io.sonora.core.audiodsp.dub

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/**
 * One-pole parameter smoother: `value += k * (target - value)` once per frame.
 *
 * Used for every control that would otherwise zipper or click (delay time, return level, mix). An exponential
 * approach is the cheapest smoother that never overshoots and reacts at once to a new target, even in the middle of a
 * previous glide.
 *
 * The value snaps to the target once it is within [SNAP_RELATIVE] (relative to `max(1, |target|)`), so a settled
 * parameter is bit-exact (mix 0 yields exactly the dry signal). State is Double: in Float, `value += k * diff` stalls
 * once `k * diff` drops below half an ulp, which for a delay of about 36000 frames and k about 4e-4 leaves the read
 * head several frames short.
 *
 * Real-time safe: no allocation, locks or exceptions after construction. Not thread-safe.
 */
class Smoother {
    private var current = 0.0
    private var coefficient = 1.0

    /** The value [next] is heading for. */
    var target: Double = 0.0
        private set

    /** The last value returned by [next] (or set by [snap]). */
    val value: Double get() = current

    /** True when the value sits exactly on the target. */
    val isSettled: Boolean get() = current == target

    /**
     * Time constant tau: about 63 % of a step after tau, about 95 % after 3 tau. A non-positive or NaN product of
     * time constant and sample rate makes the smoother jump at once.
     */
    fun configure(timeConstantSeconds: Double, sampleRate: Double) {
        val frames = timeConstantSeconds * sampleRate
        coefficient = if (frames > 0.0) 1.0 - exp(-1.0 / frames) else 1.0
    }

    fun setTarget(target: Double) {
        this.target = target
    }

    /** Jumps to the target (prepare, reset, or a settled state for tests). */
    fun snap() {
        current = target
    }

    fun next(): Double {
        if (current != target) {
            val next = current + coefficient * (target - current)
            val snapDistance = SNAP_RELATIVE * max(1.0, abs(target))
            // The equality check also catches a (theoretical) stall short of the target.
            current = if (abs(target - next) <= snapDistance || next == current) target else next
        }
        return current
    }

    companion object {
        const val SNAP_RELATIVE = 1e-6
    }
}
