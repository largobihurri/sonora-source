package io.sonora.core.audiodsp.dub

import kotlin.math.PI
import kotlin.math.abs

/** Loop states and buffer writes below this (about -240 dBFS) are flushed to zero. */
const val DENORMAL_THRESHOLD = 1e-12f

/**
 * Flushes tiny values to exact zero. Decaying tails would otherwise sit in the denormal range (slow on many CPUs; ARM64
 * does not flush by default) and silence in would never become exact silence out.
 */
fun flushDenormal(x: Float): Float = if (abs(x) < DENORMAL_THRESHOLD) 0f else x

internal const val TWO_PI_F = (2.0 * PI).toFloat()

/**
 * DC blocker: the one-pole high-pass `y = x - x1 + R * y1` with `R = 1 - 2 pi fc / fs` (a pole close to z = 1).
 *
 * In a feedback loop it keeps asymmetric saturation, or DC at the input, from accumulating (it would eat headroom and
 * thump on level changes). The output state is denormal-flushed. A default instance is prepared for 5 Hz at 48 kHz.
 * Real-time safe; not thread-safe.
 */
class DcBlocker {
    private var coefficient = 1f - TWO_PI_F * DEFAULT_CUTOFF_HZ / 48_000f
    private var previousInput = 0f
    private var previousOutput = 0f

    /** Non-positive or NaN sample rates are ignored. The coefficient is kept in [0, 1). */
    fun prepare(cutoffHz: Float, sampleRate: Float) {
        if (!(sampleRate > 0f)) return
        val r = 1f - TWO_PI_F * cutoffHz / sampleRate
        coefficient = if (r >= 0f) minOf(r, 1f - 1e-7f) else 0f
    }

    fun process(x: Float): Float {
        val y = flushDenormal(x - previousInput + coefficient * previousOutput)
        previousInput = x
        previousOutput = y
        return y
    }

    fun reset() {
        previousInput = 0f
        previousOutput = 0f
    }

    companion object {
        const val DEFAULT_CUTOFF_HZ = 5f
    }
}

/**
 * Soft saturator `x (27 + x^2) / (27 + 9 x^2)` on x clamped to [-3, 3]: a rational tanh approximation with slope 1 near
 * zero (transparent echoes), reaching exactly +-1 with zero slope at +-3 (smooth, musical compression), for one
 * division instead of a `tanh`. NaN maps to silence, so one bad sample can never poison a loop.
 */
object SoftSaturator {
    /** The largest output magnitude. */
    const val BOUND = 1f

    fun process(x: Float): Float {
        if (x.isNaN()) return 0f
        // Clamping first keeps the curve on its monotonic [-3, 3] segment, where it ends at exactly +-1.
        val c = x.coerceIn(-3f, 3f)
        val c2 = c * c
        return c * (27f + c2) / (27f + 9f * c2)
    }
}

/**
 * Soft-knee limiter for a held (FREEZE) loop: exactly unity for |x| <= [KNEE] (about -3 dBFS), so a loop at feedback
 * 1.0 keeps its level at any realistic program level, and above it `KNEE + d R / (R + d)` with `d = |x| - KNEE` and
 * `R = BOUND - KNEE`, which joins the line with the same value and slope and approaches +-[BOUND], never beyond.
 * One division, like [SoftSaturator]. NaN maps to silence; infinities map to +-[BOUND].
 */
object KneeLimiter {
    /** Below this magnitude the limiter is the identity. */
    const val KNEE = 0.7f

    /** The largest output magnitude. */
    const val BOUND = 1f

    private const val RANGE = BOUND - KNEE

    /** Larger excesses all land within float rounding of the bound; capping them keeps infinity out of the division. */
    private const val MAX_EXCESS = 1e6f

    fun process(x: Float): Float {
        if (x.isNaN()) return 0f
        val a = abs(x)
        if (a <= KNEE) return x
        val d = minOf(a - KNEE, MAX_EXCESS)
        val y = if (a == Float.POSITIVE_INFINITY) BOUND else minOf(KNEE + d * RANGE / (RANGE + d), BOUND)
        return if (x < 0f) -y else y
    }
}
