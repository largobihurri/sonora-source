package io.sonora.feature.dub.controls

import kotlin.math.ln
import kotlin.math.pow

/**
 * The corner frequencies of a preamp way's high-pass and low-pass (design §10.1): 20 Hz to 20 kHz on a log map of a
 * 0..1 position, the high-pass at or below the low-pass. A high-pass at 20 Hz or a low-pass at 20 kHz is off (a true
 * bypass in the DSP).
 */
object CornerMapping {
    const val MIN_HZ = 20f
    const val MAX_HZ = 20_000f

    /** How close to an end of the range still counts as that end (the rounding of a log round trip). */
    private const val OFF_TOLERANCE = 1e-4f

    /** The corner at [position]; NaN reads as the bottom. */
    fun hzOf(position: Float): Float {
        val p = if (position.isNaN()) 0f else position.coerceIn(0f, 1f)
        return (MIN_HZ * (MAX_HZ.toDouble() / MIN_HZ).pow(p.toDouble())).toFloat().coerceIn(MIN_HZ, MAX_HZ)
    }

    /** The position showing [hz]; out of range clamps to the ends, NaN reads as the bottom. */
    fun positionOf(hz: Float): Float {
        if (hz.isNaN()) return 0f
        val f = hz.coerceIn(MIN_HZ, MAX_HZ)
        return (ln(f.toDouble() / MIN_HZ) / ln(MAX_HZ.toDouble() / MIN_HZ)).toFloat().coerceIn(0f, 1f)
    }

    /** [hz] moved by [deltaPosition] of the whole range along the log map; NaN moves nothing. */
    fun dragBy(hz: Float, deltaPosition: Float): Float {
        if (deltaPosition.isNaN()) return hz
        return hzOf(positionOf(hz) + deltaPosition)
    }

    /** A high-pass at [hz], kept in range and at or below the way's [lowPassHz]. */
    fun clampHighPass(hz: Float, lowPassHz: Float): Float = hz.coerceIn(MIN_HZ, MAX_HZ).coerceAtMost(lowPassHz.coerceIn(MIN_HZ, MAX_HZ))

    /** A low-pass at [hz], kept in range and at or above the way's [highPassHz]. */
    fun clampLowPass(hz: Float, highPassHz: Float): Float = hz.coerceIn(MIN_HZ, MAX_HZ).coerceAtLeast(highPassHz.coerceIn(MIN_HZ, MAX_HZ))

    fun isHighPassOff(hz: Float): Boolean = hz <= MIN_HZ * (1f + OFF_TOLERANCE)

    fun isLowPassOff(hz: Float): Boolean = hz >= MAX_HZ * (1f - OFF_TOLERANCE)
}
