package io.sonora.core.audiodsp.preamp

import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.log10
import kotlin.math.pow

/**
 * Taper of a preamp way fader: position 0..1 to linear gain, from silence (-inf dB) to +6 dB.
 *
 * - Below the detent a cubic taper runs from silence at 0 to unity at the detent edge (-18 dB halfway down).
 * - Around [DETENT_POSITION] a window of +-[DETENT_HALF_WIDTH] maps to exactly 1 (0 dB), so a fader dropped near the
 *   detent is unity, bit for bit.
 * - Above the detent the gain rises linearly in dB to +6 dB at the top.
 */
object PreampFader {
    const val MAX_GAIN_DB = 6f
    const val DETENT_POSITION = 0.75f
    const val DETENT_HALF_WIDTH = 0.02f

    /** Linear gain at the top of the fader, +6 dB. */
    val MAX_GAIN: Float = 10f.pow(MAX_GAIN_DB / 20f)

    private const val LOWER_EDGE = DETENT_POSITION - DETENT_HALF_WIDTH
    private const val UPPER_EDGE = DETENT_POSITION + DETENT_HALF_WIDTH

    fun gainForPosition(position: Float): Float {
        if (position.isNaN()) return 0f
        val p = position.coerceIn(0f, 1f)
        if (abs(p - DETENT_POSITION) <= DETENT_HALF_WIDTH) return 1f
        if (p > DETENT_POSITION) {
            val t = (p - UPPER_EDGE) / (1f - UPPER_EDGE)
            return 10f.pow(MAX_GAIN_DB * t / 20f)
        }
        val t = p / LOWER_EDGE
        return t * t * t
    }

    /** The fader position that gives [gain] (clamped to 0..+6 dB); unity gives the detent itself. */
    fun positionForGain(gain: Float): Float {
        if (gain.isNaN() || gain <= 0f) return 0f
        if (gain == 1f) return DETENT_POSITION
        if (gain > 1f) {
            val db = 20f * log10(gain.coerceAtMost(MAX_GAIN))
            return UPPER_EDGE + db / MAX_GAIN_DB * (1f - UPPER_EDGE)
        }
        return cbrt(gain) * LOWER_EDGE
    }
}
