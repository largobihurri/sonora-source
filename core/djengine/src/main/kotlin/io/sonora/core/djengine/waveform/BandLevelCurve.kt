package io.sonora.core.djengine.waveform

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow

/**
 * Maps a band's peak amplitude (linear, 1.0 = digital full scale) to a column byte `0..255`:
 * `level = round(255 * min(1, |amplitude| / ceiling) ^ exponent)`.
 *
 * Pure and stateless, so the calibration against rekordbox's waveforms can tune it without touching the filters:
 * - [ceiling] is the amplitude that maps to 255; anything louder saturates. The default 1.0 is digital full scale,
 *   so a full-scale sine inside a band's passband reads close to 255 (a little below in the Bessel skirts).
 * - [exponent] bends the curve: 1.0 is linear in amplitude (halving the amplitude, -6 dB, halves the level); below 1
 *   lifts quiet columns, above 1 suppresses them.
 *
 * Rounding is half up, so the mapping is deterministic and monotonic. A NaN amplitude gives 0.
 */
class BandLevelCurve(val ceiling: Double = 1.0, val exponent: Double = 1.0) {

    init {
        require(ceiling.isFinite() && ceiling > 0) { "ceiling must be positive: $ceiling" }
        require(exponent.isFinite() && exponent > 0) { "exponent must be positive: $exponent" }
    }

    /** The column byte (0..255) for a band [amplitude]; the sign is ignored. */
    fun level(amplitude: Double): Int {
        val ratio = abs(amplitude) / ceiling
        if (ratio.isNaN() || ratio <= 0.0) return 0
        if (ratio >= 1.0) return MAX_LEVEL
        val shaped = if (exponent == 1.0) ratio else ratio.pow(exponent)
        return floor(MAX_LEVEL * shaped + 0.5).toInt().coerceIn(0, MAX_LEVEL)
    }

    companion object {
        const val MAX_LEVEL: Int = 255

        /** Linear in amplitude, full scale at 255. */
        val DEFAULT: BandLevelCurve = BandLevelCurve()
    }
}
